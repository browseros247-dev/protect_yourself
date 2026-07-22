# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.66 (versionCode 66) — Onboarding Permission Requests (OB-PERM)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.66-release.apk` | ~15.5 MB | Release | **Recommended for installation.** The onboarding flow previously never requested any runtime/special-access permission (OB-PERM-01..06) — notifications (`POST_NOTIFICATIONS`, API 33+), battery-optimization exemption ("background running"), exact alarms (`SCHEDULE_EXACT_ALARM`, API 31+), and the accessibility service were left to chance, silently breaking protection alerts and background reliability on fresh installs. Onboarding is now a two-step flow (Terms → Permission checklist) with live grant-state detection, per-row grant actions, safe system-intent fallbacks, and crash-log breadcrumbs. Also: `NotificationHelper` now gates posting on `areNotificationsEnabled()` (OB-PERM-05), and onboarding state survives rotation/process death (OB-PERM-06). Carries all previous fixes (v1.0.63 boot-VPN restore, v1.0.64 VPN review, v1.0.65 App Lock session reset). See `docs/ONBOARDING_PERMISSIONS_REPORT_v1.0.66.md`. 340/340 tests pass. |
| `protect.yourself-v1.0.66-debug.apk` | ~22.5 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable. |

## Build verification (v1.0.66)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL**
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**
- `./gradlew :app:testDebugUnitTest` → **340/340 tests pass, 0 failures, 0 errors, 0 skipped** (22 suites; +14 new OnboardingPermissionsTest cases)
- `apksigner verify` → **signature valid** for both APKs (debug keystore per `release { signingConfig = signingConfigs.getByName("debug") }` — re-sign with your own release keystore for Play Store distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `66`, versionName `1.0.66`, minSdk 26, targetSdk 35
- `aapt2 dump permissions` → `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, and new `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (OB-PERM-02) all present in the merged manifest
- `OnboardingPermissions` code verified present in DEX of both APKs
- Prior-round artifacts still verified: `BootVpnRestoreAlarmReceiver` receiver + `VpnRestoreHelper` live-restore funnel

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
