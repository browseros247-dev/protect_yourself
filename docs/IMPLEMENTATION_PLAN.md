# NopoX Rebuild — Comprehensive Implementation Plan

> **Document version**: 1.0
> **Date**: 2026-07-09
> **Source APK**: `NopoX_modified_signed.apk` (v1.0.53, build 1053)
> **Original package**: `com.planproductive.nopoz`
> **Rebuild package**: `protect.yourself`
> **Rebuild app name**: `protect.yourself`

---

## 1. Executive Summary

This document is the single source of truth for rebuilding the **NopoX** Android application from scratch as a new Android Studio project. The original source code has been permanently lost; reconstruction is based on static analysis of the APK using **Apktool 2.11.1**, **JADX 1.5.0**, **androguard 4.1.2**, and **uber-apk-signer 1.3.0**.

### 1.1 Original App Profile

| Property | Value |
|---|---|
| App name | NopoX |
| Category | Productivity / Digital Wellbeing / Porn & App Blocker |
| Version | 1.0.53 (build 1053) |
| Package | `com.planproductive.nopoz` (note: "nopoz", not "nopox") |
| Min SDK | 21+ (inferred from androidx deps) |
| Target SDK | 33 (Android 13) |
| Compile SDK | 33 |
| Language | Kotlin (primary), Java (generated code only) |
| UI framework | Jetpack Compose (hybrid — XML for widgets + block screen) |
| State mgmt | Airbnb Mavericks (MvRx) |
| DB | Room (9 DAOs, 7 migrations, version 7) |
| Backend | Firebase (Auth, Firestore, Messaging, AppCheck, Crashlytics) |
| Analytics | Amplitude + Firebase Analytics |
| Ads | AdMob (banner + app-open) |
| Billing | Google Play Billing (5 SKUs) |
| Deep links | Branch.io + custom scheme `open://nopox` |
| Other SDKs | Lottie, CanHub Image Cropper, Willy RatingBar, Timber, Splitties, Joda-Time |
| Anti-tampering | `bin.mt.signature.KillerApplication` (signature killer) |
| APK size | 23.1 MB |
| DEX files | 7 (15,507 classes; 659 in app's own package) |

### 1.2 Rebuild Goals (Confirmed by User)

1. **Pixel-perfect** UI/UX parity with the original.
2. **Hybrid UI** — Compose for screens, XML for widgets + PornBlockActivity + transparent activities (matches original architecture).
3. **Remove all payment functionality** — subscriptions, in-app purchases, premium features, paywalls, license checks.
4. **Remove all ads** — AdMob stripped entirely.
5. **Keep Crashlytics only** — strip Amplitude + Firebase Analytics.
6. **Keep Firebase backend** — Auth (optional), Firestore, Messaging.
7. **Replace Premium tab** with an **About / Help** tab.
8. **Custom package name**: `protect.yourself`.
9. **Custom app name**: `protect.yourself`.
10. **Min Android 8 (API 26)**, **Target SDK 35 (Android 15)**.
11. **DayNight theme** — follows system (dark primary, light secondary).
12. **English-only** strings.
13. **Comprehensive testing** — full unit + instrumentation suite (~300+ tests).
14. **Standard Android App Links** — replace Branch.io.
15. **Keep `bin.mt.signature.KillerApplication`** base class.
16. **Remove reCAPTCHA**; keep all other features intact.
17. **Aggressive timeline** (~2-3 weeks).

### 1.3 Critical Notes & Resolutions

> The user's selections contained a few items needing reconciliation. The following decisions are **LOCKED**:

| Item | User answer | Resolution |
|---|---|---|
| Block screen elements | "Logo + message" + remark "block screen as is" | **Keep ALL elements** (logo, message, motivation image, why-expandable, Close button with countdown, rating prompt, custom message + redirect URL). Strip only the AdMob banner + PU promotion banner (payment-related). |
| Block targets | Multi-select omitted some | Per remark "block target as is so implement all as is org" — **implement ALL block targets** exactly as original. |
| Onboarding | Skipped "Accessibility guide" + "Battery exemption" + "Lottie intros" | **Accessibility flow is mandatory** (app cannot function without it). Keep a minimal accessibility-permission flow (no brand-specific instructions per user choice). Skip battery exemption + Lottie intros. |
| Theme | "Follow system" | DayNight theme with dark as primary. Provide light variant via `values-night/` overrides. |
| Fidelity keepers | Only SignatureKiller | Drop Mavericks (use ViewModel + StateFlow), drop Cera Round Pro (use **Nunito** open-source equivalent), but **keep Lottie animations** (pixel-perfect requires them). |
| Testing + Timeline | Comprehensive (300+ tests) + Aggressive (2-3 weeks) | Acknowledged tension. Tests will be written alongside features in phase 3-5; final test pass in phase 6. |

---

## 2. Reverse-Engineering Environment Setup

### 2.1 Tools Installed

All tools are persisted under `/home/z/my-project/tools/` and `/home/z/my-project/apk-analysis/`.

| Tool | Version | Location | Purpose |
|---|---|---|---|
| Apktool | 2.11.1 | `/home/z/my-project/tools/apktool` + `apktool.jar` | Decode resources + manifest, rebuild APK |
| JADX | 1.5.0 | `/home/z/my-project/tools/jadx_dist/bin/jadx` | Decompile DEX → Java source |
| uber-apk-signer | 1.3.0 | `/home/z/my-project/tools/uber-apk-signer.jar` | Sign APK with debug or custom keystore |
| androguard | 4.1.2 | pip-installed | Python-side APK analysis |
| pyaxmlparser | 0.3.31 | pip-installed | Decode AXML without Apktool |
| OpenJDK | 21.0.11 | system | Run JADX + apktool + signer |
| Python | 3.12.13 | `/home/z/.venv/bin/python3` | Run analysis scripts |

### 2.2 Analysis Artifacts Produced

```
/home/z/my-project/apk-analysis/
├── apktool/                # Apktool decoded output (resources + smali)
│   ├── AndroidManifest.xml # decoded manifest (readable XML)
│   ├── res/                # decoded resources (layouts, drawables, strings, etc.)
│   ├── assets/             # Lottie JSONs, dexopt profiles
│   ├── smali/ ... smali7/  # disassembled DEX bytecode (7 DEX files)
│   └── unknown/            # files Apktool couldn't classify
├── jadx/                   # JADX decompiled output
│   ├── sources/com/planproductive/nopox/  # 659 app source files
│   └── resources/          # resource XML
└── raw/
    └── apk_unzipped/       # raw unzip of APK (for inspection)
```

### 2.3 Re-build & Sign Workflow (for future patching)

When the rebuild project is ready to be packaged + signed:

```bash
# 1. Build APK from Android Studio (or Gradle CLI)
./gradlew assembleRelease

# 2. Sign with uber-apk-signer (debug keystore by default)
java -jar /home/z/my-project/tools/uber-apk-signer.jar \
  -a app/build/outputs/apk/release/app-release-unsigned.apk \
  --out /home/z/my-project/download/

# 3. (Optional) Verify signature
java -jar /home/z/my-project/tools/uber-apk-signer.jar --verifySigned \
  /home/z/my-project/download/app-release-aligned-signed.apk
```

---

## 3. Original App — Feature Inventory (Reverse-Engineered)

This is the **complete feature inventory** extracted from the APK. Each item is **mandatory** in the rebuild unless explicitly marked `REMOVED`.

### 3.1 Application Class & Initialization

| Component | Original | Rebuild |
|---|---|---|
| Application class | `com.planproductive.nopox.core.NopoXApp` extends `bin.mt.signature.KillerApplication` | `protect.yourself.core.NopoXApp` extends `bin.mt.signature.KillerApplication` (kept per user request) |
| Initialization order | `ProcessLifecycleOwner` observer → `initRoomDBInstance` → `initMavericksInstance` → `initFirebaseAppCheck` → `initTimberLog` → `initCrashlytics` → `initBranchSDK` → `initAmplitude` → `BlockerPageUtils.updateAccessibilityBlockingValues` → `setAppContainer` → `AccessibilityPersistUtils.selfHealSafe` → `AccessibilityGuard.startWatching` | Same order, **drop** `initBranchSDK` (replaced by App Links), `initAmplitude`, `initFirebaseAppCheck` (kept only if Firestore rules require it — keep by default) |
| `AppContainer` | Holds `applicationScope`, `billingDataSource`, `premiumPageDataRepository` | Drop `billingDataSource` + `premiumPageDataRepository`. Keep `applicationScope`. Add `accountabilityRepository` for Real Friend feature. |
| `onAppBackgrounded` | Calls `BlockerPageUtils.updateAccessibilityBlockingValues(GlobalScope)` | Same. |

### 3.2 AndroidManifest Components

#### 3.2.1 Permissions (locked list — DO NOT modify)

```xml
<!-- Original 22 permissions — keep all except BILLING (removed) and AD_ID (ads removed) -->
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>  <!-- for KILL_BACKGROUND_PROCESSES in some flows -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="com.android.alarm.permission.SET_ALARM"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES"/>
<!-- REMOVED: com.android.vending.BILLING -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<!-- REMOVED: com.google.android.gms.permission.AD_ID -->
<uses-permission android:name="android.permission.INTERACT_ACROSS_USERS_FULL" android:protectionLevel="signature"/>
<uses-permission android:name="android.permission.INTERACT_ACROSS_USERS" android:protectionLevel="signature"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.USE_BIOMETRIC"/>
<uses-permission android:name="android.permission.USE_FINGERPRINT"/>
<!-- REMOVED: com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE -->
<uses-permission android:name="com.google.android.c2dm.permission.RECEIVE"/>
<uses-permission android:name="android.permission.REORDER_TASKS"/>
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>  <!-- needed for app list; add explicitly -->
<!-- New for targetSdk 35 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>  <!-- for Stop Me + VPN foreground services -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>  <!-- for accessibility self-heal -->
```

> **Note**: `android:isAccessibilityTool="true"` is set on `<application>`. This is critical — it makes the app show in the accessibility service picker as a tool, not a generic service. **Keep this attribute.**

#### 3.2.2 Activities (locked list — 7 activities)

| Activity | Original | Rebuild package | Notes |
|---|---|---|---|
| `MainActivity` | launcher, singleTask, scheme `open://nopox`, App Links `nopox.app.link` | `protect.yourself.features.mainActivityPage.MainActivity` | Keep `singleTask` launch mode + `showOnLockScreen="true"`. Replace Branch domains with `protectyourself.app.link` (user must configure). |
| `ProtectedAppsActivity` | "Protected Accessibility Apps" | `protect.yourself.features.protectedApps.ProtectedAppsActivity` | Shows list of apps protected from uninstall. |
| `TransparentActivity` | transparent theme, excludeFromRecents | `protect.yourself.features.transparentPage.TransparentActivity` | Used for displaying transparent overlays (e.g. permission prompts from background). |
| `PuPromotionActivity` | transparent | **REMOVED** | Was used to push PU (Prevent Uninstall) premium upsell. Not needed. |
| `PremiumAnywhereActivity` | transparent | **REMOVED** | Triggered premium upsell from anywhere. Not needed. |
| `InAppReviewActivity` | transparent | `protect.yourself.features.ratingPage.inAppReview.InAppReviewActivity` | Wrapper for Play Core in-app review. Keep for rating prompt from profile page. |
| `com.canhub.cropper.CropImageActivity` | library activity | Same (use library) | Used for cropping motivation images. Keep. |

#### 3.2.3 Services (locked list — 5 services)

| Service | Original | Rebuild | Notes |
|---|---|---|---|
| `MyFirebaseMessagingService` | FCM | `protect.yourself.commons.utils.firebaseUtils.MyFirebaseMessagingService` | Keep — needed for daily report notifications (FCM not strictly needed if only local notifs, but kept for future extensibility). |
| `MyAccessibilityService` | BIND_ACCESSIBILITY_SERVICE, exported=true, priority=100 | `protect.yourself.features.blockerPage.service.MyAccessibilityService` | **Core feature.** Reads window content, blocks URLs/keywords, watches for app switches. |
| `MyVpnService` | BIND_VPN_SERVICE, supports always-on | `protect.yourself.features.blockerPage.service.MyVpnService` | DNS-blocking VPN. Keep always-on meta-data. |
| `NotificationActionService` | foreground service for notif actions | `protect.yourself.commons.utils.notificationUtils.NotificationActionService` | Handles notification button actions (e.g. "Stop" button on Stop Me notif). |
| `com.google.android.gms.ads.AdService` | AdMob | **REMOVED** | Ads stripped. |

#### 3.2.4 Broadcast Receivers (locked list — 7 receivers)

| Receiver | Purpose | Keep? |
|---|---|---|
| `AppSystemActionReceiver` | Listens for `CONNECTIVITY_CHANGE` | ✅ |
| `AppSystemActionReceiverAllTime` | BOOT_COMPLETED, REBOOT, LOCKED_BOOT_COMPLETED, QUICKBOOT_POWERON, SCREEN_ON, USER_PRESENT | ✅ — needed to restart blocking services after reboot. |
| `AppSystemActionReceiverAllTimeWithData` | MY_PACKAGE_REPLACED, PACKAGE_ADDED, PACKAGE_REMOVED | ✅ — needed for "block new install apps" feature. |
| `MyDeviceAdminReceiver` | Device admin for anti-uninstall | ✅ — needed for Prevent Uninstall. |
| `StopMeWidget` | AppWidgetProvider for Stop Me | ✅ |
| `StreakWidget` | AppWidgetProvider for Streak | ✅ |
| `com.google.android.gms.measurement.AppMeasurementReceiver` | Firebase Analytics | **REMOVED** — analytics stripped. |

#### 3.2.5 Content Providers

| Provider | Purpose | Keep? |
|---|---|---|
| `androidx.core.content.FileProvider` (authority: `<pkg>.fileprovider`) | Share files (exported keywords, screenshots) | ✅ |
| `com.canhub.cropper.CropFileProvider` (authority: `<pkg>.cropper.fileprovider`) | Crop library file provider | ✅ |
| `com.google.android.gms.ads.MobileAdsInitProvider` | AdMob auto-init | **REMOVED** |
| `androidx.startup.InitializationProvider` | WorkManager + Emoji2 + Lifecycle + ProfileInstaller + Splitties init | ✅ (remove `WorkManagerInitializer` if using custom config) |

#### 3.2.6 App Links (replacing Branch.io)

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="https"/>
    <data android:host="protectyourself.app.link"/>           <!-- primary -->
    <data android:host="protectyourself-alternate.app.link"/> <!-- alternate -->
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:host="protectyourself" android:scheme="open"/>  <!-- custom scheme -->
</intent-filter>
```

> **Action item**: User must own `protectyourself.app.link` domain + host `assetlinks.json` at `https://protectyourself.app.link/.well-known/assetlinks.json`. Until that's done, only the `open://` scheme will work (accountability partner approval will fall back to deep-link sharing).

---

## 4. Rebuild Architecture

### 4.1 Package Structure

```
protect.yourself/
├── core/                           # Application class, DI container
│   ├── NopoXApp.kt
│   └── AppContainer.kt
├── commons/
│   ├── components/                 # Reusable Compose components
│   │   ├── appDisplay/             # App list items, icons
│   │   └── carousel/               # Carousel composables
│   └── utils/
│       ├── adDisplayUtils/         # REMOVED (ads stripped)
│       ├── alarmManager/           # AlarmManager wrappers (Stop Me scheduling)
│       ├── amplitudeUtils/         # REMOVED (analytics stripped)
│       ├── broadcastReceivers/     # System action receivers
│       ├── firebaseUtils/          # FCM, Firestore, Auth wrappers
│       ├── googleBillingUtils/     # REMOVED (billing stripped)
│       ├── localBroadCastUtils/    # LocalBroadcastManager wrappers
│       ├── notificationUtils/      # Notification channels + builders
│       └── workManager/            # WorkManager workers (periodic checks)
├── database/                       # Room database
│   ├── core/AppDatabase.kt         # @Database, 9 entities, 7 migrations
│   ├── blockScreensCount/
│   ├── pendingRequests/
│   ├── selectedApps/
│   ├── selectedKeywords/
│   ├── stopMeDuration/
│   ├── stopMeSessionCount/
│   ├── streakDates/
│   ├── switchStatus/               # 60+ switch states (the source of truth for all toggles)
│   └── vpnCustomDns/
├── features/
│   ├── agreeTermsPage/             # Onboarding: terms + privacy
│   ├── appPasswordPage/            # App lock: pattern, PIN, password, biometric
│   ├── blockerPage/                # Main blocker page + settings + components
│   │   ├── components/             # 30+ Compose screens (settings sub-pages)
│   │   ├── data/                   # Item models for settings pages
│   │   ├── identifiers/            # Enums (SettingPageItemIdentifiers, etc.)
│   │   ├── repository/             # BlockerPageRepository
│   │   ├── service/                # MyAccessibilityService, MyVpnService
│   │   ├── utils/                  # BlockerPageUtils, PornBlockPage, etc.
│   │   └── widget/                 # StopMeWidget
│   ├── internetPage/               # VPN status / internet blocking sub-page
│   ├── mainActivityPage/           # MainActivity + bottom nav + main VM
│   ├── premiumPage/                # REMOVED (replaced by About page)
│   ├── profilePage/                # Profile + backup + FAQ + about
│   ├── protectedApps/              # Anti-uninstall protection list + shield overlay
│   ├── puPromotionPage/            # REMOVED (was PU premium upsell)
│   ├── ratingPage/                 # In-app review + rating prompt
│   ├── selectAppPage/              # App picker (used by block list, VPN whitelist, etc.)
│   ├── signinSignupPage/           # Firebase Auth sign-in/up
│   ├── streakPage/                 # Streak tracking + history + achievements
│   │   ├── components/             # Streak sub-pages
│   │   ├── data/
│   │   ├── identifiers/
│   │   ├── repository/
│   │   ├── utils/
│   │   └── widget/                 # StreakWidget
│   └── transparentPage/            # TransparentActivity + helpers
├── theme/                          # Compose theme (colors, typography, shapes)
│   ├── extensions/
│   └── modifiers/
└── BuildConfig                     # Generated
```

### 4.2 Tech Stack (Locked)

| Layer | Technology | Version (target) |
|---|---|---|
| Language | Kotlin | 2.0.21 |
| Build | Gradle (Kotlin DSL) | 8.10 |
| AGP | Android Gradle Plugin | 8.7.0 |
| Min SDK | 26 | — |
| Target SDK | 35 | — |
| Compile SDK | 35 | — |
| UI | Jetpack Compose | BOM 2024.10.01 |
| Compose Compiler | 2.0.21 | matches Kotlin |
| Activity | `androidx.activity:activity-compose` | 1.9.3 |
| Lifecycle | `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 |
| Navigation | `androidx.navigation:navigation-compose` | 2.8.4 |
| State mgmt | **ViewModel + StateFlow** (replace Mavericks) | — |
| Database | Room | 2.6.1 |
| DI | Manual (`AppContainer`) — match original | — |
| Async | Kotlin Coroutines + Flow | 1.9.0 |
| WorkManager | `androidx.work:work-runtime-ktx` | 2.10.0 |
| Firebase | BoM 33.0.0 (Auth, Firestore, Messaging, Crashlytics) | — |
| Lottie | `com.airbnb.android:lottie-compose` | 6.5.0 |
| Image cropper | `com.vanniktech:android-image-cropper` (CanHub fork) | 4.6.0 |
| Rating bar | `com.github.ome450901:SimpleRatingBar` | 1.5.1 |
| Logging | Timber | 5.0.2 |
| Init | Splitties (`splitties-init:AppCtx`) | 3.0.0 |
| Date/time | Joda-Time | 2.13.0 |
| Biometric | `androidx.biometric:biometric` | 1.2.0 |
| Custom tabs | `androidx.browser:browser` | 1.8.0 |
| App links | Standard Android (no Branch.io) | — |
| **REMOVED** | AdMob, Amplitude, Branch.io, Google Play Billing, reCAPTCHA, Play Integrity | — |

### 4.3 State Management Migration (Mavericks → StateFlow)

The original uses Airbnb Mavericks (MvRx) for state. We will migrate to `ViewModel + StateFlow`:

| Original (Mavericks) | Rebuild (StateFlow) |
|---|---|
| `class FooState : MavericksState` | `data class FooState(...)` |
| `class FooViewModel(initialState) : MavericksViewModel<FooState>` | `class FooViewModel : ViewModel() { private val _state = MutableStateFlow(FooState()); val state = _state.asStateFlow() }` |
| `setState { copy(field = newValue) }` | `_state.update { it.copy(field = newValue) }` |
| `onAsync(SomeState::foo) { ... }` | Collect `state.map { it.foo }.distinctUntilChanged()` |
| `viewModel.subscribe(this) { ... }` | `viewModel.state.collectLatest { ... }` in `LifecycleOwnerKt.repeatOnLifecycle` |

> All `*State` and `*ViewModel` classes from the original will be ported 1:1 to this pattern.

---

## 5. Feature-by-Feature Implementation Spec

### 5.1 Onboarding Flow

**Original**: `agreeTermsPage/` (terms + privacy + checkbox) → accessibility permission flow → display popup permission → intro premium page.

**Rebuild** (per user answers: Terms & Privacy + Popup permission + Skip option; NO brand-specific accessibility guide; NO Lottie intros; NO battery exemption):

1. **AgreeTermsPage** — Compose screen with:
   - Scrollable terms text + privacy policy text.
   - Required checkbox: "I have read and agree to the Terms and Privacy Policy."
   - "Continue" button (disabled until checkbox ticked).
2. **AccessibilityPermissionPage** — simple flow:
   - One-page explanation: "NopoX needs Accessibility permission to block content."
   - "Grant permission" button → opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
   - App detects when permission is granted (via `AccessibilityManager.isAccessibilityServiceEnabled()`) and auto-advances.
   - "Skip" button (per user choice) — allows deferring; app shows persistent banner on main screen until granted.
3. **PopupPermissionPage** — Display Pop-up Window (SYSTEM_ALERT_WINDOW):
   - Explanation: "Needed to show block screen over other apps."
   - "Grant" button → `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` intent.
   - "Skip" button.
4. ~~Intro premium page~~ — **REMOVED** (no premium).
5. ~~Battery exemption~~ — **REMOVED** (user choice). Instead, use WorkManager with proper foreground service for critical blocking.

### 5.2 Main Activity & Bottom Navigation

**Original**: `MainActivity` (AppCompatActivity, singleTask, showOnLockScreen) → `setContent { MainActivityComposable() }`.

**Bottom nav items** (per `MainPageScreen.kt`):
- `NopoX` (icon: `ic_launcher_foreground`, label: app name)
- `Streak` (icon: `ic_fire`, label: `@string/streak`)
- `Premium` (icon: `ic_nav_crown`, label: `@string/nav_premium`) → **REPLACED** with `About` (icon: `ic_info`, label: `@string/about`)
- `Profile` (icon: `ic_profile`, label: `@string/profile`)

**Rebuild nav**:
```kotlin
sealed class MainPageScreen(route, resourceId, icon) {
    data object NopoX : MainPageScreen("NopoX", R.string.app_name, R.drawable.ic_launcher_foreground)
    data object Streak : MainPageScreen("Streak", R.string.streak, R.drawable.ic_fire)
    data object About : MainPageScreen("About", R.string.about, R.drawable.ic_info)
    data object Profile : MainPageScreen("Profile", R.string.profile, R.drawable.ic_profile)
}
```

**MainActivity.onCreate flow** (port from original):
1. `super.onCreate()`
2. ~~`initGoogleAdMobForAds(this)`~~ — REMOVED.
3. `initPageForLockScreen()` — show activity over lock screen.
4. `initReceivers()` — register `AppSystemActionReceiver`.
5. `initPeriodicWorkManager()` — enqueue `WorkerUtils.initAppDataCheckWorker`.
6. `initPageUi()` — `setContent { NopoXApp() }`.
7. `requestPostNotification()` — request `POST_NOTIFICATIONS` permission on API 33+.

### 5.3 Blocker Page (Home Screen)

This is the most complex screen — the main "NopoX" tab. Per `BlockerPageViewModel` (40+ sub-init methods) and `BlockerPageRepository`.

#### 5.3.1 Page Sections

The blocker page renders as a single scrolling column with sections (per `SettingPageItemIdentifiers`):

| Section | Items | Implementation Status |
|---|---|---|
| `SECTION_ALERT` | `BLOCK_SCREEN_COUNT` (counter), `PREMIUM_OFFER` (REMOVED), `LOGIN_NOW` (optional) | Counter shown; Login prompt only if signed-out + user enables backup |
| `SECTION_ACCOUNTABILITY_PARTNER` | `LONG_SENTENCE`, `LONG_SENTENCE_CUSTOM_MESSAGE`, `TIME_DELAY`, `TIME_DELAY_CUSTOM_DURATION`, `REAL_FRIEND`, `DAILY_REPORT`, `SUGGEST_PROTECTIVE_MODE`, `REQUEST_HISTORY` | All kept |
| `SECTION_CONTENT_BLOCKING` | `SUPPORTED_BROWSERS`, `SUPPORTED_SOCIAL_MEDIA`, `PORN_BLOCKER`, `BLOCKER_CUSTOM_KEYWORD_WEBSITE`, `BLOCKLIST_APPS`, `BLOCK_ALL_WEBSITE`, `SAFE_SEARCH`, `BLOCK_IMAGE_VIDEO_SEARCH`, `MAKE_ANY_BROWSER_SUPPORTED` | All kept |
| `SECTION_INSTA_YT_BLOCKING` | `BLOCK_SNAPCHAT_STORIES`, `BLOCK_SNAPCHAT_SPOTLIGHT`, `BLOCK_INSTA_REELS`, `BLOCK_INSTA_SEARCH`, `BLOCK_WHATSAPP_STATUS`, `BLOCK_YT_SHORTS`, `BLOCK_YT_SEARCH`, `BLOCK_TELEGRAM_SEARCH` | All kept (per "block target as is") |
| `SECTION_UNINSTALL_PROTECTION` | `PREVENT_UNINSTALL_SETTINGS`, `BLOCK_NOTIFICATION_DRAWER`, `BLOCK_PHONE_REBOOT`, `BLOCK_RECENT_APPS`, `BLOCK_SETTING_PAGE_BY_TITLE`, `BLOCK_SETTING_PAGE_BY_TITLE_APPS` | All kept |
| `SECTION_ADVANCE_FEATURE` | `BLOCK_UNSUPPORTED_BROWSERS`, `WHITELIST_UNSUPPORTED_BROWSER`, `VPN`, `WHITELIST_VPN_APPS`, `VPN_NOTIFICATION_MESSAGE`, `VPN_NOTIFICATION_HIDE`, `BLOCK_NEW_INSTALL_APPS`, `BLOCK_IN_APP_BROWSERS`, `BLOCKED_SCREEN_IMAGE`, `BLOCKED_SCREEN_MESSAGE`, `BLOCKED_SCREEN_COUNTDOWN`, `CUSTOM_REDIRECT_URL_APP`, `BLOCK_WHITELIST_DETECTED_APP`, `SET_APP_LOCK`, `TOUCH_ID`, `DISABLE_FORGOT_PASSWORD` | All kept |
| `SECTION_FAQ` | `KEEP_NOPOX_LIVE` (battery/performance tips) | Kept |

#### 5.3.2 Switch Persistence

All toggles are persisted in `switchStatus` Room table (60+ entries). Each switch has:
- `key: String` (e.g. `"porn_blocker_switch"`)
- `value: Boolean` (current state)
- Some switches have additional fields (e.g. `BLOCKED_SCREEN_COUNTDOWN` has `value: Int` seconds, `BLOCKED_SCREEN_MESSAGE` has `value: String`)

Schema (from `SwitchStatusItemModel`):
```kotlin
@Entity(tableName = "switch_status")
data class SwitchStatusItemModel(
    @PrimaryKey val key: String,
    val value: String,  // serialized value (Boolean as "true"/"false", Int as string, etc.)
    val type: String    // "boolean", "int", "string"
)
```

#### 5.3.3 Protective Modes (3 modes)

Each switch that the user wants to turn OFF may require satisfying a protective mode:

1. **Long Sentence** — User must type a custom message (≥20 chars, configurable in `LONG_SENTENCE_CUSTOM_MESSAGE`) to confirm disabling. Stored as `long_sentence_message` in switch table.

2. **Time Delay** — Configurable delay (1–300 seconds) before the switch can be toggled off. UI shows countdown. Stored as `time_delay_duration` (Int seconds).

3. **Real Friend** — Disable request is sent via email to accountability partner. Partner approves via deep link (`open://protectyourself?action=approve&requestId=X`). Stored in `pending_request_table`.

Only one protective mode can be active at a time (stored as `accountability_partner_type` switch).

#### 5.3.4 Keyword Blocking (Porn Blocker)

**Original data flow** (from `BlockerPageUtils`):

1. On app first launch, load **preset keywords** from compiled-in constants (not from assets — keywords are in Kotlin code, see `BlockerPageUtils.defaultLanguageWiseBlockKeywords` and `defaultLanguageWiseWhiteListKeywords`).
2. User can add custom keywords (blocklist or whitelist) — stored in `selectedKeywords` Room table.
3. When accessibility service detects a URL change or window title change, it:
   - URL-decodes the text
   - Checks against whitelist (if URL contains whitelist keyword → allow)
   - Checks against blocklist (if URL contains blocklist keyword → block)
   - Uses regex `websiteRegex` for URL validation

**Keyword types** (from `SelectedKeywordIdentifier`):
- `PORN_BLOCK_WORDS` — blocklist keywords (block URLs containing these)
- `PORN_WHITE_LIST_WORDS` — whitelist keywords (allow URLs containing these, overrides block)
- `SETTING_KEYWORDS_LIST_WORDS` — settings page titles to block

**Custom keyword limits** (premium-gated in original):
- Original: 3 keywords free, unlimited premium
- Rebuild: **unlimited for all users** (premium removed)

**Bulk import** — keyword import/export via JSON file (per `BlockerPageUtils.exportKeyWordToDownloadFolder` and `getKeywordFromFile`).

#### 5.3.5 Block Targets (ALL must be implemented)

Per user remark: "block target as is so implement all as is org" — implement all of these:

| Target | Implementation |
|---|---|
| **Porn keywords** (browser URL/title) | Accessibility service listens for `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` + `TYPE_WINDOW_CONTENT_CHANGED`. Extracts URL from supported browsers' address bars via `AccessibilityNodeInfo`. Checks against keyword lists. |
| **Blocklist apps** | When foreground app changes to a blocklisted package, show `PornBlockActivity` overlay. |
| **Block all websites** | When switch on, ANY URL navigation in supported browsers triggers block (unless URL is in whitelist). |
| **Block new installs** | `PACKAGE_ADDED` receiver inserts new package into `block_new_install_apps_item` list. App then gets blocked on launch. |
| **Block in-app browsers** | Per-app list (`block_in_app_browser_apps`). Accessibility detects known in-app browser class names (e.g. `com.google.android.webview`). |
| **YT Shorts** | Block when accessibility detects YT URL contains `/shorts/` or YT package foreground + Shorts view class visible. |
| **YT Search** | Block YT search bar interaction. |
| **IG Reels** | Block Instagram Reels tab. |
| **IG Search** | Block Instagram search bar. |
| **WhatsApp Status** | Block WhatsApp Status tab. |
| **Snapchat Stories** | Block Snapchat Stories view. |
| **Snapchat Spotlight** | Block Snapchat Spotlight tab. |
| **Telegram Search** | Block Telegram search. |
| **Block image/video search** | Block URLs containing `/images`, `/videos`, `tbm=isch`, `tbm=vid` etc. |
| **SafeSearch enforcement** | DNS-level: when VPN active, redirect `www.google.com` → `forcesafesearch.google.com`, etc. |
| **Block unsupported browsers** | All browsers not in `whitelist_unsupported_browser` get blocked. |
| **Make any browser supported** | App injects accessibility scraping for any browser package the user adds. |
| **Block settings page by title** | Accessibility detects settings activity title; if matches `setting_keywords_list`, block. |
| **Block notification drawer** | Accessibility detects `StatusBar` window → block. |
| **Block recent apps** | Accessibility detects recent apps window → block. |
| **Block phone reboot** | Receiver on `BOOT_COMPLETED` → immediately re-launch NopoX + start blocking. |

#### 5.3.6 Block Screen (PornBlockActivity)

**Original layout** (`page_porn_block.xml`):
```xml
<FrameLayout background="@color/bg_blue_dark">
  <LinearLayout vertical gravity="center">
    <LinearLayout horizontal center>
      <ImageView src="@mipmap/ic_launcher" 40dp/>
      <TextView text="@string/app_name" CeraRoundProBold 24sp/>
    </LinearLayout>
    <View height="8dp"/>
    <ImageView id="@id/imgMotivation" gone 200dp/>
    <TextView id="@id/txtPageMessage" 24sp white center/>
    <LinearLayout id="@id/llRatingContainer" gone>
      <TextView id="@id/txtRatingMessage"/>
      <RotationRatingBar id="@id/ratingBar"/>
    </LinearLayout>
    <ImageView id="@id/imgPuBanner" src="@drawable/banner_pu_bw" 250dp/>  <!-- REMOVED -->
  </LinearLayout>
  <LinearLayout bottom>
    <TextView id="@id/txtWhyContainer" gone/>
    <TextView id="@id/txtWhy" accent_color 16sp/>
    <FrameLayout id="@id/txtCloseContainer" bg="@drawable/bg_orange_gradient">
      <TextView id="@id/txtClose" 24sp bold white text="@string/close"/>
    </FrameLayout>
    <FrameLayout id="@id/adViewContainer"/>  <!-- REMOVED (AdMob banner) -->
  </LinearLayout>
</FrameLayout>
```

**Rebuild** (per user: "block screen as is" — keep ALL elements except ads + PU banner):

| Element | Keep? | Notes |
|---|---|---|
| App logo + app name header | ✅ | Use `protect.yourself` name + custom logo |
| `imgMotivation` (custom motivation image) | ✅ | User uploads via Settings; stored as file path in `switchStatus.blocked_screen_image_path` |
| `txtPageMessage` (block message) | ✅ | Dynamic per block reason (per `block_page_default_*` strings) |
| `llRatingContainer` (rating prompt) | ✅ | Shows after N blocks (configurable threshold, default 20) |
| `imgPuBanner` (PU promo banner) | ❌ | Was premium upsell — REMOVED |
| `txtWhyContainer` / `txtWhy` ("Why am I seeing this?") | ✅ | Expandable; shows reason text |
| `txtCloseContainer` / `txtClose` (Close button) | ✅ | Orange gradient button; with optional countdown timer (3–300s, stored in `block_screen_count_down` switch) |
| `adViewContainer` (AdMob banner) | ❌ | REMOVED |
| Custom message feature | ✅ | User-configurable message stored in `block_screen_custom_message` switch |
| Custom redirect URL feature | ✅ | When Close button tapped, optionally redirect to a URL stored in `block_screen_redirect_url` switch |

**Block screen lifecycle** (per `PornBlockPage.kt`):
1. `PornBlockActivity.onCreate` → set content view → call `PornBlockPage.init()`.
2. `initPageMessage()` — pick appropriate message based on block reason (passed via Intent extras).
3. `initPageMotivationImage()` — load user's motivation image if set.
4. `initCloseButton()` — set up Close button with countdown if enabled.
5. `initRating()` — show rating prompt if block count > threshold.
6. ~~`initBannerAd()`~~ — REMOVED.
7. ~~`initPuPromotionBanner()`~~ — REMOVED.
8. `storeAndGetBlockCount()` — increment block counter, store in `block_screen_count_table`.
9. On Close tap → finish activity (or redirect to URL if configured).

#### 5.3.7 VPN + DNS Blocking

**Original**: `MyVpnService` extends `VpnService`. Custom DNS routing via `VpnService.Builder.addDnsServer()`.

**DNS presets** (stored in `vpn_custom_dns` table):
- Cloudflare Family: `1.1.1.3`, `1.0.0.3`
- OpenDNS FamilyShield: `208.67.222.123`, `208.67.220.123`
- CleanBrowsing Family: `185.228.168.168`, `185.228.169.168`
- AdGuard Family: `94.140.14.15`, `94.140.15.16`
- User custom presets (add/edit/delete via `AddVpnCustomDnsPageContent`)

**Rebuild**:
```kotlin
class MyVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Read selected DNS from vpn_custom_dns table
        // 2. Build VPN interface with addDnsServer() for each DNS
        // 3. Start foreground service notification
        // 4. Loop packet routing (no actual VPN traffic — just DNS hijacking)
    }
}
```

**VPN whitelist apps** — apps that bypass VPN (per `vpn_whitelist_apps`):
```kotlin
builder.addAllowedApplication(packageName) // for each whitelisted app
// or
builder.addDisallowedApplication(packageName) // for each blocked app
```

**VPN notification** — foreground service notification with:
- Title: "NopoX VPN is active"
- Custom message (stored in `vpn_notification_custom_message` switch)
- Hide option (stored in `vpn_notification_hide` switch)

#### 5.3.8 Stop Me (Focus Mode)

**Original data model**:
```kotlin
@Entity(tableName = "stop_me_duration_table")
data class StopMeDurationItemModel(
    @PrimaryKey val key: String,
    val duration: Long,           // milliseconds
    val endTime: Long,            // for instant: end timestamp; for schedule: 0
    val days: Int,                // for schedule: bitmask of days (1=Sun, 2=Mon, ... 64=Sat); for instant: 0
    val startTime: Long,          // for schedule: start time of day in millis; for instant: 0
    val startTimeDayMillis: Long  // for schedule: next trigger time
)
```

**Stop Me types**:
- **Instant** — pre-set durations: 15min, 30min, 1hr, 2hr. User can add custom durations (stored as `instant` type with `duration > 0`).
- **Scheduled** — recurring: days of week (bitmask) + start time + duration. Scheduled via `AlarmManager.setExactAndAllowWhileIdle()`. Stored as `schedule` type.

**Stop Me session count** — total sessions completed, stored in `stop_me_session_count_table` (key=0, value=count).

**Stop Me whitelist apps** — apps that remain accessible during Stop Me session (per `whitelist_stop_me_apps` in `selected_apps` table).

**Stop Me widget** (`StopMeWidget`):
- Layout: `stop_me_widget.xml` — orange gradient button with focus icon + "Stop Me" text.
- Tap → starts instant Stop Me session (default 25min Pomodoro, configurable).
- Long-press → opens widget configuration.
- Updates every 86400000ms (24h) per `updatePeriodMillis`.

#### 5.3.9 Anti-Uninstall Mechanisms (All Kept)

1. **Device Admin** (`MyDeviceAdminReceiver`):
   - `<uses-policies>` empty (just prevents uninstall via "Disable" button).
   - User must deactivate Device Admin in Settings before uninstalling.
   - When Device Admin is disabled, app shows warning + re-prompts.

2. **Accessibility Watchdog** (`AccessibilityGuard`):
   - Started in `NopoXApp.onCreate`.
   - Detects when accessibility service is disabled (e.g. user killed it).
   - Re-enables via `AccessibilityPersistUtils.selfHealSafe()`.
   - Uses `AccessibilityService.takeScreenshot()` + `performGlobalAction()` for self-healing.

3. **Prevent Uninstall switch** — when ON:
   - Accessibility service listens for "NopoX" app info page.
   - When detected, immediately re-launches MainActivity (prevents user from tapping Uninstall).
   - Block screen shown with message: "Uninstall is prevented. Disable Prevent Uninstall switch first."

4. **Block Phone Reboot** — `AppSystemActionReceiverAllTime` listens for `BOOT_COMPLETED`:
   - Immediately starts accessibility service + VPN service.
   - Shows notification "NopoX is protecting you".

5. **Block Recent Apps** — accessibility listens for `StatusBar` window or recent apps activity:
   - When detected, performs `GLOBAL_ACTION_HOME` to dismiss.

6. **Block Notification Drawer** — accessibility listens for notification shade:
   - When detected, performs `GLOBAL_ACTION_NOTIFICATIONS` to dismiss (or `GLOBAL_ACTION_HOME`).

#### 5.3.10 App Lock (All Kept)

**Original**: `appPasswordPage/` with `patternLock/` sub-package.

**Lock types** (from `AppLockTypeIdentifiers`):
- **Pattern** — 9-dot pattern lock (3x3 grid). Min 4 dots connected.
- **PIN** — 4-digit numeric.
- **Password** — alphanumeric, min 6 chars.
- **Biometric** — `BiometricPrompt` (face/fingerprint).

**Forgot password** — `DISABLE_FORGOT_PASSWORD` switch:
- When ON: forgot password option hidden (user can't recover without uninstalling).
- When OFF: forgot password sends reset email via Firebase Auth.

**Lock trigger** — `ProcessLifecycle.Event.ON_STOP` → ON_START:
- When app comes back to foreground, show lock screen.
- Lock screen layout: pattern grid OR PIN pad OR password field + biometric prompt.

#### 5.3.11 Select App Page

Used by multiple features (block list, VPN whitelist, Stop Me whitelist, supported browsers, etc.).

**Original**: `SelectAppPageViewModel.getAllAppList` → loads all installed apps via `PackageManager.getInstalledApplications()`. Filtered by `SelectedAppListIdentifier` (which category).

**App categories** (from `SelectedAppListIdentifier`):
- `ALL_APPS` — every installed app
- `SUPPORTED_SOCIAL_MEDIA_APPS` — Instagram, WhatsApp, Snapchat, Telegram, YouTube (hardcoded list in `BlockerPageUtils.supportedSocialMediaApps`)
- `SUPPORTED_BROWSER_APPS` — Chrome, Firefox, Brave, Edge, Opera, Samsung Internet, etc.
- `BLOCK_APPS` — user's blocklist
- `BLOCK_SETTING_PAGE_BY_TITLE_APPS` — settings apps
- `MAKE_ANY_BROWSER_SUPPORTED_APPS` — user-added browsers
- `VPN_WHITELIST_APPS` — apps that bypass VPN
- `BLOCK_IN_APP_BROWSER_APPS` — apps where in-app browser is blocked
- `BLOCK_NEW_INSTALL_APPS` — newly installed apps auto-added
- `WHITELIST_UNSUPPORTED_BROWSER` — browsers allowed when "block unsupported browsers" is ON
- `WHITELIST_STOP_ME_APPS` — apps allowed during Stop Me
- `BLOCK_WHITELIST_DETECTED_APPS` — apps detected via accessibility events

**Rebuild**: Port `SelectAppPageViewModel` + `SelectAppPageState` 1:1. Use `PackageManager.getInstalledApplications(PackageManager.GET_META_DATA)` and filter.

### 5.4 Streak Page

**Original data model**:
```kotlin
@Entity(tableName = "streak_dates_table")
data class StreakDatesItemModel(
    @PrimaryKey val startTime: Long,     // day start timestamp
    val endTime: Long,                   // day end timestamp (or relapse time)
    val type: String = "",               // relapse type identifier
    val freeText: String = ""            // user note
)
```

**Streak features** (per user: all kept):

1. **Running streak** — current day count since last relapse. UI: large number + `streak_fire.json` Lottie animation (animated fire).

2. **Streak history** — calendar/timeline view showing:
   - Days where streak was active (highlighted)
   - Relapse days (red dot)
   - Tap a day → see note + relapse type

3. **Relapse tracking** — "I relapsed" button:
   - Pick relapse type (from `RelapseTypeIdentifiers` enum): URGE, BOREDOM, STRESS, ACCIDENTAL, SOCIAL_MEDIA, PORN, OTHER
   - Optional free-text note
   - Save → inserts new `StreakDatesItemModel` with `endTime = now`

4. **Achievements** — milestone badges:
   - 1 day, 3 days, 7 days, 14 days, 30 days, 60 days, 90 days, 180 days, 365 days
   - First-time badge unlock → `twinkle_crown.json` Lottie animation overlay
   - Badges shown in grid

5. **Streak widget** (`StreakWidget`):
   - Layout: `streak_widget.xml` — small 1x1 widget showing current streak day count.
   - Tap → opens app at Streak tab.

6. **Relapsed types stats** — pie chart or bar chart showing breakdown by relapse type (URGE: 5, BOREDOM: 3, etc.). Uses Compose Canvas or MPAndroidChart.

### 5.5 Profile Page

**Original items kept** (per user):

| Item | Implementation |
|---|---|
| Backup/Sync | Toggle. When ON: requires Firebase Auth sign-in. Uploads all DB tables to Firestore. Conflict resolution: last-write-wins. |
| Import/Export | Export: serialize all DB tables to JSON, save to `Downloads/NopoX_backup_<timestamp>.json` via `FileProvider`. Import: pick JSON file, validate, replace DB contents. |
| FAQ + About | Static Compose screens. FAQ from `faq_*` strings. About: app version, credits, privacy policy link, terms link. |
| Share app | System share sheet with Play Store link (or direct APK if sideloaded). |
| Contact email | `Intent.ACTION_SENDTO` with `mailto:support@protectyourself.app` |
| Delete account | Confirmation dialog → Firebase Auth `user.delete()` + Firestore `db.collection("users").document(uid).delete()` + clear local DB. |
| ~~Rate app~~ | User did NOT select — skip. (Original had `InAppReviewActivity`; we keep the activity for the block-screen rating prompt but no profile entry.) |
| ~~Premium status~~ | REMOVED. |

### 5.6 About Tab (Replaces Premium)

**New screen** (replaces Premium tab):

```
About
├── App info
│   ├── App name: protect.yourself
│   ├── Version: 1.0.0 (rebuild version)
│   ├── Changelog
│   └── Open source licenses
├── Help
│   ├── FAQ (link to FAQ page)
│   ├── How it works (quick start guide)
│   └── Troubleshooting
├── Contact
│   ├── Email support
│   └── Report a bug
├── Legal
│   ├── Privacy policy
│   └── Terms of service
└── Credits
    ├── Original NopoX team
    └── Open source libraries
```

### 5.7 Sign-in / Sign-up Page

**Original**: Firebase Auth with email/password. `SignInSignUpPageViewModel` handles sign-in, sign-up, password reset.

**Rebuild** (per user: optional sign-in):
- Sign-in screen accessible from Profile page → "Sign in to enable backup/sync".
- Email + password fields.
- "Sign in" / "Sign up" toggle.
- "Forgot password" link → sends reset email.
- No social sign-in (Google/Facebook) — keep simple.

### 5.8 Accountability Partner (Real Friend)

**Original flow**:
1. User enables "Real Friend" protective mode.
2. Enters friend's email → stored in `real_friend_email` switch.
3. When user wants to disable a switch, app:
   - Generates a pending request (UUID)
   - Stores in `partner_pending_request_table`
   - Sends email to friend via Firebase Cloud Functions (SMTP)
   - Email contains deep link: `https://protectyourself.app.link/approve?requestId=<UUID>`
4. Friend clicks link → opens NopoX → `MainActivity.onNewIntent` parses deep link → shows approval dialog → friend approves/rejects.
5. Approval/rejection updates `partner_pending_request_table` + triggers the original switch toggle.

**Rebuild**:
- Deep link host: `protectyourself.app.link` (user must configure)
- Fallback: if no email backend, generate a deep link + share via system share sheet ("Send approval request to your friend")
- Firebase Cloud Functions needed for email sending — user must deploy (provide template in repo `cloud-functions/` directory)

### 5.9 Notifications

**Original channels**:
- `daily_report` — daily summary (block count, streak status)
- `reminder` — periodic motivational reminders
- `stop_me` — Stop Me foreground service notification
- `block` — block triggered notification
- `premium` — premium promo notifications

**Rebuild** (per user: only Daily report kept):
- ✅ `daily_report` — scheduled via WorkManager (every day at 9 AM user local time). Content: "You blocked X screens today. Current streak: Y days."
- ❌ `reminder` — REMOVED (user choice)
- ✅ `stop_me` — kept (foreground service requires notification on Android 8+)
- ❌ `block` — REMOVED (user choice; block screen is the only feedback)
- ❌ `premium` — REMOVED

### 5.10 Widgets

#### 5.10.1 Stop Me Widget

**Layout** (`stop_me_widget.xml`):
```xml
<FrameLayout id="@id/mainContainer">
  <LinearLayout id="@id/llButtonContainer"
                background="@drawable/bg_orange_gradient"
                orientation="horizontal" gravity="center">
    <ImageView id="@id/imgIcon" src="@drawable/ic_focus" 26dp/>
    <TextView id="@id/txtMessage" text="@string/stop_me"
              CeraRoundProRegular 14sp bold white/>
  </LinearLayout>
</FrameLayout>
```

**Widget info** (`stop_me_widget_info.xml`):
- minWidth: 110dp, minHeight: 40dp
- targetCellWidth: 2, targetCellHeight: 1
- updatePeriodMillis: 86400000 (24h)
- resizeMode: horizontal|vertical
- previewImage: `@drawable/focus_mode_widget`

**Behavior** (`StopMeWidget.kt`):
- `onUpdate`: refresh button text (e.g. "Stop Me (24:35 left)" if running)
- `handelWidgetClick`: tap → start instant Stop Me session (default 25min) OR open app if session already running

#### 5.10.2 Streak Widget

**Layout** (`streak_widget.xml`): small widget showing streak day count.

**Widget info** (`streak_widget_info.xml`):
- minWidth: 40dp, minHeight: 40dp
- targetCellWidth: 1, targetCellHeight: 1

**Behavior** (`StreakWidget.kt`):
- `onUpdate`: refresh day count
- Tap → open app at Streak tab

### 5.11 Theme & Typography

#### 5.11.1 Colors (from `colors.xml`)

**Dark theme** (primary, matches original):
```kotlin
val NopoXDarkColors = darkColorScheme(
    primary = Color(0xFF1F323F),          // colorPrimary
    onPrimary = Color.White,              // colorOnPrimary
    secondary = Color.Black,              // colorOnSecondary
    background = Color(0xFF061620),       // bg_blue_dark
    onBackground = Color.White,
    surface = Color(0xFF151F26),          // bg_blue
    onSurface = Color.White,
    accent = Color(0xFF3ACFFE),           // accent_color
    bottomNavActive = Color(0xFF38D0FE),  // bottom_nav_active
    bottomNavInactive = Color(0xFF4D7389),// bottom_nav_inactive
    bottomNavDivider = Color(0xFF052233), // bottom_nav_divider
    error = Color(0xFFFF5722),            // biometric_error_color
)
```

**Light theme** (new, follows system per user choice):
```kotlin
val NopoXLightColors = lightColorScheme(
    primary = Color(0xFF1F323F),
    onPrimary = Color.White,
    secondary = Color.Black,
    background = Color(0xFFF5F7FA),       // light bg
    onBackground = Color(0xFF061620),
    surface = Color.White,
    onSurface = Color(0xFF061620),
    accent = Color(0xFF3ACFFE),
    bottomNavActive = Color(0xFF1FA8D9),
    bottomNavInactive = Color(0xFF7A8B96),
    bottomNavDivider = Color(0xFFE0E5EA),
    error = Color(0xFFFF5722),
)
```

**Brand orange gradient** (used on Close button, Stop Me widget):
```xml
<gradient>
    <start color="#FFFF9900"/>  <!-- button_large_gradient_top_default -->
    <end color="#FFFF7100"/>    <!-- button_large_gradient_bottom_default -->
</gradient>
```

#### 5.11.2 Typography (Cera Round Pro → Nunito)

Original uses Cera Round Pro Bold/Medium/Regular — **proprietary**, cannot redistribute.

**Replacement**: **Nunito** (open source, OFL license, similar geometric sans-serif feel).

```kotlin
val NopoXTypography = Typography(
    h1 = TextStyle(fontFamily = Nunito, fontWeight = Bold, fontSize = 24.sp),
    h2 = TextStyle(fontFamily = Nunito, fontWeight = Bold, fontSize = 20.sp),
    h3 = TextStyle(fontFamily = Nunito, fontWeight = SemiBold, fontSize = 18.sp),
    body1 = TextStyle(fontFamily = Nunito, fontWeight = Normal, fontSize = 16.sp),
    body2 = TextStyle(fontFamily = Nunito, fontWeight = Normal, fontSize = 14.sp),
    button = TextStyle(fontFamily = Nunito, fontWeight = Bold, fontSize = 16.sp),
    caption = TextStyle(fontFamily = Nunito, fontWeight = Normal, fontSize = 12.sp),
)
```

XML layouts (block screen, widgets) use:
```xml
<style name="TextAppearance.NopoX.Bold" parent="@android:style/TextAppearance">
    <item name="android:fontFamily">@font/nunito_bold</item>
</style>
<style name="TextAppearance.NopoX.Medium" parent="@android:style/TextAppearance">
    <item name="android:fontFamily">@font/nunito_semibold</item>
</style>
<style name="TextAppearance.NopoX.Regular" parent="@android:style/TextAppearance">
    <item name="android:fontFamily">@font/nunito_regular</item>
</style>
```

### 5.12 Lottie Animations (All 6 Kept)

| File | Used in |
|---|---|
| `loading.json` | Loading indicators |
| `no_history.json` | Empty state on Streak history |
| `no_internet.json` | No internet connection state |
| `reminder.json` | Reminder dialogs (if reintroduced) |
| `streak_fire.json` | Streak page fire animation |
| `twinkle_crown.json` | Achievement unlock animation |

All 6 JSON files are in `/home/z/my-project/apk-analysis/apktool/assets/` and will be copied to the rebuild project's `app/src/main/assets/`.

### 5.13 Strings (English-only)

The original `strings.xml` has 884 lines. We will port **all** custom strings (filter out `abc_*`, `androidx_*`, `material_*`, `mtrl_*` boilerplate — these come from libraries).

Estimated custom strings: ~400. All will be ported 1:1 with the only change being:
- `app_name` → "protect.yourself"
- Remove premium/billing-related strings (~43 strings):
  - `annual`, `annual_plan_purchase_terms`, `monthly_plan_purchase_terms`, `lifetime_plan_purchase_terms`
  - `premium_active`, `premium_inactive`, `premium_inactive_message`, `premium_page_banner_title`, `premium_page_lifetime_access_price`
  - `premium_feature_error`, `premium_feature_blocklist_error`, `premium_feature_setting_app_error`
  - `get_nopox_Premium`, `get_premium`, `nav_premium`, `no_ads_message`, `nopox_premium_notification_message`, `offer_card_text`
  - All `pu_promotion_*` strings
  - `app_lock_premium_notification_*`, `pu_premium_notification_*`, `real_friend_premium_notification_*`

### 5.14 Drawables & Mipmaps

All drawables + mipmaps from `apktool/res/drawable*/` and `apktool/res/mipmap*/` will be copied 1:1 to the rebuild project's `app/src/main/res/`.

**Custom launcher icon** — since app name changes to `protect.yourself`, the original NopoX launcher icon should be replaced. Options:
1. Keep original icon (if user has rights)
2. Generate new icon matching "protect.yourself" branding (use Android Studio Image Asset Studio)

**Action item**: User must confirm whether to keep original NopoX launcher icon or generate new one.

---

## 6. Database Schema (Room)

### 6.1 Entities (9 total, ported 1:1 from original)

| Entity | Table | Columns | Purpose |
|---|---|---|---|
| `BlockScreenCountItemModel` | `block_screen_count_table` | `key: Int` (always 0), `count: Int` | Total block count |
| `PendingRequestItemModel` | `partner_pending_request_table` | `key, request_identifier, app_name, key_word, package_name, switch_number, item_key, item_type, request_display_message, request_submit_time, request_off_time, ap_type, approval_type` | Accountability partner pending requests |
| `SelectedAppItemModel` | `selected_apps_table` | `key, package_name, app_name, identifier, is_selected` | All app lists (block list, whitelists, etc.) |
| `SelectedKeywordItemModel` | `selected_keyword_table` | `key, keyword, identifier, is_selected` | Blocklist + whitelist keywords |
| `StopMeDurationItemModel` | `stop_me_duration_table` | `key, duration, end_time, days, start_time, start_time_day_millis` | Stop Me instant + scheduled sessions |
| `StopMeSessionCountItemModel` | `stop_me_session_count_table` | `key: Int` (always 0), `duration: Int` | Total Stop Me sessions completed |
| `StreakDatesItemModel` | `streak_dates_table` | `start_time, end_time, type, free_text` | Streak days + relapse records |
| `SwitchStatusItemModel` | `switch_status` | `key, value, type` | All toggle states (60+ keys) |
| `VpnCustomDnsItemModel` | `vpn_custom_dns` | `key, first_dns, second_dns, is_selected` | VPN DNS presets |

### 6.2 DAOs (9 — one per entity)

Each DAO has standard CRUD + custom queries. Port 1:1 from `apk-analysis/jadx/sources/com/planproductive/nopox/database/*/` (~120 DAO files including generated impls).

### 6.3 Migrations

The original has 7 migrations (`MIGRATION_1_2` through `MIGRATION_6_7`). For the rebuild, we start fresh at version 7 (or version 8 to signal "rebuild"). No migration code needed for fresh installs.

If we want to support importing original DB (via backup/sync from Firestore), we need to handle schema version 1-7 → 8 migration. **Defer this** — backup/sync will only restore data, not schema.

### 6.4 Pre-populated Data

On first launch, the following must be pre-populated:

1. **Preset keywords** — port from `BlockerPageUtils.defaultLanguageWiseBlockKeywords` (~500 porn-related keywords across multiple languages) + `defaultLanguageWiseWhiteListKeywords` (~50 whitelist keywords like "quit porn", "no fap").
2. **Default Stop Me durations** — 15min, 30min, 1hr, 2hr.
3. **Default VPN DNS presets** — Cloudflare Family, OpenDNS FamilyShield, CleanBrowsing Family, AdGuard Family.
4. **Default switch states** — all switches OFF except `porn_blocker_switch` (default ON).
5. **Default supported browsers** — Chrome, Firefox, Brave, Edge, Opera, Samsung Internet, Vivaldi, DuckDuckGo.
6. **Default supported social media** — Instagram, WhatsApp, Snapchat, Telegram, YouTube.
7. **Default whitelist apps** — NopoX itself + system UI.

---

## 7. Billing Removal — Detailed Strategy

This is the **most critical** part of the rebuild. The original has pervasive premium checks throughout the codebase.

### 7.1 Components to Remove Entirely

| Component | Location | Action |
|---|---|---|
| `googleBillingUtils/` package | `commons/utils/googleBillingUtils/` (23 files) | **DELETE entire package** |
| `BillingDataSource`, `GoogleBillingUtils`, `GoogleBillingInitPurchaseUtils`, `ProductIdIdentifiers` | same | DELETE |
| `premiumPage/` package | `features/premiumPage/` (26 files) | **DELETE entire package** |
| `PremiumPageViewModel`, `PremiumPageState`, `PremiumAnywhereActivity`, `IntroPremiumPageHandler` | same | DELETE |
| `PremiumPageDataRepository` | same | DELETE |
| `puPromotionPage/` package | `features/puPromotionPage/` (4 files) | **DELETE entire package** |
| `PuPromotionActivity`, `PuPromotionActivityUtils` | same | DELETE |
| `AppContainer.billingDataSource` + `premiumPageDataRepository` | `core/NopoXApp.kt` | Remove fields + initialization |
| `MainActivityState.premiumPlanDataList`, `isShowPremiumPage`, `isIntroPremiumPageActionDone`, `premiumFeatureIdentifiers`, `isPremiumActive`, `activePlanName`, `lifetimePlanDiscountText`, `isEligibleForBannerAd` | `MainActivityState` | Remove fields |
| `SwitchStatusValues.getIsPremiumActiveNumber`, `getPremiumFeatureNotificationDisplayDate`, `getPremiumFeatureNotificationIndexData`, `getPremiumSaleEndNotificationTimeData`, `getPurchaseDataInFireStoreStatus`, `getPurchaseSuccessEventSubmitStatus`, `getIsEligibleForBannerAdNumber`, `getIsIntroPremiumPageActionDoneStatus`, `getOutSideAppOpenFlowIdentifierNumber` | `SwitchStatusValues` | Remove methods |
| AdMob SDK + classes | `com.google.android.gms.ads.*` | Remove dependency + all usages |
| `MainActivity.initGoogleAdMobForAds` | `MainActivity.kt` | Remove method + call |
| `PornBlockPage.initBannerAd`, `initPuPromotionBanner` | `PornBlockPage.kt` | Remove methods + calls |
| `BillingDataSource` references in `BlockerPageViewModel.initPremiumSwitchListener` | `BlockerPageViewModel` | Remove init method + listener |
| `PremiumPageUtils` (notification every 3 hours, developed country detection) | `premiumPage/utils/` | DELETE |
| All `<string>` resources with "premium", "subscription", "purchase", "annual", "monthly", "lifetime", "trial", "paywall", "billing" | `strings.xml` | Remove (~43 strings) |
| `com.android.vending.BILLING` permission | `AndroidManifest.xml` | Remove |
| `com.google.android.gms.permission.AD_ID` permission | `AndroidManifest.xml` | Remove |
| Google Play Billing dependency | `build.gradle` | Remove `implementation 'com.android.billingclient:billing-ktx:...'` |
| AdMob dependency | `build.gradle` | Remove `implementation 'com.google.android.gms:play-services-ads:...'` |
| AdMob meta-data | `AndroidManifest.xml` | Remove `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" .../>` |
| AdMob init provider | `AndroidManifest.xml` | Remove `MobileAdsInitProvider` |
| `AdService` | `AndroidManifest.xml` | Remove `<service android:name="com.google.android.gms.ads.AdService"/>` |
| `AdActivity` | `AndroidManifest.xml` | Remove `<activity android:name="com.google.android.gms.ads.AdActivity"/>` |
| `queries` intent for billing | `AndroidManifest.xml` | Remove `<intent><action android:name="com.android.vending.billing.InAppBillingService.BIND"/></intent>` |

### 7.2 Components to Modify (Premium Checks → Always Allow)

Many places check `isPremiumActive` before allowing an action. All such checks must be replaced with `true` (always allow).

**Pattern**:
```kotlin
// Original:
if (SwitchStatusValues.isPremiumActive()) {
    // allow action
} else {
    // show premium upsell
}

// Rebuild:
// allow action (no check)
```

**Files needing this change** (search for `isPremiumActive`, `PremiumFeatureIdentifiers`, `premium_feature_error`):
- `BlockerPageViewModel.kt` — all `initPremiumSwitchListener`, premium feature checks
- `BlockerPageUtils.kt` — keyword count limit (3 free → unlimited)
- `MainActivityViewModel.kt` — `initIsShowIntroPremiumPage`, `initBlockScreenCount`
- `BlockerPageRepository.kt` — setting page item list (filter out premium-only items)
- `ProfilePageViewModel.kt` — premium status display
- `StreakPageViewModel.kt` — premium-only features

**Specific feature gates to remove**:
- Keyword count limit (>3 keywords requires premium) → unlimited
- App lock (premium feature) → free
- VPN (premium feature) → free
- Accountability partner / Real Friend (premium) → free
- Block new install apps (premium) → free
- Block in-app browsers (premium) → free
- Block phone reboot (premium) → free
- Block recent apps (premium) → free
- Block notification drawer (premium) → free
- Block Snapchat stories/spotlight (premium) → free
- Block YT search (premium) → free
- Block Telegram search (premium) → free
- Block WhatsApp status (premium) → free
- Block all websites (premium) → free
- Block settings page by title (premium) → free
- Blocked screen custom message (premium) → free
- Blocked screen countdown (premium) → free
- Blocked screen image (premium) → free
- Custom redirect URL (premium) → free
- Daily report (premium) → free
- Long sentence custom message (premium) → free
- Stop Me custom schedule + whitelist (premium) → free
- Time delay custom duration (premium) → free
- VPN notification message (premium) → free
- Whitelist unsupported browser (premium) → free
- Whitelist VPN apps (premium) → free
- Bulk import (premium) → free
- Login now (premium sync) → free
- No ads (premium) → N/A (ads removed entirely)

### 7.3 Bottom Nav Modification

Remove `Premium` tab, add `About` tab:
```kotlin
// MainPageScreen.kt
data object About : MainPageScreen("About", R.string.about, R.drawable.ic_info)
// Remove: data object Premium : MainPageScreen("Premium", R.string.nav_premium, R.drawable.ic_nav_crown)
```

Update `MainActivityViewModel.initBottomNavItem` to return list of `[NopoX, Streak, About, Profile]`.

### 7.4 Block Screen Modification

In `page_porn_block.xml`:
- Remove `<ImageView id="@id/imgPuBanner"/>` (PU promo banner)
- Remove `<FrameLayout id="@id/adViewContainer"/>` (AdMob banner container)

In `PornBlockPage.kt`:
- Remove `initBannerAd()` method + call
- Remove `initPuPromotionBanner()` method + call

---

## 8. Implementation Phases (Aggressive Timeline)

### Phase 1: Project Skeleton (Days 1-2)

**Deliverable**: Compilable empty Android Studio project.

1. Create new Android Studio project:
   - Package: `protect.yourself`
   - App name: `protect.yourself`
   - Min SDK 26, Target SDK 35, Compile SDK 35
   - Kotlin 2.0.21, Compose BOM 2024.10.01
   - Empty Compose Activity
2. Configure `build.gradle.kts`:
   - Add all dependencies (see Section 4.2)
   - Enable Compose compiler
   - Configure ProGuard
3. Set up theme:
   - `Color.kt` with dark + light color schemes
   - `Type.kt` with Nunito typography
   - `Theme.kt` with DayNight theme
4. Copy resources:
   - Lottie JSONs to `assets/`
   - Drawables from `apktool/res/drawable*/`
   - Mipmaps (launcher icon — keep original NopoX or generate new)
   - Custom `bg_orange_gradient.xml`, `bg_blue_dark.xml`, etc.
5. Create package structure (see Section 4.1).
6. Set up `NopoXApp.kt` extending `bin.mt.signature.KillerApplication` (need to vendor this class since it's not a standard library).
7. Configure `AndroidManifest.xml`:
   - All permissions (minus BILLING + AD_ID)
   - All activities (minus Premium + PU promotion)
   - All services + receivers
   - FileProviders
8. Add Firebase config:
   - `google-services.json` placeholder
   - Firebase BoM dependencies
9. Verify: app launches and shows empty Compose screen.

**Tests**: 1 smoke test (app launches).

### Phase 2: Database + Core Infrastructure (Days 3-5)

**Deliverable**: All 9 Room entities + DAOs + 9 database tables created. App pre-populates default data on first launch.

1. Port all 9 entity classes 1:1.
2. Port all 9 DAO interfaces (skip `_Impl` classes — Room generates them).
3. Create `AppDatabase.kt` with all entities + version 8 (or 7 to match original).
4. Port `SwitchStatusValues.kt` — 60+ getter methods for switch states.
5. Port `BlockerPageUtils.kt` — keyword loading, URL validation, etc.
6. Implement pre-population:
   - Preset keywords (extract from `BlockerPageUtils.defaultLanguageWiseBlockKeywords`)
   - Default Stop Me durations
   - Default VPN DNS presets
   - Default switch states
   - Default supported browsers + social media
7. Port `AppContainer.kt` (minus billing repository).
8. Set up `WorkManager` for periodic data check.
9. Set up Firebase Auth + Firestore wrappers (no actual sign-in UI yet).

**Tests**: Unit tests for all DAOs (~30 tests), pre-population verification (~10 tests).

### Phase 3: Accessibility Service + Block Screen (Days 6-10)

**Deliverable**: Working porn blocker. User can grant accessibility permission, browse to a porn site, and see the block screen.

1. Port `MyAccessibilityService.kt`:
   - Service config XML (`accessibility_setting.xml`)
   - Event types: `typeViewClicked`, `typeViewFocused`, `typeWindowContentChanged`, `typeWindowStateChanged`, etc.
   - URL extraction from browser address bars
   - Keyword matching logic
   - App switch detection
   - Block trigger → launch `PornBlockActivity`
2. Port `PornBlockActivity.kt` + `PornBlockPage.kt`:
   - Layout `page_porn_block.xml` (minus ads + PU banner)
   - Block reason message logic
   - Motivation image loading
   - Close button with countdown
   - Rating prompt
   - Block count increment
3. Port `BlockerPageUtils.kt` keyword matching:
   - URL decoding
   - Whitelist check (overrides block)
   - Blocklist check
   - SafeSearch URL transformation
4. Implement VPN service:
   - `MyVpnService.kt` with DNS routing
   - VPN permission request flow
   - Foreground service notification
5. Implement Stop Me:
   - Stop Me page UI
   - Instant + scheduled sessions
   - Whitelist apps
   - Session counter
6. Implement Stop Me widget + Streak widget.

**Tests**: Unit tests for keyword matching (50+ test cases), VPN DNS resolution (10 tests), Stop Me scheduling (15 tests), instrumentation tests for block screen (10 tests).

### Phase 4: Main UI + Settings (Days 11-15)

**Deliverable**: Full blocker page UI with all settings sections working.

1. Port `MainActivity.kt` + `MainActivityViewModel.kt`:
   - Bottom nav (NopoX, Streak, About, Profile)
   - Navigation
   - State management (StateFlow replaces Mavericks)
2. Port `BlockerPageHome.kt` + `BlockerPageViewModel.kt`:
   - All sections (ALERT, ACCOUNTABILITY, CONTENT_BLOCKING, INSTA_YT, UNINSTALL_PROTECTION, ADVANCE, FAQ)
   - All setting page items (60+ items)
   - Switch toggle handlers
3. Port all setting sub-pages:
   - `AccessibilityTurnOnPage`
   - `AddBlockScreenCountDownPageContent`
   - `AddCustomKeywordPageContent`
   - `AddCustomMessagePageContent`
   - `AddRedirectUrlPageContent`
   - `AddStopMeCustomDurationPageContent`
   - `AddStopMeScheduleDurationPageContent`
   - `AddStopMeSchedulePageContent`
   - `AddTimeDelayDurationPageContent`
   - `AddVpnCustomDnsPageContent`
   - `BlockWhiteListDetectedAppsPageContent`
   - `CustomKeywordPageContent`
   - `DisplayPopUpWindowTurnOnPage`
   - `EnterLongSentencePageContent`
   - `FaqPageContent`
   - `PartnerRequestHistoryPageContent`
   - `SetRealFriendEmailPageContent`
   - `SettingsKeywordPageContent`
   - `StopMePageContent`
   - `VpnCustomDnsPageContent`
4. Port `SelectAppPage` + `SelectAppPageViewModel` (app picker).
5. Port `AppPasswordPage` (app lock):
   - Pattern lock
   - PIN
   - Password
   - Biometric
   - Forgot password toggle

**Tests**: Compose UI tests for all settings pages (~80 tests), unit tests for state management (~30 tests).

### Phase 5: Streak + Profile + Onboarding (Days 16-18)

**Deliverable**: All 4 tabs functional + onboarding flow.

1. Port `StreakPage`:
   - Running streak with Lottie fire animation
   - Streak history calendar
   - Relapse tracking
   - Achievements grid with twinkle crown animation
   - Relapsed types stats
2. Port Streak widget.
3. Port `ProfilePage`:
   - Backup/Sync toggle (Firebase Auth integration)
   - Import/Export JSON
   - FAQ page
   - About page
   - Share app
   - Contact email
   - Delete account
4. Port `SignInSignUpPage` (Firebase Auth).
5. Port `AgreeTermsPage` + onboarding flow (Terms + Privacy + Accessibility + Popup permission + Skip).
6. Implement About tab (replaces Premium).
7. Port accountability partner flow:
   - Real Friend email setup
   - Pending request storage
   - Deep link approval/rejection
   - Email sending via Firebase Cloud Functions (provide template)

**Tests**: Streak date math (20 tests), achievements unlock logic (15 tests), backup/sync (10 tests), onboarding flow (5 instrumentation tests).

### Phase 6: Anti-Uninstall + Polish + Tests (Days 19-21)

**Deliverable**: Production-ready APK.

1. Implement anti-uninstall:
   - Device Admin receiver + setup flow
   - Accessibility watchdog (`AccessibilityGuard`)
   - `AccessibilityPersistUtils.selfHealSafe()`
   - Prevent Uninstall switch (accessibility detects NopoX app info page)
   - Block Phone Reboot (boot receiver restarts services)
   - Block Recent Apps (accessibility detects + dismisses)
   - Block Notification Drawer (accessibility detects + dismisses)
2. Implement notifications:
   - Daily report (WorkManager scheduled)
   - Stop Me foreground service notification
3. Implement Protected Apps feature (`ProtectedAppsActivity`, `ProtectedAppsRegistry`, `ShieldOverlay`).
4. Implement TransparentActivity (for permission prompts from background).
5. Final UI polish:
   - Verify pixel-perfect match with original screenshots
   - Dark/light theme parity
   - Animation timing
6. Final test pass:
   - All unit tests green
   - All instrumentation tests green
   - Manual smoke test on emulator (API 26, 33, 35)
7. Build release APK + sign with debug keystore (user can re-sign with their own).

**Tests**: Anti-uninstall instrumentation tests (15 tests), notification tests (10 tests), end-to-end smoke tests (10 tests).

### Phase 7: Documentation + Handoff (Day 22)

1. Generate Javadoc + KDoc for all public APIs.
2. Write README.md with:
   - Setup instructions
   - Firebase project setup guide
   - Cloud Functions deployment guide
   - Deep link assetlinks.json setup
   - Build + sign instructions
3. Write CONTRIBUTING.md.
4. Write LICENSE (recommend MIT or GPL-3.0).
5. Package as git repo + zip.
6. Deliver to `/home/z/my-project/download/protect.yourself.zip`.

---

## 9. Testing Strategy (Comprehensive)

Per user choice: **Comprehensive** (~300+ tests).

### 9.1 Unit Tests (~180 tests)

| Module | Test count | Coverage |
|---|---|---|
| Database DAOs | 30 | All CRUD + custom queries |
| SwitchStatusValues | 20 | All 60+ getters |
| BlockerPageUtils (keyword matching) | 50 | URL decode, whitelist, blocklist, regex |
| Streak date math | 20 | Day count, relapse, achievements |
| Stop Me scheduling | 15 | Instant, scheduled, days bitmask |
| VPN DNS resolution | 10 | DNS validation, preset management |
| Backup/Sync serialization | 10 | JSON round-trip |
| Onboarding state | 5 | Terms, accessibility, popup |
| App lock | 10 | Pattern, PIN, password validation |
| Billing removal verification | 10 | Verify no premium checks remain |

### 9.2 Instrumentation Tests (~120 tests)

| Module | Test count | Coverage |
|---|---|---|
| Block screen UI | 10 | All element states, countdown, rating |
| Main activity nav | 10 | All 4 tabs, deep link routing |
| Blocker page settings | 80 | Each setting item toggles + persists |
| Streak page | 10 | Streak display, history, achievements |
| Profile page | 5 | Backup toggle, import/export, delete |
| Onboarding flow | 5 | Terms → accessibility → popup |
| Anti-uninstall | 10 | Device admin, prevent uninstall, reboot |

### 9.3 Test Frameworks

- **JUnit 4** — unit tests
- **Mockk** — mocking (Kotlin-friendly)
- **Turbine** — Flow testing
- **Room testing** — `Room.inMemoryDatabaseBuilder`
- **Compose UI Test** — `createAndroidComposeRule`
- **Espresso** — instrumentation tests for XML activities
- **Robolectric** — for tests needing Android framework classes

---

## 10. Risk Register & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `bin.mt.signature.KillerApplication` not available as library | High | Medium | Vendor the class as source in `protect.yourself.commons.signaturekiller.KillerApplication.kt` — it's a small class that overrides `attachBaseContext` to patch signature checks. Reverse-engineer from APK. |
| Cera Round Pro font proprietary | High | Low | Use Nunito (visually similar, OFL license). Minor visual deviation acceptable. |
| Branch.io removal breaks deep links | Medium | Medium | Replace with Android App Links. User must configure domain + assetlinks.json. Provide fallback `open://` custom scheme. |
| Firebase project not configured | High | High | Provide detailed setup guide. App will crash on launch if `google-services.json` missing — fail fast with clear error. |
| Cloud Functions for email not deployed | Medium | Medium | Accountability partner feature degrades gracefully — fall back to share-sheet deep link. |
| Pixel-perfect parity hard without original screenshots | Medium | Medium | Use Lottie animations + layout XML from APK as ground truth. User verifies visually. |
| 300+ tests + aggressive timeline (2-3 weeks) contradictory | High | Medium | Tests written alongside features (not after). Final test pass in phase 6. Some tests may be deferred to phase 7. |
| Accessibility service killed by OEM battery optimization | High | High | Document workaround per OEM (Samsung, Xiaomi, etc.). Provide `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent. (User declined battery exemption in onboarding, but we can still show a warning in settings.) |
| Google Play Protect may flag signature killer | Medium | High | Document sideload requirement. Provide build variant without signature killer for Play Store submission. |
| Room schema migration from original backups | Low | Low | Defer — backup/sync only restores data, not schema. |

---

## 11. Deliverables Checklist

### 11.1 Source Code

- [ ] Complete Android Studio project (`protect.yourself.zip`)
- [ ] `build.gradle.kts` (app + project)
- [ ] `settings.gradle.kts`
- [ ] `gradle/libs.versions.toml` (version catalog)
- [ ] `app/src/main/AndroidManifest.xml`
- [ ] `app/src/main/java/protect/yourself/**/*.kt` (~500 Kotlin files)
- [ ] `app/src/main/res/` (layouts, drawables, strings, themes, fonts, xml)
- [ ] `app/src/main/assets/` (6 Lottie JSONs)
- [ ] `app/src/test/` (~180 unit tests)
- [ ] `app/src/androidTest/` (~120 instrumentation tests)
- [ ] `app/google-services.json` (placeholder — user replaces)
- [ ] `cloud-functions/` (Firebase Cloud Functions for email sending)
- [ ] `assetlinks.json` (template for App Links)
- [ ] `.gitignore`
- [ ] `README.md`
- [ ] `CONTRIBUTING.md`
- [ ] `LICENSE`

### 11.2 Built Artifacts

- [ ] `protect.yourself-v1.0.0-release-signed.apk` (debug-signed)
- [ ] `protect.yourself-v1.0.0-mapping.txt` (ProGuard mapping)

### 11.3 Documentation

- [ ] This implementation plan (`IMPLEMENTATION_PLAN.md`)
- [ ] `README.md` with setup + build instructions
- [ ] Firebase setup guide
- [ ] Cloud Functions deployment guide
- [ ] Deep link setup guide
- [ ] OEM-specific battery optimization guide

---

## 12. Next Steps

1. **User reviews this plan** and confirms/adjusts.
2. **User provides Firebase project** (`google-services.json`) — or we proceed with placeholder.
3. **User confirms launcher icon** — keep NopoX or generate new "protect.yourself" icon.
4. **User configures domain** `protectyourself.app.link` (or alternative) + hosts `assetlinks.json`.
5. **Begin Phase 1** — project skeleton.

---

## Appendix A: Reference Material Locations

All reverse-engineering artifacts are at:

```
/home/z/my-project/apk-analysis/
├── apktool/AndroidManifest.xml         # decoded manifest
├── apktool/res/                        # decoded resources
│   ├── values/strings.xml              # 884 strings
│   ├── values/colors.xml               # all colors
│   ├── values/styles.xml               # Theme.NopoX
│   ├── layout/page_porn_block.xml      # block screen layout
│   ├── layout/stop_me_widget.xml       # Stop Me widget layout
│   ├── layout/streak_widget.xml        # Streak widget layout
│   ├── drawable*/                      # all drawables
│   ├── mipmap*/                        # launcher icons
│   ├── xml/accessibility_setting.xml   # accessibility service config
│   ├── xml/device_admin.xml            # device admin config
│   ├── xml/stop_me_widget_info.xml     # Stop Me widget info
│   └── xml/streak_widget_info.xml      # Streak widget info
├── apktool/assets/                     # 6 Lottie JSONs
├── jadx/sources/com/planproductive/nopox/  # 659 app source files
│   ├── core/NopoXApp.java
│   ├── database/                       # Room entities + DAOs
│   ├── features/                       # all feature packages
│   └── commons/utils/                  # all utility packages
└── raw/apk_unzipped/                   # raw APK contents
```

## Appendix B: Original File Counts

| Category | Count |
|---|---|
| Total APK files | 1,730 |
| App's own Kotlin/Java files | 659 |
| Layouts (XML) | 116 |
| Drawables | 260 PNG + 67 WebP + 2 GIF |
| Strings | 884 |
| Colors | ~200 |
| Room entities | 9 |
| Room DAOs | 9 |
| Setting page items | 60+ |
| Premium feature identifiers | 32 |
| Activities | 7 |
| Services | 5 |
| Broadcast receivers | 7 |
| Content providers | 4 |
| Permissions | 22 (20 after removing BILLING + AD_ID) |
| Lottie animations | 6 |
| Bottom nav tabs | 4 (NopoX, Streak, About, Profile) |

## Appendix C: User Decisions Log

| Decision | User Choice |
|---|---|
| Rebuild goal | Pixel-perfect |
| UI framework | Hybrid (Compose + XML) |
| Premium tab | Replace with About |
| Ads | Remove all |
| Analytics | Crashlytics only |
| Backend | Keep Firebase |
| Sign-in | Optional |
| Accountability | Email-based (orig) |
| Min Android | 8 (API 26) |
| Target SDK | 35 (Android 15) |
| Package name | `protect.yourself` |
| App name | `protect.yourself` |
| Block screen | All elements (per remark "as is") |
| Stop Me | All features |
| VPN/DNS | Keep VPN+DNS |
| Block targets | All (per remark "as is org") |
| Protective modes | All 3 (Long Sentence, Real Friend, Time Delay) |
| Anti-uninstall | Keep all |
| App lock | Keep all |
| Streak page | All features |
| Profile page | Backup, Import/Export, FAQ+About, Share, Contact, Delete |
| Onboarding | Terms + Privacy, Popup permission, Skip option |
| Push notifs | Daily report only |
| Widgets | Both (Stop Me + Streak) |
| Deep links | Standard App Links |
| Languages | English only |
| Theme | Follow system (DayNight) |
| Testing | Comprehensive (300+) |
| Delivery | GitHub repo (zip) |
| Fidelity keepers | SignatureKiller only |
| Scope limits | Nothing extra + Remove reCAPTCHA |
| Timeline | Aggressive (~2-3 weeks) |
| Final notes | Custom pkg `protect.yourself`, block screen as is, block targets as is |

---

**End of Implementation Plan**
