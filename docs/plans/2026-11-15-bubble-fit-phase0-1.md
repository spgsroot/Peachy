# Bubble detection, text fit and pipeline performance: phases 0 and 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure the current MlKit pipeline and the detector candidates (phase 0), then ship every improvement that needs no new dependency: one raw decode, script-aware grouping, a derived OCR cache, a shared HTTP/Coil fetch, and a faster, overlap-free, off-main-thread overlay layout (phase 1).

**Architecture:** A per-page `PageTrace` (coroutine context element) times each stage and feeds an instrumented evaluation harness. The MlKit path takes raw page bytes and decodes once, serialised; `EnginePageTranslator` consults an OCR cache before touching the page. The overlay moves its font search, plate growth and overlap resolution into pure functions over a `TextProbe`, and builds one `StaticLayout` per plate that both measures and draws.

**Tech Stack:** Kotlin, Compose (BOM 2024.09.00), Hilt, OkHttp 4.12, Coil 2.6.0, ML Kit text recognition 16.0.1, JUnit 4, `androidx.test` instrumented tests.

**Spec:** `docs/specs/2026-11-15-bubble-detection-and-fit-design.md` (phases 2-4 are out of scope here and get their own plan after the phase 0 report).

## Global Constraints

- minSdk 26; Compose BOM 2024.09.00; Coil 2.6.0; ML Kit 16.0.1.
- Phase 1 adds no dependency and no native library. The phase 0 spike may add ONNX Runtime/LiteRT only on a throwaway branch that is never merged.
- Nothing about a page leaves the phone because of this work; OCR, caches and layout are local.
- No Room schema change and no vault change. Vault `FORMAT` stays `1`. The OCR cache is derived data under `cacheDir/ocr/`, never exported.
- Cache key `TranslationKey.of(engine, source, target)` is unchanged; Model + `auto` still keys exactly `target`.
- `TranslatedLine`/`PageTranslation` JSON is unchanged in phase 1 (`area`/`fill` arrive in phase 3).
- Copyrighted pages are never committed; the evaluation set lives outside the repo.
- Strictness level: default (user gave no stricter signal): the repo's existing unit tests plus the smoke runs named below.
- Commit messages: one imperative sentence, no prefix, like the existing history.

## Review Focus

Inputs and conditions the spec implies and no happy-path test exercises, most likely first. Each has a test in the task named.

1. A very tall strip (about 800 x 12000) on the MlKit path: bounded decode memory and an ML Kit result, not an OOM or an empty page. (Task 4 smoke)
2. A Chinese or Japanese translation (no spaces) in a plate: it must wrap at any character and must not be judged "word too long" and shrunk to 6 sp. (Task 8, `longestUnbreakableWord`)
3. A translation far too long for its plate: the drawn text stays inside the plate (clipped), never over the art. (Task 9)
4. A corrupt or half-written OCR cache file: treated as a miss, page is recognised again, no crash. (Task 6)
5. A trimmed plate must stay inside the original plate and inside the page. (Task 8, `resolveOverlaps`)

---

## Owner prerequisites

- The page set: about 30 pages in a folder outside the repo, pushed to the phone's `eval/pages` dir (Task 1 prints the exact path).
- A phone connected over `adb`, with the debug app installed.
- For Task 2: a machine with Python 3.10+ and network access to Hugging Face.

## File Structure

| File | Responsibility |
| --- | --- |
| `translate/PageTrace.kt` (new) | Per-page stage timings and heap samples, carried in the coroutine context. |
| `translate/OcrCache.kt` (new) | `OcrCache` interface and `FileOcrCache`. |
| `translate/OcrGrouping.kt` | Per-block orientation and joining; `hangulShare`. |
| `translate/MlKitPageOcr.kt` | Sampled single decode, serialised recognition, Hangul rerun on `auto`. |
| `translate/PageImage.kt` | `PageSource.raw`; `PageBytes` with shared client and Coil snapshot. |
| `translate/EnginePageTranslator.kt`, `TranslationClient.kt` (interface) | New `translate` signature, OCR cache use. |
| `ui/manga/PlateLayout.kt` (new) | Pure functions: `largestFittingSp`, `capToMedian`, `resolveOverlaps`, `overlappingPairs`, `longestUnbreakableWord`. |
| `ui/manga/PlateText.kt` (new) | `StaticLayout` builder shared by measuring and drawing; `layoutPlates`. |
| `ui/manga/TranslationOverlay.kt` | Composable only; layout runs off the main thread. |
| `androidTest/.../translate/PageEvalTest.kt` (new) | Evaluation harness over the owner's page folder. |

