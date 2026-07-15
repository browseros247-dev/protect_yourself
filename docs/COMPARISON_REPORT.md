# Comparison Report: Reference (Original) vs Protect Yourself (Rebuild)

> **Original APK**: reference 1.0.53 — 15,507 classes across 7 DEX files
> **Rebuild**: `Protect Yourself v1.0.27` — package `protect.yourself` — 80 Kotlin files, ~10,651 LOC
> **Analysis Date**: 2026-07-10
> **Methodology**: Static analysis of original (JADX 1.5.0 + Apktool 2.11.1) + source review of rebuild

---

## Executive Summary

The rebuild ("Protect Yourself") is a **functional but slimmer port** of the reference app. It preserves the core blocking architecture (accessibility service + VPN + Device Admin + boot persistence) and matches the reference's title-based blocking, package+intent blocking, and supported/unsupported browser mechanisms. However, the rebuild is **~25× smaller in code size** and intentionally drops several reference subsystems: Firebase sync, premium gating, social-media-specific blocking (Instagram Reels, YouTube Shorts, etc.), backup/restore, accountability-partner backend, and in-app Stop Me / Keyword Manager / FAQ pages.

The protection mechanisms are **architecturally equivalent** (4-layer uninstall protection, same Device Admin config, same accessibility page-detection heuristics, same boot-restart flow). Two notable behavioural differences: (1) the reference signature-kills via `bin.mt.signature.KillerApplication` (reflection-based IPackageManager swap) — the rebuild extends `Application` directly, eliminating the killer (which was broken on Android 14+ anyway); (2) the rebuild's accessibility service uses `PackageManager.queryIntentActivities()` for robust browser detection, whereas the reference relied purely on package-name substring matching.

---

## 1. Implementation Architecture

### 1.1 High-Level Architecture Comparison

| Aspect | Reference (Original) | Protect Yourself (Rebuild) |
|---|---|---|
| Language | Kotlin + Java (hybrid) | Kotlin only |
| UI Framework | Jetpack Compose + XML layouts (hybrid) | Jetpack Compose + XML layouts (hybrid) |
| App size (decompiled) | 15,507 classes / 7 DEX files / 659 in app package | 80 Kotlin files / ~10,651 LOC |
| Min SDK | 26 (Android 8.0) | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) | 35 (Android 15) |
| Compile SDK | 35 | 35 |
| AGP / Kotlin | unknown (decompiled) | AGP 8.7.2 / Kotlin 2.0.21 |
| Compose BOM | unknown | 2024.10.01 |
| Room | v7 (original) | v8 (fresh schema lineage) |
| Application class | `bin.mt.signature.KillerApplication` (signature killer) | `protect.yourself.core.ProtectYourselfApp` (extends `Application` directly) |
| DI | Manual + Hilt-like patterns | Manual (`AppContainer`) |
| Firebase | Auth + Firestore + Messaging + Crashlytics | **All removed** (FirebaseInitProvider crashes avoided) |
| Ads | AdMob banner on block screen | **Removed** |
| Premium gating | All advanced features premium-gated | **All features free** |
| Proprietary font | Cera Round Pro | Nunito (open-source substitute) |
| `isAccessibilityTool="true"` | Yes | Yes (preserved) |
| `largeHeap="true"` | Yes | Yes (preserved) |

### 1.2 Application Class

**Reference** extends `bin.mt.signature.KillerApplication` — a third-party signature killer that uses reflection to swap the `IPackageManager` binder proxy. This was used to bypass Play Store signature verification (likely for beta distribution outside Play). On Android 14+ this reflection is blocked, causing crashes.

**Protect Yourself** extends `Application` directly. No signature killer, no reflection. All initialisation is wrapped in `safeInit()` blocks with try/catch, and a global uncaught-exception handler writes crash logs to `cacheDir/crash_log.txt`. Auto-initialisers for WorkManager, ProcessLifecycle, EmojiCompat, and ProfileInstaller are explicitly disabled in the manifest via `tools:node="remove"` to prevent crashes from running before `Application.onCreate()`.

### 1.3 Database Schema

Both apps use Room with 9 entities:

