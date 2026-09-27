# Freebuff Android (native coding-agent IDE)

An Android port of the [Freebuff / Codebuff](https://github.com/CodebuffAI/freebuff) coding-agent
experience toward a native mobile IDE + terminal + agent. Android is intended as a first-class
client, not a web wrapper or stripped chat app.

> **Status: early vertical slice.** This repo currently ships a *building, installable debug APK*
> that wires a real Compose/Material3 UI to a tested pure-Kotlin core (protocol + security + edit
> engine). It is **not** yet the full product. See [Limitations](#limitations).

## Upstream compatibility
Audited against pinned upstream commit `25f1d61530c6d58db394b59db12e97c113e9322d` (2026-09-27).
The Android host must implement the 16 tools in upstream `clientToolCallSchema`. Full matrix and
spec-vs-upstream divergences (incl. `cloud_plan_ready`, `lookup_agent_info`, `report_project_profile`,
and upstream-disabled `spawn_agent_inline`) live in
[`docs/UPSTREAM_COMPATIBILITY.md`](docs/UPSTREAM_COMPATIBILITY.md).

## Repository layout
```
:core:model       FileChange, the 16 ClientToolName, terminal-timeout clamp  (JVM, tested)
:core:protocol    ClientToolCallValidator — unknown tool fails loudly          (JVM, tested)
:core:security    PathSafety (traversal guard) + EnvRedaction (secret scrub)   (JVM, tested)
:core:workspace   Unified diff, apply_patch (create/update/delete), str_replace
                  multi-replacement, ignore rules + tree caps, dirty-state     (JVM, tested)
:app              Compose + Material3 vertical-slice shell                     (Android)
protocol-ref/     Dependency-free Node reference of the host contract          (Node, tested)
```
The `:core:*` JVM modules build and test with only JDK 17 + Gradle + Maven Central (no Android SDK).

## Build & test

### Core JVM tests (no Android SDK required)
```sh
gradle test           # 20 Kotlin unit tests
node --test protocol-ref/   # 7 protocol contract tests
```

### Android debug APK
Requires the Android SDK (platforms;android-34, build-tools;34.0.0). Google's endpoints may need
mirrors (see `docs/android-runtime.md`).
```sh
export FB_ANDROID=1
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Install
Enable "install unknown apps" on the device, then:
```sh
adb install -r freebuff-android-debug-v0.1.0-slice.apk
```

## Security model (spec §16)
- Path canonicalization + traversal/absolute-path rejection before any file write.
- BYOK/provider secrets are Keystore-scoped and scrubbed from terminal subprocess environments.
- Sensitive env keys are never logged; free-text logs are redacted.
- Repository-provided code / MCP / shell execution require an explicit trust gate.

## Limitations (honest, not faked)
- Terminal/PTY (NDK), Git adapter, browser/WebView preview, image input, live BYOK agent streaming,
  and the plan/todo/subagent surfaces are **not yet implemented**.
- Release AAB is not signed here (keystore is CI-only).
- Instrumentation/emulator tests are not run in this environment.
- Definition-of-Done in the Master Spec is **not** met.

See [`engineering-log.json`](engineering-log.json) for the machine-readable status.

## License
Apache-2.0 (matching upstream Freebuff). This is an independent port; it is not the upstream project.
