# On-device bubble detection, text fit and pipeline performance: design

Date: 2026-11-15. Status: phases 0–2 implemented and measured; phases 3–4 remain unimplemented. Phase 2 uses YOLO26n 960 with ORT 1.32.0 minimal CPU-4, with C++ exceptions enabled; see `docs/reports/2026-11-15-phase2-results.md` for same-Xiaomi off/on results, worker-only visual review and open failures. Implementation completion is not release-quality or owner acceptance. Phase 1 measurements on A069P remain historical context in `docs/reports/2026-11-15-phase1-results.md`; `auto` still uses Japanese without a Korean extension. Builds on `2026-11-13-manga-page-translation-design.md` and `docs/manga-reader.md`.

## Intent

The MlKit engine reads a page on the device and the overlay draws the translation over the original text. Three things limit it today: OCR sees a degraded copy of the page and does not know what a speech bubble is; the overlay fits text into a rectangle that grows around the original text, not into the bubble; and the pipeline decodes, re-encodes and measures more than it needs to. The owner wants all three improved together, with an on-device bubble detection model, with the MlKit engine as the main target and equal weight on quality and speed/memory.

## Findings in the phase 0 baseline

| # | Where | Problem |
| --- | --- | --- |
| F1 | `PageImage.encode` -> `MlKitPageOcr.recognize` | OCR runs on a JPEG already shrunk to 1600 px and compressed at quality 80, then decoded again: decode, scale, JPEG encode, decode. Small text loses detail twice, and the MlKit path pays for an upload-oriented encode it never uploads. |
| F2 | `MlKitPageOcr` | `auto` selects the Japanese recognizer, while `groupOcrBlocks` treats only `ja`/`zh` as vertical. Latin and Korean pages on `auto` read worse; there is no script detection. |
| F3 | `OcrGrouping` | ML Kit's text block is the unit. A bubble split into several blocks stays split; text outside bubbles (sound effects, art text) is indistinguishable from speech. Nothing knows where the bubble is. |
| F4 | `TranslationOverlay.plateFor` | The plate is the text box grown in 5 fixed steps (up to 3.2x wide, 1.8x tall) around its centre. It ignores the bubble outline, so it can cover art on round bubbles and miss the original text on irregular ones; plates are never checked against each other. |
| F5 | `plateStyle` | Plate is always white at 0.93 alpha with black text. Dark, screentoned and tinted bubbles and narration boxes look wrong. |
| F6 | `TranslationOverlay.fit` | Up to 5 growth steps x about 8 `TextMeasurer` calls per line run inside composition (`remember` keyed on density and page). Greedy centred wrapping leaves ragged lines inside round bubbles. |
| F7 | `TranslationRunner` | One `Semaphore(parallelism = 2)` guards the whole pipeline, so two full-page bitmaps and two OCR runs can be alive together. |
| F8 | `TranslationKey` | The cache key contains the target language, so changing it re-runs OCR even though OCR does not depend on the target. |
| F9 | `PageBytes` | Own `OkHttpClient`, and pages are downloaded again although the reader's Coil loader has just fetched them. |
| F10 | Model engine | `box` comes from the LLM in 0..1000 coordinates and is imprecise; the plate may not cover the original text. |

## Goals

- Detect speech bubbles, boxes with narration, and free text on the device, and use them to group OCR text, drop or mark sound effects, and give the overlay a real area to fit into.
- Overlay text fills the bubble's usable area, avoids neighbouring plates, matches the bubble's fill colour, and reads at a size consistent across the page.
- OCR sees the best pixels available at bounded memory; the MlKit path decodes a page once.
- Measure before and after on a fixed page set, per stage.

## Non-goals

- Inpainting or erasing the original text; the overlay still covers it with a plate.
- Changing LLM providers, prompts' meaning, consent or the cache key scheme visible to the vault.
- Refining boxes in the Model engine (F10). It keeps the existing growth path; a later spec may feed it through the detector.
- Training a model.

## Constraints