## Waves

A: Task 1, Task 2 (owner machine), Task 8. B: Task 4, Task 9 (needs 8). C: Task 5, Task 6, Task 7 (all need 4). D: Task 10. Task 3 is the owner's baseline run after Task 1.

---

### Task 1: Stage tracing and the evaluation harness

**Files:**
- Create: `app/src/main/java/ru/spgsroot/peachy/translate/PageTrace.kt`
- Create: `app/src/test/java/ru/spgsroot/peachy/translate/PageTraceTest.kt`
- Create: `app/src/androidTest/java/ru/spgsroot/peachy/translate/PageEvalTest.kt`
- Modify: `translate/MlKitPageOcr.kt` (stages `decode`, `recognize`, `group`), `translate/EnginePageTranslator.kt` (stage `translate.net`), `translate/PageImage.kt` (stages `fetch`, `encode`), `ui/manga/TranslationOverlay.kt` (make `Plate` and `plateFor` `internal`).

**Interfaces:**
- Produces:
  - `class PageTrace(clock: () -> Long = System::nanoTime, heapMb: () -> Long = ::defaultHeapMb, sink: (String) -> Unit = { Log.d("PageTrace", it) })` with `val stageMs: Map<String, Long>` (insertion order), `val heapAfterMb: Map<String, Long>`, `fun record(name: String, ms: Long)`.
  - `class PageTraceElement(val trace: PageTrace) : AbstractCoroutineContextElement(PageTraceElement)` with `companion object Key : CoroutineContext.Key<PageTraceElement>`.
  - `suspend inline fun <T> traced(name: String, block: () -> T): T`: runs `block`; if the context holds a `PageTraceElement`, records elapsed ms under `name` and the heap in MB after it; if not, just runs `block`. Same name twice in one trace sums the times.
  - `fun defaultHeapMb(): Long`: `(Runtime total - free) + Debug.getNativeHeapAllocatedSize()`, in MB.

- [ ] **Step 1: Write the failing tests** in `PageTraceTest.kt` (`runBlocking`, fake clock advanced inside the block, no-op sink):
  - `tracedRecordsElapsedMillisUnderItsName`: clock advances 5_000_000 ns in the block; `stageMs["decode"] == 5L`.
  - `sameStageNameAccumulates`: two `traced("ocr")` of 3 ms and 4 ms give `7L`.
  - `withoutATraceInContextTracedJustRunsTheBlock`: returns the block's value, nothing thrown.
  - `tracedRecordsEvenWhenTheBlockThrows`: block throws `IOException` after 2 ms; exception propagates and `stageMs["fetch"] == 2L`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "ru.spgsroot.peachy.translate.PageTraceTest"`. Expected: FAIL, `PageTrace` unresolved.
- [ ] **Step 3: Implement `PageTrace.kt`** as specified above; `traced` is `inline` with `try/finally` so the timing is recorded on failure.
- [ ] **Step 4: Run the same command.** Expected: 4 passed.
- [ ] **Step 5: Wrap the stages** in the files listed under Modify with `traced("<stage>") { ... }`. `TranslationRunner.translate` does not create traces; the harness and a debug log do (next step). Do not change behaviour.
- [ ] **Step 6: Write `PageEvalTest`** (`@RunWith(AndroidJUnit4)`): for every file in `targetContext.getExternalFilesDir("eval")/pages`, in name order, create a `PageTrace`, run `withContext(PageTraceElement(trace))` { `PageBytes`-equivalent read of the file, then `MlKitPageOcr().recognize(bytes, source)` }, with `source` from the instrumentation argument `source` (default `auto`). Also build the old plates: for each block make a `TranslatedLine(Speech, text, text, box)` and call `plateFor(...)` through a `TextMeasurer(createFontFamilyResolver(ctx), Density(ctx), LayoutDirection.Ltr)` on a 1080 x 1920 page rect; count overlapping rectangle pairs and plates under 13 sp. Write `eval/report.json` next to `pages/`: per page `{file, width, height, stageMs, heapAfterMb, blocks, oldPlatePairsOverlapping, oldPlatesBelowReadable}`, and a `summary` with p50/p95 per stage and the maximum heap.
- [ ] **Step 7: Run the harness** on the phone: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.spgsroot.peachy.translate.PageEvalTest -Pandroid.testInstrumentationRunnerArguments.source=ja`, then `adb pull` the report. Expected: `report.json` has an entry per page and every stage name appears. If `pages` is empty the test fails with a message printing the exact directory to push to.
- [ ] **Step 8: Run the full unit suite** `./gradlew :app:testDebugUnitTest`. Expected: pass.
- [ ] **Step 9: Commit** `Add stage tracing and an on-device evaluation harness for page OCR`.

