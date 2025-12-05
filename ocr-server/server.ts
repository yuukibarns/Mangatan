// @ts-nocheck
import express from "express";
import multer from "multer";
// Import Core directly to avoid loading 'sharp' references in the main file
import LensCore from "npm:chrome-lens-ocr@^4.1.0/src/core.js";
import { Image } from "imagescript";
import { parse } from "std/flags/mod.ts";
import { resolve, join, dirname } from "std/path/mod.ts";
import { existsSync, ensureDirSync } from "std/fs/mod.ts";
import { Buffer } from "node:buffer";

// --- Configuration & Setup ---

const flags = parse(Deno.args, {
  string: ["ip", "cache-path"],
  default: {
    ip: "127.0.0.1",
    port: 3000,
    "cache-path": Deno.cwd(),
  },
  alias: { p: "port" },
});

const host = flags.ip;
const port = Number(flags.port);
const customCachePath = resolve(flags["cache-path"]);
const CACHE_FILE_PATH = join(customCachePath, "ocr-cache.json");

const app = express();
const lens = new LensCore();
const upload = multer({ dest: join(Deno.cwd(), "uploads/") });

let ocrCache = new Map();
let ocrRequestsProcessed = 0;

// --- Auto-Merge Config ---
const AUTO_MERGE_CONFIG = {
  enabled: true,
  dist_k: 1.2,
  font_ratio: 1.3,
  perp_tol: 0.5,
  overlap_min: 0.1,
  min_line_ratio: 0.5,
  font_ratio_for_mixed: 1.1,
  mixed_min_overlap_ratio: 0.5,
  add_space_on_merge: false,
};

// --- Auto-Merge Logic ---

class UnionFind {
  constructor(size) {
    this.parent = Array.from({ length: size }, (_, i) => i);
    this.rank = Array(size).fill(0);
  }
  find(i) {
    if (this.parent[i] === i) return i;
    return (this.parent[i] = this.find(this.parent[i]));
  }
  union(i, j) {
    const rootI = this.find(i);
    const rootJ = this.find(j);
    if (rootI !== rootJ) {
      if (this.rank[rootI] > this.rank[rootJ]) this.parent[rootJ] = rootI;
      else if (this.rank[rootI] < this.rank[rootJ]) this.parent[rootI] = rootJ;
      else {
        this.parent[rootJ] = rootI;
        this.rank[rootI]++;
      }
      return true;
    }
    return false;
  }
}

function median(data) {
  if (!data || data.length === 0) return 0;
  const sorted = [...data].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[mid - 1] + sorted[mid]) / 2
    : sorted[mid];
}

