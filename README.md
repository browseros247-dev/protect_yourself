# Protect Yourself

A free, open-source Android app blocker & focus companion — rebuilt from the original NopoX APK with all payment functionality, ads, and tracking removed.

> **Status**: v1.0.1 — all features implemented, APKs built and signed ✅

## What Is This?

**Protect Yourself** is a faithful rebuild of the original NopoX Android app (`com.planproductive.nopoz` v1.0.53). The original source code was permanently lost; this rebuild was created by reverse-engineering the APK using Apktool, JADX, and androguard.

### What Was Removed
- ❌ All subscriptions, in-app purchases, premium features, paywalls, license checks
- ❌ All AdMob ads (banners, interstitials, app-open)
- ❌ Amplitude + Firebase Analytics (user tracking)
- ❌ Branch.io deep links (replaced by standard Android App Links)
- ❌ Google reCAPTCHA
- ❌ Play Integrity API
- ❌ Proprietary Cera Round Pro font (replaced with open-source Nunito)
- ❌ Airbnb Mavericks state management (replaced with ViewModel + StateFlow)

### What Was Kept
- ✅ All blocking features (accessibility-based content blocking)
- ✅ VPN DNS filtering (Cloudflare Family, OpenDNS FamilyShield, etc.)
- ✅ Keyword blocking (1,189 preset keywords across 37 languages)
- ✅ Accountability partner (Real Friend) via email approval
- ✅ Streak tracking with achievements
- ✅ Stop Me (focus mode) with instant + scheduled sessions
- ✅ Anti-uninstall protections (Device Admin, accessibility watchdog)
- ✅ App lock (PIN, password, pattern, biometric)
- ✅ Home-screen widgets (Stop Me + Streak)
- ✅ Firebase backend (Auth, Firestore, Messaging, Crashlytics)

## Download

Pre-built APKs are in the [`apk/`](apk/) directory:

| File | Size | Description |
|---|---|---|
| [`protect.yourself-v1.0.1-release.apk`](apk/protect.yourself-v1.0.1-release.apk) | ~20 MB | **Recommended** — production-style build |
| [`protect.yourself-v1.0.1-debug.apk`](apk/protect.yourself-v1.0.1-debug.apk) | ~28 MB | Debug build with logging enabled |

Both are signed with the Android Debug certificate (valid until 2056). For Play Store distribution, re-sign with your own release keystore.

## Features

### Content Blocking
- **Porn keyword blocking** — 1,189 preset keywords across 37 languages + custom keywords
- **Blocklist apps** — block specific apps from launching
- **Block all websites** — block every URL except whitelisted
- **Block new installs** — auto-block newly installed apps
- **Block in-app browsers** — block WebView inside other apps
- **Block YT Shorts / Search** — YouTube feature blocking
- **Block IG Reels / Search** — Instagram feature blocking
- **Block WhatsApp Status** — WhatsApp feature blocking
- **Block Snapchat Stories / Spotlight** — Snapchat feature blocking
- **Block Telegram Search** — Telegram feature blocking
- **Block image/video search** — Google/Bing image+video search blocking
- **SafeSearch enforcement** — DNS-level SafeSearch
- **Block unsupported browsers** — block all browsers except whitelisted
- **Block settings page by title** — block specific settings pages
- **Block notification drawer** — anti-circumvention
- **Block recent apps** — anti-circumvention
- **Block phone reboot** — auto-restart blocking after reboot

### VPN + DNS Blocking
- DNS-blocking VPN service (no actual VPN traffic — just DNS hijacking)
- 4 preset DNS providers: Cloudflare Family, OpenDNS FamilyShield, CleanBrowsing Family, AdGuard Family
- Custom DNS presets — user can add/edit/delete
- Per-app VPN routing — whitelist apps to bypass VPN
- Configurable notification (custom message + hide option)
- Always-on VPN support

### Stop Me (Focus Mode)
- Instant sessions: 15min, 30min, 1hr, 2hr + custom durations
- Scheduled sessions: recurring by day-of-week + start time + duration
- Per-session whitelist: apps allowed during Stop Me
- Session counter: total sessions completed
- Home-screen widget: orange button for one-tap start

