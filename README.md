# protect.yourself

A rebuild of the **NopoX** Android app — a porn/app blocker & focus companion — as a fully free, open-source Android Studio project.

> **Status**: Phase 1 (Project Skeleton) ✅ — see [implementation plan](IMPLEMENTATION_PLAN.md)

## Overview

`protect.yourself` is a faithful rebuild of the original NopoX APK (`com.planproductive.nopoz` v1.0.53). The rebuild:

- Removes **all payment functionality** (subscriptions, in-app purchases, premium features, paywalls, license checks).
- Removes **all ads** (AdMob stripped entirely).
- Removes **all user-tracking analytics** (Amplitude + Firebase Analytics gone; only Crashlytics kept).
- Replaces **Branch.io** with standard Android App Links.
- Replaces proprietary **Cera Round Pro** font with open-source **Nunito**.
- Replaces **Mavericks (MvRx)** state management with **ViewModel + StateFlow**.

Everything else — accessibility-based content blocking, VPN DNS filtering, keyword blocking, accountability partner, streak tracking, widgets, anti-uninstall protections — is preserved 1:1.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| Build | Gradle 8.10.2 (Kotlin DSL) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| UI | Jetpack Compose (BOM 2024.10.01) + XML for widgets/block screen |
| State | ViewModel + StateFlow |
| Database | Room 2.6.1 (9 entities, 9 DAOs) |
| Backend | Firebase (Auth, Firestore, Messaging, Crashlytics) |
| Async | Kotlin Coroutines 1.9.0 |
| Analytics | Firebase Crashlytics only (Amplitude + Analytics removed) |

## Project Structure

```
protect.yourself/
├── app/
│   ├── src/main/
│   │   ├── java/protect/yourself/
│   │   │   ├── core/                       # NopoXApp, AppContainer
│   │   │   ├── commons/
│   │   │   │   ├── signaturekiller/        # KillerApplication (vendor)
│   │   │   │   ├── components/
│   │   │   │   └── utils/                  # broadcastReceivers, firebaseUtils, etc.
│   │   │   ├── database/                   # Room (9 entities, 9 DAOs)
│   │   │   ├── features/                   # All UI features
│   │   │   │   ├── mainActivityPage/       # Main + bottom nav
│   │   │   │   ├── blockerPage/            # Blocker + Accessibility + VPN services
│   │   │   │   ├── streakPage/             # Streak tracking + widget
│   │   │   │   ├── profilePage/            # Profile + backup
│   │   │   │   └── ...
│   │   │   └── theme/                      # Color, Type, Theme
│   │   ├── res/
│   │   │   ├── values/                     # strings, colors, themes (dark)
│   │   │   ├── values-night/               # themes (dark override)
│   │   │   ├── drawable/                   # icons, gradients, vectors
│   │   │   ├── font/                       # nunito_*.ttf
│   │   │   ├── layout/                     # page_porn_block, widgets
│   │   │   ├── mipmap-*/                   # launcher icons (5 densities + adaptive)
│   │   │   └── xml/                        # accessibility_setting, device_admin, file_paths, widgets
│   │   ├── assets/                         # 6 Lottie JSON animations
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts                    # App module config
│   ├── proguard-rules.pro
│   └── google-services.json                # PLACEHOLDER — replace with your own
├── gradle/
│   ├── libs.versions.toml                  # Version catalog
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle.kts                        # Root project
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── README.md (this file)
```

## Setup

### 1. Prerequisites

- **Android Studio Ladybug (2024.2.1)+** with:
  - Android SDK Platform 35 (Android 15)
  - Android SDK Build-Tools 35.0.0
  - Android SDK Platform-Tools 35.0.0
  - Kotlin plugin 2.0.21
- **JDK 17** (bundled with Android Studio)

### 2. Clone and open

```bash
git clone https://github.com/258044aamm-Dev/Protect-Yourself.git
cd Protect-Yourself
# Open in Android Studio: File > Open > select this folder
```

Android Studio will prompt you to:
- Sync Gradle
- Install missing SDK components
- Configure JDK

### 3. Configure Firebase (REQUIRED)

This project uses Firebase for:
- **Firebase Auth** — sign-in / sign-up (optional, used for backup/sync)
- **Cloud Firestore** — backup/sync + accountability partner data
- **Firebase Cloud Messaging** — push notifications (daily report)
- **Firebase Crashlytics** — crash reporting

You must provide your own Firebase project:

1. Go to <https://console.firebase.google.com/>
2. Create a new project (e.g., `protect-yourself`)
3. Add an Android app with package name: **`protect.yourself`**
4. Add a second Android app with package name: **`protect.yourself.debug`** (for debug builds)
5. Download `google-services.json`
6. **Replace** `app/google-services.json` (placeholder) with the downloaded file
7. In Firebase Console, enable:
   - Authentication → Sign-in method → Email/Password
   - Cloud Firestore (start in test mode, then add security rules)
   - Cloud Messaging (no setup needed beyond enabling)

### 4. Configure deep links (OPTIONAL — Phase 5+)

To support accountability partner approval links:

1. Own a domain (e.g., `protectyourself.app.link`)
2. Host `assetlinks.json` at:
   ```
   https://protectyourself.app.link/.well-known/assetlinks.json
   ```
   Use your app's signing certificate fingerprint. See:
   <https://developer.android.com/training/app-links/verify-android-applinks>

3. If you skip this, accountability partner approval falls back to `open://protectyourself` custom scheme (works locally only).

### 5. Build

```bash
# Debug APK (with .debug suffix, AdMob-free)
./gradlew assembleDebug

# Release APK (signed with debug keystore by default — re-sign with your own for Play Store)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumentation tests (needs emulator/device)
./gradlew connectedAndroidTest
```

Output APKs land in `app/build/outputs/apk/<variant>/`.

## Documentation

- [Implementation Plan](docs/IMPLEMENTATION_PLAN.md) — comprehensive 9,800-word plan covering all 7 phases
- [Phase Progress](docs/PHASE_PROGRESS.md) — what's done in each phase

## Features

### Implemented (Phase 1)
- ✅ Project skeleton (Gradle, manifest, theme, fonts)
- ✅ Application class with signature killer base
- ✅ Room database schema (9 entities, 9 DAOs)
- ✅ All 22 manifest permissions (minus BILLING + AD_ID)
- ✅ All 4 activities + 4 services + 6 receivers + 3 providers in manifest
- ✅ All 6 Lottie animations imported
- ✅ Branded launcher icon at 5 densities + adaptive icon
- ✅ Bottom nav (NopoX, Streak, About, Profile)
- ✅ DayNight theme (dark + light)
- ✅ Nunito typography (4 weights)

### Coming (Phases 2-7)
- ⏳ Phase 2: Database pre-population, default keywords, SwitchStatusValues
- ⏳ Phase 3: Accessibility service, VPN service, block screen, Stop Me
- ⏳ Phase 4: Main UI, all settings sub-pages, app lock
- ⏳ Phase 5: Streak page, profile, onboarding, accountability partner
- ⏳ Phase 6: Anti-uninstall, notifications, polish
- ⏳ Phase 7: Tests, docs, final APK

See [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) for the full plan.

## License

TBD — recommend MIT or GPL-3.0.

## Credits

- Original NopoX by PlanProductive (rebuilt from APK via reverse engineering)
- Nunito font by Vernon Adams (OFL)
- Lottie animations by AirBnB
