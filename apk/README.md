# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.48 (versionCode 48) — PIN Lock UI Fix + Settings Dedup

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.48-release.apk` | ~15.3 MB | Release | **Recommended for installation.** Built from branch `fix/pin-lock-ui-and-dedup-settings` on top of main. Fixes two issues: (1) broken PIN lock screen UI — replaced `LazyVerticalGrid` keypad (whose `Modifier.height(240.dp)` + `aspectRatio(1f)` constraints conflicted and clipped the bottom two rows of the keypad off-screen) with a fixed `Column-of-Rows` layout where all 12 keys are always visible. Added biometric availability check, lockout countdown display, shake animation + haptic feedback, and string-resource localization. (2) Duplicated "Disable Forgot Password" and "Enable Biometrics Lock" settings — removed the duplicate toggles from `AppLockSetupPage` and made the cards on the main settings page directly toggleable (with app-lock-not-set error toasts matching the NopoX 1.0.53 reference). 6 new `AppLockManagerTest` regression tests added. 187/187 tests pass. |

## Build verification (v1.0.48 — PIN Lock UI Fix)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** in 33s
- `./gradlew :app:testDebugUnitTest` → **187/187 tests pass, 0 failures, 0 errors, 0 skipped**
- APK signed with debug keystore (per `release { signingConfig = signingConfigs.getByName("debug") }` config — re-sign with your own release keystore for Play Store distribution)
- package: `protect.yourself`
- versionCode: 48
- versionName: `1.0.48`
- minSdk: 26, targetSdk: 35
- APK size: 15,329,003 bytes (~15.3 MB)

## Test breakdown (187/187 pass)

| Test class | Tests | Status |
|---|---:|---|
| BuildConfigSmokeTest | 4 | ✅ |
| database.AllDaosTest | 22 | ✅ |
| database.switchStatus.SwitchStatusDaoTest | 18 | ✅ |
| blockerPage.identifiers.IdentifiersTest | 25 | ✅ |
| blockerPage.service.MyAccessibilityServiceTest | 3 | ✅ |
| blockerPage.utils.BlockerPageUtilsTest | 34 | ✅ |
| blockerPage.utils.BlockingValidatorTest | 43 | ✅ |
| blockerPage.utils.PresetDataTest | 14 | ✅ |
| blockerPage.utils.StopMeManagerTest | 7 | ✅ |
| streakPage.identifiers.StreakIdentifiersTest | 11 | ✅ |
| **appPasswordPage.AppLockManagerTest** (NEW) | **6** | ✅ |
| **TOTAL** | **187** | **✅** |

## What changed in this build (vs. previous v1.0.48)

### PIN Lock Screen UI (AppLockScreen.kt)
- Replaced `LazyVerticalGrid` keypad with `Column { Row { ... } }` — all 12 keys always visible (was clipped to 4 keys visible on most phones due to conflicting `height(240.dp)` + `aspectRatio(1f)` constraints).
- Same fix applied to the pattern grid.
- Whole screen now `verticalScroll`-capable so content never overflows on small devices.
- Added `canUseBiometric()` check before auto-launching `BiometricPrompt` — prevents silent failures on devices without biometrics.
- Surfaced BUG-22 rate-limiter state to the UI: countdown message, disabled input while locked out, auto-refresh every 500ms.
- Shake animation + haptic feedback on incorrect PIN entry.
- Timber logging at every state transition for diagnostics.
- All user-facing strings moved to `strings.xml` for localization.
- "Forgot PIN?" shown for PIN locks, "Forgot password?" otherwise (matches NopoX 1.0.53 reference).

### Duplicate Settings Removed (AppLockSetupPage.kt, BlockerPageViewModel.kt)
- Removed duplicate Touch ID + Disable Forgot Password toggles from `AppLockSetupPage`'s `LockTypeSelector` (replaced with a hint card pointing users to the main settings page).
- Made `TOUCH_ID_SWITCH` and `DISABLE_FORGOT_PASSWORD_SWITCH` cards on the main settings page **directly toggleable** (previously they just opened `AppLockSetup`).
- Error toasts when toggling ON without an app lock set (matches NopoX 1.0.53 strings: `touch_id_app_lock_not_set_error`, `disable_forgot_password_app_lock_not_set_error`).
- Updated card labels to match reference APK: "Set app lock", "Enable touch ID with app lock", "Disable the forgot password option".

### String Resources (strings.xml)
- Added 27 new string resources for the App Lock section + lock screen.

### Tests (AppLockManagerTest.kt)
- 6 new regression tests covering: `getLockType()` default, `isTouchIdEnabled` / `isForgotPasswordDisabled` defaults, `getLockoutRemainingMs` / `isLockedOut` defaults, `verify()` returns false when no lock is set.

## Reference APK

The reference implementation is `NopoX_1.0.53.apk` (in the repo root). This build's lock screen + settings labels mirror that reference's behavior:

| Resource key in NopoX 1.0.53 | Our equivalent |
|---|---|
| `set_app_lock_card_title` ("Set app lock") | `R.string.set_app_lock_card_title` |
| `set_touch_id_card_title` ("Enable touch ID with app lock") | `R.string.set_touch_id_card_title` |
| `disable_forgot_password_option` ("Disable the forgot password option") | `R.string.disable_forgot_password_option` |
| `touch_id_app_lock_not_set_error` | `R.string.touch_id_app_lock_not_set_error` |
| `disable_forgot_password_app_lock_not_set_error` | `R.string.disable_forgot_password_app_lock_not_set_error` |
| `lock_screen_title` ("Lock screen") | `R.string.lock_screen_title` |
| `lock_screen_use_touch_id` ("Use Touch ID") | `R.string.lock_screen_use_touch_id` |
| `forgot_pin` ("Forgot PIN?") | `R.string.lock_screen_forgot_pin` |
| `forgot_password` ("Forgot password?") | `R.string.lock_screen_forgot_password` |

## Removed APKs

The previous `protect.yourself-v1.0.48-release.apk` (integration of three fix branches) was replaced by this build (PIN lock UI fix on top of that integration).

Older versions are still accessible via git history (e.g. `git show v1.0.47:apk/protect.yourself-v1.0.47-release.apk > old.apk`).
