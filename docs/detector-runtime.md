# Vendored text detector runtime

Task 1 of phase 2, measured on 2026-11-15. The app bundles ONNX Runtime 1.32.0 basic-minimal CPU for `arm64-v8a` and the YOLO26n ORT-format model. The rebuilt AAR declares **minSdk 26**; the previous spike AAR declared 27 and is not used. No `tools:overrideLibrary`, app minSdk change, ABI filter or `noCompress` change is needed.

## Artifacts and verification

| Vendored file | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar` | 1,764,511 | `5ae11aa0f2f4043baf380c122bfae7969a55a1d7a6222e0ff730e7fddaf589ac` |
| `app/src/main/assets/detector/yolo26n.ort` | 10,513,152 | `dc6b6341319acc41f115a5c7239f888e9ef344eb492447600d21a46d691506c2` |

Native members extracted from this AAR, also verified byte-for-byte by SHA-256 in the assembled debug APK:

| Member under `jni/arm64-v8a/` | Bytes | SHA-256 | LOAD segments / alignment |
| --- | ---: | --- | --- |
| `libonnxruntime.so` | 3,200,248 | `bf527d331550988481d2008288f8cf5317f44abdc0e04144418b6427378b6e69` | 3 / all `0x4000` |
| `libonnxruntime4j_jni.so` | 120,616 | `dc7e2be8b6fb86277d13f1c56b184dea570e61ff44bc1d1df7448b5165ec4b7e` | 2 / all `0x4000` |

The native pair totals 3,320,864 bytes. Static libc++ is used; the AAR has no `libc++_shared.so`. `classes.jar` contains `ai.onnxruntime.OrtEnvironment`, `OrtSession` and `OnnxTensor`.

The universal debug APK built from this worktree changed from **87,521,316** to **99,159,924 bytes**, a measured increase of **11,638,608 bytes**. This includes the compressed model, runtime, Java/dex and packaging effects; it is not a release APK or Play download-size estimate. ZIP inspection found exactly:

```text
lib/arm64-v8a/libonnxruntime.so
lib/arm64-v8a/libonnxruntime4j_jni.so
assets/detector/yolo26n.ort
```

No ONNX Runtime library is present under any other ABI. The model extracted from the APK matches the vendored model's size and hash. The app's other native dependencies and ABIs remain unchanged.

`assembleDebug` and the existing `testDebugUnitTest` suite returned **BUILD SUCCESSFUL**: **566 tests, 0 failures, 0 errors, 0 skipped in 72 suites**. Gradle reports that it cannot strip the two ORT libraries and packages them unchanged; this warning was not suppressed. The AAR build also emits upstream CMake/AGP deprecation and compileSdk compatibility warnings.

This task verifies building and packaging, not Android inference. No phone or emulator was used. ELF LOAD alignment is not proof of execution on a 16 KB-page device or a complete APK zipalign check.

## Build pins

| Component | Pin |
| --- | --- |
| ONNX Runtime | tag `v1.32.0`, commit `fab30a46491a40f5e4c968b80e8a59acbbb91b53` |
| Build host | Debian WSL2, native Linux source/build directories |
| Source | `/home/spgs/ort-1.32.0` |
| New build directory | `/home/spgs/ort-min-work/android-api26` |
| Android SDK / NDK | `/home/spgs/Android`; NDK r28c `28.2.13676358`, Clang 19.0.1 |
| Native ABI / API | `arm64-v8a` / 26 |
| Java | Temurin OpenJDK `17.0.21+9`, `/home/spgs/jdk17` |
| ORT Java packaging | pinned source's Gradle 8.7, AGP 7.4.2, compileSdk 34 |
| Native build tools | `/usr/bin/cmake` 3.31.6, Ninja 1.13.2 from the venv |
| Build/conversion venv | `/home/spgs/ort-min-work/venv`, Python 3.13.5, onnxruntime 1.32.0, onnx 1.23.2, flatbuffers 25.12.19 |
| Operator configuration | `B.config`, SHA-256 `5e89f554a018197490f5b25def5d50b35f6e8340ef1463601761213b4cd95375` |

The retained prerequisite/download instructions are `C:/Users/spgs/Desktop/manga-tests-out/spike/ort-min/README.txt`. They install `build-essential cmake ninja-build python3-venv python3-pip openjdk-21-jdk-headless unzip zip wget ca-certificates`, Android command-line tools archive `commandlinetools-linux-13114758_latest.zip`, SDK platforms 34/35, build-tools 30.0.3/35.0.0 and NDK `28.2.13676358`. Despite the system JDK package, **the AAR build uses JDK 17**: the earlier JDK 21 spike attempt failed in `JdkImageTransform`/`jlink`. Existing prerequisites and pinned sources were reused; no source patch was needed for API 26.

### Operator/type configuration

`B.config` was generated from optimized nano+small ORT graphs with `--enable_type_reduction`; it has the same operators and types as nano-only `A.config`. It retains 29 domain/operator pairs, not the full stock runtime. Exact configuration:

```text
# Generated from model/s:
# - /home/spgs/ort-min-work/groups/B/yolo26n.ort
# - /home/spgs/ort-min-work/groups/B/yolo26s.ort
ai.onnx;11;Range{"inputs": {"0": ["int64_t"]}},TopK{"inputs": {"0": ["float"]}}
ai.onnx;13;Concat{"inputs": {"0": ["MLFloat16", "float", "int64_t"]}},Exp{"inputs": {"0": ["float"]}},Expand{"inputs": {"0": ["MLFloat16"]}},Gather{"inputs": {"0": ["int64_t"], "1": ["int64_t"]}},GatherElements{"inputs": {"0": ["MLFloat16"], "1": ["int64_t"]}},Mod,ReduceSum{"inputs": {"0": ["float"]}},Sigmoid{"inputs": {"0": ["float"]}},Tile
ai.onnx;14;Add{"inputs": {"0": ["float", "int64_t"]}},Div{"inputs": {"0": ["float"]}},Mul{"inputs": {"0": ["float", "int64_t"]}},Sub{"inputs": {"0": ["float"]}}
ai.onnx;18;Split{"inputs": {"0": ["MLFloat16"]}}
ai.onnx;19;Resize{"inputs": {"0": ["float"]}}
ai.onnx;20;ReduceMax{"inputs": {"0": ["float"]}}
ai.onnx;21;Cast{"inputs": {"0": ["MLFloat16", "float", "int64_t"]}, "outputs": {"0": ["MLFloat16", "float"]}},ConstantOfShape{"outputs": {"0": ["MLFloat16"]}},Flatten,Reshape,Shape,Transpose{"inputs": {"0": ["MLFloat16", "float"]}},Unsqueeze
ai.onnx;22;Conv{"inputs": {"0": ["float"]}},MaxPool{"inputs": {"0": ["float"]}}
com.microsoft;1;FusedMatMul,QuickGelu
```

### Exact API 26 build command

Run inside Debian WSL (`wsl.exe -d Debian --cd /home/spgs/ort-1.32.0 -- bash` from Windows). On a fresh source checkout, clone the pinned tag recursively first; clear inherited Windows Git worktree variables before using Linux Git.

```sh
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
# Fresh checkout only:
# git clone --depth 1 --branch v1.32.0 --recurse-submodules --shallow-submodules \
#   https://github.com/microsoft/onnxruntime.git /home/spgs/ort-1.32.0
export JAVA_HOME=/home/spgs/jdk17
export PATH="$JAVA_HOME/bin:/home/spgs/ort-min-work/venv/bin:$PATH"
export ANDROID_HOME=/home/spgs/Android ANDROID_SDK_ROOT=/home/spgs/Android
export OUT=/mnt/c/Users/spgs/Desktop/manga-tests-out/spike/ort-min

