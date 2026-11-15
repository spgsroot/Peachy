# Phase 2: on-device text detector results

Date: 2026-11-15. Implementation evaluated: Task 8 commit `4164573`, including the exception-enabled runtime merged from `a5197a0`, plus the Task 9 instrumented harness. Device: Xiaomi `22071212AG`, Android 15, arm64-v8a, serial `EM4HJR5PJNCYB6PR`, 4 KB kernel pages. Only the local `ru.spgsroot.peachy.pd2` copy and its test package were installed; the owner's application was not replaced.

## Result

On the same 30 pages, detector-on produced **328 translation units instead of 422** per run, including **102 joined units** and **63 second-pass blocks on 16 pages**. Extra text is **not verified as real/correct text**. The worker's overlay inspection found useful within-balloon joins, missed joins, and suspect joins across balloon lobes; this is not owner acceptance or a measured accuracy score.

The cost is material: pooled OCR+detect wall p50/p95 rose from **247/424 ms to 547/1266 ms**, and maximum sampled heap rose from **202 to 510 MiB**. The eight-height synthetic strip completed with all eight words, but the historical stacked-page strip still fails end-to-end: tiling recovers 95 first-pass blocks, then an undersized second-pass crop throws. Implementation/report completion is not release-quality sign-off.

## Runtime and Step 0

- AAR: `app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar`, 2,012,345 bytes, SHA-256 `9056463442826ec41474891032c630c9bfac87f8604b12d4c5267ebd77d0ee56` (recomputed for this report).
- ONNX Runtime 1.32.0 basic-minimal, CPU provider, intra-op 4 / inter-op 1, C++ exceptions enabled, telemetry disabled. No XNNPACK, NNAPI or GPU provider.
- Model: bundled `detector/yolo26n.ort`, 10,513,152 bytes, SHA-256 `dc6b6341319acc41f115a5c7239f888e9ef344eb492447600d21a46d691506c2`; 960 × 960 RGB-114 letterbox, NCHW float32 /255, one text class, score floor 0.25, output `[1,300,6]`, no external NMS.
- Step 0: `OrtExceptionRecoveryDeviceTest.malformedModelsThrowAndValidInferenceStillWorksInTheSameProcess` passed on this phone. A 1 MiB prefix of the real model threw Java `OrtException` (`ORT model verification failed`); valid zero-input inference then returned `[1,300,6]`; deterministic 4096-byte random input also threw `OrtException`, and the same process survived. The continuation reran this test: **1 test, 0 failures**. This does not prove native OOM recovery, every error path, or inference after the random-model rejection specifically.
- Packaging provenance is in [detector-runtime.md](../detector-runtime.md): original Task 1 universal debug APK growth **11,638,608 bytes (11.6 MB decimal)**; a separate clean-build old/new runtime comparison added **770,048 bytes** for exceptions. These are different comparison pairs, not a new combined APK measurement; neither estimates release/Play download size.

## Method and raw evidence

Both modes used the same Xiaomi in one session, the same 14 Japanese + 16 English files from `Desktop/manga-tests/{jp,eng}`, and `source=auto` (Japanese recognizer, which also reads Latin). The phase 1 A069P [report](2026-11-15-phase1-results.md) is historical context only, **not this baseline**.

`PageEvalTest` runs the real `EnginePageTranslator` up to its deliberately unconfigured provider. It records the OCR blocks, detector regions, second-pass selection/results, merged units, stage times and sampled heap. A capture-only cache always misses and performs no disk write. Mode `off` uses the phase-1-equivalent single sampled whole-page OCR path and a detector returning `null`; mode `on` uses production tiled OCR, detector, second pass and merge. None of the 30 ordinary pages exceeds the tiling threshold (maximum height/width 1.4141), and their 422 first-pass blocks match in both modes and all runs.

Wall timing starts after page bytes are read and stops after the engine reaches the provider configuration rejection (or returns an empty translation). It includes first OCR, concurrent detection, second OCR, the engine's merge and small local provider-validation overhead, **not provider translation, network, image fetch, disk-cache I/O, layout or overlay drawing**. No page is sent to a provider. `merge` is a separate pure-function replay after wall timing, explicitly flagged in each row; it is not added to wall time.