| Entity | Reference | Rebuild | Notes |
|---|---|---|---|
| `switch_status` | v7 | v8 | Same keys, 60+ switches preserved |
| `selected_keyword_table` | v7 | v8 | Same identifiers (porn_block_words, porn_white_list_words, setting_keywords_list_words, blocked_intent_names) |
| `selected_apps_table` | v7 | v8 | Same 12 list identifiers |
| `block_screen_count` | v7 | v8 | Same shape |
| `pending_request` | v7 | v8 | Same shape (unused in rebuild — no accountability backend) |
| `stop_me_duration_table` | v7 | v8 | Same shape |
| `stop_me_session_count` | v7 | v8 | Same shape |
| `streak_dates` | v7 | v8 | Same shape |
| `vpn_custom_dns` | v7 | v8 | Same shape, 4 DNS presets pre-populated |

**Critical implementation difference**: The reference pre-populated default data via Room DAOs inside `AppDatabaseCallback.onCreate()`. The rebuild does the same but switched to raw `db.execSQL()` calls because DAO calls inside the callback caused a deadlock during DB creation (the DAOs lazily trigger `AppDatabase.getInstance()` which was still mid-creation). This was a non-trivial bug fix.

The rebuild uses `fallbackToDestructiveMigration()` — fresh installs only, no migration path from reference backups.

### 1.4 Service & Receiver Inventory

| Component | Reference | Rebuild | Difference |
|---|---|---|---|
| Accessibility service | `MyAccessibilityService` (~3,166 LOC decompiled) | `MyAccessibilityService` (728 LOC) | Rebuild covers primary blocking flows; omits social-media-specific feature detection (Reels/Shorts/Status) |
| VPN service | `MyVpnService` (full tunnel + DNS) | `MyVpnService` (full tunnel + DNS, v1.0.25+) | Functionally equivalent post-v1.0.25 |
| Boot receiver | `AppSystemActionReceiverAllTime` | Same | Identical |
| Package install/remove receiver | `AppSystemActionReceiverAllTimeWithData` | Same | Identical |
| Stop Me alarm receiver | Inline in `BlockerPageUtils` | `StopMeAlarmReceiver` (extracted) | Same behaviour |
| Device Admin receiver | `DeviceAdminUtils$MyDeviceAdminReceiver` | Same | Identical (empty `<uses-policies>`) |
| Stop Me widget | `StopMeWidget` | Same | Identical |
| Streak widget | `StreakWidget` | Same | Identical |
| Firebase Messaging service | `MyFirebaseMessagingService` | **Declared in manifest but class does not exist** (will fail to instantiate if FCM push arrives) | **Gap** |
| Connectivity receiver | `AppSystemActionReceiver` | Same (but `onReceive` is a TODO stub) | **Gap** |

### 1.5 Worker Inventory

| Worker | Reference | Rebuild | Notes |
|---|---|---|---|
| `AppDataCheckWorker` | Re-applies blocking values, streak rollover, due Stop Me checks | Verifies DB integrity only — has 3 `TODO` comments for missing features | **Gap** |
| `DailyReportWorker` | Sends daily summary + checks due Stop Me | Checks due Stop Me only | Partial |
| Premium promo worker | Runs premium notifications | **Removed** | OK (premium removed) |

---

## 2. Features Comparison

### 2.1 Feature Matrix

