# Scheduled App Restrictions — Comprehensive Implementation Plan

**Project**: Protect-Yourself
**Branch analysed**: `main` (HEAD = `a3b8723`, v1.0.61 — includes the 19 VPN bug fixes)
**Plan date**: 2026-07-13
**Mode**: **PLAN ONLY — no code changes**
**Author**: VPN Analysis Bot

---

## 1. Executive Summary

This plan adds a **Scheduled App Restrictions** feature to Protect-Yourself, replacing the existing Streak feature (tab, source code, resources, widget, DB table, and all cross-references). The new feature provides two integrated capabilities:

1. **Scheduled Per-App Internet Blocking** — selected apps lose internet access during scheduled time windows, while remaining launchable and functional offline. Implemented via the existing `MyVpnService` using `addAllowedApplication()` to route only the targeted apps through a black-hole VPN tunnel.
2. **Scheduled App Launch Blocking** — selected apps cannot be opened during scheduled time windows. Implemented via the existing `MyAccessibilityService` `onAccessibilityEvent` handler, mirroring the current `cachedBlockApps` pattern.

A **unified Scheduler Engine** (WorkManager + AlarmManager + boot-receiver re-arm) coordinates both capabilities from a single code path, eliminating duplication.

### Key decisions

| Decision | Rationale |
|----------|-----------|
| Replace Streak tab (not add a 4th tab) | User explicitly requested; keeps the 3-tab UX simple |
| Remove Streak code entirely (no dead code) | User explicitly requested; reduces APK size + maintenance burden |
| DB schema bump v10 → v11 | Add 2 new tables (`scheduled_restrictions`, `restriction_logs`); drop `streak_dates_table` |
| VPN dual-mode (DNS-filter vs per-app-block) | Avoids breaking existing DNS filtering; mode switched by `Builder` config at establish() time |
| Accessibility Service inline check | Reuses existing `cachedBlockApps` pattern; adds `cachedScheduledBlockApps` set |
| WorkManager periodic 15-min + AlarmManager exact for boundaries | Balances battery (WorkManager batches) with precision (AlarmManager fires at exact schedule start/end) |
| No new permissions needed | `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `BIND_VPN_SERVICE`, `BIND_ACCESSIBILITY_SERVICE` all already declared |

### Effort estimate

| Phase | Effort | Priority |
|-------|--------|----------|
| Phase 0: Streak removal | 1 day | P0 (must do first) |
| Phase 1: DB schema + entities + DAOs | 0.5 day | P0 |
| Phase 2: Scheduler Engine | 1.5 days | P0 |
| Phase 3: VPN integration (internet blocking) | 1 day | P0 |
| Phase 4: Accessibility integration (launch blocking) | 0.5 day | P0 |
| Phase 5: UI (tab replacement + schedule editor) | 2 days | P0 |
| Phase 6: Testing + polish | 1 day | P0 |
| **Total (P0)** | **~7.5 days** | |
| Phase 7: Enhancements (focus profiles, strict mode, stats) | 3 days | P1 |
| Phase 8: Backup/restore + import/export | 1 day | P1 |
| **Total (P0 + P1)** | **~11.5 days** | |

---

## 2. Current State Assessment

### 2.1 Architecture overview

The app is a Kotlin + Jetpack Compose Android app (minSdk 26, targetSdk 35) with a Room database (schema v10, 9 entities). The architecture follows a feature-package convention:

```
protect.yourself/
├── core/                          # App, coroutine scope, crash logging
├── database/                      # Room: 9 entities, 9 DAOs, v10
│   ├── core/                      # AppDatabase, AppDatabaseCallback, migrations
│   ├── selectedApps/              # selected_apps_table (multi-category app lists)
│   ├── switchStatus/              # switch_status (key-value store, 60+ keys)
│   ├── vpnCustomDns/             # vpn_custom_dns (DNS presets)
│   ├── streakDates/              # streak_dates_table ← TO BE REMOVED
│   ├── stopMeDuration/           # stop_me_duration_table (scheduling reference)
│   └── ... (4 more)
├── commons/utils/
│   ├── broadcastReceivers/        # Boot, connectivity, package-install, Stop-Me alarms
│   ├── workManager/               # VpnRestartWorker, DailyReportWorker, AppDataCheckWorker
│   └── notificationUtils/
├── features/
│   ├── mainActivityPage/          # MainActivity (3 tabs: Home, Streak, Profile)
│   ├── blockerPage/               # ← LARGEST FEATURE
│   │   ├── service/               # MyVpnService, MyAccessibilityService (2554 lines!)
│   │   ├── components/             # BlockerPageHome, VpnManagementPage, UnifiedBlockingPage
│   │   ├── utils/                  # StopMeManager (scheduling reference!), BlockerPageUtils
│   │   └── identifiers/
│   ├── streakPage/                 # ← TO BE REMOVED (4 files, 942 lines)
│   ├── stopMePage/                 # ← Scheduling reference
│   ├── selectAppPage/              # ← Reusable app picker
│   ├── profilePage/
│   └── ... (5 more)
└── theme/
```

### 2.2 The three tabs (current state)

`MainActivity.kt` renders a `Scaffold` with a `NavigationBar` (bottom bar) containing 3 items, defined in `MainPageScreen.kt`:

| Tab | Route | Icon | Composable | Purpose |
|-----|-------|------|------------|---------|
| Home | "Home" | `Icons.Filled.Shield` | `BlockerPageHome()` | Settings + blocking config |
| Streak | "Streak" | `Icons.Filled.LocalFireDepartment` | `StreakPage()` | Streak tracking ← **REPLACE** |
| Profile | "Profile" | `Icons.Filled.Person` | `ProfilePage()` | Profile + backup/restore + FAQ |

Deep-linking: `MainActivity.EXTRA_OPEN_TAB` string extra switches tabs. Currently fired by `StreakWidget` (which will be removed). The new feature's tab will reuse this mechanism if we add a widget later.

### 2.3 VPN Service (current state)

`MyVpnService.kt` (645 lines, v1.0.61 with all 19 bug fixes applied) runs a **DNS-filtering VPN**:

```kotlin
Builder()
    .setSession(...)
    .addAddress("10.0.2.15", 24)  // + 3 more private addresses
    .addDnsServer(firstDns)         // Cloudflare Family / AdGuard Family / custom
    .addDnsServer(secondDns)
    .setMtu(1500)
    .allowBypass()
    // Per-app routing: VPN_WHITELIST_APPS → addDisallowedApplication (bypass VPN)
    .addDisallowedApplication(whitelistedPkg)
    .addDisallowedApplication(ownPackage)  // self always bypasses
    .establish()