Stage times are integer milliseconds. `decode`, `recognize` and `group` accumulate first-pass and second-pass OCR calls; `ocr2` contains those second-pass stages and de-duplication, and `detect` contains `detect.*`. Stages overlap/nest: **do not sum their percentiles to infer wall time**. Missing stages are zero in pooled percentiles (57/90 on-mode page visits requested a second pass). Heap is the maximum of Java-used plus native-allocated bytes sampled after traced stages, divided by 1024²: **not continuous high-water, RSS/PSS or an allocation profile**. It includes retained process/recognizer/session resources; overlay drawing is outside measurement.

The prior worker's six complete page-series logs and eight-height strip logs were inspected; all report `OK (1 test)`. Each page-series JSON contains exactly the same 30 filenames, no incomplete rows, and `source=auto`. All pooled stage/wall percentiles below were independently recomputed from the 180 raw page rows using nearest rank and match `aggregate.json`. No missing/invalid main series required a rerun. No warm-up page was excluded; the first run also creates diagnostic overlays outside its wall measurements, so run-to-run numbers are retained rather than claiming statistical significance.

| Order | Mode/run | Start–end UTC | Battery before series | Wall p50/p95 ms | Total page wall ms | Max sampled heap MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 1 | off/1 | 13:23:03–13:23:26 | 28.5 °C | 248/424 | 8131 | 202 |
| 2 | on/1 | 13:23:26–13:24:02 | 28.5 °C | 547/1309 | 21238 | 510 |
| 3 | off/2 | 13:24:02–13:24:13 | 28.7 °C | 252/427 | 7993 | 163 |
| 4 | on/2 | 13:24:13–13:24:37 | 28.7 °C | 521/1256 | 20698 | 481 |
| 5 | off/3 | 13:24:37–13:24:47 | 28.7 °C | 241/412 | 7658 | 163 |
| 6 | on/3 | 13:24:47–13:25:11 | 29.0 °C | 517/1262 | 20720 | 488 |

The synthetic strips followed at 13:25:11 (off) and 13:25:14 (on), both at 29.0 °C. The historical strip's off run started 13:27:59 at 29.1 °C; its failed on run has a separate battery log at 13:30:12 showing 29.0 °C, although the failed run is absent from the final run-order array. All recorded series temperatures were below 40 °C. Stay-on settings were not changed by this continuation.

Raw artifacts remain outside Git under `C:/Users/spgs/Desktop/manga-tests-out/phase2/final/`:

- `run-order.json`, `aggregate.json`, `logs/` (including per-series instrumentation/battery logs and `ort-recovery-logcat.txt`).
- `phase2-final/{off,on}/run-{1,2,3}/report.json`, individual numeric page rows and run-1 three-panel PNGs: blue regions, green blocks, red units.
- Run-1 `strip.json`; off `historical-strip.json`; on `historical-strip.failure.json` and the failed instrumentation log, retained rather than counted as a completed scan.
- `tuning-maxjoin4/`: one additional 30-page series, `run-order.json`, `instrumentation.txt`, `run-4/` JSON/overlays and the continuation's `ort-recovery-rerun.txt`.

Copyrighted page files, recognized page text, overlays and extra weights are not committed. This report contains only page identifiers, measurements and diagnostic observations.

## Same-phone measurements

Pooled nearest-rank p50/p95 across 90 page visits per mode (three runs × 30 pages), not the median of run percentiles:

| Measurement | Detector off | Detector on |
| --- | ---: | ---: |
| OCR `decode`, ms | 69/82 | 136/480 |
| OCR `recognize`, ms | 167/312 | 407/751 |
| OCR `group`, ms | 1/5 | 2/6 |
| Total `detect`, ms | disabled (0/0) | 344/396 |
| `detect.decode`, ms | — | 58/80 |
| `detect.preprocess`, ms | — | 11/14 |
| `detect.infer`, ms | — | 266/304 |
| `detect.post`, ms | — | 0/1 |
| `merge` pure replay, ms | 0/0 | 1/3 |
| `ocr2`, ms (zero when absent) | 0/0 | 213/901 |
| First-pass OCR wall, ms | 244/421 | 320/519 |
| End-to-end OCR+detect wall, ms | **247/424** | **547/1266** |
| Total wall over 90 visits, ms | 23782 | 62656 |
| Maximum sampled heap, MiB | **202** | **510** |