- Android, Kotlin, Compose, Hilt, minSdk 26. The universal APK has no `abiFilters`, so a native runtime is paid once per ABI. README/docs already treat 16 KB ELF alignment as a requirement; every new `.so` must pass the same check as ML Kit.
- Nothing about the page leaves the phone in this work. Detection and fit are fully local.
- Pure logic (box merge, assignment, inscribed rectangle, overlap resolution, tensor decoding) is testable on the JVM without Android.
- Copyrighted pages are never committed. The evaluation set lives on the owner's machine.

## Design

### Pipeline

```
bytes (saved copy or CDN)
  -> decode once, sampleSize so long side >= WORK (measure: 1600, 2000, 2400)
  -> [parallel] ML Kit full-page OCR   |  BubbleDetector (regions)
  -> merge: assign OCR blocks to regions, join blocks of one bubble, tag free text
  -> bubble geometry: usable rectangle + fill colour per region   (page bitmap still in memory)
  -> text-only LLM request (numbered blocks)                      [network gate]
  -> TranslatedLine with box, area, fill                           (stored in the existing json column)
  -> overlay: layout once off the main thread, draw
```

The Model engine path keeps `PageImage.encode` because it uploads the JPEG. Only the MlKit path switches to the raw decode, so `PageSource` returns raw bytes and the encode moves to the Model path.

### Detector

Implemented phase-2 contract: `BubbleDetector.detect(image: ByteArray): List<Region>?`, where `null` means unavailable/failed and an empty list is valid. YOLO26n has one text class, so `Region(box: Box, score: Float)` has no `kind`. The production singleton owns a lazy ORT session and reusable buffers; `onTrimMemory` releases them only when idle. OCR and detection decode separately; sharing a bitmap remains unimplemented.

Pages with aspect above about 2 (webtoon strips) are tiled vertically with overlap before detection; models trained at a square input shrink a tall strip into noise. Detections are mapped back to page fractions, then de-duplicated across tile seams.

Detector output is not trusted for recall. ML Kit stays the source of text; detector regions group it, not classify it as speech versus Sfx. Up to eight regions scoring at least 0.5 and claiming no OCR block get second-pass OCR with 10% padding. A crop currently decodes a full-width band before horizontal cropping, so this is not guaranteed cheaper than a second full-page pass. A second-pass exception currently fails the whole page, as measured on the historical stacked strip.

### Merge rules (pure Kotlin, in `translate/`)

- A block is assigned to its greatest covered-share region, with at least 60% coverage; ties prefer the smaller region.
- Regions with 2–6 assigned blocks and area at most 35% of the page join those blocks in reading order. Vertical text uses pixel height/width at least 1.5; other text reads horizontally. The unit's box is the union of its text boxes, not the detector rectangle.
- The chosen one-class model cannot identify `textFree` or `bubble` kinds. Free-text/Sfx separation still comes from the LLM's `kind`; phase 3 geometry will supply bubble-aware usable areas. The phase-2 detector does not itself assign `LineKind.Sfx`.
- Unassigned blocks, single-block regions and regions outside the merge caps keep their blocks unchanged.
- Recognizer selection remains as decided in phase 1: `auto` uses the Japanese recognizer, with no second script-selection pass.

### Bubble geometry

Computed once at detection time from the decoded bitmap, so the overlay never touches pixels:

1. Downscale the region's box to a small luminance grid. Flood-fill from the OCR text centre over pixels close to the bubble's fill; the fill is the median of a ring just inside the region border.
2. The usable area is the largest axis-aligned rectangle inside the filled set that contains the text centre (maximal rectangle over the grid), inset a few percent for the outline.
3. The plate colour is the fill; text colour is black or white by luminance contrast.

Stored as optional fields on the line: `"area":[l,t,r,b]` (page fractions, same rule as `box`) and `"fill":"#RRGGBB"`. They live in the existing `json` column and vault `json`, so there is no Room migration, and older exports and cached rows simply lack them. Lines without `area` (Model engine, old cache) use the current growth path; that path stays only for data that has no area.

If a segmentation model replaces the flood fill later, `area` stays the contract.

### Text fit and overlay

