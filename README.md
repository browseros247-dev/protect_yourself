# protect.yourself

A free, open-source rebuild of the **NopoX** Android app — a porn/app blocker & focus companion.

> **Status**: All 7 phases complete ✅ — see [phase progress](docs/PHASE_PROGRESS.md)

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

## Features

### Content Blocking (Phase 3)
- ✅ **Porn keyword blocking** — 1,189 preset keywords across 37 languages + custom keywords
- ✅ **Blocklist apps** — block specific apps from launching
- ✅ **Block all websites** — block every URL except whitelisted
- ✅ **Block new installs** — auto-block newly installed apps
- ✅ **Block in-app browsers** — block WebView inside other apps
- ✅ **Block YT Shorts / Search** — YouTube feature blocking
- ✅ **Block IG Reels / Search** — Instagram feature blocking
- ✅ **Block WhatsApp Status** — WhatsApp feature blocking
- ✅ **Block Snapchat Stories / Spotlight** — Snapchat feature blocking
- ✅ **Block Telegram Search** — Telegram feature blocking
- ✅ **Block image/video search** — Google/Bing image+video search blocking
- ✅ **SafeSearch enforcement** — DNS-level SafeSearch
- ✅ **Block unsupported browsers** — block all browsers except whitelisted
- ✅ **Block settings page by title** — block specific settings pages
- ✅ **Block notification drawer** — anti-circumvention
- ✅ **Block recent apps** — anti-circumvention
- ✅ **Block phone reboot** — auto-restart blocking after reboot

### VPN + DNS Blocking (Phase 3)
- ✅ **MyVpnService** with DNS hijacking
- ✅ **4 preset DNS**: Cloudflare Family, OpenDNS FamilyShield, CleanBrowsing, AdGuard Family
- ✅ **Custom DNS presets** — user can add/edit/delete
- ✅ **Per-app VPN routing** — whitelist apps to bypass VPN
- ✅ **Configurable notification** — custom message + hide option

### Stop Me (Focus Mode) (Phase 3)
- ✅ **Instant sessions** — 15min, 30min, 1hr, 2hr + custom
- ✅ **Scheduled sessions** — recurring by day-of-week + start time + duration
- ✅ **Per-session whitelist** — apps allowed during Stop Me
- ✅ **Session counter** — total sessions completed
- ✅ **Home-screen widget** — orange button for one-tap start

### Streak Tracking (Phase 5)
- ✅ **Running streak** with Lottie fire animation
- ✅ **Streak history** with relapse dates + types + notes
- ✅ **Relapse tracking** — 7 relapse types (URGE, BOREDOM, STRESS, etc.)
- ✅ **Achievements** — 9 milestone badges (1d, 3d, 7d, 14d, 30d, 60d, 90d, 180d, 365d)
- ✅ **Home-screen widget** showing current day count

### Protective Modes (Phase 4)
- ✅ **Long Sentence** — type a custom message to disable switches
- ✅ **Time Delay** — configurable delay before switch can be toggled
- ✅ **Real Friend** — accountability partner approval via email + deep links
- ✅ **Request history** — view pending + past protective mode requests

### Anti-Uninstall (Phase 6)
- ✅ **Device Admin** — prevents uninstall via "Disable" button
- ✅ **Accessibility Guard** — watches for service disablement + self-heals
- ✅ **Prevent Uninstall switch** — accessibility blocks NopoX app info page
- ✅ **Block Phone Reboot** — auto-restart blocking on BOOT_COMPLETED
- ✅ **Block Recent Apps** — accessibility dismisses recent apps screen
- ✅ **Block Notification Drawer** — accessibility dismisses notification shade

### App Lock (Phase 4)
- ✅ **PIN** — 4-digit numeric
- ✅ **Password** — alphanumeric, min 6 chars
- ✅ **Pattern** — 9-dot pattern (Phase 5 full impl)
- ✅ **Biometric** — BiometricPrompt (fingerprint/face)
- ✅ **Disable forgot password** — hide forgot password option

