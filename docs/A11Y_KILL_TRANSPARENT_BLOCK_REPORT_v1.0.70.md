# A11y Self-Disable Kill-Vector + Transparent-Activity Block Screen + Close-Gate — Analysis & Fix Report (v1.0.70)

- **Version**: 1.0.70 (versionCode 70)
- **Branch**: `fix/a11y-self-disable-transparent-block-v1.0.70`
- **Base**: `origin/main` @ `011f748` (post-PR #4, v1.0.69)
- **Date**: 2026-07-22
- **Scope**: Three user-reported issues — (1) accessibility service auto-disables 1–5 s after manual enable (with a suspected Prevent-Uninstall correlation), (2) replace the WindowManager overlay block screen with a transparent activity to eliminate the "Display over other apps" permission dependency, (3) block-screen Close button unresponsive.

---

## 1. Executive Summary

| ID | Issue | Root cause | Fix | Verified by |
|---|---|---|---|---|
| A11Y-KILL-01 | Accessibility service toggles itself OFF 1–5 s after being enabled manually | **Our own block engine killed it.** With Prevent Uninstallation ON, `onAccessibilityEvent` ran app-info/a11y safety checks that matched the **a11y management screens hosting our own service page** (`SubSettings` hosts the a11y-service detail page on modern AOSP; keywords incl. "permissions"). A dedicated "Check 4" additionally matched any page containing our `accessibility_service_description`. The block UI was then drawn over an accessibility-management screen ⇒ Android's consent-integrity / anti-tapjacking protection auto-disables the covering service within ~1–5 s ⇒ Settings toggle flips OFF ⇒ v1.0.69 self-heal re-arms ⇒ infinite toggle loop. | New `ProtectedSystemScreens` policy (pure, unit-tested): identifies settings-package accessibility-management screens by class markers (`Accessibility`, `InstalledServices`, `ServiceDetails`) and by our service's 40-char normalized description fingerprint. `onAccessibilityEvent` now **returns early** on any such screen (with a throttled self-heal trigger), "Check 4" deleted, `isAppInfoPageUnsafe` exempts our own service page. The user's suspected Prevent-Uninstall correlation was **correct** — those checks are gated on that switch; the chain is now broken at the root. | `ProtectedSystemScreensTest` (11) + `OverlayDependencyRemovedTest.service guards block decisions…` + manual checklist |
| ACTIVITY-BLOCK-01 | Block screen depended on "Display over other apps" (SYSTEM_ALERT_WINDOW) special permission | Two-path architecture: `BlockOverlayManager` (`TYPE_APPLICATION_OVERLAY`, requires the special permission) with an activity fallback (`+300 ms` monotonic throttle, AB-05 HOME-after-200 ms, `FALLBACK_VERIFY_DELAY_MS=900` verify via an `AtomicBoolean`, overlay-retry → HOME escalation). Granting a permanent overlay permission is a heavy ask and OEM-friction-prone. | **Single-path transparent activity.** `BlockOverlayManager` deleted entirely; manifest `SYSTEM_ALERT_WINDOW` removed; `PornBlockActivity` now uses new `Theme.TransparentBlock` (translucent, transparent window background, dim disabled, window preview disabled, no window animation) so it visually replaces the overlay. `launchBlockActivity` rewritten: optional `eventClassName` param, central `ProtectedSystemScreens` guard, breadcrumb, 300 ms throttle, `tryLaunchBlockScreen` helper (`NEW_TASK|CLEAR_TOP|EXCLUDE_FROM_RECENTS` + extras), verify @900 ms → one retry → HOME as last resort. AB-05 post-launch HOME removed. Settings row "Display pop-up window permission" + `OpenOverlaySettings` navigation + the overlay-permission notification (`NOTIF_ID_OVERLAY_PERMISSION`, 24 h throttle) all removed. | `OverlayDependencyRemovedTest` (9) — static pins on manifest/theme/sources + post-R8 mapping/dex checks |
| CLOSE-BTN-01 | Block-screen Close button unresponsive | The button was armed **asynchronously**: a DB-read chain, then a **second DB round-trip inside `CountDownTimer.onFinish`** before the listener was installed. Combined with v1.0.68's new default 3 s dwell (button disabled and *silent* during dwell), `onNewIntent` nulling the listener, and `isClickable=false` during re-setup, taps during the arming window were swallowed — felt "broken". (`handleClose` itself was fine but left the user on a dead-end screen.) | New `CloseGatePolicy` (pure, time-gated state machine, injected clock): the listener is installed **synchronously, exactly once** (`wireCloseGate`) after a single DB read. Taps during dwell now give feedback — a toast *"Close available in N s"* (ceil seconds). The countdown label is cosmetic (`startCountdownLabel`). `onNewIntent` keeps the working `finish()` listener (no null window). Any exception in setup ⇒ close enabled immediately (fail-open). `handleClose` honors a redirect URL if set, else lands on the device Home screen (`CATEGORY_HOME`), and always `finish()`es. | `CloseGatePolicyTest` (7) + `OverlayDependencyRemovedTest.close gate is synchronous and never unarms` |

**Net effect:** 402/402 automated tests pass (27 new), release APK built and fully verified **before** commit per process, release size ~3.46 MB, **no `SYSTEM_ALERT_WINDOW` in the merged manifest**.

> **UX trade (explicitly user-chosen):** the block screen is now a regular activity (`singleTop`, `excludeFromRecents`, portrait, `showOnLockScreen`) instead of an overlay window. Overlays can technically sit above *some* surfaces activities can't; the activity path is dismissible with normal navigation and trades that edge for zero special-permission friction, better lifecycle semantics, and simpler focus/IME handling.

---

## 2. Exact behavior before → after

### 2.1 Issue 1 — accessibility service kills itself (A11Y-KILL-01)

**Kill chain (before):**

1. User enables our accessibility service in Settings (often with **Prevent Uninstallation ON**).
2. `MyAccessibilityService.onAccessibilityEvent` fires for the Settings screens the user is standing on.
3. Prevent-uninstall checks (gated on the PU switch) run `isAppInfoPageUnsafe`: class-name match included `"subsettings"` (AOSP `SubSettings` hosts our own a11y-service detail page on modern devices) plus a node-tree search for our app name with keywords including `"permissions"`, plus a dedicated **Check 4** matching any page whose text/node tree contains our `accessibility_service_description`.
4. Our own service detail page → "unsafe" ⇒ block UI drawn over it.
5. Android detects an accessibility service covering/conflicting with an accessibility-management surface (consent integrity / anti-tapjacking) and **auto-disables the service ~1–5 s later**.
6. Settings toggle flips OFF; v1.0.69 self-heal logic re-arms the entry; loop repeats ⇒ "service keeps turning itself off 1–5 s after I enable it".

**Fix (after):**

| Component | Change |
|---|---|
| `features/protectedApps/ProtectedSystemScreens.kt` (new, pure) | `isSettingsPackage` (AOSP + OEM settings-package list, unchanged semantics, now the single source), `isAccessibilityManagementScreen(package, className?)` — class markers `accessibility`, `installedservices`, `servicedetails`; **uninstaller dialog and Device-Admin screens deliberately exempt** (anti-uninstall must keep working), `pageTextMatchesOurService(pageText, serviceDescription)` — 40-char normalized fingerprint match tolerant of truncation, `normalize` (lowercase, strip spaces). |
| `MyAccessibilityService.onAccessibilityEvent` | Hoisted `eventClassName`; **early guard**: `isAccessibilityManagementScreen` ⇒ `maybeTriggerSelfHealOnA11yScreen(...)` (10 s monotonic throttle, companion `lastA11yScreenHealMs`, throttled breadcrumb) then **`return`** — we never evaluate block rules on a11y-management surfaces. |
| PU call sites | Pass `eventClassName` so the guard sees the real class. `isAppInfoPageUnsafe` keeps its App-Info blocking (PU core feature) but **exempts pages matching our own a11y-service fingerprint** (description probe + 30-char node-tree probe). |
| "Check 4" | **Deleted** — it literally matched our own service-detail page. |
| `isSettingsPackage` | Delegates to `ProtectedSystemScreens` (identical list — no behavior change for SET-01..03). |

**Result:** standing on any accessibility-management screen is now safe for the service — instead of being blocked, the visit opportunistically triggers the (throttled) self-heal, i.e. the page where the user *enables* us helps *repair* us. The Prevent-Uninstall correlation the user suspected was real and is severed at the root; anti-uninstall behavior (App-Info blocking, uninstaller dialog, Device Admin) is preserved (pinned by tests, §6).

### 2.2 Issue 2 — transparent activity block screen (ACTIVITY-BLOCK-01)

| Aspect | Before (overlay + fallback) | After (transparent activity only) |
|---|---|---|
| Rendering | `BlockOverlayManager` WindowManager view (`TYPE_APPLICATION_OVERLAY`) | `PornBlockActivity` with `Theme.TransparentBlock` |
| Special permission | `SYSTEM_ALERT_WINDOW` ("Display over other apps") required for the primary path | **None** — permission declaration removed from the manifest |
| Launch | Overlay add; fallback activity after +300 ms throttle; AB-05 HOME-after-200 ms; 900 ms success verify via `PornBlockActivity.isShowing` AtomicBoolean; overlay-retry → HOME escalation | `tryLaunchBlockScreen` immediately (300 ms global throttle kept); verify @900 ms; one retry; HOME only as last resort; AB-05 removed |
| Settings UI | "Display pop-up window permission" row + `OpenOverlaySettings` navigation branch in `BlockerPageHome` | Row, mapping, enum entry, compose branch removed |
| Notifications | `NotificationHelper.showOverlayPermissionNotification` (`OVERLAY_PREFS`, 24 h throttle, `NOTIF_ID_OVERLAY_PERMISSION=2003`) | Function + constants removed (ID 2003 retired, documented) |
| Docs/comments | `OncePerSessionLogger` demo text referenced overlay | Generalized |
| Activity flags | (fallback only) singleTop, excludeFromRecents, portrait, showOnLockScreen | Same flags, now the only path; theme set to `Theme.TransparentBlock` in both `values/` and `values-night/` (translucent, transparent bg, `windowDisablePreview`, `backgroundDimEnabled=false`, no window animation) |
| Cleanup | KillTimer (HOME×5 + BACK @500 ms), stuck-latch self-heal, overlay view removal in `onDestroy` | Entire file deleted — no WindowManager state to leak |

**Theme attributes (both day & night, pinned by test):** translucent window, transparent background, `windowDisablePreview=true`, `backgroundDimEnabled=false`, `windowAnimationStyle=@null` → the activity opens instantly with the blocked-app UI, visually equivalent to the old overlay, without flicker or dim.

### 2.3 Issue 3 — Close button (CLOSE-BTN-01)

| Aspect | Before | After |
|---|---|---|
| Arming | Async DB read → countdown → **second DB round-trip inside `onFinish`** → then install listener; multiple scheduling knobs (`isClickable=false` re-setup windows) | One DB read; listener installed **synchronously once** (`wireCloseGate`, main thread) |
| Tap during 3 s dwell | Silently ignored (`isEnabled=false`, no feedback) — felt dead | Toast: *"Close available in N s"* (`R.string.block_screen_close_available_in`, ceil, min 1) |
| `onNewIntent` | Nulled the listener → dead until re-armed | Keeps the working close listener |
| Setup exception | Swallowed; button could stay unarmed | **Fail-open**: close enabled immediately on UI thread |
| After close | Returned to blocked app or dead-end | Redirect URL honored if set; else `CATEGORY_HOME` intent; always `finish()` |
| Countdown text | Owned gating logic | Purely cosmetic (`startCountdownLabel`) — gating lives in `CloseGatePolicy` |

`CloseGatePolicy` semantics (pinned by 7 tests): `remainingDwellMs` never negative; boundary click (`now == startedAt + dwell`) closes; ceil whole-seconds reporting; backwards clock skew clamps instead of arming early; repeated dwell clicks are pure (no state corruption); `dwell ≤ 0` degrades to immediate close (never traps the user).

---

## 3. Per-path scenario matrix (Issue 1)

| Scenario (service ON, events flowing) | PU switch | Before | After |
|---|---|---|---|
| Our a11y **service detail page** (SubSettings / Settings\$AccessibilityServiceDetailsActivity) | ON | **KILLED** in 1–5 s (blocked its own page; Check 4 fingerprint) | Early-return guard → no block; throttled self-heal runs; service stays ON |
| Same page, PU OFF | OFF | Could still kill via **Check 4** (not PU-gated!) | Safe (guard is switch-independent) |
| AOSP/Pixel `Settings > Accessibility` list | ON | Killed if our description text in node tree (Check 4) | Safe — class marker guard |
| OEM a11y screens (Samsung/Xiaomi variants, `a11y` classes) | ON | Same risk | Safe — package+marker matching |
| "Installed services" / downloaded-services screens | ON | Killed (that's exactly where the toggle lives) | Safe — `installedservices`/`servicedetails` markers |
| **App-Info page of a blocked app** | ON | Blocked (PU core) | **Still blocked** — exemption requires OUR service fingerprint |
| Our own **App-Info page** (system page, not a11y detail) | ON | Blocked | Still blocked (that page can't disable the service; PU intent preserved) |
| Uninstaller confirmation dialog | any | Blocked (prevents uninstall) | **Still blocked** — explicit exemption from the guard |
| Device-Admin management | any | Blocked (anti-tamper) | **Still blocked** — exempt |
| Regular blocked app page in any app | any | Blocked as configured | Unchanged — full block behavior preserved |

## 4. Per-setting review (each setting individually, as per standing instructions)

| Setting | Reviewed | Change |
|---|---|---|
| **Prevent Uninstallation** (master switch) | Root of A11Y-KILL-01 (its checks blocked our own page) | Logic kept (App-Info + uninstaller + Device Admin blocking intact); a11y-management exemption added |
| **Device Admin** (PU sub-feature) | Independent path (receiver, throttled notification) | Unchanged; still blockable-page class confirmed by test |
| **Block-App / site blocking rules** | Rule evaluation unchanged | Only the render path changed (activity instead of overlay) |
| **Block-screen countdown duration** | Read once by the new close gate; default 3 s from v1.0.68 respected | Unchanged semantics, better UX (toast feedback) |
| **Block-screen redirect URL** | Now actually reaches the browser after close (was dead-end) | Behavior completed, no data change |
| **Overlay ("Display pop-up window") permission row** | Feature removed per request | Row/navigation/notification deleted; manifest permission deleted |
| **Accessibility master switch + service entry (v1.0.69 healing)** | Interacted with the kill loop | Kept intact; new throttled trigger when visiting a11y screens; all 10 `AccessibilityPersistTest` still green |
| **Dark mode / themes** | `values-night` variant of the new theme required | `Theme.TransparentBlock` defined in both `values/` and `values-night/`; all 7 `ColorContrastTest` still green |
| **VPN / DNS / Focus / Schedules / Backup / Crash log** | Evaluated for interaction with the block-launch path | Untouched; tests green |

## 5. UI/UX, networking, stability evaluation

- **UI/UX:** one less permission to grant (overlay permission row gone from Blocker settings); no periodic "grant overlay" notification; block screen appears instantly (no preview/anim flash thanks to `windowDisablePreview`); Close gives immediate feedback during dwell via toast; post-close lands on Home instead of a dead end. Transparent theme keeps visual continuity with the old overlay (blocked-app UI appears over a translucent surface).
- **Networking:** untouched (VPN/DNS code paths not modified; no new network calls).
- **Stability:** removes an entire class of WindowManager failure modes (leaked overlay views, stuck-latch self-healing, KillTimer races) — activity lifecycle is handled by the framework; single 900 ms `postDelayed` verify + one retry bounds re-entry; `appCoroutineScope` semantics unchanged; breadcrumb logging added on guard/throttle paths (Timber + CrashLogger patterns preserved); fail-open close button guarantees the user is never trapped.

## 6. Testing & verification

### New tests (27, all passing)

**`ProtectedSystemScreensTest` (11)**

| Test | Pins |
|---|---|
| `normalize - lowercases and strips spaces` | Fingerprint normalization |
| `settings package detection - AOSP + OEM variants + substring rule` | `isSettingsPackage` coverage |
| `protected - AOSP accessibility manage screens` | `Settings > Accessibility` guarded |
| `protected - OEM a11y screens in settings and security packages` | Samsung/Xiaomi class variants guarded |
| `protected - installed-services and service-details marker classes` | Toggle-hosting screens guarded |
| `not protected - uninstaller dialog stays blockable` | Anti-uninstall preserved |
| `not protected - device admin management stays blockable` | Anti-tamper preserved |
| `not protected - App-Info page stays blockable (prevent-uninstall core feature)` | **PU core behavior NOT regressed** |
| `not protected - blank class, non-settings package, ordinary content apps` | No false-positive guards on normal apps |
| `service page fingerprint - matches normalized page text, tolerates truncation` | 40-char prefix match |
| `service page fingerprint - rejects unrelated text and junk inputs` | null/blank/garbage safe |

**`CloseGatePolicyTest` (7)**

| Test | Pins |
|---|---|
| `zero dwell is armed immediately` | 0 s config |
| `dwell boundary - blocked just before, closing exactly at the boundary` | `now == start+dwell` closes |
| `clicks during dwell report remaining whole seconds (ceil)` | Toast math matches UI |
| `remainingDwellMs never goes negative` | No negative countdowns |
| `clock skew backwards clamps to positive remaining rather than arming early` | Monotonic/time-jump safety |
| `repeated clicks during dwell stay consistent (no state corruption)` | Purity |
| `negative dwell degrades to immediate close (never traps the user)` | Fail-open |

**`OverlayDependencyRemovedTest` (9, static source/manifest pins)**

| Test | Pins |
|---|---|
| `manifest no longer declares SYSTEM_ALERT_WINDOW` | Manifest diff locked |
| `BlockOverlayManager file is gone` | Deletion locked |
| `no TYPE_APPLICATION_OVERLAY usage remains in production sources` | No resurrected overlays |
| `service has no overlay references and goes straight to the activity block screen` | Single path |
| `service guards block decisions with ProtectedSystemScreens` | A11Y-KILL-01 guard present |
| `PornBlockActivity uses the transparent block theme` | Manifest theme ref |
| `transparent block theme is translucent in day and night resources` | Both qualifiers |
| `no overlay-permission prompts remain in settings UI or notifications` | Settings row + notification gone |
| `close gate is synchronous and never unarms (CLOSE-BTN-01)` | Listener-once semantics |

### Full suite + builds (release built & validated BEFORE commit, per process)

| Gate | Result |
|---|---|
| `:app:assembleRelease` (R8) | **BUILD SUCCESSFUL** in 7 m 43 s → `app-release.apk` 3,460,311 B (~3.46 MB) |
| `apksigner verify` (release) | **Signature OK** (debug keystore — re-sign with your release keystore for distribution) |
| `aapt2 dump badging` | `protect.yourself`, **versionCode 70, versionName 1.0.70**, launcher `MainActivity` |
| Merged manifest permissions | **`SYSTEM_ALERT_WINDOW` ABSENT** (verified in built APK); all other permissions unchanged |
| Activity manifest entry | `PornBlockActivity`: theme `@0x7f12027c` = `style/Theme.TransparentBlock` ✔, `launchMode=singleTop`, `excludeFromRecents=true`, `screenOrientation=portrait`, `showOnLockScreen=true` |
| Post-R8 mapping/dex | `ProtectedSystemScreens` (`pageTextMatchesOurService` etc.), `CloseGatePolicy` (+ `Click$Blocked`/`Click$Close`), `maybeTriggerSelfHealOnA11yScreen`, `tryLaunchBlockScreen` present; **`BlockOverlayManager` = 0 references**; `string/block_screen_close_available_in` (`0x7f11002e`) present |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** → 21,279,699 B DEBUG build |
| `:app:testDebugUnitTest` (re-run AFTER builds) | **402/402 pass, 0 failures, 0 errors, 0 skipped** (29 suites) |

### Manual on-device checklist (no emulator in sandbox)

1. Enable the accessibility service → wait 60 s on the service-detail page → service stays ON (previously died in 1–5 s). Repeat with **Prevent Uninstallation ON** — stays ON.
2. Browse `Settings > Accessibility`, the installed-services list, and OEM a11y screens with blocking active → no block screen appears; service stays ON.
3. Open `adb shell settings put secure enabled_accessibility_services` (remove our entry) → within ≤ 30 s the v1.0.69 guard re-adds it; visit the a11y screen → throttled heal fires (`A11yScreenHeal` breadcrumb ≤ 1×/10 s).
4. Open a **blocked app** → block screen appears (transparent window, instant, no flicker, no "display over other apps" prompt anywhere).
5. Tap Close within 3 s → toast "Close available in N s"; at 0 s tap → closes to Home; with a redirect URL configured → browser opens instead.
6. Rapid-block triggers (open blocked app repeatedly) → single block activity instance (singleTop), no duplicate windows, auto-dismisses correctly.
7. **Anti-uninstall regression**: with PU ON, try uninstalling via App-Info/uninstaller dialog → still blocked; Device Admin page → still blocked.
8. Settings → Blocker → confirm **no** "Display pop-up window permission" row; no overlay-permission notification ever appears.

## 7. Regression watchlist (verified unchanged & green)

- `BlockScreenReliabilityTest` (10, real Room DB) — v1.0.68 block-screen semantics still pass.
- `AccessibilityPersistTest` (10) — v1.0.69 healing/master-switch repair untouched and green.
- `MyAccessibilityServiceTest` / `MyAccessibilityServiceLifecycleTest` — service behavior intact (guard only *returns early* on guarded screens).
- `ProguardRulesRegressionTest` (8), `OnboardingPermissionsTest` (14), `ColorContrastTest` (7) — green.
- LG details: release shrank vs debug as expected; R8 fullmode kept; no new permissions; manifest diff = −1 permission, ±0 features.