```

**Key observation**: The current VPN routes ALL apps through the VPN tunnel (for DNS filtering) EXCEPT those in `VPN_WHITELIST_APPS`. This is the **inverse** of what we need for scheduled internet blocking.

### 2.4 Accessibility Service (current state)

`MyAccessibilityService.kt` (2555 lines) is the core blocking engine. The relevant pattern for app-launch blocking is in `onAccessibilityEvent` → `handleWindowStateChange`:

```kotlin
// Line 566: Block apps (blocklist)
if (cachedBlockApps.contains(packageName)) {
    launchBlockActivity(packageName, "block_page_default_block_apps_message")
    return
}
```

`cachedBlockApps` is a `Set<String>` populated from `selected_apps_table` where `identifier = "block_apps"`, refreshed by `refreshBlockingConfig()`. The block action launches `PornBlockActivity` (a full-screen overlay).

**Key observation**: Adding scheduled launch blocking is straightforward — add a parallel `cachedScheduledBlockApps: Set<String>` and check it alongside `cachedBlockApps`. The scheduler engine updates this set when schedules start/end.

### 2.5 Scheduling infrastructure (current state)

The app already has scheduling infrastructure in **Stop Me** (`StopMeManager.kt`, 348 lines):

- **AlarmManager** for exact start/end times (`setExactAndAllowWhileIdle` with `RTC_WAKEUP`)
- `StopMeAlarmReceiver` (BroadcastReceiver) handles `ACTION_STOP_ME_START` / `ACTION_STOP_ME_END`
- Android 12+ `canScheduleExactAlarms()` check with `setAndAllowWhileIdle` fallback
- `AppDataCheckWorker` (WorkManager periodic) calls `checkDueSchedules()` as a safety net
- `AppSystemActionReceiverAllTime` (boot receiver) re-arms schedules after reboot

**Key observation**: The Scheduled App Restrictions feature should reuse this exact pattern. The `StopMeAlarmReceiver` + `StopMeManager` design is a proven template.

### 2.6 Database schema (current state, v10)

9 entities, 9 DAOs:

| Table | Purpose | Lines |
|-------|---------|-------|
| `block_screen_count_table` | Total block count | 1 row |
| `pending_requests_table` | Real Friend approval requests | — |
| `selected_apps_table` | Multi-category app lists (8 identifiers) | — |
| `selected_keyword_table` | Keyword blocklist/whitelist (1189+ presets) | — |
| `stop_me_duration_table` | Stop Me sessions (instant + scheduled) | — |
| `stop_me_session_count_table` | Stop Me session count | 1 row |
| `streak_dates_table` | Streak days + relapse records | ← **REMOVE** |
| `switch_status` | Key-value store (60+ keys) | — |
| `vpn_custom_dns` | DNS presets (4 defaults + user-added) | — |

**Key observation**: `stop_me_duration_table` already has a scheduling schema (`days` bitmask, `startTime`, `endTime`, `startTimeDayMillis`). The new `scheduled_restrictions` table will follow this pattern.

### 2.7 Backup/restore (current state)

`BackupManager.kt` serializes all 9 tables to/from JSON. Streak data is included as `streakDates` in `BackupTables` / `BackupEnvelope`. The `streakDatesCount` appears in `BackupStats` and is displayed in `BackupRestorePage.kt`.

### 2.8 Build environment

- AGP 8.7.2, Kotlin 2.0.21, Compose BOM 2024.10.01
- Room 2.6.1, WorkManager 2.10.0
- JDK 17 (compileOptions), in-process Kotlin compilation (gradle.properties)
- `gradle.properties` tuned for low-memory builds (2GB heap, in-process Kotlin)
- 301 unit tests, all passing

---

## 3. Streak Removal — Complete Inventory

This is Phase 0. Every file and reference below must be removed or updated. **No dead code may remain.**

### 3.1 Files to DELETE entirely (9 files, ~1500 lines)

| File | Lines | Notes |
|------|-------|-------|
| `app/src/main/java/protect/yourself/features/streakPage/StreakPageViewModel.kt` | 334 | ViewModel |
| `app/src/main/java/protect/yourself/features/streakPage/components/StreakPage.kt` | 431 | Compose UI |
| `app/src/main/java/protect/yourself/features/streakPage/widget/StreakWidget.kt` | 126 | Home-screen widget |
| `app/src/main/java/protect/yourself/features/streakPage/identifiers/RelapseTypeIdentifiers.kt` | 51 | Enum |
| `app/src/main/java/protect/yourself/database/streakDates/StreakDatesDao.kt` | 35 | DAO |
| `app/src/main/java/protect/yourself/database/streakDates/StreakDatesItemModel.kt` | 20 | Entity |
| `app/src/main/assets/streak_fire.json` | 469 KB | Lottie animation |
| `app/src/main/res/layout/streak_widget.xml` | — | Widget layout |
| `app/src/main/res/xml/streak_widget_info.xml` | — | Widget metadata |
| `app/src/main/res/drawable/streak_widget.xml` | — | Widget preview drawable |
| `app/src/test/java/protect/yourself/features/streakPage/identifiers/StreakIdentifiersTest.kt` | — | Unit test |

**Total**: 11 files, ~1000 lines of code + 469 KB Lottie asset.

### 3.2 Files to MODIFY (remove Streak references)

| File | Streak references to remove |
|------|-----------------------------|
| `MainActivity.kt` | Remove `import StreakPage`, remove `MainPageScreen.Streak -> StreakPage()` branch, remove `LocalFireDepartment` icon import |
| `MainPageScreen.kt` | Remove `Streak` enum value (or replace with `Schedule`), update `route`/`resourceId` |
| `AppDatabase.kt` | Remove `StreakDatesItemModel` from entities array, remove `streakDatesDao()` abstract fun, remove `import`, bump version to 11, add `MIGRATION_10_11` that drops `streak_dates_table` |
| `AppDatabaseCallback.kt` | (No streak seeding — already clean) |
| `BackupManager.kt` | Remove `streakDates` from `BackupTables`, `BackupEnvelope`, `BackupStats`, `RestoredCounts`, `restoreAllTables()`, `createBackup()`, `calculateStats()`. Remove `import StreakDatesItemModel` |
| `BackupRestorePage.kt` | Remove "Streak history + relapse records" line (line 201), remove "Streak history: N" from restore success (line 320) |
| `BackupRestoreViewModel.kt` | Remove `streakDatesCount = db.streakDatesDao().getAll().size` (line 56) |
| `DailyReportWorker.kt` | Remove streak days read (lines 85-89), remove `streakDays` from notification, update `daily_report_text` string |
| `NotificationHelper.kt` | Remove `streakDays` parameter from `showDailyReportNotification` (line 87, 101) |
| `AppDataCheckWorker.kt` | Remove streak date rollover logic (lines 61-76), remove `getStartOfTodayMillis` helper if only used by streak |
| `AppSystemActionReceiverAllTime.kt` | Remove `streakDays = 0` from daily report notification call (line 85) |
| `ProfilePage.kt` | Update "All data, including streak progress, will be cleared" → "All data, including schedule history, will be cleared" (line 209) |
| `AndroidManifest.xml` | Remove `<receiver>` block for `StreakWidget` (lines 306-317) |
| `strings.xml` | Remove `<string name="streak">`, `<string name="streak_widget_initial_text">`, `<string name="streak_widget_days_label">`. Update `<string name="daily_report_text">` to remove streak reference |
| `app/build.gradle.kts` | (No changes — Lottie dependency is also used elsewhere if any; verify) |

### 3.3 Verification checklist for Streak removal

After removal, run:
```bash
grep -rln "streak\|Streak" app/src --include="*.kt" --include="*.xml" --include="*.json"
```
This MUST return zero results (except possibly in `docs/` historical analysis files).

---

## 4. Recommended Architecture

### 4.1 High-level architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                        │
│  ┌─────────────────┐  ┌──────────────────┐                  │
│  │ ScheduleTab     │  │ ScheduleEditor   │                  │
│  │ (replaces       │  │ (create/edit     │                  │
│  │  Streak tab)    │  │  schedule)       │                  │
│  └────────┬────────┘  └────────┬─────────┘                  │
│           │ ScheduleViewModel   │                            │
└───────────┼─────────────────────┼────────────────────────────┘
            │                     │
┌───────────┼─────────────────────┼────────────────────────────┐
│           ▼   Domain Layer      ▼                            │
│  ┌──────────────────────────────────────────┐                │
│  │       ScheduleEngine (singleton)         │                │
│  │  ┌────────────────────────────────────┐  │                │
│  │  │ • evaluateActiveRules(now)         │  │                │
│  │  │ • scheduleNextBoundary()           │  │                │
│  │  │ • onScheduleStarted/Ended()        │  │                │
│  │  │ • getActiveInternetBlockedApps()   │  │                │
│  │  │ • getActiveLaunchBlockedApps()     │  │                │
│  │  └────────────────────────────────────┘  │                │
│  └──────┬──────────────────┬────────────────┘                │
│         │                  │                                 │
│         ▼                  ▼                                 │
│  ┌─────────────┐   ┌──────────────────┐                      │
│  │ AlarmManager│   │ WorkManager      │                      │
│  │ (exact      │   │ (periodic 15-min │                      │
│  │  boundary)  │   │  safety net)     │                      │
│  └──────┬──────┘   └────────┬─────────┘                      │
│         │                   │                                │
│         ▼                   ▼                                │
│  ┌──────────────────────────────────────┐                    │
│  │ ScheduleAlarmReceiver                │                    │
│  │ (BroadcastReceiver — re-evaluates    │                    │
│  │  rules, restarts VPN, refreshes      │                    │
│  │  Accessibility cache)                │                    │
│  └──────────────────────────────────────┘                    │
└──────────────────────────────────────────────────────────────┘
            │
            ▼ (integration points)
┌──────────────────────────────────────────────────────────────┐
│  ┌─────────────────────┐    ┌──────────────────────────────┐ │
│  │ MyVpnService        │    │ MyAccessibilityService       │ │
│  │ (dual-mode:         │    │ (adds cachedScheduledBlock   │ │
│  │  DNS-filter OR      │    │  Apps set, checked in        │ │
│  │  per-app-block)     │    │  handleWindowStateChange)    │ │
│  └─────────────────────┘    └──────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 New package structure

```
protect.yourself/
├── features/
│   ├── schedulePage/                              ← NEW
│   │   ├── SchedulePage.kt                        (Compose UI — tab content)
│   │   ├── SchedulePageViewModel.kt               (ViewModel)
│   │   ├── components/
│   │   │   ├── ScheduleEditorPage.kt              (create/edit schedule)
│   │   │   ├── ScheduleListItem.kt                (row in schedule list)
│   │   │   ├── DayOfWeekPicker.kt                 (S/M/T/W/T/F/S chips)
│   │   │   ├── TimeRangePicker.kt                 (start/end time)
│   │   │   └── FocusProfilePicker.kt              (Study/Work/Sleep/Custom — Phase 7)
│   │   └── identifiers/
│   │       └── ScheduleTypeIdentifiers.kt          (INTERNET_BLOCK, LAUNCH_BLOCK, BOTH)
│   └── ...
├── domain/                                        ← NEW
│   └── schedule/                                  ← NEW
│       ├── ScheduleEngine.kt                      (singleton coordinator)
│       ├── ScheduleEvaluator.kt                   (pure function: is rule active at time T?)
│       └── ScheduleAlarmReceiver.kt               (BroadcastReceiver)
├── database/
│   ├── scheduledRestrictions/                     ← NEW
│   │   ├── ScheduledRestrictionItemModel.kt       (Entity)
│   │   ├── ScheduledRestrictionDao.kt             (DAO)
│   │   └── ScheduledRestrictionAppItemModel.kt    (Entity — many-to-many app list)
│   └── restrictionLogs/                           ← NEW (Phase 7 — stats)
│       ├── RestrictionLogItemModel.kt
│       └── RestrictionLogDao.kt
└── commons/utils/workManager/
    └── ScheduleCheckWorker.kt                     ← NEW (periodic safety net)
