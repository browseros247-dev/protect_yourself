# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.76 (versionCode 76) — Accessibility-overlay block surface (reference-grade PU settings protection; kill-proof by window type)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.76-release.apk` | **~3.28 MB** | Release | **Recommended for installation.** Ports the reverse-engineered NopoX 1.0.53 protection strategy (see `docs/NOPOX-derived` analysis at `/home/user/nopox-analysis/`, summarized in `docs/A11Y_OVERLAY_BLOCK_REPORT_v1.0.76.md`). **A11Y-OVL-01 — new block surface `A11yBlockOverlay`:** all PU settings protections are now drawn as a full-screen **`TYPE_ACCESSIBILITY_OVERLAY` (2032)** window owned by the accessibility service itself (`flags = FLAG_LAYOUT_IN_SCREEN | NOT_TOUCH_MODAL | NOT_FOCUSABLE` = 296, MATCH_PARENT × MATCH_PARENT, TRANSLUCENT, dialog animation) — Android's sanctioned accessibility drawing channel, and therefore **exempt from the obscuring/consent protection that auto-disabled our service in ~1–5 s whenever our old Activity block screen covered an a11y screen (the v1.0.70 incident; also matches the "5–15 s auto-disable" class)**. NopoX covers those exact pages with this window type and ships kills-free; every field-caused draw-over kill is now structurally impossible for the PU surfaces. **PU-A11Y-PAGE-01 (upgraded):** the own accessibility-service detail page is now **covered** (toggle unreachable, page visibly blocked, same CLOSE-BTN-01 close-gate + countdown + launcher landing as the activity block screen via shared `CloseGatePolicy` and `page_porn_block.xml`) instead of the v1.0.75 HOME eviction; eviction + toast survive only as the fallback when the overlay cannot be added, and that fallback is suppressed during the new **5 s service-connect cool-down** (`serviceConnectCoolDownUntilMs`, reference-equivalent `accessibilityServiceConnectCoolDownTime`). Precision unchanged: the detail-only fingerprint keeps the services list reachable, and no events flow while the service is off, so enabling/re-enabling is never obstructed. **PU-VPN-01 (upgraded):** the system VPN settings page now uses the same overlay (activity block screen as fallback). Teardown hygiene: the sticky singleton is cleared in `onUnbind`. All v1.0.75/74/73 layers (self-heal, guard observer, OEM autostart row, VPN foreground reconcile, PU gates, ANR budgets) are untouched. **493/493 tests pass.** See `docs/A11Y_OVERLAY_BLOCK_REPORT_v1.0.76.md`. |
| `protect.yourself-v1.0.76-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.76)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** (R8 minified; 3,275,229 bytes), built BEFORE the test run per the release-first process
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (21,296,523 bytes)
- `./gradlew :app:testDebugUnitTest` → **493/493 tests pass, 0 failures, 0 errors, 0 skipped** (40 suites; `PuSettingsProtectionWiringTest` expanded 7→9 for the overlay-first semantics; all other suites unchanged from v1.0.75)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `76`, versionName `1.0.76`, minSdk 26, targetSdk 35
- Post-R8 mapping check → `A11yBlockOverlay` (isShowing/wm/overlayView), `serviceConnectCoolDownUntilMs`, `evictFromOurA11yServicePage` present in the release APK

## Test changes (v1.0.76)

`features/blockerPage/service/PuSettingsProtectionWiringTest` (7→9, all passing):
- NEW `a11y page is covered by the overlay first - HOME eviction only as fallback` — pins `A11yBlockOverlay.show(...pu_blocked_a11y_page_message)` BEFORE any `performGlobalAction(GLOBAL_ACTION_HOME)` in the method, the fallback log marker, the cool-down gate, and the self-heal/throttles.
- NEW `connect cool-down exists and is armed on onServiceConnected` — `SERVICE_CONNECT_COOLDOWN_MS = 5_000L`, armed in `onServiceConnected`.
- NEW `overlay is the 2032 full-screen touchable sticky surface of the reference` — `TYPE_ACCESSIBILITY_OVERLAY`, `OVERLAY_FLAGS` composition, MATCH_PARENT, TRANSLUCENT, sticky re-show message swap, `CloseGatePolicy` + countdown-seconds reuse, `CATEGORY_HOME` close landing, **no app-level windows** (no `PornBlockActivity` reference) inside the overlay.
- NEW `vpn settings screen uses the overlay first with the PU activity block as fallback`; `eviction toast remains only in the fallback`; `overlay teardown hygiene - hidden on unbind`; strings suite extended to `pu_blocked_a11y_page_message`.

## Manual device checklist (post-install)

1. PU on → Settings → Accessibility → Protect Yourself: the screen is **covered** by the block overlay ("Prevent Uninstall is on. The accessibility settings…"); the toggle is unreachable. **The service must stay enabled — watch it for > 15 s and > 5 min (this exact scenario auto-disabled us pre-v1.0.76).** Close (after the countdown) lands on the launcher; re-entering the page covers it again.
2. PU on → Settings → Network & internet → VPN (and Quick Settings long-press): covered by the overlay with the VPN message; Network overview page still opens normally.
3. PU on → Settings → Accessibility (services list): opens normally; only OUR detail page is covered.
4. Disable PU → both pages open normally. Regression sweep: content blocking (PornBlockActivity path), normal PU app-info/uninstall blocking, VPN foreground reconcile, autostart onboarding row, schedules/focus/lock — unchanged.

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
