# Protect Yourself — Source Code Analysis Report

**Task ID:** S-1
**Agent:** Source-Analyzer
**Repository:** `/home/z/my-project/repo/Protect-Yourself/`
**App version analyzed:** 1.0.37 (versionCode 37)
**Tech stack:** Kotlin 2.0.21, AGP 8.7.2, Jetpack Compose (BOM 2024.10.01), Room 2.6.1, WorkManager 2.10.0, minSdk 26, targetSdk 35
**Report date:** Source analysis pass

---

## 1. Executive Summary

"Protect Yourself" is a free, open-source Android content-blocker / focus-companion app whose explicit purpose is to help users overcome porn addiction and reduce digital distraction. It is described in its own onboarding screen (`MainActivity.kt:243-249`) as "a free, open-source app blocker & focus companion to help you overcome porn addiction and build healthier digital habits." The codebase is a Kotlin / Jetpack Compose rebuild of the original closed-source NopoX app (`docs/NOPOX_ANALYSIS.md` is referenced in the source) — the rebuild strips out billing, ads, analytics, Branch.io, and reCAPTCHA, leaving only the local on-device blocking pipeline. AboutPage (`AboutPage.kt:69-87`) confirms "Removed: Subscriptions, in-app purchases, premium features, paywalls / AdMob banner ads / Amplitude + Firebase Analytics / Branch.io / Google reCAPTCHA" while keeping all blocking features.

The blocking pipeline has three independent layers: (1) an **Accessibility Service** (`MyAccessibilityService.kt`, 1,027 lines) that listens for window/content events, extracts URLs from browser address bars, matches them against an Aho-Corasick-automaton keyword list (1,189+ keywords across 15+ languages, loaded from `assets/preset_block_keywords.json`), and launches a full-screen `PornBlockActivity` overlay on match; (2) a **DNS-filtering VPN Service** (`MyVpnService.kt`, 989 lines) that hijacks DNS traffic to /32 routes for the user-selected filtered resolver (Cloudflare Family 1.1.1.3, AdGuard Family 94.140.14.15, OpenDNS FamilyShield, CleanBrowsing, or custom) and forwards queries through a `protect()`-ed DatagramSocket pool, validating transaction IDs against forged responses; (3) **SafeSearch enforcement** that redirects Google/Bing/YouTube/DuckDuckGo to their `forcesafesearch`/`strict`/`restrict`/`safe` variants via accessibility intents.

Supporting features include: Stop Me focus mode with one-tap + scheduled sessions and a home-screen widget; streak tracking with achievements and a separate widget; anti-uninstall via Device Admin (`DeviceAdminUtils.kt`) + Accessibility Guard self-heal watchdog; an app lock supporting PIN/Password/Pattern + biometric, with PBKDF2-HMAC-SHA256 (100,000 iterations) password hashing; local JSON backup/restore through the Storage Access Framework with atomic transactional restore; structured on-device crash logging (`CrashLogger.kt`, 685 lines) replacing Firebase Crashlytics; and a manual-DI AppContainer pattern. Build configuration deliberately removes Firebase (the `google.services` plugin is commented out in `app/build.gradle.kts:6-9`) because FirebaseInitProvider was crashing the app, and `AndroidManifest.xml:348-376` disables every AndroidX startup auto-initialiser so the Application class controls boot order with `safeInit()` try/catch wrappers. The overall posture is: heavy local-only security product, intentionally stripped of cloud dependencies, written defensively but with several large god-classes and a handful of likely bugs that are catalogued in §9.

## 2. Architecture Overview

The project follows a conventional single-module Android structure with package-by-feature organisation rooted at `protect.yourself`. There are four logical layers:

- **`core/`** — `ProtectYourselfApp` (Application subclass + LifecycleObserver + WorkManager `Configuration.Provider`) and `AppContainer` (manual DI container holding the application-scoped coroutine scope and the Room database getter). There is no Hilt/Dagger — singletons are hand-rolled with `@Volatile var instance` + `synchronized(this)` double-checked locking in `AppContainer.get()` (`AppContainer.kt:40-44`), `AppDatabase.getInstance()` (`AppDatabase.kt:73-92`), `BlockerPageUtils.getInstance()` (`BlockerPageUtils.kt:402-406`), `CrashLogger.getInstance()` (`CrashLogger.kt:584-588`), and at least eight other places. This is consistent but verbose and easily replaced by Hilt; nevertheless it works and avoids annotation-processor overhead.

- **`database/`** — Room database with 9 entities / 9 DAOs at schema version 9. `AppDatabase` (`AppDatabase.kt`) declares a single explicit migration (`MIGRATION_8_9` adding a `display_name` column to `vpn_custom_dns`) and falls back to `fallbackToDestructiveMigration()` for any future schema bumps. The generic `switch_status` table (`SwitchStatusItemModel`) is a key/value store with a `type` discriminator column ("boolean"/"int"/"long"/"string") used by `SwitchStatusValues` (294 lines) to expose 50+ typed getters and a smaller set of `Flow`-based observers for Compose. The full key catalogue lives in `SwitchIdentifier.kt` (47 keys covering content blocking, social-media blocking, uninstall protection, VPN, app lock, accountability partner, block-screen customization, and app-state metadata).

- **`features/`** — Feature modules grouped by domain: `mainActivityPage`, `blockerPage` (the heart of the app — service + utils + viewmodel + components), `protectedApps` (anti-uninstall), `appPasswordPage` (app lock), `backupRestore`, `crashLog`, `signinSignupPage` (stub — Firebase removed), `stopMePage`, `streakPage`, `keywordManagerPage`, `selectAppPage`, `packageIntentPage`, `transparentPage`, `ratingPage`, `agreeTermsPage`, `profilePage`. Each feature typically follows MVVM: a `*ViewModel` extending `ViewModel`/`AndroidViewModel`, a `*Page` Composable, optional `components/`, `data/`, and `identifiers/` subpackages. State is exposed via `StateFlow` / `SharedFlow` (see `BlockerPageViewModel.kt:70-78`); one-shot navigation events use `MutableSharedFlow<BlockerPageNavigation>` with `extraBufferCapacity = 5`.

- **`commons/utils/`** — Cross-feature infrastructure: `workManager/` (`VpnRestartWorker`, `AppDataCheckWorker`, `DailyReportWorker`, `WorkerUtils`), `notificationUtils/` (`NotificationHelper`, `NotificationActionService`), `broadcastReceivers/` (`AppSystemActionReceiver`, `AppSystemActionReceiverAllTime`, `AppSystemActionReceiverAllTimeWithData`, `StopMeAlarmReceiver`), `PackageManagerProvider`.

- **`theme/`** — Compose theme (Color.kt, Type.kt, Theme.kt) using `BrandOrange` as the primary accent and a Nunito font family loaded from `res/font/`.

The MVVM pattern is consistent: Compose screens collect `StateFlow` via `collectAsState()`, dispatch intents through ViewModel methods that `viewModelScope.launch` suspend work, and the ViewModel emits side-effect events through a `SharedFlow`. WorkManager is configured for **on-demand initialisation** (manifest removes `WorkManagerInitializer`, `ProtectYourselfApp.workManagerConfiguration` provides the config) so the app boot is not blocked by WorkManager. There is no clean architecture "domain" layer — ViewModels talk directly to DAOs, which is appropriate given the relatively contained domain. Coroutine scoping is good overall: `viewModelScope` for VMs, `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.X)` for services, `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` for app-level fire-and-forget; no `GlobalScope` or `runBlocking` was found in production code (only mentioned in docstrings).

## 3. Module-by-Module Analysis

### 3.1 Core (`ProtectYourselfApp`, `AppContainer`)

`ProtectYourselfApp` (`core/ProtectYourselfApp.kt`, 191 lines) extends `Application` and implements both `LifecycleObserver` (for `ON_STOP` background detection) and `Configuration.Provider` (for WorkManager on-demand init). The `onCreate()` is carefully ordered: CrashLogger is initialised FIRST (line 55), then a global uncaught-exception handler is installed that routes crashes to CrashLogger with severity `FATAL` and `threadName`/`threadId`/`isDaemon` context (lines 150-172), then Timber is planted with both `DebugTree` and a custom `CrashLoggingTree`, then each subsequent init step runs inside a `safeInit(name) { ... }` wrapper that catches Throwable and logs without crashing. This is a pragmatic and resilient boot sequence — every init step is isolated, and a failure in (say) `AccessibilityGuard` cannot prevent `WorkManager` or `NotificationChannels` from initialising. The Application deliberately does NOT extend any signature-killer or Firebase-aware class — Firebase is entirely absent from the build.

`AppContainer` (`core/AppContainer.kt`, 46 lines) is intentionally minimal: it exposes `applicationScope` and a lazy `appDatabase` getter. The original container also held `billingDataSource` and `premiumPageDataRepository`, both removed in this rebuild (lines 12-14). Notably, the `appDatabase` property is declared as a `get()` on a `val`, which means every access calls `AppDatabase.getInstance(appContext)` — although `AppDatabase.getInstance()` is itself a double-checked-locking singleton, so this is correct but stylistically a bit awkward. The container's own singleton (`AppContainer.get()`, lines 40-44) is never used in the code I read — features directly access `AppDatabase.getInstance(context)` and feature singletons — so the container is somewhat vestigial.

### 3.2 Database (Room entities, DAOs, migrations)

`AppDatabase` (`database/core/AppDatabase.kt`, 132 lines) declares 9 entities: `BlockScreenCountItemModel`, `PendingRequestItemModel`, `SelectedAppItemModel`, `SelectedKeywordItemModel`, `StopMeDurationItemModel`, `StopMeSessionCountItemModel`, `StreakDatesItemModel`, `SwitchStatusItemModel`, `VpnCustomDnsItemModel`. Schema version is 9 with `exportSchema = true` (schemas are exported to `app/schemas/` per the Room kapt arg in `app/build.gradle.kts:27-33`). The builder registers `MIGRATION_8_9` (lines 110-130) before `fallbackToDestructiveMigration()` (line 87) — the explicit migration ALTERs `vpn_custom_dns` to add `display_name TEXT NOT NULL DEFAULT ''` and backfills the 4 default presets, preserving user data. `fallbackToDestructiveMigration()` is a safety net for any future bump that lacks a migration. The DB file is `protect_yourself_database.db`.

