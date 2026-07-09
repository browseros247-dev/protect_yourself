# Protect Yourself — Phased Testing & Debugging Plan

> **Version**: 1.0
> **Date**: 2026-07-10
> **Goal**: Test, debug, and fix every feature one phase at a time, with visible validation feedback at each step.

---

## Overview

The app has 12 major feature areas. Each is treated as a separate phase. **No phase starts until the previous phase is verified working.** Each phase follows the same 4-step cycle:

```
Investigate → Fix → Validate (with UI feedback) → Verify
```

### Validation UI/UX Principle

Every user action must produce **immediate, visible feedback**:
- ✅ **Success**: Green toast/snackbar + state update (switch animates, label changes)
- ❌ **Error**: Red error message inline + actionable guidance ("Tap here to grant permission")
- ⏳ **Loading**: Spinner or progress indicator while async work runs
- ℹ️ **Info**: Blue banner when a prerequisite is missing ("Enable accessibility first")

---

## Phase 1: App Launch & Onboarding

**Scope**: App opens without crashing, onboarding shows on first launch, terms acceptance persists.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 1.1 | Install fresh APK, tap icon | Onboarding page appears (not white screen, not crash) | ⏳ |
| 1.2 | Read terms, tick checkbox | "Accept" button enables (was disabled) | ⏳ |
| 1.3 | Tap "Accept & Open Accessibility Settings" | System Accessibility Settings opens | ⏳ |
| 1.4 | Return to app (without enabling accessibility) | Main screen shows + red accessibility banner at top | ⏳ |
| 1.5 | Kill app, reopen | Onboarding does NOT appear (terms already accepted) | ⏳ |
| 1.6 | Tap red accessibility banner | System Accessibility Settings opens | ⏳ |

### Validation UI
- Onboarding: checkbox disabled state = grayed button; enabled = orange button
- Accessibility banner: red card with ⚠️ icon, tappable
- After enabling accessibility: banner disappears on next app resume

### Files to Audit
- `MainActivity.kt` — `AppState.LOADING → ONBOARDING → MAIN` flow
- `ProtectYourselfApp.kt` — `safeInit()` wrapper, crash handler
- `AppDatabaseCallback.kt` — `execSQL()` pre-population (no DAO deadlock)

---

## Phase 2: Accessibility Service

**Scope**: Accessibility service enables, stays connected, and receives events.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 2.1 | Open Accessibility Settings, find "Protect Yourself" | Listed as accessibility service | ⏳ |
| 2.2 | Toggle ON | System shows "Use accessibility" confirmation | ⏳ |
| 2.3 | Return to app | Red banner disappears; logcat shows "Accessibility service connected" | ⏳ |
| 2.4 | Open Chrome, type "porn" in URL bar | Block screen appears with "Your porn blocker switch is on" | ⏳ |
| 2.5 | Tap Close on block screen | Block screen dismisses, returns to home screen | ⏳ |
| 2.6 | Kill accessibility service from system settings | App shows red banner again within 30s | ⏳ |
| 2.7 | Toggle accessibility OFF then ON | Service reconnects, blocking resumes | ⏳ |

### Validation UI
- Block screen: shows app logo + dynamic message + Close button
- If countdown is set: Close button shows "Close (30)" counting down
- Block count increments (visible in "Block screens" card on home tab)

### Files to Audit
- `MyAccessibilityService.kt` — `onAccessibilityEvent()`, `refreshBlockingConfig()`, `launchBlockActivity()`
- `PornBlockActivity.kt` — block screen lifecycle, countdown, rating prompt
- `AccessibilityGuard.kt` — 30s polling, `isAccessibilityServiceEnabled()`

---

## Phase 3: Content Blocking (Switches & Keywords)

