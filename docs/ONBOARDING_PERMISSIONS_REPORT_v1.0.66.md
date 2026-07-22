# Onboarding Permission Requests — Analysis & Fix Report (v1.0.66, OB-PERM)

**Branch:** `feat/onboarding-permissions-v1.0.66`
**Date:** 2026-07-22
**Reported issue:** "The onboarding flow does not request the required permissions. It does not prompt users for notification permission or background service permission during onboarding."

---

## 1. Executive Summary

The onboarding flow (`OnboardingPage` in `MainActivity.kt`) previously consisted of a
single scroll page (features, terms, a passive "please enable accessibility" hint, and
two accept buttons). It **never requested any runtime or special-access permission**,
even though several core subsystems silently depend on them:

| Subsystem | Depends on | Effect when missing |
|---|---|---|
| Alerts (accessibility-off warning, VPN-permission-required, overlay nudge, daily report) | `POST_NOTIFICATIONS` (runtime, API 33+) | All alerts **silently invisible** on API 33+ fresh installs (targetSdk 35) |
| WorkManager reconciles (ScheduleCheckWorker 15 min), VpnRestartWorker, boot-restore backup alarm | Battery-optimization exemption | Doze / OEM task killers delay or drop restores; VPN fails to come back after reboot/idle on aggressive OEM builds |
| ScheduleEngine alarms, `VpnRestoreHelper` backup alarm | `SCHEDULE_EXACT_ALARM` special access (API 31+) | Code already degrades to inexact alarms, but restores/schedules fire late |
| Content/app blocking (MyAccessibilityService) | Accessibility service toggle | Blocking completely non-functional — this was a passive text hint with no state detection or action |

**Fix (this round):** onboarding is now a two-step flow — **Terms → Permission
checklist** — with live grant-state detection, per-row grant actions (runtime prompt /
system settings with two-level fallbacks), skippable non-blocking completion, crash-log
breadcrumbs, and full state persistence across rotation/process death. A new
`OnboardingPermissions` helper centralizes state evaluation as pure, unit-testable
logic. `NotificationHelper` additionally gates all posting on
`areNotificationsEnabled()`.

---

## 2. Confirmed Issues (Root Causes)

| ID | Severity | Root cause |
|---|---|---|
| OB-PERM-01 | **High** | No `ActivityResultContracts.RequestPermission` call for `POST_NOTIFICATIONS` existed anywhere in the codebase (grep-verified). On API 33+ with targetSdk 35 the permission is denied by default, so every alert posted via `NotificationManager.notify()` no-op'd. This also silently neutralized the v1.0.64 VPN-NOTIF-04 "VPN permission required" alert. |
| OB-PERM-02 | **High** | No battery-optimization handling anywhere: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` not declared, no `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`/`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` intent ever fired. Fresh installs sit in the "optimized" bucket; Doze and OEM killers (Xiaomi/Oppo/Vivo/Samsung sleeping-apps) throttle WorkManager and drop the process, breaking boot-restore and schedule reliability. |
| OB-PERM-03 | **High** | The accessibility requirement was **passive text** ("go to Settings → Accessibility…"), with no live enabled-state detection (despite `AccessibilityGuard.isAccessibilityServiceEnabled()` existing since v1.0.42) and a single CTA that fired the settings intent *and* accepted terms in one tap — dropping the user out of onboarding mid-flow. |
| OB-PERM-04 | **Medium** | `SCHEDULE_EXACT_ALARM` special access never surfaced to the user. `ScheduleAlarmReceiver`, `StopMeManager` and `VpnRestoreHelper` already check `canScheduleExactAlarms()` and degrade to inexact alarms — correct fail-safe behavior but silently worse; user-revoked access (which Android allows at any time) went unnoticed. |
| OB-PERM-05 | **Low** | `NotificationHelper.show*()` built + posted notifications unconditionally — wasted PendingIntent/plumbing work and platform no-ops (plus a `SecurityException` risk on strict OEM builds when posting without the runtime grant). |
| OB-PERM-06 | **Low** | Onboarding state (`agreed` checkbox) used plain `remember` — lost on rotation/process re-creation; with a multi-step flow this would also have lost the current step while the user sat on a system permission screen. |

## 3. Per-Setting Review (Complete Onboarding/Re-Entry Permission Surface)

| Permission / setting | Type | Needed by | Now requested in onboarding? | Mechanism |
|---|---|---|---|---|
| `POST_NOTIFICATIONS` | Runtime (33+) | All `NotificationHelper` alerts | ✅ Yes | Runtime dialog via `rememberLauncherForActivityResult`; after a denial the row re-routes to `ACTION_APP_NOTIFICATION_SETTINGS` (Android won't re-show the dialog) |
| Battery-optimization exemption | Special app access | WorkManager, alarms, VPN auto-restore | ✅ Yes | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (package URI) with fallback to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` + toast on total failure; new manifest permission declared |
| `SCHEDULE_EXACT_ALARM` | Special app access (31+) | Scheduler + boot-restore backup alarm | ✅ Yes (RECOMMENDED row; shown only on 31+) | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (package URI) |
| Accessibility service | Settings toggle (not programmatically requestable — platform restriction) | All content/app blocking | ✅ Yes (REQUIRED row) | `ACTION_ACCESSIBILITY_SETTINGS`; live state via `AccessibilityGuard.isAccessibilityServiceEnabled()` |
| VPN consent `VpnService.prepare()` | Runtime dialog (per VPN enable) | VPN DNS filtering / per-app block | ⏸ Deliberately deferred | Kept at VPN-enable time (existing flows in `BlockerPageViewModel`). Asking during onboarding would train blind acceptance of a VPN dialog with no immediate context; consent persists across reboots once granted. Documented as a considered-and-rejected option. |
| `SYSTEM_ALERT_WINDOW` (overlay) | Special app access | Strong block screen (anti-circumvention) | ⏸ Deferred | Optional hardening; already prompted contextually (throttled 24 h) by `showOverlayPermissionNotification`. Avoids an overloaded 5-row checklist. |
| `WRITE_SECURE_SETTINGS` | Signature-only (adb/root grant) | Accessibility self-heal | ❌ Not user-requestable | Self-heal path unchanged. |
| `QUERY_ALL_PACKAGES`, FGS types, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `USE_BIOMETRIC`, etc. | Install-time / normal | Various | N/A | Granted at install; biometric enrollment handled by `BiometricPrompt` at App-Lock setup time. |

