# Protective Mode Deep Analysis — Protect-Yourself (protective-mode-fixes branch)

> **Branch**: `protective-mode-fixes` (off `main` at commit `3154d6d`)
> **Analysis date**: 2026-07-11
> **Scope**: Every Protective Mode setting — Time Delay, Real Friend, Daily Report, Suggest, Request History, Stop Me, Streak, App Lock, Long Sentence — evaluated individually against the decompiled NopoX v1.0.53 reference.
> **Methodology**: Static source review + JADX decompilation of NopoX v1.0.53 (21,067 Java files) + line-by-line comparison of TimeDelayTriggerCheckWorker, StreakPageViewModel, AccountabilityPartnerTypeIdentifiers, and the BlockerPageViewModel toggleSwitch logic.

---

## Executive Summary

The Protective Mode subsystem manages 9 features: Time Delay, Real Friend, Daily Report, Suggest, Request History, Stop Me, Streak, App Lock, and Long Sentence (always-on, no UI). The features are wired into the settings UI and the DB schema is correct, but several features are **incomplete or non-functional**:

- **PM-01 (Critical)**: Time Delay is a boolean flag with no actual delay enforcement. When enabled, the user should have to wait N seconds before a switch toggle takes effect. Currently, enabling Time Delay does nothing — all switches toggle instantly regardless. NopoX has a dedicated `TimeDelayTriggerCheckWorker` that enforces the delay.
- **PM-02 (High)**: Real Friend stores the partner's email but never sends it. There's no backend to email the partner or track approval. The user enters an email and nothing happens.
- **PM-03 (High)**: `toggleSwitch` mutual-exclusion logic sets other protective modes to `false` in the UI state BEFORE calling `loadSettingItems()`, causing a visual flicker where all three modes briefly appear OFF.
- **PM-04 (Medium)**: `AppDataCheckWorker` has 3 unimplemented TODOs: streak date rollover, Stop Me schedule check, and accessibility re-apply. Only DB integrity checks are done.
- **PM-05 (Medium)**: `SubPage.RequestHistory` and `SubPage.Faq` are placeholder stubs (`SimpleSubPage`).
- **PM-06 (Medium)**: Two parallel `AppLockType` enums with identical values.
- **PM-07 (Low)**: `StopMeWidget` hardcodes 25-minute sessions.
- **PM-08 (Low)**: Long Sentence is always-on with no enforcement — `LONG_SENTENCE_MESSAGE_SET` is force-set to `true` but no code reads it.
- **PM-09 (Low)**: `calculateConsecutiveStreak` is duplicated in `StreakPageViewModel` and `StreakWidget`.
- **PM-10 (Low)**: Orphaned strings in `strings.xml` for features that don't exist in the rebuild.

---

## 1. Setting PM1 — Time Delay

### 1.1 What it does (intended)

When Time Delay is enabled as the protective mode, the user must wait N seconds (default 30, configurable 1–300) before a switch toggle takes effect. This gives the user time to reconsider disabling a protective setting during an urge.

### 1.2 What it actually does (current implementation)

**Nothing.** The `TIME_DELAY_DURATION_SET` switch is toggled on/off, `ACCOUNTABILITY_PARTNER_TYPE` is set to `2L` (TIME_DELAY), and `TIME_DELAY_CUSTOM_DURATION` stores the duration. But no code reads these values to enforce a delay. The `toggleSwitch` method toggles switches instantly regardless of whether Time Delay is active.

### 1.3 NopoX comparison

NopoX has a dedicated `TimeDelayTriggerCheckWorker` (51 lines, decompiled) that:
1. Receives a `requestId` as input data.
2. Launches a coroutine that (presumably) waits for the delay period, then checks if the request is still pending and approves it.

The rebuild has no equivalent. This is the biggest functional gap in the Protective Mode subsystem.

### 1.4 Fix (PM-01)

Implement Time Delay enforcement in the UI: when the user toggles a switch while Time Delay is active, show a countdown dialog ("Please wait N seconds...") that blocks the toggle until the countdown completes. This is simpler than NopoX's WorkManager approach (which is designed for background delays) and more appropriate for an in-app toggle.

---

## 2. Setting PM2 — Real Friend

### 2.1 What it does (intended)

When Real Friend is enabled, the user must get their accountability partner's approval (via email) before disabling a protective switch. The partner receives an email with an approve/deny link.

### 2.2 What it actually does

The user enters the partner's email (stored in `REAL_FRIEND_EMAIL`), `ACCOUNTABILITY_PARTNER_TYPE` is set to `3L`, and `REAL_FRIEND_VISIBLE` is set to `true`. But the email is never sent — there's no email-sending code, no backend, no approval tracking. The `REAL_FRIEND_EMAIL` value is stored in the DB and never read again.

### 2.3 NopoX comparison

NopoX has Firebase Firestore backend for accountability partner requests. The rebuild removed Firebase entirely. This is a known, accepted gap — implementing a full backend is out of scope.

### 2.4 Fix (PM-02)

Since there's no backend, the best we can do is:
1. When the user enables Real Friend, open the email app with a pre-filled email to the partner explaining the feature.
2. Label the setting clearly as "requires your friend to manually respond" so the user understands the limitation.

