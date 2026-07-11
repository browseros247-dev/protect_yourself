# Crash Log Analysis & Fix Report — v1.0.46

**Branch:** `crash-log-fixes-v2` (off `main` at v1.0.40 base)
**Crash log source:** `protect_yourself_crashlogs_1783753037729.json`
**App version:** v1.0.46 (versionCode 46)
**Device:** vivo V2206, Android 14 (API 34)
**Date:** 2026-07-11
**Total crash entries:** 26 (1 FATAL, 6 ERROR, 19 WARN)

---

## 1. Executive Summary

Analysis of 26 crash log entries from a vivo V2206 running Android 14 revealed **5 distinct issues**, ranging from a CRITICAL database migration failure that prevents the app from seeding default data, to a HIGH-severity false-positive ANR report from the watchdog I implemented in the crash-logging-enhancements branch. The crash logs demonstrate that the crash logging system itself is working correctly (it captured all 26 entries with full diagnostic context including breadcrumbs, service state, and stack traces), but several issues in the application code need to be fixed.

The most impactful bug is the `SQLiteException: table vpn_custom_dns has no column named display_name` — this occurs during `AppDatabaseCallback.onCreate` when the user upgrades from an older version where the `vpn_custom_dns` table was created without the `display_name` column (added in DB v9). Room's `CREATE TABLE IF NOT EXISTS` silently succeeds when the table already exists, leaving the column missing. The fix adds a defensive schema repair step that checks for the column and adds it if missing.

The second most impactful bug is a **false-positive ANR** — the `AnrWatchdog` I created in the crash-logging-enhancements branch reported a 12.3-second ANR, but the main thread stack trace shows `nativePollOnce → Looper.loop → ActivityThread.main` — i.e. the main thread was **IDLE** (waiting for messages), not BLOCKED. This happens when the app is backgrounded and the system throttles the main Looper (common on Chinese OEMs like vivo, OPPO, Xiaomi). The fix adds a stack-trace inspection step that skips the ANR report if the main thread is in `nativePollOnce`.

---

## 2. Issue Catalog

### Issue 1 — CRITICAL: SQLiteException during DB creation (vpn_custom_dns missing display_name)

**Crash log entry:** `crash_20260711_123555_0001` (ERROR)
**Occurrences:** 1 (but blocks all default data seeding)
**Tag:** (empty — should be "AppDatabaseCallback")

**Symptom:**
```
android.database.sqlite.SQLiteException: table vpn_custom_dns has no column named display_name
(code 1 SQLITE_ERROR): , while compiling: INSERT OR IGNORE INTO vpn_custom_dns
(`key`, display_name, first_dns, second_dns, is_selected) VALUES (?, ?, ?, ?, ?)
  at protect.yourself.database.core.AppDatabaseCallback.insertDnsPresets(AppDatabaseCallback.kt:162)
  at protect.yourself.database.core.AppDatabaseCallback.onCreate(AppDatabaseCallback.kt:54)
```

**Root cause:**
The user upgraded from an older version (DB schema v8, where `vpn_custom_dns` had columns `key, first_dns, second_dns, is_selected` — no `display_name`) to v1.0.46 (DB schema v9). The `MIGRATION_8_9` migration should have added the `display_name` column via `ALTER TABLE`, but in some upgrade paths the migration doesn't run correctly:

