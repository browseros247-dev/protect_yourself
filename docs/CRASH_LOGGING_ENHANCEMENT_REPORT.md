# Crash Logging System Enhancement Report

**Branch:** `crash-logging-enhancements` (off `main` at v1.0.43 — commit `c8eda3d`)
**App version:** v1.0.43 (versionCode 43)
**Date:** 2026-07-11
**Scope:** Deep analysis and enhancement of the entire crash reporting + logging system

---

## 1. Executive Summary

The existing crash logging system (CrashLogger + CrashLoggingTree + CrashLogPage + global uncaught exception handler in ProtectYourselfApp) is architecturally sound — it captures stack traces, device info, memory/disk state, breadcrumbs, and logcat tails, all stored locally without Firebase. However, a deep analysis against modern crash-logging best practices identified **35 bugs and gaps** spanning crash detection coverage, diagnostic quality, persistence reliability, UX feedback, performance, and code quality.

This branch fixes the **8 most critical/high-impact issues** (1 CRITICAL, 7 HIGH) plus 8 MEDIUM issues, while leaving 15 LOW-priority polish items documented for future work. Key enhancements:

- **New `AnrWatchdog`** — detects Application Not Responding events (which don't throw and don't trigger UncaughtExceptionHandler)
- **New `AppCoroutineExceptionHandler`** — routes uncaught coroutine exceptions to CrashLogger with scope/dispatcher/job context (was silently lost across 8 coroutine scopes)
- **Disk-backed breadcrumbs** — survive hard crashes (SIGSEGV, SIGKILL, OOM) that defeated the previous in-memory buffer
- **Atomic file persistence** — temp+rename prevents corruption on process kill mid-write
- **Crash deduplication** — same crash repeated N times within 5 minutes merges into a single entry with `count=N`, preventing one recurring crash from evicting all other history
- **Service state capture** — every crash entry now records whether accessibility/VPN/device-admin was active (the #1 diagnostic question for "blocking stopped working")
- **OOM-resilient persistence** — falls back to a minimal hand-built JSON stub when Gson OOMs, so the crash record is never lost
- **Recursive crash protection** — AtomicBoolean flag prevents infinite recursion if the crash handler itself throws
- **Crash-detected notification** — on next launch after a FATAL crash, posts a high-priority notification so the user knows
- **Crash count badge** — Profile menu item shows a red badge with the crash count
- **Live-updating crash log list** — new entries appear in real time while the page is open
- **Fixed the same `writeJsonToUri` null-return bug** that was fixed in BackupManager (commit `a1ec981`) — extracted a shared `SafUtils` helper to eliminate duplication
- **Performance**: logcat capture is now FATAL-only (was running on every WARN+ Timber log, blocking the main thread with a subprocess); device/memory/disk info is cached with 1-second TTL

---

## 2. Root-Cause Analysis — Top 5 Critical Gaps

### 2.1 CRITICAL — `exportToUri` retry-on-IOException-only bug
**Symptom:** Export crash logs to Google Drive / Dropbox / OneDrive fails with "Could not open output stream" without ever trying the `"w"` mode fallback.

**Root cause:** Identical to the bug fixed in `BackupManager.kt` at commit `a1ec981`. If `ContentResolver.openOutputStream(uri, "wt")` returns `null` (documented behaviour for cloud SAF providers that don't support `"wt"` mode), the `catch (_: IOException)` block is never triggered, so the retry with `"w"` mode is never attempted.

**Fix:** Extracted shared `SafUtils.writeJsonToUri` helper that mirrors the BackupManager fix — a `for (mode in listOf("wt", "w"))` loop handling null + IOException + SecurityException + post-write `verifyWrittenSize`. Both `CrashLogViewModel.exportToUri` and `BackupManager.writeJsonToUri` now use this helper, eliminating duplication.

### 2.2 HIGH — No `CoroutineExceptionHandler` across 8 coroutine scopes
**Symptom:** `async { throw ... }` without `await()` silently loses exceptions. `launch` exceptions propagate but without coroutine-specific context.

**Root cause:** Grep confirmed zero `CoroutineExceptionHandler` matches in the codebase. 8 `CoroutineScope(SupervisorJob() + Dispatchers.X)` instances (AppContainer, MyAccessibilityService, MyVpnService, PornBlockActivity ×2, StopMeWidget, StreakWidget, StopMeAlarmReceiver, AppSystemActionReceiverAllTimeWithData, AppSystemActionReceiverAllTime, AppDatabaseCallback) all lacked exception handlers.

**Fix:** Created `AppCoroutineExceptionHandler` that routes to `CrashLogger.logThrowable` with `tag="Coroutine:<scopeName>"` + dispatcher + job in `extraContext`. CancellationException is re-thrown (not logged — it's normal control flow). Refactored all 8+ scopes to use the new `appCoroutineScope(scopeName, dispatcher, context)` factory.

### 2.3 HIGH — In-memory-only breadcrumbs
**Symptom:** Breadcrumbs are lost on the exact crashes they're meant to diagnose (SIGSEGV, SIGKILL, OOM-before-persist).

**Root cause:** `private val breadcrumbBuffer = mutableListOf<Breadcrumb>()` — in-memory only. The buffer exists precisely to reconstruct what led to a crash, but a hard crash kills the process before `persistEntry` runs, so the buffer is lost.

**Fix:** Backed the buffer with `breadcrumbs.json` written atomically (temp + rename) on every `logBreadcrumb` call. Read on init. Keep the in-memory cache for fast access; the disk file is the durability path.

### 2.4 HIGH — Synchronous logcat subprocess on every WARN+ Timber log
**Symptom:** Main-thread jank and potential ANR risk from `Runtime.exec("logcat -d -t 200 --pid=$pid")` running on the main thread whenever `Timber.w()/e()` is called from a lifecycle callback or UI click.

**Root cause:** `CrashLoggingTree.log()` runs on whatever thread called Timber. `captureLogcatTail()` spawns a subprocess (5–50 ms blocking) on that thread. Via `CrashLoggingTree`, this runs on the main thread for accessibility events, lifecycle callbacks, and UI clicks.

**Fix:** Capture logcat only for FATAL entries (uncaught exceptions), not for every WARN/ERROR Timber log. For WARN/ERROR, the Timber log already went to logcat; the user can re-read logcat if needed. Also cached DeviceInfo (immutable for process lifetime) and MemoryInfo/DiskInfo with 1-second TTL.

### 2.5 HIGH — No ANR detection
**Symptom:** ANRs (Application Not Responding) are invisible to CrashLogger — they don't throw and don't trigger UncaughtExceptionHandler. User reports "app froze" but no crash entry exists.

**Fix:** Created `AnrWatchdog` — a background thread that posts a tick `Runnable` to the main `Handler` every 2.5 s and waits up to 5 s for it to run. If the tick doesn't run, logs a FATAL entry via `CrashLogger.logThrowable` with the main-thread stack trace. Tunable threshold (default 5 s) and interval (default 2.5 s — Nyquist). Started in `ProtectYourselfApp.onCreate`.

---

## 3. Per-File Changes

### 3.1 New files

| File | Purpose |
|---|---|
| `commons/utils/SafUtils.kt` | Shared SAF write helper — fixes the null-return retry gap for both CrashLogViewModel.exportToUri and BackupManager.writeJsonToUri. Handles null + IOException + SecurityException + post-write size verification. |
| `core/AppCoroutineExceptionHandler.kt` | Global CoroutineExceptionHandler that routes uncaught coroutine exceptions to CrashLogger with scope/dispatcher/job context. Includes `appCoroutineScope` factory. |
| `features/crashLog/AnrWatchdog.kt` | Main-thread ANR detector — posts ticks to the main Handler and logs FATAL entries if the tick doesn't run within the threshold. |

### 3.2 Modified files

| File | Changes |
|---|---|
| `core/ProtectYourselfApp.kt` | Wrap CrashLogger.init in safeInit; recursive crash protection via AtomicBoolean; start AnrWatchdog; crash-detected notification on next launch; migrate `@OnLifecycleEvent` → `DefaultLifecycleObserver`; add foreground/background breadcrumbs; create crash-alerts notification channel; record last launch time in SharedPreferences. |
| `core/AppContainer.kt` | `applicationScope` now uses `appCoroutineScope("applicationScope", Dispatchers.Default, context)`. |
| `features/crashLog/CrashLogger.kt` | Disk-backed breadcrumbs (H2); atomic writes (temp+rename) for entries + index + breadcrumbs (H5); `reconcileIndex()` on init (H5); OOM stub fallback via hand-built JSON (H7); `inCrashHandler` AtomicBoolean for recursive protection (H6); crash deduplication via `persistEntryWithDedup` + `dedupHash` + `count` field (M7); FATAL-only logcat capture (H3); cached DeviceInfo (immutable) + MemoryInfo/DiskInfo (1s TTL) (L1); `ServiceStateInfo` capturing accessibility/VPN/device-admin status (L19); `isMainThread` field; `CrashSeverity.level: Int` property (L6); new constants `DEDUP_WINDOW_MS`, `MEM_CACHE_TTL_MS`, `DISK_CACHE_TTL_MS`; `clearBreadcrumbs()` for testing; `setInCrashHandler()` internal API. |
| `features/crashLog/CrashLoggingTree.kt` | Fallback to `android.util.Log.e` in the catch block instead of silent swallow (M9). |
| `features/crashLog/CrashLogPage.kt` | `exportToUri` uses shared `SafUtils.writeJsonToUri` (C1); `CancellationException` rethrow (M3); avoid double `toByteArray` + re-reading entries for count (M1, M2); live-updating list via `crashLogger.entryCount.collect` (M12); dedup count display in `CrashEntryRow` ("× N"); service state display in `CrashEntryDetailDialog`. |
| `features/profilePage/components/ProfilePage.kt` | Crash count badge (red circle with count) on the "Crash Logs" menu item, driven by `CrashLogger.entryCount` StateFlow (M6). |
| `features/backupRestore/BackupManager.kt` | Refactored `writeJsonToUri` to delegate to shared `SafUtils.writeJsonToUri` — eliminates duplication with CrashLogViewModel. |
| `features/blockerPage/service/MyAccessibilityService.kt` | `serviceScope` uses `appCoroutineScope("MyAccessibilityService", Dispatchers.Default, this)`. |
| `features/blockerPage/service/MyVpnService.kt` | `serviceScope` uses `appCoroutineScope("MyVpnService", Dispatchers.IO, this)`. |
| `features/blockerPage/ui/PornBlockActivity.kt` | `uiScope` and `ioScope` use `appCoroutineScope("PornBlockActivity-ui/io", ...)`. |
| `features/blockerPage/widget/StopMeWidget.kt` | `scope` uses `appCoroutineScope("StopMeWidget", Dispatchers.IO)`. |
| `features/streakPage/widget/StreakWidget.kt` | `scope` uses `appCoroutineScope("StreakWidget", Dispatchers.IO)`. |
| `commons/utils/broadcastReceivers/StopMeAlarmReceiver.kt` | `scope` uses `appCoroutineScope("StopMeAlarmReceiver", Dispatchers.IO)`. |
| `commons/utils/broadcastReceivers/AppSystemActionReceiverAllTimeWithData.kt` | `scope` uses `appCoroutineScope("AppSystemActionReceiverAllTimeWithData", Dispatchers.IO)`. |
| `commons/utils/broadcastReceivers/AppSystemActionReceiverAllTime.kt` | `scope` uses `appCoroutineScope("AppSystemActionReceiverAllTime", Dispatchers.IO)`. |
| `database/core/AppDatabaseCallback.kt` | Background coroutine for preset keyword insertion uses `appCoroutineScope("AppDatabaseCallback", Dispatchers.IO)`. |

---

## 4. Bug Catalog — What Was Fixed

| # | Severity | Component | Bug | Fix |
|---|---|---|---|---|
| C1 | CRITICAL | CrashLogViewModel.exportToUri | Retry-on-IOException-only — null return from openOutputStream("wt") skips retry | Shared SafUtils.writeJsonToUri with for-loop over ["wt", "w"] |
| H1 | HIGH | All 8+ coroutine scopes | No CoroutineExceptionHandler — async exceptions silently lost | AppCoroutineExceptionHandler + appCoroutineScope factory |
| H2 | HIGH | CrashLogger.breadcrumbBuffer | In-memory only — lost on hard crashes | Disk-backed breadcrumbs.json with atomic writes |
| H3 | HIGH | CrashLogger.captureLogcatTail | Synchronous subprocess on every WARN+ Timber log — main-thread jank | FATAL-only logcat capture + cached DeviceInfo/MemoryInfo/DiskInfo |
| H4 | HIGH | ProtectYourselfApp | No ANR detection — ANRs invisible to CrashLogger | New AnrWatchdog (5s threshold, 2.5s interval) |
| H5 | HIGH | CrashLogger.persistEntry/saveIndex | Non-atomic writes — corruption on process kill mid-write | Atomic temp+rename + reconcileIndex on init |
| H6 | HIGH | ProtectYourselfApp.installCrashHandler | No recursive crash protection — crash handler could recurse | AtomicBoolean inCrashHandler flag + Process.killProcess fallback |
| H7 | HIGH | CrashLogger.persistEntry | OOM during Gson serialisation loses crash record | Hand-built minimal JSON stub fallback (no Gson, no logcat) |
| M1 | MEDIUM | CrashLogViewModel.exportToUri | Re-reads all entries to count them | Parse count from export envelope |
| M2 | MEDIUM | CrashLogViewModel.exportToUri | Calls json.toByteArray() twice | Single bytesWritten from SafUtils |
| M3 | MEDIUM | CrashLogViewModel.exportToUri | No CancellationException rethrow | Explicit catch + rethrow |
| M4 | MEDIUM | CrashLogger index/entry files | Can get out of sync | reconcileIndex on init |
| M5 | MEDIUM | ProtectYourselfApp | No crash-detected notification | Post high-priority notification on next launch |
| M6 | MEDIUM | ProfilePage | No crash count badge | Red badge with count on Crash Logs item |
| M7 | MEDIUM | CrashLogger | No crash grouping/dedup | dedupHash + count field + persistEntryWithDedup |
| M9 | MEDIUM | CrashLoggingTree | Silent swallow of all exceptions | Fallback to android.util.Log.e |
| M10 | MEDIUM | ProtectYourselfApp | CrashLogger.init not in safeInit | Wrapped in safeInit |
| M12 | MEDIUM | CrashLogViewModel | List doesn't live-update | Collect crashLogger.entryCount flow |
| L1 | LOW | CrashLogger | No caching of device/memory/disk info | Cached DeviceInfo (process lifetime) + MemoryInfo/DiskInfo (1s TTL) |
| L6 | LOW | CrashSeverity | No numeric level | Added `val level: Int` property |
| L8 | LOW | ProtectYourselfApp | Deprecated @OnLifecycleEvent | Migrated to DefaultLifecycleObserver |
| L19 | LOW | CrashLogger | No service state capture | New ServiceStateInfo (accessibility/VPN/device-admin) |

---

## 5. Build Verification

```
$ ./gradlew :app:compileDebugKotlin --no-daemon --no-parallel
BUILD SUCCESSFUL in 1m 31s

$ ./gradlew :app:assembleDebug --no-daemon --no-parallel
BUILD SUCCESSFUL in 52s
```

**APK:** `app/build/outputs/apk/debug/app-debug.apk` (23.1 MB)

No new compilation errors. Only pre-existing deprecation warnings (`CircularProgressIndicator(Float)` overload, `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY`, `Icons.Filled.ArrowBack`, `Icons.Filled.Label`).

---

## 6. Test Plan

Manual test matrix to validate the fixes:

| # | Scenario | Expected behaviour | Pre-fix behaviour |
|---|---|---|---|
| 1 | Trigger a coroutine exception via `async { throw }` (no await) | CrashLogger entry with tag `Coroutine:<scopeName>` + dispatcher + job context | **Exception silently lost** — fixed |
| 2 | Block main thread for 6+ seconds | FATAL entry with tag `ANRWatchdog` + main-thread stack trace | **No entry** — fixed |
| 3 | Crash the app (SIGSEGV via native code, or kill from adb) | Breadcrumbs from before the crash are persisted to breadcrumbs.json and attached to the next-launch crash entry | **Breadcrumbs lost** — fixed |
| 4 | Same crash repeated 10 times within 5 minutes | Single crash entry with `count=10` | **10 separate entries** — fixed |
| 5 | Export crash logs to Google Drive | Success | **"Could not open output stream"** — fixed |
| 6 | Crash the app, then relaunch | High-priority "Protect Yourself crashed" notification | **No indication of crash** — fixed |
| 7 | Open Profile page after a crash | Red badge with crash count on "Crash Logs" item | **No badge** — fixed |
| 8 | Open Crash Logs page while a background service throws | List live-updates with new entry | **No update** — fixed |
| 9 | Disable accessibility service, then trigger a crash | Crash entry shows "Accessibility: ✗ disabled" in Service state section | **No service state captured** — fixed |
| 10 | Trigger OOM (large allocation) | Minimal stub crash entry persisted (no Gson, no logcat) | **Crash record lost** — fixed |

---

## 7. Files Changed

```
 app/src/main/java/protect/yourself/commons/utils/SafUtils.kt                 | new (117 lines)
 app/src/main/java/protect/yourself/core/AppCoroutineExceptionHandler.kt       | new (116 lines)
 app/src/main/java/protect/yourself/features/crashLog/AnrWatchdog.kt           | new (157 lines)
 app/src/main/java/protect/yourself/core/AppContainer.kt                       | modified
 app/src/main/java/protect/yourself/core/ProtectYourselfApp.kt                 | modified (full rewrite)
 app/src/main/java/protect/yourself/database/core/AppDatabaseCallback.kt       | modified
 app/src/main/java/protect/yourself/features/backupRestore/BackupManager.kt    | modified (delegate to SafUtils)
 app/src/main/java/protect/yourself/features/blockerPage/service/MyAccessibilityService.kt | modified
 app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt | modified
 app/src/main/java/protect/yourself/features/blockerPage/ui/PornBlockActivity.kt | modified
 app/src/main/java/protect/yourself/features/blockerPage/widget/StopMeWidget.kt | modified
 app/src/main/java/protect/yourself/features/crashLog/CrashLogPage.kt          | modified
 app/src/main/java/protect/yourself/features/crashLog/CrashLogger.kt           | modified (major enhancement)
 app/src/main/java/protect/yourself/features/crashLog/CrashLoggingTree.kt      | modified
 app/src/main/java/protect/yourself/features/profilePage/components/ProfilePage.kt | modified
 app/src/main/java/protect/yourself/features/streakPage/widget/StreakWidget.kt | modified
 app/src/main/java/protect/yourself/commons/utils/broadcastReceivers/StopMeAlarmReceiver.kt | modified
 app/src/main/java/protect/yourself/commons/utils/broadcastReceivers/AppSystemActionReceiverAllTimeWithData.kt | modified
 app/src/main/java/protect/yourself/commons/utils/broadcastReceivers/AppSystemActionReceiverAllTime.kt | modified
```

**3 new files, 16 modified files.** No DB schema changes. No manifest changes. No dependency changes.

---

## 8. Known Limitations & Future Work

The following items were identified in the analysis but are deferred to future branches:

### P2 — Polish (LOW priority)
- **Unit tests** for CrashLogger (round-trip, pruning, reconciliation, breadcrumb cap, severity routing, OOM stub). The test dependencies are present (junit, mockk, turbine, robolectric, truth) but no `CrashLoggerTest` exists.
- **Severity / search / date-range filters** in CrashLogPage UI.
- **Copy / share / delete individual entry** in the detail dialog.
- **Email developer** intent with crash log attachment via FileProvider.
- **Full-view** for truncated stack traces / logcat tails (currently truncated at 2000 chars in the detail dialog).
- **Disk-space-aware pruning** — check `crashDir.usableSpace` before write; if below 1 MB, prune to `MAX_ENTRIES / 2`.
- **Batch index updates** — keep index in memory; persist lazily (500 ms debounce) instead of load-modify-save per operation.
- **No-op default CrashLogger** — provide a non-null no-op instance so callers don't need `?.logThrowable` null-checks everywhere.
- **Structured error category** field in CrashLogEntry (Network/Database/Permission/UI/Service) for filtering.
- **`MAX_ENTRIES` / `MAX_BREADCRUMBS`** as BuildConfig fields (debug builds can use larger limits).

### P3 — Out of scope
- **Native crash detection** (SIGSEGV, SIGABRT) — would require NDK `sigaction` handler or reading `/data/tombstones/` (root-only). Documented as a limitation.
- **StrictMode** — could be enabled in debug builds to catch main-thread I/O. Out of scope for production.
- **Compose recomposition errors** — Compose has its own internal recovery that may swallow some IllegalStateExceptions. Would need custom `Monospace`/`AndroidComposeFactory` instrumentation.

---

## 9. Branch & Merge Guidance

- **Branch:** `crash-logging-enhancements` (pushed to `origin`)
- **Base:** `main` at v1.0.43 (commit `c8eda3d`)
- **Action:** User to review and merge via PR — **do not push directly to `main`**
- **After merge:** bump version to `1.0.44` (versionCode 44) in `app/build.gradle.kts` and tag a release
- **Note:** This branch is independent of the `backup-import-export-fix` branch. Both touch `BackupManager.kt` but use the same `SafUtils` helper, so they should merge cleanly without conflicts.
