# Detector and Android runtime spike

Date: 2026-11-15. Task 2, steps 1–4; desktop investigation, not the phone baseline. No detector or runtime is integrated into production code.

## Decision and limits

**Provisional model: Kiuyha YOLO26n at 1280; runtime: ONNX Runtime 1.32.0 for a direct-ONNX implementation.** Nano has one `text` class, not bubble/free-text classes: it can propose text groups, but cannot alone implement the design's semantic split or recover bubble outlines. Its 6,069,760-byte ONNX fits the plan's 15 MB asset rule; bundle that pinned artifact if this candidate is adopted. The stock universal runtime's APK cost is a separate shipping decision, not justified by the small weights.

RT-DETR supplies the desired three classes and visibly separates text boxes from bubble envelopes, but its 168,481,531-byte ONNX needs first-use download under the plan's rule. Small YOLO costs 20,251,671 bytes and has the same single-class limitation. Counts do not establish recall or a quality winner. Keep ML Kit as the source of text; no go/no-go quality claim is possible before owner review and the other worker's OCR baseline.

Detector input remains 1280 for Kiuyha, 640 for RT-DETR. Keep the plan's OCR `WORK = 2000` provisional: this spike did not vary OCR decode resolution, measure OCR quality, or justify changing it.

## Artifacts and licences

All weights, scripts, model-card snapshots, JSON, APKs and page overlays remain under `C:/Users/spgs/Desktop/manga-tests-out/spike/`, outside Git. Python environment: `.venv`, Python 3.12.10; `huggingface_hub 2.4.0`, `onnx 1.23.2`, `onnxruntime 1.32.0`, `numpy 2.5.4`, Pillow 13.0.0, Ultralytics 8.4.213. Downloads used `hf_hub_download(repo_id=..., filename=..., revision=..., local_dir=...)` with the revisions below.

The Kiuyha repository has **no `model.onnx` or `model.pt` at its root**. The actual files are `onnx/yolo26n.onnx`, `onnx/yolo26s.onnx`, `weights/yolo26n.pt`, `weights/yolo26s.pt`; both variants were downloaded and evaluated.

| Repository | Pinned revision |
| --- | --- |
| Kiuyha/Manga-Bubble-YOLO | `fb646500455e8a8a3a807fd27b855c8e4fc63766` |
| ogkalu/comic-text-and-bubble-detector | `16e8a622f91fabc6b5b65c96d32d1183f8843546` |

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `Kiuyha/Manga-Bubble-YOLO/onnx/yolo26n.onnx` | 6,069,760 | `b45c2e12cf0c3c1d2abfbbb9123c9f96f040f2ac36a0842382ecd9d859c851c7` |
| `Kiuyha/Manga-Bubble-YOLO/onnx/yolo26s.onnx` | 20,251,671 | `030961ef6041b17d43103e17c8b4dbd8840067e89d8898a4a12081bfe702b7cc` |
| `Kiuyha/Manga-Bubble-YOLO/weights/yolo26n.pt` | 5,364,293 | `29dbed070efdfa9b2b9f0b6a393bd9d02b86876ab54976fdf0a5cbd28b5c4334` |
| `Kiuyha/Manga-Bubble-YOLO/weights/yolo26s.pt` | 20,291,973 | `3aa52b2893ffc798d4f0e5b46e3c78c75afd3acf1eaae14ab8cfbf34b4ab201f` |
| `ogkalu/comic-text-and-bubble-detector/detector.onnx` | 168,481,531 | `065744e91c0594ad8663aa8b870ce3fb27222942eded5a3cc388ce23421bd195` |

### Licence text and training-data terms actually shown

