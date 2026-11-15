# On-device text detector (phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a size-reduced ONNX Runtime with the YOLO26n text detector at 960 px, so one bubble becomes one translation unit, silent bubbles get a second OCR pass, and tall strips are no longer read at 100 px width.

**Architecture:** ML Kit stays the only source of text. The detector (`BubbleDetector`) runs concurrently with the first OCR pass on the same page bytes, each decoding on its own; pure functions assign OCR blocks to detector regions and join them. Pages with height/width above 2 are cut into overlapping full-width tiles for both OCR and the detector. The OCR cache stores blocks, regions and page aspect; the merge runs on every read, so tuning it never invalidates the cache.

**Tech Stack:** Kotlin, ML Kit 16.0.1, ONNX Runtime 1.32.0 (own minimal arm64 AAR, ORT-format model), Hilt, JUnit 4, `androidx.test` instrumented tests.

**Spec:** `docs/specs/2026-11-15-bubble-detection-and-fit-design.md` (Detector, Merge rules, Performance). Measured inputs: `docs/reports/2026-11-15-runtime-bench-stage2.md` and `2026-11-15-ort-minimal-build.md`, `2026-11-15-detector-spike.md` (branches `runtime-bench`, `ort-minimal`, `spgsroot/detector-spike`; files are in `docs/reports/` of those branches, copy the ones you need to read). Phase 3 (bubble geometry, `area`/`fill`, overlay switch) is out of scope.

## Global Constraints

- minSdk 26 stays. The detector runtime is arm64-v8a only; on any other ABI, or if the model or runtime fails to load, the app behaves exactly as after phase 1 (`BubbleDetector.detect` returns `null`).
- No Room schema change, vault `FORMAT` stays `1`, `TranslatedLine`/`PageTranslation` JSON unchanged. The OCR cache is derived data under `cacheDir/ocr/`.
- Nothing about a page leaves the phone because of this work; the Model engine path is untouched.
- Runtime: ONNX Runtime 1.32.0, CPU provider, intra-op 4 threads, inter-op 1. XNNPACK, NNAPI, GPU and LiteRT are not used (measured: no gain or fail to load).
- Detector: `yolo26n.ort`, 10,513,152 bytes, SHA-256 `dc6b6341319acc41f115a5c7239f888e9ef344eb492447600d21a46d691506c2`, input 960x960 letterbox (colour 114), NCHW float32 /255, output `[1,300,6]` rows `x1,y1,x2,y2,score,class` in input pixels, no NMS, score >= 0.25, one class.
- Windows host: run Gradle as `cmd.exe /c gradlew.bat ...` with `JAVA_HOME=C:\Users\spgs\.jdks\jbr-21.0.11` and `ANDROID_HOME=C:\Users\spgs\AppData\Local\Android\Sdk`; `./gradlew` does not execute here. WSL Debian is used for the runtime build.
- Phone runs (Tasks 4, 5, 6, 9): serial-qualified adb (`C:/Users/spgs/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 00324362A000776`), install only a locally suffixed `applicationId` copy (never committed), never touch the owner's installed `ru.spgsroot.peachy`, uninstall the copy at the end. If the phone is locked or absent, stop and ask the coordinator. The phone kernel uses 4 KB pages: 16 KB execution stays unmeasured.
- Copyrighted pages and weights beyond the two vendored files are never committed. Pages live in `C:/Users/spgs/Desktop/manga-tests/{jp,eng}` (14 + 16 files).
- Strictness: default. Existing unit tests plus the smoke runs named below; no gates beyond the repo's checks.
- Commit messages: one imperative sentence, no prefix.

## Review Focus

Failure modes the spec implies that no happy-path test covers, most likely first; each is tested in the task named.

1. Detector unavailable or failing (non-arm64, missing model, OOM): translation works exactly as in phase 1 and nothing stale is cached. (Task 6 `OrtBubbleDetector`, Task 8)
2. A region that spans two bubbles, or a block lying across two regions: no cross-bubble join. (Task 3)
3. A tall strip: tiles cover every row, a line on a seam is read once, not twice and not lost, and memory stays bounded. (Tasks 2, 4, 5)
4. Cancelling mid-detect or mid-OCR (page turn): native tensors and the bitmap are released, the locks are free for the next page. (Tasks 5, 6, 8)
5. A corrupt or non-image page: still `IOException`/`IllegalArgumentException` as today, not a crash in tile planning. (Task 4)