The `SwitchStatusItemModel` (`database/switchStatus/SwitchStatusItemModel.kt`) is a key/value entity with `key` (PK), `value` (String), and `type` (String discriminator). It exposes `asBoolean()`, `asInt()`, `asLong()`, `asString()` parse helpers using `toBooleanStrictOrNull() ?: false` and `toIntOrNull() ?: 0` — these defaults silently coerce malformed data, which is convenient but masks corruption. The `SwitchStatusDao` is standard Room with `@Insert(onConflict = REPLACE)` upsert, observe-by-key `Flow`, and a `deleteAll()` used during backup restore. `SwitchStatusValues` wraps the DAO with 50+ typed suspend getters covering every key in `SwitchIdentifier.kt` and 7 `Flow`-based observers for the most Compose-relevant switches. Premium-related getters (`isPremiumActive()`, `isEligibleForBannerAd()`) are kept as stubs returning `false`/`0L` for compatibility with original code paths (lines 226-235).

`AppDatabaseCallback` (separate file) runs on first DB creation to seed default DNS presets, default Stop Me durations, default keyword data (loaded from `assets/preset_block_keywords.json` and `preset_whitelist_keywords.json` via `DefaultKeywordData`), and a default `BlockScreenCount` row. This is the standard Room callback pattern and works correctly.

### 3.3 BlockerPage (Accessibility service, VPN service, keyword matching, presets)

This is the largest module in the codebase and the heart of the app. Key files:

- **`MyAccessibilityService.kt`** (1,027 lines) — Listens for `TYPE_VIEW_CLICKED`, `TYPE_VIEW_FOCUSED`, `TYPE_VIEW_LONG_CLICKED`, `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED` (configured both statically in `accessibility_setting.xml` and dynamically in `configureService()` at lines 98-118). It maintains cached blocking config (`cachedBlockKeywords`, `cachedWhitelistKeywords`, `cachedBlockApps`, `cachedStopMeWhitelist`, etc.) refreshed from Room via `refreshBlockingConfig()` on service connect and periodically by `AppDataCheckWorker`. The `onAccessibilityEvent` dispatcher (lines 120-149) routes window-state-changed events to `handleWindowStateChange` (which handles app-blocking, settings-page blocking, prevent-uninstall, power-menu blocking, Stop Me enforcement) and content-change events to `handleContentChange` (which scrapes URLs from browser address bars via known view IDs in `BlockerPageUtils.BROWSER_URL_VIEW_IDS`, with a fallback recursive `findUrlInNode` that has a depth limit of 50 to prevent StackOverflow — KB-20 fix at line 1009). For non-browser apps, content text is collected up to depth 3 and matched against the keyword list with a 5,000-char length cap (line 1000). Block decisions launch `PornBlockActivity` via `Intent.FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` with dual throttling (per-package 500 ms + global 300 ms, lines 994-995). The service also implements SafeSearch enforcement (`enforceSafeSearch`, lines 385-429) which redirects Google/Bing/YouTube/DuckDuckGo to their safe variants using `startActivity` with `setPackage(packageName)` to keep the user in the same browser, throttled by URL-without-query-string to prevent redirect loops (KB-23 fix).

- **`MyVpnService.kt`** (989 lines) — DNS-only VPN service (NOT a full packet forwarder). The Builder (lines 224-263) adds `/32` routes for only the two DNS server IPs plus a hard-coded `DNS_HIJACK_HOSTS` list of ~30 common public resolvers (Google 8.8.8.8/8.8.4.4, Cloudflare 1.1.1.1/1.0.0.1, Quad9, OpenDNS, AdGuard, CleanBrowsing, Comodo, Verisign, Yandex, DNS.WATCH, Level3 — lines 900-943). All other traffic bypasses the VPN via `addDisallowedApplication` for whitelisted apps + the app itself (lines 248-263). The DNS forwarder loop (`startDnsForwarder`, lines 348-430) reads IP packets from the TUN, parses IPv4+UDP headers, validates that the destination port is 53, extracts the DNS payload, and launches a per-query coroutine on a `limitedParallelism(8)` dispatcher. Each coroutine acquires a `protect()`-ed DatagramSocket from a 16-socket pool (`DnsSocketPool`, lines 489-548 — VPN-02 fix preventing FD exhaustion), sends to the primary upstream, falls back to the secondary on failure, validates the response transaction ID against the request (security fix at lines 472-477), and writes the response back to the TUN with `synchronized(outputLock)` to serialise writes. Response packets are built by `buildDnsResponsePacket` (lines 589-643) with a **correctly computed IPv4 header checksum** (VPN-03 fix — some OEM kernels drop zero-checksum packets) using `computeIpChecksum` (RFC 1071). The service uses `START_STICKY`, exposes `start`/`stop`/`restart` companion methods, and implements `onRevoke` (lines 817-854) to update the switch + schedule a single self-restart attempt (VPN-04 fix prevents restart cascades). Foreground service uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on Android 14+ with subtype `"VPN DNS filtering for content blocking"` (manifest line 230).

- **`KeywordMatcher.kt`** (143 lines) — Aho-Corasick automaton for multi-keyword substring matching (KB-02 fix). The original NopoX used O(N×M) linear scan over 532 keywords per URL detection; the automaton precompiles keywords into a trie with failure links (built via BFS in the `init` block, lines 37-71), enabling O(M + matches) matching. The `findFirst` method (lines 78-99) returns the first match, `findAll` walks the failure chain to collect every match. The matcher is cached in `BlockerPageUtils.getOrBuildMatcher()` keyed by the keyword list's `hashCode()` with `@Volatile` + double-checked locking (lines 213-224). Thread-safe because the automaton is immutable after construction.

- **`BlockerPageUtils.kt`** (655 lines) — Central utility for keyword matching, URL validation, encoding, SafeSearch host mapping. Implements `normalizeForMatching` (KB-04 fix) which strips zero-width characters (U+200B/200C/200D/FEFF), applies Unicode NFKD normalisation, strips combining diacritical marks, decodes IDN punycode (`xn--…` → unicode), and lowercases — this defeats most homograph attacks like `p⊕rnhub.com` or `раrn` (Cyrillic а). `decodeText` recursively URL-decodes up to 5 times to defeat double-encoding. `matchKeywordInUrl` and `isDetectWord` return `Pair<Boolean, String>` where the second value is the matched keyword with 10-char context (for the "why am I blocked" UI). `isValidDNS` (KB-09 / VPN-09 fix) accepts both IPv4 and IPv6 (the original only accepted IPv4). The companion holds `BROWSER_URL_VIEW_IDS` — a Map<packageName, List<viewId>> covering 14 browsers (Chrome, Firefox Fenix + legacy, Firefox Rocket, Brave, Samsung Internet, Spin, Opera GX, Opera, Opera Mini, Tor, Vivaldi, Edge, Google Search Lite, Cast Web Video, FreeAdblockerBrowser), `IN_APP_BROWSER_CLASS_NAMES`, `DEVICE_ADMIN_TEXTS_TO_MATCH`, `HUAWEI_ULTRA_POWER_SAVING_TEXTS` (19 locales), and a per-locale `autoStartXiaomiTextToMatch()` for Xiaomi autostart permission pages.

- **`DefaultKeywordData.kt`** (123 lines) — Loads preset block + whitelist keywords from `assets/preset_block_keywords.json` (15 KB, ~1,189 keywords across en/de/hi/no/fi/ru/pt/… etc.) and `assets/preset_whitelist_keywords.json` (275 bytes — recovery-related URLs only, English). Locale handling: English-only users get only English keywords; other locales get English + their locale concatenated and de-duplicated.

- **`DefaultPresets.kt`** (153 lines) — 4 DNS presets (Cloudflare Family default, OpenDNS FamilyShield, CleanBrowsing Family, AdGuard Family), 4 Stop Me durations (15/30/60/120 min), a small `DefaultWhitelistApps` list (self + system UI + settings + package installer), and a `DeviceBrandIdentifiers` enum for Samsung/Xiaomi/Huawei/Oppo/Vivo/OnePlus/Pixel detection.

- **`BlockerPageViewModel.kt`** (934 lines) — God-class ViewModel managing 50+ switches, VPN permission flow, Device Admin flow, App Lock setup routing, accountability partner modes (Time Delay / Real Friend / Long Sentence), block-screen customization, and 18 navigation events. The `toggleSwitch` method (lines 170-313) is the largest single function — over 140 lines with special-case branches for VPN_SWITCH, TOUCH_ID_SWITCH, DISABLE_FORGOT_PASSWORD_SWITCH, SET_APP_LOCK_SWITCH (on + off), PREVENT_UNINSTALL_SWITCH, and the mutually-exclusive accountability partner modes. Each toggle persists via `switchValues.storeSwitchStatus()` then emits a `ShowToast` and calls `MyAccessibilityService.instance?.refreshBlockingConfig()`.

- **`components/BlockerPageHome.kt`** (934 lines) — Compose screen rendering the settings list, handling VPN/Device-Admin/Accessibility-settings permission launchers, and routing to sub-pages (Keyword Manager, Package Intent Manager, Stop Me, VPN Management, FAQ, Request History, Block Screen Image picker).

- **`components/VpnManagementPage.kt`** (884 lines) — Redesigned VPN page with status header card, three mode-selector cards (Balanced/Strict/Custom DNS), custom DNS provider picker, and advanced settings (VPN whitelist apps, notification message, hide notification content).

### 3.4 Stop Me (focus mode)

`StopMeManager.kt` (342 lines) is the core of Stop Me. It supports two session types: **instant sessions** (`startInstantSession(durationMillis, label)`) which start immediately and schedule an end alarm; and **scheduled sessions** (`startScheduledSession(days, startTimeMillis, durationMillis)`) which use a 7-bit day-of-week bitmask and a time-of-day to compute the next trigger time via `calculateNextTrigger` (lines 189-218). Alarms use `AlarmManager.setExactAndAllowWhileIdle` on pre-S / S+ with `canScheduleExactAlarms()`, falling back to `setAndAllowWhileIdle` when exact alarms are not allowed (Android 12+ SCHEDULE_EXACT_ALARM permission gate). The manager notifies the Accessibility service via `MyAccessibilityService.instance?.setStopMeRunning(true/false)` so the service starts blocking non-whitelisted apps. `StopMeAlarmReceiver` (separate file) handles the alarm broadcast. `checkDueSchedules()` (lines 161-181) is called periodically by `AppDataCheckWorker` — but the worker currently has `TODO Phase 5: due Stop Me scheduled sessions` (line 65 in `AppDataCheckWorker.kt`), meaning scheduled sessions are NOT actually being checked. This is a real bug — see §9. The Stop Me widget (`StopMeWidget.kt`) provides a quick-toggle on the home screen.

### 3.5 Streak tracking

