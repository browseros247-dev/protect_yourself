# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.70 (versionCode 70) — A11y self-disable fix + transparent-activity block screen + Close button fix

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.70-release.apk` | **~3.46 MB** | Release | **Recommended for installation.** Three reported issues fixed: **(1) Accessibility service auto-disables 1–5 s after manual enable** (A11Y-KILL-01): the app's own block engine was killing it — with Prevent Uninstallation ON, the unsafe-page checks matched the **settings a11y-management screens hosting our own service page** (AOSP `SubSettings` hosts the a11y-service detail page; keywords incl. "permissions", plus a dedicated check matching our `accessibility_service_description`) ⇒ block UI drawn over an a11y-management surface ⇒ Android's consent-integrity/anti-tapjacking protection force-disabled the covering service within ~1–5 s (the Prevent-Uninstall correlation the user suspected was **real**). A new `ProtectedSystemScreens` policy now makes the service **return early** on any a11y-management screen (with a 10 s throttled self-heal trigger instead), the fingerprint-based "Check 4" is deleted, and App-Info blocking exempts our own service page — **anti-uninstall itself (App-Info blocking, uninstaller dialog, Device Admin) is fully preserved**. **(2) Block screen no longer needs "Display over other apps"** (ACTIVITY-BLOCK-01): the WindowManager overlay path (`BlockOverlayManager`, `SYSTEM_ALERT_WINDOW`) is **deleted** and replaced by a single-path **transparent activity** (`Theme.TransparentBlock`: translucent, no dim, no preview, no animation), with launch-verify @900 ms → one retry → HOME as last resort; the settings row, navigation branch, and overlay-permission notification were removed, and the permission is gone from the merged manifest. **(3) Block-screen Close button unresponsive** (CLOSE-BTN-01): the button was armed asynchronously after a second DB round-trip inside the countdown callback — a new `CloseGatePolicy` installs the listener **synchronously once**, taps during the 3 s dwell now show a **"Close available in N s" toast**, setup failures fail-open (never trap the user), and closing honors a redirect URL or lands on Home. Carries v1.0.69 a11y persistence + dark-mode contrast, v1.0.68 block-screen reliability, v1.0.67 R8 size optimization, v1.0.66 onboarding permissions. See `docs/A11Y_KILL_TRANSPARENT_BLOCK_REPORT_v1.0.70.md`. 402/402 tests pass. |
| `protect.yourself-v1.0.70-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.70)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** in 7 m 43 s (R8 minified, 3,460,311 bytes; size held at ~3.46 MB)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (21,279,699 bytes)
- `./gradlew :app:testDebugUnitTest` → **402/402 tests pass, 0 failures, 0 errors, 0 skipped** (29 suites; +27 new: 11 `ProtectedSystemScreensTest` + 7 `CloseGatePolicyTest` + 9 `OverlayDependencyRemovedTest`; run AFTER the builds, per release-first process)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `70`, versionName `1.0.70`, minSdk 26, targetSdk 35
- Merged manifest (`aapt2` inspection of the BUILT apk): **`SYSTEM_ALERT_WINDOW` ABSENT**; all other permissions unchanged; `PornBlockActivity` theme `@0x7f12027c` = `style/Theme.TransparentBlock`, `launchMode=singleTop`, `excludeFromRecents=true`, `screenOrientation=portrait`, `showOnLockScreen=true`
- Post-R8 dex check (mapping-verified): `ProtectedSystemScreens` (`pageTextMatchesOurService`, `isAccessibilityManagementScreen`), `CloseGatePolicy` (incl. `Click$Blocked`/`Click$Close`), `maybeTriggerSelfHealOnA11yScreen`, `tryLaunchBlockScreen` all present in the release APK (renamed consistently); **`BlockOverlayManager` = 0 references**; `string/block_screen_close_available_in` + `style/Theme.TransparentBlock` (day & night qualifiers) present in resources; a11y service manifest block intact

## New tests (27 in v1.0.70, all passing)

`features/protectedApps/ProtectedSystemScreensTest` (11) — A11Y-KILL-01 policy pins:

