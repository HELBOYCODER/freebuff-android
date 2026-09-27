# Android Runtime & Build Environment

## Executable now (this dev box)
- **Pure-Kotlin JVM core** (`:core:model`, `:core:protocol`, `:core:security`, `:core:workspace`)
  compiles and its **20 unit tests pass** with only JDK 17 + Gradle 8.7 + Maven Central.
- **Protocol reference** (`protocol-ref/*.mjs`) runs under Node with **7 passing tests**.
- Total verified-green tests: **27**.

## Capability gate — Android UI / terminal / Git modules
The Android SDK binaries (`platforms`, `build-tools`, `platform-tools`, `cmdline-tools`) and the
Android Gradle Plugin + androidx/Compose are served from **Google endpoints
(`dl.google.com/android/repository/`, `maven.google.com`) that are egress-filtered on this
machine** — every fetch returns a proxy 404, including files known to exist publicly (e.g.
`platform-tools-latest-darwin.zip`). Maven Central and `plugins.gradle.org` are reachable, which is
why the JVM core builds.

Consequences (recorded honestly per Master Spec §14/§15/§19 — no fake build claims):
- `:app:assembleDebug` / `assembleRelease` **cannot be run here**; they are gated behind
  `FB_ANDROID=1` in `settings.gradle.kts` and the `android` CI job.
- Android **instrumentation / emulator** tests require the SDK + a device — CI-gated.
- Native **PTY/terminal** (§11) needs NDK/SDK + runtime — CI/device-gated; will use the
  RuntimeCapability layer, never a fake success.

## To enable the full build
On a machine (or CI runner) with unblocked Google access:
```sh
export FB_ANDROID=1
export ANDROID_HOME=/path/to/android-sdk
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
./gradlew :app:assembleDebug
```
JDK/Gradle provisioning used here (no brew, user-dir): Adoptium Temurin 17 aarch64 + Gradle 8.7
distribution zip, both from reachable hosts.
