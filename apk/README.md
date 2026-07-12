# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.48 (versionCode 48)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.48-release-new-install-fix.apk` | ~15.3 MB | Release | **Latest build — new install blocking fix.** Built from `fix/new-app-installation-blocking` branch. Fixes 6 root-cause bugs in the "Block new install apps" feature: (1) missing `isFirstInstall` check (NopoX parity — `firstInstallTime == lastUpdateTime` AND within 1 hour); (2) 11 missing block message string resources; (3) no pre-insert cleanup in PACKAGE_ADDED receiver; (4) silent failure when accessibility service is disconnected; (5) manifest intent-filter bug (`<data scheme="package">` blocked `MY_PACKAGE_REPLACED`); (6) `SelectAppPageViewModel` missing accessibility config refresh for 7 identifiers. New utility: `NewInstallBlockingUtils.kt`. 2 new test files (10 tests). Reference: NopoX_1.0.53.apk. |
| `protect.yourself-v1.0.48-release.apk` | ~15.3 MB | Release | Previous build (main branch). Integration of three fix branches: test-failures-cleanup, dead-code-cleanup, dead-code-cleanup-complete. 181/181 tests pass. |

## Build verification (v1.0.48 new-install-fix)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** (release APK: 15,321,023 bytes)
- `./gradlew :app:compileDebugKotlin` → **BUILD SUCCESSFUL** (Kotlin compilation verified)
- APK signed with debug keystore (per `release { signingConfig = signingConfigs.getByName("debug") }` config — re-sign with your own release keystore for Play Store distribution)
- package: `protect.yourself`
- versionCode: 48
- versionName: `1.0.48`
- minSdk: 26, targetSdk: 35
- SHA-256: `96daf8c8a896c269f29aaa43f66235f37a28dce32c621a63dcca01a38a6d4802`

## APK verification (new-install-fix build)

The release APK was verified to contain all fixes:

- **NewInstallBlockingUtils class** — present in `classes4.dex` (`Lprotect/yourself/features/blockerPage/utils/NewInstallBlockingUtils;`)
- **Rewritten receiver** — `AppSystemActionReceiverAllTimeWithData$handlePackageAdded$1` present in `classes.dex` (new `handlePackageAdded` method)
- **Fixed manifest** — `MY_PACKAGE_REPLACED` in its own intent-filter (no `<data scheme="package">`); `PACKAGE_ADDED` + `PACKAGE_REMOVED` in a separate filter with `<data scheme="package">`
- **New string resources** — `block_page_default_new_install_message` and `block_page_default_block_apps_message` (plus 9 others) present in resources table

## Test breakdown (181/181 pass)

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
| **TOTAL** | **181** | **✅** |

## Removed APKs

The following older APKs were removed when v1.0.48 was built (per the "only the latest version" policy):

- `protect.yourself-v1.0.38-release-accessibility-fix.apk`
- `protect.yourself-v1.0.39-release-uninstall-fix.apk`
- `protect.yourself-v1.0.40-debug.apk`
- `protect.yourself-v1.0.40-release.apk`
- `protect.yourself-v1.0.41-debug.apk`
- `protect.yourself-v1.0.42-debug.apk`
- `protect.yourself-v1.0.42-release.apk`
- `protect.yourself-v1.0.43-release.apk`
- `protect.yourself-v1.0.44-release.apk`
- `protect.yourself-v1.0.45-release.apk`
- `protect.yourself-v1.0.46-release.apk`
- `protect.yourself-v1.0.47-release.apk`

Older versions are still accessible via git history (e.g. `git show v1.0.47:apk/protect.yourself-v1.0.47-release.apk > old.apk`).