- [Kiuyha card, pinned revision](https://huggingface.co/Kiuyha/Manga-Bubble-YOLO/blob/fb646500455e8a8a3a807fd27b855c8e4fc63766/README.md): YAML says `license: apache-2.0` and `base_model: Ultralytics/YOLO26`; there is no full licence grant in the body. **Both ONNX metadata maps instead say exactly `AGPL-3.0 License (https://ultralytics.com/license)`**. This is a documented conflict, not evidence that derivative weights are Apache-only. The owner has accepted AGPL lineage in the spec; this report does not resolve legal interpretation.
- Kiuyha names `hal-utokyo/Manga109-s`, `Manga109-s` (~3,000 images), `Mangadex-EN` (~2,000) and `Mangadex-VI` (~1,000), 5,595 total after filtering. It credits Magi for auto-annotation of Mangadex images and states exactly: **“Note: The dataset cannot be released due to copyright concerns.”** It gives no separate dataset licence or commercial redistribution/training permission. The linked Manga109-s dataset card returned HTTP 401 during this spike; its additional terms were not verified. Model-card `apache-2.0` is not a grant for the page data.
- [ogkalu card, pinned revision](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/blob/16e8a622f91fabc6b5b65c96d32d1183f8843546/README.md): its only licence text is YAML `license: apache-2.0`. It describes “about 11k Manga, Webtoon, Manhua and Western Comic style Images”; **no named training datasets, dataset licences, copyright permissions or dataset terms are supplied**. Do not infer that its training pages are Apache-licensed. The model has no ONNX metadata licence field.

## ONNX contracts and decoding

| Artifact | Input names / declared shapes / dtypes | Output names / declared shapes / dtypes | Concrete output in this run |
| --- | --- | --- | --- |
| Kiuyha nano | `images [batch,3,height,width] float32` | `output0 [batch,300,6] float32` | `(1,300,6)` at 1280 |
| Kiuyha small | `images [batch,3,height,width] float32` | `output0 [batch,300,6] float32` | `(1,300,6)` at 1280 |
| RT-DETR | `images [N,3,H,W] float32`; `orig_target_sizes [N,2] int64` | `labels [Sublabels_dim_0,Sublabels_dim_1] int64`; `boxes [GatherElementsboxes_dim_0,GatherElementsboxes_dim_1,Sublabels_dim_0] float32`; `scores [GatherElementsboxes_dim_0,GatherElementsboxes_dim_1] float32` | `labels (1,300)`, `boxes (1,300,4)`, `scores (1,300)` at 640 |

The odd RT-DETR symbolic dimension names are copied from the graph; concrete execution, not those names, establishes the last box dimension of four.

Kiuyha metadata: `names = {0: 'text'}`, `imgsz = [1280,1280]`, `stride = 32`, `version = 8.4.14`, `end2end = True`, arguments `half=True`, `dynamic=True`, `simplify=True`, `nms=False`. **Exactly one class in both variants.** The half-export argument does not make the external tensor fp16: the graph and ONNX Runtime both report float32 inputs/outputs.

The [Ultralytics export contract](https://docs.ultralytics.com/modes/export/#what-do-the-output-tensors-represent-in-exported-yolo-models) explicitly defines the NMS-free six columns as **`[x1,y1,x2,y2,confidence,class_id]`**, not centre/size or separate per-class score channels. [End-to-end guide](https://docs.ultralytics.com/guides/end2end-detection/#exported-outputs): `xyxy`, confidence filtering only, no external NMS. This matches the artifact's `end2end=True`, `nms=False`, `(1,300,6)` contract. Coordinates are in input pixels; undo letterbox padding and scale, clip to the page, retain `confidence >= 0.25`, and use column 5 as integer class id. No sigmoid or NMS was added for single-page inference.

RT-DETR's ONNX custom metadata is **empty**; it cannot print class names from metadata. The pinned [config.json](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/blob/16e8a622f91fabc6b5b65c96d32d1183f8843546/config.json) and card agree: `0: bubble`, `1: text_bubble`, `2: text_free`. Its graph postprocessor converts centre/size predictions to `xyxy`, applies sigmoid and top-300 selection, and returns ready-to-filter labels, boxes and scores. **This particular ONNX expects `orig_target_sizes = [[width,height]]`**, unlike some Transformers APIs: graph `Tile` repeats this input directly and multiplies `[x1,y1,x2,y2]` by `[width,height,width,height]`, with no axis swap. An initial height/width feed visibly displaced boxes; it was corrected from graph inspection and all RT-DETR pages were rerun. Only corrected geometry and timings are reported.

## Desktop evaluation

Owner files: `C:/Users/spgs/Desktop/manga-tests/{eng,jp}/*.webp`: 16 English and 14 Japanese pages, all width 1280 and height 1786–1810. No page has height/width > 2, so no tiling was used; the planned 15% overlap seam path was not exercised.

Windows 11, AMD Ryzen 7 9700X (8 cores / 16 logical processors); ONNX Runtime CPUExecutionProvider, intra-op 4 threads, inter-op 1. One zero-tensor warmup per session excluded; one measured inference per page, batch 1. Some desktop work/builds overlapped the first evaluation, so these are informational PC timings, not a controlled benchmark or a phone speed ranking.

Kiuyha preprocessing: RGB, aspect-preserving bilinear resize into 1280 square, centred padding RGB 114, NCHW float32 /255. RT-DETR: RGB bilinear stretch to 640 square, NCHW float32 /255, no mean/std normalization, consistent with its pinned `preprocessor_config.json`; target sizes are original width/height. Confidence threshold 0.25 for all models; no page-level NMS. Timings exclude session creation and overlay drawing/file writes. `Infer` is only `session.run`; `Page` includes decode, preprocessing, inference, filtering and coordinate mapping. Scores below threshold are not tabulated.

Overlays: `overlays/{nano,small,rtdetr}/{eng,jp}/{page}.png` (90 PNGs). Raw per-detection boxes and scores, per-class counts and min/p50/p90/max are in `evaluation.json`. Contact sheets are `eng-contact.png` and `jp-contact.png`.

### Count totals and owner-visible interpretation

| Model / class | English 16 pages | Japanese 14 pages | Total |
| --- | ---: | ---: | ---: |
| nano / text | 327 | 114 | 441 |
| small / text | 353 | 117 | 470 |
| rtdetr / bubble | 149 | 112 | 261 |
| rtdetr / text_bubble | 145 | 111 | 256 |
| rtdetr / text_free | 86 | 36 | 122 |

Worker visual review, **not an owner approval or ground-truth annotation**: inspected eng/1,2,8,16 and jp/3,10,22,26 across all three models. Nano and small generally box text clusters rather than entire bubble envelopes; jp/3 includes merged adjacent text/bubble content. Small is not uniformly better: jp/3 has 4 regions versus nano's 5, jp/10 9 versus 10, while eng/8 has 29 versus 25. RT-DETR visibly gives separate bubble-envelope and inner-text boxes on jp/10 and jp/26, and free-text/SFX boxes on English pages, but text-free detections are not a reliable automatic SFX verdict. These observations support grouping proposals and semantic differences, not measured recall. Nested bubble + text_bubble detections describe the same speech twice and must not be counted as two recovered bubbles. Unboxed lettering and boundary/merged-region cases remain visible in the sheets; no complete bubble inventory was marked.

### Per-page counts, scores and PC time

Each class cell is **`count; min / p50 / p90 / max confidence`** over retained regions. A zero count has no score quantiles. Confidence values rounded to three decimals, milliseconds to one decimal. These are score distributions, not calibrated probabilities or accuracy.

#### Kiuyha nano, 1280

| Page | text | Infer ms | Page ms |
| --- | --- | ---: | ---: |
| eng/1.webp | 12; 0.454 / 0.730 / 0.942 / 0.948 | 121.9 | 238.1 |
| eng/2.webp | 27; 0.255 / 0.602 / 0.930 / 0.941 | 67.2 | 142.7 |
| eng/3.webp | 17; 0.560 / 0.908 / 0.950 / 0.954 | 66.1 | 143.6 |
| eng/4.webp | 13; 0.476 / 0.943 / 0.952 / 0.954 | 70.1 | 146.5 |
| eng/5.webp | 18; 0.273 / 0.749 / 0.950 / 0.953 | 66.6 | 146.0 |
| eng/6.webp | 23; 0.269 / 0.774 / 0.940 / 0.960 | 63.6 | 131.1 |
| eng/7.webp | 26; 0.351 / 0.716 / 0.928 / 0.960 | 103.9 | 178.2 |
| eng/8.webp | 25; 0.261 / 0.825 / 0.950 / 0.956 | 187.9 | 293.1 |
| eng/9.webp | 13; 0.542 / 0.880 / 0.954 / 0.959 | 180.4 | 285.1 |
| eng/10.webp | 24; 0.336 / 0.803 / 0.950 / 0.955 | 517.1 | 699.2 |
| eng/11.webp | 17; 0.310 / 0.716 / 0.944 / 0.951 | 223.3 | 363.0 |
| eng/12.webp | 19; 0.310 / 0.862 / 0.946 / 0.953 | 363.7 | 525.3 |
| eng/13.webp | 20; 0.288 / 0.807 / 0.949 / 0.959 | 275.2 | 425.9 |
| eng/14.webp | 29; 0.264 / 0.747 / 0.945 / 0.962 | 227.5 | 476.0 |
| eng/15.webp | 27; 0.254 / 0.827 / 0.946 / 0.960 | 326.8 | 704.4 |
| eng/16.webp | 17; 0.264 / 0.709 / 0.949 / 0.951 | 379.7 | 498.6 |
| jp/3.webp | 5; 0.400 / 0.472 / 0.831 / 0.833 | 158.5 | 253.7 |
| jp/4.webp | 7; 0.353 / 0.637 / 0.899 / 0.911 | 100.3 | 187.2 |
| jp/5.webp | 9; 0.338 / 0.752 / 0.863 / 0.889 | 101.4 | 208.6 |
| jp/6.webp | 6; 0.572 / 0.703 / 0.890 / 0.914 | 72.1 | 143.2 |
| jp/7.webp | 11; 0.293 / 0.723 / 0.839 / 0.896 | 70.0 | 150.0 |
| jp/8.webp | 6; 0.738 / 0.899 / 0.918 / 0.919 | 67.2 | 141.8 |
| jp/9.webp | 10; 0.669 / 0.866 / 0.902 / 0.929 | 74.4 | 147.4 |
| jp/10.webp | 10; 0.316 / 0.851 / 0.912 / 0.924 | 126.0 | 206.7 |
| jp/13.webp | 8; 0.383 / 0.831 / 0.910 / 0.923 | 61.1 | 126.2 |
| jp/15.webp | 8; 0.301 / 0.823 / 0.882 / 0.892 | 67.7 | 132.5 |
| jp/22.webp | 7; 0.584 / 0.860 / 0.893 / 0.906 | 64.3 | 127.8 |
| jp/23.webp | 7; 0.821 / 0.870 / 0.908 / 0.931 | 111.9 | 195.4 |
| jp/24.webp | 9; 0.503 / 0.791 / 0.891 / 0.912 | 85.8 | 166.4 |
| jp/26.webp | 11; 0.281 / 0.853 / 0.901 / 0.915 | 68.8 | 148.9 |

#### Kiuyha small, 1280

| Page | text | Infer ms | Page ms |
| --- | --- | ---: | ---: |
| eng/1.webp | 14; 0.376 / 0.842 / 0.954 / 0.958 | 404.6 | 512.9 |
| eng/2.webp | 29; 0.526 / 0.843 / 0.954 / 0.968 | 192.6 | 304.3 |
| eng/3.webp | 17; 0.413 / 0.949 / 0.960 / 0.964 | 217.5 | 329.4 |
| eng/4.webp | 12; 0.319 / 0.955 / 0.966 / 0.971 | 192.9 | 269.3 |
| eng/5.webp | 24; 0.304 / 0.843 / 0.959 / 0.973 | 201.3 | 271.4 |
| eng/6.webp | 22; 0.669 / 0.906 / 0.955 / 0.959 | 185.4 | 250.6 |
| eng/7.webp | 26; 0.414 / 0.879 / 0.960 / 0.968 | 182.4 | 258.2 |
| eng/8.webp | 29; 0.325 / 0.899 / 0.961 / 0.973 | 227.9 | 294.3 |
| eng/9.webp | 15; 0.302 / 0.915 / 0.954 / 0.958 | 202.2 | 271.3 |
| eng/10.webp | 26; 0.261 / 0.893 / 0.949 / 0.972 | 248.1 | 319.0 |
| eng/11.webp | 20; 0.371 / 0.864 / 0.957 / 0.966 | 299.4 | 386.1 |
| eng/12.webp | 22; 0.465 / 0.909 / 0.959 / 0.971 | 272.4 | 371.3 |
| eng/13.webp | 22; 0.258 / 0.887 / 0.952 / 0.965 | 278.7 | 354.5 |
| eng/14.webp | 31; 0.281 / 0.869 / 0.960 / 0.967 | 300.6 | 390.1 |
| eng/15.webp | 27; 0.341 / 0.904 / 0.960 / 0.967 | 1344.5 | 1457.6 |
| eng/16.webp | 17; 0.288 / 0.896 / 0.961 / 0.966 | 507.9 | 646.9 |
| jp/3.webp | 4; 0.405 / 0.723 / 0.906 / 0.920 | 188.7 | 240.1 |
| jp/4.webp | 6; 0.329 / 0.854 / 0.916 / 0.931 | 183.0 | 233.0 |
| jp/5.webp | 10; 0.255 / 0.801 / 0.887 / 0.903 | 473.6 | 535.3 |
| jp/6.webp | 6; 0.323 / 0.898 / 0.940 / 0.951 | 207.9 | 278.4 |
| jp/7.webp | 13; 0.273 / 0.667 / 0.890 / 0.907 | 216.1 | 281.8 |
| jp/8.webp | 7; 0.252 / 0.921 / 0.934 / 0.934 | 201.8 | 260.9 |
| jp/9.webp | 11; 0.316 / 0.915 / 0.939 / 0.942 | 210.2 | 265.5 |
| jp/10.webp | 9; 0.757 / 0.885 / 0.914 / 0.916 | 171.1 | 230.2 |
| jp/13.webp | 10; 0.482 / 0.843 / 0.935 / 0.940 | 193.7 | 254.8 |
| jp/15.webp | 6; 0.832 / 0.888 / 0.921 / 0.929 | 180.4 | 247.5 |
| jp/22.webp | 7; 0.674 / 0.915 / 0.933 / 0.936 | 187.4 | 252.7 |
| jp/23.webp | 7; 0.784 / 0.947 / 0.951 / 0.952 | 196.1 | 266.8 |
| jp/24.webp | 9; 0.336 / 0.911 / 0.938 / 0.947 | 187.6 | 262.5 |
| jp/26.webp | 12; 0.302 / 0.888 / 0.951 / 0.954 | 197.5 | 271.7 |

#### ogkalu RT-DETR, 640

| Page | bubble | text_bubble | text_free | Infer ms | Page ms |
| --- | --- | --- | --- | ---: | ---: |
| eng/1.webp | 8; 0.492 / 0.916 / 0.958 / 0.971 | 8; 0.446 / 0.872 / 0.960 / 0.964 | 11; 0.251 / 0.332 / 0.833 / 0.846 | 183.9 | 249.4 |
| eng/2.webp | 9; 0.385 / 0.963 / 0.966 / 0.971 | 8; 0.955 / 0.966 / 0.970 / 0.971 | 8; 0.256 / 0.342 / 0.581 / 0.791 | 179.6 | 233.4 |
| eng/3.webp | 13; 0.354 / 0.966 / 0.972 / 0.972 | 13; 0.302 / 0.939 / 0.956 / 0.956 | 20; 0.251 / 0.458 / 0.781 / 0.886 | 173.7 | 221.2 |
| eng/4.webp | 10; 0.294 / 0.961 / 0.971 / 0.975 | 9; 0.933 / 0.948 / 0.955 / 0.959 | 0 | 173.8 | 228.7 |
| eng/5.webp | 8; 0.944 / 0.962 / 0.974 / 0.975 | 8; 0.930 / 0.955 / 0.964 / 0.967 | 4; 0.316 / 0.531 / 0.688 / 0.702 | 185.8 | 244.6 |
| eng/6.webp | 9; 0.272 / 0.969 / 0.975 / 0.976 | 9; 0.291 / 0.936 / 0.963 / 0.968 | 2; 0.412 / 0.431 / 0.446 / 0.450 | 171.2 | 222.0 |
| eng/7.webp | 9; 0.892 / 0.957 / 0.971 / 0.971 | 9; 0.901 / 0.947 / 0.959 / 0.963 | 3; 0.279 / 0.389 / 0.508 / 0.538 | 182.6 | 234.6 |
| eng/8.webp | 13; 0.381 / 0.957 / 0.974 / 0.975 | 13; 0.330 / 0.939 / 0.956 / 0.963 | 2; 0.299 / 0.309 / 0.317 / 0.319 | 191.3 | 244.2 |
| eng/9.webp | 6; 0.928 / 0.965 / 0.973 / 0.974 | 6; 0.917 / 0.942 / 0.961 / 0.966 | 7; 0.261 / 0.385 / 0.573 / 0.588 | 196.0 | 251.4 |
| eng/10.webp | 10; 0.268 / 0.968 / 0.972 / 0.974 | 10; 0.265 / 0.953 / 0.961 / 0.965 | 3; 0.277 / 0.471 / 0.582 / 0.610 | 188.8 | 242.9 |
| eng/11.webp | 6; 0.952 / 0.965 / 0.970 / 0.971 | 6; 0.948 / 0.955 / 0.961 / 0.962 | 6; 0.265 / 0.423 / 0.544 / 0.559 | 183.1 | 236.1 |
| eng/12.webp | 10; 0.313 / 0.956 / 0.970 / 0.971 | 9; 0.850 / 0.944 / 0.963 / 0.966 | 6; 0.256 / 0.401 / 0.555 / 0.597 | 201.7 | 255.8 |
| eng/13.webp | 9; 0.288 / 0.944 / 0.958 / 0.971 | 8; 0.371 / 0.947 / 0.952 / 0.952 | 4; 0.283 / 0.322 / 0.400 / 0.426 | 201.2 | 273.7 |
| eng/14.webp | 11; 0.559 / 0.960 / 0.969 / 0.974 | 11; 0.454 / 0.943 / 0.959 / 0.961 | 3; 0.332 / 0.420 / 0.475 / 0.489 | 208.5 | 263.8 |
| eng/15.webp | 11; 0.590 / 0.937 / 0.969 / 0.974 | 12; 0.272 / 0.945 / 0.960 / 0.961 | 1; 0.512 / 0.512 / 0.512 / 0.512 | 180.8 | 234.5 |
| eng/16.webp | 7; 0.260 / 0.967 / 0.971 / 0.975 | 6; 0.925 / 0.949 / 0.960 / 0.963 | 6; 0.266 / 0.359 / 0.500 / 0.511 | 178.6 | 232.7 |
| jp/3.webp | 7; 0.434 / 0.968 / 0.973 / 0.978 | 6; 0.896 / 0.943 / 0.948 / 0.951 | 0 | 189.8 | 228.9 |
| jp/4.webp | 10; 0.563 / 0.964 / 0.975 / 0.976 | 11; 0.282 / 0.934 / 0.952 / 0.960 | 2; 0.265 / 0.298 / 0.324 / 0.331 | 174.3 | 211.8 |
| jp/5.webp | 1; 0.794 / 0.794 / 0.794 / 0.794 | 1; 0.746 / 0.746 / 0.746 / 0.746 | 20; 0.278 / 0.563 / 0.931 / 0.941 | 167.2 | 206.4 |
| jp/6.webp | 6; 0.965 / 0.968 / 0.971 / 0.972 | 6; 0.925 / 0.944 / 0.950 / 0.953 | 3; 0.355 / 0.364 / 0.372 / 0.374 | 175.4 | 210.1 |
| jp/7.webp | 14; 0.277 / 0.951 / 0.966 / 0.968 | 12; 0.892 / 0.915 / 0.931 / 0.940 | 2; 0.251 / 0.420 / 0.556 / 0.590 | 168.0 | 213.2 |
| jp/8.webp | 7; 0.884 / 0.934 / 0.968 / 0.972 | 7; 0.935 / 0.952 / 0.956 / 0.956 | 0 | 170.4 | 219.0 |
| jp/9.webp | 10; 0.887 / 0.928 / 0.967 / 0.974 | 10; 0.907 / 0.933 / 0.944 / 0.945 | 0 | 176.3 | 219.7 |
| jp/10.webp | 10; 0.870 / 0.967 / 0.975 / 0.977 | 10; 0.905 / 0.947 / 0.957 / 0.959 | 0 | 172.6 | 219.6 |
| jp/13.webp | 7; 0.878 / 0.944 / 0.959 / 0.960 | 7; 0.885 / 0.933 / 0.945 / 0.951 | 9; 0.254 / 0.299 / 0.326 / 0.333 | 177.7 | 225.7 |
| jp/15.webp | 7; 0.584 / 0.880 / 0.888 / 0.889 | 7; 0.448 / 0.918 / 0.940 / 0.942 | 0 | 167.4 | 213.7 |
| jp/22.webp | 7; 0.961 / 0.972 / 0.974 / 0.974 | 7; 0.909 / 0.936 / 0.953 / 0.956 | 0 | 178.3 | 227.4 |
| jp/23.webp | 8; 0.264 / 0.965 / 0.972 / 0.973 | 8; 0.259 / 0.947 / 0.960 / 0.963 | 0 | 189.9 | 237.6 |
| jp/24.webp | 8; 0.916 / 0.969 / 0.973 / 0.974 | 9; 0.256 / 0.926 / 0.950 / 0.955 | 0 | 188.3 | 238.3 |
| jp/26.webp | 10; 0.904 / 0.940 / 0.966 / 0.976 | 10; 0.719 / 0.931 / 0.951 / 0.960 | 0 | 179.7 | 223.2 |

## Android dependency cost and 16 KB checks

Measurements use the existing universal debug configuration (no ABI filters), no model assets, no runtime API calls, no minSdk/applicationId changes. A fresh `:app:clean :app:assembleDebug --console=plain` was run for the baseline and each measured dependency variant. Gradle 8.13, AGP 8.11.2, JBR 21.0.11 (`C:/Users/spgs/.jdks/jbr-21.0.11`), Android SDK `C:/Users/spgs/AppData/Local/Android/Sdk`. The first attempt with Android Studio's JBR 25 failed with Gradle's `25.0.4` error; using the already-installed JBR 21 allowed the baseline and ONNX builds.

[Maven Central metadata](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml) read during the spike reported both `<latest>` and `<release>` **1.32.0**, `lastUpdated=20261113101826`. The tested coordinates were `com.microsoft.onnxruntime:onnxruntime-android:1.32.0` and, separately, `com.google.ai.edge.litert:litert:2.3.2`.

**LiteRT is not a drop-in dependency on this checkout.** Unmodified Kotlin 2.0.21 / KSP 2.0.21-1.0.25 fails `:app:kspDebugKotlin`: `litert-api-2.3.2-api.jar` metadata binary version **2.4.0**, expected **2.0.0**. To measure library size only, the throwaway build temporarily added `kotlinOptions.freeCompilerArgs += "-Xskip-metadata-version-check"`; this is not a production fix or proof of API compatibility. The flag was removed with the dependency and never committed.

| Clean universal debug APK | Bytes | MiB | Delta bytes vs baseline | Delta MiB |
| --- | ---: | ---: | ---: | ---: |
| No new runtime | 87,452,689 | 83.40 | 0 | 0 |
| ONNX Runtime 1.32.0 | 238,419,637 | 227.37 | 150,966,948 | 143.97 |
| LiteRT 2.3.2, size-only metadata bypass | 106,132,881 | 101.22 | 18,680,192 | 17.81 |

The first incremental LiteRT APK was 190,107,920 bytes, retaining removed-entry packing residue after the ONNX build; **discarded**, not used as a dependency cost. Clean packaging reduced it to 106,132,881 bytes. The incremental ONNX APK was also replaced by the clean result above. These are raw universal APK bytes, not Play compressed download size, installed size, release/R8 size, or split APK size.

### Added native libraries, extracted from the clean APKs

All added libraries are stored uncompressed in the ZIP (`compress_type=0`, compressed size equals file size). Values below are actual ELF file bytes, not AAR sizes.

| Runtime | ABI | Library | Bytes | LOAD segments | All LOAD Align |
| --- | --- | --- | ---: | ---: | --- |
| ONNX Runtime | arm64-v8a | `libonnxruntime.so` | 36,307,784 | 3 | 0x4000 |
| ONNX Runtime | arm64-v8a | `libonnxruntime4j_jni.so` | 122,192 | 2 | 0x4000 |
| ONNX Runtime | armeabi-v7a | `libonnxruntime.so` | 25,791,836 | 3 | 0x4000 |
| ONNX Runtime | armeabi-v7a | `libonnxruntime4j_jni.so` | 89,588 | 2 | 0x4000 |
| ONNX Runtime | x86 | `libonnxruntime.so` | 43,328,156 | 3 | 0x4000 |
| ONNX Runtime | x86 | `libonnxruntime4j_jni.so` | 102,684 | 2 | 0x4000 |
| ONNX Runtime | x86_64 | `libonnxruntime.so` | 44,970,184 | 3 | 0x4000 |
| ONNX Runtime | x86_64 | `libonnxruntime4j_jni.so` | 109,432 | 2 | 0x4000 |
| LiteRT | arm64-v8a | `libLiteRt.so` | 5,629,696 | 3 | 0x4000 |
| LiteRT | arm64-v8a | `liblitert_jni.so` | 529,280 | 3 | 0x4000 |
| LiteRT | armeabi-v7a | `libLiteRt.so` | 3,653,808 | 3 | 0x4000 |
| LiteRT | armeabi-v7a | `liblitert_jni.so` | 323,316 | 3 | 0x4000 |
| LiteRT | x86_64 | `libLiteRt.so` | 7,784,496 | 3 | 0x4000 |
| LiteRT | x86_64 | `liblitert_jni.so` | 511,280 | 3 | 0x4000 |

| Runtime | ABI | Added native bytes (sum of its two libraries) |
| --- | --- | ---: |
| ONNX Runtime | arm64-v8a | 36,429,976 |
| ONNX Runtime | armeabi-v7a | 25,881,424 |
| ONNX Runtime | x86 | 43,430,840 |
| ONNX Runtime | x86_64 | 45,079,616 |
| LiteRT | arm64-v8a | 6,158,976 |
| LiteRT | armeabi-v7a | 3,977,124 |
| LiteRT | x86 | 0 (ABI not supplied) |
| LiteRT | x86_64 | 8,295,776 |

Those per-ABI numbers are native contributions, **not independently built per-ABI APK deltas**. Universal deltas also include bytecode, resources, manifest and packaging differences. LiteRT has no x86 native pair, so this does not establish detector support for 32-bit x86 devices.

### ELF and ZIP alignment evidence

No NDK was installed in this SDK. Extracted `llvm-readelf.exe` from Google's [NDK r28c Windows archive](https://dl.google.com/android/repository/android-ndk-r28c-windows.zip), member `android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe`, to the external spike directory. Its version reports Android LLVM **19.0.1**, revision `97a699bf4812a18fb657c2779f5296a4ab2694d2`.

- `llvm-readelf -lW` exited 0 for every added library: **8 ONNX Runtime + 6 LiteRT libraries**. Every LOAD segment in every listed ABI has `Align 0x4000`, including all arm64-v8a and x86_64 LOADs.
- SDK Build Tools 36.0.0 `zipalign -c -P 16 4` exited **0** for both clean runtime APKs (success produces no stdout in this invocation).
- This establishes alignment of the **new** ELF files and ZIP packaging. It does not prove the entire application can load on 16 KB pages: existing SQLCipher alignment is a separate pre-existing issue documented in `docs/manga-reader.md`, not re-audited here.

Exact size/alignment commands executed (PowerShell form shown for the directly invoked native tools; `inspect_apks.py` performed ZIP extraction, byte-size comparison and one readelf invocation per added `.so`):

```powershell
# From the external spike directory; scripts are retained there, not in Git.
python C:/Users/spgs/Desktop/manga-tests-out/spike/inspect_apks.py onnxruntime-clean litert-clean
Get-Item 'C:/Users/spgs/Desktop/manga-tests-out/spike/baseline.apk','C:/Users/spgs/Desktop/manga-tests-out/spike/onnxruntime-clean.apk','C:/Users/spgs/Desktop/manga-tests-out/spike/litert-clean.apk' | Select-Object Name,Length | ConvertTo-Json

# Exact argument pattern used for each extracted added library:
& 'C:/Users/spgs/Desktop/manga-tests-out/spike/llvm-readelf.exe' -lW 'C:/Users/spgs/Desktop/manga-tests-out/spike/unpacked/onnxruntime-clean/lib/arm64-v8a/libonnxruntime.so'
& 'C:/Users/spgs/Desktop/manga-tests-out/spike/llvm-readelf.exe' -lW 'C:/Users/spgs/Desktop/manga-tests-out/spike/unpacked/litert-clean/lib/x86_64/libLiteRt.so'
& 'C:/Users/spgs/AppData/Local/Android/Sdk/build-tools/36.0.0/zipalign.exe' -c -P 16 4 'C:/Users/spgs/Desktop/manga-tests-out/spike/onnxruntime-clean.apk'
& 'C:/Users/spgs/AppData/Local/Android/Sdk/build-tools/36.0.0/zipalign.exe' -c -P 16 4 'C:/Users/spgs/Desktop/manga-tests-out/spike/litert-clean.apk'
```

The inspector's exact readelf command is `[spike/llvm-readelf.exe, '-lW', spike/unpacked/<variant>/<ZIP member>]`, iterated over every row above, not only the two examples. Full segment listings: `unpacked/<variant>/lib/<ABI>/<library>.readelf.txt`; JSON with all segment lines and byte sizes: `runtime-results.json`; build logs: `baseline-build.log`, `onnxruntime-clean-build.log`, `litert-clean-build.log`; failed normal LiteRT build: `litert-build.log`.

Build commands executed through `build_apk.py` with the JBR/SDK environment above:

```text
gradlew.bat :app:clean :app:assembleDebug --console=plain
```

The script copied `app/build/outputs/apk/debug/app-debug.apk` outside the repo after each successful build. The dependency changes and LiteRT compiler bypass existed only uncommitted on `spike-detector`; the branch was never merged, both changes were removed, the worktree returned to `spgsroot/detector-spike`, and `spike-detector` was deleted before committing this report.

## LiteRT export and emulator result

Actual export attempted in the external venv:

```python
YOLO('C:/Users/spgs/Desktop/manga-tests-out/spike/Kiuyha/weights/yolo26n.pt').export(
    format='tflite', imgsz=1280, device='cpu')
```

Ultralytics 8.4.213 redirects deprecated `tflite` to `litert`, then fails with **`AssertionError: LiteRT export only supported on Linux x86 and macOS`** at `engine/exporter.py:1370`. Log: `litert-export.log`. No `.tflite` was produced, no cloud export was used, and no page was uploaded. The LiteRT APK figures therefore measure the **library alone**, not a working converted detector. This export attempt showed the current export default preparing a `(1,5,33600)` raw head; any future conversion must explicitly choose and inspect its output contract rather than copy the downloaded ONNX decoder blindly.

Optional emulator load/inference was **not done**. This command exited 1:

```powershell
& 'C:/Users/spgs/AppData/Local/Android/Sdk/platform-tools/adb.exe' -s emulator-5554 shell getconf PAGE_SIZE
# adb.exe: device 'emulator-5554' not found
```

No running emulator at that serial was available to establish page size or run a session. No APK was installed, no applicationId or suffix was changed, and the user's existing `ru.spgsroot.peachy` was neither overwritten nor uninstalled. The physical phone was not used.

## Rejected or deferred options

- **YOLO26s over nano:** 20.25 MB versus 6.07 MB ONNX, 470 versus 441 retained `text` regions, no new class semantics; some pages have fewer detections. Extra counts are not a measured coverage benefit and do not justify choosing it now.
- **Stock RT-DETR as a bundled asset:** 168.48 MB ONNX exceeds the 15 MB rule and shares the heavy stock ONNX runtime cost. It remains the evaluated semantic reference and would require first-use download if chosen; the repository's int8 alternatives were seen in its file listing but not evaluated.
- **LiteRT 2.3.2 as a ready runtime:** smaller measured native/packaging cost, but Windows conversion failed and the dependency's Kotlin 2.4 metadata is incompatible with this checkout. The size-only compiler bypass is not an adoption path; API/toolchain compatibility and an actual converted-model inference remain unproven.
- **Stock universal ONNX Runtime as an automatic shipping choice:** 143.97 MiB extra APK weight before weights is material. Direct ONNX loading is the provisional technical route, not approval to pay that cost without phone measurements and an owner delivery decision.
- **Single-class nano as a complete bubble/free-text classifier:** rejected. It cannot satisfy the design's three region kinds by renaming its class. Phase 2 needs either an approved reduced-semantic design or a suitable multi-class detector; that decision is not silently made here.
- Other spec candidates were not downloaded or run: `ogkalu/comic-speech-bubble-detector-yolov8m` (bubble-only, heavier YOLO lineage), `huyvux3005/manga109-segmentation-bubble` (spec records conflicting non-commercial metadata/card terms), `dmMaze/comic-text-detector` (different GPL lineage and outside this comparison). These are prior spec concerns, not newly measured quality claims.

## Explicitly not measured

- **Phone timings, p50/p95, peak heap, native memory and battery:** reserved for the baseline/device worker; no physical-device access in this spike.
- **Coverage versus ML Kit blocks** (60% overlap proxy), text coverage versus owner-marked bubbles, precision/recall, plate-fit/overlap improvements: no baseline `report.json` or ground-truth annotations consumed. These wait for the other worker's baseline; region counts cannot replace them.
- Owner approval of overlays; review here is the worker's limited visual sample, with all overlays left for the owner locally.
- 16 KB emulator library loading or Android ONNX session/inference; successful build/alignment is not a substitute.
- LiteRT inference, conversion parity, quantization, GPU/NNAPI/delegates, custom reduced ONNX builds, ABI-split/release/Play sizes, install size or model-download UX.
- Tall-strip tiling/seam correctness, Korean pages, alternate confidence thresholds, repeated controlled PC timings or alternate OCR `WORK` sizes.
- Additional dataset permissions or any legal clearance beyond recording the model cards and metadata.

Only this report is committed. No copyrighted pages, overlay pixels, model weights, temporary code, runtime dependencies, compiler flags or applicationId edits enter the commit.