`StreakPageViewModel` and `StreakPage` (separate files in `features/streakPage/`) read/write `streak_dates_table` (a list of dates the user has stayed "clean"). The streak widget (`StreakWidget.kt`) shows today's count with a fire icon (Lottie `streak_fire.json`). Relapse types are enumerated in `RelapseTypeIdentifiers.kt`. The streak page is mostly display-only — the actual relapse event is recorded when the user taps "I relapsed" on the block screen, which inserts a new row into `streak_dates_table` and resets the counter. The `AppDataCheckWorker` has a `TODO Phase 5: streak date rollover` (line 64) — meaning streak day-rollover at midnight is NOT being checked either, which could cause stale streak counts if the user doesn't open the app for several days.

### 3.6 Anti-uninstall (Device Admin, AccessibilityGuard)

Three layers:

1. **Device Admin** (`DeviceAdminUtils.kt` 43 lines + `DeviceAdminManager.kt` 71 lines) — `MyDeviceAdminReceiver` is registered in the manifest (lines 281-295) with `<uses-policies />` empty (so it claims no policies, just exists to require Device Admin removal before uninstall). When the user tries to disable, `onDisableRequested` returns a warning CharSequence. `DeviceAdminManager.requestActive()` launches `ACTION_ADD_DEVICE_ADMIN`. `removeActive()` deactivates for opt-out. The Device Admin XML (`res/xml/device_admin.xml`) is empty `<uses-policies />` — meaning the app claims no admin capabilities, but its presence still forces the user through "Deactivate device admin?" before they can uninstall.

2. **Accessibility Guard** (`AccessibilityGuard.kt`, 132 lines) — A polling watchdog that checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` every 30 seconds (`CHECK_INTERVAL_MS = 30_000L`, line 70) and posts a high-priority notification (`NotificationHelper.showAccessibilityDisabledNotification`) when the service is disabled. Self-heal cannot programmatically re-enable on Android 13+ — only the user can — so this is purely a notification prompt. `AccessibilityPersistUtils.selfHealSafe()` is a no-op placeholder (line 126). Started from `ProtectYourselfApp.onCreate()` step 7 (lines 86-90).

3. **Accessibility-level uninstall detection** (`MyAccessibilityService.isAppInfoPage`, lines 603-644) — When `isPreventUninstallOn` is true, the service detects when the user is on the Settings → App Info page for the protect.yourself package (by class-name patterns like `appinfodashboard`/`installedappdetails`/`appinfoactivity` plus the app name in text) and launches `PornBlockActivity` with the `block_page_default_pu_message`. Also matches against `DEVICE_ADMIN_TEXTS_TO_MATCH` (line 636) to catch the user on the Device Admin deactivation page. The check is guarded to only fire on app-info-style class names (line 625) to avoid locking the user out of unrelated Settings pages.

`ProtectedAppsActivity` (`features/protectedApps/ProtectedAppsActivity.kt`, 16 lines) is a stub — `onCreate` contains only `// TODO Phase 6` (line 14). This means the planned "protected apps list" UI was never implemented.

### 3.7 App Lock (PIN/Password/Pattern/Biometric)

`AppLockManager.kt` (192 lines) stores the password hash in the `switch_status` table under key `app_lock_stored_hash` as `"<salt_hex>:<hash_hex>"`. Hashing is **PBKDF2-HMAC-SHA256 with 100,000 iterations** (line 180), 256-bit key length, 16-byte random salt from `SecureRandom`. The original NopoX used only 10,000 iterations — the rebuild's 100K is a deliberate security upgrade (comment at lines 178-179 references NIST SP 800-132's 600K recommendation, balancing security vs. ~100 ms unlock time). Verification uses a **constant-time comparison** (`constantTimeEquals`, lines 103-110) to prevent timing side-channel attacks. The lock type (OFF/PIN/PASSWORD/PATTERN) is stored separately under `app_lock_type`.

`AppLockScreen.kt` (566 lines) renders PIN pad / password field / pattern grid UIs in Compose. The PIN auto-unlocks at 4 digits using `tryUnlockWithInput(newInput, onUnlocked)` (lines 372, 385) — a deliberate API to avoid the Compose state-update race condition where `tryUnlock()` would read stale state before the new input was committed. The same pattern is used for the pattern grid (line 495). Biometric unlock is auto-launched once via `LaunchedEffect(state.touchIdEnabled, state.lockType)` (lines 236-247) using `BiometricPrompt` with `BIOMETRIC_WEAK` authenticators and `setNegativeButtonText("Use PIN/password instead")`. The lock screen is shown by `MainActivity` whenever `appState == AppState.LOCKED`, including on `onResume()` (lines 140-148) so the user cannot bypass the lock by backgrounding + returning — this was an explicit bug fix (comment lines 130-139).

### 3.8 Backup / Restore

`BackupManager.kt` (631 lines) handles local JSON export/import via SAF (Storage Access Framework). Export reads all 9 DB tables, builds a `BackupEnvelope` with version (`CURRENT_BACKUP_VERSION = 1`), app version, package name, timestamp, table contents, and stats. The JSON is written to the user-picked URI with `"wt"` (truncate-write) mode, falling back to `"w"` if the provider doesn't support `"wt"` (lines 333-350). Import reads JSON, validates schema version (rejects future versions, lines 187-198), parses via Gson, and runs `restoreAllTables` inside a Room transaction (line 208) — if any table insert fails, the entire transaction is rolled back. The restore deletes all existing rows first, then bulk-inserts via `upsertAll` (lines 376-488) — this is a "clean replace" not a merge. The typed result `sealed class BackupResult` distinguishes `StorageError`/`InvalidFormat`/`UnsupportedVersion`/`DatabaseError`/`Cancelled`/`Unknown` (lines 596-617), and `BackupProgress` provides UI feedback.

**Important security note**: backups are NOT encrypted. The exported JSON contains the `app_lock_stored_hash` (salt + PBKDF2 hash) in plaintext — the hash itself is one-way so the password is not exposed, but if the user's email (`real_friend_email`) or any other PII is in the DB, it will appear in the JSON. The restore does not validate the source of the URI, so a malicious actor could craft a backup file that replaces the user's keyword lists or settings (a denial-of-service via restoring an empty backup, or replacing block keywords with a custom whitelist that whitelists porn sites).

### 3.9 Notifications & WorkManager

`NotificationHelper.kt` (132 lines) creates 3 channels on Android O+: `daily_report_channel` (DEFAULT importance), `accessibility_alert_channel` (HIGH importance, with badge), `general_channel` (LOW importance). It exposes `showDailyReportNotification(blockCount, streakDays)` and `showAccessibilityDisabledNotification()` (high-priority, taps to open Accessibility Settings).

Three workers exist:

- **`VpnRestartWorker`** (94 lines) — VPN-05 fix. On Android 12+, a `BroadcastReceiver` cannot call `startForegroundService()` directly (throws `ForegroundServiceStartNotAllowedException` within 5 s of `startForeground()`). The fix is an expedited `OneTimeWorkRequest` with `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback, enqueued from `AppSystemActionReceiverAllTime` after boot. If WorkManager scheduling fails entirely, the worker falls back to a direct `MyVpnService.start(context)` call (line 89) which works on older Android versions.
- **`AppDataCheckWorker`** (96 lines) — Periodic worker (every 24h per docs; actual scheduling is in `WorkerUtils.initAppDataCheckWorker`). Verifies block screen count exists, Stop Me session count exists, and `PORN_BLOCKER_SWITCH` is present. Has `TODO Phase 3+` for re-applying accessibility blocking values, `TODO Phase 5` for streak date rollover, and `TODO Phase 5` for due Stop Me scheduled sessions (lines 63-65) — meaning the worker currently does minimal work and several intended periodic tasks are unimplemented.
- **`DailyReportWorker`** (referenced but not read in this analysis) — sends the daily summary notification.

WorkManager is configured for **on-demand initialisation** (manifest disables `WorkManagerInitializer`, `ProtectYourselfApp.workManagerConfiguration` provides the config with `setMinimumLoggingLevel(Log.DEBUG in debug, Log.INFO in release)`).

### 3.10 Sign-in (Firebase Auth)

`SignInSignUpPage.kt` (60 lines) is a **stub**. It renders "Sign-in unavailable" with text explaining "Firebase is not configured in this build. Sign-in, backup/sync, and accountability partner features are disabled." The `onSignedIn` callback is a no-op default. Firebase Auth, Firestore, Messaging, Crashlytics, and Config are all commented out in `app/build.gradle.kts` (lines 174-179), and the `google.services` plugin is commented out (lines 6-9). The original `MyFirebaseMessagingService` is still declared in the manifest (lines 194-200) but has no implementation file in the rebuild (the build would fail if it didn't exist somewhere — likely a stub or generated file). The `firebase_token` switch key is still defined (`SwitchIdentifier.kt:83`) but never written.

### 3.11 Crash logging

`CrashLogger.kt` (685 lines) is a comprehensive on-device crash + error logging system that replaces Firebase Crashlytics. Each crash entry captures: timestamp, severity (VERBOSE → ASSERT), tag, message, throwable class, full stack trace, cause chain (depth-limited to 20), thread name/id, process ID, device info (manufacturer/model/brand/SDK/build ID/fingerprint/ABI/isEmulator), app info (version/process/installer/targetSdk/minSdk), memory info (total/avail/threshold/lowMemory + runtime max/total/free), disk info (total/avail/free/blocks), last 200 lines of logcat (best-effort, may fail on some devices), 30 most-recent breadcrumbs, and custom extra context. Entries are persisted as individual files (`crash_YYYYMMDD_HHmmss_NNNN.json`) in `<filesDir>/crashlogs/`, with an `crash_index.json` ordering file. A 50-entry cap (`MAX_ENTRIES = 50`) auto-prunes oldest. The `logBreadcrumb` ring buffer (30 entries) lets developers trace what the app was doing before a crash. Exposes `readEntries(limit)`, `readEntry(id)`, `deleteEntry(id)`, `clearAll()`, `exportAllToJson()`, `getRecentCrashesSummary(limit)`. The class is thread-safe via `synchronized(this)` on writes.

`CrashLoggingTree.kt` (separate file) is a Timber `Tree` that pipes all `Timber.e()/w()` calls into the CrashLogger, so existing `Timber` usage throughout the app is captured automatically. `ProtectYourselfApp.installCrashHandler()` (lines 150-172) installs a global `Thread.setDefaultUncaughtExceptionHandler` that routes crashes to CrashLogger with severity FATAL, then delegates to the previous handler so the OS still shows the "App keeps stopping" dialog.

## 4. Critical Implementation Details

### 4.1 How the Accessibility service blocks content

The service registers for `TYPE_VIEW_CLICKED | TYPE_VIEW_FOCUSED | TYPE_VIEW_LONG_CLICKED | TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOW_CONTENT_CHANGED` (`accessibility_setting.xml` + `configureService()` at `MyAccessibilityService.kt:101-106`). Flags include `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | FLAG_REPORT_VIEW_IDS | FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY | FLAG_RETRIEVE_INTERACTIVE_WINDOWS` so the service can read WebView content and address bars.

Event dispatch (`onAccessibilityEvent`, lines 120-149): `WINDOW_STATE_CHANGED` → `handleWindowStateChange` (the heaviest handler, lines 177-263), which checks in order: own package (skip), system UI (skip — would freeze the device), settings-page title blocking, any-title blocking, package+intent blocking, prevent-uninstall (app info page detection), power menu / ultra power saving, blocklist apps, new-install block apps, unsupported browsers (with whitelist), in-app browsers, Stop Me non-whitelisted apps. `WINDOW_CONTENT_CHANGED`/`VIEW_FOCUSED`/`VIEW_CLICKED` → `handleContentChange` (lines 267-311), which scrapes URLs from browser address bars via `extractUrlFromEvent` (Strategy 1: known view IDs from `BROWSER_URL_VIEW_IDS`; Strategy 2: fallback recursive `findUrlInNode` searching for any text starting with `http` or containing `://`, depth-limited to 50 to prevent StackOverflow). For non-browser packages, content text is collected up to depth 3 and matched against the blocklist with a 5,000-char length cap to avoid false-positives on large article bodies.