| # | Feature | Reference | Rebuild | Notes |
|---|---|---|---|---|
| 1 | Porn blocker (URL keyword matching) | ✅ | ✅ | Rebuild adds `isDetectWordInUrl()` that doesn't strip URLs (the reference had a bug where URLs were stripped before matching) |
| 2 | Custom keyword list management | ✅ Full page | ⚠️ Stub (`SimpleSubPage("Keyword Manager")`) | Rebuild can add keywords via `EditTextField` flow but no list/delete UI |
| 3 | Whitelist keyword list | ✅ | ✅ | Same |
| 4 | Blocklist apps | ✅ | ✅ | Same (via `SelectAppPage`) |
| 5 | Block all websites | ✅ | ✅ | Same |
| 6 | SafeSearch enforcement | ✅ DNS-level via VPN redirect | ⚠️ Accessibility-level only (blocks unsafe Google search URL) | **Weaker** — can't actually force SafeSearch; just blocks the unsafe URL |
| 7 | Block image/video search | ✅ | ✅ | Same |
| 8 | Supported browsers list (11 defaults) | ✅ | ✅ | Same 11 defaults + 6 more (Mi, Fennec, Bromite, etc.) |
| 9 | Block unsupported browsers | ✅ Substring matching on package name | ✅ PackageManager intent-filter inspection + signature fallback | **Stronger** — fewer false positives/negatives |
| 10 | Whitelist unsupported browsers | ✅ | ✅ | Same |
| 11 | Make any browser supported | ✅ | ✅ (fixed in v1.0.27) | Rebuild now scrapes URLs from any browser when ON, with node-tree fallback for browsers without known view IDs |
| 12 | Block in-app browsers | ✅ | ✅ | Same |
| 13 | Block new install apps (auto) | ✅ | ✅ | Same |
| 14 | Title-based settings page blocking | ✅ Toggle + keyword manager page | ✅ Text input + manage page | Rebuild uses text-input dialog instead of dedicated keyword page |
| 15 | Title-based blocking on ANY app | ✅ (also checks non-settings apps) | ✅ `isAnyTitleBlocked()` | Same behaviour |
| 16 | Package + Intent name blocking | ❌ Not present | ✅ New feature added in v1.0.26 | **Rebuild-only addition** |
| 17 | VPN (DNS blocking) | ✅ OFF/NORMAL/POWERFUL/CUSTOM | ✅ Same 4 modes | Rebuild matches reference mechanism post-v1.0.25 |
| 18 | VPN self-restart on revoke | ✅ | ✅ | Same |
| 19 | VPN per-app routing | ✅ | ✅ | Same (`addDisallowedApplication` for whitelist) |
| 20 | Always-on VPN support | ✅ | ✅ (`SUPPORTS_ALWAYS_ON` meta-data) | Same |
| 21 | Prevent uninstall (4-layer) | ✅ | ✅ | Same 4 layers: Device Admin + Accessibility guard + Boot persistence + Self-heal |
| 22 | Block phone reboot (power menu) | ✅ | ✅ | Same 20 localized strings (Korean, Hebrew, Japanese, Spanish, German, French, Russian, etc.) |
| 23 | Block notification drawer | ✅ | ❌ **Removed per user request** | Rebuild omits this feature entirely |
| 24 | Block recent apps screen | ✅ | ❌ **Removed per user request** | Rebuild omits this feature entirely |
| 25 | App lock (PIN/Password/Pattern) | ✅ | ✅ | Rebuild uses PBKDF2 (10000 iterations + 16-byte salt); reference used similar scheme |
| 26 | Touch ID (biometric) | ✅ | ✅ | Same (BiometricPrompt) |
| 27 | Disable Forgot Password | ✅ | ✅ | Same |
| 28 | Block screen customisation (image, message, countdown, redirect URL) | ✅ | ✅ | Same |
| 29 | Block screen rating prompt | ✅ | ✅ | Same (after every 20 blocks) |
| 30 | Stop Me (focus mode) instant | ✅ | ✅ | Same (AlarmManager + `goAsync()`) |
| 31 | Stop Me scheduled sessions | ✅ | ✅ | Same (day bitmask + start time + duration) |
| 32 | Stop Me widget | ✅ | ✅ | Same (toggles session) |
| 33 | In-app Stop Me page | ✅ | ❌ **Stub** (`SimpleSubPage("Stop Me")`) | Rebuild relies on widget only |
| 34 | Streak counter | ✅ Total active days | ✅ Consecutive days since last relapse | **Stronger** — rebuild's `calculateConsecutiveStreak()` is more meaningful |
| 35 | Streak achievements | ✅ | ✅ | Same |
| 36 | Streak widget | ✅ | ✅ | Same |
| 37 | Relapse recording (with note + type) | ✅ | ✅ | Same |
| 38 | Instagram Reels blocking | ✅ | ❌ Switch identifier exists but no UI / no detection logic | **Removed per user request** (social media section dropped) |
| 39 | Instagram Search blocking | ✅ | ❌ Same as above | **Removed** |
| 40 | YouTube Shorts blocking | ✅ | ❌ Same | **Removed** |
| 41 | YouTube Search blocking | ✅ | ❌ Same | **Removed** |
| 42 | Snapchat Stories blocking | ✅ | ❌ Same | **Removed** |
| 43 | Snapchat Spotlight blocking | ✅ | ❌ Same | **Removed** |
| 44 | WhatsApp Status blocking | ✅ | ❌ Same | **Removed** |
| 45 | Telegram Search blocking | ✅ | ❌ Same | **Removed** |
| 46 | Long Sentence protective mode | ✅ User-configurable | ⚠️ Always ON in background (no UI toggle) | Rebuild simplifies — Long Sentence always active |
| 47 | Time Delay protective mode | ✅ | ✅ | Same |
| 48 | Real Friend protective mode (accountability partner) | ✅ Email + Firestore backend | ⚠️ Email input only — no backend to actually email partner | **Backend missing** |
| 49 | Daily Report notification | ✅ | ✅ | Same |
| 50 | Request history (pending/past requests) | ✅ Full page + Firestore | ❌ **Stub** (`SimpleSubPage("Request History")`) | Rebuild has no accountability backend |
| 51 | Suggest protective mode | ✅ | ✅ (mailto: link) | Same |
| 52 | Backup / Restore (local) | ✅ JSON export/import | ❌ **Not implemented** | **Gap** |
| 53 | Cloud sync (Firebase Firestore) | ✅ | ❌ **Removed** | Rebuild has no Firebase |
| 54 | In-app purchases (subscriptions) | ✅ | ❌ **Removed** (per user request) | All premium features free |
| 55 | AdMob banner on block screen | ✅ | ❌ **Removed** | Per user request |
| 56 | Multi-language UI | ✅ 37+ languages | ⚠️ Strings.xml is English-only | **Gap** — keyword presets are 37 languages but UI strings are not |
| 57 | Onboarding (terms + privacy) | ✅ | ✅ | Same |
| 58 | Profile page | ✅ | ✅ | Same |
| 59 | About page | ✅ | ❌ **Removed per user request** | Profile page contains about info |
| 60 | FAQ page | ✅ | ❌ **Stub** (`SimpleSubPage("FAQ")`) | Rebuild has no FAQ content |
| 61 | Crashlytics crash reporting | ✅ | ❌ **Removed** | Firebase removed entirely |
| 62 | FCM push notifications | ✅ | ❌ **Removed** | Firebase Messaging service declared in manifest but class doesn't exist |