**Scope**: All content blocking switches work, keyword matching functions, block triggers fire.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 3.1 | Toggle "Porn blocker" ON | Switch animates to ON; toast "Porn blocker enabled" | ⏳ |
| 3.2 | Browse to a URL containing "porn" | Block screen appears | ⏳ |
| 3.3 | Toggle "Porn blocker" OFF | Switch animates to OFF; URL no longer blocked | ⏳ |
| 3.4 | Toggle "Block all websites" ON | Every URL in browser triggers block | ⏳ |
| 3.5 | Add "test" to whitelist keywords, browse to URL with "test" | NOT blocked (whitelist overrides) | ⏳ |
| 3.6 | Toggle "SafeSearch" ON | Google search URL gets blocked if `safe=active` missing | ⏳ |
| 3.7 | Toggle "Block image/video search" ON | Google Images search (`tbm=isch`) triggers block | ⏳ |
| 3.8 | Toggle "Block unsupported browsers" ON | Non-whitelisted browsers get blocked on launch | ⏳ |
| 3.9 | Toggle "Make any browser supported" ON | Can add any browser to supported list | ⏳ |
| 3.10 | Kill app, reopen | All switch states persist (loaded from DB) | ⏳ |

### Validation UI
- Each switch: orange when ON, gray when OFF
- After toggle: toast "X enabled" / "X disabled"
- If accessibility not enabled: switch toggles but red banner remains + toast "Enable accessibility for blocking to work"

### Files to Audit
- `BlockerPageViewModel.kt` — `toggleSwitch()`, `loadSwitchValue()`
- `MyAccessibilityService.kt` — `handleUrlDetected()`, `handleWindowStateChange()`
- `BlockerPageUtils.kt` — `isDetectWord()`, `isSafeUrl()`, `isImageVideoUrl()`
- `SwitchStatusValues.kt` — all 30+ switch getters

---

## Phase 4: Social Media Blocking

**Scope**: YT Shorts, IG Reels, WhatsApp Status, Snapchat, Telegram blocking works.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 4.1 | Toggle "Block YouTube Shorts" ON, open YT Shorts | Block screen appears | ⏳ |
| 4.2 | Toggle "Block Instagram Reels" ON, open IG Reels | Block screen appears | ⏳ |
| 4.3 | Toggle "Block WhatsApp Status" ON, open WA Status tab | Block screen appears | ⏳ |
| 4.4 | Toggle "Block Snapchat Stories" ON, open SC Stories | Block screen appears | ⏳ |
| 4.5 | Toggle "Block Snapchat Spotlight" ON, open SC Spotlight | Block screen appears | ⏳ |
| 4.6 | Toggle "Block Telegram Search" ON, search in Telegram | Block screen appears | ⏳ |
| 4.7 | Toggle "Block YouTube Search" ON, search in YT | Block screen appears | ⏳ |
| 4.8 | Toggle "Block Instagram Search" ON, search in IG | Block screen appears | ⏳ |
| 4.9 | Toggle all OFF | None of the above trigger block | ⏳ |

### Validation UI
- Same switch feedback as Phase 3
- Block screen message changes per feature (e.g. "You have enabled youtube shorts blocking")

### Files to Audit
- `MyAccessibilityService.kt` — `handleSocialMediaBlocking()`, `handleSocialMediaSearch()`
- `BlockerPageUtils.kt` — `BROWSER_URL_VIEW_IDS`, `IN_APP_BROWSER_CLASS_NAMES`

---

## Phase 5: VPN & DNS Blocking