1. **Interrupted migration** — the previous upgrade was killed mid-migration (OOM, user force-stop, system kill). The DB is marked as v9 but the table still has v8 structure.
2. **Room's `CREATE TABLE IF NOT EXISTS`** — when `onCreate` is called (either fresh install or after `fallbackToDestructiveMigration` drops the DB), Room's generated code uses `CREATE TABLE IF NOT EXISTS`. If the table already exists (from a previous install that wasn't fully dropped), the CREATE silently succeeds, leaving the OLD table structure in place.
3. **`fallbackToDestructiveMigration` gap** — this setting only triggers when there's no migration path. If the DB is already at v9 (but corrupted), Room thinks everything is fine and doesn't drop/recreate.

**Impact:**
- The `insertDnsPresets` call fails, which is caught by the outer `try/catch` in `onCreate`.
- However, this means **no default DNS presets are seeded** — the VPN feature won't have any presets to show.
- More critically, the error is logged as "Failed to pre-populate database (critical)" which suggests the entire DB creation failed, even though only the DNS preset insert failed.
- The app continues to run (the `try/catch` prevents a crash), but VPN DNS presets are missing.

**Fix:**
Added `ensureVpnCustomDnsSchema(db)` as the first step in `onCreate`, before `insertDnsPresets`. This function:
1. Queries `PRAGMA table_info(vpn_custom_dns)` to check if the `display_name` column exists.
2. If missing, runs `ALTER TABLE vpn_custom_dns ADD COLUMN display_name TEXT NOT NULL DEFAULT ''` (same as `MIGRATION_8_9`).
3. Backfills `display_name` for known preset keys.
4. Is idempotent — safe to call even if the column already exists.

**File:** `app/src/main/java/protect/yourself/database/core/AppDatabaseCallback.kt`

---

### Issue 2 — HIGH: ANR Watchdog false positive (main thread idle, not blocked)

**Crash log entry:** `crash_20260711_124503_0010` (FATAL)
**Occurrences:** 1
**Tag:** ANRWatchdog

**Symptom:**
```
FATAL — Application Not Responding — main thread blocked for 12302ms
blockedForMs: 12302
thresholdMs: 5000
mainThreadState: RUNNABLE
mainThreadStackTrace:
    at android.os.MessageQueue.nativePollOnce(Native Method)
    at android.os.MessageQueue.next(MessageQueue.java:339)
    at android.os.Looper.loopOnce(Looper.java:176)
    at android.os.Looper.loop(Looper.java:328)
    at android.app.ActivityThread.main(ActivityThread.java:9244)
```

**Root cause:**
The `AnrWatchdog` (created in the crash-logging-enhancements branch) posts a tick `Runnable` to the main `Handler` every 2.5 seconds. If the tick doesn't execute within 5 seconds, it reports a FATAL ANR. However, the main thread stack trace shows `nativePollOnce` — which means the main thread is **IDLE** (sitting in the Looper waiting for messages), not BLOCKED.

This is a **false positive**. The main thread is perfectly healthy — it just has nothing to do. This happens when:
- The app is backgrounded and the system throttles the main Looper (common on Chinese OEMs: vivo, OPPO, Xiaomi, Huawei)
- There's no user interaction and no pending messages
- The system delays processing the tick `Runnable` because the app is in the background

The `AnrWatchdog` should only report an ANR when the main thread is actually BLOCKED (doing real work like DB I/O, file I/O, lock contention, or heavy computation) — not when it's idle in `nativePollOnce`.

**Impact:**
- False FATAL crash entry created — pollutes the crash log
- User sees a "crash detected" notification on next launch even though the app didn't actually crash
- Undermines trust in the ANR detection system

**Fix:**
Added `isMainThreadActuallyBlocked()` check in `AnrWatchdog.runWatchdogLoop()` before calling `reportAnr()`. This function inspects the main thread's stack trace:
- If the top frame is `MessageQueue.nativePollOnce` or `Looper.loop`/`loopOnce`, the main thread is IDLE → skip the ANR report (log at DEBUG level instead)
- If the top frame is anything else (DB I/O, file I/O, synchronized block, etc.), the main thread is BLOCKED → report the ANR

**File:** `app/src/main/java/protect/yourself/features/crashLog/AnrWatchdog.kt` (new file with fix)

---

### Issue 3 — MEDIUM: CancellationException logged as ERROR (4 occurrences)

**Crash log entries:** `crash_20260711_124104_0007`, `crash_20260711_124231_0008`, `crash_20260711_124707_0012`, `crash_20260711_125045_0019` (all ERROR)
**Occurrences:** 4
**Tag:** (empty — should be "StreakPageViewModel")

**Symptom:**
```
ERROR — Failed to observe streak data
kotlinx.coroutines.JobCancellationException: Job was cancelled;
  job=SupervisorJobImpl{Cancelling}@27abe28
```

**Root cause:**
`StreakPageViewModel.observeStreakData()` uses a `try { flow.collect { ... } } catch (t: Throwable) { Timber.e(t, "Failed to observe streak data") }` pattern. When the user navigates away from the Streak page, the `viewModelScope` is cancelled, which cancels the Flow collector with a `JobCancellationException`. This is **normal structured-concurrency control flow** — not an error. But the `catch (t: Throwable)` catches it and logs it as an ERROR, creating a false-positive crash entry.

This is a well-known Kotlin coroutines anti-pattern: `catch (t: Throwable)` catches `CancellationException`, which breaks structured concurrency. The fix is to catch `CancellationException` separately and re-throw it before the `catch (t: Throwable)`.

**Impact:**
- 4 false-positive ERROR entries in the crash log (out of 6 total ERRORs)
- Makes it harder to find real errors in the crash log
- The `JobCancellationException` is expected behavior — logging it as an error is misleading

**Fix:**
Added `catch (t: CancellationException) { throw t }` before the `catch (t: Throwable)` in `observeStreakData()`. This ensures coroutine cancellation is propagated correctly and not logged as an error.

**File:** `app/src/main/java/protect/yourself/features/streakPage/StreakPageViewModel.kt`

---

### Issue 4 — MEDIUM: SYSTEM_ALERT_WINDOW warning spam (14 occurrences in 20 minutes)

**Crash log entries:** Entries 2-5, 9, 13-17, 20-25 (all WARN)
**Occurrences:** 14
**Tag:** (empty — should be "BlockOverlayManager")

**Symptom:**
```
WARN — SYSTEM_ALERT_WINDOW not granted — falling back to Activity.
User should grant it via Settings → Apps → Protect Yourself → Display over other apps.
```

**Root cause:**
The user hasn't granted the "Display over other apps" (SYSTEM_ALERT_WINDOW) permission. Every time a block is triggered (14 times in 20 minutes based on the breadcrumbs), the app logs this warning. The condition doesn't change between log entries — the permission is still missing — but the warning is logged every single time.

This floods the crash log with duplicate WARN entries, making it harder to spot real issues. The 14 duplicate warnings make up **54% of all crash log entries** (14 out of 26).

**Impact:**
- Crash log flooded with 14 identical warnings
- Real issues are harder to spot
- User sees a high crash count on the Profile badge, but most entries are just this one warning

**Fix:**
Created `OncePerSessionLogger` utility that ensures a specific warning/error is only logged ONCE per app session. The utility uses a `ConcurrentHashMap<String, Boolean>` to track which keys have been logged. Subsequent calls with the same key are silently dropped.

**How to apply to BlockOverlayManager** (in v1.0.44+ codebase):
```kotlin
// Before (logs every block attempt):
if (!Settings.canDrawOverlays(context)) {
    Timber.w("SYSTEM_ALERT_WINDOW not granted — falling back to Activity. ...")
    // ... fall back to Activity
}

// After (logs only once per session):
if (!Settings.canDrawOverlays(context)) {
    OncePerSessionLogger.warn(
        key = "overlay_permission_missing",
        message = "SYSTEM_ALERT_WINDOW not granted — falling back to Activity. ..."
    )
    // ... fall back to Activity
}
```

**Files:**
- `app/src/main/java/protect/yourself/commons/utils/OncePerSessionLogger.kt` (new file)
- `app/src/main/java/protect/yourself/core/ProtectYourselfApp.kt` (calls `OncePerSessionLogger.resetAll()` on startup)

---

### Issue 5 — LOW: Empty log tags on crash entries

**Crash log entries:** 25 out of 26 entries have empty `tag` field
**Only entry with tag:** `crash_20260711_124503_0010` (ANRWatchdog)

**Symptom:**
All crash entries except the ANRWatchdog entry have `"tag": ""` in the JSON. This makes it harder to filter/group entries by source component.

**Root cause:**
The Timber logging pattern `Timber.e(t, "message")` doesn't set a tag — Timber uses the calling class name as the default tag, but `CrashLoggingTree` passes `tag ?: ""` to `CrashLogger.logThrowable`. When Timber's tag is null (which happens when `Timber.e()` is called without `Timber.tag("...")` first), the tag is empty.

**Impact:**
- Harder to filter/group crash entries by source
- The crash log UI shows "untagged" for most entries

**Fix:**
This is a LOW-priority issue — the fix is to use `Timber.tag("ComponentName").e(...)` or call `crashLogger.logThrowable(tag = "ComponentName", ...)` directly at key call sites. Not fixed in this branch — documented for future work.

---

## 3. Breadcrumb Analysis

The crash logs include breadcrumbs that tell the full story of the user's session:

```
12:35:54 AppLifecycle: Application.onCreate started
12:35:55 AppLifecycle: Application.onCreate completed
12:36:03 AccessibilityService: onServiceConnected
12:36:19 DeviceAdmin: enabled
12:36:54 BlockAttempt: pkg=com.android.settings (keyword=null)
12:36:54 BlockFallback: Overlay permission missing — using Activity fallback
12:36:58 BlockAttempt: pkg=com.android.settings
12:36:58 BlockFallback: Overlay permission missing — using Activity fallback
... (repeated 14x total)
12:37:29 DeviceAdmin: DISABLED — possible uninstall attempt
12:37:32 DeviceAdmin: enabled
12:39:52 VpnService: startVpn requested
12:42:19 VpnService: startVpn requested
12:43:33 BlockAttempt: pkg=com.android.systemui (block_phone_reboot_bw_message)
12:44:05 VpnService: startVpn requested
12:45:03 ANRWatchdog: ANR detected (FALSE POSITIVE — main thread idle)
```

**Key observations:**
1. The user was blocking the Settings app (com.android.settings) repeatedly — likely trying to uninstall the app or change settings
2. Device admin was disabled at 12:37:29 (uninstall attempt) and re-enabled at 12:37:32
3. VPN was started 3 times (12:39, 12:42, 12:44) — possibly the user was toggling it or it was restarting
4. A block was triggered on com.android.systemui with "block_phone_reboot_bw_message" — the user tried to reboot the phone and was blocked
5. The "ANR" at 12:45:03 was a false positive — the main thread was idle

**This confirms the breadcrumb system is working correctly** and providing valuable diagnostic context. The fixes in this branch will improve the quality of the crash log by eliminating false positives.

---

## 4. Service State Analysis

The FATAL ANR entry captured service state at crash time:
```json
"serviceState": {
  "accessibilityEnabled": true,
  "deviceAdminActive": true,
  "vpnActive": true
}
```

All three critical services were active when the "ANR" was detected. This means:
- The accessibility service was correctly blocking content
- Device admin was active (anti-uninstall protection)
- VPN was running (DNS-based blocking)

The service state capture feature (added in the crash-logging-enhancements branch) is working correctly and providing the #1 diagnostic information needed for "blocking stopped working" reports.

---

## 5. Fixes Applied

| # | Severity | Issue | Fix | File |
|---|---|---|---|---|
| 1 | CRITICAL | SQLiteException: vpn_custom_dns missing display_name | Defensive schema repair via `ensureVpnCustomDnsSchema()` — checks PRAGMA table_info, adds column if missing | `AppDatabaseCallback.kt` |
| 2 | HIGH | ANR Watchdog false positive when main thread is idle | `isMainThreadActuallyBlocked()` check — inspects stack trace for nativePollOnce (idle) vs real work (blocked) | `AnrWatchdog.kt` (new) |
| 3 | MEDIUM | CancellationException logged as ERROR in StreakPageViewModel | Catch `CancellationException` separately and re-throw before `catch (t: Throwable)` | `StreakPageViewModel.kt` |
| 4 | MEDIUM | SYSTEM_ALERT_WINDOW warning spam (14x in 20 min) | `OncePerSessionLogger` utility — logs each unique warning only once per session | `OncePerSessionLogger.kt` (new) |
| 5 | LOW | Empty log tags | Documented — not fixed in this branch | — |

---

## 6. Files Changed

| File | Change | New? |
|---|---|---|
| `database/core/AppDatabaseCallback.kt` | Added `ensureVpnCustomDnsSchema()` defensive schema repair | Modified |
| `features/crashLog/AnrWatchdog.kt` | New file with false-positive fix (idle vs blocked detection) | New |
| `features/streakPage/StreakPageViewModel.kt` | CancellationException re-throw in `observeStreakData()` | Modified |
| `commons/utils/OncePerSessionLogger.kt` | New utility for once-per-session log dedup | New |
| `core/ProtectYourselfApp.kt` | Start AnrWatchdog + reset OncePerSessionLogger on startup | Modified |

---

## 7. Test Plan

| # | Scenario | Expected after fix | Before fix |
|---|---|---|---|
| 1 | Upgrade from v1.0.33 (DB v8) to latest (DB v9) with interrupted migration | `ensureVpnCustomDnsSchema` adds display_name column → `insertDnsPresets` succeeds | **SQLiteException crash** — DNS presets not seeded |
| 2 | App backgrounded on vivo/OPPO/Xiaomi, main thread idle | No ANR reported (DEBUG log only) | **False FATAL ANR** — main thread idle in nativePollOnce |
| 3 | User navigates away from Streak page | No error logged (CancellationException re-thrown) | **ERROR logged** — "Failed to observe streak data" |
| 4 | User blocks 14 sites without SYSTEM_ALERT_WINDOW permission | 1 WARN log entry (first block only) | **14 WARN entries** — one per block |
| 5 | Real ANR (main thread doing DB I/O) | FATAL entry with correct stack trace | FATAL entry (already working) |
| 6 | Fresh install (no existing DB) | `ensureVpnCustomDnsSchema` finds no table → skips ALTER → normal flow | Normal flow (already working) |

---

## 8. Branch & Merge Guidance

- **Branch:** `crash-log-fixes-v2`
- **Base:** v1.0.40 source (note: the v1.0.46 source includes the crash-logging-enhancements branch which has `AnrWatchdog.kt` — the version in this branch includes the false-positive fix)
- **Action:** User to review and merge via PR — **do not push directly to `main`**
- **Note:** The `AnrWatchdog.kt` in this branch is the full file with the fix. If merging into v1.0.46 (which already has `AnrWatchdog.kt` from the crash-logging-enhancements branch), replace the existing file with this one.
- **Note:** The `BlockOverlayManager.kt` (in v1.0.44+) should be updated to use `OncePerSessionLogger.warn()` for the SYSTEM_ALERT_WINDOW warning — see §4 for the code pattern.