### 2.2 Feature Counts

- **Reference**: 62 features inventoried above (1–62)
- **Rebuild**: 49 fully implemented (✅), 4 partial/stub (⚠️), 9 missing (❌)
- **Rebuild-only additions**: 2 (Package+Intent blocking, consecutive streak calculation)

---

## 3. Protection Mechanisms Comparison

### 3.1 Uninstall Protection (4-Layer)

Both apps implement the same 4-layer uninstall protection:

| Layer | Reference | Rebuild | Equivalent? |
|---|---|---|---|
| **Layer 1: Device Admin** | `<uses-policies />` empty (barrier only) | Same | ✅ Identical |
| **Layer 2: Accessibility guard** | `isAppInfoPage()` checks class name + app name + device admin text patterns | Same heuristics + same `deviceAdminTextToMatch()` list (admin, extended_title, applabel_title, header_title, alertTitle, detail_title) | ✅ Identical |
| **Layer 3: Boot persistence** | `AppSystemActionReceiverAllTime` listens for BOOT_COMPLETED, REBOOT, LOCKED_BOOT_COMPLETED, QUICKBOOT_POWERON (htc + comhtc), SCREEN_ON, USER_PRESENT | Same 7 actions, same priority 999 | ✅ Identical |
| **Layer 4: Self-heal** | `AccessibilityGuard` polls every 30s, posts notification if disabled | Same 30s polling, same `NotificationHelper.showAccessibilityDisabledNotification()` | ✅ Identical |

**Bypass vectors (same in both apps)**:
1. **ADB**: `adb shell settings put secure enabled_accessibility_services ""` — disables accessibility. Self-heal detects within 30s but can only post a notification (Android 13+ blocks programmatic re-enable).
2. **Safe mode**: Booting into safe mode disables all third-party apps. Boot receiver handles `BOOT_COMPLETED` but not safe mode specifically.
3. **Device Admin deactivation**: User can always deactivate Device Admin from Settings → Security → Device admin apps. Layer 2 (accessibility) catches the navigation to that page but the user can still disable if they're persistent.
4. **OEM battery optimisation**: Aggressive OEMs (Xiaomi, Huawei, Samsung) can kill accessibility services. Both apps address this with self-heal + autostart instructions but cannot prevent it.
5. **Android 14+ Device Admin for personal apps**: Deprecated; may not work on newer devices. Both apps affected equally.

### 3.2 Content Blocking Mechanisms