/home/spgs/ort-min-work/venv/bin/python tools/ci_build/build.py \
  --config Release --build_dir /home/spgs/ort-min-work/android-api26 \
  --update --build --skip_tests --parallel 6 --cmake_generator Ninja \
  --cmake_path /usr/bin/cmake --build_shared_lib --minimal_build \
  --include_ops_by_config "$OUT/B.config" --enable_reduced_operator_type_support \
  --disable_ml_ops --disable_exceptions --build_java --android \
  --android_sdk_path /home/spgs/Android \
  --android_ndk_path /home/spgs/Android/ndk/28.2.13676358 \
  --android_abi arm64-v8a --android_api 26

export APP=/mnt/c/Users/spgs/orca/workspaces/VibePlayer/bubble-detector-runtime
mkdir -p "$APP/app/libs"
cp /home/spgs/ort-min-work/android-api26/Release/java/build/android/outputs/aar/onnxruntime-release.aar \
  "$APP/app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar"
```

`--android_api 26` sets CMake `ANDROID_MIN_SDK`, which the pinned Java packaging passes as Gradle `-DminSdkVer`; both native targeting and manifest therefore use 26. The build completed successfully. ORT's packaging target also builds its example/test APKs, but no connected-device target was run.

This is basic minimal, CPU provider only, ORT format only: original `.onnx` models are not supported. No XNNPACK, NNAPI or GPU provider is enabled. `--disable_exceptions` matches the measured spike build; exceptional native paths can abort rather than become catchable Java exceptions. Build/package success does not establish graceful recovery from every native failure.

### Model provenance and copy

The original nano ONNX comes from `Kiuyha/Manga-Bubble-YOLO`, revision `fb646500455e8a8a3a807fd27b855c8e4fc63766`, path `onnx/yolo26n.onnx`, SHA-256 `b45c2e12cf0c3c1d2abfbbb9123c9f96f040f2ac36a0842382ecd9d859c851c7`. The spike converted it with ORT 1.32.0, `Fixed` optimization, ARM target and type reduction, without quantization, shape freezing or manual fp16 conversion:

```sh
/home/spgs/ort-min-work/venv/bin/python -m onnxruntime.tools.convert_onnx_models_to_ort \
  /home/spgs/ort-min-work/inputs/yolo26n/yolo26n.onnx \
  --output_dir /home/spgs/ort-min-work/converted/yolo26n \
  --optimization_style Fixed --target_platform arm --enable_type_reduction
