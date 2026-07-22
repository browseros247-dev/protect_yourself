# Block Screen Reliability + Default Countdown Timer — Analysis & Fix Report (v1.0.68)

**Branch:** `fix/block-screen-default-timer-v1.0.68` (branched from `main` @ `d17bb5f` = merged PR #2, i.e. contains all v1.0.66–v1.0.67 work)
**Date:** 2026-07-22
**Reported issues:**
1. "No default countdown timer is configured. The application should use a default countdown timer of three seconds whenever a custom value has not been set."
2. "The block screen is unreliable… does not consistently appear. In some cases it does not appear at all, and in others, it appears but does not function correctly."

---

## 1. Executive Summary

**Issue 1 (TIMER-DEFAULT-01)** — `SwitchStatusValues.getBlockScreenCountDownSeconds()`
returned `0` when the stored row was missing. Since **no code path in the entire app
ever persists a custom value** (grep-verified: no setter call, no `AppDatabaseCallback`
seed, no settings UI), the countdown was effectively always disabled: the Activity
block screen took the instant-close branch forever. Fixed at the single accessor
funnel with a **3s default** (valid customs 1–300s honored; zero/negative/unparsable/
above-max all fall back to the default).

**Issue 2 (BLOCK-SCREEN-01..04)** — four distinct root causes, two of them capable of
producing the exact reported symptom "does not appear at all":

| ID | Severity | Root cause (verified in code) | Reported symptom it produces |
|---|---|---|---|
| BLOCK-SCREEN-01 | **Critical** | `BlockOverlayManager`'s single-flight `AtomicBoolean` latch was released **only** by the Close button / `onDestroy`. Real paths that remove the window without `hideBlockOverlay()` — overlay-permission revoked while up, accessibility-service unbind/rebind in the same process (system drops the dead service's windows), a failed `removeView` — left the latch `true` forever. Every later block attempt hit `overlay already showing — skipping` and **nothing appeared again until process death**. | "does not appear at all" (sticky/permanent after first occurrence) |
| BLOCK-SCREEN-02 | **Critical** | Activity fallback: `startActivity()` from a background accessibility-service process can be **silently dropped** on API 29+ by background-activity-launch restrictions — no exception, so the code assumed success and never escalated. Symptom is OEM/permission-state dependent. | "in some cases it does not appear at all" |
| BLOCK-SCREEN-03 | **High** | `PornBlockActivity` is `singleTop`; a second block while an old instance lived reused it with **stale extras** (old package/message/keyword; possibly a dead countdown) because `onNewIntent` was never overridden. | "appears but does not function correctly" |
| BLOCK-SCREEN-04 | **Medium** | Fallback storm-throttle used wall clock (`currentTimeMillis`) — NTP/manual time changes could freeze it (silent block drops) or open a storm; overlay close button was immediate-only (no countdown), inconsistent with the Activity path, and a failed countdown read could theoretically leave the close button unwired. | inconsistent appearance/behavior |

---

## 2. Exact behavior before → after

### 2.1 Countdown (Issue 1)

| Stored value (`BLOCK_SCREEN_COUNT_DOWN_TIME_SET`) | Before | After |
|---|---|---|
| row missing (always, in practice) | **0 → instant close** | **3s default** |
| `"7"` | 7s | 7s |
| `"0"` / `"-5"` / `"abc"` / `"301"` | 0 / −5 / 0→(asInt null→0) / 301 | **3s default (invalid)** |
| `"1"` / `"300"` | 1s / 300s | 1s / 300s |
| Overlay close button | always instant | **same countdown as Activity (3s default)** |

New contract lives in one place (`SwitchStatusValues` companion): `DEFAULT_BLOCK_SCREEN_COUNTDOWN_SECONDS = 3`, valid window `MIN=1`, `MAX=300`. Invalid stored values are logged (Timber.w) for diagnostics.

### 2.2 Block-screen reliability (Issue 2)