function _groupOcrData(lines, naturalWidth, naturalHeight, config) {
  if (!lines || lines.length < 2 || !naturalWidth || !naturalHeight) {
    return lines.map((line) => [line]);
  }

  const CHUNK_MAX_HEIGHT = 3000;
  const normScale = 1000 / naturalWidth;

  const processedLines = lines.map((line, index) => {
    const bbox = line.tightBoundingBox;
    const normalizedBbox = {
      x: bbox.x * naturalWidth * normScale,
      y: bbox.y * naturalHeight * normScale,
      width: bbox.width * naturalWidth * normScale,
      height: bbox.height * naturalHeight * normScale,
    };
    normalizedBbox.right = normalizedBbox.x + normalizedBbox.width;
    normalizedBbox.bottom = normalizedBbox.y + normalizedBbox.height;

    const isVertical = normalizedBbox.width <= normalizedBbox.height;
    const fontSize = isVertical ? normalizedBbox.width : normalizedBbox.height;

    return {
      originalIndex: index,
      isVertical,
      fontSize,
      bbox: normalizedBbox,
      pixelTop: bbox.y * naturalHeight,
      pixelBottom: (bbox.y + bbox.height) * naturalHeight,
    };
  });

  processedLines.sort((a, b) => a.pixelTop - b.pixelTop);

  const allGroups = [];
  let currentLineIndex = 0;

  while (currentLineIndex < processedLines.length) {
    let chunkStartIndex = currentLineIndex;
    let chunkEndIndex = processedLines.length - 1;

    if (naturalHeight > CHUNK_MAX_HEIGHT) {
      const chunkTopY = processedLines[chunkStartIndex].pixelTop;
      for (let i = chunkStartIndex + 1; i < processedLines.length; i++) {
        if (processedLines[i].pixelBottom - chunkTopY <= CHUNK_MAX_HEIGHT) {
          chunkEndIndex = i;
        } else {
          break;
        }
      }
    }

    const chunkLines = processedLines.slice(
      chunkStartIndex,
      chunkEndIndex + 1
    );
    const uf = new UnionFind(chunkLines.length);

    const horizontalLines = chunkLines.filter((l) => !l.isVertical);
    const verticalLines = chunkLines.filter((l) => l.isVertical);

    const initialMedianH = median(horizontalLines.map((l) => l.bbox.height));
    const initialMedianW = median(verticalLines.map((l) => l.bbox.width));

    const primaryH = horizontalLines.filter(
      (l) => l.bbox.height >= initialMedianH * config.min_line_ratio
    );
    const primaryV = verticalLines.filter(
      (l) => l.bbox.width >= initialMedianW * config.min_line_ratio
    );

    const robustMedianH =
      median(primaryH.map((l) => l.bbox.height)) || initialMedianH || 20;
    const robustMedianW =
      median(primaryV.map((l) => l.bbox.width)) || initialMedianW || 20;

    for (let i = 0; i < chunkLines.length; i++) {
      for (let j = i + 1; j < chunkLines.length; j++) {
        const lineA = chunkLines[i],
          lineB = chunkLines[j];
        if (lineA.isVertical !== lineB.isVertical) continue;

        const medianForOrientation = lineA.isVertical
          ? robustMedianW
          : robustMedianH;
        const isAPrimary =
          lineA.fontSize >= medianForOrientation * config.min_line_ratio;
        const isBPrimary =
          lineB.fontSize >= medianForOrientation * config.min_line_ratio;

        let fontRatioThreshold = config.font_ratio;
        if (isAPrimary !== isBPrimary) {
          fontRatioThreshold = config.font_ratio_for_mixed;
        }

        const fontRatio = Math.max(
          lineA.fontSize / lineB.fontSize,
          lineB.fontSize / lineA.fontSize
        );
        if (fontRatio > fontRatioThreshold) continue;

        const distThreshold = medianForOrientation * config.dist_k;
        let readingGap, perpOverlap;

        if (lineA.isVertical) {
          readingGap = Math.max(
            0,
            Math.max(lineA.bbox.x, lineB.bbox.x) -
              Math.min(lineA.bbox.right, lineB.bbox.right)
          );
          perpOverlap = Math.max(
            0,
            Math.min(lineA.bbox.bottom, lineB.bbox.bottom) -
              Math.max(lineA.bbox.y, lineB.bbox.y)
          );
        } else {
          readingGap = Math.max(
            0,
            Math.max(lineA.bbox.y, lineB.bbox.y) -
              Math.min(lineA.bbox.bottom, lineB.bbox.bottom)
          );
          perpOverlap = Math.max(
            0,
            Math.min(lineA.bbox.right, lineB.bbox.right) -
              Math.max(lineA.bbox.x, lineB.bbox.x)
          );
        }

        if (readingGap > distThreshold) continue;

        const smallerPerpSize = Math.min(
          lineA.isVertical ? lineA.bbox.height : lineA.bbox.width,
          lineB.isVertical ? lineB.bbox.height : lineB.bbox.width
        );

        if (smallerPerpSize > 0 && perpOverlap / smallerPerpSize < config.overlap_min)
          continue;
        if (
          isAPrimary !== isBPrimary &&
          smallerPerpSize > 0 &&
          perpOverlap / smallerPerpSize < config.mixed_min_overlap_ratio
        )
          continue;

        uf.union(i, j);
      }
    }

    const tempGroups = {};
    for (let i = 0; i < chunkLines.length; i++) {
      const root = uf.find(i);
      if (!tempGroups[root]) tempGroups[root] = [];
      tempGroups[root].push(chunkLines[i]);
    }

    for (const rootId in tempGroups) {
      allGroups.push(
        tempGroups[rootId].map((pLine) => lines[pLine.originalIndex])
      );
    }

    currentLineIndex = chunkEndIndex + 1;
  }

  return allGroups;
}