### Streak Tracking
- Running streak with Lottie fire animation
- Streak history with relapse dates + types + notes
- 7 relapse types: Urge, Boredom, Stress, Accidental, Social Media, Porn, Other
- 9 achievement milestones: 1d, 3d, 7d, 14d, 30d, 60d, 90d, 180d, 365d
- Home-screen widget showing current day count

### Protective Modes
- **Long Sentence** — type a custom message to disable switches
- **Time Delay** — configurable delay before switch can be toggled
- **Real Friend** — accountability partner approval via email + deep links
- Request history — view pending + past protective mode requests

### Anti-Uninstall
- Device Admin — prevents uninstall via "Disable" button
- Accessibility Guard — watches for service disablement + alerts
- Prevent Uninstall switch — blocks the app info page to prevent uninstall
- Block Phone Reboot — auto-restart blocking on BOOT_COMPLETED
- Block Recent Apps — dismisses recent apps screen
- Block Notification Drawer — dismisses notification shade

### App Lock
- PIN (4-digit numeric)
- Password (alphanumeric, min 6 chars)
- Pattern (9-dot grid)
- Biometric (BiometricPrompt — fingerprint/face)
- Disable forgot password option

### Block Screen
- Dynamic block message per reason
- Optional user-uploaded motivation image
- Optional custom message
- Optional countdown timer (3–300 seconds)
- Rating prompt after every 20 blocks
- Optional redirect URL on Close

### Notifications
- Daily report — daily summary (block count + streak days)
- Stop Me foreground service notification
- Accessibility alert — high-priority when service disabled

### Onboarding
- Terms & Privacy agreement with checkbox
- Accessibility permission flow
- Display Pop-up Window permission flow
- Skip option on all screens

### Sign-in (Optional)
- Email + password sign in / sign up (Firebase Auth)
- Only required for backup/sync + accountability partner

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.0.21 |
| Build | Gradle (Kotlin DSL) | 8.10.2 |
| AGP | Android Gradle Plugin | 8.7.2 |
| Min SDK | 26 (Android 8.0) | — |
| Target SDK | 35 (Android 15) | — |
| Compile SDK | 35 | — |
| UI | Jetpack Compose + XML | BOM 2024.10.01 |
| State | ViewModel + StateFlow | — |
| Database | Room | 2.6.1 |
| Backend | Firebase BoM | 33.5.1 |
| Async | Kotlin Coroutines | 1.9.0 |
| Animations | Lottie Compose | 6.5.2 |
| WorkManager | androidx.work | 2.10.0 |
| Biometric | androidx.biometric | 1.1.0 |
| Logging | Timber | 5.0.1 |
| Date/Time | Joda-Time | 2.13.0 |
| JSON | Gson | 2.11.0 |

## Project Structure

```
Protect-Yourself/
├── app/
│   ├── src/main/
│   │   ├── java/protect/yourself/
│   │   │   ├── core/                          # ProtectYourselfApp, AppContainer
│   │   │   ├── commons/
│   │   │   │   ├── signaturekiller/           # KillerApplication (vendor)
│   │   │   │   └── utils/                     # broadcastReceivers, firebaseUtils,
│   │   │   │                                 # notificationUtils, workManager, etc.
│   │   │   ├── database/                      # Room (9 entities, 9 DAOs)
│   │   │   │   ├── core/                      # AppDatabase + callback
│   │   │   │   ├── blockScreensCount/
│   │   │   │   ├── pendingRequests/
│   │   │   │   ├── selectedApps/
│   │   │   │   ├── selectedKeywords/
│   │   │   │   ├── stopMeDuration/
│   │   │   │   ├── stopMeSessionCount/
│   │   │   │   ├── streakDates/
│   │   │   │   ├── switchStatus/              # 50+ switch state getters
│   │   │   │   └── vpnCustomDns/
│   │   │   ├── features/                      # All UI features
│   │   │   │   ├── mainActivityPage/          # Main + bottom nav + About
│   │   │   │   ├── blockerPage/               # Settings + Accessibility + VPN
│   │   │   │   ├── streakPage/                # Streak tracking + widget
│   │   │   │   ├── profilePage/               # Profile + backup
│   │   │   │   ├── selectAppPage/             # App picker
│   │   │   │   ├── appPasswordPage/           # App lock
│   │   │   │   ├── agreeTermsPage/            # Onboarding
│   │   │   │   ├── signinSignupPage/          # Firebase Auth
│   │   │   │   ├── protectedApps/             # Anti-uninstall guard
│   │   │   │   └── ...
│   │   │   └── theme/                         # Color, Type, Theme (DayNight)
│   │   ├── res/                               # layouts, drawables, strings, fonts
│   │   ├── assets/                            # 6 Lottie JSONs + preset keywords
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── google-services.json                   # Your Firebase config
├── apk/                                       # Pre-built APKs
├── docs/                                      # Implementation plan + phase progress
├── gradle/                                    # Wrapper + version catalog
├── README.md (this file)
├── CONTRIBUTING.md
├── LICENSE (MIT)
└── .gitignore
```

