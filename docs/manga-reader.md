# Native manga reader

The `manga/{id}` route uses Compose and Coil. Downloaded pages take precedence over remote URLs resolved by `NhentaiCdn.page`. Saved library metadata remains usable if the gallery request fails.

## Catalog filters

Language chips include English, Japanese, Chinese, and Translated. Each cycles through include, exclude, and off using the corresponding `language:` tag.

Tag blacklisting applies only to search cards, not the saved library. Search summaries carry tag IDs only. Fetching gallery details teaches the current catalog ViewModel the lowercase name of each returned tag ID; cards with those IDs can then be hidden by an exact, case-insensitive blocked name. Substrings do not match. Results are refiltered when metadata arrives or the blacklist changes, including restoring cards when a blocked name is removed.

## Controls

- Swipe horizontally in paged mode or scroll vertically in strip mode.
- Tap the left or right third to move backward or forward. The mirror setting swaps those two tap zones.
- Tap the center third to show or hide the title, page count, Back, favorite, settings, and page thumbnails. Chrome starts hidden.
- Pinch the current page to zoom from 1× to 5×; drag while zoomed to pan. Double-tap to reset zoom and pan.
- Tap a thumbnail to jump to its page.
- Reader settings select paged/strip mode, contain/fill-width fit, mirrored taps, and page-turn intensity. The sheet scrolls on short viewports, keeping the intensity slider and Save action reachable above the navigation bar.

Contain fits each page inside the viewport. Fill-width preserves the image aspect ratio; tall pages can be scrolled vertically in paged mode and extend naturally in strip mode. Short strip pages occupy at least one viewport so a page jump does not immediately select a neighboring page. Paged mode composes one neighboring page on each side.

## Persistence and haptics

Reader settings use SharedPreferences named `manga_reader`:

| Key | Values | Default |
| --- | --- | --- |
| `mode` | `paged`, `strip` | `paged` |
| `fit` | `contain`, `width` | `contain` |
| `mirror` | boolean | `false` |
| `intensity` | float | `PageTurnHaptic.DEFAULT_INTENSITY` |

The intensity slider calls `MangaViewModel.setIntensity`. Page turns retain the existing pulse duration.

Opening loads the saved zero-based history page through `startPage`. Invalid or absent positions start at zero. `PageTurnGate` suppresses the initial placement and repeated idle reports. Paged mode supplies its settled page; strip mode supplies the page whose center is nearest the viewport center together with the scroll-idle flag. A gate-approved change triggers one pulse and upserts reading history.

Leaving the reader reads the current pager position or live strip layout, even during a fling, and upserts history without emitting a pulse. Writes are ordered and survive cancellation of the navigation-scoped ViewModel. History stores gallery ID, title, thumbnail path, zero-based page, page count, and read timestamp; library rows are not used to store reading progress.

## Translation data and requests

`ReaderSettings` persists four translation choices alongside the existing translation mode: `engine` (`model` by default, or `mlkit`), `sourceLanguage` (`auto` by default), `targetLanguage` (`app` by default), and `overlay` (`false` by default). `app` resolves to the app/device language using the existing Russian/English fallback; an explicit target ISO code is passed through, including codes outside the UI list. The overlay flag is stored here; drawing and settings controls are separate from the translation pipeline.

`PageTranslator` receives a `TranslateRequest(engine, source, target)` with the resolved target. The Model engine sends the page image to the configured provider. The MlKit engine consumes `PageOcr` blocks locally and sends only numbered OCR text to that same provider for translation; a page with no OCR blocks returns an empty translation without an HTTP request. Both network paths require the existing provider consent, reject redirects, and use the same HTTP error mapping.

Translated lines can carry an optional `Box` around their original text. Stored JSON uses `[left, top, right, bottom]` fractions in `0..1`. Model replies may instead use the requested `0..1000` coordinates: parsing scales the whole box when any coordinate exceeds one, clamps and orders its edges, and discards malformed or degenerate boxes without dropping the translated line. OCR translations retain each referenced block's original text and box; invalid indices and repeated references are skipped.

The database and vault continue to treat the `lang` field as an opaque cache key. `TranslationKey.of(engine, source, target)` returns exactly `target` for Model + `auto`, preserving existing caches and backups. Other choices use `target|engine.wire|source`, for example `de|mlkit|ja`. Changing translation choices switches the reader's displayed state and batch to that key; cached entries under other keys remain available.

### Choosing a model