Block decisions (`launchBlockActivity`, lines 781-840) launch `PornBlockActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | FLAG_ACTIVITY_CLEAR_TOP`, passing the offending package name, a message-resource key, and the matched keyword (KB-19 fix for "why am I blocked" UI). Dual throttling prevents block-screen storms: 500 ms per-package + 300 ms global (lines 994-995). Stale-event detection (`isStaleEvent`, lines 171-175) skips events older than 2,000 ms to prevent matching a URL from a previous page when the system delays delivery (KB-07 fix). The `browserCache` is a `ConcurrentHashMap` because `onAccessibilityEvent` fires on the main thread while `refreshBlockingConfig` runs on `Dispatchers.Default` — a plain `MutableMap` would risk `ConcurrentModificationException` (KB-22 fix).

### 4.2 How the VPN service does DNS hijacking

`MyVpnService.startVpn()` (lines 132-321) builds the TUN interface via `VpnService.Builder()`:

1. **Session + address + MTU**: `.setSession("Protect Yourself")`, `.addAddress("10.0.0.2", 32)`, `.setMtu(1500)`.
2. **DNS servers**: `.addDnsServer(primary)` + `.addDnsServer(secondary)` — the two upstream IPs the user's apps will think they're querying.
3. **Routes**: `.addRoute(primaryDns, 32)` + `.addRoute(secondaryDns, 32)` — ONLY the DNS server IPs are routed into the TUN. This is the critical fix that prevents the original "VPN breaks internet" bug — the original code called `addRoute("0.0.0.0", 0)` which routed the entire IPv4 internet into the TUN, but the service never forwarded non-DNS packets, so all traffic was lost (comment lines 42-65).
4. **DNS hijack routes**: `for (captured in DNS_HIJACK_HOSTS) builder.addRoute(captured.first, captured.second)` — adds /32 host routes for ~30 common public DNS resolvers (Google 8.8.8.8/8.8.4.4, Cloudflare 1.1.1.1/1.0.0.1, Quad9, OpenDNS, AdGuard, CleanBrowsing, Comodo, Verisign, Yandex, DNS.WATCH, Level3 — lines 900-943). Apps that hardcode these IPs (Chrome's "secure DNS", Firefox DoH fallbacks, etc.) have their DNS queries captured into the TUN and rewritten to the user-selected filtered upstream.
5. **Per-app routing**: `builder.addDisallowedApplication(pkg)` for VPN-whitelisted apps + the app itself, so they bypass the TUN entirely.
6. **Establish**: `vpnInterface = builder.establish()` — returns null if the user revoked VPN permission.

The DNS forwarder (`startDnsForwarder`, lines 348-430) reads IP packets from `FileInputStream(pfd.fileDescriptor)`, parses IPv4 + UDP headers (`parseIpPacket`/`parseUdp`, lines 566-586 — IPv4-only, IPv6 routes around VPN), validates that the destination port is 53, extracts the DNS payload, and launches a per-query coroutine on `Dispatchers.IO.limitedParallelism(8)`. Each coroutine: acquires a `protect()`-ed DatagramSocket from the 16-socket `DnsSocketPool` (so the forwarded query goes out through the real network, not back into the TUN — VPN-02 fix preventing FD exhaustion), sends to primary, falls back to secondary on failure, validates the response transaction ID against the request (security fix preventing forged/mismatched responses, lines 472-477), builds the response IP+UDP packet via `buildDnsResponsePacket` (lines 589-643) with **correctly computed IPv4 header checksum** (VPN-03 fix — some MediaTek/Huawei OEM kernels silently drop zero-checksum packets), and writes back to the TUN via `synchronized(outputLock) { output.write(responsePacket) }` to serialise concurrent writes. The forwarder loop runs while `isActive && !isClosed` (line 364), where `isClosed` checks `vpnInterface?.fileDescriptor?.valid()?.not()` (line 669).

`onRevoke` (lines 817-854) handles the system revoking VPN permission — stops the VPN, sets the switch to false, schedules AT MOST ONE self-restart attempt (VPN-04 fix prevents restart cascades), and stops the service.

### 4.3 How keyword matching works

`KeywordMatcher` (143 lines) implements the Aho-Corasick string-matching algorithm. Construction (`init` block, lines 37-71): builds a trie where each node has `children: MutableMap<Char, Node>`, `failure: Node?`, and `output: String?`. Keywords are normalised (trimmed + lowercased if `caseInsensitive`). Failure links are built via BFS: root's children get `failure = root`; for each dequeued node, each child's failure is found by walking the parent's failure chain until a node has a transition on the current character or reaches root (lines 56-69). Output merging (lines 66-68) copies the failure node's output so we don't miss shorter keywords that end at this position.

Matching (`findFirst`, lines 78-99): walks the input character by character. For each char, follows failure links until a transition exists or we reach root. If a transition exists, move to that node; otherwise stay at root. After each transition, check if the current node has an output — if so, return `Match(keyword, start, end)` where `start = i - kw.length + 1` and `end = i + 1`. The result is O(M + matches) regardless of the keyword set size N. `findAll` (lines 105-131) walks the failure chain at each match position to collect all overlapping matches.

Edge cases handled:
- **Homograph attacks**: `BlockerPageUtils.normalizeForMatching` (lines 89-103) strips zero-width chars, applies NFKD normalization, strips combining diacritical marks, decodes IDN punycode, and lowercases — so `p⊕rnhub.com` (U+2295) becomes `p+rnhub.com`, `раrn` (Cyrillic а) normalises differently, and `xn--80akhbyknj4f.com` decodes to `видео.com`. The KB-04 comment at lines 78-87 acknowledges this is not a complete defense — combined with the keyword list including common variants, it raises the bar.
- **Double-encoding**: `decodeText` URL-decodes up to 5 times (lines 60-64) to defeat `%2520porn` → `%20porn` → ` porn`.
- **URL stripping**: `isDetectWord` (for text content) strips URLs via `websiteRegex.replace(normalized, "")` (line 144) before matching, so a keyword like `xxx` doesn't match the URL `https://example.com/xxx-redirect` inside body text.
- **Cache invalidation**: `getOrBuildMatcher` caches by `words.hashCode()` (lines 213-224). If the list changes (add/delete keyword), the hash mismatches and a new matcher is built. `@Volatile` + `synchronized(this)` with double-checked locking for thread safety.
- **Empty input**: `findFirst`/`findAll` return null/empty when no keywords match; `isDetectWord`/`matchKeywordInUrl` short-circuit on `words.isEmpty()`.

### 4.4 How anti-uninstall works

Three layers, in increasing order of strength:

1. **Device Admin** (weakest): `MyDeviceAdminReceiver` is registered with empty `<uses-policies />` in `device_admin.xml`. When active, the user must explicitly deactivate Device Admin before they can uninstall the app — the system shows the "Deactivate device admin?" dialog with `onDisableRequested`'s warning CharSequence ("Disabling device admin will reduce your protection. Are you sure?"). This is the only legal anti-uninstall mechanism on stock Android — apps cannot truly prevent uninstall, only require an extra confirmation step.

2. **Accessibility-level app-info detection** (medium): When `isPreventUninstallOn` is true, `MyAccessibilityService.isAppInfoPage` (lines 603-644) detects when the user navigates to the Settings → App Info page for `protect.yourself` (the page where the Uninstall button lives) by checking the class name for patterns like `appinfodashboard`, `installedappdetails`, `appinfoactivity`, `appinfopage` (line 613-617). The check is **guarded** to only fire when the class name indicates an app-info page (line 625) — without this guard, the method would match any settings page that mentions "Protect Yourself" (accessibility settings, notification settings, etc.) and lock the user out. Also matches `DEVICE_ADMIN_TEXTS_TO_MATCH` (line 636) — phrases like "device admin", "deactivate", and internal view IDs — to catch the user on the Device Admin deactivation page. On match, launches `PornBlockActivity` with `block_page_default_pu_message`.

3. **Accessibility Guard watchdog** (background): `AccessibilityGuard` (132 lines) polls `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` every 30 seconds and posts a high-priority notification when the service is disabled. Cannot programmatically re-enable on Android 13+ (system restriction) — only the user can. This is the "self-heal" layer.

`ProtectedAppsActivity` was intended to let the user mark additional apps as "protected" (perhaps to extend the same protection to companion apps), but it remains a stub with `TODO Phase 6` (line 14).

### 4.5 How the 50+ switches are managed

The switch system is a generic key/value store: `SwitchStatusItemModel(key, value, type)` where `type` is "boolean"/"int"/"long"/"string". The full catalogue of 47 keys lives in `SwitchIdentifier.kt` (lines 12-85) grouped by purpose: content blocking (PORN_BLOCKER_SWITCH, SAFE_SEARCH_SWITCH), social-media blocking (8 keys for Snapchat/Instagram/WhatsApp/YouTube/Telegram), uninstall protection (5 keys), advanced blocking (BLOCK_UNSUPPORTED_BROWSERS, BLOCK_PACKAGE_INTENT, VPN_SWITCH, VPN_CONNECTION_TYPE, VPN_NOTIFICATION_HIDE_SWITCH, BLOCK_NEW_INSTALL_APPS_SWITCH, BLOCK_IN_APP_BROWSERS_SWITCH, SET_APP_LOCK_SWITCH, TOUCH_ID_SWITCH, DISABLE_FORGOT_PASSWORD_SWITCH), accountability partner (ACCOUNTABILITY_PARTNER_TYPE, LONG_SENTENCE_MESSAGE_SET, TIME_DELAY_DURATION_SET, REAL_FRIEND_VISIBLE/EMAIL), block-screen customization (6 keys), VPN customization (3 keys), Stop Me (1 key), and app state (TERMS_APPROVE_STATUS, RATING_GIVEN_STATUS, FIREBASE_TOKEN, LAST_BACKUP_CREATED_TIME, USER_DEVICE_CURRENCY_CODE). Two pseudo-keys (BLOCK_SETTING_TITLE_INPUT, BLOCK_PACKAGE_INTENT_INPUT, lines 77-78) are routing keys for the edit-text-dialog flow, not real switches.

`SwitchStatusValues` (294 lines) wraps the DAO with one suspend getter per key, returning sensible defaults when the key is missing (e.g. `isPornBlockerSwitchOn()` returns `true` by default — line 42 — so porn blocking is ON unless explicitly disabled). Seven `Flow`-based observers (`observePornBlockerSwitch`, `observeSafeSearchSwitch`, `observeVpnSwitch`, `observePreventUninstallSwitch`, `observeAppLockSwitch`, `observeTouchIdSwitch`, `observeAccountabilityPartnerType`, `observeAll`) drive Compose recomposition. Writes go through four typed `storeSwitchStatus(key, value)` overloads (boolean/int/long/string) that always set the correct `type` discriminator. Premium-related getters are stubbed to `false`/`0L` for compatibility with original code paths (lines 226-235). The keys are documented in `SwitchIdentifier.kt:8-9` as "Do NOT remove keys even if the feature is removed — preserves DB schema compatibility for users who restore from original backups."

## 5. Permissions Analysis

The AndroidManifest.xml declares 22 permissions (down from the original's 25 — billing, AD_ID, and INSTALL_REFERRER removed). Categorised by purpose:

**Notifications (3):** `ACCESS_NOTIFICATION_POLICY` (interrupt DND for high-priority alerts), `POST_NOTIFICATIONS` (Android 13+ runtime permission for daily reports + accessibility alerts), `USE_FULL_SCREEN_INTENT` (full-screen block activity on lock screen — declared on `PornBlockActivity` at manifest line 186). All justified by app purpose.

**Foreground services (3):** `FOREGROUND_SERVICE` (general), `FOREGROUND_SERVICE_DATA_SYNC` (WorkManager expedited work), `FOREGROUND_SERVICE_SPECIAL_USE` (VPN service — subtype declared as `"VPN DNS filtering for content blocking"` at manifest line 230, required for Play Store approval of VPN FGS). All justified.

**System access (6):**
- `WRITE_SECURE_SETTINGS` (`tools:ignore="ProtectedPermissions"`, line 20-21) — **PROTECTED permission, not actually grantable to a regular app.** Used by `Settings.Secure.getString` for `ENABLED_ACCESSIBILITY_SERVICES` reads — but reads don't actually require WRITE_SECURE_SETTINGS, only writes do. This declaration is harmless but suggests intended functionality (perhaps programmatic accessibility enablement on rooted devices or via adb) that the code does not currently exercise.
- `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` (lines 22-23) — for Stop Me session start/end alarms. The code in `StopMeManager.scheduleEndAlarm` (lines 230-249) correctly checks `alarmManager.canScheduleExactAlarms()` on Android 12+ and falls back to inexact alarms. `USE_EXACT_ALARM` is the new Android 13+ permission for alarm apps; declaring both is belt-and-suspenders.
- `VIBRATE` — notification vibration.
- `SET_ALARM` — required to use `AlarmManager` directly (declared so the app can set alarms via the `com.android.alarm.permission.SET_ALARM` system permission).

**Network (2):** `INTERNET` (the VPN service needs to forward DNS queries over the real network; the app also needs it for any future cloud features), `ACCESS_NETWORK_STATE` (for connectivity change detection in `AppSystemActionReceiver`). Both justified.

**Boot / startup (1):** `RECEIVE_BOOT_COMPLETED` — to restart VPN + refresh accessibility after reboot. Used by `AppSystemActionReceiverAllTime` (manifest lines 252-265).

**Process management (2):** `KILL_BACKGROUND_PROCESSES` — **suspicious**, not used in any code I read. May be a leftover from the original NopoX intent to kill offending apps (the rebuild uses PornBlockActivity overlay instead). `REORDER_TASKS` — same — declared but no usage found. Both could be removed.

**Overlay (1):** `SYSTEM_ALERT_WINDOW` — declared but not actively used (the app uses `PornBlockActivity` with `FLAG_ACTIVITY_NEW_TASK` instead of a true `WindowManager.addView` overlay). Could be removed.

**Multi-user (2):** `INTERACT_ACROSS_USERS_FULL` + `INTERACT_ACROSS_USERS` — both declared at `protectionLevel="signature"` (lines 42-47), meaning they can ONLY be granted to apps signed with the platform key — i.e. they will never actually be granted to a regular install. These are leftovers from the original NopoX multi-user support and have no effect on a regular install.

**Wake / power (1):** `WAKE_LOCK` — for keeping the CPU awake during Stop Me alarm processing. Justified.

**Biometric (2):** `USE_BIOMETRIC` (Android 10+ biometric prompt), `USE_FINGERPRINT` (deprecated, for older devices). Justified by app lock feature.

**Firebase Cloud Messaging (1):** `com.google.android.c2dm.permission.RECEIVE` — declared but Firebase Messaging is removed from the build. Leftover; should be removed.

**Package visibility (1):** `QUERY_ALL_PACKAGES` (`tools:ignore="QueryAllPackagesPermission"`, lines 60-61) — **flagged by Play Store policy** as a sensitive permission. Used by the app picker (`SelectAppPage`) to list all installed apps for blocklist/whitelist management, and by `isBrowserPackageDetected` which calls `packageManager.queryIntentActivities` to enumerate browser apps. The app has a legitimate need (a content blocker must enumerate all installed browsers to scrape URLs and enforce the "unsupported browsers" block), but the `<queries>` block (lines 64-99) is already comprehensive enough that `QUERY_ALL_PACKAGES` may not be strictly required — Play Store reviewers may push back. The queries block declares intents for MAIN, SENDTO, VIEW, PICK, DIAL, TTS_SERVICE, GET_CONTENT, IMAGE_CAPTURE, and CustomTabsService — this gives the app visibility into a wide range of apps without needing the all-packages permission.

**Custom signature permission (1):** `protect.yourself.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (lines 102-105) — Android 13+ best practice for `registerReceiver` with `RECEIVER_NOT_EXPORTED`. Used internally; not granted to other apps.

**Summary:** Most permissions are justified by the app's purpose. The suspicious/leftover ones to remove: `KILL_BACKGROUND_PROCESSES`, `REORDER_TASKS`, `SYSTEM_ALERT_WINDOW`, `com.google.android.c2dm.permission.RECEIVE`, `INTERACT_ACROSS_USERS_FULL`, `INTERACT_ACROSS_USERS` (signature-level, no effect anyway), and possibly `WRITE_SECURE_SETTINGS` (unused). `QUERY_ALL_PACKAGES` is the one most likely to attract Play Store review attention.

## 6. Dependencies Audit

From `gradle/libs.versions.toml` and `app/build.gradle.kts`. Active dependencies:

**AndroidX Core (5):**
- `androidx.core:core-ktx` 1.13.1 — Kotlin extensions for Android framework. **Outdated**: 1.15.0 is current as of late 2024. Minor.
- `androidx.appcompat:appcompat` 1.7.0 — Backward-compatible Activity/Fragment. Current.
- `androidx.activity:activity-compose` 1.9.3 — `ComponentActivity` + `setContent`. Current.
- `androidx.core:core-splashscreen` 1.0.1 — Splash screen API. Current.
- `com.google.android.material:material` 1.12.0 — Material Design components (for non-Compose parts). Current.

**Lifecycle (6):** `lifecycle-runtime-ktx`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-ktx`, `lifecycle-process`, `lifecycle-service` — all 2.8.7. Current.

**Navigation (1):** `androidx.navigation:navigation-compose` 2.8.4. Current.

**Compose (BOM-managed, 2024.10.01):** ui, ui-graphics, ui-tooling-preview, foundation, material3, material-icons-extended, runtime, runtime-livedata. All current via BOM.

**Room (3):** `room-runtime`, `room-ktx` 2.6.1 + `room-compiler` (kapt). **Outdated**: 2.7.0 is current; also still using `kapt` instead of KSP — `ksp` is declared in the version catalog (line 5) but not applied. Migration to KSP would speed up builds significantly. Schema export is enabled (`room.schemaLocation`, `room.incremental`, `room.expandProjection`).

**WorkManager (1):** `work-runtime-ktx` 2.10.0. Current.

**Biometric (1):** `androidx.biometric:biometric` 1.1.0. Current (no newer release).

**Other AndroidX (2):** `documentfile` 1.0.1 (for SAF tree URIs in backup), `fragment-ktx` 1.8.5 (for `FragmentActivity` to support `BiometricPrompt`). Both current.

**Coroutines (2):** `kotlinx-coroutines-core`, `kotlinx-coroutines-android` 1.9.0. Current.

**UI libraries (1):** `lottie-compose` 6.5.2 — for the streak fire / loading / reminder animations. Current.

**Utility (3):** `timber` 5.0.1 (logging — current, but the library is in maintenance mode and `Timber.DebugTree` is always planted even in release builds in this codebase, which is a code-quality issue — see §8), `joda-time` 2.13.0 (date/time — current; used for streak date calculations), `gson` 2.11.0 (JSON — current; used for backup serialisation + keyword preset loading).

**Core library desugaring (1):** `com.android.tools:desugar_jdk_libs:2.1.2` — for Java 8+ time API on minSdk 26. Current.

**Testing (10):** junit 4.13.2, mockk 1.13.13, turbine 1.2.0, robolectric 4.13, kotlinx-coroutines-test 1.9.0, room-testing 2.6.1, arch-core-testing 2.2.0, truth 1.4.4, work-testing 2.10.0, compose-ui-test-junit4 (BOM-managed). All current.

**Removed in rebuild (commented out in build.gradle.kts):** Firebase BOM 33.5.1 + Auth + Firestore + Messaging + Config + Crashlytics (lines 174-179), `kotlinx-coroutines-play-services` (line 184), image-cropper 4.6.0 (line 188), SimpleRatingBar (line 189), Glance appwidget + material3 (lines 190-191), Splitties (line 195), browser 1.8.0 (line 161), viewpager2, recyclerview, preference (lines 166-168).

**Plugin audit (build.gradle.kts + libs.versions.toml):** `android.application` 8.7.2, `kotlin.android` 2.0.21, `compose.compiler` 2.0.21, `kotlin.kapt` 2.0.21. **`google.services` plugin** is declared in `libs.versions.toml` (4.4.2) and applied-false at the project-level `build.gradle.kts:6` but NOT applied at the app level (commented out). **`firebase.crashlytics` plugin** 3.0.2 — same, applied-false at project level, not used at app level. **`ksp` 2.0.21-1.0.27** is declared in the catalog but never applied — Room could migrate from `kapt` to `ksp` for faster builds.

**Risk assessment:** No obviously risky dependencies. No JitPack-only libraries (Splitties was removed for that reason — comment at line 195). No beta/alpha dependencies. The main concerns are: (1) Timber always-on in release, (2) Room still on kapt, (3) a few libraries a minor version behind, and (4) Gson is generally fine but has known issues with deserialisation vulnerabilities (CVE-2022-25647) — the version used (2.11.0) is patched, but Gson is increasingly being replaced by kotlinx.serialization in modern Android.

## 7. Security Considerations

### 7.1 Hardcoded secrets / keys / tokens

A source-wide grep for `api_key|secret_key|secret=|password\s*=\s*"|token\s*=\s*"|AIza[0-9A-Za-z_\-]{35}|firebase.?config|BEGIN PRIVATE KEY` found only legitimate uses: `javax.crypto.SecretKeyFactory` (PBKDF2 hashing in `AppLockManager.kt:9,160` — framework API, not a secret), and the `FIREBASE_TOKEN` switch key string (a key NAME, not a token value). **No hardcoded API keys, secrets, or tokens were found.** This is partly because Firebase was entirely stripped from the build — there is no `google-services.json` in the repo (confirmed via `ls app/google-services.json` → not found), so there are no Firebase API keys / project IDs to leak. The SafeSearch host map, DNS hijack host list, browser view-ID list, and keyword preset JSON are all public data (not secrets).

### 7.2 Firebase config exposure

There is no `google-services.json` in the repository (checked at `app/google-services.json` — not present). The `google.services` plugin is commented out in `app/build.gradle.kts:6-9` with an explicit explanation: "generates FirebaseInitProvider config that auto-initializes Firebase BEFORE Application.onCreate(), crashing the app if the Firebase project is invalid/misconfigured." This is a deliberate removal. The `MyFirebaseMessagingService` is still declared in the manifest (lines 194-200) — this declaration is dead and the class likely doesn't exist (or is a stub); if the class is missing, the build would fail. **Recommendation**: remove the dead `<service>` declaration from the manifest.

### 7.3 VPN traffic interception risks

The VPN service **only intercepts DNS** — it does NOT route other traffic through the TUN. This is verified by the source: `addRoute(firstDns, 32)` + `addRoute(secondDns, 32)` + `addRoute(captured, 32)` for DNS_HIJACK_HOSTS (lines 231-246), with NO `addRoute("0.0.0.0", 0)`. All non-DNS traffic bypasses the VPN via `addDisallowedApplication` for whitelisted apps + self. This means the app does NOT have visibility into the user's browsing traffic — it can only see DNS queries, which is the minimum required for content filtering. **Risk**: the DNS forwarder validates transaction IDs (lines 472-477) to prevent forged-response injection, but it does NOT implement DNS-over-HTTPS or DNS-over-TLS upstream — queries go out as plain UDP to the user-selected upstream (Cloudflare/AdGuard/OpenDNS), which means an on-network attacker could still observe the user's DNS queries (and the upstream could log them). The DNS hijack host list is comprehensive (~30 common public resolvers) but cannot catch every DoH endpoint a determined user might use. The `MAX_DNS_RESPONSE_SIZE = 4096` cap (line 883) and FD-exhaustion protection (DnsSocketPool with 16-socket cap) are good defensive measures.

### 7.4 Accessibility service abuse risks

The accessibility service has full read access to all window content on the device — every URL the user visits, every text field they type into, every button label. This is inherently high-risk: a malicious actor who compromised this app could exfiltrate passwords, banking details, messages, and anything else rendered on screen. The app's stated posture is that "Accessibility data is processed locally and never sent to servers" (`MainActivity.kt:265` onboarding text) — and indeed the source shows no network calls from the service (no OkHttp, no Retrofit, no Volley, no Firebase, no analytics). All matching is done in-process against the local Room DB.

However, the service is configured with `flagRequestEnhancedWebAccessibility` + `flagRetrieveInteractiveWindows` + `canRetrieveWindowContent="true"` (`accessibility_setting.xml` lines 9-10) — this gives it access to WebView content, which is necessary to scrape URLs from in-app browsers but is also the highest-risk configuration. The service also declares `android:isAccessibilityTool="true"` on the `<application>` tag (manifest line 117), which from Android 13+ is required for accessibility services that are not "real" assistive tools and which adds a system warning to the user. The block-screen throttle (500 ms per-package + 300 ms global) prevents the service from spamming the user with overlays. The `isAppInfoPage` anti-uninstall check is correctly guarded to only fire on app-info-style class names (line 625) to avoid locking the user out of unrelated settings pages. The `isAnyTitleBlocked` check explicitly does NOT match against class names (KB-09 fix, lines 542-566) because class names are implementation details that change between app versions and would cause false positives.

### 7.5 Device Admin abuse risks

The Device Admin receiver is registered with **empty `<uses-policies />`** (`res/xml/device_admin.xml`) — meaning the app claims no admin capabilities (no `WipeData`, no `ResetPassword`, no `DisableCamera`, etc.). The only effect of being a Device Admin is that the user must explicitly deactivate it before uninstalling the app. This is the minimum-impact use of Device Admin and is the only legal anti-uninstall mechanism on stock Android. `MyDeviceAdminReceiver.onDisableRequested` returns a warning CharSequence but does not block deactivation. The receiver listens for `DEVICE_ADMIN_ENABLED`, `DEVICE_ADMIN_DISABLE_REQUESTED`, `DEVICE_ADMIN_DISABLED`, `USER_ADDED`, `USER_SWITCHED` (manifest lines 289-293) — the user-added/switched handlers are inherited from the original NopoX multi-user support and may be vestigial. **No abuse risk beyond the inherent "user must deactivate before uninstall" friction.**

### 7.6 Data storage security

The Room database is **NOT encrypted** — no SQLCipher, no `SupportFactory`, no encryption key management. The DB file `protect_yourself_database.db` lives in the app's internal storage (`/data/data/protect.yourself/databases/`), which is protected by the standard Android sandbox (only the app can read it, and only on a non-rooted device). However: (1) on a rooted device, the DB is fully readable; (2) anyone with physical access + adb can dump it; (3) the app's `android:allowBackup="false"` (manifest line 110) correctly disables auto-backup to Google Drive, but the user can still create local JSON backups via the Backup/Restore feature; (4) **backups are NOT encrypted** (`BackupManager.kt` writes plain JSON). The backup contains the `app_lock_stored_hash` (salt + PBKDF2 hash — the password itself is safe due to one-way hashing) and the `real_friend_email` (PII) plus all keyword lists. **Recommendation**: add optional AES encryption to backups with a user-supplied passphrase.

### 7.7 Network security

The manifest does NOT declare `android:usesCleartextTraffic="true"` (default is `false` on API 28+, which is the app's minSdk). A grep for `http://` in the source found no production cleartext URLs — only the browser-detection intent filter (`http://www.google.com` in `MyAccessibilityService.kt:713` for `queryIntentActivities`, which is intent-filter inspection, not an actual HTTP request) and the SafeSearch host map (which only rewrites `https://` URLs). **No cleartext traffic risk.** There is no certificate pinning (the app makes no direct HTTPS requests of its own — it has no cloud backend), so pinning is moot. The VPN forwarder uses plain UDP DNS, which is inherently observable by on-network attackers but cannot be TLS-encrypted without DoH/DoT upstream support — a known limitation.

## 8. Code Quality Issues

### 8.1 God classes / large methods

Several files are far larger than is maintainable:
- `BlockerPageHome.kt` — 934 lines, contains the entire blocker home screen + sub-page routing + 4 permission launchers + edit/number dialogs.
- `BlockerPageViewModel.kt` — 934 lines, 18 navigation events, `toggleSwitch()` is a 140-line method with 6 special-case branches.
- `VpnManagementPage.kt` — 884 lines.
- `MyAccessibilityService.kt` — 1,027 lines, would benefit from splitting handlers into separate strategy classes.
- `MyVpnService.kt` — 989 lines, the DNS forwarder + packet parsing + notification + companion could be split into 3-4 files.
- `BlockerPageUtils.kt` — 655 lines, the companion object alone has 250+ lines of static data (browser view IDs, SafeSearch host map, locale texts).
- `CrashLogger.kt` — 685 lines, would benefit from splitting persistence / context-capture / data-classes into separate files.

### 8.2 Missing error handling

Most I/O has try/catch with Timber logging, but several places swallow exceptions silently:
- `MainActivity.kt:320` — `} catch (_: Throwable) {}` when opening accessibility settings.
- `MainActivity.kt:243-249` — multiple empty catches in the onboarding onClick handlers.
- `AppLockScreen.kt:245, 304` — `catch (_: Throwable) {}` for biometric prompt failures.
- `AccessibilityGuard.selfHeal` (line 107) — catches and logs but the user gets no feedback if the notification fails to post.
- `MyAccessibilityService.kt:632` — `catch (_: Throwable) {}` when getting the app name string for app-info-page detection.
- Many DAO calls in `SwitchStatusValues` default silently (e.g. `dao.get(...)?.asBoolean() ?: true`) — convenient but masks DB corruption.

### 8.3 Race conditions / concurrency issues

The codebase is generally careful about concurrency, but several patterns are fragile:
- `MyAccessibilityService` mutable cached fields (`cachedBlockKeywords`, `cachedBlockApps`, `isPornBlockerOn`, etc.) are read on the main thread (from `onAccessibilityEvent`) and written from `Dispatchers.Default` (from `refreshBlockingConfig`'s `serviceScope.launch`). They are NOT `@Volatile` and NOT in atomic refs. In practice this is probably fine because Kotlin's field reads/writes are atomic for references, but the visibility guarantee is not strict — the service could briefly see stale config after a refresh. The `browserCache` was correctly upgraded to `ConcurrentHashMap` (KB-22 fix) — but the same problem exists for the other cached fields.
- `BlockerPageUtils.cachedMatcherEntry` uses `@Volatile` + double-checked locking correctly (lines 210-224).
- `MyVpnService.startVpn()` uses `isStarting` flag (line 76) without synchronisation — concurrent `startVpn` calls from different threads could race. The flag is read at line 137 without volatile semantics.
- `MyVpnService.vpnState` setter calls `refreshNotification()` which launches a coroutine — if the setter is called rapidly, multiple notification-refresh coroutines could race. Probably harmless (last-write-wins) but wasteful.
- `CrashLogger.breadcrumbBuffer` access is correctly `synchronized` (line 177, 494).
- `AppSystemActionReceiverAllTime.scope` is a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` created per-receiver-instance — since BroadcastReceiver objects are created and destroyed per-broadcast, this leaks the scope. The scope is never cancelled. **Real leak** — see §9.

### 8.4 Memory leaks

- `AppSystemActionReceiverAllTime` (line 29): creates `private val scope = CoroutineScope(...)` as an instance field. BroadcastReceiver instances are short-lived (one per `onReceive`), so this scope is leaked on every broadcast. The scope's `SupervisorJob` is never cancelled. **Real leak.** Fix: use `goAsync()` + a `GlobalScope`-equivalent (or a shared application scope from `AppContainer.applicationScope`).
- `MyAccessibilityService` and `MyVpnService` hold `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.X)` — these are cancelled in `onDestroy` (`MyVpnService` line 810; `MyAccessibilityService` does NOT cancel its scope in `onDestroy`, only sets `instance = null` at line 158 — **potential leak** if the service is destroyed while coroutines are in flight).
- `AccessibilityGuard` holds a `Handler(Looper.getMainLooper())` and a `context: Context?` — the context is set to `context.applicationContext` (line 42), which is safe, and the handler is on the main looper, so no leak there.
- `AppLockManager` and other singletons hold `appContext` (application context), which is safe.
- The `CrashLogger` holds `context.applicationContext`, safe.

### 8.5 Resource cleanup issues

- `MyAccessibilityService.onDestroy` (lines 155-161) does NOT cancel `serviceScope` — any in-flight `refreshBlockingConfig` coroutine will continue running, and the scope's SupervisorJob will leak until GC. Should add `serviceScope.cancel()`.
- `MyVpnService.onDestroy` (lines 807-815) correctly calls `stopVpn()` (which cancels `forwarderJob` + `restartJob` + closes `vpnInterface`) and `serviceScope.cancel()`. Good.
- `MyVpnService.DnsSocketPool.close()` (lines 540-547) closes all pooled sockets and is called in the forwarder's `finally` block (line 426). Good.
- `AppDatabase` instance is a singleton that lives for the app lifetime — Room handles this correctly; no explicit close needed.
- `FileInputStream`/`FileOutputStream` for the TUN (lines 357-358) are NOT explicitly closed — they're tied to `vpnInterface.fileDescriptor`, which is closed in `stopVpn()`. This is correct but fragile; explicit `.close()` calls in `stopVpn` would be safer.

### 8.6 Hardcoded strings vs resources

Many user-facing strings are hardcoded in Kotlin instead of using string resources:
- `MainActivity.kt` — "Welcome to Protect Yourself", "A free, open-source app blocker…", "Key Features:", "Terms & Privacy", "I have read and agree…", "Accept & Open Accessibility Settings", etc. (lines 224-341). These should be in `strings.xml`.
- `BlockerPageViewModel.kt` — toast messages like "App lock disabled", "Time Delay protective mode enabled", "Real Friend enabled — enter partner's email" (lines 219, 242, 263).
- `AppLockScreen.kt` — "Enter your pin/password/pattern", "Use Biometric", "Forgot password?", "Unlock" (lines 267, 311, 325, 447).
- `DeviceAdminUtils.kt:34` — "Disabling device admin will reduce your protection. Are you sure?" — should be localised.
- `NotificationHelper.kt:122` — "Protect Yourself: Blocking disabled!", "Tap to re-enable accessibility service".
- `DefaultKeywordData.kt:93, 106` — toast messages.
- `StopMeManager.kt:65, 137` — toast messages.
- `CrashLogger.kt` — many log messages (acceptable, these are diagnostic).
- `AboutPage.kt` — entire page is hardcoded English.

The `lint` config disables `MissingTranslation` and `ExtraTranslation` (build.gradle.kts:114), so this won't fail the build, but it means the app is effectively English-only for large portions of the UI. The original NopoX supported 15+ languages.

### 8.7 Test coverage gaps

Test files (`app/src/test/java/protect/yourself/`):
- `BuildConfigSmokeTest.kt` — verifies BuildConfig fields.
- `database/AllDaosTest.kt` + `database/switchStatus/SwitchStatusDaoTest.kt` — Room DAO tests using Robolectric.
- `features/blockerPage/identifiers/IdentifiersTest.kt` — enum/identifier tests.
- `features/blockerPage/service/MyAccessibilityServiceTest.kt` — accessibility service tests.
- `features/blockerPage/utils/BlockerPageUtilsTest.kt` — keyword matching, URL validation, SafeSearch URL building.
- `features/blockerPage/utils/PresetDataTest.kt` — preset DNS / Stop Me durations.
- `features/blockerPage/utils/StopMeManagerTest.kt` — Stop Me scheduling.
- `features/streakPage/identifiers/StreakIdentifiersTest.kt` — enum tests.

`app/src/androidTest/java/protect/yourself/AppLaunchSmokeTest.kt` — single instrumented smoke test.

**Coverage gaps:**
- **No tests for `MyVpnService`** — the most complex and security-critical class. The DNS forwarder, packet parsing, checksum computation, socket pool, and `onRevoke` self-restart logic are all untested. This is a significant gap.
- **No tests for `BlockerPageViewModel`** — the 934-line god-class with 6 special-case toggle branches.
- **No tests for `AppLockManager`** — PBKDF2 hashing + constant-time comparison are security-critical and should have tests verifying wrong-password rejection, salt uniqueness, and timing.
- **No tests for `BackupManager`** — the transactional restore, schema validation, and error-type discrimination.
- **No tests for `CrashLogger`** — file rotation, breadcrumb buffer, index management.
- **No tests for `AccessibilityGuard`** — polling watchdog, self-heal notification.
- **No UI tests** for any Compose screen (the `compose-ui-test-junit4` dependency is declared but no `*Test.kt` uses it).
- **No instrumented tests** for the accessibility service actually blocking content (would require a real device or emulator with accessibility enabled).

## 9. Potential Bugs

### 9.1 Stop Me scheduled sessions never fire (CONFIRMED BUG)

`AppDataCheckWorker.kt:63-65` has `TODO Phase 5: due Stop Me scheduled sessions`. `StopMeManager.checkDueSchedules()` exists and is well-tested, but it is **never called** from any worker. This means scheduled Stop Me sessions (the recurring weekly schedules) will never start automatically — only the alarm-based `StopMeAlarmReceiver` will fire them, and the alarms are scheduled correctly in `startScheduledSession` (line 108) via `scheduleStartAlarm`. So if alarms survive device reboot, scheduled sessions will fire — but `AlarmManager` alarms are cleared on reboot, and `AppSystemActionReceiverAllTime` does NOT re-schedule Stop Me alarms after boot (it only refreshes accessibility config and restarts VPN). **Result**: after a reboot, all scheduled Stop Me sessions are silently cancelled until the user opens the app and the next `calculateNextTrigger` runs. The intent was clearly for `AppDataCheckWorker` to call `checkDueSchedules()` periodically, but it doesn't.

### 9.2 Streak date rollover never runs (CONFIRMED BUG)

`AppDataCheckWorker.kt:64` has `TODO Phase 5: streak date rollover`. The streak counter is supposed to roll over at midnight (increment by 1 day if the user stayed clean since yesterday). Without a worker doing this, the streak count shown to the user will be stale if they don't open the app for several days. Opening the app likely triggers a rollover check somewhere (in `StreakPageViewModel`), but if the user has the widget on their home screen and never opens the app, the widget will show yesterday's streak indefinitely.

### 9.3 `MyAccessibilityService` cached fields not `@Volatile` (LIKELY BUG)

Fields like `cachedBlockKeywords`, `cachedBlockApps`, `isPornBlockerOn` (lines 46-69) are read on the main thread from `onAccessibilityEvent` and written from `serviceScope.launch` (Dispatchers.Default) in `loadAllConfig`. Without `@Volatile`, the JVM memory model does not guarantee that the main thread ever sees the updated values — the write may stay in the worker thread's CPU cache indefinitely. In practice, on Android's ART VM, this usually works because ART has stronger memory visibility than the spec requires, but it's a latent bug. The `browserCache` was correctly upgraded to `ConcurrentHashMap` (KB-22 fix), but the other cached fields were not.

### 9.4 `MyVpnService.isStarting` flag not `@Volatile` (LIKELY BUG)

`isStarting` (line 76) guards against concurrent `startVpn()` calls. It's read at line 137 (`if (isStarting)`) and set at line 141 / cleared at line 276. Without `@Volatile`, a concurrent call from a different thread could see stale `false` and proceed to start a second VPN interface. The `ACTION_RESTART` path (lines 115-125) launches a coroutine on `serviceScope` that calls `startVpn()` after a 300 ms delay — if `ACTION_START` arrives in that window, both could proceed.

### 9.5 `MyAccessibilityService.onDestroy` does not cancel `serviceScope` (LEAK)

Lines 155-161 set `instance = null` and show a toast, but do NOT call `serviceScope.cancel()`. Any in-flight `refreshBlockingConfig` or `refreshKeywordList` coroutine will continue running after the service is destroyed, leaking the SupervisorJob. The fix is one line: `serviceScope.cancel()` in `onDestroy`.

### 9.6 `AppSystemActionReceiverAllTime` leaks its scope (LEAK)

Line 29: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`. BroadcastReceiver instances are created per-broadcast and GC'd after `onReceive` returns (or after `pendingResult.finish()` for `goAsync()`). The scope is never cancelled. Every boot/screen-on/user-present broadcast leaks a scope. Fix: use `AppContainer.applicationScope` or `goAsync()` + a coroutine that completes before `pendingResult.finish()`.

### 9.7 `MyVpnService.onRevoke` self-restart races with `stopVpn` (POSSIBLE BUG)

`onRevoke` (lines 817-854) calls `stopVpn()` which cancels `restartJob` (line 686), then schedules a new `restartJob` (line 838). But `stopVpn` also sets `vpnState = VpnState.IDLE` (line 693), which triggers the `vpnState` setter (line 96-102) to call `refreshNotification()` — `refreshNotification` launches yet another coroutine on `serviceScope`. If `stopVpn` and the new `restartJob` race, the restart could call `startVpn()` while the old `vpnInterface` is still being closed. The 300 ms delay in `ACTION_RESTART` (line 122) mitigates this for restart, but `onRevoke` uses a 2000 ms delay (line 844) which should be enough — but the delay is inside `serviceScope.launch`, and if `serviceScope` is cancelled (which it isn't in `onRevoke`), the restart won't fire.

### 9.8 `BlockerPageUtils.getOrBuildMatcher` cache key collision (THEORETICAL)

The matcher cache is keyed by `words.hashCode()` (line 214). `List<String>.hashCode()` is well-defined but not collision-resistant — two different keyword lists could have the same hashCode, causing the cache to return a stale matcher that doesn't contain the new keywords. The probability is astronomically low for realistic keyword lists, but it's a theoretical correctness issue. A more robust key would be the list's size + a content hash, or just always rebuild (the build is fast — O(sum of keyword lengths)).

### 9.9 `MyAccessibilityService.isAnyTitleBlocked` ignores `className` but signature keeps it (CODE SMELL)

KB-09 fix (lines 542-566) explicitly does NOT match against `className` because class names are implementation details. But the method signature still accepts `className: String` for backward compatibility. The parameter is unused. This is harmless but confusing — future maintainers may think className is being checked.

### 9.10 `SwitchStatusValues.getAppLockType` reads from `"app_lock_type"` but `getAppLockType` identically-named method is dead (DEAD CODE)

`SwitchStatusValues.getAppLockType()` (lines 215-222) reads the `app_lock_type` key and returns an `AppLockTypeIdentifiers` enum. But `AppLockManager.getLockType()` (lines 29-32) reads the same key and returns an `AppLockType` enum (different enum!). Both enums exist; the `AppLockTypeIdentifiers` one in `features/blockerPage/identifiers/` appears unused in production code. Dead code that could confuse maintainers.

### 9.11 `MyVpnService.isClosed` uses `fileDescriptor.valid()` (FRAGILE)

Line 669: `private val isClosed: Boolean get() = vpnInterface?.fileDescriptor?.valid()?.not() ?: true`. `ParcelFileDescriptor.FileDescriptor.valid()` returns true if the FD is open, but after `vpnInterface.close()`, the FD is closed and `valid()` returns false. This should work, but it's fragile — if `vpnInterface` is set but the FD has been invalidated by the OS (e.g. on VPN revoke), `valid()` may still return true momentarily. A simple `@Volatile var isStopped = false` flag set in `stopVpn()` would be more reliable.

### 9.12 `KeyboardOptions` biometric prompt `BIOMETRIC_WEAK` allows class-2 biometrics (SECURITY WEAKNESS)

`AppLockScreen.launchBiometricPrompt` (line 546) uses `BiometricManager.Authenticators.BIOMETRIC_WEAK`, which allows class-2 biometrics (lower security, can be spoofed with a photo on some devices). For an app whose entire purpose is anti-circumvention, `BIOMETRIC_STRONG` would be more appropriate. The tradeoff is that older devices only have class-2 sensors, so `BIOMETRIC_STRONG` would lock some users out of biometric unlock. Worth documenting the tradeoff explicitly.

### 9.13 `PornBlockActivity` not actually read (NOT ANALYSED)

I did not read `PornBlockActivity.kt` — it's referenced as the block-screen overlay but not in the file list I was asked to read. A potential bug area: if `PornBlockActivity` doesn't finish itself when the user taps Close, the block could persist forever. The launch flags `FLAG_ACTIVITY_CLEAR_TOP` (line 820) suggest the offending app's task is cleared, but the block activity itself must call `finish()`.

## 10. Improvement Opportunities (Prioritized)

### P0 — Critical (correctness/security)

1. **Cancel `serviceScope` in `MyAccessibilityService.onDestroy`** — one-line fix, prevents coroutine leak when the service is disabled.
2. **Mark `MyAccessibilityService` cached fields as `@Volatile`** — guarantees visibility of config refresh to the main-thread event handler. Fields: `cachedBlockKeywords`, `cachedWhitelistKeywords`, `cachedBlockApps`, `cachedStopMeWhitelist`, `cachedVpnWhitelist`, `cachedNewInstallBlockApps`, `cachedInAppBrowserBlockApps`, `cachedUnsupportedBrowserWhitelist`, `cachedSettingTitles`, `cachedBlockedPackageNames`, `cachedBlockedIntentNames`, and all the `is*On` booleans (lines 46-69).
3. **Mark `MyVpnService.isStarting` and `isRunning` as `@Volatile`** — prevents concurrent-start races.
4. **Implement `AppDataCheckWorker` TODOs** — call `StopMeManager.checkDueSchedules()` and add streak date rollover logic. Currently scheduled Stop Me sessions silently break after reboot and streak counts go stale.
5. **Fix `AppSystemActionReceiverAllTime` scope leak** — use `AppContainer.applicationScope` or `goAsync()` + complete-before-finish pattern instead of creating a per-instance scope.
6. **Encrypt backups** — add optional AES-256 encryption to `BackupManager` with a user-supplied passphrase. The current plain-JSON backup exposes `real_friend_email` and other PII.
7. **Remove dead `MyFirebaseMessagingService` manifest entry** (lines 194-200) — the class doesn't exist (Firebase removed), the manifest declaration is dead code that may cause install issues.

### P1 — High (maintainability/reliability)

8. **Split god classes** — extract `MyAccessibilityService` handlers into strategy classes (`WindowStateHandler`, `ContentChangeHandler`, `UrlExtractor`, `BlockLauncher`); split `BlockerPageViewModel` into per-feature-section VMs (`VpnViewModel`, `AppLockViewModel`, `AccountabilityPartnerViewModel`); split `BlockerPageUtils` companion into a separate `BlockerConstants.kt`.
9. **Migrate Room from `kapt` to `ksp`** — KSP is declared in the version catalog but not applied. KSP is 2-3x faster than kapt for Room and is the recommended path forward.
10. **Add VPN service unit tests** — `MyVpnService` is the most complex and security-critical class and has zero tests. Test: DNS hijack route construction, packet parsing (`parseIpPacket`/`parseUdp`), checksum computation (`computeIpChecksum` — RFC 1071 compliance), `DnsSocketPool` acquire/release/invalidate, transaction-ID validation, `buildDnsResponsePacket` correctness.
11. **Add `AppLockManager` tests** — verify PBKDF2 hash + constant-time comparison: wrong-password rejection, salt uniqueness across calls, hash format `salt:hash` round-trip, empty password handling.
12. **Add `BackupManager` tests** — round-trip export/import, schema-version rejection, transactional rollback on partial-failure, all 9 tables restored correctly.
13. **Stop planting `Timber.DebugTree` in release builds** — `ProtectYourselfApp.initTimberLog()` (line 140) always plants `DebugTree`. Should be `if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())`. The `CrashLoggingTree` can stay in release.
14. **Move hardcoded UI strings to `strings.xml`** — especially in `MainActivity`, `AppLockScreen`, `BlockerPageViewModel` toasts, `NotificationHelper`. The lint config disables `MissingTranslation`, so this won't fail builds, but localisation is currently impossible for ~50% of the UI.
15. **Remove unused permissions** — `KILL_BACKGROUND_PROCESSES`, `REORDER_TASKS`, `SYSTEM_ALERT_WINDOW`, `com.google.android.c2dm.permission.RECEIVE`, `INTERACT_ACROSS_USERS_FULL`, `INTERACT_ACROSS_USERS`, `WRITE_SECURE_SETTINGS` (unused read). Reduces Play Store review friction and attack surface.

### P2 — Medium (polish/UX)

16. **Implement `ProtectedAppsActivity`** — currently a stub with `TODO Phase 6`. Either implement the "protected apps list" feature or remove the activity + manifest entry.
17. **Upgrade PBKDF2 iterations to 600,000** — NIST SP 800-132 recommends ≥600K for PBKDF2-HMAC-SHA256 as of 2023. Current is 100K (AppLockManager.kt:180). The comment acknowledges this — bump it for new hashes, keep 100K for backwards-compat verification.
18. **Upgrade biometric to `BIOMETRIC_STRONG`** when available, with fallback to `BIOMETRIC_WEAK` on older devices.
19. **Add DNS-over-HTTPS upstream support** — currently DNS forwarder uses plain UDP, observable by on-network attackers. Adding DoH (RFC 8484) upstream would encrypt DNS queries end-to-end.
20. **Replace Gson with kotlinx.serialization** — Gson has known deserialisation vulnerabilities (patched in 2.11.0 but the library is in maintenance) and kotlinx.serialization is faster + safer + Kotlin-native.
21. **Add Compose UI tests** — the `compose-ui-test-junit4` dependency is declared but no `*Test.kt` uses it. Critical flows to test: app lock unlock (correct/incorrect PIN), blocker page toggle states, VPN mode selection, backup/restore flow.
22. **Add `@Volatile` to `MyAccessibilityService.instance` and `MyVpnService.instance`** — both are already `@Volatile` (good), but the pattern should be documented as required for all service singletons.
23. **Add network security config** — even though no cleartext traffic exists, an explicit `network_security_config.xml` with `cleartextTrafficPermitted="false"` and certificate pinning for any future cloud endpoints would be defensive.
24. **Implement `ProtectedAppsActivity` or remove it** — currently a 16-line stub.

### P3 — Low (cleanup)

25. **Remove `AboutPage.kt` credit for "Splitties"** — the library was removed (line 195 of build.gradle.kts), but AboutPage.kt:124 still credits it. Misleading.
26. **Remove `nav_premium` string** — `strings.xml:6` still has `<string name="nav_premium">Premium</string>` but Premium tab was replaced by About/Profile. Dead string.
27. **Remove `BROWSER` permission quirks** — the `<queries>` block is comprehensive enough that `QUERY_ALL_PACKAGES` may not be needed. Test by removing it and see if the app picker still works via the intent-based queries.
28. **Document the `safeInit` pattern** — the `safeInit(name) { block }` wrapper in `ProtectYourselfApp` is a good pattern that should be documented and used consistently for all future init steps.
29. **Add a CI lint config** — `lint.abortOnError = false` (build.gradle.kts:112) lets lint issues ship to production. At minimum, enable `abortOnError = true` for release builds.
30. **Version the keyword preset JSON** — `preset_block_keywords.json` has no version field. If the format changes, `DefaultKeywordData.loadJsonMap` will fail silently with a toast. Add a `"version": 1` field and validate it.

---

**End of report.** Total source files read: 30+. Total lines analysed: ~12,000+. Report length: ~6,500 words.