## 4. Implementation Details

### 4.1 `commons/utils/permissionUtils/OnboardingPermissions.kt` (new)

- **Pure core:** `buildRows(sdkInt, notificationsEnabled, batteryIgnored, exactAlarmsAllowed, accessibilityEnabled)` builds the ordered row list; `allRequiredGranted()` / `missingKinds()` derive UI summary + skip logging. The entire granted/denied × applicable/not-applicable matrix is unit-testable without Android.
- **Fail-closed live reads:** `evaluate(context)` reads real state; every read is try/catch (a broken OEM settings provider reports "not granted" and never crashes onboarding).
- **Intent factories (pure):** battery direct-request + settings fallback, exact-alarm, accessibility, per-app notification settings — action strings/URIs/flags pinned by tests.

### 4.2 `MainActivity.kt` — onboarding rebuilt as two steps

- `OnboardingStep` enum + `rememberSaveable` step/checkbox (OB-PERM-06); `BackHandler` on the permissions step returns to terms instead of exiting the app.
- **Terms step:** original content preserved (features, terms, importance banner updated to point at the new permission step), single **Accept & Continue** CTA.
- **Permissions step:**
  - Rows: Notifications (Required, 33+), Background running (Recommended), Alarms & reminders (Recommended, 31+), Accessibility service (Required).
  - Live refresh: `LifecycleEventObserver` on `ON_RESUME` (returning from system screens) + refresh after every action result.
  - Per-row action buttons with labels (`Allow`/`Exempt`/`Enable`/`Open`); granted/Not-required rows show a check.
  - Launch funnel `launchSystemScreen()` catches `ActivityNotFoundException`/`SecurityException`, logs + breadcrumb, battery row has a second-level settings fallback; terminal failures surface an explanatory toast (user-facing feedback).
  - Notification row: runtime prompt on 33+; after a denial (`notifDeniedOnce`, `rememberSaveable`) the row opens per-app notification settings instead.
  - Completion is **skippable by design** (permission best practice): **Continue to App** logs the missing kinds (`Timber.w` + crash-log breadcrumb `finish_with_missing`) — diagnostics without blocking the user.
- Crash-log breadcrumbs via the same `ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(...)` pattern used elsewhere; the wrapper swallows logger-uninitialized states.

### 4.3 `NotificationHelper` (OB-PERM-05)

- New `canPostNotifications()` gate at the top of all four `show*()` methods: skips build+post work when disabled, logs the skip with notification id; **fails open** on read errors so a broken read can never silence a critical protection alert (posting while disabled is a safe platform no-op).

### 4.4 Manifest

- Added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (required by the direct whitelist dialog; without it `startActivity` throws `SecurityException`, which the funnel already catches and falls back from).

## 5. UI/UX Evaluation

| Aspect | Before | After |
|---|---|---|
| Discoverability | Zero permission UI | Ordered checklist with per-item actions and status language (`✓ Granted` / `Required` / `Recommended` / `Not required here`) |
| Interruption risk | Settings intent fired *with* terms acceptance — user dropped out mid-flow | Settings launches only from explicit row taps; state auto-refreshes on return; back returns to terms |
| Dark/light themes | — | All rows use `MaterialTheme.colorScheme` tokens (checked both palettes) |
| Small screens / landscape | Scrollable single page | Both steps remain scrollable; row layout uses weighted columns (no overflow) |
| Rotation / process death | Checkbox lost (OB-PERM-06) | Step + checkbox + denial flag are `rememberSaveable` |
| API 26–35 consistency | N/A | Rows adapt per platform: pre-33 notification row and pre-31 alarm row collapse to informational "not required" granted rows |

## 6. Networking, Stability & Error-Handling Evaluation

