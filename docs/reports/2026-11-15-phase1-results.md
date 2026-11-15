# Phase 1 page pipeline results

Date: 2026-11-15. Device: A069P, Android 17, arm64-v8a; serial `00324362A000776`. Baseline code `931e313`; final pipeline `c5746b8` plus the Task 10 harness summary field. Task 2's detector/runtime spike is separate and is not reported here.

## Result and measurement boundaries

Measured aggregate stage time fell 0.8% for Japanese, 14.9% for English and 10.5% for auto. This is not an unconditional quality/memory win: sampled heap rose in all configurations, Japanese overlapping pairs increased, and English/auto produced more small plates. Do not interpret implementation completion as a release-quality sign-off. The detector/geometry decision still needs the owner's visual assessment and the separate spike.

Three fresh instrumented runs per configuration; median run selected by summed per-page stage times, not by independently cherry-picking stage medians. Same 14 Japanese and 16 English files as the baseline, same fitted 1080 × 1920 layout and device density. Japanese uses `ja`, English uses `en`, and `auto` visits all 30 once per run. The synthetic strip is excluded from those totals. Each page's OCR text is also its deterministic plate text; no provider translation or network latency is measured. Original-text plate metrics cannot predict all target-language translations.

Phone was woken before each run; battery saver was off. Battery temperature was 27°C at baseline setup and 28°C before the final run series; 15 seconds separated runs. Per-stage times are integer ms, p50/p95 nearest rank. Heap is maximum Java-used plus native-allocated MB sampled after stages, not continuous process high-water/RSS. Raw numeric JSON remains outside the repo at `Desktop/manga-tests/eval-results`. No copyrighted page, screenshot or overlay image is committed.

Run totals (sum of measured stage ms):

| Configuration | Baseline runs 1 / 2 / 3 | Selected | Final runs 1 / 2 / 3 | Selected |
|---|---|---:|---|---:|
| ja on jp | 6602 / 6329 / 9343 | 1 | 6702 / 6548 / 5493 | 2 |
| en on eng | 9500 / 10089 / 10191 | 2 | 8882 / 8583 / 8578 | 2 |
| auto on all | 21855 / 17600 / 16680 | 2 | 15756 / 16346 / 8494 | 1 |

The fast third final auto run shows substantial device/run variability; three-run medians do not establish statistical significance.

## Before/after stage measurements

All time cells are p50/p95 ms. Network is unmeasured, not zero. Fetch is local-file reading, not CDN or Coil latency. Baseline `encode` includes bounds, sampled decode, scale and JPEG encode; final `decode` includes bounds plus one raw pixel decode, so compare the whole pipeline rather than just those differently scoped stages.

| Configuration | Build | Fetch | Encode | Decode | Recognize | Group | Layout | Peak sampled heap MB |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| ja on jp | Before | 0/1 | 80/94 | 49/58 | 266/546 | 10/18 | 56/111 | 103 |
| ja on jp | After | 0/1 | removed | 62/121 | 315/551 | 1/25 | 64/110 | 139 |
| en on eng | Before | 0/2 | 84/94 | 47/54 | 321/504 | 14/36 | 130/345 | 112 |
| en on eng | After | 1/1 | removed | 70/102 | 363/663 | 3/24 | 61/88 | 130 |
| auto on all | Before | 0/1 | 76/90 | 46/53 | 334/607 | 10/32 | 36/261 | 147 |
| auto on all | After | 2/5 | removed | 57/77 | 363/605 | 2/8 | 59/98 | 160 |

Raw OCR can retain more pixels than the old exact 1600-long-side upload JPEG. A power-of-two sample targeting 2000 can retain nearly 4000 on the long side; this removes lossy encoding but is not a universal heap reduction. The measured two-concurrent-page smoke below remains below the auto baseline, unlike the full-set final process sample.

## Plate and OCR counts

| Configuration | OCR blocks before / after | Overlap pairs before / after | Pages with overlaps before / after | Plates below 13 sp before / after |
|---|---:|---:|---:|---:|
| ja on jp | 144 / 144 | 21 / 38 | 10 / 13 | 142 / 132 |
| en on eng | 259 / 305 | 117 / 46 | 14 / 14 | 152 / 229 |
| auto on all | 356 / 422 | 114 / 71 | 24 / 24 | 294 / 393 |

