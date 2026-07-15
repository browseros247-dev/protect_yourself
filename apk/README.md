# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.54 (versionCode 54) — Merged: PIN Lock UI Fix + Main's v1.0.49-v1.0.54 Features

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.54-release.apk` | ~15.4 MB | Release | **Recommended for installation.** Built from merged `main` after integrating `fix/pin-lock-ui-and-dedup-settings`. Combines: (1) **PIN lock screen UI fix** — replaced broken `LazyVerticalGrid` keypad with fixed `Column-of-Rows` layout; added biometric availability check, lockout countdown, shake animation, full string-resource localization. (2) **Settings dedup** — removed duplicate Touch ID + Disable Forgot Password toggles from `AppLockSetupPage`; made the cards directly toggleable with app-lock-not-set error toasts. (3) **Main's v1.0.49-v1.0.54 features** — block-screen motivation image + custom message UI/UX overhaul, preview block screen, new install blocking fix (6 root-cause bugs), Block In-App Browser detection fix (IAB-01/IAB-02), SafeSearch enforcement rewrite (SS-01/02/03/04), whitelist unsupported browser fix, crash log fixes (ANR fix for `selfHealSafe`), DB migration v8→v9 fix (`displayName` column). 259/259 tests pass. |

## Build verification (v1.0.54 — merged)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL**
- `./gradlew :app:testDebugUnitTest` → **259/259 tests pass, 0 failures, 0 errors, 0 skipped**
- APK signed with debug keystore (per `release { signingConfig = signingConfigs.getByName("debug") }` config — re-sign with your own release keystore for Play Store distribution)
- package: `protect.yourself`
- versionCode: 54
- versionName: `1.0.54`
- minSdk: 26, targetSdk: 35
- APK size: ~15.4 MB

## Test breakdown (259/259 pass)

| Test class | Tests | Status |
|---|---:|---|
| BuildConfigSmokeTest | 4 | ✅ |
| database.AllDaosTest | 22 | ✅ |
| database.core.AppDatabaseSchemaRepairTest | 9 | ✅ (new on main) |
| database.switchStatus.SwitchStatusDaoTest | 18 | ✅ |
| blockerPage.identifiers.IdentifiersTest | 25 | ✅ |
| blockerPage.service.MyAccessibilityServiceTest | 3 | ✅ |
| blockerPage.utils.BlockerPageUtilsTest | 34+264 | ✅ (expanded on main) |
| blockerPage.utils.BlockingValidatorTest | 43 | ✅ |
| blockerPage.utils.PresetDataTest | 14+21 | ✅ (expanded on main) |
| blockerPage.utils.StopMeManagerTest | 7 | ✅ |
| blockerPage.utils.BlockScreenImageLoaderTest | 4 | ✅ (new on main) |
| blockerPage.utils.NewInstallBlockingUtilsTest | 6 | ✅ (new on main) |
| blockerPage.utils.NewInstallBlockingIntegrationTest | 14 | ✅ (new on main) |
| streakPage.identifiers.StreakIdentifiersTest | 11 | ✅ |
| appPasswordPage.AppLockManagerTest | 6 | ✅ (new on feature branch) |
| **TOTAL** | **259** | **✅** |

## What's in this build (merged from two branches)

### From `fix/pin-lock-ui-and-dedup-settings` (PIN Lock UI Fix + Settings Dedup)

#### PIN Lock Screen UI (AppLockScreen.kt)
- Replaced `LazyVerticalGrid` keypad with `Column { Row { ... } }` — all 12 keys always visible (was clipped to 4 keys visible on most phones due to conflicting `height(240.dp)` + `aspectRatio(1f)` constraints).
- Same fix applied to the pattern grid.
- Whole screen now `verticalScroll`-capable so content never overflows on small devices.
- Added `canUseBiometric()` + `checkBiometricAvailability()` + `BiometricAvailability` enum — returns specific failure reason (NO_HARDWARE, HW_UNAVAILABLE, NONE_ENROLLED, etc.) so the UI can show the right message.
- Surfaced BUG-22 rate-limiter state to the UI: countdown message, disabled input while locked out, auto-refresh every 500ms.
- Shake animation + haptic feedback on incorrect PIN entry.
- Timber logging at every state transition for diagnostics.
- All user-facing strings moved to `strings.xml` for localization (29 new string resources).
- "Forgot PIN?" shown for PIN locks, "Forgot password?" otherwise (matches the reference behavior).

#### Duplicate Settings Removed (AppLockSetupPage.kt, BlockerPageViewModel.kt)
- Removed duplicate Touch ID + Disable Forgot Password toggles from `AppLockSetupPage`'s `LockTypeSelector` (replaced with a hint card pointing users to the main settings page).
- Made `TOUCH_ID_SWITCH` and `DISABLE_FORGOT_PASSWORD_SWITCH` cards on the main settings page **directly toggleable** (previously they just opened `AppLockSetup`).
- Error toasts when toggling ON without an app lock set (matches the reference strings).
- Updated card labels to match reference APK: "Set app lock", "Enable touch ID with app lock", "Disable the forgot password option".

### From `main` (v1.0.49-v1.0.54 features)

#### Block Screen Motivation Image + Custom Message UI/UX (v1.0.54)
- New `BlockScreenImageLoader` for safe image loading with size validation (20 MB max).
- New `saveBlockScreenImageUri`, `clearBlockScreenImage`, `clearBlockScreenMessage`, `saveBlockScreenMessage`, `previewBlockScreen` methods in `BlockerPageViewModel`.
- New `ClearBlockScreenImage`, `ClearBlockScreenMessage`, `PreviewBlockScreen` navigation events.
- Localized action labels: "Choose" / "Change" / "Remove" for image; "Default" / "Custom" for message.
- Preview block screen feature.

#### New Install Blocking Fix (v1.0.49)
- New `NewInstallBlockingUtils` class.
- Fixed 6 root-cause bugs preventing the feature from working.
- AndroidManifest fix: split intent-filters so `MY_PACKAGE_REPLACED` (no data URI) doesn't get filtered out by `<data android:scheme="package" />`.

#### Block In-App Browser Detection Fix (IAB-01, IAB-02)
- New `UnsupportedBrowserDetector` class.
- Repaired detection logic that was incorrectly blocking supported browsers.

#### SafeSearch Enforcement Rewrite (SS-01/02/03/04)
- Rewrote SafeSearch to match the reference behavior.

#### Whitelist Unsupported Browser Fix
- Picker now shows only unsupported browsers (was showing all browsers).

#### Crash Log Fixes (v1.0.49)
- ANR fix: moved `selfHealSafe` to a background coroutine in `ProtectYourselfApp.onCreate` (was blocking the main thread for 100-500ms on some OEMs).
- DB migration v8→v9 fix: column name changed from `display_name` (snake_case) to `displayName` (camelCase) to match Room 2.6.1's default naming.

## Reference APK

This build's lock screen + settings labels mirror the reference app's behavior.

## Removed APKs

The previous `protect.yourself-v1.0.48-release.apk` was replaced by this v1.0.54 build (per the "only the latest version" policy).

Older versions are still accessible via git history (e.g. `git show v1.0.47:apk/protect.yourself-v1.0.47-release.apk > old.apk`).