`translate.net` is only the local unconfigured-provider path here (off 0/1 ms, on 0/2 ms), not a network measurement. Concurrent inference and extra OCR both add cost; these data do not isolate CPU contention from decode/second-pass work.

### Counts and second pass

All three runs have identical count totals, so the table below is **per 30-page run**, not 90 independent pages:

| Count | Detector off | Detector on |
| --- | ---: | ---: |
| First-pass blocks | 422 | 422 |
| Detector regions | 0 (disabled) | 408 |
| Requested second-pass regions | 0 | 98 |
| Added second-pass blocks after their seam de-duplication | 0 | 63 |
| Pages with added second-pass blocks | 0 | 16/30 |
| Final blocks before merge | 422 | 485 |
| Translation units | 422 | 328 |
| Joined units (at least two member blocks) | 0 | 102 |
| Blocks removed by joining | 0 | 157 |
| Detector-null pages | 30/30 (forced off) | **0/30** |

Across all three on runs: 294 requested regions, 189 added blocks, 306 joined units, 984 translation units and **0/90 detector-null results**. Forced-null off results are not runtime failures. Zero observed nulls does not validate unsupported ABIs or every failure mode.

`ocr2` consumed 29,878 ms across 294 requested regions: **101.6 ms per requested region**, weighted by region count. The distribution of per-page `ocr2 / requestedRegions` on pages requesting regions was p50 **100.0 ms**, p95 **115.25 ms**. This is a per-page mean, not individually instrumented crop latency. A region need not yield a block. Added blocks may be genuine small text, partial/duplicate text or recognition noise; **text realness and transcription accuracy remain unverified**.

Pages with second-pass blocks (same in all three runs): `eng/1.webp`, `eng/2.webp`, `eng/3.webp`, `eng/6.webp`, `eng/7.webp`, `eng/8.webp`, `eng/9.webp`, `eng/10.webp`, `eng/11.webp`, `eng/12.webp`, `eng/13.webp`, `eng/14.webp`, `eng/15.webp`, `jp/5.webp`, `jp/15.webp`, `jp/23.webp`.

### Per-page wall and counts

Wall columns are the median of that page's three complete runs in each mode. Counts are stable across the three on runs. “Joined” counts merged units, not the number of eliminated blocks.