function autoMergeOcrData(lines, naturalWidth, naturalHeight, config) {
  if (!config.enabled || !lines || lines.length < 2) return lines;

  const groups = _groupOcrData(lines, naturalWidth, naturalHeight, config);
  const finalMergedData = [];

  for (const group of groups) {
    if (group.length === 1) {
      finalMergedData.push(group[0]);
      continue;
    }

    const verticalCount = group.filter(
      (l) => l.tightBoundingBox.height > l.tightBoundingBox.width
    ).length;
    const isVerticalGroup = verticalCount > group.length / 2;

    group.sort((a, b) => {
      const boxA = a.tightBoundingBox;
      const boxB = b.tightBoundingBox;
      if (isVerticalGroup) {
        const centerXA = boxA.x + boxA.width / 2;
        const centerXB = boxB.x + boxB.width / 2;
        if (centerXA !== centerXB) return centerXB - centerXA;
        return boxA.y + boxA.height / 2 - (boxB.y + boxB.height / 2);
      } else {
        const centerYA = boxA.y + boxA.height / 2;
        const centerYB = boxB.y + boxB.height / 2;
        if (centerYA !== centerYB) return centerYA - centerYB;
        return boxA.x + boxA.width / 2 - (boxB.x + boxB.width / 2);
      }
    });

    const joinChar = config.add_space_on_merge ? " " : "\u200B";
    const combinedText = group.map((l) => l.text).join(joinChar);

    const minX = Math.min(...group.map((l) => l.tightBoundingBox.x));
    const minY = Math.min(...group.map((l) => l.tightBoundingBox.y));
    const maxR = Math.max(
      ...group.map((l) => l.tightBoundingBox.x + l.tightBoundingBox.width)
    );
    const maxB = Math.max(
      ...group.map((l) => l.tightBoundingBox.y + l.tightBoundingBox.height)
    );

    finalMergedData.push({
      text: combinedText,
      isMerged: true,
      forcedOrientation: isVerticalGroup ? "vertical" : "horizontal",
      tightBoundingBox: {
        x: minX,
        y: minY,
        width: maxR - minX,
        height: maxB - minY,
      },
    });
  }
  return finalMergedData;
}

// --- Persistence ---

function loadCacheFromFile() {
  try {
    if (existsSync(CACHE_FILE_PATH)) {
      const fileContent = Deno.readTextFileSync(CACHE_FILE_PATH);
      const data = JSON.parse(fileContent);
      ocrCache = new Map(Object.entries(data));
      console.log(`[Cache] Loaded ${ocrCache.size} items from ${CACHE_FILE_PATH}`);
    }
  } catch (error) {
    console.error("[Cache] Error loading cache:", error);
  }
}

function saveCacheToFile() {
  try {
    const cacheDir = dirname(CACHE_FILE_PATH);
    ensureDirSync(cacheDir);
    const data = Object.fromEntries(ocrCache);
    Deno.writeTextFileSync(CACHE_FILE_PATH, JSON.stringify(data, null, 2));
  } catch (error) {
    console.error("[Cache] Error saving cache:", error);
  }
}

function transformOcrData(lensResult) {
  if (!lensResult?.segments) return [];
  return lensResult.segments.map(({ text, boundingBox }) => ({
    text: text,
    tightBoundingBox: {
      x: boundingBox.centerPerX - boundingBox.perWidth / 2,
      y: boundingBox.centerPerY - boundingBox.perHeight / 2,
      width: boundingBox.perWidth,
      height: boundingBox.perHeight,
    },
  }));
}

// --- Routes ---

app.use(express.json());
app.use((req, res, next) => {
  res.header("Access-Control-Allow-Origin", "*");
  res.header("Access-Control-Allow-Headers", "*");
  next();
});

app.get("/", (req, res) => {
  res.json({
    status: "running",
    message: "Deno OCR Server (Imagescript)",
    requests_processed: ocrRequestsProcessed,
    items_in_cache: ocrCache.size,
  });
});