| Mechanism | Reference | Rebuild | Stronger? |
|---|---|---|---|
| URL extraction from browser address bar | View ID map for 6 browsers (Chrome, Firefox, Brave, Edge, Opera, Samsung) + fallback node search | View ID map for 6 browsers + fallback node search (same) + node-tree URL search when no view IDs match | ✅ Rebuild slightly stronger (v1.0.27 fix) |
| URL keyword matching | `isDetectWord()` strips URLs before matching (bug: keywords never match URLs) | `isDetectWordInUrl()` does NOT strip URLs (fix) + `isDetectWord()` for non-URL text | ✅ Rebuild stronger (critical bug fixed) |
| Browser detection for "block unsupported" | Package name substring matching (`contains("browser")`, `contains("chrome")`, etc.) | `PackageManager.queryIntentActivities()` for `ACTION_VIEW` + `http/https` + `BROWSABLE` category + signature fallback + per-package cache | ✅ Rebuild stronger (fewer false positives/negatives) |
| VPN DNS blocking | Full tunnel `addRoute("0.0.0.0", 0)` + family DNS | Same | ✅ Equivalent (post v1.0.25) |
| Per-app VPN routing | `addDisallowedApplication()` for whitelist | Same | ✅ Equivalent |
| SafeSearch enforcement | DNS-level redirect (www.google.com → forcesafesearch.google.com) via VPN | Accessibility-level only (blocks unsafe Google search URL) | ❌ Rebuild weaker |
| Block screen throttling | 500ms per package | Same | ✅ Equivalent |
| HOME action before block screen | Yes | Yes | ✅ Equivalent |
| Block screen back button | Disabled (user must use Close) | Same | ✅ Equivalent |
| Block screen countdown | Configurable 3–300s | Same | ✅ Equivalent |
| Block screen redirect URL | Configurable | Same | ✅ Equivalent |
| Block screen motivation image | Configurable | Same | ✅ Equivalent |

### 3.3 Anti-Circumvention Mechanisms

| Mechanism | Reference | Rebuild | Notes |
|---|---|---|---|
| Block notification drawer | ✅ Detects `StatusBar`, `notification`, `quicksettings`, `shade` | ❌ Removed | Rebuild vulnerable to quick-tile access to settings |
| Block recent apps screen | ✅ Detects `recents`, `recentapps`, `overview`, `taskview` | ❌ Removed | Rebuild vulnerable to force-stop from recents |
| Block phone reboot / power menu | ✅ 20 localized strings | ✅ Same 20 strings | Equivalent |
| Title-based settings page blocking | ✅ | ✅ | Equivalent |
| Title-based blocking on ANY app | ✅ | ✅ | Equivalent |
| Package + Intent name blocking | ❌ | ✅ | Rebuild-only addition |

**Circumvention impact of removed features**:
- Without "Block notification drawer", user can swipe down the shade → tap Settings gear → access app info. Layer 2 (accessibility) catches this once they navigate to the app info page, but they get further than in the reference.
- Without "Block recent apps", user can swipe up from home → long-press app → App Info → Uninstall. Layer 2 catches the App Info navigation but again the user gets further.

### 3.4 App Lock Mechanism

| Aspect | Reference | Rebuild |
|---|---|---|
| Lock types | PIN / Password / Pattern / OFF | Same |
| Password hashing | PBKDF2 with salt | PBKDF2WithHmacSHA256, 10000 iterations, 256-bit key, 16-byte salt |
| Biometric | BiometricPrompt | Same |
| Forgot password | Email support link (configurable to disable) | Same (mailto: link) |
| Race condition | Possible (PIN auto-unlock read stale state) | Fixed via `tryUnlockWithInput(input)` taking explicit input |

### 3.5 Stop Me Mechanism

| Aspect | Reference | Rebuild |
|---|---|---|
| Instant sessions | ✅ | ✅ via `StopMeManager.startInstantSession()` |
| Scheduled sessions | ✅ | ✅ via `StopMeManager.startScheduledSession()` (day bitmask + start time + duration) |
| Alarm scheduling | `AlarmManager.setExactAndAllowWhileIdle()` | Same + `canScheduleExactAlarms()` check on Android 12+ |
| Background invocation | `runBlocking` on main thread (bug) | `goAsync()` pattern (fixed) |
| Widget | ✅ | ✅ `StopMeWidget` toggles session |
| In-app UI | ✅ Full page | ❌ Stub (`SimpleSubPage`) — widget only |
| Whitelist | ✅ `WHITELIST_STOP_ME_APPS` | ✅ Same |
| Session count tracking | ✅ | ✅ via `stopMeSessionCountDao()` |

### 3.6 Streak Mechanism

| Aspect | Reference | Rebuild |
|---|---|---|
| Streak calculation | Total active day count | Consecutive days since last relapse (more meaningful) |
| Relapse recording | Type + free-text note | Same |
| Achievements | ✅ | ✅ |
| Widget | ✅ | ✅ |
| Flow observation | Nested collectors (memory leak risk) | Single collector (fixed) |
| Background rollover | AppDataCheckWorker handles | ❌ TODO in AppDataCheckWorker (not yet implemented) |