| Page | Off wall ms | On wall ms | First / final blocks | Regions | Units (joined) | OCR2 blocks |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `eng/1.webp` | 412 | 779 | 18 / 20 | 11 | 10 (4) | 2 |
| `eng/10.webp` | 274 | 1293 | 13 / 23 | 23 | 14 (5) | 10 |
| `eng/11.webp` | 246 | 1012 | 8 / 13 | 14 | 8 (4) | 5 |
| `eng/12.webp` | 359 | 1014 | 16 / 18 | 19 | 12 (4) | 2 |
| `eng/13.webp` | 283 | 1168 | 12 / 14 | 17 | 11 (3) | 2 |
| `eng/14.webp` | 252 | 1122 | 13 / 17 | 28 | 15 (2) | 4 |
| `eng/15.webp` | 329 | 1098 | 20 / 23 | 25 | 17 (4) | 3 |
| `eng/16.webp` | 286 | 673 | 21 / 21 | 15 | 10 (4) | 0 |
| `eng/2.webp` | 427 | 1070 | 46 / 47 | 19 | 36 (5) | 1 |
| `eng/3.webp` | 327 | 934 | 25 / 28 | 17 | 18 (5) | 3 |
| `eng/4.webp` | 328 | 521 | 21 / 21 | 11 | 11 (6) | 0 |
| `eng/5.webp` | 326 | 792 | 19 / 19 | 17 | 10 (4) | 0 |
| `eng/6.webp` | 242 | 1262 | 8 / 18 | 22 | 12 (4) | 10 |
| `eng/7.webp` | 272 | 1192 | 10 / 17 | 24 | 12 (5) | 7 |
| `eng/8.webp` | 361 | 1244 | 16 / 20 | 24 | 17 (3) | 4 |
| `eng/9.webp` | 247 | 930 | 12 / 18 | 14 | 14 (3) | 6 |
| `jp/10.webp` | 209 | 324 | 11 / 11 | 10 | 9 (1) | 0 |
| `jp/13.webp` | 189 | 311 | 9 / 9 | 7 | 7 (2) | 0 |
| `jp/15.webp` | 189 | 465 | 6 / 7 | 9 | 7 (0) | 1 |
| `jp/22.webp` | 188 | 313 | 8 / 8 | 7 | 7 (1) | 0 |
| `jp/23.webp` | 191 | 373 | 10 / 11 | 7 | 7 (4) | 1 |
| `jp/24.webp` | 193 | 310 | 10 / 10 | 8 | 8 (2) | 0 |
| `jp/26.webp` | 186 | 301 | 10 / 10 | 10 | 8 (2) | 0 |
| `jp/3.webp` | 174 | 298 | 7 / 7 | 3 | 3 (3) | 0 |
| `jp/4.webp` | 199 | 295 | 14 / 14 | 6 | 6 (4) | 0 |
| `jp/5.webp` | 201 | 517 | 8 / 10 | 9 | 8 (2) | 2 |
| `jp/6.webp` | 180 | 309 | 12 / 12 | 6 | 5 (4) | 0 |
| `jp/7.webp` | 240 | 322 | 13 / 13 | 10 | 10 (3) | 0 |
| `jp/8.webp` | 210 | 313 | 12 / 12 | 6 | 6 (5) | 0 |
| `jp/9.webp` | 203 | 307 | 14 / 14 | 10 | 10 (4) | 0 |

## Strip diagnostics

Both inputs are 800 × 12000. These are single diagnostic runs, excluded from ordinary-page percentiles.

| Input/mode | First-pass blocks | Regions | Final units | Wall ms | Max sampled heap MiB | Outcome |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Eight-height synthetic, off | 8 | disabled | 8 | 274 | 89 | Complete |
| Eight-height synthetic, on | 8 | 8 | 8 | 3867 | 374 | Complete; no second pass |
| Historical stacked pages, off | **0** | disabled | 0 | 297 | 57 | Complete, no text |
| Historical stacked pages, on | **95** | 84 | unavailable | **5964 partial** | **390 partial** | Second-pass failure; no cache write |

The synthetic input draws `ALPHA`, `BRAVO`, `CHARLIE`, `DELTA`, `ECHO`, `FOXTROT`, `GOLF`, `HOTEL` at y = 1000 + 1400 × index, 110 px sans. Both modes returned exactly those eight words in order. It proves the tiled path handles eight separated heights, **not an eight-versus-zero recall improvement**: the simpler large text is readable even when sampled as a whole page.

The zero-block comparison belongs to the historical strip stacked from evaluation pages, also zero in the phase 1 report. The same-Xiaomi off run confirms zero again. On mode recovers 95 first-pass blocks, then `MlKitPageOcr.recognizeRegions` fails at `recognizer.process` with `MlKitException: InputImage width and height should be at least 32!`. One requested unpadded box is only about 13 original pixels wide; 10% padding does not guarantee ML Kit's minimum. Recorded pre-failure stages: OCR decode 2046 ms, recognize 1923 ms, group 68 ms; detection 4960 ms (decode 1457, preprocess 195, infer 3209, post 1); `ocr2` 989 ms. These overlap and are partial. There is no successful merged-unit or end-to-end translation result for this input; do not present 95 as a completed output or claim the historical strip is fixed.

## Overlay inspection and one tuning trial