- **Stuck-latch self-heal (BLOCK-SCREEN-01):** when the latch is set, the manager now verifies `overlayView != null && overlayView.isAttachedToWindow && windowManager != null`. Only then is it a genuine "already showing" skip; otherwise it cancels the stale kill timer, clears refs, logs a Timber warning + CrashLogger breadcrumb (`stuck latch self-heal`), and proceeds to show the overlay again.
- **Fallback visibility verification (BLOCK-SCREEN-02):** after the fallback `startActivity`, a 900ms deferred check (`FALLBACK_VERIFY_DELAY_MS`) reads `PornBlockActivity.isShowing` (new companion `AtomicBoolean`, set in `onCreate`/cleared in `onDestroy`) and `BlockOverlayManager.isShowing()`. If neither is visible, it escalates: retry the overlay path (permission may have been granted meanwhile), else last-resort `GLOBAL_ACTION_HOME`, each step logged + breadcrumbed.
- **Stale-extras rebind (BLOCK-SCREEN-03):** `PornBlockActivity.onNewIntent()` now `setIntent()`, cancels the stale countdown, resets dynamic views (motivation image, rating container, why-toggle, close button/listener) and re-runs `configureBlockScreen()` against the new extras.
- **Monotonic throttle (BLOCK-SCREEN-04):** `SystemClock.elapsedRealtime()`.
- **Overlay countdown parity (TIMER-DEFAULT-01):** overlay close button now starts disabled/dimmed, reads the same getter off-thread, shows `Close (n)` ticks (ceil to 3-2-1), and wires the listener only after the dwell — with a **fail-safe:** any failure along the way wires immediate close instead of leaving a dead button (overlay must never lock the screen without an exit).
- Cancel hygiene: `hideBlockOverlay()` cancels the close countdown (stale ticks can never post into a detached view or a *new* overlay's button).

## 3. Per-path scenario matrix

| Scenario | Path | Behavior after fix |
|---|---|---|
| Fresh block, overlay permission granted | Overlay | Overlay shows, kill-timer HOME×5→BACK kills offender, close enabled after 3s default dwell |
| Overlay permission NOT granted | Activity fallback | Activity shown (verified visually after 900ms; escalate if BAL-dropped), close enabled after 3s dwell |
| Permission revoked WHILE overlay is up | Self-heal | System drops window; next block attempt detects stale latch → re-shows (activity fallback if permission truly gone), never permanent black hole |
| Accessibility service rebinds mid-overlay | Self-heal + `isShowing()` | New manager instance shows overlay; old latch state irrelevant |
| Second block while block screen alive (singleTop) | `onNewIntent` rebind | Screen re-binds to the new package/message/keyword; countdown re-arms |
| Rapid block storm (>3/s) | Global 300ms throttle (monotonic) | Storm suppressed without freezing under clock changes |
| DB read failure during close-button setup | Fail-safe | Immediate close wired — button never dead |
| User closes before kill timer finishes | Cancel hygiene | Kill timer + countdown cancelled; no stray HOME/BACK/tick afterwards |
| Screen-off / locked states | Unchanged-by-design | Block triggers require accessibility window events (screen-on context); manifest `showOnLockScreen` retained |

## 4. Per-setting review (block-screen subsystem)

| Setting | Storage/UI | Status |
|---|---|---|
| Close countdown | `switch_status/BLOCK_SCREEN_COUNT_DOWN_TIME_SET`; **no setter exists** → default-only in practice; future UI will store into the same row and be honored via the valid window | ✅ Fixed (3s default) |
| Custom block message | `BLOCK_SCREEN_CUSTOM_MESSAGE` (+ `_SET` flag; cleared via `clearBlockScreenCustomMessage`) | ✅ Works both paths (overlay applies async, Activity async); unchanged |
| Motivation image | `BLOCK_SCREEN_STORE_IMAGE_PATH`; `BlockScreenImageLoader.decodeWithReason` (content://-safe; failure toasts) | ✅ Unchanged; verified both paths apply it |
| Redirect URL on close | `BLOCK_SCREEN_REDIRECT_URL` (+ `_SET`) | ✅ Activity-only by design; unchanged |
| Overlay permission nudge | `showOverlayPermissionNotification` (24h throttle) | ✅ Unchanged; now also gated by OB-PERM-05 `areNotificationsEnabled` |
| Kill timer (HOME×5 + BACK×1 @500ms) | Reference-parity implementation (OV-02/THREAD-01) | ✅ Verified unchanged |

## 5. UI/UX, networking, stability evaluation

- **UI/UX:** countdown now identical on both block paths (3-2-1 tick labels, dimmed/disabled during dwell); "Why am I seeing this?" preserved; stale-content class eliminated (onNewIntent).
- **Networking:** untouched (no block-path network I/O).
- **Stability:** every new async step is try/catch with Timber + CrashLogger breadcrumbs; fail-safes prefer *showing something* or *wiring close* over silent lock states; no new crash surfaces (all window ops already main-thread/posted).
- **R8 compatibility:** new code is direct-reference only (no reflection); post-build dex check confirmed `BlockOverlayManager`/`PornBlockActivity`/`DEFAULT_BLOCK_SCREEN_*` present; manifest component `PornBlockActivity` (singleTop, non-exported, showOnLockScreen) intact — 3.3 MB release size preserved.

## 6. Testing & verification

### New tests — `BlockScreenReliabilityTest` (10 cases, Robolectric, real Room DB)
Pins: default=3 companion; unset→3; 0→3; −5→3; `"abc"`→3; 301→3; custom 7; boundary 1 & 300; delete-custom→3 fallback; `PornBlockActivity.isShowing` default false (visibility-flag contract).

### Full suite + builds (release built & validated BEFORE commit, per process)
- `:app:testDebugUnitTest` → **358/358 pass** (24 suites, 0 failures/errors/skipped) — all 348 prior tests green.
- `:app:assembleRelease` → BUILD SUCCESSFUL (R8 on; size held at ~3.3 MB) — verified by `apksigner` + badging `68/1.0.68` (minSdk 26, targetSdk 35) + dex symbol checks **before** any commit; `:app:assembleDebug` → SUCCESSFUL.

### Manual on-device checklist (no emulator in sandbox)
1. Fresh install, grant accessibility + overlay: trigger a block (keyword URL) → overlay appears instantly; Close shows "Close (3)…(1)" then enables; underlying app is gone after close.
2. Revoke overlay permission mid-block (Settings) → trigger again → activity fallback appears (or overlay re-appears after re-grant) — no permanent suppression (BLOCK-SCREEN-01 manual verification of self-heal logcat `stuck latch self-heal`).
3. Rapid-fire two different blocked apps → second block rebinds the same screen with fresh message/keyword (no stale text).
4. Block with screen rotations/lock → back gesture swallowed; predictive back (API 34) swallowed.
5. Change device clock forward/backward mid-use → block storm throttling unaffected (monotonic).
6. `adb shell settings delete secure enabled_accessibility_services` re-enable → service reconnect → block path healthy (LC-class regression check).

## 7. Regression watchlist (verified unchanged)

- Blocking decision logic (keyword/URL/title/package matchers) — untouched; only the *presentation* layer and timer accessor changed.
- Kill-timer cadence (reference parity) and OV-01/OV-02/THREAD-01 history — untouched.
- OB-PERM onboarding (v1.0.66), PERF R8 (v1.0.67), VPN/App-Lock fixes (v1.0.63–65) — untouched; suite green.
- `SwitchStatusValues` other getters — untouched; new companion constants additive only.
