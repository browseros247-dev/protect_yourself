# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.48 (versionCode 48)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.48-release.apk` | ~15.3 MB | Release | **Recommended for installation.** Integration of three fix branches on top of main: (1) `fix/test-failures-cleanup` — fixes 26 pre-existing test failures (BuildConfigSmokeTest semver validation, AllDaosTest method signature, Robolectric runner for ApplicationProvider + Patterns); (2) `chore/dead-code-cleanup` — 364 lines of dead code removed across 42 files; (3) `fix/dead-code-cleanup-complete` — comprehensive dead-code cleanup (22 orphaned SwitchStatusValues accessors, 11 orphaned Room DAO methods, 3 dead Composable pages, 148 unused strings, 20 unused colors, MyVpnService.refreshNotification dead function, MainActivity.EXTRA_OPEN_TAB deep-link wired up). 12 merge conflicts resolved, 2 regressions fixed (SelectedKeywordDao.getSelectedByIdentifier restored, LaunchedEffect import added). 181/181 tests pass. |

## Build verification (v1.0.48)

- `./gradlew assembleRelease` → **BUILD SUCCESSFUL** in 41s
- `./gradlew testDebugUnitTest` → **181/181 tests pass, 0 failures, 0 errors, 0 skipped**
- APK signed with debug keystore (per `release { signingConfig = signingConfigs.getByName("debug") }` config — re-sign with your own release keystore for Play Store distribution)
- package: `protect.yourself`
- versionCode: 48
- versionName: `1.0.48`
- minSdk: 26, targetSdk: 35

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