**Scope**: VPN starts/stops, DNS filtering works, per-app whitelist functions.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 5.1 | Toggle "VPN" ON | System VPN permission dialog appears | ⏳ |
| 5.2 | Tap "Allow" on VPN dialog | VPN starts; foreground notification "Protect Yourself VPN active" | ⏳ |
| 5.3 | Check system VPN status | Key icon in status bar; VPN listed in settings | ⏳ |
| 5.4 | Browse to adult site | DNS blocks resolution (site doesn't load) | ⏳ |
| 5.5 | Toggle "VPN" OFF | VPN stops; notification disappears; key icon gone | ⏳ |
| 5.6 | Tap "VPN notification message" → Edit dialog | Text field appears with current message | ⏳ |
| 5.7 | Enter custom message, tap Save | Toast "Saved"; action label changes to "Custom" | ⏳ |
| 5.8 | Toggle "Hide VPN notification content" ON | Notification shows app name only (no content) | ⏳ |
| 5.9 | Tap "Whitelist VPN apps" → SelectAppPage | App picker opens; can select apps to bypass VPN | ⏳ |

### Validation UI
- VPN switch: when toggling ON, show "Requesting VPN permission..." spinner
- If permission denied: switch stays OFF + toast "VPN permission denied"
- Edit dialog: Save button disabled if text is empty
- Custom message label: "Default" → "Custom" after saving

### Files to Audit
- `MyVpnService.kt` — `startVpn()`, `stopVpn()`, `buildNotification()`
- `BlockerPageViewModel.kt` — `RequestVpnPermission`, `StopVpn` navigation
- `BlockerPageHome.kt` — `vpnPermissionLauncher`, `ActivityResultContracts`

---

## Phase 6: Stop Me (Focus Mode)

**Scope**: Instant + scheduled sessions work, whitelist apps, session counter, widget.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 6.1 | Tap Stop Me widget on home screen | Instant 25min session starts; widget shows "Stop Me (24:59)" | ⏳ |
| 6.2 | Open non-whitelisted app during session | Block screen "Your focus mode session is running" | ⏳ |
| 6.3 | Open whitelisted app during session | App opens normally | ⏳ |
| 6.4 | Wait for session to end (or tap stop in notification) | Session ends; session count increments | ⏳ |
| 6.5 | Check "Stop Me" tab → session count | Shows updated count (e.g. "You've completed 3 sessions") | ⏳ |

### Validation UI
- Widget: orange button, shows countdown timer when active
- Notification: "Focus mode active — Time remaining: 24:59"
- Block screen during Stop Me: specific message + Close button

### Files to Audit
- `StopMeManager.kt` — `startInstantSession()`, `stopActiveSession()`, `calculateNextTrigger()`
- `StopMeAlarmReceiver.kt` — alarm handling
- `StopMeWidget.kt` — `RemoteViews` rendering, click handling
- `MyAccessibilityService.kt` — `isStopMeRunning` flag, `setStopMeRunning()`

---

## Phase 7: Streak Tracking

**Scope**: Streak count, relapse recording, achievements, history, widget.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 7.1 | Open Streak tab | Fire animation plays; day count shows (e.g. "0 days") | ⏳ |
| 7.2 | Tap "Record relapse" | Dialog opens with 7 relapse types + note field | ⏳ |
| 7.3 | Select "Urge", enter note, tap Record | Dialog closes; relapse count increments; day count resets | ⏳ |
| 7.4 | Check achievements section | Shows unlocked badges (e.g. "First Day" if day count ≥ 1) | ⏳ |
| 7.5 | Check history section | Shows recent relapse with date + type + note | ⏳ |
| 7.6 | Check streak widget | Shows current day count | ⏳ |

### Validation UI
- Fire animation: Lottie `streak_fire.json` plays in loop with pulsing scale
- Relapse dialog: radio buttons for types, text field for note, Record/Cancel buttons
- Achievement unlock: `twinkle_crown.json` animation plays on first unlock

### Files to Audit
- `StreakPageViewModel.kt` — `calculateCurrentStreak()`, `recordRelapse()`
- `StreakPage.kt` — Lottie animation, achievement grid, history list
- `StreakWidget.kt` — `RemoteViews` with day count
- `StreakDatesDao.kt` — `observeAll()`, `countActiveStreakDays()`

---

## Phase 8: App Lock (PIN/Password/Pattern/Biometric)

**Scope**: Lock setup, lock screen on launch, biometric, forgot password.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 8.1 | Tap "App lock" in settings → setup page opens | Shows lock type selector (PIN/Password/Pattern) | ⏳ |
| 8.2 | Select "PIN", enter "1234", tap Next | Confirm screen appears | ⏳ |
| 8.3 | Re-enter "1234", tap "Confirm & Save" | Toast "App lock set successfully"; returns to settings | ⏳ |
| 8.4 | Kill app, reopen | Lock screen appears with PIN pad (4 dots) | ⏳ |
| 8.5 | Enter "1234" | App unlocks; main screen appears | ⏳ |
| 8.6 | Enter wrong PIN "9999" | Error "Incorrect. Attempt #1"; input clears | ⏳ |
| 8.7 | Toggle "Touch ID" ON in App Lock setup | Biometric prompt appears on next launch | ⏳ |
| 8.8 | Use fingerprint to unlock | App unlocks without PIN entry | ⏳ |
| 8.9 | Toggle "Disable Forgot Password" ON | "Forgot password?" link hidden on lock screen | ⏳ |
| 8.10 | Tap "Disable App Lock" in setup | Lock disabled; app opens without lock screen | ⏳ |

### Validation UI
- PIN pad: 4 dots fill as digits entered; auto-unlock on 4th digit
- Pattern grid: dots turn orange when selected; auto-unlock at 4+ dots
- Password field: masked input, "Unlock" button enabled at 6+ chars
- Biometric: auto-launches if Touch ID enabled; fallback to manual entry
- Error: red text below input, attempt counter

### Files to Audit
- `AppLockManager.kt` — `setLock()`, `verify()`, `disableLock()`, PBKDF2 hashing
- `AppLockSetupPage.kt` — lock type selector, enter/confirm flow
- `AppLockScreen.kt` — PIN pad, password field, pattern grid, biometric
- `MainActivity.kt` — `AppState.LOCKED` check

---

## Phase 9: Protective Modes

**Scope**: Long Sentence, Time Delay, Real Friend, request history.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 9.1 | Toggle "Long Sentence" ON | Switch ON; "Long Sentence custom message" action becomes enabled | ⏳ |
| 9.2 | Tap "Long Sentence custom message" → Edit dialog | Shows current message (default: "I will not give in to my urges") | ⏳ |
| 9.3 | Change message, tap Save | Toast "Saved"; label changes to "Custom" | ⏳ |
| 9.4 | Toggle "Time Delay" ON | Switch ON; "Time Delay duration" action becomes enabled | ⏳ |
| 9.5 | Tap "Time Delay duration" → number dialog | Shows current value (default: 30) | ⏳ |
| 9.6 | Change to 60, tap Save | Toast "Saved"; label changes to "60s" | ⏳ |

### Validation UI
- Edit dialogs: AlertDialog with text/number field, Save/Cancel buttons
- Number dialog: validation (min-max range), error message if invalid
- Action labels update dynamically after save

### Files to Audit
- `BlockerPageViewModel.kt` — `saveTextField()`, `saveNumberField()`, `EditTextField`, `EditNumberField`
- `BlockerPageHome.kt` — `EditTextDialog`, `EditNumberDialog` composables
- `SwitchStatusValues.kt` — `getLongSentenceCustomMessage()`, `getTimeDelayCustomDurationSeconds()`

---

## Phase 10: Anti-Uninstall & Advanced Settings

**Scope**: Device Admin, prevent uninstall, block notification drawer, block recent apps, block phone reboot, block settings pages.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 10.1 | Toggle "Prevent uninstall" ON | Switch ON; accessibility guards app info page | ⏳ |
| 10.2 | Try to uninstall app from settings | Block screen appears; can't reach uninstall button | ⏳ |
| 10.3 | Toggle "Block notification drawer" ON | Swiping down from top → block screen / home action | ⏳ |
| 10.4 | Toggle "Block Recent Apps" ON | Tapping recent apps button → block screen / home action | ⏳ |
| 10.5 | Toggle "Block phone reboot" ON | After reboot, app auto-restarts blocking | ⏳ |
| 10.6 | Toggle "Block unsupported browsers" ON | Non-whitelisted browsers blocked on launch | ⏳ |
| 10.7 | Toggle "Block new install apps" ON | Install new app → it's auto-blocked | ⏳ |
| 10.8 | Toggle "Block in-app browsers" ON | In-app WebView in other apps → block screen | ⏳ |
| 10.9 | Tap "Blocked screen image" → Choose | Image picker opens (or placeholder page) | ⏳ |
| 10.10 | Tap "Blocked screen message" → Edit | Text dialog opens, save custom message | ⏳ |
| 10.11 | Tap "Blocked screen countdown" → Edit | Number dialog (3-300s), save countdown | ⏳ |
| 10.12 | Tap "Custom redirect URL" → Edit | Text dialog opens, save URL | ⏳ |

### Validation UI
- All edit dialogs show current value + Save/Cancel
- Number dialog validates range (3-300) with inline error
- Action labels update: "Off" → "30s", "Default" → "Custom", "None" → "Set"
- Block screen shows custom message + countdown + redirect URL when configured

### Files to Audit
- `MyAccessibilityService.kt` — `isNotificationDrawer()`, `isRecentApps()`, `isSettingsPage()`
- `DeviceAdminUtils.kt` — `MyDeviceAdminReceiver`
- `AccessibilityGuard.kt` — self-heal, notification alert
- `BlockerPageViewModel.kt` — all action handlers in `onActionClick()`
- `BlockerPageHome.kt` — all dialog handlers in `LaunchedEffect`

---

## Phase 11: App Picker (SelectAppPage)

**Scope**: App list loads, search works, selection toggles + persists.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 11.1 | Tap "Blocklist apps" → Manage | SelectAppPage opens with all installed apps | ⏳ |
| 11.2 | Type "chrome" in search field | List filters to show only Chrome | ⏳ |
| 11.3 | Tap Chrome app row | Checkmark appears; app added to blocklist | ⏳ |
| 11.4 | Tap Chrome again | Checkmark disappears; app removed from blocklist | ⏳ |
| 11.5 | Tap Back | Returns to settings page | ⏳ |
| 11.6 | Open Chrome | Block screen appears (if Porn Blocker is ON) | ⏳ |
| 11.7 | Tap "Supported browsers" → Manage | Shows preset browsers (Chrome, Firefox, etc.) pre-selected | ⏳ |

### Validation UI
- Search field: real-time filtering as user types
- App row: checkmark icon (orange) when selected, absent when not
- Loading: spinner while app list loads
- Error: if PackageManager fails, show error message

### Files to Audit
- `SelectAppPageViewModel.kt` — `loadApps()`, `searchApp()`, `toggleAppSelection()`
- `SelectAppPage.kt` — `AppRow` composable, search field
- `PackageManagerProvider.kt` — singleton initialization

---

## Phase 12: Profile, About & Notifications

**Scope**: Profile page actions, About page, daily report notification, widgets.

### Test Cases
| # | Action | Expected Result | Status |
|---|---|---|---|
| 12.1 | Open Profile tab | Shows app version + 7 profile items | ⏳ |
| 12.2 | Tap "Share app" | System share sheet opens | ⏳ |
| 12.3 | Tap "Contact us" | Email intent opens | ⏳ |
| 12.4 | Tap "Delete account" | Confirmation dialog appears | ⏳ |
| 12.5 | Tap "Delete" on dialog | Account deletion (stub — shows toast) | ⏳ |
| 12.6 | Open About tab | Shows app info, rebuild info, help links, credits | ⏳ |
| 12.7 | Wait 24h (or trigger worker manually) | Daily report notification appears | ⏳ |
| 12.8 | Add Stop Me widget to home screen | Widget appears with orange button | ⏳ |
| 12.9 | Add Streak widget to home screen | Widget appears with day count | ⏳ |

### Validation UI
- Profile: each item is a tappable card with title + subtitle
- About: organized in cards with orange section headers
- Delete dialog: red "Delete" button + "Cancel"
- Notification: shows "Blocks today: X. Current streak: Y days."

### Files to Audit
- `ProfilePage.kt` — share intent, email intent, delete dialog
- `AboutPage.kt` — all cards
- `NotificationHelper.kt` — `showDailyReportNotification()`
- `DailyReportWorker.kt` — periodic work
- `StopMeWidget.kt` + `StreakWidget.kt` — RemoteViews

---

## Execution Protocol

### For Each Phase:

1. **Investigate** (30 min)
   - Read all source files listed in "Files to Audit"
   - Check for null safety, missing error handling, unhandled edge cases
   - Look for TODO/FIXME comments indicating incomplete code

2. **Fix** (varies)
   - Apply fixes to source code
   - Add validation UI (toasts, error messages, loading states)
   - Add inline guidance for missing prerequisites

3. **Validate** (15 min)
   - Build debug APK
   - Test each test case manually
   - Fill in "Status" column: ✅ Pass / ❌ Fail / ⏳ Pending

4. **Verify** (15 min)
   - Build release APK
   - Re-test critical test cases
   - Commit + push to GitHub
   - Update this document with results

### Status Legend
| Symbol | Meaning |
|---|---|
| ⏳ | Not yet tested |
| ✅ | Test passed |
| ❌ | Test failed — needs fix |
| 🔧 | Fixed, awaiting re-test |

---

## UI/UX Validation Checklist

Every screen must have:

- [ ] **Loading state**: Spinner while async data loads
- [ ] **Empty state**: Friendly message when no data (e.g. "No relapses yet — keep going!")
- [ ] **Error state**: Red error message with actionable guidance
- [ ] **Success feedback**: Toast or snackbar after successful action
- [ ] **Disabled state**: Buttons disabled when input is invalid
- [ ] **Prerequisite check**: If accessibility not enabled, show banner + disable blocking switches
- [ ] **Persistent state**: All toggle states survive app restart (loaded from Room DB)
- [ ] **Navigation back**: Every sub-page has a visible Back button
- [ ] **Touch targets**: All cards/buttons at least 48dp tall
- [ ] **Color contrast**: Text readable against background (WCAG AA)

---

## Phase Dependencies

```
Phase 1 (Launch) → Phase 2 (Accessibility) → Phase 3 (Content Blocking)
                                              ↓
                                           Phase 4 (Social Media)
                                              ↓
                                           Phase 5 (VPN)
                                              ↓
                                           Phase 6 (Stop Me)
                                              ↓
                                           Phase 7 (Streak)
                                              ↓
                                           Phase 8 (App Lock)
                                              ↓
                                           Phase 9 (Protective Modes)
                                              ↓
                                           Phase 10 (Anti-Uninstall + Advanced)
                                              ↓
                                           Phase 11 (App Picker)
                                              ↓
                                           Phase 12 (Profile + Notifications)
```

**Rule**: A phase cannot start until the previous phase has all test cases marked ✅ or 🔧.

---

## Summary

| Phase | Feature | Test Cases | Est. Time | Status |
|---|---|---|---|---|
| 1 | App Launch & Onboarding | 6 | 1h | ⏳ |
| 2 | Accessibility Service | 7 | 2h | ⏳ |
| 3 | Content Blocking | 10 | 3h | ⏳ |
| 4 | Social Media Blocking | 9 | 2h | ⏳ |
| 5 | VPN & DNS Blocking | 9 | 2h | ⏳ |
| 6 | Stop Me (Focus Mode) | 5 | 2h | ⏳ |
| 7 | Streak Tracking | 6 | 2h | ⏳ |
| 8 | App Lock | 10 | 3h | ⏳ |
| 9 | Protective Modes | 6 | 1h | ⏳ |
| 10 | Anti-Uninstall & Advanced | 12 | 3h | ⏳ |
| 11 | App Picker | 7 | 1h | ⏳ |
| 12 | Profile & Notifications | 9 | 2h | ⏳ |
| **Total** | | **96 test cases** | **~24h** | |