| Test | What it pins |
|---|---|
| `protected - AOSP accessibility manage screens` | `Settings > Accessibility` is guarded (never blocked) |
| `protected - OEM a11y screens in settings and security packages` | Samsung/Xiaomi a11y class variants guarded |
| `protected - installed-services and service-details marker classes` | The exact toggle-hosting screens guarded |
| `not protected - App-Info page stays blockable (prevent-uninstall core feature)` | **Anti-uninstall core NOT regressed** |
| `not protected - uninstaller dialog stays blockable` | Uninstaller still blocked |
| `not protected - device admin management stays blockable (anti-uninstall feature)` | Anti-tamper still blocked |
| `not protected - blank class, non-settings package, ordinary content apps` | No false positives on normal apps |
| `service page fingerprint - matches normalized page text, tolerates truncation` | Our service page fingerprint (40-char prefix) |
| `service page fingerprint - rejects unrelated text and junk inputs` | null/blank/garbage safe |
| `settings package detection - AOSP + OEM variants + substring rule` | `isSettingsPackage` parity with old list |
| `normalize - lowercases and strips spaces` | Fingerprint normalization |

`features/blockerPage/ui/CloseGatePolicyTest` (7) — CLOSE-BTN-01 gate pins:

| Test | What it pins |
|---|---|
| `zero dwell is armed immediately` | 0 s countdown closes instantly |
| `dwell boundary - blocked just before, closing exactly at the boundary` | Exact 3 s boundary semantics |
| `clicks during dwell report remaining whole seconds (ceil)` | Toast matches countdown label |
| `remainingDwellMs never goes negative` | No negative countdowns |
| `clock skew backwards clamps to positive remaining rather than arming early` | Monotonic/time-jump safety |
| `repeated clicks during dwell stay consistent (no state corruption)` | Pure state machine |
| `negative dwell degrades to immediate close (never traps the user)` | Fail-open guarantee |

`features/blockScreen/OverlayDependencyRemovedTest` (9) — ACTIVITY-BLOCK-01 structural pins (scan production sources/manifest/resources):

| Test | What it pins |
|---|---|
| `manifest no longer declares SYSTEM_ALERT_WINDOW` | Permission removal locked |
| `BlockOverlayManager file is gone` | Deletion locked |
| `no TYPE_APPLICATION_OVERLAY usage remains in production sources` | No resurrected overlay path |
| `service has no overlay references and goes straight to the activity block screen` | Single-path launch |
| `service guards block decisions with ProtectedSystemScreens` | A11Y-KILL-01 early guard present |
| `PornBlockActivity uses the transparent block theme` | Manifest theme reference |
| `transparent block theme is translucent in day and night resources` | Both `values/` and `values-night/` |
| `no overlay-permission prompts remain in settings UI or notifications` | Settings row + notification gone |
| `close gate is synchronous and never unarms (CLOSE-BTN-01)` | Listener installed once, never nulled |

## Manual device checklist (post-install)

1. **A11y persistence (the reported bug)**: enable the accessibility service → stay on its detail page 60 s → it stays ON (previously it flipped OFF within 1–5 s). Repeat with **Prevent Uninstallation ON**.
2. Browse `Settings > Accessibility` + the installed-services list while blocking is active → no block screen, service stays ON; removing our entry via `adb shell settings put secure enabled_accessibility_services …` gets repaired within ≤ 30 s (v1.0.69 guard still active).
3. Open a blocked app → block screen appears instantly (transparent window, no flicker) with **no** "Display over other apps" prompt anywhere in the flow.
4. Close button: tap within 3 s → toast "Close available in N s"; after countdown → closes to Home; with a redirect URL configured → browser opens.
5. Trigger the block repeatedly → a single block-screen instance (singleTop), correctly dismissed each time.
6. **Anti-uninstall regression**: with Prevent Uninstallation ON, try uninstalling from App-Info / uninstaller dialog / Device Admin → still blocked.
7. Blocker settings → **no** "Display pop-up window permission" row; no overlay-permission notification after boot/update.
8. Dark Mode: block screen readable and instant in dark theme (Theme.TransparentBlock exists in `values-night`).

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
