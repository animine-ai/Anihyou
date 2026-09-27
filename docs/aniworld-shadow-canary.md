# AniWorld Shadow Canary

This is an internal developer diagnostic. It runs only in an explicitly opted-in Foss debug build. R2 stays the user-visible release path.

## Build

The project property is read only by the `debug` build type. All release-like build types set `ANIWORLD_SHADOW_CANARY=false`; runtime scheduling also requires `BuildConfig.DEBUG`.

Build without installing:

`./scripts/run-aniworld-shadow-canary.sh --build`

The helper builds `:app:assembleFossDebug` with `-PaniworldShadowCanary=true`, confirms the generated debug BuildConfig contains the true flag, and prints the universal APK path.

## Run once on a device

Connect one authorized Android device with runtime network access, then run:

`./scripts/run-aniworld-shadow-canary.sh --install-and-run`

For multiple devices, set `ANDROID_SERIAL` to the selected device serial. The helper installs the debug APK without clearing app data, verifies `run-as`, launches the app once, and waits at most 300 seconds for a new terminal generation with a metric. Increase or reduce the bound with `--timeout-seconds` (1..900). A cooldown, missing network, or a worker failure is reported as a timeout or an explicit export error; the helper does not keep polling indefinitely.

The scheduler uses one unique WorkManager one-time request with `KEEP` and a `CONNECTED` constraint. Canary work does not automatically retry. Reopening the explicit canary debug build can schedule one fresh bounded attempt after the previous work has completed.

## Export an existing sample

`./scripts/run-aniworld-shadow-canary.sh --report-only --output shadow-report.json`

The Python exporter requires a debuggable package and `adb run-as`. It copies only the app-private SQLite file and its WAL snapshot into a temporary mode-0700 directory, reads Room schema 13 in read-only mode, then deletes the temporary copy. The output is a bounded JSON report with the latest ten generations by default.

The report contains build identity, generation state/outcome/reason, start/end timestamps, validated manifest count/digest, attempt/completion/role/429 summaries, scoped and host cooldown summaries, validated v1 metric payloads and normalized counters including per-source outcomes. Direct URLs are not exported.

No AniList data, access tokens, headers, cookies, HTML, response bodies, or full database are included in the JSON report. Unknown database/payload versions fail closed; malformed v1 JSON is marked malformed and excluded from normalized/raw payload output.

## CI boundary

CI runs parser fixtures and Kotlin tests only. The local canary helper is never invoked by CI and must not be wired into a workflow. GitHub Actions continues to use local fixtures, fakes, and loopback I/O, with no live request to AniWorld.

