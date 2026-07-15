# Protect Yourself

A privacy-first Android app that blocks pornographic and distracting content at multiple layers — DNS filtering via VPN, accessibility-service-based content/app blocking, anti-uninstall protection, scheduled app restrictions, focus sessions, and crash diagnostics. The app runs entirely on-device; no cloud, no analytics, no accounts.

- **Package**: `protect.yourself`
- **Current version**: `1.0.62` (versionCode `62`)
- **Min SDK**: 26 (Android 8.0) · **Target SDK**: 35 (Android 15)
- **Language**: Kotlin 2.0.21 · **UI toolkit**: Jetpack Compose (Material 3)
- **Build system**: Gradle 8.7.2 with Kotlin DSL + Version Catalogs

---

## Table of Contents

1. [System Architecture Overview](#1-system-architecture-overview)
2. [Technology Stack and Dependencies](#2-technology-stack-and-dependencies)
3. [Core Infrastructure](#3-core-infrastructure)
4. [Database Layer](#4-database-layer)
5. [Feature: blockerPage (Content & App Blocking Engine)](#5-feature-blockerpage--content--app-blocking-engine-)
6. [Feature: appPasswordPage (App Lock)](#6-feature-apppasswordpage--app-lock-)
7. [Feature: stopMePage (Focus Sessions)](#7-feature-stoppemepage--focus-sessions-)
8. [Feature: schedulePage (Scheduled App Restrictions)](#8-feature-schedulepage--scheduled-app-restrictions-)
9. [Feature: selectAppPage (App Picker)](#9-feature-selectapppage--app-picker-)
10. [Feature: keywordManagerPage (Keyword Lists)](#10-feature-keywordmanagerpage--keyword-lists-)
11. [Feature: packageIntentPage (Package & Intent Blocking)](#11-feature-packageintentpage--package--intent-blocking-)
12. [Feature: mainActivityPage (Entry Point & Navigation)](#12-feature-mainactivitypage--entry-point--navigation-)
13. [Feature: profilePage (Profile & Diagnostics Hub)](#13-feature-profilepage--profile--diagnostics-hub-)
14. [Feature: protectedApps (Anti-Uninstall & Self-Heal)](#14-feature-protectedapps--anti-uninstall--self-heal-)
15. [Feature: backupRestore (Backup & Restore)](#15-feature-backuprestore--backup--restore-)
16. [Feature: crashLog (Crash Diagnostics)](#16-feature-crashlog--crash-diagnostics-)
17. [Domain Layer: schedule (Schedule Engine)](#17-domain-layer-schedule--schedule-engine-)
18. [Background Infrastructure](#18-background-infrastructure)
19. [Theme System](#19-theme-system)
20. [Permissions Summary](#20-permissions-summary)
21. [Building & Running](#21-building--running)
22. [Project Structure](#22-project-structure)

---

## 1. System Architecture Overview

Protect Yourself is built as a single-module Android app (`:app`) using a feature-based package layout. There is no DI framework — dependencies are wired by hand through a small `AppContainer` and per-feature `ViewModelProvider.Factory` implementations. State is communicated via Kotlin `StateFlow`/`SharedFlow` and observed by Compose through `collectAsState()` and `LaunchedEffect`.

The runtime blocking model is **defense in depth across three independent enforcement layers**, each able to operate without the others:

```
┌────────────────────────────────────────────────────────────────────────┐
│                         USER INTERFACE LAYER                            │
│   Compose screens  •  ViewModels  •  StateFlow / SharedFlow            │
└───────────────┬───────────────────────────────────┬────────────────────┘
                │                                   │
                ▼                                   ▼
┌───────────────────────────────┐   ┌────────────────────────────────────┐
│      NETWORK LAYER (VPN)      │   │   UI/EVENT LAYER (Accessibility)    │
│        MyVpnService           │   │   MyAccessibilityService            │
│                               │   │                                     │
│  • DNS filtering (Cloudflare  │   │  • URL keyword matching             │
│    Family / AdGuard Family /  │   │  • App blocklist enforcement        │
│    custom preset)             │   │  • In-app browser detection         │
│  • Per-app internet blocking  │   │  • Settings-page-by-title blocking  │
│    (scheduled restrictions)   │   │  • Anti-circumvention (power menu,  │
│  • SafeSearch at resolver     │   │    recent apps, notification shade) │
│                               │   │  • Block overlay + Activity fallback│
└───────────────┬───────────────┘   └─────────────────┬──────────────────┘
                │                                     │
                ▼                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       PERSISTENCE LAYER (Room)                          │
│  switch_status • selected_keyword • selected_apps • block_screen_count  │
│  stop_me_duration • stop_me_session_count • vpn_custom_dns             │
│  scheduled_restriction • scheduled_restriction_app • pending_request   │
└────────────────────────────────────────────────────────────────────────┘
                ▲                                     ▲
                │                                     │
┌───────────────┴───────────────────────────────────┴────────────────────┐
│                     BACKGROUND INFRASTRUCTURE                           │
│  WorkManager workers (24h, 15min)  •  AlarmManager (exact alarms)       │
│  Broadcast receivers (boot, package, screen, connectivity)              │
│  AccessibilityGuard (ContentObserver + 30s polling)                     │
│  CrashLogger + AnrWatchdog + CrashLoggingTree (Timber)                  │
└────────────────────────────────────────────────────────────────────────┘
```

### Architectural principles

1. **Feature-isolated packages** — each feature lives under `features/<featureName>/` with its own `components/`, `utils/`, `identifiers/`, `data/`, and `service/` sub-packages as needed. Cross-feature dependencies are explicit and minimal.
2. **Single source of truth for runtime decisions** — `ScheduleEngine` is the only component that decides which apps are blocked by schedules. VPN and Accessibility read from `ScheduleEngine.getActiveInternetBlockedApps()` / `getActiveLaunchBlockedApps()`, never from the DB directly.
3. **Manual dependency injection** — `AppContainer` holds the application-scoped coroutine scope and the Room database. Each `ViewModel` exposes a `factory(...)` companion that constructs it with required dependencies.
4. **Defense-in-depth self-heal** — the accessibility service is re-armed by `AccessibilityPersistUtils.selfHealSafe(context)` from no fewer than seven call sites: `Application.onCreate`, `MainActivity.onResume`, `MyAccessibilityService.onServiceConnected/onUnbind`, both manifest receivers, `AppDataCheckWorker` (24h), and `AccessibilityGuard` (ContentObserver + 30s polling).
5. **Crash-safe initialization** — every init step in `ProtectYourselfApp.onCreate()` is wrapped in `safeInit(name, block)` which catches `Throwable` and logs to CrashLogger without killing the app. Auto-initializers for `WorkManager`, `ProcessLifecycle`, `EmojiCompat`, and `ProfileInstaller` are explicitly disabled in the manifest and re-enabled manually inside `onCreate()`.
6. **No network calls** — the app makes zero outbound network requests. Crash logs are local-only. Backups are local JSON files via SAF. DNS filtering happens inside the local VPN tunnel; the configured resolvers (Cloudflare/AdGuard) receive only DNS queries, never user data.

---

## 2. Technology Stack and Dependencies

All versions are pinned in `gradle/libs.versions.toml`.

### Build tooling

| Tool | Version | Notes |
|---|---|---|
| Android Gradle Plugin | 8.7.2 | |
| Kotlin | 2.0.21 | With Compose compiler plugin |
| Java | 17 | Source + target compatibility |
| Core library desugaring | 2.1.2 | For `java.time` on minSdk 26 |

### AndroidX

| Library | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | 1.13.1 | Kotlin extensions |
| `androidx.appcompat:appcompat` | 1.7.0 | AppCompat theme + back-compat |
| `androidx.activity:activity-compose` | 1.9.3 | `ComponentActivity` + Compose interop |
| `androidx.lifecycle:lifecycle-*` | 2.8.7 | ViewModel, runtime-compose, process |
| `androidx.compose:compose-bom` | 2024.10.01 | Compose BOM (UI, Material3, Foundation) |
| `androidx.room:room-*` | 2.6.1 | Database (runtime, ktx, kapt compiler) |
| `androidx.work:work-runtime-ktx` | 2.10.0 | WorkManager for periodic background work |
| `androidx.biometric:biometric` | 1.1.0 | `BiometricPrompt` for app lock |
| `androidx.fragment:fragment-ktx` | 1.8.5 | Required by BiometricPrompt |
| `com.google.android.material:material` | 1.12.0 | Material Components (legacy widgets) |

### Kotlin coroutines

`org.jetbrains.kotlinx:kotlinx-coroutines-core` and `-android` at 1.9.0. Used throughout for async DB reads, service IPC, and Compose state observation.

### UI libraries

- `com.airbnb.android:lottie-compose` 6.5.2 — used for onboarding and stop-me animations.

### Utilities

- `com.jakewharton.timber:timber` 5.0.1 — logging facade. Planted trees: `Timber.DebugTree` and `CrashLoggingTree` (routes WARN+ into the structured crash log).
- `com.google.code.gson:gson` 2.11.0 — JSON parsing for backup/restore, preset keyword assets, and crash log entry persistence.

### Testing

| Library | Version | Scope |
|---|---|---|
| `junit:junit` | 4.13.2 | unit test |
| `io.mockk:mockk` / `mockk-android` | 1.13.13 | unit / instrumentation |
| `app.cash.turbine:turbine` | 1.2.0 | Flow testing |
| `org.robolectric:robolectric` | 4.13 | unit tests requiring Android stubs |
| `com.google.truth:truth` | 1.4.4 | fluent assertions |
| `androidx.arch.core:core-testing` | 2.2.0 | LiveData/ViewModel testing |
| `androidx.room:room-testing` | 2.6.1 | in-memory DB tests |
| `androidx.work:work-testing` | 2.10.0 | WorkManager test driver |
| `androidx.test:core` / `ext:junit` | 1.6.1 / 1.2.1 | Robolectric interop |

### Removed dependencies

The following were intentionally stripped from the original upstream app to eliminate crashes and reduce attack surface:

- **Firebase** (all of it) — `FirebaseInitProvider` auto-initializes before `Application.onCreate()` and crashes if the project config is invalid. CrashLogger provides equivalent on-device diagnostics.
- **Branch.io** — replaced by standard Android App Links (`protectyourself.app.link`).
- **Google Play Billing** — premium tier removed; the app is fully free.
- **CanHub Image Cropper** — the block-screen motivation image now uses a simpler decode-and-downsample pipeline (`BlockScreenImageLoader`).
- **Signature killer** (`bin.mt.signature.KillerApplication`) — the app extends `Application` directly.

---

## 3. Core Infrastructure

### `core/ProtectYourselfApp.kt` — Application class

The entry point for app initialization. Extends `Application`, implements `DefaultLifecycleObserver` (for foreground/background breadcrumbs) and `Configuration.Provider` (for on-demand WorkManager initialization).

**Initialization sequence** (each step wrapped in `safeInit` so a single failure doesn't kill the app):

1. **CrashLogger.init** — first, so any crash during subsequent steps is captured.
2. **installCrashHandler** — installs a global `Thread.setDefaultUncaughtExceptionHandler` that routes to CrashLogger with severity FATAL, then delegates to the previous handler. Recursive-crash protection via an `AtomicBoolean` flag prevents infinite recursion if the crash handler itself throws.
3. **Timber** — plants `DebugTree` + `CrashLoggingTree`.
4. **AppDatabase.getInstance** — Room (non-blocking; lazy DB creation).
5. **ProcessLifecycleOwner** — registers `this` as observer for foreground/background breadcrumbs.
6. **AppContainer** — manual DI container (always set; not wrapped in `safeInit` because `appContainer` is `lateinit`).
7. **PackageManagerProvider.init** — singleton `PackageManager` ref for the app picker.
8. **AccessibilityGuard.startWatching** — registers the ContentObserver + 30s polling fallback. `selfHealSafe` runs on a background IO coroutine (v1.0.49 fix — was contributing to ANRs on some OEMs when run on the main thread).
9. **WorkerUtils.initAppDataCheckWorker** + **ScheduleCheckWorker.enqueue** — schedules periodic workers.
10. **ThemePreferences.init** — loads the saved theme mode.
11. **NotificationHelper.createAllChannels** + crash-alert channel.
12. **AnrWatchdog.start** — 2.5 s polling for ANR detection.
13. **notifyIfCrashedSinceLastLaunch** — if a FATAL entry exists with timestamp > last launch time, posts a high-priority notification.

**Crash notification channel**: `crash_alerts` (separate from the app's other channels so the user can mute crash alerts independently).

### `core/AppContainer.kt` — manual DI container

A tiny singleton holding the application-scoped `CoroutineScope` (SupervisorJob + Dispatchers.Default + `AppCoroutineExceptionHandler`) and the Room `AppDatabase`. Accessed from `ViewModelProvider.Factory` implementations via `AppContainer.get(context)`.

### `core/AppCoroutineExceptionHandler.kt`

A `CoroutineExceptionHandler` that routes any uncaught coroutine exception to `CrashLogger` with the scope name, dispatcher, and job context. Fixes the silent-loss bug where `async { throw }` without `await` previously dropped the exception entirely.

---

## 4. Database Layer

The Room database lives in `database/core/AppDatabase.kt`. Current schema version is **11**. The DB file is `protect_yourself_database.db`.

### Entities (10 tables)

| Entity | Table | Purpose |
|---|---|---|
| `SwitchStatusItemModel` | `switch_status` | All toggle/settings state keyed by `SwitchIdentifier` |
| `SelectedKeywordItemModel` | `selected_keyword` | Blocklist, whitelist, setting-title, and intent-name keywords |
| `SelectedAppItemModel` | `selected_apps` | App lists keyed by `SelectedAppListIdentifier` (block list, VPN whitelist, Stop-Me whitelist, supported browsers, new-install block, etc.) |
| `BlockScreenCountItemModel` | `block_screen_count` | Single-row table tracking total block count |
| `PendingRequestItemModel` | `pending_request` | Accountability partner request history |
| `StopMeDurationItemModel` | `stop_me_duration_table` | Stop Me sessions (instant + scheduled) |
| `StopMeSessionCountItemModel` | `stop_me_session_count_table` | Single-row table tracking completed session count |
| `VpnCustomDnsItemModel` | `vpn_custom_dns` | Custom DNS presets for VPN CUSTOM mode |
| `ScheduledRestrictionItemModel` | `scheduled_restriction_table` | Scheduled app restriction rules |
| `ScheduledRestrictionAppItemModel` | `scheduled_restriction_app_table` | Per-rule app package list |

### Migrations

| Migration | What it does |
|---|---|
| `MIGRATION_8_9` | Adds `displayName` column to `vpn_custom_dns`; runs schema repair for any snake_case → camelCase column transition |
| `MIGRATION_9_10` | Comprehensive `vpn_custom_dns` schema repair — drops and recreates the table if any camelCase columns are missing |
| `MIGRATION_10_11` | Drops `streak_dates_table` (Streak feature was removed in v1.0.62 in favor of Scheduled App Restrictions) |

`fallbackToDestructiveMigration()` is the last-resort fallback for any future schema bumps without a written migration.

### `AppDatabaseCallback`

Runs `repairVpnCustomDnsSchema(db)` on every DB open (idempotent — only repairs if columns are actually missing) and reseeds default DNS presets if the table is empty.

### DAO access

Each entity has a dedicated DAO. Accessors are exposed as abstract functions on `AppDatabase`:

```kotlin
abstract fun switchStatusDao(): SwitchStatusDao
abstract fun selectedAppsListDao(): SelectedAppListAppsDao
abstract fun selectedKeywordDao(): SelectedKeywordDao
abstract fun blockScreenCountDao(): BlockScreenCountDao
abstract fun pendingRequestDao(): PendingRequestDao
abstract fun stopMeDurationDao(): StopMeDurationDao
abstract fun stopMeSessionCountDao(): StopMeSessionCountDao
abstract fun vpnCustomDnsDao(): VpnCustomDnsDao
abstract fun scheduledRestrictionDao(): ScheduledRestrictionDao
abstract fun scheduledRestrictionAppDao(): ScheduledRestrictionAppDao
```

The typed wrapper `SwitchStatusValues(dao)` provides convenience accessors like `getPornBlocker()`, `setVpnSwitch(value)`, `getBlockScreenCustomMessage()`, etc., so callers don't have to remember string identifiers.

---

## 5. Feature: blockerPage (Content & App Blocking Engine)

The largest feature (~11,200 LOC across 23 files). Bundles the Compose settings UI, the accessibility service that performs runtime blocking, the VPN service that performs DNS filtering, the block-screen overlay, the home-screen widget, and a utility package with keyword matching, validation, presets, and device-admin helpers.

### 5.1 Architecture

```
BlockerPageHome (Compose)
    │
    ├─ BlockerPageViewModel ──► SwitchStatus (Room) + selectedAppsListDao
    │       │                    + selectedKeywordDao + vpnCustomDnsDao
    │       │                    + blockScreenCountDao
    │       │
    │       ├─► MyAccessibilityService.refreshBlockingConfig()
    │       ├─► MyVpnService.start/stop/restart(context)
    │       ├─► DeviceAdminUtils.removeActive(context)
    │       └─► AppLockManager.disableLock()
    │
    ├─ UnifiedBlockingPage ──► KeywordManagerViewModel + PackageIntentViewModel
    ├─ VpnManagementPage    ──► BlockerPageViewModel (reused)
    │
    └─ Sub-pages: SelectAppPage, AppLockSetupPage, StopMePage,
                  WriteSecureSettingsSetupPage, ProtectedAppsActivity
```

### 5.2 Dependencies

- **Internal**: `AppDatabase` and all DAOs; `SwitchStatusValues`; `BlockerPageUtils` (singleton); `ScheduleEngine`; `AccessibilityPersistUtils`; `AppLockManager`; `DeviceAdminManager`; `StopMeManager`; `BlockScreenImageLoader`; `DefaultPresets`/`DefaultDnsPresets`/`DefaultStopMeDurations`/`DefaultWhitelistApps`; `DefaultKeywordData`; `BlockingValidator`; `KeywordMatcher`; `NewInstallBlockingUtils`; `DeviceAdminUtils`; `NotificationHelper`; `ProtectYourselfApp.getCrashLogger()`; `OncePerSessionLogger`.
- **Compose**: Material3, foundation, runtime, material-icons-extended, activity-compose.
- **Android framework**: `AccessibilityService`, `VpnService`, `WindowManager`, `AlarmManager`, `DevicePolicyManager`, `Settings.Secure`, `PackageManager`.

### 5.3 Data flow

1. **UI → ViewModel**: user toggles a switch → `BlockerPageViewModel.toggleSwitch(item)`. The ViewModel validates prerequisites (dependency rules), handles Time Delay countdown if active, requests system permission flows (VPN, Device Admin, App Lock) via one-shot `BlockerPageNavigation` events, then writes the new switch value via `SwitchStatusValues`.
2. **ViewModel → Service**: after every write, the ViewModel calls `MyAccessibilityService.instance?.refreshBlockingConfig()` (or `refreshKeywordList(identifier)` for targeted keyword refresh — KB-03 fix; or `refreshNewInstallBlockSync(db)` for new-install blocking — NIB-01 fix). For VPN changes, it calls `MyVpnService.start/stop/restart(context)`.
3. **Service → UI**: the service exposes `@Volatile` cached fields read on every accessibility event. The VPN service exposes `observableVpnState: VpnState` (companion-level) so the UI can show live status without polling the DB.
4. **Service → Block screen**: when a block is triggered, the service calls `launchBlockActivity(pkg, msgKey, matchedKeyword?)` which tries `BlockOverlayManager.showBlockOverlay()` first, then falls back to `PornBlockActivity`, then `GLOBAL_ACTION_HOME` as last resort.

### 5.4 Workflow — toggling blocking on

1. User taps "Porn blocker" switch on the Blocker tab.
2. `BlockerPageViewModel.toggleSwitch(item)` runs:
   - If `item.isDisabled` (prerequisite off) → emit `ShowToast(dependencyMessage)` and abort.
   - If Time Delay is active and the user is *turning off* a switch → emit `RequestTimeDelay(delaySeconds, item)`; UI shows countdown dialog; on completion, `confirmToggleAfterDelay` re-runs the toggle bypassing the Time Delay check.
   - Otherwise: `switchValues.storeSwitchStatus(switchKey, newValue)`.
3. `_state.update` reflects the new value incrementally (or `loadSettingItems()` for protective-mode switches to avoid flicker).
4. `MyAccessibilityService.instance?.refreshBlockingConfig()` reloads all DB tables into the service's `@Volatile` cache.
5. Toast feedback via `ShowToast("$switchName enabled|disabled")`.

### 5.5 Workflow — accessibility event blocking

`MyAccessibilityService.onAccessibilityEvent(event)` routes events to handlers:

- **`TYPE_VIEW_CLICKED` / `VIEW_LONG_CLICKED` / `WINDOW_STATE_CHANGED`** → `checkInAppBrowserBlock` (if `isBlockInAppBrowsersOn`)
- **All events** (except our own app and SystemUI for some checks) → prevent-uninstall `isAppInfoPage` + settings-by-title `isSettingsPage`
- **`TYPE_WINDOW_STATE_CHANGED`** → `handleWindowStateChange`: power menu, notification drawer, recent apps, package+intent block, blocklist apps, scheduled apps, new-install apps, unsupported browsers, Stop Me
- **`TYPE_WINDOW_CONTENT_CHANGED` / `VIEW_FOCUSED` / `VIEW_CLICKED` / `VIEW_LONG_CLICKED`** → `handleContentChange`: URL extraction (priority) → content-text keyword match (fallback)

For URL detection (`handleContentChange`):

1. Skip if both `isPornBlockerOn` and `isSafeSearchOn` are false.
2. `extractUrlFromEvent` — try `BlockerPageUtils.BROWSER_URL_VIEW_IDS[packageName]` (Chrome `url_bar`, Firefox `mozac_browser_toolbar_url_view`, etc.); fallback to recursive `findUrlInNode` (depth ≤ 50, nodes ≤ 300). Also check `contentDescription` (URL-01 fix for Chrome 2024+).
3. If URL found → `handleUrlDetected`:
   - Anti-loop guard (`pornPreviousUrl == decoded`)
   - Skip if URL > 8192 chars
   - **SafeSearch first** (if `isSafeSearchOn` and `getSafeSearchUrl` returns non-null) → `enforceSafeSearch` (open safe URL in same browser, throttled 2 s per pkg + URL-without-query)
   - **Whitelist check** (overrides block) — runs whether or not Porn Blocker is on
   - **Block keyword match** (only if `isPornBlockerOn`) — `BlockerPageUtils.matchKeywordInUrl` via Aho-Corasick; on match, reset `pornPreviousUrl = ""` and launch block screen with `matchedKeyword`
   - Track URL on no-match to avoid re-matching on every content-change event
4. If no URL found → content-text keyword match (depth ≤ 3, nodes ≤ 500, text ≤ 5000 chars).

### 5.6 Workflow — block screen display

`MyAccessibilityService.launchBlockActivity(packageName, messageResKey, matchedKeyword?)`:

1. **Strategy 1 — Overlay (preferred)**: `BlockOverlayManager.showBlockOverlay(...)`:
   - `AtomicBoolean isOverlayShowing` single-flight guard.
   - `Settings.canDrawOverlays()` check; if missing, log + show "overlay permission missing" notification (throttled to once per 24 h) and fall through.
   - Builds a programmatic `LinearLayout` with header, warning icon, motivation `ImageView`, message, "Why am I seeing this?" toggle, Close button.
   - `WindowManager.addView(view, params)` with `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, `MATCH_PARENT`.
   - Starts `KillTimer` (500 ms ticks: HOME × 5 then BACK × 1, dispatched to main thread — THREAD-01/OV-02 fix).
   - `applyUserCustomisations` async on `ioScope`: reads custom message + motivation image from DB, decodes via `BlockScreenImageLoader`, posts view mutations to the main thread.
2. **Strategy 2 — Activity fallback**: 300 ms global throttle; launch `PornBlockActivity` with extras; press HOME after 200 ms delay (AB-05 fix) to move offending app to background.
3. **Strategy 3 — Last resort**: `performGlobalAction(GLOBAL_ACTION_HOME)`.

`PornBlockActivity.onCreate` reads Intent extras, resolves the message string, configures the Why toggle (shows matched keyword), async-loads the motivation image + custom message, configures the Close button (optional 3–300 s countdown, optional redirect URL), maybe shows the rating prompt (every 20 blocks), and increments `block_screen_count`.

### 5.7 Workflow — keyword matching

`BlockerPageUtils` uses `KeywordMatcher` (Aho-Corasick automaton — KB-02 fix). The matcher is cached by `words.hashCode()` so it's rebuilt only when the keyword set changes.

- For URLs: `matchKeywordInUrl(url, words)` → `normalizeForMatching(url)` (IDN punycode decode + zero-width strip + NFKD normalize + lowercase — KB-04 fix defeats homograph attacks) → `matcher.findFirst(normalized)`.
- For content text: `isDetectWord(detectText, words)` → strip URLs via `websiteRegex` → `matcher.findFirst(stripped)`.
- For whitelist: `isSafeUrl(url, whitelistKeywords)` → `normalizeForMatching(decodeText(url))` → `matcher.findFirst`.

Build cost: O(sum of keyword lengths). Match cost: O(text length + number of matches) — roughly 100× faster than the linear scan over 532 default keywords.

### 5.8 VPN ↔ Accessibility coordination

The two services are independent but complementary:

- **VPN** catches DNS-level requests that the accessibility service can't see (e.g., system browsers in private mode, apps that bypass accessibility scraping).
- **Accessibility service** catches in-app browsers, content text, app launches, and anti-circumvention that VPN can't see.
- **SafeSearch** is enforced at both layers: accessibility redirect (`getSafeSearchUrl` → `enforceSafeSearch`) AND DNS-level (Cloudflare Family / AdGuard Family resolvers filter adult content at the resolver).

`ScheduleEngine` (in `domain/schedule`) is the only cross-service coordinator: it can switch the VPN into `PER_APP_BLOCK` mode via `MyVpnService.setScheduledBlockApps(context, apps)` and update the accessibility service's scheduled block set via `MyAccessibilityService.updateScheduledBlockApps(set)`.

### 5.9 Files

| Path | Purpose |
|---|---|
| `BlockerPageViewModel.kt` | Settings state machine, switch toggling, navigation events, VPN management state |
| `components/BlockerPageHome.kt` | Top-level Compose screen with category cards + sub-page navigation |
| `components/UnifiedBlockingPage.kt` | 5-tab management surface (blocklist / whitelist / setting-titles / packages / intents) |
| `components/VpnManagementPage.kt` | Dedicated VPN mode + custom DNS preset manager |
| `service/MyAccessibilityService.kt` | Core blocking engine (2,592 LOC) — listens to all window/content events |
| `service/MyVpnService.kt` | DNS-filtering VPN with per-app-block mode for scheduled restrictions |
| `service/BlockOverlayManager.kt` | Non-dismissible `TYPE_APPLICATION_OVERLAY` block screen |
| `ui/PornBlockActivity.kt` | Activity-based fallback block screen + preview screen |
| `widget/StopMeWidget.kt` | Home-screen widget for toggling Stop Me sessions |
| `utils/BlockerPageUtils.kt` | Keyword matching, URL validation, SafeSearch, browser ID registry |
| `utils/KeywordMatcher.kt` | Aho-Corasick automaton for O(M + matches) multi-keyword matching |
| `utils/BlockingValidator.kt` | Pure-function validation for keyword/package/intent inputs |
| `utils/StopMeManager.kt` | Stop Me session scheduler (instant + scheduled) |
| `utils/DefaultPresets.kt` | DNS presets, Stop Me durations, default whitelist apps |
| `utils/DefaultKeywordData.kt` | Asset-loader for preset block + whitelist keywords (~532 entries) |
| `utils/NewInstallBlockingUtils.kt` | First-install detection (distinguishes fresh installs from updates) |
| `utils/DeviceAdminUtils.kt` | Anti-uninstall device admin receiver + helpers |
| `utils/BlockScreenImageLoader.kt` | Fault-tolerant decoder for the user-picked motivation image |
| `identifiers/*.kt` | Enums for settings rows, VPN modes, accountability partner types, app lock types, keyword list types |
| `data/SettingPageItemModel.kt` | Row model for the settings list |

---

## 6. Feature: appPasswordPage (App Lock)

PIN / password / pattern lock with optional biometric unlock. Hashes credentials with PBKDF2-HMAC-SHA256 (100K iterations — 10× the original strength) and a per-install random 16-byte salt, stored as `salt:hash` in the `app_lock_stored_hash` switch.

### Architecture

```
MainActivity (gate)
    │
    ├─ AppLockManager (singleton)
    │     │
    │     ├─ AppLockType (OFF / PIN / PASSWORD / PATTERN)
    │     ├─ setLock(type, password)  → PBKDF2 + salt + store hash
    │     ├─ verify(input)            → constant-time compare + rate limiter
    │     └─ disableLock()            → clears type, hash, TOUCH_ID, DISABLE_FORGOT_PASSWORD
    │
    ├─ AppLockScreen (Compose)        → PIN keypad / password field / pattern grid
    │     └─ BiometricPrompt          → auto-launch if touch ID enabled + biometrics available
    │
    └─ AppLockSetupPage (Compose)     → select type → enter → confirm → save
```

### Dependencies

- `AppDatabase.switchStatusDao()` via `SwitchStatusValues`
- `androidx.biometric:biometric` (BiometricManager + BiometricPrompt)
- `SecureRandom`, `SecretKeyFactory` (PBKDF2)
- SharedPreferences for the rate limiter state
- `ProtectYourselfApp.getCrashLogger()`

### Data flow

1. **Setup**: `AppLockSetupPage` walks the user through selecting a lock type → entering credentials → confirming credentials → `AppLockManager.setLock(type, password)` generates a salt, hashes via PBKDF2 on `Dispatchers.Default` (BUG-02 fix — CPU-bound ~100 ms), persists `salt:hash`, sets `SET_APP_LOCK_SWITCH=true`, resets the rate limiter.
2. **Unlock**: on every app foreground (when in MAIN state), `MainActivity.checkAppState()` re-evaluates lock state. `AppLockScreen` collects input, calls `tryUnlock`, which calls `AppLockManager.verify(input)`.
3. **Verification**: rate-limiter check first (early reject) → validate stored salt format → PBKDF2 on `Dispatchers.Default` → constant-time compare → on failure `recordFailedAttempt()`, on success `resetRateLimiter()`.

### Rate limiter strategy

| Attempts | Delay |
|---|---|
| 1–4 | none |
| 5–9 | exponential backoff `2^(attempts-5)` seconds (1s, 2s, 4s, 8s, 16s) |
| 10+ | 5-minute lockout |

State is persisted in `app_lock_rate_limiter` SharedPreferences so it survives app restart.

### Workflow

1. User enables App Lock in the Blocker tab → `BlockerPageViewModel.toggleSwitch` emits `OpenAppLockSetup` → `BlockerPageHome` navigates to `AppLockSetupPage`.
2. User picks lock type, enters + confirms credential → `AppLockManager.setLock(type, password)` runs on `Dispatchers.Default`.
3. On success: `SET_APP_LOCK_SWITCH=true`, `app_lock_type=<type>`, hash stored. `BlockerPageViewModel.loadSettingItems()` re-runs to show the now-visible `TOUCH_ID` / `DISABLE_FORGOT_PASSWORD` rows (AL-02 fix).
4. On next app foreground: `MainActivity.checkAppState()` → `AppLockManager.isLockEnabled()` returns true → `appState = LOCKED` → `AppLockScreen` is rendered.
5. User enters credential → `AppLockManager.verify(input)` → on success `isUnlocked=true` → `appState = MAIN`.

---

## 7. Feature: stopMePage (Focus Sessions)

Quick "focus mode" sessions. During a session, apps not on the Stop Me whitelist are blocked by the accessibility service for a fixed duration.

### Architecture

```
StopMePage (Compose)            StopMeWidget (AppWidgetProvider)
    │                               │
    └─ StopMePageViewModel          └─ ACTION_START_INSTANT
            │                           │
            └─ StopMeManager (singleton)
                    │
                    ├─ startInstantSession(duration)
                    │     ├─ Insert StopMeDurationItemModel
                    │     ├─ Schedule end alarm (AlarmManager → StopMeAlarmReceiver)
                    │     └─ MyAccessibilityService.setStopMeEndTime(endTime)
                    │
                    ├─ startScheduledSession(days, startTime, duration)
                    │     ├─ Insert row
                    │     └─ Schedule start alarm
                    │
                    ├─ stopActiveSession()
                    │     ├─ Delete active row
                    │     ├─ Cancel end alarm
                    │     ├─ Increment stopMeSessionCountDao
                    │     └─ setStopMeEndTime(0L)
                    │
                    └─ checkDueSchedules() — called by AppDataCheckWorker + DailyReportWorker
```

### Dependencies

- `AppDatabase.stopMeDurationDao()` + `stopMeSessionCountDao()`
- `AlarmManager` (exact alarms with `setExactAndAllowWhileIdle` / fallback `setAndAllowWhileIdle`)
- `StopMeAlarmReceiver` (registered in manifest)
- `MyAccessibilityService.instance?.setStopMeEndTime(endTime)`

### Data flow

- **Start instant session**: `StopMeManager.startInstantSession(durationMillis)` inserts a `StopMeDurationItemModel(key="instant_${now}", duration, endTime=now+duration, days=0, startTime=0, startTimeDayMillis=0)`, schedules an end alarm via `StopMeAlarmReceiver`, and immediately calls `MyAccessibilityService.instance?.setStopMeEndTime(endTime)` so blocking starts without waiting for the next DB refresh.
- **End session**: `StopMeAlarmReceiver` fires `ACTION_STOP_ME_END` → `StopMeManager.stopActiveSession()` deletes the row, cancels the alarm, increments the session count, and calls `setStopMeEndTime(0L)`.
- **Scheduled session**: `startScheduledSession(days, startTime, duration)` calculates the next trigger via `calculateNextTrigger(days, startTimeMillis, fromTime)` (iterates 0..6 days checking day-bit match — AB-17 fix), inserts a row, and schedules a start alarm. When the start alarm fires, `StopMeManager.checkDueSchedules()` finds due schedules and starts them as instant sessions, then reschedules for the next week.

### Workflow

1. User opens Stop Me page (or taps the home-screen widget).
2. `StopMePageViewModel.state` exposes `activeSession`, `scheduledSessions`, `sessionCount`.
3. User taps a quick-start button (15/30/60/120 min) → `startInstantSession(durationMillis)`.
4. `ActiveSessionCard` renders with a 1-second ticker showing `MM:SS` countdown.
5. User can tap Stop to end early, or wait for the alarm to fire.
6. `stopMeSessionCountDao` is incremented on every successful session completion (early or natural).
7. Scheduled sessions appear in the "Scheduled sessions" list with cancel buttons.

---

## 8. Feature: schedulePage (Scheduled App Restrictions)

Time-based app restrictions — replaced the Streak feature in v1.0.62. Schedule per-app internet and/or launch blocking for time windows on chosen weekdays.

### Architecture

```
SchedulePage (Compose)            ScheduleEditorPage (Compose)
    │                                  │
    └─ SchedulePageViewModel           └─ (delegates to VM)
            │
            ├─ createSchedule / updateSchedule / deleteSchedule / toggleSchedule
            │     │
            │     ├─ scheduledRestrictionDao + scheduledRestrictionAppDao (CRUD)
            │     └─ ScheduleEngine.reevaluateAndApply()
            │           │
            │           ├─ ScheduleEvaluator.evaluate(rules, appsByRule, now)
            │           │     → ActiveRules(internetBlocked, launchBlocked)
            │           │
            │           ├─ MyAccessibilityService.updateScheduledBlockApps(launchBlocked)
            │           ├─ MyVpnService.setScheduledBlockApps(internetBlocked)  // or clear
            │           └─ ScheduleAlarmReceiver.scheduleAlarm(nextBoundary)
            │
            └─ ScheduleNavigation (VpnRequired, Error)
```

### Dependencies

- `AppDatabase.scheduledRestrictionDao()` + `scheduledRestrictionAppDao()`
- `SwitchStatusValues` (for `VPN_SWITCH` check)
- `ScheduleEngine` (singleton)
- `ScheduleAlarmReceiver` (registered in manifest)
- `SelectAppPage` (via `AppPickerDialog`)

### Data flow

- **CRUD**: every create/update/delete/toggle calls `ScheduleEngine.reevaluateAndApply()` after the DB write. The engine reads all enabled rules + per-rule apps, evaluates them via `ScheduleEvaluator` (pure function), and applies the resulting blocked sets to the accessibility service and VPN service.
- **Boundary alarms**: `ScheduleAlarmReceiver` is fired by AlarmManager at every rule boundary (start/stop being active). It calls `ScheduleEngine.reevaluateAndApply()` and reschedules the next boundary alarm.
- **Safety net**: `ScheduleCheckWorker` (15-min periodic) catches cases where AlarmManager exact alarms were delayed by Doze mode or the app was force-stopped.

### Rule types

| `ScheduleTypeIdentifiers` | Effect |
|---|---|
| `INTERNET` | VPN blocks internet for selected apps (per-app-block mode) |
| `LAUNCH` | Accessibility service blocks app launches |
| `BOTH` | Both layers |

### Workflow

1. User opens the Schedule tab → sees a list of existing rules with status badges (Active now / Inactive) and toggle/edit/delete actions.
2. User taps the FAB → `ScheduleEditorPage` opens with name, type (segmented button), start/end time (TimePickerDialog), days-of-week (filter chips), and an app picker (`AppPickerDialog` using `SelectAppPage`).
3. User saves → `SchedulePageViewModel.createSchedule(...)`:
   - Generates `schedule_<UUID>` key.
   - Inserts rule + apps in a transaction.
   - Calls `ScheduleEngine.reevaluateAndApply()`.
   - If type is `internet` or `both` and VPN is off, emits `VpnRequired` event so the UI can warn the user.
4. `ScheduleEngine` evaluates rules, updates the in-memory blocked sets, calls `MyAccessibilityService.updateScheduledBlockApps(set)` and `MyVpnService.setScheduledBlockApps(...)` (or `clearScheduledBlockApps(...)`), and schedules the next boundary alarm via `ScheduleAlarmReceiver.scheduleAlarm`.
5. At the boundary time, `ScheduleAlarmReceiver` fires → `ScheduleEngine.reevaluateAndApply()` → next boundary scheduled.

---

## 9. Feature: selectAppPage (App Picker)

A generic, reusable full-screen app picker used by 8+ features: block list, VPN whitelist, Stop Me whitelist, supported browsers, new-install block, in-app browser block, settings-by-title, blocked package names, blocked detected apps.

### Architecture

```
SelectAppPage (Compose)
    │
    └─ SelectAppPageViewModel(identifier)
            │
            ├─ loadAllInstalledApps() — PackageManager.getInstalledApplications
            ├─ loadUnsupportedBrowsers() — UnsupportedBrowserDetector (cached 60s)
            │
            └─ toggleAppSelection(app)
                  ├─ Optimistic UI update
                  ├─ DB upsert/delete by identifier + package name
                  ├─ Feature-specific cache refresh:
                  │   • VPN_WHITELIST_APPS → MyVpnService.restart() (if VPN on)
                  │   • WHITELIST_UNSUPPORTED_BROWSER → refreshBlockingConfig()
                  │   • BLOCK_NEW_INSTALL_APPS → addToNewInstallBlockCache + async full refresh (NIB-02)
                  │   • others → refreshBlockingConfig()
                  └─ On failure: revert UI + emit error toast
```

### Dependencies

- `AppDatabase.selectedAppsListDao()`
- `SwitchStatusValues`
- `MyAccessibilityService.instance` (singleton accessor)
- `MyVpnService.restart()`
- `UnsupportedBrowserDetector`
- `PackageManagerProvider`
- `DisplayAppsItemModel`

### Data flow

- **Initial load**: `SelectAppPageViewModel` queries `PackageManager.getInstalledApplications(GET_META_DATA)` (or `UnsupportedBrowserDetector.getUnsupportedBrowserPackages()` for the browser-whitelist identifier — typically 2–10 entries vs 200+), then queries the DAO for currently-selected packages and merges them into `DisplayAppsItemModel` rows.
- **Search**: `searchApp(query)` filters `allApps` by app name / package name (case-insensitive).
- **Toggle**: `toggleAppSelection(app)` optimistically updates the UI, writes to the DB, then triggers a feature-specific cache refresh so blocking takes effect immediately.

### Workflow

1. User taps an "Edit list" entry in the Blocker tab → `BlockerPageHome` navigates to `SelectAppPage(identifier)`.
2. Search bar filters the list live; tap a row to toggle selection (checked state animates).
3. Refresh icon in the top app bar re-queries PackageManager (useful if a new app was installed since the picker was opened).
4. For `WHITELIST_UNSUPPORTED_BROWSER`, an info banner explains what an "unsupported browser" is.

---

## 10. Feature: keywordManagerPage (Keyword Lists)

Manages three keyword lists simultaneously: blocklist (`PORN_BLOCK_WORDS`), whitelist (`PORN_WHITE_LIST_WORDS`), and setting-title keywords (`SETTING_KEYWORDS_LIST_WORDS`).

### Architecture

```
UnifiedBlockingPage (Compose) — 3 tabs: BLOCKLIST / WHITELIST / SETTING_TITLES
    │
    └─ KeywordManagerViewModel
            │
            ├─ addBlockKeyword / addWhitelistKeyword / addSettingTitleKeyword
            │     ├─ BlockingValidator.validateKeyword
            │     ├─ selectedKeywordDao.insert (prefixed key: block_<ts>_<hash>)
            │     ├─ refreshAccessibility() — KB-03 fix: targeted refreshKeywordList(identifier)
            │     └─ emit KeywordManagerEvent.Added
            │
            ├─ deleteKeyword(key) → delete + refresh + emit Deleted
            │
            └─ setSettingTitleSwitchEnabled(enabled) — toggles BLOCK_SETTING_PAGE_BY_TITLE_SWITCH
```

### Dependencies

- `AppDatabase.selectedKeywordDao()` + `switchStatusDao()`
- `SwitchStatusValues`
- `BlockingValidator` + `ValidationResult.toUserMessage()`
- `MyAccessibilityService.instance?.refreshKeywordList(identifier)`

### Data flow

- The ViewModel exposes `state: StateFlow<KeywordManagerState>` with all three lists, the active tab, search query, and the master switch state for setting titles.
- `hideSystemKeywords` defaults to true (UB-01 fix) — preset keywords (`preset_block_*`, `preset_whitelist_*`) are hidden from the UI but still enforced by the accessibility service.
- After every add/delete, `refreshAccessibility()` calls `MyAccessibilityService.instance?.refreshKeywordList(identifier)` (KB-03 fix — targeted refresh instead of full `refreshBlockingConfig()`).

### Validation rules

`BlockingValidator.validateKeyword(input, existing)`:
- Min 2 chars, max 100 chars
- Not blank
- Not a duplicate (case-insensitive)
- Returns `Valid(normalized)`, `Blank`, `TooShort`, `TooLong`, `Duplicate`, or `InvalidFormat(reason)`

### Workflow

1. User opens Unified Blocking Management from the Blocker tab → switches to the BLOCKLIST / WHITELIST / SETTING_TITLES tab.
2. User types a keyword in the add field → live validation via `BlockingValidator`.
3. On valid submit: `addBlockKeyword(keyword)` inserts with prefixed key, calls `refreshKeywordList(PORN_BLOCK_WORDS)` for immediate enforcement, emits `Added` event → toast.
4. User can search/filter the list and tap the delete icon per entry.

---

## 11. Feature: packageIntentPage (Package & Intent Blocking)

Advanced blocking by exact package name (e.g., `com.example.app`) or intent/class name substring (e.g., `MainActivity`). Catches apps that bypass keyword blocking via intents.

### Architecture

```
UnifiedBlockingPage (Compose) — 2 tabs: PACKAGES / INTENTS
    │
    └─ PackageIntentViewModel
            │
            ├─ addPackageEntry(input)
            │     ├─ BlockingValidator.validatePackageName (must contain dot, ^[a-z0-9._]+$)
            │     ├─ selectedAppsListDao.insert (key: blocked_pkg_<ts>_<hash>, identifier: BLOCKED_PACKAGE_NAMES)
            │     ├─ auto-enable BLOCK_PACKAGE_INTENT_SWITCH
            │     ├─ refreshBlockingConfig()
            │     └─ emit Added
            │
            ├─ addIntentEntry(input)
            │     ├─ BlockingValidator.validateIntentName (^[A-Za-z0-9._$]+$)
            │     ├─ selectedKeywordDao.insert (key: blocked_intent_<ts>_<hash>, identifier: BLOCKED_INTENT_NAMES)
            │     ├─ auto-enable switch
            │     ├─ refresh
            │     └─ emit Added
            │
            └─ deletePackage(key) / deleteIntent(key)
```

### Dependencies

- `AppDatabase.selectedAppsListDao()` + `selectedKeywordDao()` + `switchStatusDao()`
- `SwitchStatusValues`
- `BlockingValidator`
- `MyAccessibilityService.instance?.refreshBlockingConfig()`

### Data flow

- Package entries are stored in `selected_apps_table` under `BLOCKED_PACKAGE_NAMES` identifier — exact match against `event.packageName`.
- Intent entries are stored in `selected_keyword_table` under `BLOCKED_INTENT_NAMES` identifier — substring match against `event.className`.
- After any change, `refreshBlockingConfig()` reloads both lists into the accessibility service's `cachedBlockedPackageNames` and `cachedBlockedIntentNames`.

### Workflow

1. User opens Unified Blocking Management → switches to PACKAGES or INTENTS tab.
2. User enters a package name (must contain a dot, lowercase only) or intent name (alphanumeric + dots/underscores/dollar signs).
3. On valid submit, the entry is persisted, the master switch is auto-enabled if needed, and the accessibility service refreshes its cache.

---

## 12. Feature: mainActivityPage (Entry Point & Navigation)

The app's launcher activity and top-level navigation hub.

### Architecture

```
MainActivity (FragmentActivity)
    │
    ├─ onCreate → checkAppState() (IO scope)
    │     ├─ Read TERMS_APPROVE_STATUS
    │     ├─ Read AppLockManager.isLockEnabled()
    │     └─ AppState = ONBOARDING | LOCKED | MAIN
    │
    ├─ onResume → AccessibilityPersistUtils.selfHealSafe()
    │           → re-check lock state if appState == MAIN
    │
    └─ MainScreen (Compose)
            ├─ 3-tab Scaffold: Home / Schedule / Profile
            ├─ ScheduleEditorPage sub-page state
            └─ BackHandler
```

### Dependencies

- `AppDatabase.switchStatusDao()` via `SwitchStatusValues`
- `AppLockManager.getInstance(context)`
- `AccessibilityPersistUtils.selfHealSafe()`
- `BlockerPageHome` (Home tab)
- `SchedulePage` / `ScheduleEditorPage` (Schedule tab)
- `ProfilePage` (Profile tab)
- `AppTheme`

### Workflow

1. **Cold start**: `onCreate` → `checkAppState()` reads `TERMS_APPROVE_STATUS` and `AppLockManager.isLockEnabled()`. On DB error, defaults to `ONBOARDING` (BUG-25 safe fallback) and logs the throwable to CrashLogger.
2. **Loading** → spinner while `checkAppState()` runs.
3. **Onboarding** (first run): inline terms + accessibility prompt. On accept, persists `TERMS_APPROVE_STATUS=true` and opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
4. **Locked**: `AppLockScreen` is rendered; user must enter credential or use biometric.
5. **Main**: 3-tab Scaffold (Home / Schedule / Profile). Widget taps can deep-link to a specific tab via `EXTRA_OPEN_TAB`.
6. **onResume**: `AccessibilityPersistUtils.selfHealSafe(this)` (re-arm accessibility), then re-check lock state if `appState == MAIN` (closes the background→return lock-bypass hole).
7. **onNewIntent**: reads `EXTRA_OPEN_TAB` for widget tab-switches when activity is already running.

---

## 13. Feature: profilePage (Profile & Diagnostics Hub)

Profile tab content — hub for app info, theme, diagnostics, backup/restore, crash logs, and the one-time ADB setup for self-heal.

### Architecture

```
ProfilePage (Compose)
    │
    ├─ App version card (version name/code, package name)
    ├─ Profile items list:
    │     • Backup & Restore → BackupRestorePage
    │     • Crash Logs (live count badge) → CrashLogPage
    │     • Share app / Contact us / About / Delete account
    ├─ Theme selector card (Light / Dark / System Default radio rows)
    └─ Reliable Accessibility card → WriteSecureSettingsSetupPage
```

### Dependencies

- `BuildConfig` (version info)
- `ThemePreferences` (theme mode)
- `CrashLogger.entryCount` (live badge)
- `BackupRestorePage`, `CrashLogPage`, `WriteSecureSettingsSetupPage` (navigated sub-pages)
- Compose Material3

### Workflow

1. User opens Profile tab → sees app version card, items list, theme selector, reliable accessibility card.
2. Tap "Backup & Restore" → `BackupRestorePage` replaces the profile list (with `BackHandler` to return).
3. Tap "Crash Logs" → `CrashLogPage` (with live count badge from `CrashLogger.entryCount.collectAsState()`).
4. Tap a theme radio button → `ThemePreferences.setThemeMode(context, mode)` → change propagates app-wide immediately via the `themeMode` StateFlow.
5. Tap "Reliable Accessibility" → `WriteSecureSettingsSetupPage` with ADB setup instructions.

---

## 14. Feature: protectedApps (Anti-Uninstall & Self-Heal)

The most defensive feature. Two complementary mechanisms:

1. **Device Admin** (`DeviceAdminManager` + `DeviceAdminUtils.MyDeviceAdminReceiver`): replaces the "Uninstall" button with "Disable" in Settings → Apps. Tapping Disable opens the Device Admin deactivation screen, which `MyAccessibilityService.isAppInfoPage` detects and blocks.
2. **Accessibility self-heal** (`AccessibilityPersistUtils` + `AccessibilityGuard`): if the user has granted `WRITE_SECURE_SETTINGS` via ADB, the app can write directly to `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` to re-arm the accessibility service the instant Android removes it.

### Architecture

```
┌────────────────────────────────────────────────────────────┐
│                  Anti-Uninstall Layer                       │
│  DeviceAdminManager ← DevicePolicyManager                  │
│       │                                                     │
│       └─ MyDeviceAdminReceiver                              │
│             ├─ onEnabled → log breadcrumb                   │
│             ├─ onDisabled → throttled (5min) notification   │
│             └─ onDisableRequested → returns "" (UP-09)      │
└────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│              Accessibility Self-Heal Layer                  │
│                                                             │
│  AccessibilityPersistUtils.selfHealSafe(context)            │
│       │                                                     │
│       ├─ selfHealAccessibilityService(context)              │
│       │     ├─ Check WRITE_SECURE_SETTINGS granted          │
│       │     ├─ Read Settings.Secure.ENABLED_ACCESSIBILITY_  │
│       │     │   SERVICES                                    │
│       │     ├─ If our component missing, append + write     │
│       │     └─ Set accessibility_enabled=1                  │
│       │                                                     │
│       ├─ guardAllProtectedServices(context)                 │
│       │     └─ Re-arm all services in ProtectedAppsRegistry │
│       │                                                     │
│       └─ AccessibilityGuard.ensureWatching(context)         │
│                                                             │
│  AccessibilityGuard (singleton)                             │
│       ├─ ContentObserver on Settings.Secure (instant)       │
│       └─ 30-second polling fallback (OEM-proof)             │
└────────────────────────────────────────────────────────────┘
```

### Dependencies

- `DevicePolicyManager` (system service)
- `Settings.Secure` (read/write `ENABLED_ACCESSIBILITY_SERVICES`)
- `ContextCompat.checkSelfPermission` for `WRITE_SECURE_SETTINGS`
- `PackageManagerProvider`
- `MyAccessibilityService` (for class name)
- `ProtectedAppsRegistry` (SharedPreferences-backed set)
- `NotificationHelper`
- SharedPreferences for throttle timestamps

### Self-heal call sites

`AccessibilityPersistUtils.selfHealSafe(context)` is called from seven sites (defense in depth):

1. `ProtectYourselfApp.onCreate()` (app start)
2. `MainActivity.onResume()` (app comes to foreground)
3. `MyAccessibilityService.onServiceConnected()` + `onUnbind()`
4. `AppSystemActionReceiverAllTime.onReceive()` (boot, screen on, user present)
5. `AppSystemActionReceiverAllTimeWithData.onReceive()` (package install/remove/replace)
6. `AppDataCheckWorker.doWork()` (24 h periodic)
7. `AccessibilityGuard` ContentObserver + 30 s polling

### Workflow

1. User opens Profile → Reliable Accessibility → `WriteSecureSettingsSetupPage`.
2. Page shows the exact ADB command: `adb shell pm grant protect.yourself android.permission.WRITE_SECURE_SETTINGS` (auto-filled with package name), copy-to-clipboard button, and a "Verify" button.
3. User runs the command on their computer, taps Verify → page re-checks permission; if granted, immediately calls `selfHealSafe()`.
4. From this point on, `AccessibilityGuard` watches `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`:
   - **ContentObserver** fires instantly when the value changes (most OEMs).
   - **30 s polling fallback** catches OEMs that don't deliver the change notification (some Xiaomi / Huawei / Samsung ROMs).
5. When the accessibility service is detected as disabled, `selfHeal(context)` is called on a single-thread `ExecutorService` (BUG-06 fix — was creating a new Thread per observer fire).
6. If `WRITE_SECURE_SETTINGS` is missing or the write fails, falls back to a throttled (1/hour — BUG-24 fix) high-priority notification via `NotificationHelper.showAccessibilityDisabledNotification`.

### ProtectedAppsActivity

Separate `AppCompatActivity` that lists every installed accessibility service across all apps and lets the user toggle "protection" for each one. Our own service is always protected (switch locked ON). Protected services are stored in `ProtectedAppsRegistry` SharedPreferences and re-armed by `guardAllProtectedServices(context)` on every `selfHealSafe()` call.

---

## 15. Feature: backupRestore (Backup & Restore)

Local JSON backup/restore for all 10 DB tables. Uses Gson for serialization, SAF (Storage Access Framework) for file I/O, and Room `withTransaction` for atomic restore (all-or-nothing rollback on failure).

### Architecture

```
BackupRestorePage (Compose)
    │
    └─ BackupRestoreViewModel
            │
            └─ BackupManager (singleton)
                    │
                    ├─ exportToUri(outputUri)
                    │     ├─ Read all 10 tables
                    │     ├─ Build BackupEnvelope (version, app info, tables, stats)
                    │     ├─ Serialize JSON via Gson
                    │     ├─ Write via SafUtils.writeJsonToUri
                    │     └─ Verify written size matches expected
                    │
                    └─ importFromUri(inputUri)
                          ├─ Read JSON
                          ├─ Parse + validate schema version
                          └─ Restore all tables in Room withTransaction
                                (rollback on failure)
```

### Dependencies

- `AppDatabase` + all 10 DAOs
- Gson
- `SafUtils.writeJsonToUri`
- Room `withTransaction`
- `ProtectYourselfApp.getCrashLogger()`
- `MyAccessibilityService.instance?.refreshBlockingConfig()` (after import)

### Data flow

- **Export**: reads all 10 tables → builds `BackupEnvelope(backupVersion=1, appVersionCode, appVersionName, packageName, createdAt, createdAtFormatted, tables, stats)` → serializes to JSON → writes via SAF → verifies written size.
- **Import**: reads JSON → parses → validates schema version → restores all tables in a Room transaction (rollback on failure). After success, calls `MyAccessibilityService.instance?.refreshBlockingConfig()` so blocking reflects the restored switch states.

### Error handling

| Exception | Result |
|---|---|
| `IOException` | `StorageError` |
| `JsonSyntaxException` / `JsonParseException` | `InvalidFormat` |
| Schema version > current | `UnsupportedVersion` |
| Transaction failure | `DatabaseError` (rolled back) |
| `OutOfMemoryError` | `StorageError` |
| `CancellationException` | Re-thrown (preserves structured concurrency) |

### Workflow

1. User opens Profile → Backup & Restore.
2. Tap Export → SAF `CreateDocument("application/json")` opens → user picks file location → `BackupManager.exportToUri(uri)` runs on `viewModelScope` with IO dispatcher.
3. Progress bar shows percentage + status text; on success, a dialog shows stats (rows per table, total bytes).
4. Tap Import → SAF `OpenDocument()` opens → user picks existing file → confirmation dialog warns about overwrite → on confirm, `BackupManager.importFromUri(uri)` runs in a transaction.
5. On success: accessibility service refreshes its cache; on error: typed error dialog with recovery suggestions.

---

## 16. Feature: crashLog (Crash Diagnostics)

Comprehensive on-device crash + error logging. Captures timestamp, severity, thread info, stack trace, cause chain, device info, app info, memory/disk info, service state, logcat tail (FATAL only), breadcrumbs, and custom context tags. File rotation keeps the most recent 50 entries. Deduplicates consecutive identical crashes within 5 minutes.

### Architecture

```
ProtectYourselfApp.onCreate()
    │
    ├─ CrashLogger.init (FIRST)
    │
    ├─ installCrashHandler (Thread.setDefaultUncaughtExceptionHandler)
    │     └─ routes FATAL to CrashLogger
    │
    ├─ Timber.plant(DebugTree + CrashLoggingTree)
    │     └─ routes WARN+ to CrashLogger
    │
    └─ AnrWatchdog.start
          └─ polls main thread every 2.5s; logs FATAL ANR if blocked > 5s
                (with false-positive check: skip if main thread is nativePollOnce)

CrashLogger (singleton)
    │
    ├─ logThrowable(throwable, severity, tag, message, extraContext)
    ├─ logMessage(severity, tag, message, extraContext)
    ├─ logBreadcrumb(category, message, data)  ← called from many sites
    ├─ readEntries(limit) / readEntry(id)
    ├─ deleteEntry(id) / clearAll()
    └─ exportAllToJson()  ← for SAF export

Persistence:
    context.filesDir/crashlogs/
        ├── <id>.json          (per-entry file)
        ├── crash_index.json   (ordered list of ids)
        └── breadcrumbs.json   (ring buffer)
```

### Dependencies

- `ActivityManager`, `DevicePolicyManager`, `StatFs` (device/memory/disk info)
- `Settings.Secure` (service state)
- Gson
- `BuildConfig`
- `Process.myPid()`
- `AtomicLong` / `AtomicBoolean` (re-entrancy guard)
- Timber

### Data captured per entry

- `id`, `timestamp`, `severity`, `tag`, `message`
- `throwableClass`, `stackTrace`, `causeChain`
- `threadName`, `threadId`, `processId`, `isMainThread`
- `deviceInfo` (model, manufacturer, brand, SDK, build, fingerprint, isEmulator)
- `appInfo` (version name/code, package, installer package name)
- `memoryInfo` (available/total RAM, low-memory state)
- `diskInfo` (available/total disk)
- `serviceState` (accessibility enabled, VPN active, device admin active) — #1 diagnostic
- `logcatTail` (FATAL only)
- `breadcrumbs` (recent breadcrumb entries)
- `extraContext` (caller-supplied key/value map)
- `dedupHash`, `count`

### Workflow

1. On app start, `CrashLogger.init()` creates the `crashlogs/` directory, loads the index, and starts fresh (per-session log dedup tracker is reset).
2. Throughout the app, `logBreadcrumb(category, message, data)` is called from many sites (AppLockManager, BackupManager, AppSystemActionReceiverAllTimeWithData, etc.) — these go to a ring buffer that's included in the next crash entry.
3. When an exception is thrown:
   - **FATAL uncaught** → `Thread.getDefaultUncaughtExceptionHandler()` routes to `logThrowable(severity=FATAL)`.
   - **WARN/ERROR logged via Timber** → `CrashLoggingTree` routes to `logThrowable` or `logMessage`.
   - **ANR** → `AnrWatchdog` posts a tick to the main Handler every 2.5 s; if the tick doesn't run within 5 s AND the main thread is not `nativePollOnce` (idle), logs FATAL ANR with the main-thread stack trace.
4. `persistEntry` writes a new JSON file atomically (temp + rename) to survive hard crashes. Dedup hash is computed from stack trace + tag + message; if the same hash appears within 5 minutes, the existing entry's `count` is incremented instead.
5. On next app launch, `notifyIfCrashedSinceLastLaunch()` checks for FATAL entries with timestamp > last launch time and posts a high-priority notification.
6. User can view logs in Profile → Crash Logs (badge shows live count), tap an entry for a detail dialog with the full stack trace + device info + breadcrumbs, export to JSON via SAF, or clear all.

---

## 17. Domain Layer: schedule (Schedule Engine)

The single coordinator for Scheduled App Restrictions. The ONLY component that decides which apps are blocked — VPN and Accessibility read from it, never from the DB directly.

### Architecture

```
ScheduleEngine (singleton)
    │
    ├─ reevaluateAndApply()
    │     ├─ Read all enabled rules + per-rule apps from DB
    │     ├─ ScheduleEvaluator.evaluate(rules, appsByRule, now) → ActiveRules
    │     ├─ If launchBlocked != lastLaunchBlocked:
    │     │     ├─ Cache new set
    │     │     └─ MyAccessibilityService.updateScheduledBlockApps(set)
    │     ├─ If internetBlocked != lastInternetBlocked:
    │     │     ├─ Cache new set
    │     │     └─ MyVpnService.setScheduledBlockApps(...) or clearScheduledBlockApps(...)
    │     └─ ScheduleAlarmReceiver.scheduleAlarm(nextBoundary) or cancelAlarm
    │
    ├─ onBootCompleted() — re-arms alarms after boot
    ├─ getActiveInternetBlockedApps() — for VPN to query
    ├─ getActiveLaunchBlockedApps() — for Accessibility to query
    └─ resetCachedState() — M3 fix: clears cached sets so next reevaluateAndApply()
                            detects the change (called by MyVpnService.onRevoke)

ScheduleEvaluator (object — pure function, no I/O, unit-testable)
    │
    ├─ evaluate(rules, appsByRule, now) → ActiveRules
    │     For each enabled rule:
    │       • Check day-of-week bitmask (bit 0=Sunday, ..., bit 6=Saturday; 127=every day; 0=never)
    │       • Check time window (handles cross-midnight when endTime < startTime)
    │       • Add rule's apps to internetBlocked / launchBlocked / both based on type
    │     Multiple active rules → union
    │
    └─ nextBoundary(rules, appsByRule, now) → Long
          Calculates the next timestamp (within 7 days) when a rule starts or stops.
          Returns Long.MAX_VALUE if no future boundary.

ScheduleAlarmReceiver (BroadcastReceiver)
    │
    ├─ onReceive → ScheduleEngine.reevaluateAndApply() on IO scope
    │              → reschedule next boundary alarm
    │
    └─ scheduleAlarm(context, triggerAtMillis)
          • setExactAndAllowWhileIdle on API < S or when canScheduleExactAlarms() on S+
          • Fallback: setAndAllowWhileIdle
```

### Dependencies

- `AppDatabase.scheduledRestrictionDao()` + `scheduledRestrictionAppDao()`
- `ScheduleEvaluator` (pure function)
- `MyAccessibilityService.instance`
- `MyVpnService`
- `ScheduleAlarmReceiver`
- `AlarmManager`

### Idempotency

`reevaluateAndApply()` is safe to call multiple times — it only restarts VPN / updates the accessibility cache when the active set actually changes. This is critical because it's called from:

- `SchedulePageViewModel` after every CRUD
- `ScheduleAlarmReceiver` at every boundary
- `ScheduleCheckWorker` every 15 minutes (safety net)
- `AppSystemActionReceiverAllTime` after boot

### Time representation

- `startTimeMinutes` / `endTimeMinutes` are minutes from midnight (0–1439)
- Day-of-week bitmask: bit 0=Sunday (1), bit 1=Monday (2), ..., bit 6=Saturday (64). 127 = every day. 0 = never active.
- `Calendar.DAY_OF_WEEK` returns 1=Sunday through 7=Saturday, so the engine shifts by `(dayOfWeek - 1)`.

---

## 18. Background Infrastructure

### WorkManager workers (`commons/utils/workManager/`)

| Worker | Periodicity | Purpose |
|---|---|---|
| `AppDataCheckWorker` | 24 h | Verify DB integrity (block_screen_count, stop_me_session_count rows exist); re-apply accessibility config; check due Stop Me schedules; `selfHealSafe()` periodic safety net |
| `DailyReportWorker` | 24 h | Always: check Stop Me schedules, check accessibility state. If `DAILY_REPORT_SWITCH` on: show daily summary notification with block count |
| `ScheduleCheckWorker` | 15 min | Safety net for scheduled app restrictions — catches cases where AlarmManager exact alarms were delayed by Doze mode or app was force-stopped. Calls `ScheduleEngine.reevaluateAndApply()` |
| `VpnRestartWorker` | one-time (expedited) | Starts VPN service after boot. On Android 12+, manifest receivers can't start foreground services directly — this expedited worker is exempt from the restriction. Pre-checks `VpnService.prepare()` and syncs `VPN_SWITCH=false` if permission was revoked (BUG-18) |

`WorkerUtils` is the singleton entry point: `initAppDataCheckWorker(context)` schedules AppDataCheckWorker + DailyReportWorker; `ScheduleCheckWorker.enqueue(context)` and `VpnRestartWorker.enqueue(context)` are called from `ProtectYourselfApp.onCreate()` and `AppSystemActionReceiverAllTime` respectively.

### Broadcast receivers (`commons/utils/broadcastReceivers/`)

| Receiver | Actions | Purpose |
|---|---|---|
| `AppSystemActionReceiver` | `CONNECTIVITY_CHANGE` | Re-evaluates VPN state — if `VPN_SWITCH` is on but service isn't running, restarts it (BUG-12 fix). Rarely fires on Android 7+ (only when app is in foreground) |
| `AppSystemActionReceiverAllTime` | `BOOT_COMPLETED`, `REBOOT`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `SCREEN_ON`, `USER_PRESENT`, `MY_PACKAGE_REPLACED` | Layer 3 of uninstall protection. Refreshes accessibility config; schedules `VpnRestartWorker` (if VPN switch on); calls `ScheduleEngine.onBootCompleted()` |
| `AppSystemActionReceiverAllTimeWithData` | `MY_PACKAGE_REPLACED` (separate filter), `PACKAGE_ADDED`, `PACKAGE_REMOVED` (with `data` scheme filter) | The new-install auto-block engine. On `PACKAGE_ADDED`: checks `BLOCK_NEW_INSTALL_APPS_SWITCH`, verifies genuine first install via `NewInstallBlockingUtils.isFirstInstall`, inserts into DB, refreshes accessibility cache (targeted + full — NIB-01/NIB-02 fix). On `PACKAGE_REMOVED`: cleans up package from ALL `SelectedAppListIdentifier` values. Also invalidates `UnsupportedBrowserDetector` cache on both events |
| `StopMeAlarmReceiver` | `STOP_ME_START`, `STOP_ME_END` | Stop Me session lifecycle. `STOP_ME_START` → `StopMeManager.checkDueSchedules()`. `STOP_ME_END` → `StopMeManager.stopActiveSession()` |
| `ScheduleAlarmReceiver` | `SCHEDULE_BOUNDARY` | Fired by AlarmManager at schedule rule boundaries. Calls `ScheduleEngine.reevaluateAndApply()` and reschedules the next boundary alarm |

All receivers call `AccessibilityPersistUtils.selfHealSafe(context)` synchronously as defense-in-depth.

### NotificationHelper (`commons/utils/notificationUtils/`)

Central notification channel + builder utilities. Three channels:

| Channel ID | Importance | Purpose |
|---|---|---|
| `daily_report_channel` | DEFAULT | Daily summary of blocking activity |
| `accessibility_alert_channel` | HIGH | Critical alerts when accessibility service is disabled |
| `general_channel` | LOW | General notifications |

Plus the VPN service creates its own `vpn_service_channel` (LOW) for the foreground service notification, and `ProtectYourselfApp` creates `crash_alerts` (HIGH) for crash-detected notifications.

Key APIs: `createAllChannels(context)`, `showDailyReportNotification(context, blockCount)`, `showAccessibilityDisabledNotification(context)` (throttled to 1/hour), `showOverlayPermissionNotification(context)` (throttled to once per 24 h), `showVpnPermissionRequiredNotification(context)`.

---

## 19. Theme System

### Architecture

```
ThemePreferences (object)
    │
    ├─ themeMode: StateFlow<Int>  (0=Light, 1=Dark, 2=System Default)
    ├─ init(context) — loads from protect_yourself_theme SharedPreferences (default 2)
    └─ setThemeMode(context, mode)

AppTheme (Composable)
    │
    ├─ Reads ThemePreferences.themeMode via collectAsState()
    ├─ MODE_SYSTEM → falls back to isSystemInDarkTheme()
    └─ Applies DarkColorScheme or LightColorScheme
```

### Color palette

| Role | Dark | Light |
|---|---|---|
| Primary | `#1F323F` | `#1F323F` |
| Background | `#061620` | `#F5F7FA` |
| Surface | `#151F26` | `#FFFFFF` |
| Tertiary (Brand Orange) | `#FF7100` | `#FF7100` |
| Secondary (Accent Cyan) | `#3ACFFE` | `#3ACFFE` |
| Error | `#FF5722` | `#FF5722` |

### Typography

`NunitoFontFamily` loaded from `res/font/`: `nunito_regular.ttf`, `nunito_semibold.ttf`, `nunito_bold.ttf`, `nunito_extrabold.ttf`. Defines all Material3 typography styles (display / headline / title / body / label — large / medium / small).

### Workflow

1. `ThemePreferences.init(context)` is called from `ProtectYourselfApp.onCreate()`.
2. `AppTheme` is the root composable used by `MainActivity.setContent`.
3. User changes theme in Profile → Theme card → `ThemePreferences.setThemeMode(context, mode)`.
4. The StateFlow emits the new mode → `AppTheme` recomposes with the new color scheme → change propagates app-wide immediately.

---

## 20. Permissions Summary

The manifest declares 22 permissions (down from the original 25 — `BILLING`, `AD_ID`, `INSTALL_REFERRER` removed with the paid/ads features). Notable entries:

| Permission | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Block screen overlay (`TYPE_APPLICATION_OVERLAY`) |
| `BIND_ACCESSIBILITY_SERVICE` | Core blocking engine — declared on the service, granted by user in Settings |
| `BIND_VPN_SERVICE` | DNS-filtering VPN |
| `WRITE_SECURE_SETTINGS` | Self-heal: re-arm accessibility service programmatically (granted via ADB) |
| `DEVICE_ADMIN` | Anti-uninstall (replaces Uninstall button with Disable) |
| `FOREGROUND_SERVICE_DATA_SYNC` / `FOREGROUND_SERVICE_SPECIAL_USE` | Required for targetSdk 34+ foreground service types |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Stop Me + Schedule boundary alarms |
| `QUERY_ALL_PACKAGES` | App picker feature (declared with `tools:ignore`) |
| `INTERACT_ACROSS_USERS_FULL` / `INTERACT_ACROSS_USERS` | Multi-user self-heal flows (signature-level) |
| `KILL_BACKGROUND_PROCESSES` / `REORDER_TASKS` | Block screen KillTimer (HOME × 5 + BACK × 1) |
| `RECEIVE_BOOT_COMPLETED` | Re-arm blocking after boot |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | App lock biometric unlock |

A custom signature-level permission `protect.yourself.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is declared for safe dynamic receiver registration on Android 13+.

### Queries block

The manifest declares `<queries>` for `MAIN`, `SENDTO`, `VIEW`, `PICK`, `DIAL`, `TTS_SERVICE`, `GET_CONTENT`, `IMAGE_CAPTURE`, `BROWSABLE https`, and `CustomTabsService` intents so the app has visibility into other installed apps (required for the app picker, share, contact, and browser detection features on Android 11+).

---

## 21. Building & Running

### Prerequisites

- Android Studio Koala or newer (or Gradle 8.7.2 from CLI)
- JDK 17
- Android SDK with `compileSdk = 35`, `minSdk = 26`
- For self-heal testing: a device or emulator with ADB access

### Setup

```bash
# Clone the repo
git clone <repo-url>
cd protect_yourself

# Copy the local.properties template and edit SDK path
cp setup/local.properties.template local.properties
# Edit local.properties → sdk.dir=/path/to/Android/sdk

# (Optional) Run the setup script for SDK packages
bash setup/setup-env.sh
```

### Build

```bash
# Debug build
./gradlew assembleDebug

# Release build (signed with debug keystore by default — re-sign for Play Store)
./gradlew assembleRelease
```

APKs are output to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

### Tests

```bash
# Unit tests (Robolectric + MockK + Turbine)
./gradlew test

# Instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

### Granting WRITE_SECURE_SETTINGS (for self-heal)

```bash
adb shell pm grant protect.yourself android.permission.WRITE_SECURE_SETTINGS
```

After granting, open the app → Profile → Reliable Accessibility → tap "Verify" to confirm.

---

## 22. Project Structure

```
protect_yourself/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── preset_block_keywords.json       # ~532 preset block keywords
│       │   │   └── preset_whitelist_keywords.json   # preset whitelist keywords
│       │   ├── java/protect/yourself/
│       │   │   ├── core/                            # AppContainer, ProtectYourselfApp, exception handler
│       │   │   ├── database/                        # Room: 10 entities, 10 DAOs, AppDatabase
│       │   │   │   ├── core/                        # AppDatabase, AppDatabaseCallback
│       │   │   │   ├── switchStatus/
│       │   │   │   ├── selectedApps/
│       │   │   │   ├── selectedKeywords/
│       │   │   │   ├── blockScreensCount/
│       │   │   │   ├── pendingRequests/
│       │   │   │   ├── stopMeDuration/
│       │   │   │   ├── stopMeSessionCount/
│       │   │   │   ├── vpnCustomDns/
│       │   │   │   └── scheduledRestrictions/
│       │   │   ├── domain/
│       │   │   │   └── schedule/                    # ScheduleEngine, ScheduleEvaluator, ScheduleAlarmReceiver
│       │   │   ├── features/
│       │   │   │   ├── mainActivityPage/            # MainActivity, MainPageScreen
│       │   │   │   ├── blockerPage/                 # The big one — see section 5
│       │   │   │   ├── appPasswordPage/             # AppLockManager, AppLockScreen, AppLockSetupPage
│       │   │   │   ├── stopMePage/                  # StopMePage, StopMePageViewModel
│       │   │   │   ├── schedulePage/                # SchedulePage, ScheduleEditorPage
│       │   │   │   ├── selectAppPage/               # SelectAppPage, UnsupportedBrowserDetector
│       │   │   │   ├── keywordManagerPage/          # KeywordManagerViewModel
│       │   │   │   ├── packageIntentPage/           # PackageIntentViewModel
│       │   │   │   ├── profilePage/                 # ProfilePage
│       │   │   │   ├── protectedApps/               # DeviceAdminManager, AccessibilityGuard, AccessibilityPersistUtils
│       │   │   │   ├── backupRestore/               # BackupManager, BackupRestorePage
│       │   │   │   └── crashLog/                    # CrashLogger, AnrWatchdog, CrashLogPage
│       │   │   ├── commons/utils/
│       │   │   │   ├── workManager/                 # AppDataCheckWorker, DailyReportWorker, ScheduleCheckWorker, VpnRestartWorker
│       │   │   │   ├── broadcastReceivers/          # AppSystemActionReceiver*, StopMeAlarmReceiver
│       │   │   │   ├── notificationUtils/           # NotificationHelper
│       │   │   │   ├── PackageManagerProvider.kt
│       │   │   │   ├── SafUtils.kt
│       │   │   │   └── OncePerSessionLogger.kt
│       │   │   └── theme/                           # Color, Type, Theme, ThemePreferences
│       │   └── res/
│       │       ├── drawable/
│       │       ├── font/                            # Nunito (regular, semibold, bold, extrabold)
│       │       ├── layout/                          # page_porn_block.xml, stop_me_widget.xml (XML fallbacks)
│       │       ├── mipmap-*/                        # Launcher icon (multiple densities)
│       │       ├── values/                          # colors.xml, strings.xml, themes.xml
│       │       ├── values-night/                    # Night theme
│       │       └── xml/                             # accessibility_setting.xml, device_admin.xml, file_paths.xml, stop_me_widget_info.xml
│       ├── androidTest/                             # Instrumentation tests
│       └── test/                                    # Unit tests (Robolectric)
├── apk/                                             # Pre-built APKs (v1.0.62 debug + release)
├── docs/                                            # Analysis + fix reports (per-version subdirs)
├── gradle/
│   ├── libs.versions.toml                           # Version catalog
│   └── wrapper/
├── setup/                                           # Setup scripts + SDK package list
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat
├── LICENSE
├── CONTRIBUTING.md
└── README.md
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines, code style, and pull request process.

## License

See [LICENSE](LICENSE) for details.
