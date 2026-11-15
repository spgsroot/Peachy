# Compose and build-config performance

Date: 2026-11-15. Device: Xiaomi 22071212AG, HyperOS 2.0 / Android 15, Dimensity 8100, 8 GB, arm64-v8a, 1220x2712 @480dpi, serial `EM4HJR5PJNCYB6PR`. One session, five suffixed copies, interleaved A, B, C, D, E. Numbers from any other phone are not comparable.

## Winner

Ship C: release with R8 and resource shrinking, plus the SQLCipher keep rule. Do not ship the baseline profile or the Compose BOM bump.

Against the current release (B), C is the configuration that improves the metrics R8 is supposed to improve, on the same phone, in the same session:

- Cold-only `am start -W` TotalTime median 241 ms vs 279 ms (max 271 vs 316).
- Library-scroll janky frames 0.3% vs 3.3%, p99 8 ms vs 19 ms, slow UI thread 3 vs 29.
- PSS after the reader step 85.5 MB vs 113.5 MB.
- APK 60.87 MiB vs 74.95 MiB.

D (C plus a hand-written baseline profile) did not beat C on cold start, which is the metric a profile is for: cold-only median 255 vs 241, full-scenario median 259 vs 245. Library scroll matched C. Overlay p99 was less noisy, not enough to take a cold-start regression.

E (D plus Compose BOM 2025.07.00) built without touching Kotlin 2.0.21 or AGP 8.11.2. It did not win. Cold-only median 257 vs C's 241, reader jank worse, library jank 0.2% vs 0.3% (one frame in about 500). BOM 2025.08.00 was not tried: it needs AGP 9.1. `gradle/libs.versions.toml` stays at `2024.09.00`.

Debug (A) is what the owner runs while developing. It is not a release candidate: cold-only median 481 ms, library jank 9.5%, PSS 142.5 MB, APK 93.76 MiB.

## Configurations

| | What was installed |
|---|---|
| A | Current `debug`, `applicationIdSuffix` `.perfa` |
| B | `release`, R8 off, debug-signed, `.perfb` |
| C | `release`, `isMinifyEnabled` and `isShrinkResources`, debug-signed, `.perfc` |
| D | C plus `app/src/main/baselineProfiles/scenario.txt`, compiled `speed-profile`, `.perfd` |
| E | D plus Compose BOM `2025.07.00`, `.perfe` |

Suffixes, the `FLAG_SECURE` removal, and the seed activity were local and are not in the commit. A, B, and C were `cmd package compile -m verify -f` before the series so a leftover profile would not favor them. D and E were `speed-profile` so the packaged baseline could apply. Each copy was seeded with the same 16 local pages and 37 library rows before the series. No page was sent to a translation provider.

The baseline profile is hand-written, not from the `androidx.baselineprofile` plugin. The plugin's generator installs and runs a macrobenchmark module unattended; HyperOS blocks that install with a USB prompt the plugin cannot answer. The file is an ART `cmd package dump-profiles` sample of the scenario on a non-minified release build, filtered to `H`/`S`/`P` methods. It was not committed because D did not win.

## Scenario

Same script for every copy, five full passes then five extra cold starts, order A B C D E inside each pass.

1. Cold start: `am force-stop`, then `am start -W` of `MainActivity`. TotalTime and WaitTime. The app has no `reportFullyDrawn`; none was added.
2. Library: open the manga Downloads list, five swipes up and five down, `dumpsys gfxinfo <pkg> reset` before and the same dump after.
3. Reader: open the seeded gallery, tap forward 12 pages, zoom with the on-screen buttons, pan. Janky %, p99, slow UI thread.
4. Overlay: open the translation panel from the already-seeded cache. No provider call, no owner key.
5. `dumpsys meminfo` PSS after step 3. APK size from the file that was installed.

Battery temperature was read before every run (`dumpsys battery`). It stayed 28.6–29.2°C, under the 40°C cool-down line, so no run waited. Battery level was 98% before the series and was not re-read per run.

With n=5, nearest-rank p95 is the maximum. Tables are median / max.

## Results

Cold start, TotalTime ms. "Full" is the start at the beginning of each scripted pass. "Cold" is the extra force-stop series.

