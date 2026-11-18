# Office engine packaging (debug, opt-in)

Reproducible path from one staged LibreOffice engine (`x86_64` or `arm64-v8a`) to a Peachy **debug**
APK. Nothing here runs in a normal build, and no native binary is added to Git:
the stage stays on the local machine and the prepared payload lives under the
ignored `app/build/office-engine/debug/` directory.

Source pin: `libreoffice-26.8.3.1` / `861effc5289d539f15c4c18d1ba8579ffd78728b`.
App id: `ru.spgsroot.peachy`. Tile mode: `rgba`.
`have_feature_android_lok=false` (macros never execute).
The stage manifest selects the ABI. Each debug APK contains the Office engine for only that ABI.

## Inputs

| Gradle property | Value |
|---|---|
| `peachy.officeEngine.stage` | per-ABI stage directory (`lib/`, `assets/`, `licences/`, `MANIFEST.txt`) |
| `peachy.officeEngine.javaGlue` | directory with the four pinned `org/libreoffice/kit/*.java` files |
| `peachy.officeEngine.python` | optional interpreter; default `python` on Windows, `python3` elsewhere |

Both required properties must be supplied together. With neither, the Gradle
wiring registers no task and touches no Office file; release variants are never
wired.

Example on Windows (WSL2 stage exposed as a UNC path; substitute your own paths):

```powershell
.\gradlew.bat :app:assembleDebug `
  -Ppeachy.officeEngine.stage='\\wsl.localhost\Debian\home\<user>\office-engine\out\x86_64' `
  -Ppeachy.officeEngine.javaGlue='\\wsl.localhost\Debian\home\<user>\office-engine\src\core\android\Bootstrap\src\org\libreoffice\kit'
```

Example with variables:

```sh
./gradlew :app:assembleDebug \
  -Ppeachy.officeEngine.stage="$OFFICE_STAGE" \
  -Ppeachy.officeEngine.javaGlue="$OFFICE_GLUE"
```

## What `office_engine_package.py prepare` refuses

- a wrong `applicationId`, ABI, tag, commit, `tile_mode`, or macro flag in either
  `MANIFEST.txt` or `assets/office-engine/build-manifest.properties`;
- an `app minSdk` below the engine's `min_sdk_app`;
- an ELF library that is not ELF64 little-endian `ET_DYN` for the declared ABI
  (`e_machine` 62 for `x86_64`, 183 for `arm64-v8a`), any `PT_LOAD` with alignment
  below `0x4000` or incongruent address and offset, or any `NEEDED` entry that is
  neither a staged engine library nor a known Android system library;
- missing, extra, empty-required, or locally leaking assets, a `fonts.conf`
  cachedir for a different applicationId, or a missing/empty `LICENSE`/`NOTICE`;
- a Java glue directory that is not exactly the four pinned files, whose SHA-256
  differs from the constants bound to the pin, or which lacks its MPL-2.0 header;
- any stage file whose bytes disagree with the `## sha256` list in
  `MANIFEST.txt`, any file absent from that list (except the app declaration,
  which `20-stage.sh` writes after hashing), any unverified file at the stage
  root, symlinks, or names that collide case-insensitively;
- a `MANIFEST.txt` `elf_load_align` that disagrees with the actual segments.

`prepare` copies only after validation, re-hashes every copied file, writes
`provenance.json` (pins, hashes, ELF facts, MPL-2.0 glue list) outside the
packaged asset tree, and publishes atomically into its own preparation root,
`app/build/office-engine/debug/payload/`. The stage is never written to, and the
preparation root is never inside an AGP source category.

Three separate Gradle tasks then sync one category each into the directory AGP
assigns to its generated-source property:

| Task | Copies | AGP source type |
|---|---|---|
| `officeEngineDebugAssets` | `payload/assets/` | assets |
| `officeEngineDebugJniLibs` | `payload/jniLibs/<abi>/` | jniLibs |
| `officeEngineDebugJava` | `payload/java/` | java |

`verifyOfficeEngineDebugNativeLibs` runs before packaging and compares AGP's
merged native libraries against the `officeEngineDebugJniLibs` output, so it
proves, byte-for-byte, that all 14 engine libraries are in the selected ABI directory and that
none of them appears in another ABI. It accepts both AGP layouts
(`<dir>/<abi>` and `<dir>/lib/<abi>`). No `pickFirst` rule is added.

Run the isolated tests with synthetic fixtures:

```sh
python -m unittest discover -s tools/office-packaging -p "test_*.py"
```

## Collision findings (current dependency set)

Checked against `app/build/intermediates/merged_native_libs/{debug,release}`
and every resolved AAR: no other producer supplies `liblo-native-code.so`, the
NSS set, `libsqlite3.so`, `libssl3.so`, or `libc++_shared.so`. Existing native
dependencies are `libandroidx.graphics.path`, `libdatastore_shared_counter`,
`libmlkit_google_ocr_pipeline`, `libmupdf_java`, `libsqlcipher` (and
`libonnxruntime*` for arm64). If a future dependency introduces a colliding
name, the merge or `verify-merged` fails; resolve it by removing the competing
producer, not with `pickFirst`.

## APK and runtime checks

After `assembleDebug`, inspect the assets and ABI-specific libraries in the APK,
check that `MANIFEST.txt`, `provenance.json` and `licences/` are not packaged,
and run `zipalign -c -P 16 -v 4`. `llvm-readelf -h -l` confirms the machine
and load alignment of the packaged libraries. Packaging checks do not establish
runtime compatibility or Office readiness.

- Runtime: asset unpack, `libreofficekit_hook`, tile colours (RGBA), macro lock,
  and the network/extref filters on a device/emulator.
- MPL-2.0 source-offer/NOTICE handling for shipping the Java glue and MPL/LGPL
  engine libraries.
- Debug APK size and install/memory behaviour.
