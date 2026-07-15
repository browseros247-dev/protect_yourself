# App-Blocking Deep Analysis — Protect-Yourself (app-blocking-fixes branch)

> **Branch**: `app-blocking-fixes` (off `main` at commit `ce7def1`, which includes the Future-Brand merge)
> **Analysis date**: 2026-07-11
> **Scope**: Every app-blocking setting, evaluated individually against the decompiled reference v1.0.53, with focus on (a) blocking correctness, (b) performance, (c) UI/UX, (d) bugs / inconsistencies / improvement opportunities.
> **Methodology**: Static source review of all app-blocking code + JADX decompilation of the reference APK (21,067 Java files decompiled) + line-by-line comparison of `MyAccessibilityService`, `StopMeManager`, `BlockerPageUtils`, and the Device Admin integration.

---

## Executive Summary

The app-blocking subsystem is the most complex part of Protect-Yourself — it manages 11 distinct blocking features (blocklist apps, unsupported browsers, in-app browsers, new install apps, package+intent names, prevent uninstall, block reboot, Stop Me, + 2 removed features that leave residual code). All blocking flows through a single `handleWindowStateChange` dispatch in `MyAccessibilityService`, which checks each condition in sequence and launches `PornBlockActivity` when a match is found.

A deep review against the decompiled reference reveals **20 issues** of varying severity. The most important are:

- **AB-01 (Critical)** — Stop Me state is in-memory only (`isStopMeRunning: Boolean`). After process death (reboot, force-stop, OEM killing the service), an active Stop Me session silently stops blocking. **The reference uses `stopMeEndTime` (a persisted long timestamp)** which survives process death — the service checks `System.currentTimeMillis() < stopMeEndTime` on every event.
- **AB-02 (Critical)** — Blocklist apps are gated by `isPornBlockerOn && cachedBlockApps.contains(packageName)`. If the user turns off the Porn Blocker switch, blocklist apps stop being blocked even though the user explicitly added them. This is confusing and inconsistent with the UI label ("Apps that get blocked on launch").
- **AB-03 (High)** — `isAppInfoPage` matches against `DEVICE_ADMIN_TEXTS_TO_MATCH` which includes generic strings like "admin" and "deactivate". Any settings page mentioning "admin" (e.g. "Administrator settings") triggers the prevent-uninstall block, locking the user out of unrelated settings pages.
- **AB-04 (High)** — `isPowerMenu` checks `lowerPkg.contains("shutdown")` — matches `com.example.shutdown.helper` (not a power menu) as a false positive.
- **AB-05 (High)** — Block screen uses `FLAG_ACTIVITY_CLEAR_TOP` but NOT `FLAG_ACTIVITY_CLEAR_TASK` or `GLOBAL_ACTION_HOME`. The offending app stays in the back stack. When the user taps Close, they may return to the offending app instead of the home screen. The reference presses HOME before showing the block screen.
- **AB-06 (Medium)** — `isBrowserPackageDetected` calls `PackageManager.queryIntentActivities()` on every accessibility event for an uncached package. This is a slow IPC call that can cause event processing back up under rapid app switching.
- **AB-07 through AB-12** — Dead code: `IN_APP_BROWSER_CLASS_NAMES`, `autoStartXiaomiTextToMatch()`, `isNotificationDrawer()`, `isRecentApps()`, `ProtectedAppsActivity` stub, `NotificationActionService` stub, empty Device Admin policies, unused Device Admin intent-filter actions.

The detailed analysis below walks through every app-blocking setting one by one.

---

## 1. Inventory of app-blocking settings