```

Task 1 copied the retained conversion rather than reconverting it. In Windows Git Bash:

```sh
mkdir -p app/src/main/assets/detector
cp C:/Users/spgs/Desktop/manga-tests-out/spike/ort-min/models/yolo26n.ort \
  app/src/main/assets/detector/yolo26n.ort
sha256sum app/src/main/assets/detector/yolo26n.ort
```

The phase-2 consumer uses a 960x960 RGB-114 letterbox, NCHW float32 /255, CPU intra-op 4 / inter-op 1 threads. Output is `[1,300,6]` with `x1,y1,x2,y2,score,class`, one text class, score >= 0.25, no external NMS. Bundling these files does not yet implement that consumer.

## Repeating package checks

Run from the app repository in Windows Git Bash:

```sh
unzip -p app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar AndroidManifest.xml
unzip -o app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar 'jni/*/*.so' \
  -d C:/Users/spgs/Desktop/manga-tests-out/spike/ort-min/api26-verification
C:/Users/spgs/Desktop/manga-tests-out/spike/llvm-readelf.exe -lW \
  C:/Users/spgs/Desktop/manga-tests-out/spike/ort-min/api26-verification/jni/arm64-v8a/libonnxruntime.so \
  C:/Users/spgs/Desktop/manga-tests-out/spike/ort-min/api26-verification/jni/arm64-v8a/libonnxruntime4j_jni.so
sha256sum app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar \
  app/src/main/assets/detector/yolo26n.ort
cmd.exe /c "set JAVA_HOME=C:\Users\spgs\.jdks\jbr-21.0.11&&set ANDROID_HOME=C:\Users\spgs\AppData\Local\Android\Sdk&&gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain"
unzip -l app/build/outputs/apk/debug/app-debug.apk
stat -c '%n %s bytes' app/build/outputs/apk/debug/app-debug.apk
```

The manifest must show `android:minSdkVersion="26"`; every LOAD line must show `Align 0x4000`. APK inspection must find the two ORT libraries only under `lib/arm64-v8a/` and the model at `assets/detector/yolo26n.ort`.

## ABI policy and licences

Arm64-only keeps a second inference stack small; the benchmark used arm64, and no 32-bit or x86 minimal build was measured. The app remains universal, with no `abiFilters`. The phase-2 detector contract requires `detect` to return `null` on other ABIs or unavailable runtime/model, preserving phase-1 OCR translation rather than disabling the app. That fallback is implemented by the later detector/pipeline tasks, not by this vendoring change.

The [pinned model card](https://huggingface.co/Kiuyha/Manga-Bubble-YOLO/blob/fb646500455e8a8a3a807fd27b855c8e4fc63766/README.md) has YAML `license: apache-2.0` and `base_model: Ultralytics/YOLO26`, with no full licence grant in the body. The detector-spike report records the ONNX metadata as **`AGPL-3.0 License (https://ultralytics.com/license)`**. These statements conflict; do not describe the weights as Apache-only or attribute AGPL to the YAML. The owner accepted **AGPL-3.0** for Ultralytics-lineage weights and the app on **2026-11-15**; the app's `LICENSE` contains AGPL-3.0. This records the accepted lineage, not a resolution of the upstream legal conflict.

The card names `hal-utokyo/Manga109-s`, `Manga109-s` (~3,000 images), `Mangadex-EN` (~2,000) and `Mangadex-VI` (~1,000), 5,595 images after filtering, and credits Magi for Mangadex auto-annotation. It states: **“Note: The dataset cannot be released due to copyright concerns.”** It supplies no separate dataset licence or commercial redistribution/training permission. The linked Manga109-s card returned HTTP 401 during the spike, so its additional terms were not verified. The model-card Apache label does not license the page data. No training or evaluation page is vendored.

Sources for the recorded model/data terms and runtime measurements: [detector spike](reports/2026-11-15-detector-spike.md), [phase-2 plan](plans/2026-11-15-bubble-detector-phase2.md), and the sibling `ort-minimal` / `runtime-bench` worktrees' `2026-11-15-ort-minimal-build.md` and `2026-11-15-runtime-bench-stage2.md` reports. Only the two specified binary artifacts are committed here.

ONNX Runtime itself is [MIT-licensed](https://github.com/microsoft/onnxruntime/blob/fab30a46491a40f5e4c968b80e8a59acbbb91b53/LICENSE). Its upstream copyright/permission notice is reproduced here:

```text
MIT License

Copyright (c) Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