- The overlay draws into `area` instead of growing the text box.
- Layout (font size, line breaks) is computed once per line off the composition thread and reused on zoom, pan and recomposition. Measuring and drawing must use the same layout object; today they use two code paths, and the existing comment on `plateStyle` documents the overflow that causes. Whether this is `TextMeasurer` serialised on one dispatcher or a `StaticLayout` drawn on a canvas is decided in the spike, because `TextMeasurer` thread safety must be confirmed.
- Balanced line breaking (`LineBreak.Paragraph` or equivalent; verify availability on the Compose version in the catalog) so round bubbles fill evenly.
- Page-level font consistency: sizes are chosen per line by binary search, then lines of the same page are clamped to within a band of the median so one page does not mix 22 sp and 9 sp without need. A line that would fall below the readable floor takes the next larger area or the existing minimum instead.
- Plates are placed in reading order; a later plate is shrunk to avoid earlier ones. The existing `fittedPage` mapping is reused.

### Performance changes

| Change | Fixes |
| --- | --- |
| Raw decode once for the MlKit path; no JPEG round trip | F1 |
| Separate gates: OCR/detect 1 at a time, network 2 at a time | F7 |
| OCR layer cached in `cacheDir/ocr/{gallery}/{page}-{source}-{pipelineVersion}.json` (derived, rebuildable, never in the vault or backup); changing target language reuses it | F8 |
| One shared `OkHttpClient`; reuse the Coil disk cache entry for CDN pages when present | F9 |
| In OnTurn and Whole modes, run OCR/detect for the next page while the current page waits for the network | latency |
| Layout off the main thread and cached | F6 |
| Multi-page LLM requests in Whole mode | deferred, see Phases |

The OCR cache holds blocks, regions, `area` and `fill`. `pipelineVersion` includes the detector model hash, so replacing the model invalidates it.

## Model and runtime candidates

All figures below are from the model cards and release pages as read on 2026-11-15, not measured on a device.

| Model | Runtime | Notes |
| --- | --- | --- |
| `Kiuyha/Manga-Bubble-YOLO` (YOLO26n/s ONNX) | ONNX Runtime, or convert to LiteRT | 2.4M / 9.5M params (about 10 MB fp32 for n, from 4 bytes per parameter). End-to-end head: output `(1, 300, 6)`, no NMS. Input 1280. Test set: nano precision 0.929, recall 0.863, mAP50 0.947; small 0.937 / 0.893 / 0.961. The table lists one class, `Text`; the card text says bubbles and text regions. The class list must be read from the ONNX before relying on bubble vs free text. Trained on Manga109-s, Mangadex EN/VI pages auto-labelled with Magi; dataset not released. |
| `ogkalu/comic-text-and-bubble-detector` (RT-DETR-v2 r50vd) | ONNX Runtime | Classes `bubble`, `text_bubble`, `text_free`: exactly the split this design needs. Apache-2.0, no Ultralytics. Input 640, tall webtoons split in training. The repo is 395 MB across formats; a full r50vd is far too big for the APK, so it needs quantisation or an on-demand download. |
| `ogkalu/comic-speech-bubble-detector-yolov8m` | Ultralytics export | Bubbles only; YOLOv8m is a heavy fit and Ultralytics-licensed. |
| `huyvux3005/manga109-segmentation-bubble` (YOLO11n-seg) | Ultralytics export | Gives masks. Its metadata says `cc-by-nc-4.0` while the card body says Apache-2.0; unusable until the author resolves it. |
| `dmMaze/comic-text-detector` | Torch/ONNX | GPL-3.0. |

Chosen phase-2 runtime: ONNX Runtime 1.32.0 basic-minimal CPU-4, arm64-v8a, exceptions enabled and telemetry disabled, with the bundled ORT-format nano model at 960 px. Its native LOAD segments are 16 KB aligned, but execution on a 16 KB-page device remains unmeasured; see [runtime packaging](../detector-runtime.md) and the [same-phone evaluation](../reports/2026-11-15-phase2-results.md). The table above records the earlier candidate research, not the shipped model's input size or runtime measurements.

### Licence (decided by the owner, 2026-11-15)

The project is open source and the owner accepts AGPL-3.0 terms for Ultralytics-lineage weights, so those models are allowed. Weights fine-tuned from Ultralytics YOLO inherit Ultralytics' AGPL-3.0 terms in general; the Hugging Face "apache-2.0" label on a derivative does not change the base licence (a sibling YOLO26 card, `leoxs22/manga-panel-detector-yolo26n`, says `agpl-3.0`). This is my reading, not legal advice.

