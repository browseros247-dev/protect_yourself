# VPN DNS Schema Corruption Fix Report — v1.0.57

## Executive Summary

The crash `SQLiteException: table vpn_custom_dns has no column named first_dns`
was caused by a **schema synchronization gap**: the defensive schema repair
(`ensureVpnCustomDnsSchema`) only ran in `onCreate`, which does NOT fire on
database upgrades. Users with old installs (pre-v1.0.49, with snake_case
columns) had corrupt tables that were never repaired, causing the first DAO
query to crash.

The fix adds an **`onOpen` callback** that repairs the schema on every DB
open, plus a new **MIGRATION_9_10** that explicitly handles the snake_case →
camelCase transition. The release APK v1.0.57 builds successfully and all
unit tests pass.

---

## Root Cause Analysis

### The Crash

```
SQLiteException: table vpn_custom_dns has no column named first_dns (code 1 SQLITE_ERROR);
while compiling: INSERT OR IGNORE INTO vpn_custom_dns
(key, display_name, first_dns, second_dns, is_selected) VALUES (?, ?, ?, ?, ?)
```

### NopoX 1.0.53 Reference (Mandatory)

Decompiled `VpnCustomDnsDao_Impl.java` shows NopoX 1.0.53 uses **snake_case**
column names: `first_dns`, `second_dns`, `is_selected`. This is because NopoX
was built with an older Room version that auto-converts camelCase fields to
snake_case columns.

The rebuild uses Room 2.6.1 which generates **camelCase** columns: `firstDns`,
`secondDns`, `isSelected`, `displayName`. The Room schema JSON (`9.json`)
confirms this.

### The Gap

1. **v1.0.49 fix**: Changed all raw SQL from snake_case to camelCase to match
   Room 2.6.1's generated columns. ✓ Correct for fresh installs.

2. **Existing users**: Users with old installs (pre-v1.0.49) had DBs with
   snake_case columns. When they upgraded, `onCreate` did NOT fire (it only
   fires on fresh DB creation). The migration path (MIGRATION_8_9) only
   added `displayName` — it did NOT handle the snake_case → camelCase
   transition for `firstDns`, `secondDns`, `isSelected`.

3. **Defensive repair was in the wrong lifecycle method**: `ensureVpnCustomDnsSchema`
   was called from `onCreate`, which doesn't fire on upgrade. The corrupt
   table persisted across the upgrade.

4. **Result**: The first DAO query that touched `vpn_custom_dns` after upgrade
   crashed with "no column named first_dns" (or "firstDns", depending on
   which SQL ran first).

---

## The Fix

### 1. `onOpen` Callback (CRITICAL)

Added `override fun onOpen(db: SupportSQLiteDatabase)` to `AppDatabaseCallback`.
This runs on **every** DB open — fresh install, upgrade, and normal app launch.
It calls:
- `ensureVpnCustomDnsSchema(db)` — repairs column corruption
- `ensureDnsPresetsExist(db)` — re-inserts presets if table is empty

### 2. MIGRATION_9_10 (New)

Bumped DB version from 9 to 10. Added `MIGRATION_9_10` that calls
`repairVpnCustomDnsSchema` — a comprehensive DROP+CREATE repair that
handles ALL column naming mismatches (not just `displayName`).

### 3. MIGRATION_8_9 Enhanced

MIGRATION_8_9 now also calls `repairVpnCustomDnsSchema` after adding
`displayName`, catching users upgrading from pre-v1.0.49 builds.

### 4. `repairVpnCustomDnsSchema` (New shared method)

Extracted from `ensureVpnCustomDnsSchema` into a `companion object` method
on `AppDatabase` so both the migration and the callback can call it. It:
1. Reads `PRAGMA table_info(vpn_custom_dns)`
2. Compares existing columns against expected camelCase columns
3. If columns are missing, DROP + CREATE with correct schema
4. Re-inserts the 4 default DNS presets

### 5. `ensureDnsPresetsExist` (New method)

Called from `onOpen`. Checks if `vpn_custom_dns` has any rows; if empty,
re-inserts all 4 default presets using `INSERT OR IGNORE`. This handles
the case where old snake_case SQL silently failed to insert presets
(caught by the try/catch in `insertDnsPresets`), leaving the user with
an empty DNS preset list.

---

## Files Changed

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Version bump 1.0.55 → 1.0.57 |
| `app/src/main/java/.../AppDatabase.kt` | Added MIGRATION_9_10, repairVpnCustomDnsSchema, version 10 |
| `app/src/main/java/.../AppDatabaseCallback.kt` | Added onOpen callback, ensureDnsPresetsExist |
| `app/src/test/java/.../AppDatabaseSchemaRepairOnOpenTest.kt` | New: 10 unit tests |
| `apk/protect.yourself-v1.0.57-release.apk` | New release APK |
| `docs/VPN_DNS_SCHEMA_FIX_REPORT.md` | This report |

---

## Testing

### New unit tests: `AppDatabaseSchemaRepairOnOpenTest.kt` (10 tests)

| Test | Description |
|------|-------------|
| `onOpen repair ensures vpn_custom_dns has correct camelCase columns` | Schema verification |
| `onOpen re-inserts DNS presets when table is empty` | Preset re-insertion |
| `onOpen does not duplicate presets when table already has data` | Idempotency |
| `onOpen repair inserts presets with correct values` | Value verification |
| `raw SQL INSERT with camelCase succeeds after onOpen repair` | Positive path |
| `raw SQL INSERT with snake_case fails after onOpen repair` | Regression guard |
| `DAO getAll query succeeds after onOpen repair` | Crash scenario |
| `DAO getSelected query succeeds after onOpen repair` | Crash scenario |
| `DAO observeAll emits non-empty list after onOpen repair` | Flow test |
| `database version is 10` | Version verification |

### Existing tests (no regressions)
- `AppDatabaseSchemaRepairTest` — 9 tests ✓
- All other tests pass

### Release APK
- File: `protect.yourself-v1.0.57-release.apk`
- Size: ~15.4 MB
- Version: 1.0.57 (versionCode 57)
- Build: `./gradlew :app:assembleRelease` — BUILD SUCCESSFUL

---

## Verification Checklist

- [x] `onOpen` callback runs on every DB open (fresh install + upgrade + normal launch)
- [x] `ensureVpnCustomDnsSchema` repairs snake_case → camelCase column corruption
- [x] `ensureDnsPresetsExist` re-inserts presets if table is empty
- [x] MIGRATION_9_10 explicitly handles snake_case → camelCase transition
- [x] MIGRATION_8_9 also calls repairVpnCustomDnsSchema (catches v8 → v9 upgrades)
- [x] `fallbackToDestructiveMigration` remains as last-resort fallback
- [x] All raw SQL uses camelCase column names (matching Room 2.6.1)
- [x] DAO queries succeed after onOpen repair (no more "no column named" crash)
- [x] Presets are not duplicated on repeated DB opens (INSERT OR IGNORE)
- [x] All existing tests pass (no regressions)
- [x] Release APK builds successfully
- [x] Changes are on feature branch, not main
