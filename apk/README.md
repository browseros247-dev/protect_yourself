# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.73 (versionCode 73) — Proactive audit round: CrashLogger thread-safety + per-WARN binder-IPC fix

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.73-release.apk` | **~3.27 MB** | Release | **Recommended for installation.** A full proactive audit round (per the standing "find and fix all bugs while preserving functionality" objective) swept the high-risk subsystems — broadcast receivers & manifest filters, singleton/DB initialization, coroutine scopes (ViewModel/Service/Widget), BackupManager export/import/restore, the v1.0.71-hardened accessibility traversal budgets, and VPN stop/revoke state sync. All proven safe, **except one subsystem with two real defects in CrashLogger**: **(1) CRLOG-TS-01 — thread-unsafe `SimpleDateFormat`.** The logger's two shared formatters were used UNGUARDED while `CrashLoggingTree` enters the logger concurrently from every WARN+ Timber call on ANY thread (accessibility binder thread, WorkManager workers, IO coroutines, main). `SimpleDateFormat.format()` mutates an internal Calendar; concurrent calls corrupt timestamp strings (or throw internal AIOOBE) — corrupting/loosing the very crash entries meant to diagnose field issues. Fix: a single `timestampLock` funnelled through `formatTimestamp`/`formatFileTimestamp`, all 5 format sites wrapped. **(2) CRLOG-IPC-01 — 3–4 binder IPCs per WARN log.** Every WARN+ entry synchronously captured AppInfo (`getCurrentProcessName` + `getInstallerPackageName`, 2 binder IPCs) and ServiceStateInfo (Settings.Secure read + `DevicePolicyManager.isAdminActive`, ~2 IPCs) **on the caller thread** — the exact per-event binder-storm class that caused the vivo V2206 ANRs in v1.0.71, re-triggerable during any warn-loop storm. Fix: `cachedAppInfo` (immutable per process → captured once) + 1-second TTL `captureServiceStateCached` (same pattern as the existing memory/disk caches). Behavior, file formats, dedup, and crash-capture content are all unchanged; only the concurrency correctness and per-log syscall cost differ. Carries v1.0.72 (top-bar theme switcher, mandatory onboarding, dark-mode buttons, layout audit), v1.0.71, and all earlier rounds. See `docs/PROACTIVE_AUDIT_CRASHLOG_REPORT_v1.0.73.md`. **458/458 tests pass.** |
| `protect.yourself-v1.0.73-debug.apk` | ~20.9 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.73)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** in 5 m 46 s (R8 minified, 3,274,757 bytes; byte-identical size to v1.0.72)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (21,296,115 bytes)
- `./gradlew :app:testDebugUnitTest` → **458/458 tests pass, 0 failures, 0 errors, 0 skipped** (37 suites; +5 new `CrashLoggerRobustnessTest`; run AFTER the builds, per release-first process)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `73`, versionName `1.0.73`, minSdk 26, targetSdk 35
- Post-R8 mapping check → `CrashLogger.cachedAppInfo$delegate`, `captureServiceStateCached()` present in the release APK; `CrashLogger` class retained and fully mapped
- Audit of `!!` operator sites (5 total): all verified state-guarded (import-confirmation URI), loop-invariant (KeywordMatcher failure-chain, covered by 43 `BlockingValidatorTest` tests) — no unguarded force-unwraps found

## New tests (5 in v1.0.73, all passing)

`features/crashLog/CrashLoggerRobustnessTest` (5; static pins + real Robolectric concurrency smoke tests):

| Test | What it pins |
|---|---|
| `every SimpleDateFormat use goes through the timestampLock wrappers` | Zero bare `dateFormat.format(Date())`/`fileDateFormat.format(Date())` sites; wrappers exist and hold `timestampLock` for the full format call (TS-01 can't regress) |
| `app info is captured once per process, not once per log call` | `cachedAppInfo` by lazy; entry builds use it (3 sites); no per-call `captureAppInfo()` in entry construction (IPC-01 can't regress) |
| `service state capture has a TTL cache like memory and disk` | `SERVICE_STATE_CACHE_TTL_MS = 1000L` + `captureServiceStateCached()`; entry builds no longer call the IPC-heavy path directly |
| `concurrent logMessage from 8 threads - all entries well-formed, nothing lost` | 8×60 real concurrent `logMessage` calls against the real logger: zero escaped exceptions, 480/480 ids returned, persisted files carry `crash_yyyyMMdd_HHmmss_####` names + well-formed `"timestampFormatted"` values (pre-fix this could corrupt) |
| `concurrent breadcrumbs from 4 threads - buffer bounded, no corruption` | 4×50 concurrent `logBreadcrumb` calls: zero escaped exceptions; a subsequent entry still builds cleanly after the storm |

## Manual device checklist (post-install)

1. Use the app normally for a few minutes (blocking on), then open Profile → Crash Log: entries show correct human-readable timestamps (no garbled characters).
2. Trigger a burst of warnings (e.g. toggle a permission off and on while the app is in the foreground) → crash log gains well-formed WARN entries; the app stays responsive (no ANR) during the burst.
3. Regression sweep: crash log dedup still groups repeats within 5 min into a count; breadcrumbs still attach to entries; export-to-file still produces a complete JSON export; FATAL entries still carry the logcat tail.
4. Full regression sweep from v1.0.72 still holds: top-bar theme switcher, mandatory onboarding gate, dark-mode button contrast, and all blocking/VPN/schedules/focus/lock behavior.

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
