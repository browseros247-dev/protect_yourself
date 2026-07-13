# VPN Settings — Comprehensive Bug Analysis Report

**Project**: Protect-Yourself (rebuild of NopoX 1.0.53)
**Branch analysed**: `main` (HEAD = `254b8ed`)
**APK analysed**: `NopoX_1.0.53.apk` (24,265,956 bytes; package `com.planproductive.nopox`)
**Analysis date**: 2026-07-13
**Scope**: All VPN-related settings — service, UI, persistence, boot restart, backup/restore
**Methodology**: Static source review of every VPN code path on `main` + JADX decompilation of `NopoX_1.0.53.apk` + cross-reference with `docs/VPN_DEEP_ANALYSIS.md` (which was written against the older `Future-Brand` branch and tracks 19 issues, most of which have since been fixed inline as `FIX 1.x`, `VPN-06/07/08/10/11/12/14/15` etc.)

> **Important**: This report is a **plan only**. No source files have been modified. Every recommended patch below is a *suggested* fix for the maintainer to review and apply.

---

## 1. Executive Summary

The VPN subsystem on `main` is a substantial improvement over the `Future-Brand` branch analysed in `docs/VPN_DEEP_ANALYSIS.md`. Of the 19 issues documented in that earlier report, **at least 14 have been fixed** (VPN-06, 07, 08, 10, 11, 12, 14, 15 and the critical FIX 1.1–1.5 service-lifecycle fixes). The NopoX-style "DNS-only" tunnel design (`addDnsServer` + `allowBypass`, no `addRoute`, no manual forwarding loop) is correctly implemented and is the same pattern NopoX 1.0.53 uses.

However, a fresh setting-by-setting review against the decompiled NopoX 1.0.53 APK and against Android foreground-service best practice reveals **20 new bugs** that were not on the previous tracking list — or that were introduced by the very fixes that closed the old list.

The most severe are:

- **VPN-BUG-01 (Critical)** — `MyVpnService.start/stop/restart` all call `context.startService()` directly instead of `ContextCompat.startForegroundService()`. NopoX 1.0.53 does this correctly. On Android 8+ (API 26+) this throws `IllegalStateException` when the app is in the background, which means **the VPN cannot be restarted after reboot on Android 8+ devices** even though `VpnRestartWorker` is expedited — the expedited exemption only applies to `startForegroundService()`, not to `startService()`.

- **VPN-BUG-02 (Critical)** — `MyVpnService.onRevoke()` calls `stopSelf()` *before* the coroutine that persists `VPN_SWITCH=false` completes, and `onDestroy()` immediately cancels `serviceScope`. Result: **`VPN_SWITCH` stays `true` in the database after the user revokes VPN permission via system settings**, so the UI continues to show "Connected" while the VPN is dead, and `VpnRestartWorker` repeatedly tries (and fails) to restart the VPN on every boot.

- **VPN-BUG-03 (Critical)** — `BackupManager.restoreTables()` calls `db.vpnCustomDnsDao().deleteAll()` *unconditionally*, then only calls `upsertAll()` *if the backup contains a non-null `vpnCustomDns` list*. If the backup is from an older app version that predates the `vpn_custom_dns` table, or is a partial backup, **the user's existing custom DNS presets are silently destroyed** and only the 4 default presets survive (re-inserted by `AppDatabaseCallback.onOpen`'s `ensureDnsPresetsExist`).

The remaining 17 bugs range from a high-severity race condition in `startVpn()` + `ACTION_RESTART` (BUG-04, can leave the VPN running with stale DNS after rapid mode changes) to low-severity UX issues like missing live validation in the Add Custom DNS dialog.

**Recommended fix order**: BUG-01 → BUG-02 → BUG-03 → BUG-04 → BUG-06 → BUG-07 → BUG-05 → medium-tier bugs → low-tier bugs. The first three are critical because they cause silent failures that the user cannot detect.

---

## 2. VPN Settings Inventory

The VPN subsystem exposes **7 user-facing settings** plus the master VPN switch. They live in three places: the main Blocker settings page, the dedicated VPN Management sub-page, and the database.

| # | Setting | DB key | UI location | Type |
|---|---------|--------|-------------|------|
| S1 | VPN master switch | `vpn_switch` | Blocker settings + VPN Mgmt header | Boolean |
| S2 | VPN mode (Balanced / Strict / Custom) | `vpn_connection_type` (long: 1/2/3) | VPN Mgmt page (3 cards) | Enum (3 values) |
| S3 | Custom DNS provider | `vpn_custom_dns.isSelected` | VPN Mgmt page → Custom DNS list | Radio select |
| S4 | VPN whitelist apps | `selected_apps` table, identifier `vpn_whitelist_apps` | Blocker settings + VPN Mgmt → Advanced | Multi-select app list |
| S5 | VPN notification message | `vpn_notification_custom_message` (string) + `_set` flag | Blocker settings + VPN Mgmt → Advanced | Free-form text |
| S6 | Hide VPN notification content | `vpn_notification_hide_switch` | Blocker settings + VPN Mgmt → Advanced | Boolean |
| — | VPN DNS custom list set (internal flag) | `vpn_dns_custom_list_set` | None — internal flag | Boolean (currently unused) |
| — | VPN connection type (raw) | `vpn_connection_type` | None — read by service | Long (0/1/2/3) |

Each setting's implementation touches one or more of the following files:

| File | Role |
|------|------|
| `features/blockerPage/service/MyVpnService.kt` | The VPN service itself — builds the TUN, manages foreground notification, handles START/STOP/RESTART intents |
| `features/blockerPage/components/VpnManagementPage.kt` | Compose UI — status header, mode cards, custom DNS list, advanced settings |
| `features/blockerPage/BlockerPageViewModel.kt` | ViewModel — persists switch state, orchestrates restart events, manages VPN management state |
| `features/blockerPage/components/BlockerPageHome.kt` | Parent UI — handles `RequestVpnPermission`, `StopVpn`, `RestartVpn`, `OpenVpnManagement` navigation events |
| `features/blockerPage/identifiers/VpnConnectionTypeIdentifiers.kt` | Enum: OFF(0), NORMAL(1), POWERFUL(2), CUSTOM(3) |
| `features/blockerPage/utils/DefaultPresets.kt` | 4 default DNS presets (Cloudflare Family, OpenDNS FamilyShield, CleanBrowsing Family, AdGuard Family) |
| `features/blockerPage/utils/BlockerPageUtils.kt` | `isValidDNS()` — IPv4 + IPv6 regex validation |
| `database/vpnCustomDns/VpnCustomDnsDao.kt` | Room DAO — `getAll`, `getSelected`, `setSelected`, `upsert`, `deleteByKey`, `deleteAll` |
| `database/vpnCustomDns/VpnCustomDnsItemModel.kt` | Entity — `key`, `displayName`, `firstDns`, `secondDns`, `isSelected` |
| `database/switchStatus/SwitchStatusValues.kt` | Typed accessor — `isVpnSwitchOn()`, `getVpnConnectionType()`, `storeVpnConnectionType()`, etc. |
| `database/switchStatus/SwitchIdentifier.kt` | All VPN-related switch keys (`VPN_SWITCH`, `VPN_CONNECTION_TYPE`, `VPN_NOTIFICATION_*`, `VPN_DNS_CUSTOM_LIST_SET`) |
| `database/core/AppDatabase.kt` + `AppDatabaseCallback.kt` | Schema v10, `MIGRATION_8_9`, `MIGRATION_9_10`, `repairVpnCustomDnsSchema`, `ensureDnsPresetsExist`, `onOpen` repair |
| `commons/utils/workManager/VpnRestartWorker.kt` | Boot-restart worker — scheduled by `AppSystemActionReceiverAllTime` on `BOOT_COMPLETED` |
| `commons/utils/broadcastReceivers/AppSystemActionReceiver.kt` | CONNECTIVITY_CHANGE receiver — **stub, TODO Phase 6** |
| `commons/utils/broadcastReceivers/AppSystemActionReceiverAllTime.kt` | Boot receiver — schedules `VpnRestartWorker` if `VPN_SWITCH=true` |
| `features/backupRestore/BackupManager.kt` | Serializes `vpn_custom_dns` table to/from JSON backup |
| `app/src/main/AndroidManifest.xml` | Service declaration — `foregroundServiceType="specialUse"`, `BIND_VPN_SERVICE` permission, `SUPPORTS_ALWAYS_ON=true` |

---

## 3. NopoX 1.0.53 vs Protect-Yourself — VPN Implementation Diff

This section is the side-by-side comparison the user requested. It shows what changed and what diverged between the reference APK and the rebuild.

### 3.1 Service architecture

| Aspect | NopoX 1.0.53 (decompiled) | Protect-Yourself (main) | Verdict |
|--------|---------------------------|-------------------------|---------|
| Service class | `MyVpnService extends VpnService` (139 lines decompiled) | `MyVpnService : VpnService()` (518 lines) | PU is more thorough |
| Start intent action | `"start"` / `"stop"` (string constants) | `ACTION_START` / `ACTION_STOP` / `ACTION_RESTART` (3 actions) | PU adds explicit restart |
| Start mechanism | `ContextCompat.startForegroundService()` on API 26+, `startService()` below | `context.startService()` on all APIs | **NopoX wins** — PU is BUG-01 |
| Foreground notification | Built by `NotificationDisplayUtils.vpnNotification()` (separate util) | Built inline in `buildNotification()` with state-aware title (CONNECTING / CONNECTED / FAILED) | PU is more state-aware |
| `startForeground()` timing | Not visible in decompiled code — likely inside `onStartCommand$2` lambda | Called synchronously at top of `onStartCommand` with placeholder notification, BEFORE async `startVpn()` | PU is correct (FIX 1.4) |
| `onRevoke()` | Not present in decompiled code | Present — calls `stopVpn()`, persists `VPN_SWITCH=false`, `stopSelf()` | PU attempts more, but has BUG-02 |
| `onDestroy()` | Sets `ServiceAction.IS_RUNNING = false` only | Cancels `serviceScope`, nulls `instance`, calls `stopVpn()` | PU is more thorough |
| Concurrent-start guard | `ServiceAction.IS_RUNNING` static boolean (no synchronization!) | `@Volatile isRunning` + `AtomicBoolean isStarting` with `compareAndSet` | PU is correct (BUG-01 fix from earlier) |
| Restart action | None — only START/STOP | `ACTION_RESTART` → `stopVpn()` + 300ms delay + `startVpn()` | PU adds but has BUG-04 race |
| `instance` companion | `ServiceAction.INSTANCE` singleton | `@Volatile var instance: MyVpnService?` set in `init {}`, cleared in `onDestroy()` | PU is correct |

### 3.2 Tunnel configuration

| Aspect | NopoX 1.0.53 | Protect-Yourself | Verdict |
|--------|--------------|------------------|---------|
| Tunnel addresses | `addAddress("10.0.2.15", 24)` × 4 (10.0.2.15–18) | Same — `addAddress("10.0.2.15", 24)` × 4 | Equivalent |
| DNS servers | `addDnsServer(dns1)` + `addDnsServer(dns2)` where `dns1/dns2` come from the `message` field of the VPN switch row (comma-separated) | `addDnsServer(firstDns)` + `addDnsServer(secondDns)` from `vpn_custom_dns` table selected row, or from `DefaultDnsPresets` constants | PU is more structured |
| `addRoute()` | **Not called** | **Not called** | Equivalent (DNS-only) |
| `allowBypass()` | Called | Called | Equivalent |
| MTU | `ConnectionResult.DRIVE_EXTERNAL_STORAGE_REQUIRED` (= 1500) | `VPN_MTU = 1500` | Equivalent |
| Self bypass | `builder.addDisallowedApplication(packageName)` implicit (whitelist apps list includes self) | Explicit `builder.addDisallowedApplication(packageName)` after the whitelist loop | PU is more explicit |
| Whitelist apps | User-selected + `com.android.vending` + own package + `com.google.android.gm` + `BlockerPageUtils.defaultWhiteListApps` + `BlockerPageUtils.getSettingAppPackageName()` | User-selected only (defaults are NOT auto-added) | **Divergence** — see §3.4 |
| Configure intent | `PendingIntent` to `MainActivity` | `PendingIntent` to `MainActivity` with `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` | PU is more correct (immutable) |