| | Full TotalTime | Full WaitTime | Cold TotalTime |
|---|---:|---:|---:|
| A debug | 433 / 463 | 436 / 466 | 481 / 518 |
| B release, R8 off | 249 / 287 | 252 / 327 | 279 / 316 |
| C release, R8 on | 245 / 272 | 253 / 284 | 241 / 271 |
| D C + profile | 259 / 323 | 264 / 325 | 255 / 268 |
| E D + BOM 2025.07.00 | 225 / 283 | 232 / 299 | 257 / 292 |

Library scroll, five up and five down.

| | Frames | Janky % | p50 ms | p90 ms | p95 ms | p99 ms | Slow UI |
|---|---:|---:|---:|---:|---:|---:|---:|
| A | 755 / 767 | 9.5 / 9.8 | 7 / 7 | 19 / 20 | 28 / 29 | 34 / 36 | 71 / 73 |
| B | 881 / 887 | 3.3 / 3.4 | 6 / 6 | 10 / 11 | 14 / 15 | 19 / 20 | 29 / 30 |
| C | 898 / 905 | 0.3 / 0.4 | 5 / 5 | 6 / 7 | 7 / 9 | 8 / 15 | 3 / 3 |
| D | 899 / 904 | 0.3 / 0.4 | 5 / 5 | 6 / 7 | 7 / 11 | 8 / 15 | 3 / 3 |
| E | 899 / 905 | 0.2 / 0.3 | 5 / 5 | 6 / 6 | 7 / 7 | 8 / 9 | 1 / 3 |

Reader: open, 12 page taps, zoom, pan. Frame counts swing from about 180 to about 500 because the timed region includes the open, so jank here is noisier than the library scroll.

| | Janky % | p99 ms | Slow UI |
|---|---:|---:|---:|
| A | 5.0 / 5.1 | 24 / 27 | 8 / 25 |
| B | 1.7 / 3.3 | 22 / 28 | 3 / 11 |
| C | 2.4 / 2.7 | 17 / 24 | 8 / 10 |
| D | 2.1 / 2.6 | 17 / 28 | 5 / 8 |
| E | 2.7 / 3.2 | 23 / 28 | 5 / 13 |

Translation overlay, opened from cache.

| | Janky % | p99 ms | Slow UI |
|---|---:|---:|---:|
| A | 5.5 / 7.5 | 34 / 125 | 15 / 21 |
| B | 3.2 / 3.9 | 48 / 81 | 7 / 8 |
| C | 2.9 / 5.6 | 48 / 150 | 7 / 10 |
| D | 2.9 / 3.4 | 42 / 69 | 7 / 8 |
| E | 3.5 / 4.0 | 42 / 77 | 8 / 12 |

Memory and size. PSS is after the reader step.

| | PSS KB | APK |
|---|---:|---:|
| A | 142536 / 144113 | 93.76 MiB |
| B | 113506 / 119662 | 74.95 MiB |
| C | 85532 / 89939 | 60.87 MiB |
| D | 85329 / 89674 | 60.87 MiB |
| E | 85285 / 86852 | 60.89 MiB |

Zero failed runs in the official series (25 full + 25 cold).

## Commands

Gradle, from the worktree:

```
cmd.exe /c "set JAVA_HOME=C:\Users\spgs\.jdks\jbr-21.0.11&&set ANDROID_HOME=C:\Users\spgs\AppData\Local\Android\Sdk&&gradlew.bat :app:assembleDebug --console=plain"
cmd.exe /c "set JAVA_HOME=C:\Users\spgs\.jdks\jbr-21.0.11&&set ANDROID_HOME=C:\Users\spgs\AppData\Local\Android\Sdk&&gradlew.bat :app:assembleRelease -PperfSuffix=.perfb --console=plain"
cmd.exe /c "set JAVA_HOME=C:\Users\spgs\.jdks\jbr-21.0.11&&set ANDROID_HOME=C:\Users\spgs\AppData\Local\Android\Sdk&&gradlew.bat :app:assembleRelease -PperfSuffix=.perfc -PperfMinify=true --console=plain"
```

D and E used the same release task with their own suffix. Those `-P` flags were local and are not in the committed build file. Install:

```
"C:/Program Files/Git/bin/bash.exe" C:/Users/spgs/Desktop/manga-tests-out/install_confirm.sh <apk> <suffixed package>
```

