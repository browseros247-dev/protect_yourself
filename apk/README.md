# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.34 (versionCode 34)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.34-release.apk` | ~15 MB | Release | **Recommended for installation.** Removes Supported Browsers + Make Any Browser Supported; moves Block Unsupported Browsers + Whitelist to Content Blocking; moves Package+Intent to Uninstall Protection. |
| `protect.yourself-v1.0.34-debug.apk` | ~22 MB | Debug | Debug build with logging enabled. Larger due to unstripped debug info. |

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
