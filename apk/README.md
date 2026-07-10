# Pre-built APKs

This directory contains pre-built, signed APKs of **Protect Yourself** for direct installation.

## Files

| File | Size | Package | Build Type | Description |
|---|---|---|---|---|
| `protect.yourself-v1.0.31-release.apk` | ~15 MB | `protect.yourself` | Release | Production-style build. **Recommended for installation.** Adds in-app Stop Me page + Keyword Manager page. |
| `protect.yourself-v1.0.31-debug.apk` | ~22 MB | `protect.yourself` | Debug | Debug build with logging enabled. Larger due to unstripped debug info. |
| `protect.yourself-v1.0.30-release.apk` | ~15 MB | `protect.yourself` | Release | Previous build (local crash logging system). |
| `protect.yourself-v1.0.30-debug.apk` | ~22 MB | `protect.yourself` | Debug | Previous build debug. |
| `protect.yourself-v1.0.29-release.apk` | ~15 MB | `protect.yourself` | Release | Previous build (local JSON Backup/Restore). |
| `protect.yourself-v1.0.29-debug.apk` | ~22 MB | `protect.yourself` | Debug | Previous build debug. |
| `protect.yourself-v1.0.28-release.apk` | ~15 MB | `protect.yourself` | Release | Previous build (NopoX-style SafeSearch enforcement). |
| `protect.yourself-v1.0.28-debug.apk` | ~22 MB | `protect.yourself` | Debug | Previous build debug. |
| `protect.yourself-v1.0.27-release.apk` | ~15 MB | `protect.yourself` | Release | Previous build (supported/unsupported browser blocking). |
| `protect.yourself-v1.0.27-debug.apk` | ~22 MB | `protect.yourself` | Debug | Previous build debug. |
| `protect.yourself-v1.0.26-release.apk` | ~15 MB | `protect.yourself` | Release | Previous build (title-based + uninstall protection). |
| `protect.yourself-v1.0.26-debug.apk` | ~22 MB | `protect.yourself` | Debug | Previous build debug. |

Both APKs target:
- **Min Android**: 8.0 (API 26)
- **Target Android**: 15 (API 35)
- **Compile SDK**: 35
- **Version**: 1.0.31 (versionCode 31)
- **App label**: "Protect Yourself" (release) / "Protect Yourself DEBUG" (debug)

## Signing

Both APKs are signed with the embedded debug keystore (Android Debug certificate):
- **Subject**: C=US, O=Android, CN=Android Debug
- **SHA256**: `33493ce032e3bbc3f2b8deb70395706427b959417d93dca344616bc64f2e8d07`
- **Signature scheme**: v2
- **Valid until**: July 1, 2056

For Play Store distribution, re-sign with your own release keystore before uploading.

## Installation

1. Transfer the APK to your Android device (via USB, cloud storage, or direct download)
2. On the device, open the APK file
3. If prompted, allow "Install unknown apps" for your file manager / browser
4. Tap **Install**
5. Open **Protect Yourself** from your app drawer

## First-Launch Setup

After installation, the app will guide you through:

1. **Terms & Privacy** — read and agree
2. **Accessibility permission** — required for content blocking
3. **Display Pop-up Window permission** — required to show block screen over other apps
4. Optional: **Device Admin** (anti-uninstall), **VPN** (DNS blocking), **App Lock**

## Firebase Configuration

These APKs are built with the Firebase project configured in `app/google-services.json`:

- **Project ID**: `flutter-ai-playground-64339`
- **Project Number**: `149261109336`
- **App ID**: `1:149261109336:android:10a383c68a9b5a7626e22d`
- **Package**: `protect.yourself`

To use a different Firebase project, replace `app/google-services.json` and rebuild.

## Build Provenance

Built with:
- **Temurin JDK**: 17.0.13
- **Android SDK**: Platform 35 + Build-Tools 35.0.0 + Platform-Tools
- **Gradle**: 8.10.2
- **Android Gradle Plugin**: 8.7.2
- **Kotlin**: 2.0.21
- **Jetpack Compose BOM**: 2024.10.01
- **Room**: 2.6.1
- **Firebase BoM**: 33.5.1

Build commands:
```bash
./gradlew assembleRelease --no-daemon --max-workers=1
./gradlew assembleDebug   --no-daemon --max-workers=1
```

## Verification

You can verify the APK signature using `uber-apk-signer`:

```bash
java -jar uber-apk-signer.jar -a protect.yourself-v1.0.1-release.apk -y
```

Expected output: `signature verified [v2]` with the SHA256 above.

## Rebuilding

To rebuild from source:

```bash
git clone https://github.com/258044aamm-Dev/Protect-Yourself.git
cd Protect-Yourself
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