| # | Setting | Switch key | App-list identifier | Block message key | Status |
|---|---------|------------|---------------------|-------------------|--------|
| AB1 | Blocklist apps | (gated by `PORN_BLOCKER_SWITCH`) | `BLOCK_APPS` | `block_page_default_block_apps_message` | ✅ Active |
| AB2 | Block unsupported browsers | `BLOCK_UNSUPPORTED_BROWSERS_SWITCH` | `WHITELIST_UNSUPPORTED_BROWSER` | `block_page_default_unsupported_browser_message` | ✅ Active |
| AB3 | Whitelist unsupported browsers | (no switch) | `WHITELIST_UNSUPPORTED_BROWSER` | — | ✅ Active |
| AB4 | Block in-app browsers | `BLOCK_IN_APP_BROWSERS_SWITCH` | `BLOCK_IN_APP_BROWSER_APPS` | `block_page_default_in_app_browser_message` | ✅ Active |
| AB5 | Block new install apps | `BLOCK_NEW_INSTALL_APPS_SWITCH` | `BLOCK_NEW_INSTALL_APPS` | `block_page_default_new_install_message` | ✅ Active |
| AB6 | Package + Intent blocking | `BLOCK_PACKAGE_INTENT_SWITCH` | `BLOCKED_PACKAGE_NAMES` + `BLOCKED_INTENT_NAMES` | `block_page_default_block_apps_message` | ✅ Active |
| AB7 | Prevent uninstall | `PREVENT_UNINSTALL_SWITCH` + Device Admin | (none) | `block_page_default_pu_message` | ✅ Active |
| AB8 | Block phone reboot | `BLOCK_PHONE_REBOOT_SWITCH` | (none) | `block_phone_reboot_bw_message` | ✅ Active |
| AB9 | Stop Me (focus mode) | (runtime state) | `WHITELIST_STOP_ME_APPS` | `block_page_default_stop_me_message` | ✅ Active |
| AB10 | Block notification drawer | `BLOCK_NOTIFICATION_DRAWER_SWITCH` | (none) | `block_page_default_notification_drawer_message` | ❌ Removed (residual code) |
| AB11 | Block recent apps | `BLOCK_RECENT_APPS_SWITCH` | (none) | `block_recent_apps_bw_message` | ❌ Removed (residual code) |

---

## 2. The dispatch pipeline — `handleWindowStateChange`

All 11 settings flow through a single `handleWindowStateChange` method that checks conditions in a fixed order and returns on the first match. The order matters — earlier checks take priority.

### 2.1 Current order (lines 177–263)

1. Skip own app + SystemUI
2. Settings page title blocking (`isSettingsPage`)
3. Title-based blocking on ANY app (`isAnyTitleBlocked`)
4. Package + Intent name blocking (`isPackageOrIntentBlocked`)
5. Prevent uninstall (`isAppInfoPage`)
6. Block phone reboot (`isPowerMenu`)
7. Blocklist apps (`cachedBlockApps.contains`)
8. Block new install apps (`cachedNewInstallBlockApps.contains`)
9. Block unsupported browsers (`isBrowserPackageDetected`)
10. Block in-app browsers (`cachedInAppBrowserBlockApps.contains` + URL extraction)
11. Stop Me (`isStopMeRunning && !cachedStopMeWhitelist.contains`)

### 2.2 Reference order (from decompiled `onAccessibilityEvent`)

1. `checkBlockBrowsers` (combined browser detection + URL keyword matching)
2. `checkPhoneRebootOption`
3. `checkRecentAppsOption`
4. `checkPreventUninstallPromotion` (promote enabling prevent-uninstall)
5. `checkPreventUninstall`
6. `checkNotificationDrawer`
7. `checkSettingAppKeywordClickBlock`
8. `checkCustomSocialMediaBlocking`
9. `checkSocialMediaSearch`
10. `checkSocialMediaClick`
11. `checkPornUrlSearch` / `checkPornClickedText`

### 2.3 Key differences

- **Reference checks browser blocking FIRST** — before prevent-uninstall and power menu. This makes sense because browser blocking is the most common case and should be fast.
- **Reference has `checkPreventUninstallPromotion`** — a separate method that detects when the user is on the app info page but hasn't enabled prevent-uninstall yet, and promotes the feature. The rebuild doesn't have this.
- **Reference combines browser detection + URL keyword matching into one method** (`checkBlockBrowsers`). The rebuild splits them into `isBrowserPackageDetected` + `handleContentChange` → `handleUrlDetected`.
- **Reference checks Stop Me LAST** (not visible in the smali excerpt but confirmed by the field `stopMeEndTime` being checked inside `checkBlockBrowsers`). The rebuild checks Stop Me last too, which is correct.

---

## 3. Setting AB1 — Blocklist apps

### 3.1 What it does

Apps in the `BLOCK_APPS` list get blocked on launch — when the accessibility service detects a `WINDOW_STATE_CHANGED` event from a package in the list, it launches the block screen.