### Task 2: Detector and runtime spike (owner machine; throwaway)

**Files:**
- Create: `docs/reports/2026-11-15-detector-spike.md`
- Nothing else is committed; scripts and the dependency edits live on a branch `spike-detector` that is deleted afterwards.

**Interfaces:**
- Consumes: Task 1's `report.json` `blocks` counts (for the coverage proxy); the owner's page folder.
- Produces: the report below, which names the model, the runtime, the delivery (asset or download), `WORK` side, and the 16 KB result. Phase 2's plan is written from it.

- [ ] **Step 1: Download** `Kiuyha/Manga-Bubble-YOLO` (`model.onnx`) and `ogkalu/comic-text-and-bubble-detector` (`detector.onnx`) with `huggingface_hub`. Record file sizes and sha256.
- [ ] **Step 2: Print for each ONNX** input name/shape/dtype, output names/shapes, and the class names from model metadata. Expected: Kiuyha's output is `(1, 300, 6)`; record whether it has one class or several.
- [ ] **Step 3: Run both on every page** with `onnxruntime` (CPU), at 1280 (Kiuyha) and 640 (ogkalu), tall strips tiled vertically with 15% overlap. Save overlay PNGs outside the repo. Compute the coverage proxy: share of ML Kit blocks from `report.json` that lie at least 60% inside one detector region, per model and per page type. Record the owner's verdict on the overlays.
- [ ] **Step 4: Measure the runtime cost.** On `spike-detector` add `com.microsoft.onnxruntime:onnxruntime-android` (latest release at that time, 1.23 or newer) to `app/build.gradle.kts`, build a universal debug APK, and record the APK delta and the per-ABI `.so` sizes. Repeat with `com.google.ai.edge.litert:litert:2.3.2` using a `.tflite` exported from the chosen YOLO weights. Check 16 KB alignment for each added `.so` with `llvm-readelf -lW` (LOAD `Align` 0x4000 on arm64-v8a and x86_64) and `zipalign -c -P 16 4` on the APK, the same procedure `docs/manga-reader.md` records for ML Kit.
- [ ] **Step 5: Time one inference** per page on the phone with the runtime that built (throwaway instrumented test): p50/p95 ms and peak heap at the model's input size.
- [ ] **Step 6: Write the report.** It states: chosen model and its licence text and dataset terms (Manga109-s, Mangadex), chosen runtime, APK delta per ABI, 16 KB result, timings, coverage proxy, `WORK` side recommendation, and delivery: bundled in `assets/` when the model file is at most 15 MB, otherwise first-use download with a pinned sha256. Include the rejected options and why.
- [ ] **Step 7: Commit only the report** `Record the detector and runtime spike results`, then delete `spike-detector`.

### Task 3: Baseline run (owner device)

**Files:**
- Create: `docs/reports/2026-11-15-baseline.md`