```

### 4.3 Database schema (v11)

Two new tables, one removed:

```kotlin
// NEW: scheduled_restrictions — one row per schedule rule
@Entity(tableName = "scheduled_restrictions")
data class ScheduledRestrictionItemModel(
    @PrimaryKey val key: String,                    // UUID
    val name: String,                               // "YouTube work hours"
    val type: String,                               // "internet" | "launch" | "both"
    val startTimeMinutes: Int,                      // minutes from midnight, 0-1439 (e.g. 600 = 10:00 AM)
    val endTimeMinutes: Int,                        // minutes from midnight, 0-1439
    val daysOfWeek: Int,                            // bitmask: 1=Sun, 2=Mon, 4=Tue, ... 64=Sat
    val isEnabled: Boolean = true,
    val isStrictMode: Boolean = false,              // Phase 7: cannot disable until period ends
    val focusProfile: String = "",                  // Phase 7: "study" | "work" | "sleep" | "custom"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// NEW: scheduled_restriction_apps — apps targeted by each rule
@Entity(
    tableName = "scheduled_restriction_apps",
    primaryKeys = ["restrictionKey", "packageName"]  // composite PK
)
data class ScheduledRestrictionAppItemModel(
    val restrictionKey: String,                     // FK → scheduled_restrictions.key
    val packageName: String,
    val appName: String                             // cached for UI display
)

// REMOVED: streak_dates_table (dropped in MIGRATION_10_11)
```

**Design notes**:
- `startTimeMinutes` / `endTimeMinutes` use minutes-from-midnight (0-1439) instead of epoch millis. This makes daily recurrence trivial — no timezone/DST complexity.
- Cross-midnight schedules (e.g. 10 PM → 6 AM) are represented as `startTimeMinutes=1320, endTimeMinutes=360`. The evaluator treats `endTime < startTime` as "wraps to next day".
- `daysOfWeek` bitmask matches the existing `StopMeDurationItemModel.days` pattern.
- The app list is a separate table (many-to-many) so the UI can show "X apps" without joining.

### 4.4 ScheduleEvaluator — pure function

The core logic is a pure function that determines which rules are active at a given timestamp. This makes it trivially testable:

```kotlin
object ScheduleEvaluator {
    data class ActiveRules(
        val internetBlockedPackages: Set<String>,
        val launchBlockedPackages: Set<String>
    )

    fun evaluate(
        rules: List<ScheduledRestrictionItemModel>,
        appsByRule: Map<String, List<String>>,  // ruleKey → packageNames
        now: Long = System.currentTimeMillis()
    ): ActiveRules {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val dayOfWeekBit = 1 shl (calendar.get(Calendar.DAY_OF_WEEK) - 1)  // 1=Sun
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val internetBlocked = mutableSetOf<String>()
        val launchBlocked = mutableSetOf<String>()

        for (rule in rules) {
            if (!rule.isEnabled) continue
            if (rule.daysOfWeek and dayOfWeekBit == 0) continue  // not scheduled today

            val active = if (rule.startTimeMinutes <= rule.endTimeMinutes) {
                // Same-day: 9:00 → 17:00
                nowMinutes in rule.startTimeMinutes until rule.endTimeMinutes
            } else {
                // Cross-midnight: 22:00 → 6:00
                nowMinutes >= rule.startTimeMinutes || nowMinutes < rule.endTimeMinutes
            }

            if (active) {
                val apps = appsByRule[rule.key] ?: emptyList()
                when (rule.type) {
                    "internet" -> internetBlocked.addAll(apps)
                    "launch" -> launchBlocked.addAll(apps)
                    "both" -> {
                        internetBlocked.addAll(apps)
                        launchBlocked.addAll(apps)
                    }
                }
            }
        }

        return ActiveRules(internetBlocked, launchBlocked)
    }
}
```

### 4.5 ScheduleEngine — singleton coordinator

```kotlin
class ScheduleEngine private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: ScheduleEngine? = null
        fun getInstance(context: Context): ScheduleEngine =
            instance ?: synchronized(this) {
                instance ?: ScheduleEngine(context.applicationContext).also { instance = it }
            }
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val db = AppDatabase.getInstance(context)

    /**
     * Called by ScheduleAlarmReceiver at schedule boundaries,
     * by ScheduleCheckWorker (periodic), by boot receiver,
     * and by SchedulePageViewModel after CRUD operations.
     *
     * 1. Evaluates active rules.
     * 2. If internet-blocked set changed → restart VPN in appropriate mode.
     * 3. If launch-blocked set changed → refresh Accessibility service cache.
     * 4. Schedules next boundary alarm.
     */
    suspend fun reevaluateAndApply() {
        val rules = db.scheduledRestrictionDao().getAllEnabled()
        val appsByRule = rules.associate { rule ->
            rule.key to db.scheduledRestrictionAppDao().getPackagesForRule(rule.key)
        }
        val active = ScheduleEvaluator.evaluate(rules, appsByRule)

        // Update Accessibility cache
        MyAccessibilityService.instance?.updateScheduledBlockApps(active.launchBlockedPackages)

        // Update VPN if internet-blocked set changed
        val vpnNeedsRestart = active.internetBlockedPackages != lastInternetBlockedSet
        if (vpnNeedsRestart) {
            lastInternetBlockedSet = active.internetBlockedPackages
            if (active.internetBlockedPackages.isNotEmpty()) {
                // Restart VPN in per-app-block mode
                MyVpnService.restart(context)
            } else if (vpnWasInPerAppBlockMode) {
                // No more scheduled blocks → restart VPN in normal DNS-filter mode
                vpnWasInPerAppBlockMode = false
                MyVpnService.restart(context)
            }
        }

        scheduleNextBoundary(rules)
    }

    private fun scheduleNextBoundary(rules: List<ScheduledRestrictionItemModel>) {
        // Find the nearest future boundary (any rule starting or ending)
        // Schedule an AlarmManager alarm at that timestamp
        // → ScheduleAlarmReceiver will call reevaluateAndApply()
    }

    fun onBootCompleted() {
        // Re-arm all alarms (same pattern as StopMeManager)
    }
}
```

### 4.6 VPN dual-mode integration

`MyVpnService.startVpn()` needs a new mode parameter. The VPN runs in one of two modes:

**Mode A: DNS-filter mode (current behavior, default)**
- `addDnsServer(familySafeDNS)`
- All apps routed through VPN (except `VPN_WHITELIST_APPS`)
- Purpose: content filtering for all apps

**Mode B: Per-app-block mode (new, when scheduled internet blocking is active)**
- `addAllowedApplication(scheduledApp1)` × N (ONLY scheduled apps routed through VPN)
- `addDnsServer(familySafeDNS)` (still applies to routed apps, but irrelevant — their traffic is black-holed)
- No packet forwarding → scheduled apps get no internet
- All other apps bypass VPN → normal internet
- Purpose: deny internet to specific apps

**Critical**: These two modes are mutually exclusive at the `Builder` level. When both DNS filtering AND scheduled internet blocking are needed simultaneously, we must choose one:

| Scenario | VPN mode | DNS filtering | Scheduled internet block |
|----------|----------|---------------|--------------------------|
| VPN ON, no schedule active | A (DNS-filter) | ✅ All apps | ❌ |
| VPN ON, schedule active (internet block) | B (per-app-block) | ❌ Other apps lose DNS filtering | ✅ Scheduled apps |
| VPN OFF, schedule active (internet block) | B (per-app-block) | ❌ | ✅ Scheduled apps |
| VPN OFF, no schedule | (VPN not running) | ❌ | ❌ |

**Design decision**: When scheduled internet blocking is active, the VPN runs in Mode B. This means **DNS filtering is paused for non-scheduled apps while a schedule is active**. This is an acceptable trade-off because:
1. Scheduled internet blocking is typically active during focused time windows (work/study/sleep), when the user is less likely to be browsing.
2. The alternative (forwarding both DNS and black-holing specific apps) requires a full packet forwarder — the exact complexity that caused the "VPN connected but no filtering" bug in v1.0.33 (see `docs/VPN_DEEP_ANALYSIS.md` §2.1).

**Implementation**: Add a `VpnMode` enum and a companion `currentMode` field:

```kotlin
enum class VpnMode { DNS_FILTER, PER_APP_BLOCK }