### Block Screen (Phase 3)
- ✅ **Dynamic block message** per reason
- ✅ **Optional motivation image** (user-uploaded)
- ✅ **Optional custom message**
- ✅ **Optional countdown timer** (3-300s)
- ✅ **Rating prompt** after every 20 blocks
- ✅ **Optional redirect URL** on Close
- ✅ **Block count increment** (persisted)

### Notifications (Phase 6)
- ✅ **Daily report** — daily summary (block count + streak days)
- ✅ **Stop Me foreground service** — required for Android 8+
- ✅ **Accessibility alert** — high-priority when service disabled

### Widgets (Phase 3)
- ✅ **Stop Me widget** — orange button + remaining time
- ✅ **Streak widget** — current day count

### Profile (Phase 5)
- ✅ **Backup/Sync** (Firebase Auth required)
- ✅ **Import/Export** local config
- ✅ **FAQ + About**
- ✅ **Share app** via system share sheet
- ✅ **Contact email** via mailto intent
- ✅ **Delete account** with confirmation

### Onboarding (Phase 5)
- ✅ **Terms & Privacy** with agree checkbox
- ✅ **Accessibility permission** flow
- ✅ **Pop-up Window permission** flow
- ✅ **Skip option** on all screens

### Sign-in (Phase 5)
- ✅ **Email + password** sign in / sign up (Firebase Auth)
- ✅ **Optional** — only required for backup/sync + accountability partner

### About Tab (Phase 5)
- ✅ Replaces original Premium tab
- ✅ App info + version + package
- ✅ Rebuild info (what was removed)
- ✅ Help links (FAQ, How it works, Troubleshooting)
- ✅ Contact links (Email, Report bug)
- ✅ Legal links (Privacy, Terms)
- ✅ Credits (open source libraries)

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
│   │   │   │   ├── mainActivityPage/       # Main + bottom nav + About
│   │   │   │   ├── blockerPage/            # Blocker + Accessibility + VPN services
│   │   │   │   ├── streakPage/             # Streak tracking + widget
│   │   │   │   ├── profilePage/            # Profile + backup
│   │   │   │   └── ...
│   │   │   └── theme/                      # Color, Type, Theme
│   │   ├── res/                            # All resources
│   │   ├── assets/                         # 6 Lottie JSONs + preset keywords
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── google-services.json                # PLACEHOLDER — replace with your own
├── gradle/
│   ├── libs.versions.toml                  # Version catalog
│   └── wrapper/
├── docs/
│   ├── IMPLEMENTATION_PLAN.md              # 9,800-word plan
│   ├── PHASE_PROGRESS.md                   # Phase tracker
│   ├── launcher_icon_preview.png
│   └── playstore_icon_512.png
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── README.md (this file)
├── CONTRIBUTING.md
├── LICENSE (MIT)
└── .gitignore
```

## Setup

### 1. Prerequisites

- **Android Studio Ladybug (2024.2.1)+** with:
  - Android SDK Platform 35 (Android 15)
  - Android SDK Build-Tools 35.0.0
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

### 4. Configure deep links (OPTIONAL)

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
- [Contributing](CONTRIBUTING.md) — how to contribute

## Testing

The project includes 100+ unit tests covering:
- BlockerPageUtils (keyword matching, URL/DNS validation) — 30 tests
- Preset data integrity (DNS, Stop Me, browsers) — 17 tests
- SwitchStatusDao + SwitchStatusValues — 14 tests
- All 9 Room DAOs — 20 tests
- StopMeManager (day bitmask math) — 7 tests
- Streak identifiers + achievements — 18 tests
- BlockerPage identifiers (4 enums) — 25 tests
- AccessibilityService constants — 3 tests

## License

MIT — see [LICENSE](LICENSE).

## Credits

- Original NopoX by PlanProductive (rebuilt from APK via reverse engineering)
- Nunito font by Vernon Adams (OFL)
- Lottie animations by AirBnB
- All open source libraries listed in [About tab](app/src/main/java/protect/yourself/features/mainActivityPage/components/AboutPage.kt)