## Setup

### Prerequisites

- **Android Studio Ladybug (2024.2.1)+**
- **Android SDK Platform 35** (Android 15)
- **Android SDK Build-Tools 35.0.0**
- **JDK 17** (bundled with Android Studio)

### Clone & Open

```bash
git clone https://github.com/258044aamm-Dev/Protect-Yourself.git
cd Protect-Yourself
# Open in Android Studio: File > Open > select this folder
```

### Configure Firebase

The project includes your Firebase config at `app/google-services.json`. Firebase is used for:

- **Firebase Auth** — optional sign-in for backup/sync + accountability partner
- **Cloud Firestore** — backup/sync data
- **Firebase Cloud Messaging** — daily report push notifications
- **Firebase Crashlytics** — crash reporting

To use your own Firebase project, replace `app/google-services.json` with your own (must include package name `protect.yourself`).

### Build

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (signed with debug keystore by default)
./gradlew assembleRelease

# Run unit tests
./gradlew test
```

Output APKs: `app/build/outputs/apk/<variant>/`

### Configure Deep Links (Optional)

To support accountability partner approval links:

1. Own a domain (e.g., `protectyourself.app.link`)
2. Host `assetlinks.json` at `https://protectyourself.app.link/.well-known/assetlinks.json`
3. If you skip this, the `open://protectyourself` custom scheme works locally

## Installation

1. Download `protect.yourself-v1.0.1-release.apk` from the [`apk/`](apk/) directory
2. Transfer to your Android device
3. Open the APK file (enable "Install unknown apps" if prompted)
4. Tap **Install**
5. Open **Protect Yourself** from your app drawer

### First-Launch Setup

1. **Terms & Privacy** — read and agree
2. **Accessibility permission** — required for content blocking
3. **Display Pop-up Window permission** — required to show block screen
4. Optional: Device Admin (anti-uninstall), VPN (DNS blocking), App Lock

## Testing

The project includes 100+ unit tests:

| Test File | Tests | Coverage |
|---|---|---|
| `BlockerPageUtilsTest` | 30 | Keyword matching, URL/DNS validation |
| `PresetDataTest` | 17 | DNS presets, Stop Me durations, browsers |
| `SwitchStatusDaoTest` | 14 | Switch status DAO + values |
| `AllDaosTest` | 20 | All 9 Room DAOs |
| `StopMeManagerTest` | 7 | Day bitmask math |
| `StreakIdentifiersTest` | 18 | Relapse types + achievements |
| `IdentifiersTest` | 25 | All 4 identifier enums |
| `MyAccessibilityServiceTest` | 3 | Service constants |

Run tests:
```bash
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumentation tests (needs device)
```

## Documentation

- [Implementation Plan](docs/IMPLEMENTATION_PLAN.md) — comprehensive 9,800-word plan
- [Phase Progress](docs/PHASE_PROGRESS.md) — what was done in each phase
- [APK README](apk/README.md) — APK details + installation + verification
- [Contributing](CONTRIBUTING.md) — how to contribute

## License

MIT — see [LICENSE](LICENSE).

## Credits

- Original NopoX by PlanProductive (rebuilt from APK via reverse engineering)
- Nunito font by Vernon Adams (OFL)
- Lottie animations by AirBnB
- All open source libraries: Jetpack Compose, Room, Firebase, Timber, Joda-Time, Gson, Splitties (Apache 2.0)