app.get("/ocr", async (req, res) => {
  const { url: imageUrl, user, pass, context = "No Context" } = req.query;

  if (!imageUrl) return res.status(400).json({ error: "Image URL required" });

  if (ocrCache.has(imageUrl)) {
    const entry = ocrCache.get(imageUrl);
    return res.json(entry.data || entry);
  }

  console.log(`[OCR] Processing: ${imageUrl}`);

  try {
    const headers = new Headers();
    if (user) {
      headers.set("Authorization", "Basic " + btoa(`${user}:${pass || ""}`));
    }

    // 1. Fetch Image
    const response = await fetch(imageUrl, { headers });
    if (!response.ok) throw new Error("Failed to fetch image");

    const arrayBuffer = await response.arrayBuffer();
    const uint8Array = new Uint8Array(arrayBuffer);

    // 2. Decode Image using ImageScript (WASM)
    const image = await Image.decode(uint8Array);
    const fullWidth = image.width;
    const fullHeight = image.height;

    const MAX_CHUNK_HEIGHT = 3000;
    let allFinalResults = [];

    // 3. Chunking Logic
    if (fullHeight > MAX_CHUNK_HEIGHT) {
      console.log(`[OCR] Image tall (${fullHeight}px). Chunking...`);
      for (let yOffset = 0; yOffset < fullHeight; yOffset += MAX_CHUNK_HEIGHT) {
        const currentTop = Math.round(yOffset);
        if (currentTop >= fullHeight) continue;

        let chunkHeight = Math.min(MAX_CHUNK_HEIGHT, fullHeight - currentTop);
        if (currentTop + chunkHeight > fullHeight) {
          chunkHeight = fullHeight - currentTop;
        }
        if (chunkHeight <= 0) continue;

        // ImageScript: crop(x, y, w, h)
        // Clone is safest to avoid mutation issues
        const chunk = image.clone().crop(0, currentTop, fullWidth, chunkHeight);
        
        // Encode to PNG (returns Uint8Array)
        const chunkBuffer = await chunk.encode();
        
        // Convert to Base64 for Google Lens
        const b64 = Buffer.from(chunkBuffer).toString("base64");
        const dataUrl = `data:image/png;base64,${b64}`;

        const rawChunkResults = transformOcrData(await lens.scanByURL(dataUrl));
        let mergedChunkResults = rawChunkResults;

        if (AUTO_MERGE_CONFIG.enabled && rawChunkResults.length > 0) {
          mergedChunkResults = autoMergeOcrData(
            rawChunkResults,
            fullWidth,
            chunkHeight,
            AUTO_MERGE_CONFIG
          );
        }

        // Adjust coordinates back to global image space
        mergedChunkResults.forEach((result) => {
          const bbox = result.tightBoundingBox;
          const yGlobalPx = bbox.y * chunkHeight + currentTop;
          bbox.y = yGlobalPx / fullHeight;
          bbox.height = (bbox.height * chunkHeight) / fullHeight;
          allFinalResults.push(result);
        });
      }
    } else {
      // Small image, process directly
      const b64 = Buffer.from(uint8Array).toString("base64");
      const dataUrl = `data:image/png;base64,${b64}`;
      
      const rawResults = transformOcrData(await lens.scanByURL(dataUrl));
      allFinalResults = rawResults;
      if (AUTO_MERGE_CONFIG.enabled && rawResults.length > 0) {
        allFinalResults = autoMergeOcrData(
          rawResults,
          fullWidth,
          fullHeight,
          AUTO_MERGE_CONFIG
        );
      }
    }

    ocrRequestsProcessed++;
    ocrCache.set(imageUrl, { context, data: allFinalResults });
    saveCacheToFile();

    res.json(allFinalResults);
  } catch (err) {
    console.error(`[OCR] Error: ${err.message}`);
    res.status(500).json({ error: err.message });
  }
});

app.post("/purge-cache", (req, res) => {
  const count = ocrCache.size;
  ocrCache.clear();
  saveCacheToFile();
  res.json({ status: "success", removed: count });
});

// --- Server Start ---
app.listen(port, host, () => {
  loadCacheFromFile();
  console.log(`Deno OCR Server running at http://${host}:${port}`);
});