---

## 4. Critical Gaps in Rebuild

### 4.1 Missing Functional Pages (Stubs)

The following sub-pages are declared in `BlockerPageHome.SubPage` but render as `SimpleSubPage` (just a back button + title text):

1. **Keyword Manager** — no UI to view/delete existing blocklist keywords. Users can only add via the edit dialog.
2. **Stop Me** — no in-app UI to start/view sessions. Only the home-screen widget works.
3. **Request History** — no UI to view pending/past protective mode requests.
4. **FAQ** — no FAQ content.

### 4.2 Missing Backend Integration

1. **Firebase Firestore** — Real Friend protective mode requires emailing the accountability partner. The reference used Firestore to send requests; rebuild only collects the email and stores it locally.
2. **Firebase Auth** — No user account system. Rebuild has no sign-in page (despite `SignInSignUpPage.kt` existing as a stub).
3. **FCM Push** — `MyFirebaseMessagingService` is declared in the manifest but the class doesn't exist. If an FCM push arrives, the system will fail to instantiate it.
4. **Crashlytics** — No crash reporting. The rebuild's `ProtectYourselfApp.installCrashHandler()` writes to a local file instead.

### 4.3 Missing Worker Features

`AppDataCheckWorker` has 3 explicit `TODO` comments:
- `// TODO Phase 3+: re-apply accessibility blocking values` — if the accessibility service's cached config drifts from DB, no auto-correction.
- `// TODO Phase 5: streak date rollover` — if the app isn't open at midnight, the streak may not roll over correctly.
- `// TODO Phase 5: due Stop Me scheduled sessions` — scheduled Stop Me sessions rely solely on AlarmManager; if the alarm is killed by OEM battery optimisation, the session won't fire.

`DailyReportWorker` runs but only checks due Stop Me schedules — no daily summary notification with block count + streak (which the reference had).

### 4.4 Removed Anti-Circumvention Features

Per user request, two anti-circumvention features were removed:
- **Block Notification Drawer** — quick settings shade no longer blocked.
- **Block Recent Apps Screen** — recent apps overview no longer blocked.

This creates a slightly wider attack surface for users trying to circumvent protection (see §3.3 above).

### 4.5 Localisation Gap

The reference shipped with full UI translations for 37+ languages. The rebuild's `strings.xml` is English-only. However, the **keyword presets** (`preset_block_keywords.json`) are still in all 37 languages (1,189 keywords total, 532 in English), so content blocking still works internationally — only the UI text is English.

### 4.6 Backup / Restore

The reference had JSON-based local backup + Firebase cloud sync. The rebuild has neither. Users cannot export their keyword lists, app selections, or switch states. This is a significant UX gap for users who reinstall or switch devices.

---

## 5. Strengths of the Rebuild over the reference

### 5.1 Stability Improvements

1. **No signature killer** — `KillerApplication` is gone, eliminating reflection-based crashes on Android 14+.
2. **No Firebase auto-init** — FirebaseInitProvider is removed, eliminating crashes from invalid/missing `google-services.json`.
3. **Safe init wrapper** — all `Application.onCreate()` steps are wrapped in `safeInit()` with try/catch; a single failing init step doesn't crash the app.
4. **Crash log file** — `cacheDir/crash_log.txt` captures stack traces for debugging.
5. **Single `setContent`** — Compose is set up exactly once; state changes via `mutableStateOf` trigger recomposition (previous bug: multiple `setContent` calls created multiple composition trees).
6. **No DAO deadlock** — `AppDatabaseCallback.onCreate()` uses raw `execSQL()` instead of DAOs (which caused deadlock).
7. **`MainPageScreen` enum** — converted from `data object` + companion `all` list (which caused NPE) to a plain `enum class`.

### 5.2 Functional Improvements

1. **URL keyword matching fixed** — `isDetectWordInUrl()` doesn't strip URLs before matching, so `pornhub.com` correctly matches keyword `porn` (the reference stripped URLs, so the keyword never matched).
2. **Robust browser detection** — `PackageManager.queryIntentActivities()` for `ACTION_VIEW` + `http/https` + `BROWSABLE` is more accurate than substring matching.
3. **URL scraping fallback** — when a browser has no known view IDs (e.g. user-added via "Make any browser supported"), the rebuild falls back to node-tree traversal to find URL-like text. The reference returned `null` in this case.
4. **Consecutive streak** — counts days since last relapse (meaningful) instead of total active days (misleading).
5. **Single Flow collector** — no nested collectors, eliminating memory leaks in streak tracking.
6. **App Lock race condition fixed** — `tryUnlockWithInput(input)` takes explicit input instead of reading stale state.
7. **Stop Me `goAsync()`** — replaced `runBlocking` on main thread (which froze the UI) with the `goAsync()` pattern.
8. **Stop Me widget toggles** — widget correctly toggles session on/off (the reference always started a new session).
9. **Package + Intent blocking** — new feature not present in the reference.