The continuation worker directly inspected the following **eight run-1 on-mode sheets**; the judgment is the worker's, **not the owner's**, and does not certify OCR wording, speech/Sfx labels or translation quality. Each sheet shows regions / blocks / merged units separately.

| Page | Worker observation at original thresholds |
| --- | --- |
| `eng/1.webp` | Top dialogue, left caption and right narration fragments mostly collapse within their own regions; title/credit blocks also join. No clear unit spanning two separate ordinary balloons in this sheet. Some large Sfx have no OCR block. |
| `eng/2.webp` | Several balloon/caption fragments join correctly, but the upper-left and lower-right dense captions remain heavily split: their regions contain 9 and 10 blocks, above the six-block cap. |
| `eng/3.webp` | Separate top-row dialogue stays separate while fragments within it join; the large lower title becomes one broad unit, and some Sfx remain unread. |
| `eng/4.webp` | Five fragments in the upper central balloon become one useful unit. In the middle row, an existing OCR box crosses the two neighboring dialogue areas; region merge does not repair that pre-existing block geometry. |
| `jp/3.webp` | Three broad regions reduce seven blocks to three units. Upper-right paired lobes, left stacked lobes and the large lower-right lobed balloon are each covered by one unit; these are suspect cross-lobe joins, not proof of one semantic bubble. |
| `jp/4.webp` | The top-left region joins five blocks across two stacked lobes; a middle-left region also spans two lobes. Smaller isolated balloons remain separate. The stacked joins are suspect. |
| `jp/5.webp` | Narrow vertical groups largely stay localized; two small within-region joins reduce ten blocks to eight units. Several detector-only/Sfx areas have no retained text. |
| `jp/6.webp` | The broad left-middle region joins five blocks across two stacked lobes. Isolated right and bottom text stays separate. The left join is suspect. |

One permitted-constant trial lowered **`MAX_JOIN` from 6 to 4**, leaving `ASSIGN_SHARE = 0.6` and `MAX_REGION_AREA = 0.35` unchanged. A freshly built suffixed app/test pair ran all 30 pages once (`on/run-4`, 13:39:40–13:40:16 UTC, battery 28.6 °C), with overlays explicitly enabled. Instrumentation passed. Blocks and region arrays match the original run exactly, isolating the merge change.

| Tuning measurement, 30 pages | Original cap 6, on/run-1 | Trial cap 4, on/run-4 |
| --- | ---: | ---: |
| First / final blocks | 422 / 485 | 422 / 485 |
| Regions / second-pass blocks | 408 / 63 | 408 / 63 |
| Units / joined units | 328 / 102 | 362 / 94 |
| Removed blocks by joining | 157 | 123 |
| Wall p50/p95 ms | 547/1309 | 555/1270 |
| Total wall ms | 21238 | 21199 |
| Merge replay p50/p95 ms | 0/2 | 1/2 |
| Max sampled heap MiB | 510 | 504 |

The worker also inspected trial sheets for `jp/3.webp`, `jp/4.webp`, `jp/6.webp` and `eng/4.webp`. Cap 4 removes the top-left join on `jp/4` and left-middle join on `jp/6`, but replaces them with multiple individual columns, not two correctly grouped lobes. It leaves all `jp/3` suspect joins and the middle-left `jp/4` join unchanged. It also breaks the useful five-fragment single-balloon join on `eng/4`; other lost joins occur on `eng/1`, `eng/2`, `eng/3` and `eng/16`.

**Decision: reject cap 4 and retain the original constants (6, 0.35, 0.6).** It trades eight joined units for 34 extra units without resolving the broad-region problem reliably. This is not a claim that cap 6 is correct: cross-lobe joins and missed dense-caption joins remain open for owner review. No new rule was added and no tuning code is committed. Both runs and the rejection rationale are retained; the three original runs remain the reported final-configuration measurements.

## Open issues and unmeasured work

