# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.68 (versionCode 68) — Block Screen Reliability + Default Countdown Timer

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.68-release.apk` | **~3.3 MB** | Release | **Recommended for installation.** Block-screen reliability round: **(1) Default countdown timer** — previously unset (no code path could ever persist a custom value), so block screens closed instantly; now a **3s default** applies whenever no valid custom value is set (TIMER-DEFAULT-01), on BOTH the overlay and activity block paths. **(2) Block screen reliability** (BLOCK-SCREEN-01..04): stuck single-flight latch that permanently suppressed the overlay after permission revoke/service rebind (self-healing via attach-state check), silent background-activity-launch drops on API 29+ (post-launch visibility verification + escalation), `singleTop` stale-extras rebind in `PornBlockActivity.onNewIntent`, wall-clock throttle replaced with monotonic clock, overlay close button can never be left dead. Carries v1.0.67 R8 size optimization (3.3 MB release), v1.0.66 onboarding permissions, v1.0.63–65 VPN + App Lock fixes. See `docs/BLOCK_SCREEN_RELIABILITY_REPORT_v1.0.68.md`. 358/358 tests pass. |
| `protect.yourself-v1.0.68-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.68)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** (R8 minified; size held at ~3.3 MB)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**
- `./gradlew :app:testDebugUnitTest` → **358/358 tests pass, 0 failures, 0 errors, 0 skipped** (24 suites; +10 new BlockScreenReliabilityTest cases covering unset/zero/negative/unparsable/above-max/custom/boundary countdown values + visibility-flag contract)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `68`, versionName `1.0.68`, minSdk 26, targetSdk 35
- Post-R8 dex check: `BlockOverlayManager`, `PornBlockActivity`, `DEFAULT_BLOCK_SCREEN_*` all present in the release DEX

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