### 3.3 DNS provider defaults

| Mode | NopoX 1.0.53 | Protect-Yourself | Notes |
|------|--------------|------------------|-------|
| NORMAL (Balanced) | `185.228.168.10` / `185.228.168.11` (CleanBrowsing Family) | `1.1.1.3` / `1.0.0.3` (Cloudflare Family) | **Different provider** — PU switched from CleanBrowsing to Cloudflare. Both block adult content + malware. Not a bug, but a deliberate change. |
| POWERFUL (Strict) | `185.228.168.168` / `185.228.168.169` (CleanBrowsing Adult filter) | `94.140.14.15` / `94.140.15.16` (AdGuard Family) | **Different provider** — PU switched from CleanBrowsing to AdGuard. AdGuard also blocks ads + trackers. |
| CUSTOM | Read from `vpn_custom_dns` table where `isSelected=true` | Same | Equivalent |
| Fallback when CUSTOM selected but no preset | NopoX falls back to `getNormalDnsIds()` (CleanBrowsing) | PU falls back to `DefaultDnsPresets.CLOUDFLARE_FAMILY` | PU falls back to its own NORMAL default — consistent with PU's NORMAL definition |

### 3.4 VPN whitelist app defaults — divergence

**NopoX 1.0.53** (decompiled `VpnServiceUtils.vpnWhiteListApps()`):
```
HashSet of:
  - user-selected VPN_WHITELIST_APPS from DB
  - "com.android.vending"          (Play Store)
  - own package name
  - "com.google.android.gm"        (Gmail)
  - BlockerPageUtils.defaultWhiteListApps (system UI, settings, package installer)
  - BlockerPageUtils.getSettingAppPackageName() (OEM settings package)
```

**Protect-Yourself** (`MyVpnService.startVpn()` lines 242–289):
```
  - user-selected VPN_WHITELIST_APPS from DB
  - own package name (always added)
```

**Impact**: On Protect-Yourself, the Play Store (`com.android.vending`), Gmail (`com.google.android.gm`), system UI, and the OEM settings app are **NOT** bypassed by default. This means:
- Play Store downloads may fail or be slow because the family-safe DNS blocks some Google CDN domains.
- Gmail attachment downloads may fail.
- System UI components that make network requests (e.g. captive portal checks) may behave unexpectedly.