companion object {
    @Volatile var currentMode: VpnMode = VpnMode.DNS_FILTER
        private set

    fun setMode(mode: VpnMode) {
        if (currentMode != mode) {
            currentMode = mode
            // Restart VPN to apply new mode
            restart(context)
        }
    }
}
```

In `startVpn()`:
```kotlin
when (currentMode) {
    VpnMode.DNS_FILTER -> {
        // Existing behavior: addDnsServer + addDisallowedApplication(whitelist)
    }
    VpnMode.PER_APP_BLOCK -> {
        // New: addAllowedApplication(scheduledApps) + addDnsServer (irrelevant)
        val scheduledApps = ScheduleEngine.getInstance(this).getActiveInternetBlockedApps()
        for (pkg in scheduledApps) {
            builder.addAllowedApplication(pkg)
        }
        // Self must also be allowed (in per-app-block mode, addAllowedApplication
        // means ONLY these apps are routed — we don't want to route ourselves)
    }
}
```

### 4.7 Accessibility Service integration

Add a parallel cache alongside `cachedBlockApps`:

```kotlin
@Volatile private var cachedScheduledBlockApps: Set<String> = emptySet()

fun updateScheduledBlockApps(apps: Set<String>) {
    cachedScheduledBlockApps = apps
    Timber.i("Scheduled block apps updated: ${apps.size} apps")
}
```

In `handleWindowStateChange()`, add the check (before the existing `cachedBlockApps` check):

```kotlin
// Scheduled launch blocking
if (cachedScheduledBlockApps.contains(packageName)) {
    launchBlockActivity(packageName, "block_page_default_scheduled_app_message")
    return
}
```

### 4.8 ScheduleAlarmReceiver

```kotlin
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = appCoroutineScope("ScheduleAlarmReceiver", Dispatchers.IO)
        scope.launch {
            try {
                ScheduleEngine.getInstance(context).reevaluateAndApply()
            } catch (t: Throwable) {
                Timber.e(t, "ScheduleAlarmReceiver failed")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE_BOUNDARY = "protect_yourself.action.SCHEDULE_BOUNDARY"
    }
}
```

### 4.9 ScheduleCheckWorker (periodic safety net)

```kotlin
class ScheduleCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            ScheduleEngine.getInstance(applicationContext).reevaluateAndApply()
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "ScheduleCheckWorker failed")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "schedule_check_periodic"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleCheckWorker>(
                15, TimeUnit.MINUTES  // minimum allowed by WorkManager
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
```

---

## 5. Android Best Practices & Relevant APIs

### 5.1 APIs used

| API | Purpose | minSdk | Notes |
|-----|---------|--------|-------|
| `VpnService.Builder.addAllowedApplication()` | Route only specific apps through VPN | 21 | Core of per-app internet blocking |
| `VpnService.Builder.addDisallowedApplication()` | Bypass VPN for specific apps | 21 | Existing DNS-filter mode uses this |
| `AlarmManager.setExactAndAllowWhileIdle()` | Exact schedule boundaries | 23 (M) | Android 12+ requires `canScheduleExactAlarms()` check |
| `AlarmManager.canScheduleExactAlarms()` | Check exact alarm permission | 31 (S) | Fall back to `setAndAllowWhileIdle` if denied |
| `WorkManager PeriodicWorkRequest` | 15-min safety net | 26 | Ensures schedules re-evaluate after Doze/force-stop |
| `BroadcastReceiver` (BOOT_COMPLETED) | Re-arm alarms after reboot | 26 | Already registered in manifest |
| `AccessibilityService` | Detect app launches | 26 | Already configured; add new cache set |
| `Room` with migrations | Schema evolution | 26 | v10 → v11 migration drops streak, adds 2 tables |

### 5.2 Best practices applied

1. **Single source of truth**: `ScheduleEngine` is the only component that decides which apps are blocked. VPN and Accessibility read from it, never from the DB directly.
2. **Pure evaluation function**: `ScheduleEvaluator.evaluate()` is a pure function — no side effects, no I/O. Trivially unit-testable.
3. **Idempotent re-evaluation**: `reevaluateAndApply()` can be called multiple times safely. It only restarts the VPN when the active set actually changes.
4. **Defense-in-depth scheduling**: AlarmManager (exact) + WorkManager (periodic) + boot receiver. If any one fails, the others catch up.
5. **Android 12+ exact alarm restrictions**: Check `canScheduleExactAlarms()` at runtime; fall back to inexact alarms. The `SCHEDULE_EXACT_ALARM` permission is already in the manifest.
6. **No main-thread I/O**: All DB reads/writes in `ScheduleEngine` are suspend functions on `Dispatchers.IO`.
7. **Crash-safe**: `reevaluateAndApply()` wraps all operations in try/catch; a failure logs a crash breadcrumb and reschedules.

---

## 6. Technical Limitations & Platform Constraints

### 6.1 VPN limitations

| Limitation | Impact | Mitigation |
|------------|--------|------------|
| Only one VPN can be active at a time | If another app's VPN is active, ours can't start | Show a clear error: "Another VPN is active. Disable it to use scheduled internet blocking." |
| `addAllowedApplication` and `addDisallowedApplication` are mutually exclusive per Builder | Can't mix DNS-filter mode and per-app-block mode in one VPN session | Use dual-mode approach (§4.6); restart VPN when mode changes |
| VPN permission can be revoked by user | Scheduled internet blocking silently stops | `onRevoke()` already syncs `VPN_SWITCH=false` (BUG-02 fix); add schedule-aware re-evaluation |
| Always-on VPN (system setting) can force our VPN to stay up | If user enables always-on, we can't switch modes | Detect via `VpnService.prepare()` checks; warn user |
| Android may kill VPN service under memory pressure | Scheduled internet blocking stops | `START_STICKY` (already set) + `VpnRestartWorker` pattern |

### 6.2 Accessibility Service limitations

| Limitation | Impact | Mitigation |
|------------|--------|------------|
| User can disable Accessibility at any time | Scheduled launch blocking stops | Already monitored by `AccessibilityGuard` (existing); add schedule-aware re-evaluation |
| `onAccessibilityEvent` fires AFTER the app window is already visible | Brief flash of the blocked app before overlay appears | Mitigate by making `PornBlockActivity` opaque + fast-launching (already the case) |
| Accessibility can't prevent app launch — only react to it | The blocked app's process starts briefly | Acceptable; the overlay immediately covers it |
| Battery: Accessibility fires on EVERY window change | Frequent wakeups | Existing `cachedBlockApps` set check is O(1); scheduled check adds another O(1) set lookup |

### 6.3 AlarmManager limitations (Android 12+)

| Limitation | Impact | Mitigation |
|------------|--------|------------|
| `SCHEDULE_EXACT_ALARM` permission is restricted on Android 13+ | App must request user to grant exact alarms in Settings | Check `canScheduleExactAlarms()`; if false, use `setAndAllowWhileIdle` (less precise, but still fires within ~15 min) |
| Doze mode delays inexact alarms | Schedule boundaries may be delayed by 15-30 min | Acceptable for non-strict mode; strict mode requires exact alarms |
| App force-stop cancels all alarms | After force-stop, schedules don't fire until app is reopened | WorkManager periodic worker re-arms on next run; `AppDataCheckWorker` pattern |

### 6.4 WorkManager limitations

| Limitation | Impact | Mitigation |
|------------|--------|------------|
| Minimum periodic interval is 15 minutes | Schedule boundaries may be off by up to 15 min | AlarmManager provides exact boundaries; WorkManager is the safety net only |
| WorkManager may be delayed in Doze | Same as above | Same as above |

### 6.5 Cross-midnight schedule edge case

A schedule from 22:00 to 06:00 spans midnight. The evaluator handles this:
- If `endTimeMinutes < startTimeMinutes` → the schedule wraps to the next day
- `nowMinutes >= startTimeMinutes || nowMinutes < endTimeMinutes` → active

**Edge case**: A schedule from 00:00 to 23:59 (essentially all day) — `startTimeMinutes=0, endTimeMinutes=1439`. `startTime <= endTime`, so the standard `nowMinutes in startTime until endTime` check applies. Works correctly.

### 6.6 Day-of-week bitmask edge cases

- `daysOfWeek = 127` → every day (all 7 bits set)
- `daysOfWeek = 0` → no days (rule never active) — should be rejected at the UI level
- Sunday = bit 0 (value 1), Saturday = bit 6 (value 64) — matches `Calendar.DAY_OF_WEEK` (1=Sunday)

---

## 7. Risks, Edge Cases & Compatibility

### 7.1 Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| VPN mode switch (DNS-filter ↔ per-app-block) causes brief internet interruption | Medium | Schedule the mode switch at the exact boundary; the interruption is <1 sec. Acceptable. |
| User has VPN_WHITELIST_APPS configured, then enables scheduled internet blocking | Medium | In per-app-block mode, `VPN_WHITELIST_APPS` is irrelevant (only `addAllowedApplication` apps are routed). Document this in the UI. |
| Accessibility service is overloaded with checks | Low | Adding one more `Set.contains()` is O(1) — negligible |
| DB migration v10 → v11 fails | High | `MIGRATION_10_11` is additive (2 new tables) + destructive (drop streak). `fallbackToDestructiveMigration` is the last resort. Test migration with `RoomDatabase.MigrationTestHelper`. |
| Streak removal breaks backup/restore for existing users | Medium | `BackupManager` should gracefully handle old backups that contain `streakDates` — just ignore the field. The `restoreAllTables` already skips null/empty tables (BUG-03 fix). |
| Schedule doesn't fire after device reboot | High | `AppSystemActionReceiverAllTime` (boot receiver) calls `ScheduleEngine.onBootCompleted()` to re-arm all alarms. Same pattern as `StopMeManager`. |
| User uninstalls an app that's in a schedule | Low | The scheduled app list stores `packageName` — if the app is uninstalled, `addAllowedApplication` throws `NameNotFoundException`. Catch and log (already pattern in existing code). |

### 7.2 Edge cases

1. **Schedule with no apps**: UI should reject — a schedule with 0 apps is meaningless.
2. **Schedule with 0 days selected**: UI should reject.
3. **Schedule where start time == end time**: Treat as "all day" (active 24/7 on selected days). Or reject. Recommend reject at UI level.
4. **Multiple overlapping schedules targeting the same app**: Union — if ANY active schedule blocks the app, it's blocked.
5. **Schedule active during DST transition**: `Calendar` handles this automatically; `nowMinutes` is local time.
6. **User changes timezone**: Schedules are in local time. If the user travels, schedules fire at the new local time. This is the expected behavior.
7. **App killed by system (low memory)**: On next app launch, `ScheduleEngine.reevaluateAndApply()` runs and catches up. WorkManager periodic worker also catches up within 15 min.

### 7.3 Compatibility

| Android version | minSdk 26 | Notes |
|-----------------|-----------|-------|
| Android 8.0 (26) | ✅ | `startForegroundService` works (BUG-01 fix) |
| Android 9.0 (28) | ✅ | |
| Android 10 (29) | ✅ | |
| Android 11 (30) | ✅ | |
| Android 12 (31) | ✅ | `canScheduleExactAlarms()` check required |
| Android 13 (33) | ✅ | `SCHEDULE_EXACT_ALARM` restricted; `USE_EXACT_ALARM` for calendar/alarm apps — we're neither, so user must grant in Settings |
| Android 14 (34) | ✅ | Foreground service type `specialUse` (already declared) |
| Android 15 (35) | ✅ | targetSdk 35 — tested |

---

## 8. Performance, Battery & Security

### 8.1 Performance

| Operation | Frequency | Cost | Notes |
|-----------|-----------|------|-------|
| `ScheduleEvaluator.evaluate()` | Every boundary + every 15 min | <1 ms (pure function, ~10 rules) | Negligible |
| `ScheduleEngine.reevaluateAndApply()` | Every boundary + every 15 min | ~10-50 ms (DB read + VPN restart check) | VPN restart only when set changes |
| VPN restart on mode change | Only when schedule starts/ends | ~500 ms (establish() call) | Acceptable at boundaries |
| Accessibility `cachedScheduledBlockApps.contains()` | Every window change event | O(1) set lookup | Negligible |
| DB read of `scheduled_restrictions` | Every re-evaluation | ~5 ms (small table, ~10-50 rows) | Indexed by `isEnabled` |

### 8.2 Battery

| Component | Battery impact | Mitigation |
|-----------|----------------|------------|
| AlarmManager exact alarms | Very low (fires only at boundaries) | Already used by Stop Me |
| WorkManager periodic (15 min) | Low | Only runs `reevaluateAndApply()` (~50 ms) |
| VPN in per-app-block mode | Same as DNS-filter mode | No additional cost |
| Accessibility service | Already running | One extra `Set.contains()` per event — negligible |

**Estimated additional battery drain**: <1% per day (based on Stop Me's similar pattern).

### 8.3 Security

| Consideration | Status |
|---------------|--------|
| Schedule data stored locally | ✅ In Room DB, never sent to servers |
| VPN only blocks app traffic, doesn't inspect it | ✅ Per-app-block mode just doesn't forward packets |
| Accessibility only checks package name, doesn't read content | ✅ Same as existing `cachedBlockApps` check |
| No new permissions required | ✅ All permissions already declared |
| Strict mode (Phase 7) must not be bypassable | ⚠️ Phase 7 — needs `DeviceAdminManager` or similar to prevent app uninstall during strict period |

---

## 9. UI/UX Recommendations

### 9.1 Tab replacement

Replace the Streak tab with a "Schedule" tab:

| Aspect | Current (Streak) | New (Schedule) |
|--------|------------------|----------------|
| Tab name | "Streak" | "Schedule" |
| Icon | `Icons.Filled.LocalFireDepartment` | `Icons.Filled.Schedule` (Material icon) |
| Route | "Streak" | "Schedule" |
| Content | Streak count + relapse log | Schedule list + FAB to add |

### 9.2 Schedule list page (tab content)

```
┌─────────────────────────────────────┐
│  Schedule                           │  ← Title
│  Restrict apps by time              │  ← Subtitle
├─────────────────────────────────────┤
│  ┌─────────────────────────────────┐│
│  │ 🌐 YouTube Work Hours    [ON]  ││  ← Schedule card
│  │ 9:00 AM – 5:00 PM, Mon–Fri      ││
│  │ Internet blocked · 1 app        ││
│  │ Currently active                ││  ← Live status badge
│  └─────────────────────────────────┘│
│  ┌─────────────────────────────────┐│
│  │ 🚫 TikTok Bedtime        [ON]  ││
│  │ 11:00 PM – 7:00 AM, Daily        ││
│  │ Launch blocked · 1 app          ││
│  │ Starts in 4h 23m                ││
│  └─────────────────────────────────┘│
│  ┌─────────────────────────────────┐│
│  │ 🌐🚫 Focus Mode          [OFF] ││
│  │ 9:00 AM – 5:00 PM, Mon–Fri      ││
│  │ Both · 5 apps                   ││
│  └─────────────────────────────────┘│
│                                     │
│                              ╔════╗ │
│                              ║ +  ║ │  ← FAB
│                              ╚════╝ │
└─────────────────────────────────────┘
```

### 9.3 Schedule editor page

```
┌─────────────────────────────────────┐
│  ← Back          New Schedule       │
├─────────────────────────────────────┤
│  Name                               │
│  ┌─────────────────────────────────┐│
│  │ YouTube Work Hours              ││  ← Text field
│  └─────────────────────────────────┘│
│                                     │
│  Restriction Type                   │
│  ┌─────────┐ ┌─────────┐ ┌────────┐│
│  │ Internet│ │ Launch  │ │  Both  ││  ← Segmented buttons
│  └─────────┘ └─────────┘ └────────┘│
│                                     │
│  Time Window                        │
│  ┌──────────┐    ┌──────────┐       │
│  │ 9:00 AM ▼│ →  │ 5:00 PM ▼│       │  ← Time pickers
│  └──────────┘    └──────────┘       │
│                                     │
│  Repeat                             │
│  [S] [M] [T] [W] [T] [F] [S]       │  ← Day-of-week chips
│                                     │
│  Apps (1 selected)                  │
│  ┌─────────────────────────────────┐│
│  │ YouTube                  ✓      ││  ← App picker (reuses SelectAppPage)
│  └─────────────────────────────────┘│
│  [+ Add apps]                       │
│                                     │
│  ┌─────────────────────────────────┐│
│  │           Save                  ││  ← Save button
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

### 9.4 UX principles applied

1. **Live status**: Each schedule card shows whether it's "Currently active" or "Starts in Xh Ym".
2. **Quick toggle**: Each card has an ON/OFF switch — no need to open the editor to pause.
3. **Visual distinction**: Internet-block vs launch-block vs both are visually distinct (icons).
4. **Reuse existing app picker**: `SelectAppPage` already supports multi-select — extend it for schedule app selection.
5. **Time picker**: Use Material 3 `TimePicker` component.
6. **Day-of-week chips**: `FilterChip` row, matches Material 3 guidelines.
7. **Empty state**: When no schedules exist, show an illustration + "Create your first schedule" CTA.
8. **Error states**: Clear toasts for "Select at least one app", "Select at least one day", etc.

---

## 10. Phased Implementation Roadmap

### Phase 0: Streak Removal (P0, 1 day)

1. Delete the 11 Streak files listed in §3.1
2. Update the 14 files listed in §3.2 to remove Streak references
3. Add `MIGRATION_10_11` to `AppDatabase.kt` that drops `streak_dates_table`
4. Remove `StreakDatesItemModel` from `@Database` entities, remove `streakDatesDao()`
5. Bump DB version to 11
6. Update `strings.xml`: remove streak strings, update `daily_report_text`
7. Verify: `grep -rln "streak\|Streak" app/src` returns zero results
8. Build + run 301 tests (some streak tests will be removed — adjust expected count)

**Acceptance**: App builds, tests pass, no streak references remain.

### Phase 1: DB Schema + Entities + DAOs (P0, 0.5 day)

1. Create `ScheduledRestrictionItemModel.kt` + `ScheduledRestrictionDao.kt`
2. Create `ScheduledRestrictionAppItemModel.kt` + `ScheduledRestrictionAppDao.kt`
3. Add both to `@Database` entities (now v11 with 10 entities)
4. Add abstract DAO functions to `AppDatabase`
5. Update `MIGRATION_10_11` to CREATE both new tables
6. Update `AppDatabaseCallback.onCreate` to seed any default focus profiles (Phase 7)
7. Write unit tests for both DAOs (extend `AllDaosTest.kt`)

**Acceptance**: DB migration v10 → v11 works; DAO CRUD tests pass.

### Phase 2: Scheduler Engine (P0, 1.5 days)

1. Create `ScheduleEvaluator.kt` (pure function) + unit tests (15+ cases)
2. Create `ScheduleEngine.kt` singleton
3. Create `ScheduleAlarmReceiver.kt` + register in `AndroidManifest.xml`
4. Create `ScheduleCheckWorker.kt` + enqueue from `ProtectYourselfApp.onCreate()`
5. Add `ScheduleEngine.onBootCompleted()` call to `AppSystemActionReceiverAllTime`
6. Wire up `ScheduleEngine.reevaluateAndApply()` to be called by the alarm receiver, the worker, and the boot receiver

**Acceptance**: `ScheduleEvaluator` unit tests pass; `ScheduleEngine` correctly schedules alarms; boot receiver re-arms alarms.

### Phase 3: VPN Integration (P0, 1 day)

1. Add `VpnMode` enum + `currentMode` companion field to `MyVpnService`
2. Add `setMode()` function that restarts VPN when mode changes
3. Modify `startVpn()` to branch on `currentMode`:
   - `DNS_FILTER`: existing behavior
   - `PER_APP_BLOCK`: use `addAllowedApplication` for scheduled apps
4. `ScheduleEngine.reevaluateAndApply()` calls `MyVpnService.setMode()` when the internet-blocked set changes
5. Handle the case where VPN is OFF but scheduled internet blocking is active — start VPN in per-app-block mode
6. Handle `onRevoke()` — re-evaluate schedules after permission revoked

**Acceptance**: Scheduled internet blocking works; app opens but has no internet; other apps unaffected.

### Phase 4: Accessibility Integration (P0, 0.5 day)

1. Add `cachedScheduledBlockApps: Set<String>` to `MyAccessibilityService`
2. Add `updateScheduledBlockApps()` function
3. Add the check in `handleWindowStateChange()` before the existing `cachedBlockApps` check
4. `ScheduleEngine.reevaluateAndApply()` calls `MyAccessibilityService.instance?.updateScheduledBlockApps()`
5. Add new block-screen message string: `block_page_default_scheduled_app_message`

**Acceptance**: Scheduled launch blocking works; blocked app immediately shows block screen.

### Phase 5: UI (P0, 2 days)

1. Create `SchedulePage.kt` (tab content — schedule list + FAB)
2. Create `SchedulePageViewModel.kt` (CRUD + live status)
3. Create `ScheduleEditorPage.kt` (create/edit form)
4. Create `DayOfWeekPicker.kt` (reusable component)
5. Create `TimeRangePicker.kt` (reusable component)
6. Extend `SelectAppPage` to support schedule app selection (new `SelectedAppListIdentifier` or a dedicated picker)
7. Replace `MainPageScreen.Streak` with `MainPageScreen.Schedule`
8. Update `MainActivity.kt`: `MainPageScreen.Schedule -> SchedulePage()`
9. Update tab icon to `Icons.Filled.Schedule`
10. Update `strings.xml` with all new strings

**Acceptance**: Full CRUD works; schedules can be created, edited, deleted, toggled; list shows live status.

### Phase 6: Testing + Polish (P0, 1 day)

1. Write unit tests for `ScheduleEvaluator` (15+ edge cases)
2. Write unit tests for DAOs
3. Write integration test for `ScheduleEngine` → VPN mode switch
4. Manual testing on Android 14 device:
   - Create internet-block schedule → verify target app loses internet
   - Create launch-block schedule → verify target app shows block screen
   - Create both schedule → verify both behaviors
   - Cross-midnight schedule
   - Day-of-week recurrence
   - Reboot persistence
   - VPN permission revoke
5. Update `BackupManager` to include `scheduled_restrictions` + `scheduled_restriction_apps` tables
6. Update `BackupRestorePage` to show schedule counts
7. Add new string resources for all user-facing text

**Acceptance**: All tests pass; manual test plan passes; backup/restore works.

### Phase 7: Enhancements (P1, 3 days)

1. **Focus profiles** (Study, Work, Sleep, Custom) — pre-built schedule templates
2. **Strict mode** — schedule cannot be disabled until period ends (requires Device Admin or PIN)
3. **Block history / usage statistics** — `restriction_logs` table + stats UI
4. **Notifications before restriction starts/ends** — 5-min warning notification
5. **Temporary override** — emergency unlock with cooldown

### Phase 8: Backup/Restore + Import/Export (P1, 1 day)

1. Add `scheduledRestrictions` + `scheduledRestrictionApps` to `BackupTables` / `BackupEnvelope`
2. Update `BackupManager.restoreAllTables()` to restore both tables
3. Add export-schedules-to-JSON feature (shareable file)
4. Add import-schedules-from-JSON feature

---

## 11. Testing & Validation Strategy

### 11.1 Unit tests

| Test class | Coverage target | Key cases |
|------------|----------------|-----------|
| `ScheduleEvaluatorTest` | 95% | Same-day schedule, cross-midnight, multi-day, overlapping, disabled rules, empty apps, all-day |
| `ScheduledRestrictionDaoTest` | 90% | CRUD, getAllEnabled, getAppsForRule |
| `ScheduledRestrictionAppDaoTest` | 90% | CRUD, composite PK, getPackagesForRule |
| `ScheduleEngineTest` | 80% | reevaluateAndApply, scheduleNextBoundary, onBootCompleted |
| `AppDatabaseMigrationTest` | 100% | v10 → v11 migration (streak dropped, new tables created) |

### 11.2 Integration tests

| Test | What it verifies |
|------|------------------|
| `ScheduleEngineVpnIntegrationTest` | Schedule start → VPN switches to per-app-block mode → target app's traffic black-holed |
| `ScheduleEngineAccessibilityIntegrationTest` | Schedule start → Accessibility `cachedScheduledBlockApps` updated → blocked app launches block screen |
| `ScheduleAlarmReceiverTest` | Alarm fires → `reevaluateAndApply()` runs → next alarm scheduled |
| `ScheduleCheckWorkerTest` | Worker runs → `reevaluateAndApply()` runs |

### 11.3 Manual test plan

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| 1 | Internet block, same-day | Create schedule 9:00-17:00, Mon-Fri, internet block, YouTube. Wait until 9:00. | YouTube opens but has no internet. Other apps have internet. |
| 2 | Launch block, cross-midnight | Create schedule 23:00-7:00, daily, launch block, TikTok. Wait until 23:00. | TikTok shows block screen immediately on launch. |
| 3 | Both block | Create schedule with type=both. | Both internet and launch blocked during window. |
| 4 | Multiple schedules, same app | Two schedules targeting YouTube, one active, one not. | YouTube blocked (union semantics). |
| 5 | Reboot persistence | Create schedule, reboot device. | Schedule fires correctly after reboot. |
| 6 | VPN permission revoked | Revoke VPN permission during active schedule. | `VPN_SWITCH` synced to false (BUG-02 fix); schedule re-evaluates on next app launch. |
| 7 | Accessibility disabled | Disable Accessibility during active launch-block schedule. | `AccessibilityGuard` detects (existing); launch block stops until re-enabled. |
| 8 | Schedule toggle | Toggle a schedule OFF mid-window. | Blocking stops immediately; VPN restarts in DNS-filter mode. |
| 9 | App uninstalled | Uninstall an app that's in a schedule. | Schedule still works for other apps; uninstalled app's `addAllowedApplication` throws, caught and logged. |
| 10 | Backup/restore | Create schedules, backup, restore on fresh install. | Schedules restored correctly. |

### 11.4 Build verification

```bash
# After each phase:
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:assembleRelease
./gradlew --no-daemon :app:testDebugUnitTest
# All must succeed; test count should be >= 301 (minus removed streak tests, plus new tests)
```

---

## 12. Future Enhancements & Long-term Maintainability

### 12.1 Future enhancements (P2, post-launch)

1. **Geo-fencing**: Trigger schedules based on location (home/work/school).
2. **Usage-based triggers**: Block an app after X minutes of use per day.
3. **App categories**: Block "all social media" or "all games" by category.
4. **Schedule templates**: Pre-built templates (Study, Work, Sleep, Digital Detox).
5. **Schedule insights**: "You saved 2h 30m this week by blocking YouTube during work hours."
6. **Family/link sharing**: Share schedules with accountability partner.
7. **Widget**: Home-screen widget to toggle a "Focus Mode" schedule on/off.
8. **Quick settings tile**: Toggle a default schedule from the quick settings shade.

### 12.2 Long-term maintainability

| Practice | Implementation |
|----------|----------------|
| Pure evaluation function | `ScheduleEvaluator` is fully testable without Android dependencies |
| Single source of truth | `ScheduleEngine` is the only component that decides what's blocked |
| Feature flags | Add `SCHEDULED_RESTRICTIONS_ENABLED` switch to enable/disable the feature for A/B testing |
| Telemetry | Use the existing `CrashLogger` breadcrumb system to log schedule transitions |
| Documentation | This plan + inline KDoc on every public API |
| Schema migrations | Every DB change gets a numbered migration (`MIGRATION_11_12`, etc.) |
| Backward compatibility | `BackupManager` handles old backups gracefully (ignores unknown fields) |

### 12.3 Monitoring

- Crash log breadcrumbs: `ScheduleEngine.reevaluateAndApply()` logs "X internet-blocked, Y launch-blocked apps"
- Crash log on schedule failure: `ScheduleAlarmReceiver` logs throwable if `reevaluateAndApply()` throws
- VPN mode transitions: `MyVpnService.setMode()` logs "Switching from DNS_FILTER to PER_APP_BLOCK"
- Accessibility cache updates: `updateScheduledBlockApps()` logs "N apps in scheduled block set"

---

## 13. Implementation Order Summary

```
Phase 0: Streak Removal          [1 day]
    │
    ▼
Phase 1: DB Schema v11           [0.5 day]
    │
    ▼
Phase 2: Scheduler Engine        [1.5 days]
    │
    ├──▶ Phase 3: VPN Integration    [1 day]
    │
    └──▶ Phase 4: Accessibility      [0.5 day]
              │
              ▼
        Phase 5: UI                 [2 days]
              │
              ▼
        Phase 6: Testing + Polish   [1 day]
              │
              ▼
        ─── P0 COMPLETE (7.5 days) ───
              │
              ├──▶ Phase 7: Enhancements      [3 days]
              │
              └──▶ Phase 8: Backup/Restore    [1 day]
                        │
                        ▼
                 ─── P1 COMPLETE (4 days) ───
```

---

## 14. Files to Create (Complete List)

### New files (Phase 0-6)

```
app/src/main/java/protect/yourself/
├── domain/schedule/
│   ├── ScheduleEngine.kt
│   ├── ScheduleEvaluator.kt
│   └── ScheduleAlarmReceiver.kt
├── database/scheduledRestrictions/
│   ├── ScheduledRestrictionItemModel.kt
│   ├── ScheduledRestrictionDao.kt
│   ├── ScheduledRestrictionAppItemModel.kt
│   └── ScheduledRestrictionAppDao.kt
├── features/schedulePage/
│   ├── SchedulePage.kt
│   ├── SchedulePageViewModel.kt
│   ├── components/
│   │   ├── ScheduleEditorPage.kt
│   │   ├── ScheduleListItem.kt
│   │   ├── DayOfWeekPicker.kt
│   │   └── TimeRangePicker.kt
│   └── identifiers/
│       └── ScheduleTypeIdentifiers.kt
├── commons/utils/workManager/
│   └── ScheduleCheckWorker.kt

app/src/test/java/protect/yourself/
├── domain/schedule/
│   ├── ScheduleEvaluatorTest.kt
│   └── ScheduleEngineTest.kt
├── database/scheduledRestrictions/
│   ├── ScheduledRestrictionDaoTest.kt
│   └── ScheduledRestrictionAppDaoTest.kt
└── database/core/
    └── AppDatabaseMigrationTest.kt
```

### Files to modify (Phase 0-6)

```
app/src/main/java/protect/yourself/
├── database/core/AppDatabase.kt                          (v11, add entities + DAOs + migration)
├── database/core/AppDatabaseCallback.kt                  (seed defaults if needed)
├── database/switchStatus/SwitchIdentifier.kt             (add SCHEDULE_MASTER_SWITCH)
├── features/mainActivityPage/MainActivity.kt             (Streak → Schedule tab)
├── features/mainActivityPage/repository/MainPageScreen.kt (Streak → Schedule)
├── features/blockerPage/service/MyVpnService.kt          (dual-mode support)
├── features/blockerPage/service/MyAccessibilityService.kt (cachedScheduledBlockApps)
├── features/backupRestore/BackupManager.kt              (add schedule tables, remove streak)
├── features/backupRestore/BackupRestorePage.kt          (update stats display)
├── features/backupRestore/BackupRestoreViewModel.kt     (update stats calc)
├── features/profilePage/components/ProfilePage.kt       (update "clear data" text)
├── commons/utils/workManager/AppDataCheckWorker.kt      (remove streak rollover, add schedule check)
├── commons/utils/workManager/DailyReportWorker.kt       (remove streak days)
├── commons/utils/notificationUtils/NotificationHelper.kt (remove streakDays param)
├── commons/utils/broadcastReceivers/AppSystemActionReceiverAllTime.kt (remove streakDays, add schedule boot)
├── core/ProtectYourselfApp.kt                           (enqueue ScheduleCheckWorker)
└── features/blockerPage/components/BlockerPageHome.kt   (if schedule sub-page navigation needed)

app/src/main/AndroidManifest.xml                          (remove StreakWidget, add ScheduleAlarmReceiver)
app/src/main/res/values/strings.xml                       (remove streak strings, add schedule strings)
app/build.gradle.kts                                      (version bump)
app/src/main/assets/                                       (remove streak_fire.json)
```

### Files to delete (Phase 0)

```
app/src/main/java/protect/yourself/features/streakPage/   (4 .kt files)
app/src/main/java/protect/yourself/database/streakDates/  (2 .kt files)
app/src/main/assets/streak_fire.json
app/src/main/res/layout/streak_widget.xml
app/src/main/res/xml/streak_widget_info.xml
app/src/main/res/drawable/streak_widget.xml
app/src/test/java/protect/yourself/features/streakPage/   (1 test file)
```

---

## 15. Conclusion

This plan provides a clear, phased path to:

1. **Remove the Streak feature entirely** — no dead code, no orphan resources, clean DB migration.
2. **Add Scheduled App Restrictions** — a unified, well-architected feature that reuses the existing VPN + Accessibility infrastructure.
3. **Maintain the 3-tab UX** — Schedule replaces Streak; Home and Profile unchanged.
4. **Minimize risk** — dual-mode VPN avoids breaking existing DNS filtering; pure-function evaluator is fully testable; defense-in-depth scheduling (AlarmManager + WorkManager + boot receiver) ensures reliability.
5. **Stay within platform constraints** — no new permissions; respects Android 12+ exact alarm restrictions; handles Doze/force-stop/reboot gracefully.

The P0 scope (Phases 0-6) delivers a production-ready feature in ~7.5 days. The P1 scope (Phases 7-8) adds focus profiles, strict mode, statistics, and backup/restore in ~4 additional days.

**Next step**: Review this plan, then begin Phase 0 (Streak removal) when ready to implement.

---

*End of plan.*
