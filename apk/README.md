# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.75 (versionCode 75) — PU-gated protection of the Accessibility-Service page and system VPN settings

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.75-release.apk` | **~3.28 MB** | Release | **Recommended for installation.** Closes the two remaining single-switch off-ramps while **Prevent Uninstall** is enabled. **(1) PU-A11Y-PAGE-01 — own Accessibility-Service settings page.** Previously a user could open Settings → Accessibility → Protect Yourself and toggle the service OFF even while Prevent Uninstall was on. Blocking that page is uniquely dangerous: since v1.0.70 we know the OS **auto-disables any accessibility service whose window covers a11y-management screens** (~1–5 s kill). So this protection **evicts, never covers**: when the service detects its OWN detail page while PU is on, it presses `GLOBAL_ACTION_HOME` (throttled 1.5 s), keeps the v1.0.69 self-heal armed as backstop, and shows an explanation toast 700 ms later (after the Settings window is gone — zero windows over the page, ever). Detection is precise: a **detail-only text fingerprint** derived from the service description-minus-summary distinguishes our detail page from the accessibility services LIST, which stays reachable so other apps' services can still be managed; the node-tree probe is throttled (400 ms) to keep event handling within the v1.0.71 ANR budget; and while the service is off (fresh enable / OEM kill) no events flow at all, so enabling is never obstructed. **(2) PU-VPN-01 — system VPN settings page.** The VPN settings screen (Settings → Network & internet → VPN, also reachable by Quick Settings long-press — same activity) is where our VPN can be disconnected/forgotten or always-on toggled. It now gets the standard PU block screen (new message: "Prevent Uninstall is on. VPN settings are blocked to keep DNS protection active."). Covering it is safe — the OS kill-switch only protects a11y screens — and matching is **class-name only** so the Network overview page (whose text mentions "VPN") stays reachable. Both protections are gated strictly on the Prevent-Uninstall switch and on other apps' windows; with PU off, behavior is exactly v1.0.74. Carries v1.0.74 (OEM autostart row + VPN foreground reconcile), v1.0.73 and all earlier rounds. See `docs/PU_SETTINGS_PROTECTION_REPORT_v1.0.75.md`. **491/491 tests pass.** |
| `protect.yourself-v1.0.75-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.75)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** (R8 minified; 3,275,061 bytes), built BEFORE the test run per the release-first process
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (21,296,415 bytes)
- `./gradlew :app:testDebugUnitTest` → **491/491 tests pass, 0 failures, 0 errors, 0 skipped** (40 suites; +12 vs v1.0.75 baseline: new `PuSettingsProtectionWiringTest` 7, `ProtectedSystemScreensTest` 11→16)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `75`, versionName `1.0.75`, minSdk 26, targetSdk 35
- Post-R8 mapping check → `evictFromOurA11yServicePage`, `showPuEvictionToast`, `ProtectedSystemScreens.isVpnSettingsScreen`, `detailOnlyFingerprint` present in the release APK

## New tests (12 in v1.0.75, all passing)

`features/blockerPage/service/PuSettingsProtectionWiringTest` (7; static pins):
eviction check runs BEFORE the A11Y-KILL-01 early-return and only under `isPreventUninstallOn`; eviction uses `GLOBAL_ACTION_HOME` and **never `launchBlockActivity`** on the a11y page (with self-heal as backstop + probe/kick throttles); the toast is delayed past the HOME transition (main looper) and throttled 20 s; detail detection uses the detail-only fingerprint with node-probe fallback and the SubSettings/a11y-context scope restriction; the VPN screen is blocked inside the PU gate via the standard block flow with the new message key; both helpers exist with pinned semantics; both new strings exist in resources.

`ProtectedSystemScreensTest` (+5, now 16):
VPN screen positive matrix (AOSP `$VpnSettingsActivity`, `network.vpn` host, legacy `vpn2`, MIUI/vivo variants); rejections (NetworkDashboard stays reachable, accessibility pages, non-settings packages, blank class, our own in-app VPN page); detail fingerprint matches full-description text, **never the list summary**, matches inside a surrounding page, and degrades gracefully (junk → empty = fail-open; edited-translation fallback).

## Manual device checklist (post-install)

1. Enable accessibility + VPN/"DNS" + Prevent Uninstall. Open Settings → Accessibility → Protect Yourself → you are returned to the home screen (~instant) and a toast explains the block; the service stays ON (no 1–5 s self-disable — nothing ever covers the page).
2. With PU on: open Settings → Network & internet → VPN (and/or long-press the Quick Settings VPN tile) → PU block screen appears ("VPN settings are blocked…"), Close returns home. Confirm the Network overview page itself still opens normally.
3. With PU on: Settings → Accessibility (services list) still opens — only OUR detail page evicts.
4. Disable Prevent Uninstall → both pages open normally again (PU-off behavior unchanged). Regression sweep: normal PU app-info blocking, app uninstall blocking, and all blocking/VPN/schedules/focus/lock behavior unchanged.

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