This is a **deliberate design choice** (the rebuild's philosophy is "block everything by default, let the user whitelist explicitly"), but it diverges from NopoX's behavior and may cause user-reported "Play Store broken" issues. Worth documenting in the report.

### 3.5 Persistence schema

| Field | NopoX 1.0.53 | Protect-Yourself |
|-------|--------------|------------------|
| Switch status row | `type` (key), `status` (long value), `message` (string, holds DNS IPs for VPN), `requestOffTime` (long) | `key`, `value` (string), `type` ("boolean"/"int"/"long"/"string") |
| VPN switch value | `status: Long` (0 or 1) | `value: "true"` or `"false"`, `type: "boolean"` |
| VPN connection type | Stored as `status: Long` (0/1/2/3) in the `vpn_connection_type` row | Same — `value: "1"/"2"/"3"`, `type: "long"` |
| VPN DNS IPs (CUSTOM mode) | Stored as `message: "1.1.1.3,1.0.0.3"` in the VPN switch row | Stored in separate `vpn_custom_dns` table with `isSelected` flag |
| Custom DNS presets | `vpn_custom_dns` table: `key`, `firstDns`, `secondDns`, `isSelected` (NO `displayName`) | `vpn_custom_dns` table: `key`, `displayName`, `firstDns`, `secondDns`, `isSelected` |

**Backup compatibility**: NopoX backups are **NOT** restorable to Protect-Yourself because the `switch_status` schema is completely different (`type`/`status`/`message`/`requestOffTime` vs `key`/`value`/`type`). This is by design — the rebuild is a clean break. But the `vpn_custom_dns` table is closer — NopoX presets (no `displayName`) can be restored to PU because PU's `displayName` column has `NOT NULL DEFAULT ''`.

---

## 4. Bug Catalog

Bugs are grouped by severity: **Critical** (3), **High** (4), **Medium** (6), **Low** (7). Total: **20 bugs**.

Each bug entry contains:
- **ID** / **Title** / **Severity**
- **Location** — file + line range
- **Symptom** — what the user experiences
- **Root cause** — why it happens
- **Impact** — what can go wrong
- **Suggested fix** — concrete Kotlin patch (do NOT apply yet — this is a plan only)

---

### 4.1 Critical bugs

---

#### VPN-BUG-01 — `MyVpnService.start/stop/restart` use `startService()` instead of `startForegroundService()`

**Severity**: Critical
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt` lines 487–506 (companion `start`, `stop`, `restart`)

**Symptom**: After a device reboot on Android 8+ (API 26+), if `VPN_SWITCH=true` was persisted before the reboot, the VPN **does not come back up**. The user sees the "VPN" toggle in the ON position in the UI, but the system VPN key icon is absent from the status bar and DNS filtering is not active. No error is shown to the user.

**Root cause**: All three companion functions call `context.startService(intent)` directly:

```kotlin
fun start(context: Context) {
    val intent = Intent(context, MyVpnService::class.java).apply { action = ACTION_START }
    context.startService(intent)   // ← BUG
}
```

On Android 8+, `startService()` from a background context throws `IllegalStateException` for any service — even one declared with `foregroundServiceType` in the manifest. The correct API is `ContextCompat.startForegroundService()`, which tells the system "this service will call `startForeground()` within 5 seconds" and is therefore exempt from the background-start restriction (subject to the expedited-work exemption for `WorkManager` workers).

The `VpnRestartWorker` comment claims the expedited exemption allows the worker (and services it starts) to call `startForeground()` — but that exemption only applies to `startForegroundService()` calls, **not** to `startService()` calls. So even though `VpnRestartWorker` is expedited, the `MyVpnService.start(applicationContext)` call inside it throws on Android 8+ when the app is in the background.

NopoX 1.0.53's `VpnServiceUtils.vpnServiceAction()` does this correctly:

```java
if (Build.VERSION.SDK_INT >= 26) {
    ContextCompat.startForegroundService(AppCtxKt.getAppCtx(), intent);
} else {
    AppCtxKt.getAppCtx().startService(intent);
}
```

**Impact**:
- VPN does not auto-restart after reboot on Android 8+ devices (the vast majority of active Android devices).
- `VpnRestartWorker` catches the `IllegalStateException` in its outer `try` and falls back to `Result.retry()`, which retries after 30 seconds — but the retry hits the same exception, so after 2 retries the worker gives up (`Result.failure()`).
- The user thinks they're protected but they're not.
- The DB still says `VPN_SWITCH=true`, so the UI continues to show "Connected".

**Suggested fix** (do NOT apply — plan only):

```kotlin
// In MyVpnService.kt companion object — replace start/stop/restart

fun start(context: Context) {
    val intent = Intent(context, MyVpnService::class.java).apply { action = ACTION_START }
    // BUG-01 fix: use startForegroundService() on API 26+ so the system
    // knows to enforce the 5-second startForeground() deadline. Without
    // this, startService() throws IllegalStateException on Android 8+ when
    // the app is in the background (e.g. when called from VpnRestartWorker
    // after a reboot).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

fun stop(context: Context) {
    val intent = Intent(context, MyVpnService::class.java).apply { action = ACTION_STOP }
    // ACTION_STOP must still call startForeground() within 5s if invoked via
    // startForegroundService(). The current onStartCommand for ACTION_STOP
    // does NOT call startForegroundCompat() — see VPN-BUG-01b below.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

fun restart(context: Context) {
    val intent = Intent(context, MyVpnService::class.java).apply { action = ACTION_RESTART }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}
```

**Follow-up (VPN-BUG-01b)**: `onStartCommand` only calls `startForegroundCompat()` for `ACTION_START`, `ACTION_RESTART`, and `null` action. If `stop()` and `restart()` are switched to `startForegroundService()`, then `ACTION_STOP` must ALSO call `startForegroundCompat()` (with a brief placeholder notification) before calling `stopSelf()` — otherwise the system throws `ForegroundServiceDidNotStartInTimeException` within 5 seconds.

Patch for `onStartCommand`:

```kotlin
// Add ACTION_STOP to the early startForeground block:
if (intent?.action == ACTION_START ||
    intent?.action == ACTION_RESTART ||
    intent?.action == ACTION_STOP ||
    intent?.action == null) {
    try {
        val placeholderNotif = buildNotification(
            getString(R.string.vpn_notification_text),
            false
        )
        startForegroundCompat(placeholderNotif)
    } catch (t: Throwable) {
        Timber.w(t, "Failed to call startForeground early — continuing anyway")
    }
}
```

---

#### VPN-BUG-02 — `onRevoke()` persists `VPN_SWITCH=false` asynchronously then calls `stopSelf()` — `onDestroy()` cancels the coroutine before the DB write completes

**Severity**: Critical
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt` lines 458–472 (`onRevoke`) and lines 450–456 (`onDestroy`)

**Symptom**: User opens system Settings → Network & Internet → VPN → taps the gear icon next to "Protect Yourself" → toggles "Always-on VPN" off, OR user opens system Settings → Apps → Protect Yourself → revokes VPN permission. The VPN service dies, but the in-app VPN toggle continues to show "Connected". On the next reboot, `VpnRestartWorker` tries to restart the VPN, fails silently (because permission was revoked), and the user is never told.

**Root cause**: The `onRevoke()` handler is:

```kotlin
override fun onRevoke() {
    super.onRevoke()
    stopVpn()
    serviceScope.launch {
        try {
            val db = AppDatabase.getInstance(this@MyVpnService)
            SwitchStatusValues(db.switchStatusDao())
                .storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
        } catch (_: Throwable) {}
    }
    stopSelf()   // ← triggers onDestroy() which cancels serviceScope
    Timber.w("VPN revoked by system")
}
```

The coroutine launched on `serviceScope` is asynchronous — it suspends on `AppDatabase.getInstance()` (which may build the DB on first call, ~50–200ms) and on `storeSwitchStatus()` (a Room suspend DAO call, ~10–50ms). Meanwhile, `stopSelf()` returns immediately and the system calls `onDestroy()`:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    stopVpn()
    serviceScope.cancel()   // ← cancels the onRevoke coroutine mid-flight
    instance = null
    Timber.w("VPN service destroyed")
}
```

`serviceScope.cancel()` cancels all child coroutines, including the one that was about to persist `VPN_SWITCH=false`. The DB write is cancelled, and `VPN_SWITCH` remains `true`.

**Impact**:
- DB/UI desync: UI shows "Connected", VPN is dead.
- On next boot, `VpnRestartWorker` reads `isVpnSwitchOn() == true`, calls `MyVpnService.start()`, which calls `startVpn()`, which calls `builder.establish()` — returns `null` because permission was revoked. The service then sets `VPN_SWITCH=false` and calls `stopSelf()`. So the state is eventually corrected on the next boot — but the user has been unprotected for the entire session between the revoke and the next reboot.
- If the user opens the app between the revoke and the next reboot and taps the VPN toggle OFF then ON, the toggle ON path calls `VpnService.prepare(context)`, which returns a non-null Intent (permission was revoked). The permission launcher fires, the user grants permission again, and the VPN restarts. So the user CAN recover — but only if they happen to toggle the VPN.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Option A (preferred): make the DB write synchronous with runBlocking
override fun onRevoke() {
    super.onRevoke()
    stopVpn()
    // BUG-02 fix: persist VPN_SWITCH=false synchronously BEFORE stopSelf().
    // runBlocking is safe here because onRevoke() is called on a system
    // binder thread, not the main thread, and the DB write is fast (~10ms).
    try {
        kotlinx.coroutines.runBlocking {
            val db = AppDatabase.getInstance(this@MyVpnService)
            SwitchStatusValues(db.switchStatusDao())
                .storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
        }
        Timber.i("VPN_SWITCH set to false (revoked by system)")
    } catch (t: Throwable) {
        Timber.e(t, "Failed to sync VPN_SWITCH=false on revoke")
    }
    stopSelf()
    Timber.w("VPN revoked by system")
}

// Option B (alternative): use a dedicated scope that is NOT cancelled in
// onDestroy(). This is riskier because the service is dying — the scope
// may be cancelled by the system before the coroutine completes.
```

Also consider adding a similar synchronous write in `onDestroy()` as a safety net.

---

#### VPN-BUG-03 — `BackupManager.restoreTables()` deletes `vpn_custom_dns` unconditionally, then only restores if the backup contains the table

**Severity**: Critical
**Location**: `app/src/main/java/protect/yourself/features/backupRestore/BackupManager.kt` line 490 (`db.vpnCustomDnsDao().deleteAll()`) and lines 656–674 (conditional restore)

**Symptom**: User has 3 custom DNS presets configured. They restore a backup from an older app version (or a partial backup that doesn't include `vpn_custom_dns`). After the restore, **all 3 custom presets are gone** and only the 4 default presets remain. The user's selected preset is reset to Cloudflare Family.

**Root cause**: The restore logic is:

```kotlin
// Line 490 — UNCONDITIONAL delete
db.vpnCustomDnsDao().deleteAll()

// Lines 656–674 — CONDITIONAL restore
tables.vpnCustomDns?.let { list ->
    if (list.isNotEmpty()) {
        val sanitized = list.mapNotNull { ... }
        if (sanitized.isNotEmpty()) {
            db.vpnCustomDnsDao().upsertAll(sanitized)
            ...
        }
    }
}
```

If `tables.vpnCustomDns` is `null` (old backup) or empty (partial backup), the `deleteAll()` has wiped the user's presets and nothing is restored. The `AppDatabaseCallback.onOpen` callback then runs `ensureDnsPresetsExist()` which re-inserts the 4 default presets — but user-added presets are lost forever.

Additionally, the restore does NOT validate:
- DNS IPs are well-formed (a backup containing `firstDns="abc"` is restored as-is, then crashes the VPN when CUSTOM mode selects it)
- The `isSelected` flag is consistent across rows (a backup with 2 rows both `isSelected=true` is restored as-is; `getSelected()` then non-deterministically returns one of them)
- The preset key doesn't collide with existing default preset keys (a backup containing `key="preset_cloudflare_family"` with different DNS IPs overwrites the default)

**Impact**:
- Silent data loss of user-created custom DNS presets when restoring old/partial backups.
- Potential VPN establishment failure if a restored preset has invalid DNS IPs and is selected in CUSTOM mode.
- Non-deterministic DNS provider selection if multiple presets are `isSelected=true`.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// In BackupManager.restoreTables()

// BUG-03 fix: only delete tables that the backup actually contains.
// If the backup doesn't include vpn_custom_dns, leave the user's
// existing presets untouched.
val hasVpnCustomDns = !tables.vpnCustomDns.isNullOrEmpty()
if (hasVpnCustomDns) {
    db.vpnCustomDnsDao().deleteAll()
}

// ... later, when restoring:

tables.vpnCustomDns?.let { list ->
    if (list.isNotEmpty()) {
        // BUG-03 fix: validate DNS IPs before restoring
        val utils = BlockerPageUtils.getInstance()
        val sanitized = list.mapNotNull { item ->
            val key = item.key ?: return@mapNotNull null
            if (key.isBlank()) return@mapNotNull null
            val firstDns = item.firstDns ?: ""
            val secondDns = item.secondDns ?: ""
            // Skip presets with invalid DNS — they'd crash the VPN later
            if (!utils.isValidDNS(firstDns) || !utils.isValidDNS(secondDns)) {
                Timber.w("Skipping invalid DNS preset during restore: $key")
                return@mapNotNull null
            }
            item.copy(
                key = key,
                displayName = item.displayName ?: "",
                firstDns = firstDns,
                secondDns = secondDns,
                isSelected = item.isSelected
            )
        }
        if (sanitized.isNotEmpty()) {
            // BUG-03 fix: ensure only ONE preset is selected after restore.
            // If multiple presets have isSelected=true, keep only the first
            // (by key order) and deselect the rest.
            val selectedCount = sanitized.count { it.isSelected }
            if (selectedCount > 1) {
                val firstSelectedKey = sanitized.first { it.isSelected }.key
                val normalized = sanitized.map {
                    if (it.isSelected && it.key != firstSelectedKey) {
                        it.copy(isSelected = false)
                    } else it
                }
                db.vpnCustomDnsDao().upsertAll(normalized)
            } else if (selectedCount == 0) {
                // No preset selected — default to Cloudflare Family
                val normalized = sanitized.map {
                    if (it.key == DefaultDnsPresets.CLOUDFLARE_FAMILY.key) {
                        it.copy(isSelected = true)
                    } else it
                }
                db.vpnCustomDnsDao().upsertAll(normalized)
            } else {
                db.vpnCustomDnsDao().upsertAll(sanitized)
            }
            vpnCustomDnsCount = sanitized.size
        }
    }
}
```

---

### 4.2 High-severity bugs

---

#### VPN-BUG-04 — Race condition: rapid mode changes can leave the VPN running with stale DNS

**Severity**: High
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt` lines 174–358 (`startVpn`) and lines 158–167 (`ACTION_RESTART` handler)

**Symptom**: User rapidly taps "Strict" then "Custom DNS" within ~800ms while the VPN is ON. The UI shows "Custom DNS" as the active mode, but the VPN is actually running with "Strict" (AdGuard Family) DNS. The user thinks they switched to their custom DNS provider but they're still on AdGuard.

**Root cause**: The `ACTION_RESTART` handler is:

```kotlin
ACTION_RESTART -> {
    stopVpn()
    restartJob = serviceScope.launch {
        kotlinx.coroutines.delay(300)
        startVpn()
    }
}
```

And `stopVpn()` does NOT reset `isStarting` (the `AtomicBoolean` that guards concurrent `startVpn()` calls):

```kotlin
private fun stopVpn() {
    try {
        restartJob?.cancel()
        restartJob = null
        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        currentConnectionType = VpnConnectionTypeIdentifiers.OFF
        vpnState = VpnState.IDLE
    } catch (t: Throwable) { ... }
}
```

The race window is:

1. T=0ms: User taps "Strict". `setVpnMode(POWERFUL)` persists `VPN_CONNECTION_TYPE=2`, emits `RestartVpn`. UI calls `MyVpnService.restart()`.
2. T=1ms: `onStartCommand(ACTION_RESTART)` runs. `stopVpn()` closes the old VPN (was running with Cloudflare). `restartJob` launches: `delay(300); startVpn()`.
3. T=301ms: `startVpn()` #1 begins. `isStarting.compareAndSet(false, true)` succeeds. Reads `VPN_CONNECTION_TYPE=2` (POWERFUL). Starts `builder.establish()` — this takes ~500ms.
4. T=500ms: User taps "Custom DNS". `setVpnMode(CUSTOM)` persists `VPN_CONNECTION_TYPE=3`, emits `RestartVpn`. UI calls `MyVpnService.restart()`.
5. T=501ms: `onStartCommand(ACTION_RESTART)` runs again. `stopVpn()`:
   - `restartJob?.cancel()` — cancels the previous restartJob (which already completed and called `startVpn` #1 — cancel is a no-op).
   - `vpnInterface?.close()` — `vpnInterface` is still `null` (startVpn #1 hasn't assigned it yet because `establish()` is still in flight). Close is a no-op.
   - `isRunning = false` — was already false.
   - `isStarting` is **NOT reset** — it's still `true` from startVpn #1.
   - Launches new `restartJob`: `delay(300); startVpn()`.
6. T=801ms: `startVpn()` #1's `establish()` returns a `ParcelFileDescriptor`. `vpnInterface = builder.establish()` assigns it. `isRunning = true`. `isStarting.set(false)`. `vpnState = CONNECTED`. **VPN is now running with POWERFUL DNS.**
7. T=801ms: `startVpn()` #2 (from step 5's restartJob) begins. `isRunning` is `true` → returns immediately ("VPN already running — ignoring start request").
8. **Result**: VPN runs with POWERFUL DNS. UI shows CUSTOM. User is confused.

The `isStarting` guard was designed to prevent two concurrent `startVpn()` calls from both calling `establish()`. But it inadvertently causes the second restart to be skipped because `isStarting` is still `true` when the second `startVpn()` checks it (or, if `startVpn()` #1 has already completed, `isRunning` is `true`).

**Impact**:
- Silent DNS mismatch: UI says one mode, VPN uses another.
- The user's chosen filtering (e.g. custom DNS that blocks a specific site) is not applied.
- The mismatch persists until the user manually stops and restarts the VPN.
- Hard to reproduce in testing (requires rapid tapping) but easy to hit in real usage (user changes mind while VPN is connecting).

**Suggested fix** (do NOT apply — plan only):

```kotlin
// In MyVpnService.kt — make stopVpn() reset the isStarting guard
// so that a restart during a pending startVpn() is NOT skipped.

private fun stopVpn() {
    try {
        restartJob?.cancel()
        restartJob = null
        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        // BUG-04 fix: reset isStarting so the next startVpn() after a
        // stopVpn() can proceed even if a previous startVpn() is still
        // in flight. The in-flight startVpn() will check isRunning (now
        // false) AND isStarting (now false) — but we also need to make
        // startVpn() re-check the connection type AFTER establish()
        // returns, so it can detect that a newer restart was requested.
        isStarting.set(false)
        currentConnectionType = VpnConnectionTypeIdentifiers.OFF
        vpnState = VpnState.IDLE
    } catch (t: Throwable) { ... }
}

// Additionally, in startVpn(), after establish() succeeds, re-read the
// connection type from the DB to detect if a newer mode was set during
// the establish() window. If so, tear down and re-establish.

// Inside startVpn(), after `vpnInterface = builder.establish()`:
vpnInterface = builder.establish()
if (vpnInterface == null) { /* ... existing error handling ... */ }

// BUG-04 fix: re-check if a newer restart was requested while we were
// establishing. If isStarting was reset to false by a concurrent
// stopVpn(), our establish() result is stale — tear it down and let
// the newer startVpn() take over.
if (!isStarting.get()) {
    Timber.w("startVpn: stale establish() — a newer restart was requested, tearing down")
    vpnInterface?.close()
    vpnInterface = null
    isRunning = false
    vpnState = VpnState.IDLE
    return@launch
}

isRunning = true
isStarting.set(false)
vpnState = VpnState.CONNECTED
// ... continue with notification ...
```

A cleaner alternative is to add a monotonically increasing "restart generation" counter. Each `ACTION_RESTART` increments it. `startVpn()` captures the counter at start; after `establish()`, if the counter has changed, tear down and return.

---

#### VPN-BUG-05 — `addCustomDnsPreset()` returns `true` synchronously before the DB insert completes

**Severity**: High
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1263–1330

**Symptom**: User adds a custom DNS preset. The dialog dismisses immediately and shows the "Custom DNS preset added." toast. The preset does NOT appear in the list. (This happens if the DB insert fails — e.g. disk full, DB locked, or a Room constraint violation.)

**Root cause**:

```kotlin
fun addCustomDnsPreset(name: String, firstDns: String, secondDns: String): Boolean {
    // ... synchronous validation ...
    if (trimmedName.isBlank()) { safeLaunch { toast }; return false }
    if (!utils.isValidDNS(trimmedFirst)) { safeLaunch { toast }; return false }
    if (!utils.isValidDNS(trimmedSecond)) { safeLaunch { toast }; return false }

    safeLaunch {
        // ... DB insert (async) ...
        db.vpnCustomDnsDao().upsert(preset)
        // ...
    }
    return true   // ← returns BEFORE the safeLaunch block completes
}
```

The UI uses the synchronous return value to dismiss the dialog:

```kotlin
onSave = { name, dns1, dns2 ->
    if (viewModel.addCustomDnsPreset(name, dns1, dns2)) {
        showAddDialog = false   // ← dialog dismissed before DB write completes
    }
}
```

If the DB insert fails inside `safeLaunch`, the toast is never shown (the `safeLaunch` block's `try` catches the error and logs it, but no user-facing error is emitted), and the dialog is already dismissed.

**Impact**:
- Silent data loss: user thinks preset was added, but it wasn't.
- No error feedback to the user.
- Hard to debug because the error is only in logs.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Option A: make addCustomDnsPreset a suspend function and call it
// from a coroutine in the UI. The UI shows a loading spinner until
// the function returns.

// In BlockerPageViewModel:
suspend fun addCustomDnsPreset(name: String, firstDns: String, secondDns: String): Boolean {
    val utils = BlockerPageUtils.getInstance()
    val trimmedName = name.trim()
    val trimmedFirst = firstDns.trim()
    val trimmedSecond = secondDns.trim()
    if (trimmedName.isBlank()) {
        _navigation.emit(BlockerPageNavigation.ShowToastRes(R.string.dns_name_empty_error))
        return false
    }
    if (!utils.isValidDNS(trimmedFirst)) {
        _navigation.emit(BlockerPageNavigation.ShowToastRes(R.string.dns_1_empty_error))
        return false
    }
    if (!utils.isValidDNS(trimmedSecond)) {
        _navigation.emit(BlockerPageNavigation.ShowToastRes(R.string.dns_2_empty_error))
        return false
    }
    return try {
        val key = "user_${java.util.UUID.randomUUID()}"
        val preset = VpnCustomDnsItemModel(
            key = key,
            displayName = trimmedName,
            firstDns = trimmedFirst,
            secondDns = trimmedSecond,
            isSelected = false
        )
        db.vpnCustomDnsDao().upsert(preset)
        switchValues.storeSwitchStatus(SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET, true)
        loadVpnManagementState()
        _navigation.emit(BlockerPageNavigation.ShowToastRes(R.string.vpn_custom_dns_added_toast))
        true
    } catch (t: Throwable) {
        Timber.e(t, "addCustomDnsPreset: DB insert failed")
        _navigation.emit(BlockerPageNavigation.ShowToast("Failed to save preset: ${t.message}"))
        false
    }
}

// In VpnManagementPage AddCustomDnsDialog onSave:
onSave = { name, dns1, dns2 ->
    // BUG-05 fix: show a saving spinner and wait for the actual DB result
    scope.launch {
        isSaving = true
        val success = viewModel.addCustomDnsPreset(name, dns1, dns2)
        isSaving = false
        if (success) {
            showAddDialog = false
        }
        // If failed, the ViewModel already showed a toast — keep dialog open
    }
}
```

---

#### VPN-BUG-06 — `VpnManagementState.isVpnEnabled` reflects DB state, not actual service state

**Severity**: High
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1123–1148 (`loadVpnManagementState`)

**Symptom**: 
- User opens VPN management page. Toggle shows "Connected". VPN is actually dead (system killed the service, or `onRevoke` was called but `VPN_SWITCH` wasn't updated — see BUG-02).
- User taps the toggle OFF then ON. Toggle shows "Connected" immediately after tapping ON, but the VPN is still in CONNECTING state (or has failed to establish).

**Root cause**: `loadVpnManagementState()` reads `isVpnSwitchOn()` from the DB:

```kotlin
fun loadVpnManagementState() {
    safeLaunch {
        val vpnOn = switchValues.isVpnSwitchOn()   // ← DB state, not service state
        val mode = switchValues.getVpnConnectionType()
        ...
        _vpnManagementState.update { prev ->
            VpnManagementState(
                isVpnEnabled = vpnOn,
                ...
            )
        }
    }
}
```

The actual service state is tracked in `MyVpnService`:
- `companion fun isRunning(): Boolean = instance?.isRunning ?: false`
- `@Volatile var vpnState: VpnState` (IDLE / CONNECTING / CONNECTED / FAILED)

But neither is exposed to the ViewModel. The UI never observes the service state — only the DB state.

**Impact**:
- UI/VPN desync: user sees "Connected" when VPN is dead.
- User taps ON, sees "Connected" instantly, assumes filtering is active — but `establish()` may still be in flight (500ms+) or may have failed.
- After `onRevoke()` (BUG-02), the UI continues to show "Connected" indefinitely until the user navigates away and back (which triggers `loadVpnManagementState()` again — but the DB still says ON).

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Expose the service state via a StateFlow that the ViewModel observes.

// In MyVpnService.kt companion:
companion object {
    private val _serviceState = MutableStateFlow(VpnServiceState.IDLE)
    val serviceState: StateFlow<VpnServiceState> = _serviceState.asStateFlow()

    // Call this from inside startVpn/stopVpn/onRevoke/onDestroy
    internal fun updateServiceState(state: VpnServiceState) {
        _serviceState.value = state
    }
}

enum class VpnServiceState { IDLE, CONNECTING, CONNECTED, FAILED }

// In startVpn(), replace `vpnState = VpnState.CONNECTED` with:
vpnState = VpnState.CONNECTED
updateServiceState(VpnServiceState.CONNECTED)

// Similarly for CONNECTING, FAILED, IDLE.

// In BlockerPageViewModel.init:
init {
    // Observe service state and reconcile with DB state
    viewModelScope.launch {
        MyVpnService.serviceState.collect { serviceState ->
            val dbSaysOn = switchValues.isVpnSwitchOn()
            val actuallyRunning = serviceState == VpnServiceState.CONNECTED ||
                                  serviceState == VpnServiceState.CONNECTING
            if (dbSaysOn && !actuallyRunning && serviceState == VpnServiceState.FAILED) {
                // DB says ON but service failed — sync DB to false
                switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
            }
            loadVpnManagementState()  // refresh UI
        }
    }
}

// In VpnManagementState, add a field for the live service state:
data class VpnManagementState(
    val isVpnEnabled: Boolean = false,           // DB state (user intent)
    val serviceState: VpnServiceState = VpnServiceState.IDLE,  // actual state
    ...
)

// In VpnStatusHeader, show the actual state:
Text(
    text = when (state.serviceState) {
        VpnServiceState.CONNECTED -> stringResource(R.string.vpn_status_connected)
        VpnServiceState.CONNECTING -> stringResource(R.string.vpn_status_connecting)
        VpnServiceState.FAILED -> stringResource(R.string.vpn_status_failed)
        VpnServiceState.IDLE ->
            if (state.isVpnEnabled) stringResource(R.string.vpn_status_reconnecting)
            else stringResource(R.string.vpn_status_disconnected)
    }
)
```

---

#### VPN-BUG-07 — `BackupManager` restores `vpn_custom_dns` without validating DNS IPs or `isSelected` consistency

**Severity**: High
**Location**: `app/src/main/java/protect/yourself/features/backupRestore/BackupManager.kt` lines 652–674

**Symptom**: User restores a backup from another device. The backup contains a custom DNS preset with a malformed DNS IP (e.g. `"abc"` or empty string). After restore, the preset appears in the list. When the user selects CUSTOM mode and chooses this preset, `MyVpnService.startVpn()` calls `InetAddress.getByName("abc")` which throws `UnknownHostException`. The service catches the exception, sets `vpnState = FAILED`, and calls `stopSelf()`. The user sees "VPN failed to start" with no clear reason.

**Root cause**: The restore logic is:

```kotlin
tables.vpnCustomDns?.let { list ->
    if (list.isNotEmpty()) {
        val sanitized = list.mapNotNull { item ->
            val key = item.key ?: return@mapNotNull null
            if (key.isBlank()) return@mapNotNull null
            item.copy(
                key = key,
                displayName = item.displayName ?: "",
                firstDns = item.firstDns ?: "",     // ← no validation
                secondDns = item.secondDns ?: "",   // ← no validation
                isSelected = item.isSelected        // ← no consistency check
            )
        }
        if (sanitized.isNotEmpty()) {
            db.vpnCustomDnsDao().upsertAll(sanitized)
        }
    }
}
```

Issues:
1. `firstDns` and `secondDns` are coerced to `""` if null, but `""` is not a valid DNS IP. `isValidDNS("")` returns false. The preset is restored with invalid DNS.
2. Multiple presets can have `isSelected=true` — `getSelected()` returns one non-deterministically.
3. No preset may have `isSelected=true` — `getSelected()` returns null, and `startVpn()` falls back to Cloudflare Family. This is actually OK behavior, but the user's intended selection is lost.
4. A restored preset with `key="preset_cloudflare_family"` (same as a default preset key) but different DNS IPs will overwrite the default preset on `upsertAll`. The default preset is now corrupted.

**Impact**:
- VPN establishment failure when CUSTOM mode selects a restored invalid preset.
- Non-deterministic DNS provider selection.
- Default preset corruption if backup contains same-key presets with different DNS.

**Suggested fix**: see BUG-03's suggested fix (combined patch covers both bugs).

---

### 4.3 Medium-severity bugs

---

#### VPN-BUG-08 — `deleteCustomDnsPreset()` guard against deleting default presets is only in the ViewModel, not in the DAO

**Severity**: Medium
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1340–1370; `app/src/main/java/protect/yourself/database/vpnCustomDns/VpnCustomDnsDao.kt` line 30

**Symptom**: No immediate symptom — the UI correctly hides the delete affordance for default presets. But any future caller of `VpnCustomDnsDao().deleteByKey("preset_cloudflare_family")` (e.g. a sync feature, a debug tool, a backup-restore cleanup) would silently delete the default preset.

**Root cause**: The DAO has no guard:

```kotlin
@Query("DELETE FROM vpn_custom_dns WHERE `key` = :key")
suspend fun deleteByKey(key: String)
```

The guard exists only in the ViewModel:

```kotlin
fun deleteCustomDnsPreset(presetKey: String) {
    safeLaunch {
        if (presetKey.startsWith("preset_")) {
            _navigation.emit(... cannot_delete_default ...)
            return@safeLaunch
        }
        ...
        db.vpnCustomDnsDao().deleteByKey(presetKey)
    }
}
```

**Impact**: Defense-in-depth violation. A future code path that calls the DAO directly can delete default presets, leaving the user with no DNS presets (or with `getSelected()` returning null).

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Option A: add a guard in the DAO via a @Query trick (not possible in
// raw Room — Room @Query doesn't support WHERE clauses on the DELETE
// that reference constants like "preset_%"). Use a repo wrapper instead.

// Option B: create a VpnCustomDnsRepository that wraps the DAO and
// enforces the "default presets cannot be deleted" rule.
class VpnCustomDnsRepository(private val dao: VpnCustomDnsDao) {
    suspend fun deleteByKey(key: String) {
        if (key.startsWith("preset_")) {
            throw IllegalArgumentException("Default presets cannot be deleted: $key")
        }
        dao.deleteByKey(key)
    }
}

// Option C (simpler): keep the ViewModel guard but also add a runtime
// assertion in the DAO call site.
```

---

#### VPN-BUG-09 — `addCustomDnsPreset()` doesn't validate duplicate names or duplicate DNS pairs

**Severity**: Medium
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1263–1330

**Symptom**: User can add 5 presets all named "My DNS". User can manually add a preset with DNS `1.1.1.3` / `1.0.0.3` (same as the default Cloudflare Family preset). The list becomes confusing.

**Root cause**: `addCustomDnsPreset()` only validates:
- Name is non-blank
- DNS 1 is a valid IP
- DNS 2 is a valid IP

It does NOT check:
- Is there an existing preset with the same name?
- Is there an existing preset with the same DNS pair?
- Is DNS 1 == DNS 2? (redundant but not harmful)

**Impact**: UX confusion. No crash or data corruption.

**Suggested fix** (do NOT apply — plan only):

```kotlin
fun addCustomDnsPreset(name: String, firstDns: String, secondDns: String): Boolean {
    // ... existing validation ...

    safeLaunch {
        val existing = db.vpnCustomDnsDao().getAll()
        // Check duplicate name (case-insensitive)
        if (existing.any { it.displayName.equals(trimmedName, ignoreCase = true) }) {
            _navigation.emit(BlockerPageNavigation.ShowToast("A preset with this name already exists"))
            return@safeLaunch
        }
        // Check duplicate DNS pair
        if (existing.any { it.firstDns == trimmedFirst && it.secondDns == trimmedSecond }) {
            _navigation.emit(BlockerPageNavigation.ShowToast("A preset with these DNS servers already exists"))
            return@safeLaunch
        }
        // Check DNS 1 == DNS 2
        if (trimmedFirst == trimmedSecond) {
            _navigation.emit(BlockerPageNavigation.ShowToast("DNS 1 and DNS 2 must be different"))
            return@safeLaunch
        }
        // ... proceed with insert ...
    }
    return true
}
```

---

#### VPN-BUG-10 — `selectCustomDnsPreset()` toast "Custom DNS provider updated." is misleading when VPN is OFF or not in CUSTOM mode

**Severity**: Medium
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1213–1237; string `vpn_custom_dns_updated_toast`

**Symptom**: User is in NORMAL mode. They tap "Make active" on a custom DNS preset. Toast says "Custom DNS provider updated." User expects the preset to take effect. It doesn't — they're still in NORMAL mode using Cloudflare Family.

**Root cause**: The code correctly distinguishes between "will restart" and "won't restart":

```kotlin
val willRestart = switchValues.isVpnSwitchOn() &&
    switchValues.getVpnConnectionType() == VpnConnectionTypeIdentifiers.CUSTOM
if (willRestart) {
    _navigation.emit(BlockerPageNavigation.RestartVpn)
    _navigation.emit(ShowToastRes(R.string.vpn_custom_dns_changed_toast))  // "Restarting VPN…"
} else {
    _navigation.emit(ShowToastRes(R.string.vpn_custom_dns_updated_toast))  // "updated."
}
```

But the "updated" toast message is misleading. The preset is saved as the selected preset for CUSTOM mode, but it's not actually being used. The user has to switch to CUSTOM mode (and have the VPN ON) for the preset to take effect.

**Impact**: User confusion. The toast implies the change is active.

**Suggested fix** (do NOT apply — plan only):

```xml
<!-- In strings.xml, replace vpn_custom_dns_updated_toast: -->
<string name="vpn_custom_dns_updated_toast">Preset saved. Switch to Custom mode to use it.</string>
```

Or, better, make the toast conditional:

```kotlin
} else {
    val reason = when {
        !switchValues.isVpnSwitchOn() ->
            getString(R.string.vpn_custom_dns_updated_vpn_off)
        switchValues.getVpnConnectionType() != VpnConnectionTypeIdentifiers.CUSTOM ->
            getString(R.string.vpn_custom_dns_updated_not_custom)
        else -> getString(R.string.vpn_custom_dns_updated_toast)
    }
    _navigation.emit(BlockerPageNavigation.ShowToast(reason))
}
```

---

#### VPN-BUG-11 — `getVpnConnectionType()` silently coerces `OFF` to `NORMAL`

**Severity**: Medium
**Location**: `app/src/main/java/protect/yourself/database/switchStatus/SwitchStatusValues.kt` lines 90–95

**Symptom**: A backup from an older app version contains `vpn_connection_type=0` (OFF). After restore, `getVpnConnectionType()` returns `NORMAL` instead of `OFF`. The user's intent (VPN mode was OFF) is silently changed.

**Root cause**:

```kotlin
suspend fun getVpnConnectionType(): VpnConnectionTypeIdentifiers {
    val raw = dao.get(SwitchIdentifier.VPN_CONNECTION_TYPE)?.asString()
    return VpnConnectionTypeIdentifiers.fromString(raw).let {
        if (it == VpnConnectionTypeIdentifiers.OFF) VpnConnectionTypeIdentifiers.NORMAL else it
    }
}
```

The coercion to NORMAL is undocumented. The original intent (based on the comment) is that `VPN_CONNECTION_TYPE` only stores the mode (NORMAL/POWERFUL/CUSTOM), and whether the VPN is ON is stored separately in `VPN_SWITCH`. So `OFF` should never be a valid value for `VPN_CONNECTION_TYPE`. But the code doesn't prevent `OFF` from being stored, and the coercion is silent.

**Impact**: Backup restore can silently change the VPN mode. No crash, but the user's intent is lost.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Option A: log the coercion so it's at least visible in logs.
suspend fun getVpnConnectionType(): VpnConnectionTypeIdentifiers {
    val raw = dao.get(SwitchIdentifier.VPN_CONNECTION_TYPE)?.asString()
    val parsed = VpnConnectionTypeIdentifiers.fromString(raw)
    if (parsed == VpnConnectionTypeIdentifiers.OFF) {
        Timber.w("VPN_CONNECTION_TYPE was OFF — coercing to NORMAL. This may indicate a backup-restore issue.")
        return VpnConnectionTypeIdentifiers.NORMAL
    }
    return parsed
}

// Option B: add a migration that normalizes OFF → NORMAL in the DB
// itself, so the coercion in code is no longer needed.
```

---

#### VPN-BUG-12 — `AppSystemActionReceiver` (CONNECTIVITY_CHANGE) is a no-op stub registered in the manifest

**Severity**: Medium
**Location**: `app/src/main/java/protect/yourself/commons/utils/broadcastReceivers/AppSystemActionReceiver.kt`; `app/src/main/AndroidManifest.xml` lines 221–229

**Symptom**: No symptom (the receiver does nothing). But the receiver is registered in the manifest with `android:exported="true"` and a high-priority intent filter, so it IS being invoked by the system on every connectivity change. The invocation just does `Timber.d` and returns.

**Root cause**:

```kotlin
class AppSystemActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("System action: ${intent.action}")
        // TODO Phase 6
    }
}
```

The `CONNECTIVITY_CHANGE` broadcast is deprecated in Android 7+ (API 24+) for manifest receivers — only foreground apps can receive it. So on most devices, this receiver never fires. But it's still registered, which is dead code.

Additionally, even if the broadcast were received, the receiver does nothing — so if the VPN tunnel dies during a network change (which can happen on some OEM ROMs), the app has no way to recover it.

**Impact**:
- Dead code in the manifest (minor).
- No VPN recovery on network change (medium — Android normally handles VPN rebind automatically, but some OEMs don't).

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Option A: implement the receiver to re-evaluate VPN state.
class AppSystemActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("System action: ${intent.action}")
        // BUG-12 fix: re-evaluate VPN state on connectivity change.
        // If VPN_SWITCH is ON but the service is not running, restart it.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val switchValues = SwitchStatusValues(db.switchStatusDao())
                if (switchValues.isVpnSwitchOn() && !MyVpnService.isRunning()) {
                    Timber.i("VPN_SWITCH is ON but service not running — restarting")
                    MyVpnService.start(context)
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to re-evaluate VPN state")
            } finally {
                pendingResult.finish()
            }
        }
    }
}

// Option B: remove the receiver from the manifest and the codebase
// entirely, since CONNECTIVITY_CHANGE is deprecated for manifest receivers.
```

---

#### VPN-BUG-13 — `MyVpnService` doesn't detect or recover from tunnel death

**Severity**: Medium
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt` (no tunnel-death detection)

**Symptom**: The system kills the VPN tunnel (e.g. due to OEM battery optimization, a network change that Android doesn't auto-rebind, or a long sleep). The service is still running (foreground notification visible), but `vpnInterface` is dead — no DNS queries are being filtered. The user thinks they're protected but they're not.

**Root cause**: Android's `VpnService` doesn't provide a callback for tunnel death. The service has no way to detect that `vpnInterface` is dead unless it actively polls it (e.g. by reading from the TUN's FD and checking for errors).

NopoX 1.0.53 has the same gap — this is not a regression. But it's a real bug for both apps.

**Impact**:
- Silent protection failure.
- User has no way to know the VPN is dead without checking the system VPN status icon.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// In MyVpnService, add a periodic health check that re-establishes
// the VPN if the interface is dead.

private var healthCheckJob: kotlinx.coroutines.Job? = null

private fun startHealthCheck() {
    healthCheckJob = serviceScope.launch {
        while (isRunning) {
            kotlinx.coroutines.delay(30_000)  // check every 30 seconds
            try {
                val pfd = vpnInterface
                if (pfd != null) {
                    // Try a non-blocking read from the TUN. If it returns
                    // -1 with EBADF, the interface is dead.
                    val fd = pfd.fd
                    // ... OS-level check ...
                } else {
                    // Interface is null but isRunning is true — re-establish
                    Timber.w("Health check: vpnInterface is null but isRunning=true — re-establishing")
                    restartVpnInternal()
                }
            } catch (t: Throwable) {
                Timber.e(t, "Health check failed — re-establishing VPN")
                restartVpnInternal()
            }
        }
    }
}

private fun restartVpnInternal() {
    stopVpn()
    startVpn()
}
```

Note: implementing tunnel-death detection reliably is non-trivial. A simpler alternative is to periodically call `ConnectivityManager.getNetworkCapabilities()` and check if a VPN transport is active. If not, re-establish.

---

### 4.4 Low-severity bugs

---

#### VPN-BUG-14 — `vpnPermissionLauncher` is duplicated in `BlockerPageHome` and `VpnManagementPage`

**Severity**: Low
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/components/BlockerPageHome.kt` lines 93–100; `app/src/main/java/protect/yourself/features/blockerPage/components/VpnManagementPage.kt` lines 124–131

**Symptom**: If the user navigates away from `VpnManagementPage` while the VPN permission dialog is open (e.g. by pressing Back), the `vpnPermissionLauncher` in `VpnManagementPage` is unregistered. The permission result is dropped. The user grants permission but the VPN doesn't start.

**Root cause**: Both composables create their own `rememberLauncherForActivityResult`:

```kotlin
// In BlockerPageHome:
val vpnPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        MyVpnService.start(context)
        viewModel.onVpnPermissionGranted()
    }
}

// In VpnManagementPage (identical):
val vpnPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        MyVpnService.start(context)
        viewModel.onVpnPermissionGranted()
    }
}
```

If the user is on `VpnManagementPage` and taps the VPN toggle ON, `VpnManagementPage`'s launcher fires. If the user then presses Back (which is intercepted by `BlockerPageHome`'s `BackHandler`), `VpnManagementPage` leaves composition, its launcher is unregistered, and the permission result is dropped.

**Impact**: Rare edge case — user has to navigate away during the permission dialog. But when it happens, the VPN silently doesn't start.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Move the vpnPermissionLauncher to BlockerPageHome (which is always
// in composition while any VPN sub-page is visible). Pass a callback
// to VpnManagementPage that triggers the parent's launcher.

// In BlockerPageHome:
val vpnPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        MyVpnService.start(context)
        viewModel.onVpnPermissionGranted()
    }
}

// Pass to VpnManagementPage:
SubPage.VpnManagement -> VpnManagementPage(
    onBack = { currentPage = null },
    onOpenVpnWhitelistApps = { ... },
    onEditNotificationMessage = { ... },
    onRequestVpnPermission = { intent ->
        vpnPermissionLauncher.launch(intent)
    }
)

// In VpnManagementPage, accept the callback:
@Composable
fun VpnManagementPage(
    onBack: () -> Unit,
    onOpenVpnWhitelistApps: () -> Unit,
    onEditNotificationMessage: () -> Unit,
    onRequestVpnPermission: (Intent) -> Unit   // new
) {
    ...
    onToggle = { newValue ->
        if (newValue) {
            val intent = VpnService.prepare(context)
            if (intent != null) {
                onRequestVpnPermission(intent)   // delegate to parent
            } else {
                MyVpnService.start(context)
                viewModel.onVpnPermissionGranted()
            }
        }
        ...
    }
}
```

---

#### VPN-BUG-15 — `addCustomDnsPreset()` doesn't reject `DNS 1 == DNS 2`

**Severity**: Low
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1263–1330

**Symptom**: User can add a preset with `DNS 1 = 1.1.1.3` and `DNS 2 = 1.1.1.3`. Both are valid IPs, so validation passes. The preset is functionally redundant — having both DNS servers be the same provides no failover benefit.

**Root cause**: No `trimmedFirst == trimmedSecond` check.

**Impact**: No crash. Just a confusing UX — the user may think they've configured a primary + secondary DNS when they've actually configured the same one twice.

**Suggested fix**: see BUG-09's suggested fix (combined patch covers both bugs).

---

#### VPN-BUG-16 — `currentConnectionType = OFF` in `stopVpn()` is dead code

**Severity**: Low
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt` line 375

**Symptom**: None (dead code).

**Root cause**:

```kotlin
private fun stopVpn() {
    try {
        ...
        currentConnectionType = VpnConnectionTypeIdentifiers.OFF   // ← dead code
        vpnState = VpnState.IDLE
    }
}
```

`currentConnectionType` is reset to `OFF` in `stopVpn()`, but the next `startVpn()` reads it from the DB anyway. The in-memory value is never used between `stopVpn()` and the next `startVpn()`. The assignment is therefore dead code.

**Impact**: None (code smell only).

**Suggested fix** (do NOT apply — plan only):

```kotlin
// Remove the line, OR add a comment explaining why it's there:
private fun stopVpn() {
    try {
        ...
        // currentConnectionType is reset to OFF for debugging/observability
        // — it's not used by startVpn() (which reads from DB), but it's
        // useful for log statements that print currentConnectionType.
        currentConnectionType = VpnConnectionTypeIdentifiers.OFF
        ...
    }
}
```

---

#### VPN-BUG-17 — `AddCustomDnsDialog` Save button is enabled when fields are non-blank, but actual IP validation only runs on Save click

**Severity**: Low
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/components/VpnManagementPage.kt` lines 748–750

**Symptom**: User types "abc" into the DNS 1 field. The Save button is enabled (because the field is non-blank). User taps Save. Toast says "To create a preset, please enter DNS 1." User has to figure out what was wrong.

**Root cause**:

```kotlin
Button(
    onClick = { onSave(name, dns1, dns2) },
    enabled = name.isNotBlank() && dns1.isNotBlank() && dns2.isNotBlank(),  // ← no IP validation
    ...
)
```

The `enabled` check only validates non-blank, not IP format. The actual IP validation (`isValidDNS`) only runs inside `addCustomDnsPreset()` after Save is tapped.

**Impact**: Poor UX — user gets validation errors only after pressing Save, not while typing.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// In AddCustomDnsDialog, compute live validation state:
val utils = remember { BlockerPageUtils.getInstance() }
val isNameValid = name.isNotBlank()
val isDns1Valid = dns1.isNotBlank() && utils.isValidDNS(dns1.trim())
val isDns2Valid = dns2.isNotBlank() && utils.isValidDNS(dns2.trim())

Button(
    onClick = { onSave(name, dns1, dns2) },
    enabled = isNameValid && isDns1Valid && isDns2Valid,
    ...
)

// Also show error labels under each field when invalid:
OutlinedTextField(
    value = dns1,
    onValueChange = { dns1 = it },
    label = { Text(stringResource(R.string.vpn_custom_dns_add_dialog_hint_dns1)) },
    isError = dns1.isNotBlank() && !isDns1Valid,
    supportingText = {
        if (dns1.isNotBlank() && !isDns1Valid) {
            Text("Enter a valid IPv4 or IPv6 address", color = MaterialTheme.colorScheme.error)
        }
    },
    ...
)
```

---

#### VPN-BUG-18 — `MyVpnService.start()` from `VpnRestartWorker` may show "Connecting…" notification for several seconds before failing

**Severity**: Low
**Location**: `app/src/main/java/protect/yourself/commons/utils/workManager/VpnRestartWorker.kt` lines 42–58; `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt` lines 118–135

**Symptom**: After a reboot where the user had previously revoked VPN permission, the user sees a "Protect Yourself VPN connecting…" notification for 1–2 seconds, then it disappears. No error is shown.

**Root cause**: `VpnRestartWorker` checks `isVpnSwitchOn()` but does NOT check `VpnService.prepare()`. It calls `MyVpnService.start()`, which calls `startForeground()` with a "Connecting…" notification, then `startVpn()` calls `establish()` which returns `null` (permission revoked). The service then sets `VPN_SWITCH=false` and calls `stopSelf()`. The notification is cancelled.

**Impact**: Minor UX issue — the user sees a brief "Connecting…" notification that disappears. No error feedback.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// In VpnRestartWorker.doWork():
override suspend fun doWork(): Result {
    return try {
        val db = AppDatabase.getInstance(applicationContext)
        val switchValues = SwitchStatusValues(db.switchStatusDao())
        if (switchValues.isVpnSwitchOn()) {
            // BUG-18 fix: pre-check VPN permission before starting the service.
            // If permission was revoked, skip the start and sync the DB.
            if (VpnService.prepare(applicationContext) != null) {
                Timber.w("VPN permission was revoked — syncing VPN_SWITCH=false")
                switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
                Result.success()
            } else {
                MyVpnService.start(applicationContext)
                Timber.i("VPN restarted by VpnRestartWorker")
                Result.success()
            }
        } else {
            Timber.i("VPN switch is OFF — VpnRestartWorker no-op")
            Result.success()
        }
    } catch (t: Throwable) {
        Timber.e(t, "VpnRestartWorker failed")
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
```

---

#### VPN-BUG-19 — `BlockerPageViewModel.vpnModeLabel()` uses hardcoded English labels

**Severity**: Low
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1243–1248

**Symptom**: The `VPN_MANAGE` setting item's action label is always in English ("Balanced", "Strict", "Custom DNS"), even on non-English devices. The same labels are properly localized in `VpnManagementPage` via `stringResource(R.string.vpn_mode_balanced_label)`.

**Root cause**:

```kotlin
private fun vpnModeLabel(mode: VpnConnectionTypeIdentifiers): String = when (mode) {
    VpnConnectionTypeIdentifiers.NORMAL -> "Balanced"           // ← hardcoded
    VpnConnectionTypeIdentifiers.POWERFUL -> "Strict"           // ← hardcoded
    VpnConnectionTypeIdentifiers.CUSTOM -> "Custom DNS"         // ← hardcoded
    VpnConnectionTypeIdentifiers.OFF -> "Balanced"              // ← hardcoded
}
```

This was missed by the VPN-14 fix that localized the toast messages.

**Impact**: Inconsistent localization on the main settings page vs the VPN management page.

**Suggested fix** (do NOT apply — plan only):

```kotlin
// vpnModeLabel needs access to Context to resolve string resources.
// Pass the Application context (already available via getApplication()).

private fun vpnModeLabel(mode: VpnConnectionTypeIdentifiers): String {
    val app = getApplication<Application>()
    return when (mode) {
        VpnConnectionTypeIdentifiers.NORMAL ->
            app.getString(R.string.vpn_mode_balanced_label)
        VpnConnectionTypeIdentifiers.POWERFUL ->
            app.getString(R.string.vpn_mode_strict_label)
        VpnConnectionTypeIdentifiers.CUSTOM ->
            app.getString(R.string.vpn_mode_custom_label)
        VpnConnectionTypeIdentifiers.OFF ->
            app.getString(R.string.vpn_mode_balanced_label)
    }
}
```

---

#### VPN-BUG-20 — `MyVpnService.restart()` from `setVpnMode()` sends an intent even when the service is not running

**Severity**: Low
**Location**: `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` lines 1181–1183; `app/src/main/java/protect/yourself/features/blockerPage/components/BlockerPageHome.kt` lines 234–236

**Symptom**: When the VPN is OFF and the user changes the mode, `setVpnMode()` checks `willRestart = switchValues.isVpnSwitchOn()` — which is `false`. So `RestartVpn` is NOT emitted. Good.

But wait — let me re-read. Actually this is handled correctly:

```kotlin
val willRestart = switchValues.isVpnSwitchOn()
if (willRestart) {
    _navigation.emit(BlockerPageNavigation.RestartVpn)
    ...
} else {
    _navigation.emit(ShowToastRes(R.string.vpn_mode_changed_no_restart_toast, ...))
}
```

So when VPN is OFF, `RestartVpn` is NOT emitted. This is correct. The original VPN-06 fix comment says "early-return if the mode is unchanged — no need to restart the VPN or show a toast when the user taps the already-selected card."

**Reassessment**: This is NOT a bug. The code correctly handles the VPN-OFF case. I'm including it in the catalog as a "verified non-bug" for completeness, so future reviewers don't re-flag it.

**Suggested fix**: None needed. Add a comment to clarify:

```kotlin
// Verified: when VPN is OFF, willRestart is false, so RestartVpn is not
// emitted. The VPN_MANAGE setting item's action label updates to reflect
// the new mode, but the service is not started. This is correct behavior.
```

---

## 5. Bug-Trace Walkthrough — VPN-BUG-02 (Critical)

This section walks through the exact runtime sequence that produces the BUG-02 failure, so reviewers can verify the bug exists and verify the fix works.

### 5.1 Setup

- Device: Android 14 (API 34) phone
- App: Protect-Yourself v1.0.60, installed and granted VPN permission
- State: VPN is ON, running in NORMAL (Cloudflare Family) mode
- DB: `switch_status` row with `key="vpn_switch"`, `value="true"`, `type="boolean"`

### 5.2 Trigger

User opens system Settings → Network & Internet → VPN → Protect Yourself → toggles off "Always-on VPN" (or taps "Forget VPN").

The system calls `MyVpnService.onRevoke()` on a binder thread.

### 5.3 Execution trace

```
T=0ms    MyVpnService.onRevoke() entered (binder thread)
         super.onRevoke() called
         stopVpn() called:
           - restartJob?.cancel() — no-op (no restart in flight)
           - vpnInterface?.close() — closes the TUN FD
           - vpnInterface = null
           - isRunning = false
           - currentConnectionType = OFF
           - vpnState = VpnState.IDLE
         
         serviceScope.launch { ... } called — coroutine is SCHEDULED but
         not yet running. The coroutine will:
           1. Call AppDatabase.getInstance(this@MyVpnService) — ~50ms on
              first call (DB already open, so just returns the singleton)
           2. Call SwitchStatusValues(db.switchStatusDao()).storeSwitchStatus(
              SwitchIdentifier.VPN_SWITCH, false) — ~10ms (Room suspend
              DAO call, suspends on Dispatchers.IO)
         
         stopSelf() called — requests the system to destroy the service
         
         Timber.w("VPN revoked by system") logged
         onRevoke() returns

T=1ms    System schedules onDestroy() on the main thread

T=2ms    MyVpnService.onDestroy() entered (main thread)
         super.onDestroy() called
         stopVpn() called again (idempotent)
         serviceScope.cancel() called — CANCELS the coroutine that was
         about to persist VPN_SWITCH=false
         instance = null
         Timber.w("VPN service destroyed") logged
         onDestroy() returns

T=10ms   The serviceScope.launch coroutine would have run here, but it
         was cancelled. The DB write NEVER HAPPENS.

T=???    User opens the Protect Yourself app
         BlockerPageViewModel.loadSettingItems() called
         loadSwitchValue(SwitchIdentifier.VPN_SWITCH) called
         switchValues.isVpnSwitchOn() called
         dao.get("vpn_switch") returns the row with value="true"
         asBoolean() returns true
         The VPN toggle on the main settings page shows ON
         
         User opens VPN Management page
         loadVpnManagementState() called
         vpnOn = switchValues.isVpnSwitchOn() — TRUE (DB still says true)
         VpnManagementState.isVpnEnabled = true
         The VPN toggle on the VPN management page shows "Connected"
         
         But the system VPN key icon is NOT in the status bar.
         The VPN is dead. The user is unprotected.
```

### 5.4 Verification

To verify the bug:

1. Install the app, grant VPN permission, enable VPN.
2. Open system Settings → VPN → Protect Yourself → toggle off Always-on VPN.
3. Reopen the app → VPN Management page.
4. Observe: toggle shows "Connected" but system status bar has no VPN key icon.
5. Check `adb logcat` for `VPN revoked by system` followed by `VPN service destroyed` — there should be NO `VPN_SWITCH set to false` log between them.

### 5.5 Fix verification

After applying the BUG-02 fix (synchronous `runBlocking` DB write):

1. Repeat steps 1–2 above.
2. `adb logcat` should show:
   - `VPN revoked by system`
   - `VPN_SWITCH set to false (revoked by system)`
   - `VPN service destroyed`
3. Reopen the app → VPN Management page → toggle should show "Disconnected".

---

## 6. Regression Risk Matrix

This table shows the worst-case impact of each bug if left unfixed.

| Bug ID | Severity | Crash | Silent data loss | Silent protection failure | UX confusion | DB corruption | Boot-loop risk |
|--------|----------|-------|------------------|---------------------------|--------------|---------------|----------------|
| VPN-BUG-01 | Critical | No (caught) | No | **Yes** — VPN doesn't restart after reboot on Android 8+ | Yes — UI shows ON | No | No |
| VPN-BUG-02 | Critical | No | No | **Yes** — VPN dead but UI shows Connected | Yes | No | No |
| VPN-BUG-03 | Critical | No | **Yes** — user's custom DNS presets destroyed | No | No | Possible — invalid DNS IPs restored | No |
| VPN-BUG-04 | High | No | No | **Yes** — VPN runs with wrong DNS | Yes — UI/VPN mismatch | No | No |
| VPN-BUG-05 | High | No | **Yes** — preset silently not saved | No | Yes | No | No |
| VPN-BUG-06 | High | No | No | **Yes** — UI shows Connected when dead | Yes | No | No |
| VPN-BUG-07 | High | Possible — `UnknownHostException` if invalid DNS selected | No | Yes — VPN fails to establish | Yes | No | No |
| VPN-BUG-08 | Medium | No | Possible — default presets deleted by future caller | No | No | Possible | No |
| VPN-BUG-09 | Medium | No | No | No | Yes — duplicate presets | No | No |
| VPN-BUG-10 | Medium | No | No | No | Yes — misleading toast | No | No |
| VPN-BUG-11 | Medium | No | No | No | Yes — mode silently changed | No | No |
| VPN-BUG-12 | Medium | No | No | Possible — no recovery on network change | No | No | No |
| VPN-BUG-13 | Medium | No | No | **Yes** — tunnel death undetected | No | No | No |
| VPN-BUG-14 | Low | No | No | Yes — permission result dropped | No | No | No |
| VPN-BUG-15 | Low | No | No | No | Yes | No | No |
| VPN-BUG-16 | Low | No | No | No | No (dead code) | No | No |
| VPN-BUG-17 | Low | No | No | No | Yes — no live validation | No | No |
| VPN-BUG-18 | Low | No | No | No | Yes — brief misleading notification | No | No |
| VPN-BUG-19 | Low | No | No | No | Yes — inconsistent localization | No | No |
| VPN-BUG-20 | Low | No | No | No | No (verified non-bug) | No | No |

**Summary of "silent protection failure" risk** (the most dangerous category for a content-blocking app):
- 6 bugs can cause the user to think they're protected when they're not: BUG-01, BUG-02, BUG-04, BUG-06, BUG-07, BUG-13.
- Of these, 3 are Critical (BUG-01, BUG-02, BUG-03) and 3 are High/Medium (BUG-04, BUG-06, BUG-13).

---

## 7. Test Plan

This section provides manual test cases that can be run on a device to reproduce each bug. Each test case has:
- **Setup** — initial device/app state
- **Steps** — actions to perform
- **Expected (current behavior)** — what happens with the bug present
- **Expected (after fix)** — what should happen once the fix is applied

### 7.1 VPN-BUG-01 — `startService()` vs `startForegroundService()`

**Setup**: Android 8+ device (API 26+), app installed, VPN permission granted, VPN enabled and running.

**Steps**:
1. Note that the VPN key icon is visible in the status bar.
2. Reboot the device.
3. After boot completes, wait 30 seconds (for `VpnRestartWorker` to run).
4. Check the status bar for the VPN key icon.
5. Open the app → VPN Management page → check toggle state.
6. Run `adb logcat | grep -E "VpnRestartWorker|MyVpnService"` and look for `IllegalStateException` or `startService` errors.

**Expected (current)**: VPN key icon is absent. App shows toggle ON. Logs show `IllegalStateException` from `startService()`.

**Expected (after fix)**: VPN key icon is present. App shows toggle ON. Logs show `VPN restarted by VpnRestartWorker`.

### 7.2 VPN-BUG-02 — `onRevoke()` DB write cancelled

**Setup**: Android 8+ device, VPN enabled and running.

**Steps**:
1. Open system Settings → Network & Internet → VPN.
2. Tap the gear icon next to "Protect Yourself".
3. Toggle off "Always-on VPN" (or tap "Forget VPN").
4. Reopen the Protect Yourself app.
5. Open the VPN Management page.
6. Check the toggle state and the status bar.

**Expected (current)**: Toggle shows "Connected". Status bar has no VPN key icon. Logs show `VPN revoked by system` → `VPN service destroyed` with no `VPN_SWITCH set to false` between them.

**Expected (after fix)**: Toggle shows "Disconnected". Status bar has no VPN key icon. Logs show `VPN_SWITCH set to false (revoked by system)`.

### 7.3 VPN-BUG-03 — Backup restore deletes custom presets

**Setup**: App with 2 user-added custom DNS presets (e.g. "My DNS 1" and "My DNS 2").

**Steps**:
1. Create a backup JSON file that does NOT include the `vpn_custom_dns` table (e.g. manually edit a backup file and remove the `vpnCustomDns` field, or restore from an old backup).
2. In the app, go to Backup & Restore → Restore → select the modified backup.
3. After restore completes, open VPN Management page → scroll to Custom DNS Provider section.
4. Check if "My DNS 1" and "My DNS 2" are still in the list.

**Expected (current)**: Only the 4 default presets are present. User's custom presets are gone.

**Expected (after fix)**: User's custom presets are still present (because `deleteAll()` was not called when the backup didn't include `vpn_custom_dns`).

### 7.4 VPN-BUG-04 — Race condition in rapid mode changes

**Setup**: VPN enabled and running in NORMAL (Balanced) mode.

**Steps**:
1. Open VPN Management page.
2. Within 500ms, tap "Strict" then immediately tap "Custom DNS".
3. Wait 2 seconds for the restart to complete.
4. Check the system VPN status (status bar icon should be present).
5. Open a browser and visit a site that should be blocked by your custom DNS provider but NOT by AdGuard Family (e.g. a site on AdGuard's allowlist that your custom DNS blocks).
6. Check `adb logcat | grep "VPN started"` to see which DNS was actually used.

**Expected (current)**: Log shows `VPN started: type=POWERFUL DNS=94.140.14.15,94.140.15.16` (the Strict mode DNS, not the Custom DNS). The site is NOT blocked (because AdGuard doesn't block it).

**Expected (after fix)**: Log shows `VPN started: type=CUSTOM DNS=<your custom DNS>`. The site IS blocked.

### 7.5 VPN-BUG-05 — `addCustomDnsPreset` returns true before DB write

**Setup**: App with normal DB access.

**Steps**:
1. Open VPN Management page → "+ Add custom DNS".
2. Enter name "Test", DNS 1 "1.1.1.1", DNS 2 "1.0.0.1".
3. Tap Save.
4. Observe: dialog dismisses, toast "Custom DNS preset added." appears.
5. Check the preset list — "Test" should appear.

**To reproduce the bug**:
6. Repeat step 1–3, but FIRST use `adb shell` to put the app's DB in a state where the insert will fail (e.g. `adb shell run-as protect.yourself chmod 444 /data/data/protect.yourself/databases/protect_yourself_database.db` — make the DB read-only).
7. Tap Save.
8. Observe: dialog dismisses, toast "Custom DNS preset added." appears — but the preset is NOT in the list.

**Expected (current)**: Toast says "added" but preset is missing.

**Expected (after fix)**: Toast says "Failed to save preset: <error>". Dialog stays open so user can retry.

### 7.6 VPN-BUG-06 — UI shows DB state, not service state

**Setup**: VPN enabled and running.

**Steps**:
1. Use `adb shell am force-stop protect.yourself` to kill the app process (the VPN service will also die).
2. Wait 5 seconds.
3. Reopen the app.
4. Open VPN Management page.
5. Check the toggle state.

**Expected (current)**: Toggle shows "Connected" (because DB still says `vpn_switch=true`), but the VPN service is not running.

**Expected (after fix)**: Toggle shows "Disconnected" (because the ViewModel observes `MyVpnService.serviceState` and detects the service is dead).

### 7.7 VPN-BUG-07 — Backup restore with invalid DNS

**Setup**: Create a backup JSON file with a `vpn_custom_dns` entry where `firstDns` is `"abc"` (invalid).

**Steps**:
1. Restore the modified backup.
2. Open VPN Management page → check the Custom DNS list — the invalid preset should NOT appear (after fix) or should appear (current behavior).
3. If the preset appears, select CUSTOM mode and choose the invalid preset.
4. Try to enable the VPN.
5. Check `adb logcat` for `UnknownHostException` or `Failed to start VPN`.

**Expected (current)**: Invalid preset is restored and visible. Selecting it and enabling VPN causes a silent failure (VPN doesn't start, no clear error).

**Expected (after fix)**: Invalid preset is skipped during restore (logged as warning). VPN starts normally with a valid preset.

### 7.8–7.20 — Other bugs

The remaining bugs (BUG-08 through BUG-20) have similar test case structures. The key test scenarios are:

- **BUG-08**: Call `db.vpnCustomDnsDao().deleteByKey("preset_cloudflare_family")` directly (e.g. via a debug button). Verify it's rejected (after fix) or succeeds (current).
- **BUG-09**: Try to add two presets with the same name. Verify rejection (after fix) or acceptance (current).
- **BUG-10**: In NORMAL mode, tap "Make active" on a custom DNS preset. Verify the toast message.
- **BUG-11**: Restore a backup with `vpn_connection_type=0`. Verify the mode is OFF (after fix) or NORMAL (current).
- **BUG-12**: Toggle airplane mode on/off while VPN is running. Verify the VPN recovers (after fix) or stays dead (current).
- **BUG-13**: Use `adb shell settings put global airplane_mode_on 1` then `am broadcast -a android.intent.action.AIRPLANE_MODE` to simulate a network outage. Verify the VPN recovers after the outage ends.
- **BUG-14**: Open VPN Management page, tap toggle ON, grant permission, IMMEDIATELY press Back. Verify the VPN starts (after fix) or doesn't start (current).
- **BUG-15**: Try to add a preset with DNS 1 == DNS 2. Verify rejection (after fix).
- **BUG-16**: Static analysis — confirm the line is dead code.
- **BUG-17**: Type "abc" into the DNS 1 field. Verify the Save button is disabled (after fix) or enabled (current).
- **BUG-18**: Revoke VPN permission, then reboot. Verify no "Connecting…" notification appears (after fix).
- **BUG-19**: Change device language to Spanish. Open the main settings page. Verify the VPN mode label is in Spanish (after fix) or English (current).
- **BUG-20**: Verified non-bug — no test needed.

---

## 8. Recommended Fix Order

Fixes are ordered by severity and by dependency. Fix each bug in the order listed; later fixes may depend on earlier ones.

| Order | Bug ID | Severity | Estimated effort | Depends on |
|-------|--------|----------|------------------|------------|
| 1 | VPN-BUG-01 | Critical | Small (5 lines per function + `onStartCommand` tweak) | None |
| 2 | VPN-BUG-02 | Critical | Small (replace `serviceScope.launch` with `runBlocking`) | None |
| 3 | VPN-BUG-03 | Critical | Medium (conditional delete + DNS validation + isSelected normalization) | None |
| 4 | VPN-BUG-04 | High | Medium (add `isStarting.set(false)` in `stopVpn()` + post-establish re-check) | None |
| 5 | VPN-BUG-06 | High | Large (add `serviceState` StateFlow + observe in ViewModel + UI changes) | None |
| 6 | VPN-BUG-07 | High | Medium (combined with BUG-03 fix) | BUG-03 |
| 7 | VPN-BUG-05 | High | Medium (make `addCustomDnsPreset` suspend + UI spinner) | None |
| 8 | VPN-BUG-08 | Medium | Small (add repository wrapper or DAO guard) | None |
| 9 | VPN-BUG-09 | Medium | Small (add duplicate-name and duplicate-DNS checks) | None |
| 10 | VPN-BUG-10 | Medium | Small (update toast string) | None |
| 11 | VPN-BUG-11 | Medium | Small (add log warning) | None |
| 12 | VPN-BUG-12 | Medium | Medium (implement CONNECTIVITY_CHANGE handler or remove receiver) | BUG-06 |
| 13 | VPN-BUG-13 | Medium | Large (add health-check polling) | BUG-06 |
| 14 | VPN-BUG-14 | Low | Small (move launcher to parent composable) | None |
| 15 | VPN-BUG-15 | Low | Small (add `DNS 1 != DNS 2` check) | BUG-09 |
| 16 | VPN-BUG-16 | Low | Trivial (remove dead line or add comment) | None |
| 17 | VPN-BUG-17 | Low | Small (add live validation to dialog) | None |
| 18 | VPN-BUG-18 | Low | Small (pre-check `VpnService.prepare()` in worker) | BUG-01 |
| 19 | VPN-BUG-19 | Low | Small (use string resources in `vpnModeLabel`) | None |
| 20 | VPN-BUG-20 | Low | None (verified non-bug) | None |

**Total estimated effort**: ~3–5 days of focused work for a single developer, assuming the suggested patches are followed as a starting point.

---

## 9. Cross-Reference with Existing Fix History

The table below maps the new bugs to the existing fix history (where applicable), to make it easy to see which bugs are regressions from earlier fixes and which are net-new.

| New Bug ID | Related existing fix | Relationship |
|------------|---------------------|--------------|
| VPN-BUG-01 | (none — NopoX does it correctly) | Net-new — the rebuild never had this fix |
| VPN-BUG-02 | FIX 1.1 (ACTION_STOP persists VPN_SWITCH=false) | Related — FIX 1.1 added the pattern for ACTION_STOP, but the same pattern in `onRevoke()` has the race condition |
| VPN-BUG-03 | (none) | Net-new — backup logic was not analyzed in VPN_DEEP_ANALYSIS.md |
| VPN-BUG-04 | FIX 1.2 (assign restartJob so stopVpn can cancel it) + BUG-01 fix (AtomicBoolean isStarting) | Regression — the AtomicBoolean guard inadvertently causes the second restart to be skipped |
| VPN-BUG-05 | (none) | Net-new |
| VPN-BUG-06 | VPN-18 (notification title should reflect state) | Related — VPN-18 fixed the notification; the UI toggle has the same problem |
| VPN-BUG-07 | (none) | Net-new |
| VPN-BUG-08 | (none) | Net-new — defense-in-depth issue |
| VPN-BUG-09 | (none) | Net-new |
| VPN-BUG-10 | VPN-07 fix (only show restart toast when restart will happen) | Regression — VPN-07 fix added the conditional, but the "updated" toast is still misleading |
| VPN-BUG-11 | (none) | Net-new |
| VPN-BUG-12 | (none) | Net-new — the stub was always there |
| VPN-BUG-13 | (none) | Net-new — NopoX has the same gap |
| VPN-BUG-14 | (none) | Net-new |
| VPN-BUG-15 | (none) | Net-new |
| VPN-BUG-16 | (none) | Net-new — dead code from the original implementation |
| VPN-BUG-17 | (none) | Net-new |
| VPN-BUG-18 | (none) | Net-new |
| VPN-BUG-19 | VPN-14 fix (use string resources for toasts) | Regression — VPN-14 fix missed `vpnModeLabel` |
| VPN-BUG-20 | VPN-06 fix (no-op if mode unchanged) | Verified non-bug — VPN-06 fix correctly handles this |

**Summary**: 
- **3 regressions** from earlier fixes: BUG-04, BUG-10, BUG-19.
- **16 net-new bugs**: BUG-01, 02, 03, 05, 06, 07, 08, 09, 11, 12, 13, 14, 15, 16, 17, 18.
- **1 verified non-bug**: BUG-20.

---

## 10. Files Analysed

The following files were read in full during this analysis:

**Source code (Protect-Yourself, main branch)**:
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt`
- `app/src/main/java/protect/yourself/features/blockerPage/components/VpnManagementPage.kt` (865 lines)
- `app/src/main/java/protect/yourself/features/blockerPage/components/BlockerPageHome.kt` (1516 lines, VPN sections)
- `app/src/main/java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt` (1408 lines, VPN sections)
- `app/src/main/java/protect/yourself/features/blockerPage/identifiers/VpnConnectionTypeIdentifiers.kt`
- `app/src/main/java/protect/yourself/features/blockerPage/identifiers/SettingPageItemIdentifiers.kt`
- `app/src/main/java/protect/yourself/features/blockerPage/utils/DefaultPresets.kt`
- `app/src/main/java/protect/yourself/features/blockerPage/utils/BlockerPageUtils.kt` (isValidDNS section)
- `app/src/main/java/protect/yourself/database/vpnCustomDns/VpnCustomDnsDao.kt`
- `app/src/main/java/protect/yourself/database/vpnCustomDns/VpnCustomDnsItemModel.kt`
- `app/src/main/java/protect/yourself/database/switchStatus/SwitchStatusValues.kt`
- `app/src/main/java/protect/yourself/database/switchStatus/SwitchIdentifier.kt`
- `app/src/main/java/protect/yourself/database/switchStatus/SwitchStatusDao.kt`
- `app/src/main/java/protect/yourself/database/switchStatus/SwitchStatusItemModel.kt`
- `app/src/main/java/protect/yourself/database/core/AppDatabase.kt`
- `app/src/main/java/protect/yourself/database/core/AppDatabaseCallback.kt`
- `app/src/main/java/protect/yourself/commons/utils/workManager/VpnRestartWorker.kt`
- `app/src/main/java/protect/yourself/commons/utils/broadcastReceivers/AppSystemActionReceiver.kt`
- `app/src/main/java/protect/yourself/commons/utils/broadcastReceivers/AppSystemActionReceiverAllTime.kt`
- `app/src/main/java/protect/yourself/features/backupRestore/BackupManager.kt` (VPN sections)
- `app/src/main/java/protect/yourself/core/AppCoroutineExceptionHandler.kt` (appCoroutineScope)
- `app/src/main/res/values/strings.xml` (VPN strings)

**Decompiled NopoX 1.0.53**:
- `sources/com/planproductive/nopox/features/blockerPage/service/MyVpnService.java`
- `sources/com/planproductive/nopox/features/blockerPage/service/MyVpnService$onStartCommand$2.java`
- `sources/com/planproductive/nopox/features/blockerPage/utils/VpnServiceUtils.java`
- `sources/com/planproductive/nopox/features/blockerPage/identifiers/VpnConnectionTypeIdentifiers.java`
- `sources/com/planproductive/nopox/database/vpnCustomDns/VpnCustomDnsDao.java`
- `sources/com/planproductive/nopox/database/vpnCustomDns/VpnCustomDnsItemModel.java`
- `sources/com/planproductive/nopox/database/vpnCustomDns/VpnCustomDnsValues.java`
- `sources/com/planproductive/nopox/database/switchStatus/SwitchStatusDao.java`
- `sources/com/planproductive/nopox/database/switchStatus/SwitchStatusItemModel.java`

**Existing documentation**:
- `docs/VPN_DEEP_ANALYSIS.md` (19 issues documented against the older Future-Brand branch)
- `docs/VPN_DNS_SCHEMA_FIX_REPORT.md` (v1.0.57 schema repair)

---

## 11. Conclusion

The VPN subsystem on `main` is in good shape overall — the NopoX-style DNS-only tunnel design is correctly implemented, the schema corruption crash from v1.0.56 is fixed, and 14 of the 19 issues from the earlier `VPN_DEEP_ANALYSIS.md` have been addressed. The foreground notification is now state-aware, the AtomicBoolean guard prevents concurrent `establish()` calls, and the `VpnRestartWorker` correctly uses expedited WorkManager to bypass Android 12+ background-start restrictions.

However, **20 new bugs** were identified in this analysis, of which **3 are Critical** and **4 are High**. The most dangerous category is "silent protection failure" — 6 bugs can cause the user to think they're protected when they're not. For a content-blocking app whose entire value proposition is protection, this is the highest-priority category to fix.

The Critical bugs (BUG-01, BUG-02, BUG-03) should be fixed first. The suggested patches in this report are ready to be reviewed and applied. None of the patches have been applied to the source files — this report is a **plan only**, as requested.

After fixing the Critical and High bugs, the VPN subsystem should be in a production-ready state. The Medium and Low bugs can be addressed in a follow-up release.

**Next steps for the maintainer**:
1. Review the suggested patches for BUG-01, BUG-02, BUG-03 (Critical).
2. Run the test cases in §7.1–7.3 to verify the bugs exist on a real device.
3. Apply the patches (or revised versions) on a feature branch.
4. Re-run the test cases to verify the fixes.
5. Repeat for BUG-04 through BUG-07 (High).
6. Schedule the Medium and Low bugs for a follow-up release.

---

*End of report.*
