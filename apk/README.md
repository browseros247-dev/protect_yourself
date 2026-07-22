# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.72 (versionCode 72) — UI round: top-bar theme switcher, mandatory onboarding, Dark-Mode buttons, layout audit

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.72-release.apk` | **~3.27 MB** | Release | **Recommended for installation.** Four UI tasks, behavior preserved: **(1) Theme switcher moved out of the Profile card (THEME-SWITCH-01)**: the Profile-tab "Theme" radio card is gone; a compact icon button (sun/moon reflecting the effective brightness, full a11y label) now sits in the new top app bar, opening a dropdown with System Default / Dark / Light (leading icons + check mark on the current mode). Same `ThemePreferences` persistence, instant app-wide apply. **(2) Onboarding enforcement (OB-ENFORCE-01)**: the permissions step is no longer skippable — "Continue to App" stays disabled until every REQUIRED permission is granted (accessibility; notifications on API 33+; RECOMMENDED items stay optional). The launch gate (`checkAppState`) now re-evaluates required permissions even when terms were accepted earlier, and re-opens onboarding DIRECTLY at the permissions checklist; the gate runs on entry only (no surprise mid-session bounce — the in-app banner + self-heal handle revocation), and an evaluator crash is fail-open + crash-logged, never a hard lockout. **(3) Dark-Mode button contrast (DARK-BTN-01)**: dark-scheme `primary` was near-black navy #1F323F — every TextButton/OutlinedButton label+border (Allow/Disallow/Export/Import/Open/Close/Cancel, dialogs, onboarding rows) hit ≈1.3:1 on dark surfaces (invisible). Replaced with the interactive pair #9DB8C6/#0A2029 (≈8:1 both ways); filled brand buttons moved from BrandOrange+white (≈2.8:1) to `brandButtonColors()` = #B85700 + white (≈4.77:1, AA; also fixes the PIN/lock keypad keys). **(4) Layout audit (UI-CONSIST-01)**: onboarding permission rows no longer glue the status tag ("• Required") to the title (own line now); accessibility banners use matching 24dp icons / 12dp gaps; Reliable Accessibility status circles use WCAG-safe semantic tokens (white on #2E7D32/#E65100 ≈4.9/3.6:1, was ≈2.5/2.2:1), step circles pair with onPrimary, the diagnostics component-name row wraps instead of clipping, and the Profile "View" text label (≈2.8:1 on light) became a chevron. Carries v1.0.71 (field crash-log audit: a11y ANR budgets, countdown self-heal, breadcrumb NPE fix, release log tree), v1.0.70, v1.0.69, v1.0.68, v1.0.67, v1.0.66. See `docs/UI_THEME_ONBOARDING_DARKMODE_REPORT_v1.0.72.md`. **453/453 tests pass.** |
| `protect.yourself-v1.0.72-debug.apk` | ~20.8 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.72)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** in 5 m 15 s (R8 minified, 3,274,757 bytes; ~0.2 MB smaller than v1.0.71)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (21,279,731 bytes)
- `./gradlew :app:testDebugUnitTest` → **453/453 tests pass, 0 failures, 0 errors, 0 skipped** (36 suites; +18 new: 7 `OnboardingEnforcementTest` + 4 `DarkModeButtonsTest` + 4 `ThemeSwitcherPlacementTest` + 3 new `ColorContrastTest` pairs; run AFTER the builds, per release-first process)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `72`, versionName `1.0.72`, minSdk 26, targetSdk 35; launchable `MainActivity` label "Protect Yourself"
- Merged manifest (`aapt2` inspection of the BUILT apk): `enableOnBackInvokedCallback=true` present; `SYSTEM_ALERT_WINDOW` absent; all other permissions unchanged
- Post-R8 mapping check: `ThemeSwitcherKt.ThemeSwitcherIcon` / `ThemeModeMenuItem` present in the release APK (Compose-slot mangling normal); R8 size stable

## New tests (18 in v1.0.72, all passing)

`features/mainActivityPage/OnboardingEnforcementTest` (7, static source pins):

| Test | What it pins |
|---|---|
| `continue to app button is disabled until required permissions are granted` | `enabled = requiredReady` from `allRequiredGranted(rows)` |
| `skippable guidance text is removed` | Old "You can continue without them" copy + breadcrumb gone |
| `mandatory guidance and recommended-stay-optional logging exist` | New guidance copy + `finish_required_complete` breadcrumb |
| `app state gate rechecks required permissions after terms` | `!requiredPermissionsReady -> AppState.ONBOARDING` + direct-to-permissions flag |
| `gate evaluation crash is fail-open with crash log, not a hard lockout` | Fail-open catch + crash-log entry |
| `onResume recheck skips permission gate - entry-only enforcement` | `checkAppState(enforceRequiredPermissions = false)` on resume |
| `onboarding can start directly at the permissions step` | `startAtPermissions` → `OnboardingStep.PERMISSIONS` initial step |

`theme/DarkModeButtonsTest` (4, static source pins):

| Test | What it pins |
|---|---|
| `no filled button uses raw BrandOrange container anymore` | `containerColor = BrandOrange` count in main sources = 0 |
| `AppButtons provides the shared brandButtonColors helper` | helper exists, #B85700 + white container/content |
| `dark theme uses the interactive primary pair, not near-black navy` | `primary = DarkPrimaryInteractive` / `onPrimary = DarkOnPrimaryInteractive` |
| `contrast tokens exist with audited hex values` | #B85700 / #9DB8C6 / #0A2029 / #2E7D32 / #E65100 tokens |

`theme/ThemeSwitcherPlacementTest` (4, static source pins):

| Test | What it pins |
|---|---|
| `theme selector card and radio rows are gone from ProfilePage` | Card/`ThemeOptionRow`/`RadioButton` removed |
| `switcher composable exists with the three modes in a dropdown` | DropdownMenu + System Default/Dark/Light + persistence call |
| `main screen hosts a top bar with the theme switcher action` | Scaffold `topBar` + `TopAppBar` + `ThemeSwitcherIcon()` |
| `switcher icon reflects effective brightness like AppTheme` | LIGHT/DARK/system mapping matches `AppTheme` |

`theme/ColorContrastTest` (+3, pure JVM WCAG math — suite now 10):

| Test | What it pins |
|---|---|
| `dark primary works as content color on dark surfaces (button labels, borders)` | #9DB8C6 on dark surface/background ≥ 4.5:1 |
| `brand button pair - white label on BrandOrangeButton meets WCAG AA in both schemes` | #B85700 + white ≥ 4.5:1 (≈4.77) |
| `semantic status pairs - white icon reaches WCAG non-text 3_1` | white on #2E7D32/#E65100 ≥ 3:1 (≈4.9/3.6) |

The pre-existing v1.0.69 pin tests were renamed v1.0_72 and extended with the new pinned role values (dark primary/onPrimary/primaryContainer, light primary).

## Manual device checklist (post-install)

1. Top bar visible on all three tabs; tap the sun/moon icon → dropdown shows System Default / Dark / Light with a check on the current mode; selection persists across restart; Profile tab no longer has the Theme card.
2. Fresh install (clear data): terms step → permissions step; "Continue to App" is DISABLED until accessibility (+ notifications on Android 13+) are granted; granting them live-enables the button; battery/alarms stay optional; after finishing, the app opens normally.
3. Terms accepted earlier but required permission revoked → next cold start lands DIRECTLY on the permissions checklist (no terms step).
4. Dark Mode sweep: onboarding Allow/Exempt/Enable/Open rows, dialog Export/Import/Cancel/Close/OK labels, schedule/backup buttons — all labels clearly readable; filled orange buttons now show white text on deep orange (#B85700).
5. Reliable Accessibility page: status card green/amber readable, step-number circles legible, diagnostics "service component" wraps without clipping.
6. Regression sweep: blocking, VPN, schedules, focus, app lock (keypad keys now #B85700), backup/restore, crash log — unchanged from v1.0.71.

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
