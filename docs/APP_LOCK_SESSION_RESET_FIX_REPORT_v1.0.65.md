# App Lock — Password Field Not Reset on App Return (LOCKSESSION-01/02/03)

**Reported**: after enabling App Lock, switching to another app and
returning shows the password input "already containing input" — the user
must manually clear the field before entering their password.
**Fixed in**: v1.0.65 (branch `fix/vpn-boot-restore-v1.0.63`).

---

## 1. Root cause

`AppLockViewModel` is obtained via `viewModel()` inside the `AppLockScreen`
composable with no key, which scopes it to the **host Activity's
ViewModelStore**. Switching to another app STOPs the Activity but does not
destroy it, so the ViewModel (and its `AppLockState`) survives.

`AppLockState` carries the interactive session data — `input`, `error`,
`attempts`, `isUnlocked`, `isLockedOut`/countdown, `triggerShake` — and
**nothing reset it when the lock re-engaged**:

- On **successful unlock**, `isUnlocked=true` was set but `input` kept the
  **full plaintext credential** in the retained state (PIN/password/pattern).
- On **return to the app**, `MainActivity.onResume() → checkAppState()`
  flips `MAIN → LOCKED`, `AppLockScreen` recomposes with the retained
  ViewModel → the OutlinedTextField / PIN dots / pattern dots render
  `state.input` — the field appears pre-filled with the last password
  (reported bug). Partial input left mid-typing (*) also persisted.

The state architecture ("all three input UIs render `state.input` directly")
means one retained-state root cause explains every manifestation.

### Confirmed secondary symptoms of the same root cause

1. **Hard-lock for PIN/pattern users (latent, severe).** Stale
   `isUnlocked=true` made the guards `if (input.length >= 4) return`
   (PIN pad) and `if (... || state.isUnlocked) return@clickable`
   (pattern dots) ignore ALL further taps — after re-locking, a PIN user
   whose leftover input was 4 digits (their previous PIN) or ANY pattern
   user could not enter a credential at all until the app was killed.
2. **Biometric auto-prompt never re-fired on re-lock.** The auto-launch
   `LaunchedEffect(touchIdEnabled, lockType)` evaluated its
   `!state.isUnlocked` guard against the stale `true` and skipped; only the
   first lock after process start offered the prompt.
3. **Stale lockout countdown/error** visible on re-entry until the 500 ms
   ticker refreshed it.
4. **Plaintext credential in retained memory** — the password sat in the
   retained ViewModel long after it was needed (memory-hygiene issue).

Same pattern in `AppLockSetupViewModel` (LOCKSESSION-03): backgrounding the
app mid-setup left `setupStep=CONFIRM` + `firstEntry` pre-filled, resuming
setup at a confirmation screen for a credential the user no longer sees.

## 2. Fix

New session-begin APIs on the ViewModels, invoked at every engagement, with
redundant layers so **no single missed hook can ever show stale input**:

| Layer | Change |
|---|---|
| `AppLockViewModel.beginLockSession()` | **New.** Clears `input`, `error`, `attempts`, stale `isUnlocked`, `triggerShake`; immediately re-syncs lockout countdown; reloads lock type / Touch ID / forgot-password config. |
| `AppLockViewModel.onForegroundReturn()` | **New.** Light reset (input + error + lockout refresh) used when the app returns to the foreground *while already on the lock screen* (composition never disposed, so the entry effect doesn't re-fire). Guards against disturbing a just-completed unlock. |
| `AppLockScreen` | `LaunchedEffect(Unit) { beginLockSession() }` — fires on **every** composition entry = every lock engagement (the screen leaves composition while the app is unlocked); plus a `LifecycleEventObserver` (ON_RESUME → `onForegroundReturn()`); biometric auto-launch effect now also keyed on `state.isUnlocked` so the prompt re-fires when a new session resets stale `isUnlocked`. |
| `MainActivity.checkAppState()` | Host-side **pre-reset**: before flipping to `AppState.LOCKED`, resolves the same (default-keyed) ViewModel via `ViewModelProvider` and calls `beginLockSession()` — so not even a single frame renders stale input. Non-fatal + idempotent (composable effect is the invariant backstop). |
| Success paths (`tryUnlock`, `tryUnlockWithInput`, `biometricUnlock`) | Set `input = ""` together with `isUnlocked = true` — plaintext never lingers in retained state after use. |
| `AppLockSetupViewModel.beginSetupSession()` + `AppLockSetupPage` entry effect | **New.** Fresh setup session (selector step, empty entry fields, config reload) on every page entry (LOCKSESSION-03). |

### Why this is consistent across Android versions

The mechanism is pure ViewModel/Compose-state + the standard
`Lifecycle.Event.ON_RESUME` contract (unchanged since API 1) — no
version-dependent behavior anywhere, so it works identically on
minSdk 26 through targetSdk 35, in all Activity-retention modes
(retained, destroyed, process-death → fresh ViewModel defaults to empty).

## 3. Behavior after fix

| Scenario | Before | After |
|---|---|---|
| Unlock → switch app → return | Field shows previous password/pattern | Field empty, ready to type |
| Type 2 digits → home → return | 2 dots remain | Field empty |
| Pattern user re-lock | **All dots dead — hard-locked** | Fresh session, entry works |
| PIN user with 4-digit leftover | **Pad dead — hard-locked** | Fresh session, entry works |
| Touch ID on re-lock | Prompt never re-offered | Prompt re-offered (guarded once per session) |
| Mid-setup background → return | Lands on stale CONFIRM step | Restarts at lock-type selector |
| While locked out (5 min) → return | Stale countdown briefly | Countdown synced instantly |
| Memory hygiene | Plaintext lingered in VM | Cleared on unlock + session begin |

## 4. Tests (8 new — `AppLockSessionResetTest`)

326/326 suite total pins: partial-input clearing, plaintext wiped on
successful unlock, **post-unlock session accepts entry again** (hard-lock
regression), foreground-return reset (both on-screen and don't-disturb-
unlock cases), config reload at session begin, immediate lockout sync,
mid-setup progress cleared (LOCKSESSION-03).

## 5. Verification

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`
  → **BUILD SUCCESSFUL**, **326/326 tests pass**.
- `apksigner verify` → v2 OK; `aapt2 dump badging` → versionCode `65`,
  `1.0.65`, minSdk 26, targetSdk 35.