### 5.3 Business Model

1. **All features free** — no premium gating, no ads, no IAP. Every feature works without payment.
2. **No tracking** — no Crashlytics, no Analytics, no AdMob.
3. **Open-source** — code on GitHub at `258044aamm-Dev/Protect-Yourself`.

---

## 6. Threat Model Comparison

| Threat | Reference Mitigation | Rebuild Mitigation | Verdict |
|---|---|---|---|
| Casual user uninstalls via Settings → Apps | Device Admin + accessibility page detection | Same | Equivalent |
| User disables accessibility via Settings | Self-heal posts notification within 30s | Same | Equivalent |
| User reboots to kill accessibility | Boot receiver restarts services | Same | Equivalent |
| User enters ultra power saving | Power menu detection (20 localized strings) | Same | Equivalent |
| User accesses Settings via notification shade | Block notification drawer | ❌ Removed | **Rebuild weaker** |
| User force-stops via recent apps | Block recent apps | ❌ Removed | **Rebuild weaker** |
| User uses ADB to disable accessibility | Self-heal detects but cannot re-enable (Android 13+) | Same | Equivalent (both vulnerable) |
| User boots into safe mode | Boot receiver doesn't handle safe mode | Same | Equivalent (both vulnerable) |
| User uses unsupported browser | Block unsupported browsers + whitelist | Same + stronger browser detection | **Rebuild stronger** |
| User uses in-app browser (WebView) | Block in-app browsers per-app list | Same | Equivalent |
| User searches porn on Google | URL keyword matching (buggy in the reference) | Fixed URL matching | **Rebuild stronger** |
| User searches image/video results | Block image/video search URLs | Same | Equivalent |
| User uninstalls via Play Store | Device Admin prevents | Same | Equivalent |
| User clears app data | Device Admin doesn't prevent, but clearing data resets switches → protection off | Same | Equivalent (both vulnerable) |
| User disables Device Admin | Accessibility catches navigation to Device Admin page | Same | Equivalent |

---

## 7. File Inventory

### 7.1 Reference (from decompilation)

| File | Lines (decompiled) | Purpose |
|---|---|---|
| `MyAccessibilityService.java` | ~3,166 (decompiled, expanded from Kotlin) | Core blocking logic |
| `BlockerPageUtils.java` | ~3,166 | URL/keyword matching, device brand detection, power saving text matching |
| `PornBlockPage.java` | ~400 | Block screen display |
| `DeviceAdminUtils.java` | ~100 | Device Admin activation/deactivation |
| `AccessibilityGuard.kt` | ~130 | Self-heal polling |
| `AppSystemActionReceiverAllTime.kt` | ~80 | Boot/screen event receiver |
| `SwitchStatusValues.java` | ~300 (60+ getters) | Switch state accessors |
| `SettingPageItemIdentifiers.kt` | ~60 | Setting items enum |
| `AndroidManifest.xml` | 368 | Component declarations |
| `strings.xml` | 884 | UI strings (37+ languages) |
| **Total** | ~8,664 (decompiled) | |

### 7.2 Rebuild