In the provider dialog, **Models** loads the unsaved address/key's `/models` list; selecting a row fills the model field without saving, and manual entry always remains available. Filter for image readers (on by default for Model, off for MlKit; unknown capabilities remain visible), or sort by name, price (input + output at equal token counts; unknown prices last), or measured first-token time. OpenCode Go prices are USD per million tokens with the UTC multiplier at list refresh applied and labelled; OpenRouter's per-token prices are converted to the same unit. Other providers have unknown prices, not an embedded price table. **Measure speed** asks confirmation because its one streaming synthetic-image request costs tokens; no page is sent and no page consent is needed. First-content-token and total times are estimates, timestamped and cached only by host/model in the app cache directory, without keys or vault/database records. Real page time depends on output length and reasoning.



## Overlay on the page

With `overlay` on, a page with a finished translation draws each boxed, non-Sfx line as a white plate over its original text. `fittedPage` excludes letterboxing; the overlay shares the image's transform, so zoom and pan carry the plates with the page. Lines without boxes and Sfx stay in the panel. Settings (`TranslationSettingRows`) holds engine, source, target and overlay controls.

`layoutPlates` runs on `Dispatchers.Default`, keyed by lines, fitted page and density rather than zoom/pan. It grows each original text box through the existing five steps until 13 sp fits or growth is exhausted, searches 6–22 sp, and caps large sizes at 1.25 times the page's positive-size median. Later plates are trimmed against earlier plates on the smaller-overlap axis, but a trim losing more than half the original width/height is skipped; overlaps can remain. Each trimmed plate is re-fitted without exceeding its cap. One balanced-breaking `StaticLayout`, without hyphenation, is shared by fitting and drawing; CJK text can wrap by character. Text that still overflows at 6 sp is clipped to the plate. These are rectangle heuristics, not detected bubble outlines.

## On-device page text recognition

`PageOcr` is bound through Hilt to the singleton `MlKitPageOcr`. It uses ML Kit's bundled Latin,
Japanese, Chinese and Korean text-recognition models (16.0.1); recognition needs no model download
and does not send page images to a server. `ja` and `auto` select Japanese (also reads Latin),
`zh` selects Chinese, `ko` selects Korean, and other source codes select Latin. Each recognizer
is created lazily and retained for the application's lifetime. `auto` does not run a second recognizer.
The MlKit path fetches original bytes on `Dispatchers.Default`, with no JPEG round trip.
For ordinary pages, `OCR_WORK_SIDE = 2000` chooses the largest power-of-two sample retaining
at least that long side without upscaling; the decoded long side can approach 4000 pixels.
Pages taller than twice their width use full-width tiles, 1.5 page-widths high with 20% overlap.
`PageDecode` uses `BitmapRegionDecoder` with a bounded whole-page fallback; seam duplicates
overlapping at least half the smaller block keep the larger box. All boxes map back to the
original page, and reading order is computed there. The Model engine retains its 1600 px
upload JPEG encoding. Invalid OCR bytes throw `IOException`.
A per-instance mutex is acquired before each tile/crop decode; only one recognition bitmap/task
is active at once. Other pages can interleave between tiles. Cancellation stops waiting
immediately, but ML Kit's completion listener retains and recycles the current bitmap and
releases the mutex only when native recognition has finished; the next tile is not started.

Before detector merge, each ML Kit text block remains one output block. Lines below 0.3 confidence, blank lines and
symbol-only noise are removed. Most lines with height at least 1.5 times width make a block vertical:
its columns read right-to-left, top-to-bottom; other blocks use rows top-to-bottom, left-to-right.
Mostly kana/Han text joins without spaces; other scripts join with spaces. Page rows use columns
right-to-left for `ja`/`zh` and for `auto` when most retained blocks are vertical, otherwise left-to-right.
Boxes are clipped and expressed as page fractions. Interval rules remain in `OcrGrouping.kt`.
The synthetic eight-height 800 × 12000 strip is read at all eight heights. After the Task 10
minimum-crop/failure-isolation fix, the older stacked-page strip completes on the same Xiaomi
with 95 first-pass blocks, three second-pass blocks, 6117 ms OCR+detect wall and 390 MiB
sampled heap; provider translation remains untested.

### On-device text detector and merge