### 3.2 Issues

#### AB-02 (Critical) — Gated by Porn Blocker switch

```kotlin
// Block apps (blocklist)
if (isPornBlockerOn && cachedBlockApps.contains(packageName)) {
    launchBlockActivity(packageName, "block_page_default_block_apps_message")
    return
}
```

The condition requires `isPornBlockerOn` to be true. If the user turns off the Porn Blocker switch (e.g. to stop URL keyword matching but keep app blocking), blocklist apps stop being blocked. This is confusing — the UI says "Apps that get blocked on launch" with no mention of the Porn Blocker dependency.

**Reference comparison**: The reference has `pornBlock` as a separate boolean, and `blockApps` is checked independently. The rebuild's coupling is a regression.

**Fix**: Remove the `isPornBlockerOn` gate. Blocklist apps should work independently.

```kotlin
if (cachedBlockApps.contains(packageName)) {
    launchBlockActivity(packageName, "block_page_default_block_apps_message")
    return
}
```

---

## 4. Setting AB7 — Prevent uninstall

### 4.1 What it does

When ON, the accessibility service detects when the user navigates to the app info page for Protect-Yourself (where the Uninstall button lives) and blocks them. Also requires Device Admin to be active (which prevents uninstall via the "Uninstall" button even if the accessibility block is bypassed).

### 4.2 Issues

#### AB-03 (High) — `DEVICE_ADMIN_TEXTS_TO_MATCH` too broad

```kotlin
val DEVICE_ADMIN_TEXTS_TO_MATCH: List<String> = listOf(
    "device admin", "deactivate", "extended_title", "applabel_title",
    "header_title", "alertTitle", "detail_title"
)
```

The strings "device admin" and "deactivate" are too generic. Any settings page that mentions "admin" (e.g. "Administrator settings", "User administration") or "deactivate" (e.g. "Deactivate account" in a social app) will trigger the prevent-uninstall block, locking the user out of unrelated pages.

**Reference comparison**: The reference has `deviceAdminTextList` but the decompiled code shows it's used only when the package is `com.android.settings` AND the class name contains `appinfo`/`installedappdetails`. The rebuild's `isAppInfoPage` does check the package name, but the device-admin text matching is done without the class-name guard — it matches ANY text on ANY settings page.

**Fix**: Only check device-admin texts when the class name indicates an app-info page (which the code partially does, but the text matching loop runs unconditionally after the class-name checks fail). Move the device-admin text matching inside the `if (lowerClass.contains("appinfo") ...)` guard.

#### AB-04 (High) — `isPowerMenu` false positive on package name

```kotlin
if (lowerPkg.contains("shutdown") ||
    lowerPkg.contains("powermenu") ||
    lowerPkg.contains("globalactions")
) {
    return true
}
```

`lowerPkg.contains("shutdown")` matches `com.example.shutdown.helper` — not a power menu. This causes false-positive reboot blocks on any app whose package name contains "shutdown".

**Fix**: Use exact package-name matching against known power-dialog packages (e.g. `com.android.server.powerdialog`, `com.miui.powermanager`) instead of substring matching.

---

## 5. Setting AB8 — Block phone reboot

### 5.1 What it does

When ON, detects the power menu / shutdown dialog and blocks it. Uses class-name matching (`powerdialog`, `shutdownactivity`, `globalactions`, etc.) + localized ultra-power-saving text matching.

### 5.2 Issues

- **AB-04** (see above) — false positive on package name containing "shutdown".
- The localized `HUAWEI_ULTRA_POWER_SAVING_TEXTS` list includes "you (owner)", "Add user", "Add guest" — these are NOT power-saving texts. They appear to be multi-user detection strings that were accidentally included. Should be moved to a separate list or removed.

---

## 6. Setting AB9 — Stop Me (focus mode)

### 6.1 What it does

When a Stop Me session is active, the accessibility service blocks any app not in the `WHITELIST_STOP_ME_APPS` list. The session has a duration (15/30/60/120 min) and ends automatically when the timer expires (via AlarmManager).

### 6.2 Issues

#### AB-01 (Critical) — Stop Me state is in-memory only