- **Networking:** none of the onboarding permission paths perform network I/O (intents only); the DNS-filter VPN behavior is untouched. Post-permission, boot-restore/VPN benefits are reliability-only.
- **Stability scenarios:**
  - User backgrounds the app on a permission dialog/system screen → `ON_RESUME` refresh + `rememberSaveable` state → correct row state.
  - OEM without the direct battery intent handler → caught, settings-list fallback, then toast.
  - `canScheduleExactAlarms()`/`isIgnoringBatteryOptimizations()` throwing builds → fail-closed "not granted", onboarding still completes.
  - Second notification denial (sticky) → row re-routes to per-app notification settings.
  - `onResume` re-lock invariant (LOCKSESSION-01) untouched — onboarding only runs pre-terms (`appState == ONBOARDING`), Main/lock flows unchanged.
- **Logging:** `Timber.i/w/e` on every action, result, skip, and failure; `CrashLogger` breadcrumbs (`terms_continue`, `post_notifications_request/result`, `settings_launch_failed`, `finish_with_missing`, `finish_all_granted`).

## 7. Testing

### 7.1 New unit tests — `OnboardingPermissionsTest` (14 cases, Robolectric `@Config(sdk=[34])`)

| Test | Pins |
|---|---|
| sdk-26 matrix | Runtime-gated rows collapse to granted/not-applicable; accessibility still required |
| sdk-34 all-denied | All 4 rows applicable+missing; `missingKinds` order |
| sdk-34 all-granted | `allRequiredGranted` true, no missing |
| Required-vs-recommended | Recommended misses don't block `allRequiredGranted` |
| Gate boundaries | 32/33 notification, 30/31 exact-alarm |
| Battery shadow | `ShadowPowerManager.setIgnoringBatteryOptimizations(pkg, …)` reflected in reads + `evaluate()` |
| Exact-alarm shadow | `ShadowAlarmManager.setCanScheduleExactAlarms` (static) reflected |
| Notification shadow | `ShadowNotificationManager.setNotificationsEnabled` reflected |
| Accessibility default | Disabled in fresh shadow state |
| OB-PERM-05 ×2 | Helper posts when enabled; skips when disabled |
| Intent factories ×3 | Action strings, `package:` URIs, NEW_TASK flags, no-data fallback intent |

### 7.2 Full suite + builds

- `:app:testDebugUnitTest` → **340/340 pass** (22 suites), 0 failures/errors/skipped — no regressions across the 326 existing tests (including the notification-shadow-dependent `VpnReviewFixesTest`).
- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**.
- `apksigner verify` OK on both; badging `protect.yourself` versionCode **66** / `1.0.66`; merged manifest contains `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**; `OnboardingPermissions` present in DEX of both APKs.

### 7.3 State/transition coverage matrix (manual reasoning + unit pins)

| Scenario | Expected behavior | Covered by |
|---|---|---|
| Each row: Granted | Check icon, no button, "✓ Granted" | Row-builder matrix tests |
| Each row: Denied/missing | Action button + Required/Recommended badge | Matrix tests |
| API 26–32 | Notification row informational granted; no alarm row action | Gate + sdk-26 tests |
| API 31–32 | Alarm row actionable; notification row informational | Gate tests |
| API 33–35 | All runtime-gated rows actionable | sdk-34 live-read tests |
| Notification denied once | Row re-routes to app notification settings | UI path (`notifDeniedOnce`) |
| Return from settings without granting | Row stays missing (live refresh, no false positive) | `ON_RESUME` refresh design |
| Rotation/process re-creation | Step, checkbox, denial flag preserved | `rememberSaveable` (OB-PERM-06) |
| Continue with missing permissions | Allowed; missing kinds logged + breadcrumb | Footer logic + `missingKinds` tests |
| OEM missing direct battery handler | Falls back to settings list, then toast | `launchSystemScreen` two-level fallback |

## 8. Regression Watchlist (verified unchanged)

- `checkAppState()` terms→lock→main routing, BUG-25 safe fallback, LOCKSESSION-01 pre-lock reset — untouched.
- Existing accept contract `OnboardingPage(onAccept)` kept; caller in `setContent` unchanged.
- `AccessibilityGuard`/`AccessibilityPersistUtils` self-heal cadence — read-only reuse.
- `NotificationHelper` posting semantics unchanged when notifications are enabled (existing `VpnReviewFixesTest` passes).

## 9. Manual On-Device Validation Checklist (emulator unavailable in build sandbox)

1. Fresh install (API 34): onboarding shows Terms → Permissions; allow notifications in dialog → row flips to Granted.
2. Battery row → system whitelist dialog appears; deny → row stays missing; Open fallback works.
3. Alarm row (API 31+) → "Alarms & reminders" screen opens; toggle → check on return.
4. Accessibility row → settings open; enable service → check on return; blocking works.
5. Rotate / background mid-permission-dialog → step + checkbox preserved.
6. Continue with missing permissions → toast-free, in-app; logcat shows `finish_with_missing` breadcrumb; `app_lock`-style flows unaffected.
7. API 28 device → notification/alarm rows shown as not required.