A later rectangle is trimmed only if it can retain at least half its original width/height. The algorithm intentionally leaves some overlaps; the plan's broad “overlap-free” description is not its actual contract. Growth, changed line wrapping and OCR block counts also change the candidate rectangles. No bubble-outline containment claim is made.

## Per-task proof and findings

| Task | Exercised evidence | Finding |
|---|---|---|
| 1 | Four trace regressions; real phone harness | Named stages accumulate, including failures; absent trace is a no-op. |
| 3 | Nine baseline runs on `931e313` before any phase 1 changes | Baseline report preserves the selected run and limits. |
| 8 | Pure layout unit tests | Six-step font search, median upper cap, half-size overlap rule, containment and CJK word handling. |
| 4 | Engine routing regression; existing task-cancellation tests; real tall-strip and concurrent-page smoke | Raw bytes only for MlKit; one per-instance OCR lock, released with bitmap recycling on native completion, not waiter cancellation. Two concurrent pages sampled 125 MB against 147 MB auto baseline. |
| 5 | Orientation regressions; final phone auto runs | Per-block geometry and script-based joining; auto remains Japanese without a second recognition, per owner decision. |
| 6 | Seven file-cache regressions, two target/cache-hit regressions, real phone target-change trace | First request: fetch 1, decode 71, recognize 566, group 6 ms; changed target: none of those stages. Intentionally unconfigured provider rejected both sends before network; unit fake-server tests separately prove different translated outputs. |
| 7 | Real phone saved-file/Coil/network-interceptor precedence test | Distinct local/cache/network bytes selected in order; exactly one intercepted network request; no real CDN call. |
| 9 | Four phone StaticLayout tests | Whole words, short-text readable fit, Chinese above 6 sp, and transparent pixels outside an overflowing plate. Shared native draw function is used by the composable. |
| 10 | Nine final harness runs; final unit/build verification | Metrics above; no isolated causal timing attribution to individual tasks. |

F1, F6, F7, F8 and F9 implementation changes are in place. F2's orientation/joining issue is addressed, but automatic recognizer selection is deliberately not extended. F3, F4's bubble outline, F5 and F10 remain for phases 2–4. The upper font cap is not a two-sided consistency band.

### Tall-strip acceptance decision

The synthetic `tall-strip.png` is 800 × 12000, stacked from the evaluation pages outside the repo. The exact baseline `MlKitPageOcr` source from `931e313` was temporarily compiled as an instrumented comparator and fed through baseline `PageImage.encode`; it returned **0 blocks**. Final raw sampled recognition also returned **0 blocks**. This is not a newly introduced recall regression on this strip, but neither pipeline is usable for its small text.

Final trace: decode 164 ms / 40 MB sampled, recognize 1325 ms / 61 MB, group 1 ms / 61 MB; no OOM or crash. Baseline comparator: encode 216 ms, decode 3 ms, recognize 278 ms, group 0 ms; maximum sampled 77 MB in that sequential comparator process. These are one-run diagnostic timings, not median benchmarks. Coordinator explicitly relaxed the text-bearing strip acceptance to bounded memory, no OOM/crash and trace presence; tiling stays in phase 2. Temporary comparator/smoke sources were removed after proof.

## Verification

All commands use `gradlew.bat` with JDK 21 and the installed Android SDK. Final outputs: `:app:testDebugUnitTest` **532 tests, 0 failures, 0 errors, 0 skipped**; `:app:assembleDebug` succeeded. Full unit totals after each code task:

| Task | Passed / total |
|---|---:|
| 1 | 514 / 514 |
| 8 | 519 / 519 |
| 4 | 520 / 520 |
| 5 | 523 / 523 |
| 6 | 532 / 532 |
| 7 | 532 / 532 |
| 9 | 532 / 532 |
| 10 | 532 / 532 |

Expected RED runs: missing trace/layout/cache APIs, missing `raw`, and two orientation failures. Initial input-folder smoke failed on Android 17 because adb-created children were shell-owned; app-created folders followed by pushing files fixed access. Original tall-strip nonempty assertion failed, then acceptance was explicitly changed as described above. Existing compiler deprecation/opt-in warnings elsewhere remain; they did not fail builds.

An extra temporary Compose surface/screenshot smoke failed with “No compose hierarchies found”; device window evidence showed keyguard/NotificationShade and a dreaming lockscreen, including after wake/dismiss-keyguard. This does not establish a production overlay failure, and no visual/no-hitch claim is made; native rendering checks passed. The temporary surface test was removed. The final permanent on-device set is the four plate tests plus cache and fetch-precedence tests.