```kotlin
private var isStopMeRunning = false

fun setStopMeRunning(running: Boolean) {
    isStopMeRunning = running
}
```

`isStopMeRunning` is a plain `Boolean` on the accessibility service. If the service is killed (reboot, force-stop, OEM battery optimization), `isStopMeRunning` resets to `false`. The session row still exists in `stop_me_duration_table` with a valid `endTime`, but the accessibility service doesn't check it.

**Reference comparison**: The reference uses `private static long stopMeEndTime` — a persisted timestamp. On every event, the reference checks `System.currentTimeMillis() < stopMeEndTime`. If the service is killed and reconnected, `refreshAccessibilityVariables` reloads `stopMeEndTime` from the DB, so the session resumes automatically.

**Fix**: Replace `isStopMeRunning: Boolean` with `stopMeEndTime: Long`. On `refreshBlockingConfig`, load the active session from the DB and set `stopMeEndTime`. In `handleWindowStateChange`, check `System.currentTimeMillis() < stopMeEndTime` instead of `isStopMeRunning`.

#### AB-14 (Low) — Boot receiver doesn't restore Stop Me state

`AppSystemActionReceiverAllTime` calls `refreshBlockingConfig()` on boot but doesn't call `setStopMeRunning(true)`. Combined with AB-01, this means after a reboot, an active Stop Me session is completely lost.

**Fix**: In `loadAllConfig`, load the active Stop Me session from the DB and set `stopMeEndTime` (after AB-01 fix).

#### AB-16 (Low) — Widget hardcodes 25-minute session

```kotlin
StopMeManager.getInstance(context).startInstantSession(
    durationMillis = TimeUnit.MINUTES.toMillis(25)
)
```

The widget always starts a 25-minute session. Should use the last-used duration or a configurable default.

#### AB-17 (Low) — `calculateNextTrigger` off-by-one

```kotlin
for (i in 0..7) {  // checks 8 days but only 7 exist in a week
```

This is technically harmless (the 8th iteration just re-checks the first day), but it's confusing. Should be `0..6`.

---

## 7. Setting AB2 — Block unsupported browsers

### 7.1 What it does

When ON, any browser NOT in the supported list AND NOT in the unsupported-browser whitelist gets blocked. A "browser" is defined as an app that handles http/https URLs (via PackageManager intent-filter inspection) OR matches known browser package signatures.

### 7.2 Issues

#### AB-06 (Medium) — `queryIntentActivities` on every event

```kotlin
val httpResolved = pm.queryIntentActivities(httpIntent, 0)
val httpsResolved = pm.queryIntentActivities(httpsIntent, 0)
```

`queryIntentActivities` is a PackageManager IPC call. For an uncached package, it's called on every `WINDOW_STATE_CHANGED` event. Under rapid app switching, this can cause event processing to back up.

**Fix**: The `browserCache` (ConcurrentHashMap) already caches results, so each package is only queried once. But the initial query is still slow. Consider pre-populating the cache on `onServiceConnected` by querying all browser packages once.

---

## 8. Block screen launch — `launchBlockActivity`

### 8.1 Issues

#### AB-05 (High) — No HOME action before block screen

The comment explains that pressing HOME before `startActivity` caused the block screen to be dismissed. But NOT pressing HOME means the offending app stays in the back stack. When the user taps Close, they return to the offending app.

**Reference comparison**: The reference's `showPuBlockPage` method presses `GLOBAL_ACTION_HOME` before launching the block activity. The rebuild tried this and had the dismissal problem — but the fix should be to press HOME *after* a short delay (e.g. 100ms), not to skip it entirely.

**Fix**: Press HOME after launching the block screen (with a 200ms delay to let the block activity appear first):

```kotlin
startActivity(intent)
// Press HOME after a short delay to move the offending app to the background
// without dismissing the block screen.
serviceScope.launch {
    delay(200)
    try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
}
```

---

## 9. Dead code & residual cleanup

### 9.1 Issues