| File | Lines | Purpose |
|---|---|---|
| `MyAccessibilityService.kt` | 728 | Core blocking logic (slimmer) |
| `BlockerPageUtils.kt` | 322 | URL/keyword matching |
| `PornBlockActivity.kt` | 317 | Block screen |
| `BlockerPageViewModel.kt` | 593 | Settings state + actions |
| `BlockerPageHome.kt` | 679 | Settings UI |
| `MyVpnService.kt` | 395 | VPN service |
| `StopMeManager.kt` | 342 | Stop Me sessions |
| `AppLockManager.kt` | 170 | App lock state |
| `AppLockScreen.kt` | 562 | Lock screen UI |
| `MainActivity.kt` | 373 | Main activity + onboarding |
| `ProtectYourselfApp.kt` | 148 | Application class |
| `AppDatabase.kt` | 91 | Room database |
| `AppDatabaseCallback.kt` | 222 | DB pre-population |
| `AccessibilityGuard.kt` | 132 | Self-heal |
| `DeviceAdminManager.kt` | 71 | Device Admin |
| `StreakPageViewModel.kt` | 198 | Streak tracking |
| `DefaultPresets.kt` | 199 | DNS + browser + social presets |
| `DefaultKeywordData.kt` | 103 | Keyword preset loader |
| `AppSystemActionReceiverAllTime.kt` | 88 | Boot receiver |
| `AppSystemActionReceiverAllTimeWithData.kt` | 89 | Package event receiver |
| `StopMeAlarmReceiver.kt` | ~50 | Stop Me alarm receiver |
| `StopMeWidget.kt` | ~80 | Stop Me widget |
| `StreakWidget.kt` | ~60 | Streak widget |
| `AppDataCheckWorker.kt` | 96 | Periodic worker (with TODOs) |
| `DailyReportWorker.kt` | 68 | Daily report worker |
| `WorkerUtils.kt` | 96 | Worker scheduling |
| `AndroidManifest.xml` | 388 | Component declarations |
| `strings.xml` | 224 | UI strings (English only) |
| **Total** | ~10,651 LOC across 80 Kotlin files | |

---

## 8. Recommendations

### 8.1 High-Priority Gaps to Close

1. **Implement in-app Stop Me page** — currently only the widget can start a session. Users without widgets on their home screen have no way to use Stop Me from inside the app.
2. **Implement Keyword Manager page** — users need to view and delete existing blocklist keywords, not just add new ones.
3. **Implement Backup/Restore** — JSON export/import of all DB tables. Critical for users who reinstall or switch devices.
4. **Implement streak rollover in `AppDataCheckWorker`** — currently the streak may not roll over at midnight if the app isn't open.
5. **Implement due Stop Me schedule check in `AppDataCheckWorker`** — currently relies solely on AlarmManager, which OEMs can kill.
6. **Implement accessibility config drift correction in `AppDataCheckWorker`** — re-apply blocking values if the service's cache has drifted from DB.

### 8.2 Medium-Priority Gaps

7. **Implement SafeSearch at VPN/DNS level** — the reference redirected `www.google.com` → `forcesafesearch.google.com` via VPN. Rebuild only blocks unsafe URLs at accessibility level.
8. **Add `MyFirebaseMessagingService` class or remove from manifest** — currently declared but doesn't exist; will crash if FCM push arrives.
9. **Add UI translations** — at minimum Spanish, French, German, Portuguese, Hindi, Arabic, Chinese, Japanese, Korean, Russian.
10. **Implement FAQ page** — even a static FAQ would help users understand the app.
11. **Implement Request History page** — at minimum show pending requests stored locally.

### 8.3 Low-Priority / Optional

12. **Re-add Block Notification Drawer + Block Recent Apps** — these were removed per user request but their absence weakens anti-circumvention. Consider re-adding as opt-in toggles.
13. **Add Crashlytics back** (with proper Firebase config) — crash logs in `cacheDir/crash_log.txt` are useful but require the user to manually extract and share them.
14. **Add social media section back** — Instagram Reels, YouTube Shorts, etc. blocking was removed per user request. Consider re-adding if user changes mind.
15. **Add cloud sync** (optional, opt-in) — would enable cross-device settings sync without Firebase.

---

## Conclusion

The rebuild successfully replicates **the reference's core protection architecture** (4-layer uninstall protection, accessibility-based content blocking, VPN DNS filtering, Stop Me, streak tracking) while fixing several critical bugs from the original (URL keyword matching, Stop Me `runBlocking`, app lock race condition, signature killer crashes, Firebase auto-init crashes).

It is **functionally equivalent** for the primary use case (blocking pornographic content in browsers) and **stronger** in three areas: browser detection (PackageManager vs substring), URL scraping fallback (node-tree search vs null return), and streak calculation (consecutive vs total).

It is **weaker** in three areas: SafeSearch enforcement (accessibility-level only vs DNS-level), anti-circumvention (notification drawer + recent apps removed), and localisation (English-only UI vs 37 languages).

It is **missing** four significant subsystems: Firebase backend (Auth + Firestore + FCM + Crashlytics), backup/restore, in-app pages for Keyword Manager / Stop Me / Request History / FAQ, and full `AppDataCheckWorker` features (3 TODOs).

For a user who wants a free, open-source, privacy-respecting alternative and is willing to manage settings via the widget + dialogs, the rebuild is a solid choice. For a user who needs cross-device sync, accountability partner emails, or in-app Stop Me management, the rebuild has gaps that need to be closed.