Phone, always `-s EM4HJR5PJNCYB6PR`:

```
adb shell am force-stop <pkg>
adb shell am start -W -n <pkg>/ru.spgsroot.peachy.MainActivity
adb shell dumpsys gfxinfo <pkg> reset
adb shell dumpsys gfxinfo <pkg>
adb shell dumpsys meminfo <pkg>
adb shell dumpsys battery
adb shell cmd package compile -m verify -f <pkg>
adb shell cmd package compile -m speed-profile -f <pkg>
```

Swipes and taps came from a fresh `uiautomator dump` on this 1220x2712 screen, not from coordinates copied off another phone. The script lives outside the repo at `Desktop/manga-tests-out/compose-perf/measure.py`. Raw dumps are in `Desktop/manga-tests-out/compose-perf/runs/`. No page image, screenshot, or weight is committed.

Compose compiler reports, debug compile only:

```
gradlew.bat :app:compileDebugKotlin -P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=... -P plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=...
```

## Compose compiler

Strong skipping is on. It is the default from Kotlin 2.0.20, and this project is Kotlin 2.0.21. The debug report has 898 composables, 889 restartable, 676 skippable, 0 classes marked `@Stable`.

Every composable in the reader, library, gallery, and player screens is skippable. Unstable parameters remain, and strong skipping is why they are still skippable: `NavController`, `MangaViewModel`, `GalleryViewModel`, `PlayerViewModel`, `List`, and `GalleryDetail`. The lazy lists on those screens already pass `key`. No `contentType` on the single-type library and reader lists. Nothing in that list was changed: the measured jank drop is R8 versus debug/release, and no finding was A/B tested on its own. A fix without a measured effect stays in this report.

## R8 keep rules

One rule, in `app/proguard-rules.pro`:

```
-keep class net.sqlcipher.** { *; }
```

It protects `SQLiteDatabase.mNativeHandle` and the other members `libsqlcipher.so` looks up by name from `JNI_OnLoad`. Without it, the minified C build crashed in `SQLiteDatabase` with `NoSuchFieldError: mNativeHandle` before the library opened. The rule is one package, not one class, and it is not our code. No rule is broader than one package, so no class of ours is kept by a broad rule. The cost is that unused SQLCipher members survive shrinking. Narrowing to the JNI fields was not done after the package rule stopped the crash.

Phone exercise on the minified C copy, after the keep rule: Hilt injection, Room (37 galleries, 16 pages), kotlinx.serialization, Coil image load, ML Kit text recognition (22 blocks on one local page), and Buttplug client construct (no device, so disconnected rather than a class-load failure). ONNX Runtime is not a dependency of this app; the seed reported it absent. No `ClassNotFoundException` on that pass.

## Signing and CI

`app/build.gradle.kts` has no `signingConfig`. `.github/workflows/release.yml` still runs `bundleRelease assembleRelease` and signs afterwards with `r0adkll/sign-android-release`. R8 runs before that signing step. The workflow file was not edited.

## Not measured

Other phones, other thermals, and a battery below 98%. This session never left 28.6–29.2°C, so a hot device is unmeasured. `reportFullyDrawn` is unmeasured because the app does not call it. Startup method tracing beyond `am start -W` is unmeasured. The reader and overlay gfxinfo windows include navigation, so they are not a pure fling. Translation was cache-only. Play delivery size and a clean install of the real `ru.spgsroot.peachy` package were not measured; the table is the suffixed copies.

## Left for the owner

- Install the new release once on a clean profile. R8 is now on for the real package, and the only new keep is SQLCipher.
- CI and Play artifacts will be smaller. The measured unsigned R8 APK is 60.87 MiB against 74.95 MiB with R8 off. Signing in `release.yml` is unchanged.
- The SQLCipher rule keeps the whole `net.sqlcipher` package. Narrow it only with a crash check of a clean install.
- A baseline profile did not help this scenario. A later attempt would need a non-minified benchmark build; the packaged profile is rewritten by R8, which is the likely reason D lost to C.
- Compose BOM 2025.07.00 compiles on the current Kotlin and AGP. It is not worth taking for these numbers. 2025.08.00 needs AGP 9.1.