- **AB-07**: `IN_APP_BROWSER_CLASS_NAMES` declared in `BlockerPageUtils` but never referenced. Dead code.
- **AB-08**: `autoStartXiaomiTextToMatch()` declared but never called. Dead code.
- **AB-09**: `isNotificationDrawer()` and `isRecentApps()` helpers in `MyAccessibilityService` — never called. Dead code.
- **AB-10**: `BLOCK_NOTIFICATION_DRAWER` and `BLOCK_RECENT_APPS` in the "Uninstall Protection" category card's `setOf(...)` filter — no corresponding rows in `buildSettingItems()`. Silently no-ops.
- **AB-11**: `ProtectedAppsActivity` and `NotificationActionService` are stubs (`TODO Phase 6`) declared in the manifest.
- **AB-12**: `device_admin.xml` has empty `<uses-policies />`. The manifest declares `USER_ADDED` and `USER_SWITCHED` actions on the receiver, but `MyDeviceAdminReceiver` doesn't override those callbacks.
- **AB-13**: `AccessibilityPersistUtils.selfHealSafe()` is a no-op.

---

## 10. Summary of issues by severity

| ID | Severity | Setting | Summary |
|----|----------|---------|---------|
| AB-01 | Critical | AB9 (Stop Me) | Stop Me state is in-memory only — lost on process death. Reference uses persisted `stopMeEndTime` |
| AB-02 | Critical | AB1 (Blocklist apps) | Gated by `isPornBlockerOn` — blocklist apps stop working when Porn Blocker is OFF |
| AB-03 | High | AB7 (Prevent uninstall) | `DEVICE_ADMIN_TEXTS_TO_MATCH` too broad — "admin"/"deactivate" match unrelated pages |
| AB-04 | High | AB8 (Block reboot) | `isPowerMenu` false positive on package name containing "shutdown" |
| AB-05 | High | (block screen) | No HOME action — offending app stays in back stack |
| AB-06 | Medium | AB2 (Unsupported browsers) | `queryIntentActivities` IPC on every event for uncached packages |
| AB-07 | Medium | (dead code) | `IN_APP_BROWSER_CLASS_NAMES` never used |
| AB-08 | Medium | (dead code) | `autoStartXiaomiTextToMatch()` never called |
| AB-09 | Medium | (dead code) | `isNotificationDrawer()` + `isRecentApps()` never called |
| AB-10 | Medium | (dead code) | `BLOCK_NOTIFICATION_DRAWER` + `BLOCK_RECENT_APPS` in category filter set |
| AB-11 | Medium | (stubs) | `ProtectedAppsActivity` + `NotificationActionService` are TODO stubs |
| AB-12 | Medium | (Device Admin) | Empty `<uses-policies />` + unused `USER_ADDED`/`USER_SWITCHED` actions |
| AB-13 | Medium | (Accessibility) | `selfHealSafe()` is a no-op |
| AB-14 | Low | AB9 (Stop Me) | Boot receiver doesn't restore Stop Me state |
| AB-15 | Low | AB5 (New install) | Package receiver doesn't call `refreshBlockingConfig()` after auto-add |
| AB-16 | Low | AB9 (Stop Me) | Widget hardcodes 25-minute session |
| AB-17 | Low | AB9 (Stop Me) | `calculateNextTrigger` off-by-one (`0..7` should be `0..6`) |
| AB-18 | Low | AB6 (Package+Intent) | `cachedBlockedIntentNames` uses O(N) scan — should use KeywordMatcher |
| AB-19 | Low | AB7 (Prevent uninstall) | `isAppInfoPage` class-name matching is OEM-fragile |
| AB-20 | Low | AB8 (Block reboot) | `HUAWEI_ULTRA_POWER_SAVING_TEXTS` includes non-power-saving strings ("Add user", "Add guest") |

---

## 11. Recommended priority order for fixes

1. **AB-01** (Stop Me persisted state) — biggest correctness gap vs reference
2. **AB-02** (blocklist apps gate) — confusing UX regression
3. **AB-03** (device admin text matching) — false-positive lockout risk
4. **AB-05** (HOME action before block screen) — UX correctness
5. **AB-04** (power menu false positive) — false-positive block
6. **AB-14** (boot receiver Stop Me restore) — depends on AB-01
7. **AB-15** (package receiver refresh) — new install not blocked immediately
8. **AB-18** (intent name KeywordMatcher) — performance
9. **AB-06** (browser cache pre-population) — performance
10. **AB-07 through AB-13** (dead code cleanup) — code quality
11. **AB-16, AB-17, AB-19, AB-20** — minor fixes
