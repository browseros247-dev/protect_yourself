# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.67 (versionCode 67) — App Size & Performance Optimization (PERF)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.67-release.apk` | **~3.3 MB** | Release | **Recommended for installation.** Size & performance round (PERF-01..05): release APK shrank from **15.5 MB → 3.3 MB (−78%; raw payload 51 → 6.5 MB, −87%)** by enabling R8 code shrinking+obfuscation and unused-resource shrinking, English-only `resourceConfigurations`, and removing two unused dependencies (`runtime.livedata`, `lottie.compose`). All reflection entry points are pinned by keep rules + a regression-guard test (Gson backup/crash-log models incl. Room entities, WorkManager workers, enum constants). Startup improved: WorkManager scheduling and the crash-log disk scan now run off the main thread (PERF-02/03), and per-step + total init timing is logged (PERF-04). All functionality preserved — carry-over: v1.0.66 onboarding permissions, v1.0.63–65 VPN + App Lock fixes. See `docs/PERF_SIZE_OPTIMIZATION_REPORT_v1.0.67.md`. 348/348 tests pass. |
| `protect.yourself-v1.0.67-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.67)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** (R8 `minifyReleaseWithR8` + `shrinkReleaseRes` completed)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**
- `./gradlew :app:testDebugUnitTest` → **348/348 tests pass, 0 failures, 0 errors, 0 skipped** (23 suites; +8 new ProguardRulesRegressionTest guards)
- Size delta measured on the packaged artifacts: release **15.48 MB → 3.30 MB** (−78.7%); raw uncompressed **51.0 MB → 6.54 MB**; dex payload **48.2 MB → 4.85 MB**
- `apksigner verify` → **signature valid** for both APKs (debug keystore per `release { signingConfig = signingConfigs.getByName("debug") }` — re-sign with your own release keystore for Play Store distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `67`, versionName `1.0.67`, minSdk 26, targetSdk 35
- Post-R8 integrity: critical FQNs verified present in dex (`VpnRestartWorker`, `ScheduleCheckWorker`, `BackupEnvelope`, `CrashLogEntry`, `MyVpnService`, `BootVpnRestoreAlarmReceiver`, `MyAccessibilityService`, `OnboardingPermissions`); 26 manifest components intact; baseline profile + all four ABI `libandroidx.graphics.path.so` retained

## New tests (10 across v1.0.63+v1.0.64, all passing)

v1.0.63 — boot-restore regression suite (`VpnRestartWorkerEnqueueTest`):

| Test | What it pins |
|---|---|
| `expedited request with initial delay throws at build time` | The platform constraint that caused BOOT-VPN-01 — guards against re-introduction |
| `enqueue schedules the vpn restart unique work` | **The regression test** — after `VpnRestartWorker.enqueue()`, the unique work really exists in WorkManager (pre-fix: always empty) |
| `repeated enqueues keep a single unique work` | No accumulation of duplicate restore jobs |
| `vpn restart request is expedited and carries no initial delay` | Request spec: expedited, zero initial delay, non-expedited out-of-quota fallback |
| `enqueue replaces a pre-existing stale unique work` | REPLACE (not KEEP) policy — a stale request can never block a fresh restore attempt |

v1.0.64 — comprehensive review regression suite (`VpnReviewFixesTest`):

| Test | What it pins |
|---|---|
| `stop does not start the service when the VPN is not running` | VPN-STOP-02 — no service start (no notification flash) when stopping a dead VPN |
| `vpn permission notification keeps scheduled-restriction copy by default` | VPN-NOTIF-04 — schedule call-site copy unchanged |
| `vpn permission notification uses custom copy when provided` | VPN-NOTIF-04 — boot-restore scenario shows correct copy |
| `restoreIfEnabled returns NOT_ENABLED when vpn switch is off` | DB switch is the source of truth for restore decisions |
| `backup alarm delay is longer than the worker verify window` | Layered-restore invariant (backup fires after primary path) |

## Manual device checklist (post-install)

1. Enable VPN → confirm "Connected".
2. Reboot → do NOT open the app → VPN notification + tunnel re-appear (WorkManager path within seconds of BOOT_COMPLETED/unlock; backup alarm within ~45 s worst case).
3. Turn VPN off → reboot → VPN stays OFF.
4. App update (`adb install -r`) without reboot → VPN restored via `MY_PACKAGE_REPLACED` path.

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