---

## 3. Setting PM3 — Daily Report

### 3.1 What it does

Shows a daily notification with block count + streak days. Implemented via `DailyReportWorker` (WorkManager periodic, every 24h). Also checks Stop Me schedules + accessibility service state.

### 3.2 Status

**Working.** The worker is correctly scheduled and fires daily. The notification shows the correct information.

### 3.3 Issues

None. This is one of the few Protective Mode features that works correctly.

---

## 4. Setting PM4 — Suggest protective mode

### 4.1 What it does

Opens a `mailto:` link to `support@protectyourself.app` with subject "Suggest Protect Yourself protective mode".

### 4.2 Status

**Working.** Simple and correct.

---

## 5. Setting PM5 — Request history

### 5.1 What it does (intended)

Shows pending + past protective mode requests (e.g. "Time Delay request approved on 2024-01-15").

### 5.2 What it actually does

**Stub.** `SubPage.RequestHistory` renders `SimpleSubPage("Request History")` — a placeholder that says "This feature is being implemented."

### 5.3 Fix (PM-05)

Either implement a basic request history page (showing recent switch toggles from the DB's `switch_status` history), or remove the setting from the UI until it's ready.

---

## 6. Setting PM6 — Stop Me (focus mode)

### 6.1 Status

**Mostly working.** Instant sessions, scheduled sessions, alarm receiver, widget, and accessibility integration are all implemented. The AB-01 fix (persisted `stopMeEndTime`) ensures sessions survive process death.

### 6.2 Issues

- **PM-07**: Widget hardcodes 25-minute sessions — should use last-used duration.
- The scheduled session UI in `StopMePage.kt` shows scheduled sessions but has no "Add Schedule" button — the user can only start instant sessions from the page (scheduled sessions can only be created via... nowhere? The `ScheduledSessionCard` has a delete button but no add button is visible).

---

## 7. Setting PM7 — Streak

### 7.1 Status

**Working.** Consecutive streak calculation, relapse recording, achievements, widget — all implemented and functional.

### 7.2 Issues

- **PM-09**: `calculateConsecutiveStreak` is duplicated in `StreakPageViewModel` and `StreakWidget`.

---

## 8. Setting PM8 — App Lock

### 8.1 Status

**Working.** PIN/Password/Pattern lock, biometric (Touch ID), forgot password disable, PBKDF2 hashing (100k iterations), constant-time comparison, Compose race condition fix.

### 8.2 Issues

- **PM-06**: Two parallel `AppLockType` enums.

---

## 9. Setting PM9 — Long Sentence

### 9.1 What it does (intended)

When Long Sentence is the active protective mode, the user must type a long sentence (e.g. "I will not give in to my urges") before a switch toggle takes effect.

### 9.2 What it actually does

**Nothing.** `LONG_SENTENCE_MESSAGE_SET` is force-set to `true` in every `toggleSwitch` branch, but no code reads it to enforce the sentence typing. The `LONG_SENTENCE_CUSTOM_MESSAGE` stores the sentence text but it's never displayed.

### 9.3 Fix

Since this feature was intentionally removed from the UI ("always ON in background"), the correct fix is to either:
1. Implement it (show a dialog requiring the user to type the sentence before toggling), or
2. Remove the switch key entirely and stop force-setting it.

Given that the feature was intentionally removed, option 2 is cleaner. But the user might want it back, so keeping the key but adding a comment is the safest approach.

---

## 10. Summary of issues by severity

| ID | Severity | Setting | Summary |
|----|----------|---------|---------|
| PM-01 | Critical | Time Delay | No actual delay enforcement — switch is a no-op |
| PM-02 | High | Real Friend | Email stored but never sent — no backend |
| PM-03 | High | (all) | toggleSwitch mutual-exclusion causes UI flicker |
| PM-04 | Medium | AppDataCheckWorker | 3 TODOs: streak rollover, Stop Me check, accessibility re-apply |
| PM-05 | Medium | Request History | Stub placeholder |
| PM-06 | Medium | App Lock | Two parallel AppLockType enums |
| PM-07 | Low | Stop Me | Widget hardcodes 25-min session |
| PM-08 | Low | Long Sentence | Always-on with no enforcement |
| PM-09 | Low | Streak | Duplicated calculateConsecutiveStreak |
| PM-10 | Low | (strings) | Orphaned strings for non-existent features |

---

## 11. Recommended priority order for fixes

1. **PM-01** (Time Delay enforcement) — biggest functional gap
2. **PM-03** (toggleSwitch UI flicker) — quick fix, visible improvement
3. **PM-02** (Real Friend email intent) — quick fix, sets correct expectations
4. **PM-04** (AppDataCheckWorker TODOs) — streak rollover is important for data integrity
5. **PM-07** (StopMeWidget duration) — quick fix
6. **PM-09** (deduplicate streak calculation) — code quality
7. **PM-06** (unify AppLockType enums) — code quality
8. **PM-05** (Request History stub) — either implement or remove
9. **PM-08** (Long Sentence) — keep as-is with better documentation
10. **PM-10** (orphaned strings) — cleanup
