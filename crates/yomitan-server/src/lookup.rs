use crate::state::{AppState, StoredRecord};
use lindera::{
    dictionary::{DictionaryKind, load_dictionary_from_kind},
    mode::Mode,
    segmenter::Segmenter,
    tokenizer::Tokenizer,
};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tracing::{error, info};
use wordbase_api::{DictionaryId, FrequencyValue, Record, RecordEntry, RecordId, Span, Term};

pub struct LookupService {
    tokenizer: Arc<Tokenizer>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct Candidate {
    pub word: String,
    pub _reason: String,
}

impl LookupService {
    pub fn new() -> Self {
        info!("⏳ [Lookup] Initializing Lindera (UniDic)...");
        let dictionary = load_dictionary_from_kind(DictionaryKind::UniDic)
            .expect("Failed to load UniDic dictionary");

        let segmenter = Segmenter::new(Mode::Normal, dictionary, None);
        let tokenizer = Tokenizer::new(segmenter);
        info!("✅ [Lookup] Lindera Initialized.");

        Self {
            tokenizer: Arc::new(tokenizer),
        }
    }

    pub fn search(&self, state: &AppState, text: &str, cursor_offset: usize) -> Vec<RecordEntry> {
        let mut results = Vec::new();
        let mut processed_candidates = HashSet::new();

        // 1. Get DB connection
        let conn = match state.pool.get() {
            Ok(c) => c,
            Err(e) => {
                error!("❌ Failed to get DB connection: {}", e);
                return vec![];
            }
        };

        // 2. Get Dictionary Configs (Priority & Enabled)
        let dict_configs: HashMap<DictionaryId, (bool, i64)> = {
            let dicts = state.dictionaries.read().expect("lock");
            dicts
                .iter()
                .map(|(id, d)| (*id, (d.enabled, d.priority)))
                .collect()
        };

        // 3. Prepare Statement (Fetching dictionary_id too)
        let mut stmt = match conn.prepare("SELECT dictionary_id, json FROM terms WHERE term = ?") {
            Ok(s) => s,
            Err(e) => {
                error!("❌ DB Prepare Error: {}", e);
                return vec![];
            }
        };

        let start_index = self.snap_to_char_boundary(text, cursor_offset);
        if start_index >= text.len() {
            return vec![];
        }

        let search_text = &text[start_index..];
        let chars: Vec<char> = search_text.chars().take(24).collect();

        // Reusable decoder
        let mut decoder = snap::raw::Decoder::new();

        for len in (1..=chars.len()).rev() {
            let substring: String = chars[0..len].iter().collect();
            let candidates = self.generate_candidates(&substring);

            for candidate in candidates {
                if !self.is_valid_candidate(&substring, &candidate.word) {
                    continue;
                }

                if processed_candidates.contains(&candidate.word) {
                    continue;
                }
                processed_candidates.insert(candidate.word.clone());

                let rows = stmt.query_map(rusqlite::params![candidate.word], |row| {
                    let dict_id: i64 = row.get(0)?;
                    let compressed: Vec<u8> = row.get(1)?; // CHANGED: Fetch BLOB
                    Ok((dict_id, compressed))
                });

                if let Ok(mapped_rows) = rows {
                    for row_result in mapped_rows {
                        if let Ok((dict_id_raw, compressed_data)) = row_result {
                            let dict_id = DictionaryId(dict_id_raw);

                            // CHECK ENABLED
                            if let Some((enabled, _)) = dict_configs.get(&dict_id) {
                                if !*enabled {
                                    continue;
                                }
                            }

                            // CHANGED: Decompress -> Deserialize
                            if let Ok(decompressed) = decoder.decompress_vec(&compressed_data) {
                                if let Ok(stored) =
                                    serde_json::from_slice::<StoredRecord>(&decompressed)
                                {
                                    let estimated_len = candidate.word.chars().count();
                                    let term_obj = Term::from_parts(
                                        Some(candidate.word.as_str()),
                                        stored.reading.as_deref(),
                                    )
                                    .unwrap_or_else(|| {
                                        Term::from_headword(candidate.word.clone()).unwrap()
                                    });

                                    let mut freq = 0;
                                    if let Record::YomitanGlossary(g) = &stored.record {
                                        freq = g.popularity;
                                    }

                                    results.push(RecordEntry {
                                        span_bytes: Span {
                                            start: 0,
                                            end: candidate.word.len() as u64,
                                        },
                                        span_chars: Span {
                                            start: 0,
                                            end: estimated_len as u64,
                                        },
                                        source: stored.dictionary_id,
                                        term: term_obj,
                                        record_id: RecordId(0),
                                        record: stored.record.clone(),
                                        profile_sorting_frequency: None,
                                        source_sorting_frequency: Some(FrequencyValue::Rank(freq)),
                                    });
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sort results
        results.sort_by(|a, b| {
            // 1. Sort by Length (Longest Match First)
            let len_cmp = b.span_chars.end.cmp(&a.span_chars.end);
            if len_cmp != std::cmp::Ordering::Equal {
                return len_cmp;
            }

            // 2. Sort by Dictionary Priority (Lower Index = Higher Priority)
            let prio_a = dict_configs.get(&a.source).map(|(_, p)| *p).unwrap_or(999);
            let prio_b = dict_configs.get(&b.source).map(|(_, p)| *p).unwrap_or(999);

            let prio_cmp = prio_a.cmp(&prio_b);
            if prio_cmp != std::cmp::Ordering::Equal {
                return prio_cmp;
            }

            // 3. Sort by Frequency/Popularity
            let get_val = |f: Option<&FrequencyValue>| -> i64 {
                match f {
                    Some(FrequencyValue::Rank(v)) => *v,
                    Some(FrequencyValue::Occurrence(v)) => *v,
                    None => 0,
                }
            };
            get_val(b.source_sorting_frequency.as_ref())
                .cmp(&get_val(a.source_sorting_frequency.as_ref()))
        });

        results
    }

    fn snap_to_char_boundary(&self, text: &str, index: usize) -> usize {
        if index >= text.len() {
            return text.len();
        }
        let mut i = index;
        while i > 0 && !text.is_char_boundary(i) {
            i -= 1;
        }
        i
    }

    fn is_valid_candidate(&self, source: &str, candidate: &str) -> bool {
        if source == candidate {
            return true;
        }
        let source_kanji: Vec<char> = source.chars().filter(|c| self.is_kanji(*c)).collect();
        let cand_kanji: Vec<char> = candidate.chars().filter(|c| self.is_kanji(*c)).collect();

        if !cand_kanji.is_empty() {
            for k in cand_kanji {
                if source_kanji.contains(&k) {
                    return true;
                }
            }
            return false;
        }
        true
    }

    fn is_kanji(&self, c: char) -> bool {
        c >= '\u{4E00}' && c <= '\u{9FFF}'
    }

    fn generate_candidates(&self, text: &str) -> Vec<Candidate> {
        let mut candidates = Vec::new();
        candidates.push(Candidate {
            word: text.to_string(),
            _reason: "Original".to_string(),
        });

        if let Ok(mut tokens) = self.tokenizer.tokenize(text) {
            if let Some(first_token) = tokens.first_mut() {
                let details = first_token.details();
                if details.len() >= 8 {
                    let lemma = &details[7];
                    if *lemma != "*" && *lemma != text {
                        candidates.push(Candidate {
                            word: lemma.to_string(),
                            _reason: "Lindera".to_string(),
                        });
                    }
                }
            }
        }
        candidates
    }
}