## Plan deviations and owner manual smoke

- Scope order is 1, 3, 8, 4, 5, 6, 7, 9, 10; Task 2 is assigned elsewhere.
- Owner removed the Korean auto extension; existing explicit `ko`, dependency and settings were left unchanged. Task 5 commit is `Order OCR text by each block's orientation`.
- Used direct serial-qualified adb instrumentation instead of Gradle connected-device discovery, to avoid touching the emulator. Added the required English configuration and recursive `jp`/`eng` input folders.
- Harness baseline includes the production-equivalent upload encode; local reads alone would miss the old JPEG round trip. Network and provider timing cannot be inferred from this harness.
- Moved the harness to raw bytes in Task 4 and to the new plate API in Task 9 so each smoke/build exercises a live API; Task 10 adds the overlapping-page summary.
- Zero-fit plate growth now retains the largest attempted area rather than the old first equal-zero candidate, matching the existing fallback comment and plan intent.
- Windows worktree pointers prevented running the POSIX plan bookkeeping scripts through WSL; native git/Gradle plus an isolated ledger recorded equivalent task/RED/GREEN/commit evidence.
- Tall-strip acceptance was relaxed only by explicit coordinator decision, with both baseline/final counts retained above.

Owner manual smoke, not executed against the user's app/provider:

1. On baseline `931e313`, open ten evaluation pages in a configured gallery with MlKit and overlay enabled; capture screenshots outside the repo and record filenames. No screenshot filenames are claimed here.
2. In an unsaved gallery, open a page so Coil downloads it, then translate it: confirm `fetch.coil` without `fetch.net`; in Whole mode, translate a never-viewed page and confirm `fetch.net`. Instrumented byte/cache precedence was proven, not this full reader/gallery flow.
3. Open a translated page with at least five bubbles, inspect balanced fill and residual overlap, pinch-zoom and pan, and confirm there is no new `layoutPlates` Logcat entry during the transform. Observe first-draw frame smoothness on the actual reader; no no-hitch claim is made by the off-thread implementation or native bitmap tests.

The separate evaluation package was used for every install. The debug-only `.eval` suffix was local/uncommitted and removed before delivery; the user's original package/data were not overwritten or uninstalled. No dependency/native library, Room schema, vault, translation key or translated JSON contract changed. No ref was pushed, no PR/tag created.

## Inputs for the phase 2 decision

Median per-page stage share of measured page elapsed time (each page's stage ms divided by that page's elapsed ms, then p50); these percentages need not sum to 100 because layout, encoding, bookkeeping and independent medians remain. **Network is unmeasured in every row**, not a 0% estimate for real translation.

| Configuration | Fetch before / after | Decode before / after | OCR + grouping before / after | Network |
|---|---:|---:|---:|---|
| ja on jp | 0.0% / 0.0% | 9.3% / 13.7% | 58.0% / 69.6% | unmeasured |
| en on eng | 0.0% / 0.1% | 7.5% / 12.4% | 54.9% / 72.8% | unmeasured |
| auto on all | 0.0% / 0.5% | 8.4% / 10.8% | 66.4% / 76.5% | unmeasured |

OCR + grouping p50/p95 ms on this phone: **ja 280/550 → 317/576; en 337/507 → 372/668; auto 348/639 → 365/609**. Extra raw detail has a recognition/heap cost even after eliminating encode. Before choosing another resolution/runtime, weigh the full-set heap (103→139, 112→130, 147→160 MB), not just the concurrent smoke.

Pages with overlapping plates: **ja 10/14 → 13/14, en 14/16 → 14/16, auto 24/30 → 24/30**. Geometry, not just faster text measurement, is still needed. Tall strip: **0 blocks before and 0 after**; phase 2 must supply tiling before claiming tall-text recall.

No provider-translated page was visually judged in this run. Numeric fragmentation/size flags for owner inspection on auto: `eng/2.webp` has 46 blocks, 32 smaller than 0.1% of page area, median area 0.0735%; `eng/16.webp` has 21 blocks, 13 below 0.1%, median 0.0793%; `eng/14.webp` has 13 blocks, 8 below 0.1%, median 0.0709%. These counts/sizes flag poor grouping candidates, not proven OCR text accuracy or detector coverage. The 800 × 12000 strip's zero blocks is the demonstrated poor-recall page type. Region assignment/merge and targeted second-pass OCR remain relevant, pending the separate detector/runtime/licence spike and owner visual verdict.
