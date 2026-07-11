# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.45 (versionCode 45)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.45-release.apk` | ~16 MB | Release | **Recommended for installation.** Merges crash-logging-enhancements branch: new AnrWatchdog (ANR detection), AppCoroutineExceptionHandler (routes uncaught coroutine exceptions to CrashLogger across 8+ scopes), disk-backed breadcrumbs (survive hard crashes), atomic file persistence, crash deduplication, service state capture (accessibility/VPN/device-admin status at crash time), OOM-resilient persistence, crash-detected notification on next launch, crash count badge on Profile menu, fixed exportToUri null-return bug (shared SafUtils helper). Also includes all prior fixes from v1.0.44 (uninstall prevention overlay + kill timer) and v1.0.40-v1.0.43 (backup import/export fixes, streak fixes, protective mode fixes, VPN DNS/modes/whitelist fixes). |
| `protect.yourself-v1.0.44-release.apk` | ~16 MB | Release | Previous release — uninstall prevention overlay + 500ms kill timer + error handling. |
| `protect.yourself-v1.0.43-release.apk` | ~16 MB | Release | VPN modes UI fix + accessibility WRITE_SECURE_SETTINGS self-heal. |
| `protect.yourself-v1.0.42-release.apk` | ~16 MB | Release | VPN modes + UI/UX refinement. |
| `protect.yourself-v1.0.41-release.apk` | ~15 MB | Release | VPN app whitelist fix. |
| `protect.yourself-v1.0.40-release.apk` | ~15 MB | Release | Streak fixes + protective mode fixes + analysis docs. |
| `protect.yourself-v1.0.39-release-uninstall-fix.apk` | ~15 MB | Release | Uninstall prevention fix. |
| `protect.yourself-v1.0.38-release-accessibility-fix.apk` | ~15 MB | Release | Accessibility fix. |

## Build Targets

- **Min Android**: 8.0 (API 26)
- **Target Android**: 15 (API 35)
- **Compile SDK**: 35
- **App label**: "Protect Yourself" (release) / "Protect Yourself DEBUG" (debug)

## Signing

Both APKs are signed with the embedded debug keystore (Android Debug certificate):
- **Subject**: C=US, O=Android, CN=Android Debug
- **Signature scheme**: v2
- **Valid until**: July 1, 2056

For Play Store distribution, re-sign with your own release keystore before uploading.

## Installation

1. Transfer the APK to your Android device (via USB, cloud storage, or direct download)
2. On the device, open the APK file
3. If prompted, allow "Install unknown apps" for your file manager / browser
4. Tap **Install**
5. Open **Protect Yourself** from your app drawer

## Going-Forward Workflow

When a new version is built:
1. Remove the old `protect.yourself-v*.apk` files from this directory
2. Copy the new `protect.yourself-v<NEW_VERSION>-debug.apk` and `protect.yourself-v<NEW_VERSION>-release.apk` here
3. Update this README with the new version number + description
4. Commit + push to GitHub

This keeps the repo lean — only the latest APK is ever stored.

## Rebuilding from Source

```bash
git clone https://github.com/258044aamm-Dev/Protect-Yourself.git
cd Protect-Yourself
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