**Interfaces:**
- Consumes: Task 1's harness.
- Produces: the "before" numbers Task 10 compares against.

- [ ] **Step 1: Run the harness** on the debug build from the commit after Task 1, once with `source=ja` and once with `source=auto`, three runs each; keep the median run. Record the phone model.
- [ ] **Step 2: Capture overlay screenshots** of ten pages of the set in the reader (MlKit engine, overlay on), from the same build.
- [ ] **Step 3: Write `baseline.md`** with the per-stage p50/p95, max heap, `oldPlatePairsOverlapping` and `oldPlatesBelowReadable` totals, and the screenshots' file names (screenshots stay outside the repo).
- [ ] **Step 4: Commit** `Record the page pipeline baseline`.

### Task 4: One raw decode, one OCR at a time

**Review:** per-task — concurrency (the OCR lock and the bitmap's lifetime under cancellation)

**Files:**
- Modify: `translate/PageImage.kt`, `translate/PageOcr.kt`, `translate/MlKitPageOcr.kt`, `translate/TranslationRunner.kt:66-83`
- Test: `app/src/test/.../translate/TranslationRunnerTest.kt`, `.../EnginePageTranslatorTest.kt` (fake rename)

**Interfaces:**
- Produces:
  - `PageSource.raw(galleryId: Int, page: Int): ByteArray`: the page bytes exactly as saved or downloaded, no decode, no re-encode; same exceptions as `jpeg`. `jpeg` stays and now calls `PageImage.encode(raw(...))`.
  - `PageOcr.recognize(image: ByteArray, source: String): List<OcrBlock>` (parameter renamed from `jpeg`; any format `BitmapFactory` decodes).
  - `internal const val OCR_WORK_SIDE = 2000` in `MlKitPageOcr.kt` (provisional; Task 2's report may change it).
  - `TranslationRunner.translate` asks `source.jpeg(...)` when `request.engine == TranslateEngine.Model` and `source.raw(...)` otherwise.

- [ ] **Step 1: Write the failing test** in `TranslationRunnerTest`: `mlKitEngineGetsRawBytesAndModelEngineGetsJpeg`. `FakeSource` records which method was called per page; translate page 0 with an engine of `MlKit` and page 1 with `Model`; assert `rawCalls == [0]` and `jpegCalls == [1]`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*TranslationRunnerTest"`. Expected: FAIL (`raw` unresolved).
- [ ] **Step 3: Implement** `PageSource.raw`, `PageBytes.raw` (the current `readBytes()`/`download` body), `PageBytes.jpeg = encode(raw)`, and the runner switch. Update `FakeSource` in `TranslationRunnerTest`.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Change `MlKitPageOcr.recognize`:** take a `Mutex` (one per instance) before decoding and release it when the ML Kit task completes, not when the caller is cancelled (the existing `awaitOcrTask` release callback stays the owner of `bitmap.recycle()`; unlock in the same callback). Decode with `BitmapFactory.Options.inSampleSize = PageImage.sampleSize(w, h, OCR_WORK_SIDE)` after a bounds-only pass; no further scaling. Keep `IOException("Cannot decode the page image")` for undecodable bytes. Rename the parameter in `PageOcr`, `MlKitPageOcr` and the two `PageOcr` fakes in `EnginePageTranslatorTest`.
- [ ] **Step 6: Run the unit suite** `./gradlew :app:testDebugUnitTest`. Expected: pass.
- [ ] **Step 7: Smoke on the phone** (Review Focus 1): push an 800 x 12000 test strip next to the evaluation pages and run the harness at `source=auto`. Expected: the strip yields blocks (or none if it has no text) without OOM; the report's `decode` stage and heap for it are recorded. Run the harness with two pages translated concurrently from the reader (Whole mode, MlKit engine): the maximum heap in the trace stays at or below the Task 3 baseline.
- [ ] **Step 8: Commit** `Decode each page once for on-device OCR and run recognition one page at a time`.

### Task 5: Script-aware grouping and the `auto` recognizer

**Files:**
- Modify: `translate/OcrGrouping.kt`, `translate/MlKitPageOcr.kt`
- Test: `app/src/test/.../translate/OcrGroupingTest.kt`

**Interfaces:**
- Produces:
  - `internal fun hangulShare(text: String): Float`: Hangul syllable and Jamo letters divided by all letters; 0 for no letters.
  - `groupOcrBlocks(blocks, imageWidth, imageHeight, source)` keeps its signature with new semantics. A line rect is vertical when `height >= 1.5 * width`. A block is vertical when most of its lines are vertical: vertical blocks read columns right-to-left, top-to-bottom within a column; others read rows top-to-bottom, left-to-right. A block's lines join without a space when its text is mostly kana/Han, otherwise with one space. Page order is columns right-to-left when `source` is `ja` or `zh`, or `auto` with more than half the blocks vertical; otherwise rows left-to-right. Existing confidence, symbol-only and clipping rules are unchanged.

- [ ] **Step 1: Write the failing tests** in `OcrGroupingTest` (update the existing tests that pin "ja/zh are always vertical" to the new rule):
  - `autoWithTallLinesReadsColumnsRightToLeftWithoutSpaces`
  - `autoWithWideLatinLinesReadsRowsWithSpaces`
  - `japaneseSourceWithHorizontalLinesReadsRowsAndJoinsWithoutSpaces`
  - `autoPageWithMostlyVerticalBlocksPutsTheRightBlockFirst`
  - `hangulShareIsZeroForLatinAndOneForHangulOnly` (`"hello"` is 0, `"안녕하세요"` is 1, a mixed `"안녕 ab"` is `2/4`)
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*OcrGroupingTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the rules above in `OcrGrouping.kt`; keep the interval-overlap helpers as they are.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: In `MlKitPageOcr`**, for `source == "auto"`: run the Japanese recognizer; if `hangulShare` of its joined text is at least `0.3f`, run the Korean recognizer on the same bitmap inside the same lock and use its blocks. Latin-only pages do not rerun (Review Focus note: `hangulShare == 0`).
- [ ] **Step 6: Smoke:** run the harness at `source=auto` on one Korean and one English page; the report shows Korean text recognised on the Korean page and no rerun on the English one (`recognize` stage appears twice only for the Korean page).
- [ ] **Step 7: Commit** `Order OCR text by each block's orientation and pick the Korean recognizer on auto when the page is Hangul`.

### Task 6: OCR cache that survives a change of target language

**Review:** per-task — persisted format (cache files, atomic writes)

**Files:**
- Create: `translate/OcrCache.kt`, `app/src/test/.../translate/OcrCacheTest.kt`
- Modify: `translate/TranslationClient.kt` (`PageTranslator` interface and `TranslationClient.translate`), `translate/EnginePageTranslator.kt`, `translate/TranslationRunner.kt`, `di/AppModule.kt`
- Test: `.../EnginePageTranslatorTest.kt`, `.../TranslationRunnerTest.kt`, `.../TranslationClientTest.kt` (call sites)

**Interfaces:**
- Produces:
  - `interface OcrCache { suspend fun get(galleryId: Int, page: Int, source: String): List<OcrBlock>?; suspend fun put(galleryId: Int, page: Int, source: String, blocks: List<OcrBlock>) }`. `null` is a miss; an empty list is a cached "page has no text".
  - `class FileOcrCache(private val dir: File) : OcrCache`: file `dir/{galleryId}/{page}-{source}-v{OCR_PIPELINE_VERSION}.json`, body `{"blocks":[{"text":"…","box":[l,t,r,b]}]}`; write to a temp file then rename; an unreadable or malformed file is a miss; a write failure is swallowed (the cache is an optimisation).
  - `const val OCR_PIPELINE_VERSION = 1` in `OcrCache.kt`.
  - `PageTranslator.translate(galleryId: Int, page: Int, request: TranslateRequest, image: suspend () -> ByteArray): PageTranslation`. `image` is invoked only when bytes are needed. `TranslationClient.translate` invokes it once; `EnginePageTranslator` MlKit branch invokes it only on an OCR cache miss.
  - `EnginePageTranslator(client: TranslationClient, ocr: PageOcr, ocrCache: OcrCache)`. Provide `OcrCache` in `AppModule` as `FileOcrCache(File(context.cacheDir, "ocr"))`.

- [ ] **Step 1: Write the failing tests.**
  - `OcrCacheTest` (JUnit `TemporaryFolder`): `roundTripKeepsTextAndBox`; `missingEntryIsNull`; `emptyListRoundTripsAsEmptyNotNull`; `corruptFileIsAMiss` (write `{"blocks":[` to the target path; `get` returns null, no exception); `differentSourceOrPageAreSeparateEntries`; `staleVersionFileIsIgnored` (a file with another version suffix is not read).
  - `EnginePageTranslatorTest`: `secondRequestWithAnotherTargetReusesTheCachedOcr` (two `translate` calls with targets `ru` and `en`: `FakeOcr.calls == 1`, the `image` lambda ran once, both translations differ); `ocrCacheHitDoesNotLoadTheImage` (pre-seeded cache: the `image` lambda is never invoked and `FakeOcr.calls == 0`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*OcrCacheTest" --tests "*EnginePageTranslatorTest"`. Expected: FAIL (types missing).
- [ ] **Step 3: Implement** `OcrCache.kt`, the new `PageTranslator` signature (update `TranslationClient`, `EnginePageTranslator`, `TranslationRunner` which passes `{ if (request.engine == Model) source.jpeg(...) else source.raw(...) }`, and `AppModule`), and update every fake and call site in the three test files.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest`. Expected: pass.
- [ ] **Step 5: Smoke:** translate a page with MlKit to Russian, then switch the target to English and translate it again; the trace shows `fetch`, `decode` and `recognize` only the first time.
- [ ] **Step 6: Commit** `Cache OCR results per page so changing the target language does not read the page again`.

### Task 7: Shared HTTP client and the reader's downloaded page

**Files:**
- Modify: `translate/PageImage.kt` (`PageBytes`), `di/AppModule.kt`

**Interfaces:**
- Produces: `@Provides @Singleton fun provideHttpClient(): OkHttpClient` in `AppModule`, used by `provideTranslationClient` and by `PageBytes`. `PageBytes(library: MangaLibraryRepository, http: OkHttpClient, @ApplicationContext context: Context)`. Source order in `raw`: saved copy, then the Coil disk cache entry for the CDN URL (`context.imageLoader.diskCache?.openSnapshot(url)`, bytes of `snapshot.data`), then the network with timeouts 20 s connect / 30 s read via `http.newBuilder()`. Stage names `fetch.local`, `fetch.coil`, `fetch.net`.

- [ ] **Step 1: Implement** as above. Pages absent from every source still throw `IOException`/`IllegalArgumentException` as before.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest`. Expected: pass (no test touches `PageBytes` construction; if one does, update it).
- [ ] **Step 3: Smoke:** open an unsaved gallery page in the reader, then translate it with the MlKit engine; the trace shows `fetch.coil` and no `fetch.net` for that page, and a page never shown in the reader (Whole mode) shows `fetch.net`.
- [ ] **Step 4: Commit** `Reuse the reader's downloaded page and one HTTP client for translation fetches`.

### Task 8: Pure plate layout functions

**Files:**
- Create: `ui/manga/PlateLayout.kt`, `app/src/test/.../ui/manga/PlateLayoutTest.kt`

**Interfaces:**
- Produces (all `internal`, `Rect` is `androidx.compose.ui.geometry.Rect`):
  - `fun interface TextProbe { fun fits(sp: Float, widthPx: Float, heightPx: Float): Boolean }` (bound to one line's text and kind by the caller).
  - `fun largestFittingSp(probe: TextProbe, widthPx: Float, heightPx: Float, minSp: Float = 6f, maxSp: Float = 22f): Float`: 0 when `minSp` does not fit; `maxSp` when it fits; otherwise 6 bisection steps, returning the largest size that fit.
  - `fun capToMedian(sizes: List<Float>, factor: Float = 1.25f): List<Float>`: median of the positive sizes; any size above `median * factor` becomes `median * factor`; zeros and smaller sizes are unchanged.
  - `fun resolveOverlaps(rects: List<Rect>): List<Rect>`: rects are in reading order; each later rect is trimmed against every earlier result along the axis with the smaller overlap, on the side facing the earlier rect; a trim that would leave less than half of the rect's original width (height for a vertical trim) is skipped.
  - `fun overlappingPairs(rects: List<Rect>): Int`: pairs with positive-area intersection.
  - `fun longestUnbreakableWord(text: String): String`: the longest whitespace-separated token that contains no CJK ideograph, kana or Hangul; `""` when there is none.

- [ ] **Step 1: Write the failing tests** in `PlateLayoutTest`:
  - `largestFittingSp`: probe fits iff `sp <= 10.3f` returns a value in `[10.2, 10.3]`; always-fits returns 22; never-fits returns 0.
  - `capToMedian`: `[10, 10, 10, 30]` becomes `[10, 10, 10, 12.5]`; `[0, 12, 12]` keeps the 0; empty list returns empty.
  - `resolveOverlaps`: two rects overlapping side by side: the first is unchanged, the second ends where the first begins, `overlappingPairs == 0`; disjoint rects unchanged; a rect that would lose more than half its width keeps its overlap; every result is inside its original rect (Review Focus 5).
  - `longestUnbreakableWord`: `"a bb ccc"` is `"ccc"`; `"这是一个很长的句子"` is `""`; `"say 你好 loudly"` is `"loudly"`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*PlateLayoutTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the five functions.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** `Add pure plate layout functions for font search, size capping and overlap resolution`.

### Task 9: Overlay layout off the main thread, one layout for measure and draw

**Files:**
- Create: `ui/manga/PlateText.kt`, `app/src/androidTest/.../ui/manga/PlateTextTest.kt`
- Modify: `ui/manga/TranslationOverlay.kt`

**Interfaces:**
- Consumes: Task 8's functions; `fittedPage`, `GROWTH`, `MIN_READABLE_SP` (kept).
- Produces:
  - `internal class PlacedPlate(val rect: Rect, val layout: StaticLayout, val textTop: Float)`.
  - `internal fun plateLayout(text: String, kind: LineKind, sizePx: Float, widthPx: Int): StaticLayout`: `TextPaint` black, bold for `Sign`, italic for `Thought`, centred, `BREAK_STRATEGY_BALANCED`, hyphenation off, line spacing multiplier 1.1, no font padding.
  - `internal fun layoutPlates(lines: List<TranslatedLine>, page: Rect, density: Density): List<PlacedPlate>`: for each line with a box and not `Sfx`, in order: growth steps as today, each tested with `largestFittingSp` through a probe that builds `plateLayout` and also rejects when `longestUnbreakableWord` is wider than the inner width; stop growing at `MIN_READABLE_SP`; then `capToMedian` on the sizes, `resolveOverlaps` on the rects, then re-fit each trimmed plate (size never larger than the capped one; 6 sp minimum even if it overflows). Inner area is the plate minus 2 dp horizontally and 1 dp vertically on each side.

- [ ] **Step 1: Write the failing instrumented tests** in `PlateTextTest` (run on the phone):
  - `noWordIsSplitAcrossLines`: a plate of 160 x 120 dp with a text of ordinary words; each original word appears whole inside one layout line.
  - `layoutFitsTheInnerAreaWhenAFontSizeFits`: for a short text in a 200 x 100 dp plate, `layout.height <= inner height` and `sp >= 13`.
  - `chineseTranslationIsNotShrunkToTheMinimum`: a 40-character Chinese string in a 150 x 90 dp plate gets a size above 6 sp.
  - `overlongTextIsClippedToThePlate` (Review Focus 3): a 600-character text in a 60 x 40 dp plate gets 6 sp, and the composable's draw clips to the plate rect (assert `layout.height > inner height` and that the draw function applies `clipRect` by drawing into a `Bitmap` canvas and checking that pixels outside the plate rect stay transparent).
- [ ] **Step 2: Run** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.spgsroot.peachy.ui.manga.PlateTextTest`. Expected: FAIL (types missing).
- [ ] **Step 3: Implement** `PlateText.kt` as specified.
- [ ] **Step 4: Rewrite `TranslationOverlay`:** `produceState(emptyList(), lines, page, density) { value = withContext(Dispatchers.Default) { layoutPlates(lines, page, density) } }`; draw each plate as before (white 0.93 plate, 6 dp corners) and render the layout with `drawIntoCanvas { clipRect(plate); translate(.., textTop); layout.draw(nativeCanvas) }`. Delete the old `plateFor`, `fit`, `plateStyle`, `Plate` and the `TextMeasurer` use.
- [ ] **Step 5: Run** the instrumented test above and `./gradlew :app:testDebugUnitTest`. Expected: pass.
- [ ] **Step 6: Smoke:** open the reader on the evaluation pages with the MlKit engine and overlay on; pinch-zoom and pan on a page with five or more bubbles. Expected: no frame hitch on first draw, plates do not overlap, text fills round bubbles evenly, zoom does not re-layout (the trace/Logcat shows no `layoutPlates` call during zoom).
- [ ] **Step 7: Commit** `Lay out overlay plates off the main thread with one layout shared by fitting and drawing`.

### Task 10: Harness plate metrics, docs, and the Phase 1 report

**Files:**
- Modify: `androidTest/.../translate/PageEvalTest.kt`, `docs/manga-reader.md` (sections "Overlay on the page" and "On-device page text recognition"; add an "OCR cache" paragraph), `docs/specs/2026-11-15-bubble-detection-and-fit-design.md` (status line)
- Create: `docs/reports/2026-11-15-phase1-results.md`

**Interfaces:**
- Consumes: Task 9's `layoutPlates`, Task 3's baseline.

- [ ] **Step 1: Switch the harness's plate metrics** to `layoutPlates`: `plateOverlappingPairs` via `overlappingPairs`, `platesBelowReadable` as plates whose size is under 13 sp.
- [ ] **Step 2: Run the harness** three times each for `source=ja` and `source=auto`; keep the median run.
- [ ] **Step 3: Update `docs/manga-reader.md`:** the OCR section now says: one sampled decode at `OCR_WORK_SIDE`, one recognition at a time, per-block orientation and joining, `auto` reruns Korean when Hangul share is at least 0.3, and the OCR cache location, key and version; the overlay section describes growth, size cap relative to the median, overlap trimming, balanced breaking and off-thread layout. Remove sentences that are now false (the `auto` ordering sentence, the "binary search" sentence).
- [ ] **Step 4: Write `phase1-results.md`:** per-stage p50/p95 and max heap, plate metrics, before (Task 3) and after, per task; state which spec findings are closed (F1, F2, F6, F7, F8, F9) and that F3, F4 (bubble outline), F5 and F10 remain for phases 2-4.
- [ ] **Step 5: Run** `./gradlew :app:testDebugUnitTest`. Expected: pass.
- [ ] **Step 6: Commit** `Report phase 1 results and document the new OCR and overlay behaviour`.

---

## Self-review

- Spec coverage: findings F1 (Task 4), F2 (Task 5), F6 and F4 partly (Tasks 8, 9), F7 (Task 4), F8 (Task 6), F9 (Task 7); evaluation harness and baseline (Tasks 1, 3); phase 0 spike including licence record, 16 KB and runtime size (Task 2). F3, F5, F10, detector, geometry, `area`/`fill` are phases 2-4 by design.
- Deviation from the spec: font consistency is an upper cap only (`capToMedian`), not a two-sided band, because raising a small size can overflow its plate.
- Type consistency: `PageSource.raw`, `PageOcr.recognize(image, source)`, `PageTranslator.translate(galleryId, page, request, image)`, `OcrCache.get/put(galleryId, page, source, ...)`, `OCR_WORK_SIDE`, `OCR_PIPELINE_VERSION`, `TextProbe`, `PlacedPlate` are each defined once and used with the same names.