Consequences:

- The checkout had no `LICENSE` file and `release.yml` publishes signed APKs. The owner chose AGPL-3.0 for the app on 2026-11-15; `LICENSE` (the AGPL-3.0 text) and the README/README_RU licence line were added. Shipping AGPL weights is then consistent with the app's own licence.
- Models whose metadata or card forbids commercial use (`huyvux3005/manga109-segmentation-bubble`, `cc-by-nc-4.0`) stay excluded: non-commercial terms are not AGPL-compatible.
- The training data (Manga109-s, scraped fan translations) adds its own terms; the chosen model's card is recorded in the Phase 0 report with its licence text and dataset terms.
- Apache-2.0 without Ultralytics lineage (RT-DETR) is no longer required, only preferred when quality and size are equal.

## Evaluation

A fixed set of about 30 pages chosen by the owner (Japanese vertical, English horizontal, Korean, a round-bubble page, a screentone-heavy page, a narration-box page, a tall strip), kept outside the repo. A debug-only entry point runs the pipeline over the folder and writes, per page: per-stage time (decode, detect, OCR, merge, geometry), peak heap, region/block counts, and an overlay screenshot for review. Metrics:

- Text coverage: share of the owner's marked bubbles with all their text inside one translation unit.
- Plate fit: share of plates whose area stays inside the bubble, and overlapping plate pairs per page.
- Per-stage p50/p95 and peak heap on the owner's phone.

A phase ships only if it improves its metric on this set. Phase 2 compares detector off/on on the same Xiaomi in the same session; phase 0 and phase 1 A069P numbers are context, not its baseline. Owner-marked coverage and plate-fit metrics remain unmeasured, so implementation completion does not establish the release-quality gate.

## Phases

0. Baseline and spike (go/no-go): instrument current stage timings; run the candidate models in Python on the page set; read the ONNX class lists; build a throwaway app module build with ONNX Runtime and with LiteRT and record APK delta per ABI and the 16 KB alignment result; record the chosen model's licence and dataset terms. Output: a short report in `docs/reports/`, and the chosen model/runtime.
1. No new dependency: raw single decode for MlKit (F1), script detection for `auto` (F2), separate gates (F7), OCR cache (F8), shared HTTP client and Coil reuse (F9), overlay layout off the main thread with one layout for measure and draw, balanced breaking, plate overlap avoidance, font-size band (F4 partial, F6).
2. Detector — implemented and measured: bundled nano YOLO26n 960, exception-enabled ORT-min CPU-4 for arm64-v8a, tiled OCR/detection, pure merge, second-pass OCR and cache v2. The [phase-2 report](../reports/2026-11-15-phase2-results.md) records higher latency/heap, remaining fragmentation, detector-null cache loss and second-pass failure; geometry and owner acceptance are not part of this completion claim.
3. Bubble geometry: flood-fill usable area and fill colour, JSON fields, overlay switch to `area`, colour handling (F4, F5).
4. Optional, only if the numbers justify it: multi-page LLM requests in Whole mode, segmentation mask in place of the flood fill, detector input for the Model engine's boxes (F10).

Each phase is independently releasable; Phase 1 does not depend on the detector.

## Risks

- Recall: the nano model misses about 14% of text regions in its own test; this is why OCR text is the source of truth and regions are only used to group and shape.
- Class semantics: the chosen YOLO26n model has a single text class. Free-text/Sfx separation remains the LLM `kind` plus phase 3 geometry; the detector does not prove a bubble outline.
- Flood fill on gradient, textured or open-outline bubbles can leak; geometry is clamped to the detected region and falls back to the text box expanded by the detector region when the filled area is implausible.
- Native runtime growth in a universal APK and the 16 KB requirement; measured in Phase 0.
- Tall strips and very large pages: tiling and the work-resolution cap bound memory; measured in Phase 0/1.

## Open items for the builder

Exact thresholds (assignment overlap, readable floor, band width), the `WORK` resolution, the layout engine choice for text, file and class names beyond those given, and the debug entry point's form.