---

## File structure

| File | Responsibility |
| --- | --- |
| `translate/PageTiles.kt` (new) | Pure: tile plan for strips, tile-to-page box mapping, seam de-duplication |
| `translate/BlockMerge.kt` (new) | Pure: `Region`, `PageScan`, `mergeBlocks`, reading order of a page |
| `translate/PageDecode.kt` (new) | Page size, whole-page decode, tile decode (region decoder with fallback) |
| `translate/BubbleDetector.kt`, `OrtBubbleDetector.kt`, `DetectorPreprocess.kt`, `DetectorDecode.kt` (new) | Detector contract, ONNX session lifecycle, Android preprocessing, pure output decoding |
| `translate/MlKitPageOcr.kt`, `PageOcr.kt` (modify) | Strip tiling and `recognizeRegions` |
| `translate/OcrCache.kt` (modify) | Cache v2 over `PageScan` |
| `translate/EnginePageTranslator.kt`, `di/OcrModule.kt` (modify) | Parallel scan, second pass, merge |
| `app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar`, `app/src/main/assets/detector/yolo26n.ort` (new binaries) | Runtime and model |

---

### Task 1: Vendor the minimal ONNX Runtime and the nano model

**Files:**
- Create: `app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar`, `app/src/main/assets/detector/yolo26n.ort`, `docs/detector-runtime.md`
- Modify: `app/build.gradle.kts` (dependencies block)

**Interfaces:**
- Produces: Java package `ai.onnxruntime.*` on the app classpath; asset path `detector/yolo26n.ort`.