1. **Detector-null cache loss:** `EnginePageTranslator.scanPage` returns a first-pass scan without writing the OCR cache when detection returns `null`. Non-arm64 devices and detector-failed pages therefore lose the phase 1 cache benefit and repeat OCR on a target change/retry. First-pass text still reaches translation; this is not full performance equivalence to phase 1.
2. **Second-pass failure loses the page:** a failure inside `recognizeRegions` propagates and fails the whole page instead of retaining first-pass blocks. The historical strip demonstrates the below-32-pixel-crop case above; no cache is written. Fixing this production behavior is outside Task 9's allowed harness/docs/constants scope.
3. **USB installation prompts:** Xiaomi/HyperOS can prompt “Install via USB” on every install; prior work required per-install approval. The continuation used the verified-suffix install helper, which returned prompt-free `Success` for its app and test updates; it never selected “Remember my choice.” Do not assume unattended installs work consistently.
4. **Grouping is not bubble geometry:** one detector rectangle can contain several lobes, and an OCR block can already cross dialogue areas. The rejected cap trial does not fix these cases; dense captions above the join cap remain fragmented. No owner-labeled coverage/precision/recall score exists.
5. **Latency and heap regressions:** the pooled wall and sampled-heap increases above remain; no speed/memory win is claimed. Region OCR currently decodes a full-width band before horizontal cropping.

Not measured: execution on 16 KB-page devices (alignment checks alone do not prove it); a shared OCR/detector bitmap (each decodes separately); battery energy or sustained thermal throttling (battery temperature was only a pre-series guard); other phones/chipsets or non-arm64 fallback execution; native OOM injection; real provider translation quality/latency, verified transcription/second-pass text realness, owner-marked bubble coverage, plate containment/overlap or pinch/pan/render performance. Phase 3 geometry, `area`/`fill` and the overlay switch are not implemented here. Room, translation JSON and vault formats are unchanged.

## Verification and deviations

Final production constants were restored to their original values and the local application ID suffix was removed before building. The final command was:

```bat
set JAVA_HOME=C:\Users\spgs\.jdks\jbr-21.0.11
set ANDROID_HOME=C:\Users\spgs\AppData\Local\Android\Sdk
gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Result: **BUILD SUCCESSFUL**, **616 JVM tests, 0 failures, 0 errors, 0 skipped in 75 suites**; both debug APKs assembled. The clean-test task forces the unit suite to execute rather than reusing a previous result. Generated APK metadata identifies `ru.spgsroot.peachy` and `ru.spgsroot.peachy.test`; these unsuffixed artifacts were **not installed** on the phone.

Device evidence: six successful main page-series instrumentation tests, two successful eight-height strip tests, the successful off historical-strip diagnostic and the failed on historical-strip diagnostic from the prior worker; the continuation adds one successful 30-page tuning test and one successful Step 0 recovery test. These are separate evidence sets, not a claim that the entire connected-device suite passed. Final review was a self-review, per the one-worker/no-helper restriction; no independent review was dispatched.

Deviations from the original Task 9 wording: same-phone off/on replaces the cross-phone A069P comparison as required by the continuation; two distinct strip inputs are reported rather than misattributing zero blocks to the easier eight-height input; tuning was tried once and rejected rather than silently retaining worse grouping. The historical-strip failure is reported, not suppressed or fixed outside scope. Serial-qualified direct instrumentation targets only the permitted suffixed copy.

Cleanup verified: `adb -s EM4HJR5PJNCYB6PR uninstall ru.spgsroot.peachy.pd2.test` and `uninstall ru.spgsroot.peachy.pd2` each returned `Success`; subsequent `pm list packages ru.spgsroot.peachy` returned no matching packages. The final Git diff contains no `applicationId` change and no `BlockMerge.kt` change; the rejected trial and local suffix are not part of the deliverable.

## Owner-manual acceptance, not executed

- Review the original and tuning overlay sheets, especially `jp/3`, `jp/4`, `jp/6`, the pre-existing crossing block on `eng/4`, and dense captions on `eng/2`; decide how connected balloon lobes should group.
- Translate five representative pages through a real configured provider and assess source transcription, second-pass text, speech/Sfx kinds and translation quality.
- Pinch/pan a joined page in the actual reader and check plate placement, clipping, overlap and responsiveness. This harness does not exercise that UI surface.
