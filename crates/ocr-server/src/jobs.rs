use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

use futures::StreamExt;
use tokio::sync::Mutex;

use crate::state::{AppState, JobProgress};

pub async fn run_chapter_job(
    state: AppState,
    base_url: String,
    pages: Vec<String>,
    user: Option<String>,
    pass: Option<String>,
    context: String,
) {
    let total = pages.len();

    {
        state
            .active_chapter_jobs
            .write()
            .expect("lock poisoned")
            .insert(base_url.clone(), JobProgress { current: 0, total });
    }

    state.active_jobs.fetch_add(1, Ordering::Relaxed);
    tracing::info!("[Job] Started for {} ({} pages)", context, total);

    // Shared atomic counter for tracking completed pages across threads
    let completed_counter = Arc::new(AtomicUsize::new(0));

    // Mutex to ensure only one thread performs the file-save I/O at a time
    let save_lock = Arc::new(Mutex::new(()));

    // Create a stream from the pages
    let stream = futures::stream::iter(pages.into_iter());

    // Process up to 6 pages concurrently
    // "Buffer Unordered" or "For Each Concurrent" automatically fills slots as they free up.
    stream
        .for_each_concurrent(6, |url| {
            let state = state.clone();
            let base_url = base_url.clone();
            let user = user.clone();
            let pass = pass.clone();
            let context = context.clone();
            let completed_counter = completed_counter.clone();
            let save_lock = save_lock.clone();

            async move {
                let cache_key = crate::logic::get_cache_key(&url);

                // 1. Check Cache
                let exists = { state.cache.read().expect("lock").contains_key(&cache_key) };

                if exists {
                    tracing::info!("[Job] Skip (Cached): {url}");
                } else {
                    // 2. Fetch & Process (Network + OCR)
                    // This is the heavy lifting that now happens in parallel
                    match crate::logic::fetch_and_process(&url, user, pass).await {
                        Ok(res) => {
                            tracing::info!("[Job] Processed: {url}");
                            // Write result to in-memory cache
                            let mut w = state.cache.write().expect("lock");
                            w.insert(
                                cache_key,
                                crate::state::CacheEntry {
                                    context: context.clone(),
                                    data: res,
                                },
                            );
                        }
                        Err(err) => {
                            tracing::warn!("[Job] Failed: {url} (Error: {err:?})");
                        }
                    }
                }

                // 3. Update Progress
                let current = completed_counter.fetch_add(1, Ordering::Relaxed) + 1;

                {
                    if let Some(prog) = state
                        .active_chapter_jobs
                        .write()
                        .expect("lock")
                        .get_mut(&base_url)
                    {
                        prog.current = current;
                    }
                }

                // 4. Periodic Save (Thread-Safe)
                // We use try_lock() to skip saving if another thread is already doing it,
                // preventing I/O pile-up.
                if current % 5 == 0 {
                    if let Ok(_guard) = save_lock.try_lock() {
                        state.save_cache();
                    }
                }
            }
        })
        .await;

    // Final Save to ensure everything is persisted
    state.save_cache();

    state.active_jobs.fetch_sub(1, Ordering::Relaxed);

    {
        state
            .active_chapter_jobs
            .write()
            .expect("lock poisoned")
            .remove(&base_url);
    }

    tracing::info!("[Job] Finished for {}", context);
}