`BubbleDetector` is bound to the singleton `OrtBubbleDetector`: bundled YOLO26n at 960 px,
ONNX Runtime 1.32.0 basic-minimal CPU, four intra-op threads and one inter-op thread.
Memory-pattern optimization is disabled. Three interleaved detector-on repeats on the
evaluation phone lowered maximum sampled heap from 475 to 345 MiB; inference p50 changed
from 263 to 266 ms. The CPU arena and thread-spinning defaults remain enabled. This is a
measured tradeoff on that phone, not a universal memory bound.
The native runtime supports arm64-v8a only. OCR and detection run as concurrent coroutine
children over the same page bytes, each owning its decode; they do not share a bitmap.
Detection uses the same overlapping tile plan, maps boxes to page fractions and unions
seam duplicates. Scores below 0.25 are discarded. One mutex protects the lazy ORT session,
tensor and reusable buffers through synchronous inference. Cancellation cannot interrupt
`run()`; cleanup and lock release wait for it to return. `onTrimMemory` releases idle resources.

The model has one text class: `Region` contains only `box` and `score`, not a speech/Sfx kind.
ML Kit remains the only source of text. `mergeBlocks` assigns each block to its greatest
covered-share region (at least `ASSIGN_SHARE = 0.6`; ties prefer the smaller region).
Regions containing 2–6 blocks and occupying at most 35% of the page join those blocks in
geometric reading order, with the union of their text boxes. Other blocks remain unchanged.
This groups text, not bubble outlines; provider `kind` still separates speech and sound effects.

Up to eight regions scoring at least 0.5 and claiming no first-pass block get a second OCR
pass with 10% padding clipped to the page. Each padded rectangle grows symmetrically to at
least 32 original pixels per side, shifting inside page edges as necessary; a page smaller than
32 pixels in either dimension skips region OCR. Sampling preserves that minimum on the
decoded crop, and an undersized decoder result is rejected before ML Kit sees it.
Duplicate second-pass blocks are removed, and the resulting blocks are reordered before merge.
A region crop currently decodes a full-width band before cropping horizontally.

Unavailable ABI/model/runtime or a non-cancellation detector failure falls back to the first
OCR pass without detector grouping or a second pass. `null` means unavailable/failed;
an empty region list is a valid result. Cancellation always propagates. Failure to decode or
recognize one second-pass region skips only that region, retaining other successful regions
and first-pass text; native bitmap/lock ownership still ends only when the task finishes.
If the entire second-pass call fails for another non-cancellation reason, translation uses the
first-pass blocks without detector grouping and does not cache that incomplete scan.
A detector-null scan is also not cached, including on non-arm64 devices; this still loses the
earlier OCR-cache benefit on that path. First-pass OCR failure remains a page failure.


### OCR cache

`FileOcrCache` stores `PageScan(blocks, regions, aspect)` under
`cacheDir/ocr/{galleryId}/{page}-{source}-v2-dc6b6341.json`
(`OCR_PIPELINE_VERSION = 2`, detector-model hash prefix `dc6b6341`).
The blocks include successful second-pass text, before merge; merge runs again on every read,
so changing grouping thresholds does not invalidate OCR. Target language is not part of the key.
Empty blocks/regions are valid hits; missing, malformed, invalid-geometry or old-version files
are misses. A successful same-directory atomic replacement removes obsolete generations
for that page/source, not unrelated entries. Write failure does not prevent translation.
Cache hits skip fetching, OCR, detection and the second pass. The cache is disposable and
never exported; Room, translation JSON and the vault format are unchanged.
The lazy image supplier avoids reading page bytes on hits. Uncached fetch order remains
saved file, reader Coil disk snapshot, then CDN with shared OkHttp resources.

Phone measurements and limits: [phase 1](reports/2026-11-15-phase1-results.md) is historical
A069P context; [phase 2](reports/2026-11-15-phase2-results.md) compares both modes on one
Xiaomi. Grouping reduces unit fragmentation, but time and sampled heap rise; the worker's
overlay review is not owner acceptance. Runtime packaging and APK-size provenance are in
[detector-runtime.md](detector-runtime.md).

Dependency references: [Android setup](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
and [release notes](https://developers.google.com/ml-kit/release-notes). ML Kit requires API 23;
the app retains minSdk 26. In the measured universal debug APK, ML Kit adds one
`libmlkit_google_ocr_pipeline.so` per ABI. Its arm64-v8a and x86_64 ELF load segments are 16 KB
aligned; its armeabi-v7a and x86 segments are 4 KB aligned. APK ZIP alignment passes
`zipalign -c -P 16 4`. In the 3.7.0 release APK, all 64-bit native libraries, including SQLCipher,
pass the 16 KiB ELF alignment check. This is a packaging check, not a device startup test.