- [ ] **Step 1: Rebuild the CPU AAR with Android API 26.** The spike AAR (`C:/Users/spgs/Desktop/manga-tests-out/spike/ort-min/onnxruntime-1.32.0-B-cpu-arm64-v8a.aar`) declares minSdk 27; the app is 26. Follow `README.txt` in that folder (WSL Debian, source tag `v1.32.0`, NDK 28.2.13676358, JDK 17, config `B.config`, basic minimal, `--enable_reduced_operator_type_support`) and set the Android API level to 26 (`--android_api 26`, and the AAR's `minSdkVersion`). Copy the result to `app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar`.
- [ ] **Step 2: Verify the AAR.** `unzip -p <aar> AndroidManifest.xml` shows `minSdkVersion` 26; `llvm-readelf.exe -lW` on both `.so` shows every LOAD `Align 0x4000`; record sizes and SHA-256. If the API 26 build fails, stop and ask the coordinator: do not add `tools:overrideLibrary` and do not raise the app's minSdk.
- [ ] **Step 3: Copy the model** from `.../ort-min/models/yolo26n.ort`; `sha256sum` must equal the constant in Global Constraints.
- [ ] **Step 4: Gradle.** `implementation(files("libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar"))`; no `abiFilters` and no `noCompress` change. Run `cmd.exe /c gradlew.bat :app:assembleDebug`; expected BUILD SUCCESSFUL. List the APK: `lib/arm64-v8a/` holds `libonnxruntime.so` and `libonnxruntime4j_jni.so`, no ORT library under other ABIs; record the APK size before/after in `docs/detector-runtime.md`.
- [ ] **Step 5: `docs/detector-runtime.md`:** build pins and exact commands, AAR and model hashes and sizes, operator config, model card licence line (`AGPL-3.0` per the card YAML metadata; the owner accepted AGPL-3.0 on 2026-11-15) and dataset terms as in the detector-spike report, why arm64-only and what other ABIs get (phase 1 behaviour).
- [ ] **Step 6: Commit** `git add app/libs app/src/main/assets/detector app/build.gradle.kts docs/detector-runtime.md` / `Vendor the minimal ONNX Runtime and the nano text detector`.

### Task 2: Page tiles

**Files:**
- Create: `app/src/main/java/ru/spgsroot/peachy/translate/PageTiles.kt`
- Test: `app/src/test/java/ru/spgsroot/peachy/translate/PageTilesTest.kt`

**Interfaces:**
- Produces:
  - `data class PageTile(val top: Int, val bottom: Int)` (pixel rows of the original page, full width).
  - `object PageTiles { const val STRIP_ASPECT = 2.0; const val TILE_ASPECT = 1.5; const val OVERLAP = 0.2; fun plan(width: Int, height: Int): List<PageTile>; fun <T> dedupeSeams(items: List<T>, box: (T) -> Box, pick: (T, T) -> T): List<T> }`.
  - `fun PageTile.toPage(local: Box, pageHeight: Int): Box` (box in fractions of the tile to fractions of the page).
  - `Box` and `OcrBlock` are the existing types in `PageOcr.kt`.

- [ ] **Step 1: Write failing tests** in `PageTilesTest`:
  - `pageUpToTwiceAsTallIsOneTile`: `plan(800, 1600)` is `[PageTile(0, 1600)]`; `plan(800, 1601)` has two tiles.
  - `stripTilesCoverEveryRowWithOverlap`: `plan(800, 12000)`: first top 0, last bottom 12000, every tile height `1200` (`TILE_ASPECT * width`), consecutive tiles overlap by at least `0.2 * 1200 = 240` rows, no gap; the last tile is shifted up to keep full height.
  - `tinyPageIsOneTile`: `plan(1, 1)`, `plan(0, 0)` do not throw and give one tile.
  - `toPageMapsLocalBox`: tile `(1000, 2200)` on a 12000-row page maps local `Box(0.1, 0.5, 0.2, 1.0)` to `Box(0.1, 1600/12000, 0.2, 2200/12000)` within 1e-6.
  - `dedupeKeepsOnePerSeamPair`: two boxes with intersection/smaller-area >= 0.5 collapse to `pick(a, b)`; boxes with 0.49 stay both; far boxes untouched; order of the first occurrences preserved.
- [ ] **Step 2: Run** `cmd.exe /c gradlew.bat :app:testDebugUnitTest --tests "*PageTilesTest"`; expected FAIL (unresolved reference).
- [ ] **Step 3: Implement** the signatures above. `dedupeSeams` is greedy over the input order: the first item opens a slot; a later item overlapping a kept item by at least 0.5 of the smaller area replaces it by `pick`.
- [ ] **Step 4: Run** the same command; expected PASS.
- [ ] **Step 5: Commit** `Plan overlapping tiles for tall pages`.

### Task 3: Block merge

**Files:**
- Create: `translate/BlockMerge.kt`; Test: `translate/BlockMergeTest.kt`
- Modify: `translate/OcrGrouping.kt` (expose helpers; behaviour unchanged, `OcrGroupingTest` stays green)

**Interfaces:**
- Consumes: `Box`, `OcrBlock` (`PageOcr.kt`).
- Produces:
  - `data class Region(val box: Box, val score: Float)`.
  - `data class PageScan(val blocks: List<OcrBlock>, val regions: List<Region>, val aspect: Float)` (`aspect` is original width/height).
  - `fun mergeBlocks(scan: PageScan): List<OcrBlock>`.
  - `fun orderPage(blocks: List<OcrBlock>, aspect: Float): List<OcrBlock>` (same row/column rules as `groupOcrBlocks`, vertical page when most blocks are taller than 1.5 x their width in pixels).
  - `internal fun blockShare(block: Box, region: Box): Float` (share of the block's area inside the region).
  - Constants in `BlockMerge.kt`: `ASSIGN_SHARE = 0.6f`, `MAX_JOIN = 6`, `MAX_REGION_AREA = 0.35f` (fraction of the page; tuned in Task 9).

Rules: a block belongs to the region holding at least `ASSIGN_SHARE` of its area, the smaller region on a tie. A region is merged only if it holds at least 2 blocks, at most `MAX_JOIN`, and its area is at most `MAX_REGION_AREA` of the page. Members are put in reading order (columns right to left when the region is at least 1.5 times taller than wide in pixels, else rows left to right), their text joined with no space when mostly kana/Han, otherwise a space (reuse the existing separator rule). The unit box is the union of the member boxes. A unit takes the place of its earliest member in the block list; other blocks keep their order.

- [ ] **Step 1: Write failing tests** in `BlockMergeTest` (page aspect 0.7 unless stated):
  - `noRegionsReturnsBlocksUnchanged`, `singleBlockRegionLeavesBlockAlone`.
  - `twoLatinBlocksInOneRegionBecomeOneUnit`: text `"A B"`, box is the union, list length drops by one.
  - `kanaBlocksJoinWithoutSpaceInColumnOrder`: tall region, right column first.
  - `blockStraddlingTwoRegionsJoinsNeither`: 50% in each region.
  - `blockWithSixtyPercentJoinsThatRegion`; `tieGoesToSmallerRegion`.
  - `hugeRegionIsNotMerged` (area 0.5) and `regionWithSevenBlocksIsNotMerged`.
  - `unitsKeepPageOrder`: unit sits where its earliest member was.
  - `orderPageRowsAndColumns`: three blocks in a row read left to right; the same blocks as tall boxes read right to left.
- [ ] **Step 2: Run** `... --tests "*BlockMergeTest"`; expected FAIL.
- [ ] **Step 3: Implement.** In `OcrGrouping.kt` make `rows`, `columns` and the separator decision `internal` (extract the separator rule into `internal fun joinSeparator(texts: List<String>): String`); `BlockMerge.kt` works in pixel space using `aspect` (e.g. x scaled by `aspect`, y by 1) for the 1.5 test only.
- [ ] **Step 4: Run** `... --tests "*BlockMergeTest" --tests "*OcrGroupingTest"`; expected PASS.
- [ ] **Step 5: Commit** `Join the OCR blocks of one detected region`.

### Task 4: Page decode with tiles

**Files:**
- Create: `translate/PageDecode.kt`; Test: `app/src/androidTest/.../translate/PageDecodeDeviceTest.kt`
- Modify: `MlKitPageOcr.kt` only to call `PageDecode.decode` instead of its inline decode (no behaviour change; Task 5 builds on it)

**Interfaces:**
- Consumes: `PageTile` (Task 2), `PageImage.sampleSize`.
- Produces:
  - `object PageDecode { fun size(image: ByteArray): Pair<Int, Int>; fun decode(image: ByteArray, maxSide: Int): Bitmap; fun decodeTile(image: ByteArray, tile: PageTile, maxSide: Int, useRegionDecoder: Boolean = true): Bitmap }`.
  - All throw `IOException("Cannot decode the page image")` for unreadable data. `decode` and `decodeTile` return ARGB_8888 whose long side is at most `maxSide` times the sample step (power of two, as `PageImage.sampleSize`).
  - `decodeTile` uses `BitmapRegionDecoder` on the full-width rows `[tile.top, tile.bottom)`; when the decoder rejects the data, or returns null, it decodes the whole page at the smallest sample size keeping at most 16,000,000 pixels and crops the rows. `useRegionDecoder = false` forces the fallback (tests).

- [ ] **Step 1: Write failing tests** (androidTest, synthetic 800x6000 bitmap with a red band at rows 2400..2600, encoded as JPEG, PNG and WebP via `Bitmap.compress`):
  - `tileOfEachFormatHasExpectedSizeAndBand` for both decoder modes: tile `(2000, 3200)` is 800x1200 (maxSide 2000), the band sits at tile rows 400..600.
  - `wholeStripDecodeKeepsWidth`: fallback decode of the 800x12000 strip is at least 800 wide (the phase 1 baseline gave about 107).
  - `corruptBytesThrowIoException` for `size`, `decode`, `decodeTile`.
- [ ] **Step 2: Run on the phone** (see Global Constraints) with `adb -s 00324362A000776 shell am instrument -w -e class ru.spgsroot.peachy.translate.PageDecodeDeviceTest <suffixed-package>.test/androidx.test.runner.AndroidJUnitRunner` after `:app:assembleDebug :app:assembleDebugAndroidTest`; expected FAIL.
- [ ] **Step 3: Implement.** Record in the commit message whether the region decoder accepted WebP on this phone.
- [ ] **Step 4: Run** the device test and `:app:testDebugUnitTest`; expected PASS.
- [ ] **Step 5: Commit** `Decode page tiles without reading a tall strip at thumbnail size`.

### Task 5: OCR on tiles and on regions

**Review:** per-task — concurrency (the OCR lock and bitmap lifetime across several tiles and under cancellation)

**Files:**
- Modify: `translate/PageOcr.kt`, `translate/MlKitPageOcr.kt`, fakes of `PageOcr` in `EnginePageTranslatorTest.kt`, `PageEvalTest.kt`, `OcrCacheDeviceTest.kt` (stub `recognizeRegions` returning empty)
- Test: `app/src/androidTest/.../translate/MlKitTilesDeviceTest.kt`

**Interfaces:**
- Consumes: `PageTiles`, `PageDecode`.
- Produces: `PageOcr.recognizeRegions(image: ByteArray, source: String, regions: List<Box>): List<OcrBlock>` (each region padded by 10% of its width and height, clipped to the page; blocks returned as fractions of the page; empty regions list returns empty without decoding). `recognize` keeps its signature; for a page with height/width above `STRIP_ASPECT` it reads every tile of `PageTiles.plan` one after another, maps boxes to page pixels, de-duplicates seams with `dedupeSeams` (`pick` keeps the larger box), then calls `groupOcrBlocks` with the original page size. Pages that are not strips behave exactly as before.

- [ ] **Step 1: Write failing device tests** (draw text with `Canvas`, 120 px sans, on white):
  - `stripTextOnEveryTileIsRead`: 800x6000 page with words `ALPHA`..`ECHO` at y = 300, 1500, 2900, 4300, 5700 and one word `SEAM` centred on a tile seam; `recognize(..., "en")` returns every word once (no duplicates), tops ascending.
  - `recognizeRegionsReadsOnlyThePaddedRegion`: page with `LEFT` and `RIGHT`; region around `RIGHT` returns only `RIGHT`, box inside the page and overlapping the region.
  - `recognizeRegionsOfNothingIsEmpty`.
  - `cancellingStripOcrReleasesTheLock`: cancel the first call after the first tile, then a second `recognize` on a small page completes within 10 s.
- [ ] **Step 2: Run** the device tests; expected FAIL.
- [ ] **Step 3: Implement.** Keep the phase 1 ownership rule: a bitmap is recycled and the lock released only when the ML Kit task finishes, never in a `finally` of the waiting coroutine. A tile uses the lock once per tile, so another page may interleave between tiles.
- [ ] **Step 4: Run** the device tests, then `:app:testDebugUnitTest`; expected PASS.
- [ ] **Step 5: Commit** `Read tall strips tile by tile and read chosen regions again`.

### Task 6: Bubble detector

**Review:** per-task — concurrency (one native session, lock, release under memory pressure, cancellation)

**Files:**
- Create: `translate/BubbleDetector.kt`, `translate/OrtBubbleDetector.kt`, `translate/DetectorPreprocess.kt`, `translate/DetectorDecode.kt`
- Modify: `PeachyApplication.kt` (`onTrimMemory`)
- Test: `translate/DetectorDecodeTest.kt` (JVM), `androidTest/.../translate/OrtBubbleDetectorDeviceTest.kt`

**Interfaces:**
- Consumes: `Region` (Task 3), `PageTiles`/`PageTile.toPage` (Task 2), `PageDecode.decodeTile` and `size` (Task 4), `traced` (`PageTrace.kt`).
- Produces:
  - `interface BubbleDetector { suspend fun detect(image: ByteArray): List<Region>?; fun release() }` (`null` = cannot run; empty = no text; boxes are page fractions).
  - `const val DETECTOR_SIDE = 960`, `DETECTOR_MIN_SCORE = 0.25f` (`DETECTOR_MODEL_TAG` comes from Task 7).
  - `class OrtBubbleDetector @Inject constructor(@ApplicationContext context: Context) : BubbleDetector`, `@Singleton`.
  - `object DetectorDecode { fun yolo(rows: FloatArray, padX: Int, padY: Int, scale: Double, width: Int, height: Int): List<Region> }` (`rows` = flattened `[300,6]`; map out of the letterbox, clamp to 0..1, drop score below `DETECTOR_MIN_SCORE` and boxes with no area).
- Port from the benchmark (`runtime-bench` branch, `app/src/androidTest/.../FastDetectorPreprocess.kt`, `OrtDetectorRuntime.kt`, `DetectorDecode.kt`): decode towards 960, one filtered `Canvas.drawBitmap` into a reusable 960x960 `ARGB_8888` letterbox, `getPixels` into a reusable `IntArray`, one loop into a reusable `FloatArray(3*960*960)` then one bulk `put` into the direct `FloatBuffer` (the benchmark's per-element `put` cost 118 ms; do not copy it).

Behaviour: one `Mutex` serialises detections. The session is created lazily from `filesDir/detector/yolo26n-$DETECTOR_MODEL_TAG.ort` (copied from the asset when missing or of the wrong length; a failed copy deletes the partial file). Each tile is detected in turn; tile regions are mapped with `toPage`, seam duplicates merged by union. `LinkageError`/`UnsatisfiedLinkError` or a missing model sets a permanent `unavailable` flag; `OrtException`, `OutOfMemoryError` and `IOException` return `null` for that call only. Stages are traced as `detect.decode`, `detect.preprocess`, `detect.infer`, `detect.post`. `release()` closes the session and buffers only if the lock is free (`tryLock`); `onTrimMemory(level >= TRIM_MEMORY_BACKGROUND)` calls it.

- [ ] **Step 1: Write failing tests** `DetectorDecodeTest`: `mapsLetterboxedBoxToPageFractions` (800x1200 page, side 960, known pad/scale), `dropsLowScoreAndEmptyBoxes`, `clampsToUnitSquare`, `tileOffsetMappingMatchesToPage`.
- [ ] **Step 2: Run** `... --tests "*DetectorDecodeTest"`; expected FAIL. Implement `DetectorDecode`; run again; expected PASS.
- [ ] **Step 3: Write failing device tests** `OrtBubbleDetectorDeviceTest`: `detectsRegionsOnAPage` (a pushed manga page from `getExternalFilesDir("eval")/pages`: non-null, at least one region, every box inside 0..1); `blankPageGivesEmptyNotNull`; `detectAfterReleaseWorksAgain`; `cancelledDetectionLeavesTheLockFree` (cancel then a second `detect` returns within 10 s); `twoConcurrentDetectionsBothComplete`; `corruptBytesReturnNull`; `stripIsTiledAndRegionsStayOnThePage` (synthetic 800x6000 with dark blocks: regions across the page height, boxes ordered inside 0..1).
- [ ] **Step 4: Implement** `BubbleDetector`, `OrtBubbleDetector`, `DetectorPreprocess`, the `onTrimMemory` hook. Run the device tests; expected PASS.
- [ ] **Step 5: Smoke:** with `adb logcat -s PageTrace` run `detectsRegionsOnAPage` on three pages and note `detect.*` stage times in the commit message (the benchmark gave about 168 ms preprocess and 162 ms inference per page; do not claim more than you observe).
- [ ] **Step 6: Commit** `Detect text regions on the device with the minimal ONNX Runtime`.

### Task 7: OCR cache v2

**Review:** per-task — persisted format (cache files, atomic writes, stale generations)

**Files:**
- Modify: `translate/OcrCache.kt`, `OcrCacheTest.kt`, `androidTest/.../OcrCacheDeviceTest.kt`, fakes of `OcrCache` in `EnginePageTranslatorTest.kt` and `TranslationRunnerTest.kt`, `PageEvalTest.kt`

**Interfaces:**
- Consumes: `PageScan`, `Region`, `Box` (Task 3).
- Produces: `const val OCR_PIPELINE_VERSION = 2`; `const val DETECTOR_MODEL_TAG = "dc6b6341"` (first 8 hex of the model hash; Task 6 and the cache file names use it); `interface OcrCache { suspend fun get(galleryId: Int, page: Int, source: String): PageScan?; suspend fun put(galleryId: Int, page: Int, source: String, scan: PageScan) }`; `class FileOcrCache(dir: File, tag: String = DETECTOR_MODEL_TAG)`. File: `cacheDir/ocr/{gallery}/{page}-{source}-v2-{tag}.json`, JSON `{"aspect":0.7,"blocks":[{"text":"..","box":[l,t,r,b]}],"regions":[{"box":[l,t,r,b],"score":0.9}]}`.

Rules: a file with an invalid box (not `0 <= l < r <= 1`, `0 <= t < b <= 1`), a non-finite or non-positive `aspect`, or a score outside 0..1 is a miss. `put` deletes files of the same page and source with another version or tag (stale generations, including phase 1 `*-v1.json`) after writing the new one atomically (existing temp-file-and-move code).

- [ ] **Step 1: Write failing tests:** `roundTripKeepsBlocksRegionsAndAspect`; `emptyRegionsAreValid`; `invalidRegionBoxIsAMiss`; `badAspectIsAMiss`; `otherTagIsAMiss`; `putRemovesStaleGenerations` (a `3-auto-v1.json` and a `3-auto-v2-deadbeef.json` disappear, the page 4 file stays); `oldBlocksOnlyFileIsAMiss`.
- [ ] **Step 2: Run** `... --tests "*OcrCacheTest"`; expected FAIL.
- [ ] **Step 3: Implement.** Update every fake and caller compiled against the old interface; `EnginePageTranslator` is changed in Task 8, so keep it compiling by wrapping `blocks` in a `PageScan(blocks, emptyList(), 1f)` there for now.
- [ ] **Step 4: Run** `:app:testDebugUnitTest`; expected PASS.
- [ ] **Step 5: Commit** `Cache detector regions with the OCR blocks`.

### Task 8: Page scan pipeline

**Review:** per-task — concurrency (parallel OCR and detection, cancellation, nothing cached on failure)

**Files:**
- Modify: `translate/EnginePageTranslator.kt`, `di/OcrModule.kt` (`@Binds abstract fun bindBubbleDetector(impl: OrtBubbleDetector): BubbleDetector`), `app/src/test/.../EnginePageTranslatorTest.kt`

**Interfaces:**
- Consumes: Tasks 2-7.
- Produces: `EnginePageTranslator(client, ocr, detector: BubbleDetector, ocrCache)`; constants `SECOND_PASS_SCORE = 0.5f`, `SECOND_PASS_LIMIT = 8` in `EnginePageTranslator.kt`.

Flow for the MlKit engine: `bytes = image()`; `scan = ocrCache.get(...) ?: scanPage(bytes, source)`; `units = mergeBlocks(scan)`; translate `units` exactly as now (`TranslatedLine.box` is the unit box). `scanPage`: inside `coroutineScope`, start `ocr.recognize` and `detector.detect` as two `async` children (stage `detect` traced around the detector call); `aspect` from `PageDecode.size`; regions `null` means no second pass and **no cache write**. Otherwise second pass: regions with score at least `SECOND_PASS_SCORE` that no block claims (`blockShare >= ASSIGN_SHARE` for any block), best score first, at most `SECOND_PASS_LIMIT`, one `ocr.recognizeRegions` call (stage `ocr2`); extra blocks are added and the list reordered with `orderPage`. `ocrCache.put(PageScan(blocks, regions, aspect))` only for a non-null detector result.

- [ ] **Step 1: Write failing tests** (fakes for `PageOcr`, `BubbleDetector`, `OcrCache`, `TranslationClient` as in the existing test):
  - `detectorNullGivesPhaseOneResultAndWritesNoCache`.
  - `regionJoinsBlocksBeforeTranslation`: client receives one block; the line's box is the union.
  - `silentRegionTriggersOneSecondPassWithItsBox`; `lowScoreRegionDoesNotTriggerSecondPass`; `secondPassIsCappedAtEightRegions`; `secondPassBlocksAreOrderedIntoThePage`.
  - `cacheHitSkipsOcrDetectorAndSecondPass` and merge still applied to the cached scan.
  - `ocrAndDetectorStartBeforeEitherFinishes` (two `CompletableDeferred` gates).
  - `cancelWhileDetectingPropagatesAndCachesNothing`.
  - `pageWithoutTextStillGivesEmptyTranslation`.
- [ ] **Step 2: Run** `... --tests "*EnginePageTranslatorTest"`; expected FAIL.
- [ ] **Step 3: Implement.** `TranslationRunner` is unchanged.
- [ ] **Step 4: Run** the full `:app:testDebugUnitTest` and `:app:assembleDebug`; expected PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `Scan pages with OCR and the detector together`.

### Task 9: Evaluation on the phone, tuning, docs, report

**Files:**
- Modify: `app/src/androidTest/.../PageEvalTest.kt`, `translate/BlockMerge.kt` (constants only, if tuning demands), `docs/manga-reader.md`, `README.md`, `README_RU.md`, `docs/specs/2026-11-15-bubble-detection-and-fit-design.md`
- Create: `docs/reports/2026-11-15-phase2-results.md`

- [ ] **Step 1: Extend the harness** to run the Task 8 scan (detector, merge, second pass) per page and write per page: stage times (`decode`, `recognize`, `detect.*`, `merge`, `ocr2`), peak heap, counts of blocks, regions, units, joined units, second-pass blocks, and an overlay image (regions, blocks, units in three colours). Add a synthetic 800x12000 strip run (text at 8 heights) reporting blocks found and peak heap.
- [ ] **Step 2: Run on the phone** (Global Constraints) over the 30 pages with `source=auto`, three runs, and the strip once. Compare per-stage p50/p95 and peak heap with the `auto` row of the phase 1 results (`docs/reports/2026-11-15-phase1-results.md`, final pipeline).
- [ ] **Step 3: Tune once.** Look at the overlays of at least eight pages for wrong joins (a unit spanning two bubbles) and missed joins; if wrong joins appear, adjust `MAX_JOIN`, `MAX_REGION_AREA` or `ASSIGN_SHARE` in `BlockMerge.kt`, rerun, and record both runs. Do not tune on fewer than the eight pages, and do not add new rules.
- [ ] **Step 4: Docs.** `docs/manga-reader.md`: detector, tiling, merge, second pass, cache v2, when the detector is unavailable. README/README_RU: one sentence on the on-device text detector and the APK growth measured in Task 1. Spec: record that the chosen model has one class, so `Region` has no `kind`, free-text/Sfx separation still comes from the LLM `kind` plus phase 3 geometry, and mark phase 2 done with the measured runtime (ORT-min CPU-4, nano 960).
- [ ] **Step 5: Report** `2026-11-15-phase2-results.md`: the table from Step 2, joins and second-pass counts, strip result, tuning runs, what was not measured (16 KB execution, shared bitmap, battery, other phones), and the owner-manual list: review the overlay sheets, a real provider translation of five pages, pinch/pan on a joined page.
- [ ] **Step 6: Run** `:app:testDebugUnitTest` and `:app:assembleDebug`; expected PASS. **Commit** `Report phase 2 results and document the text detector`.

---

## Self-review

- Spec coverage: detector interface and tiling (Tasks 2, 6), merge rules (3), second-pass OCR (5, 8), cache with model tag (7), model delivery as APK asset because it is 10.5 MB (1), traces and evaluation (6, 9). Geometry, `area`/`fill`, overlay switch: phase 3, not here. The spec's `Region.kind` is dropped because the model has one class (recorded in Task 9).
- Types: `Region`, `PageScan(blocks, regions, aspect)`, `PageTile(top, bottom)`, `PageTiles.plan/dedupeSeams/toPage`, `PageDecode.size/decode/decodeTile`, `BubbleDetector.detect(image): List<Region>?`, `PageOcr.recognizeRegions`, `OcrCache.get/put` over `PageScan` are used with the same names in every task.
- Dependency waves: A = Tasks 1, 2, 3; B = 4, 7; C = 5, 6; D = 8; E = 9. Tasks 4 to 6 and 9 need the phone; Task 1 needs WSL. Shared files: `MlKitPageOcr.kt` (Tasks 4 then 5), `EnginePageTranslatorTest.kt`/fakes (Tasks 5, 7 then 8), `PageEvalTest.kt` (Tasks 5, 7 then 9).
- Open choices for the implementer: exact tile-seam `pick` for regions (union) versus OCR blocks (larger), the benchmark-port details in Task 6, thresholds in Task 3 (tuned in Task 9).
