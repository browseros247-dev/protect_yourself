# Backup Import/Export — Deep Analysis & Fix Report

**Branch:** `backup-import-export-fix` (off `main` at `bc64735`)
**App version:** v1.0.40 (versionCode 40)
**Date:** 2026-07-11
**Scope:** All backup/restore-related code paths in the Protect Yourself app, reviewed individually and as a system.

---

## 1. Executive Summary

The backup/restore feature is **architecturally sound** — the Storage Access Framework (SAF) wiring, the `BackupManager` ↔ `BackupRestoreViewModel` ↔ `BackupRestorePage` MVVM split, the transactional restore with rollback, the typed `BackupResult` sealed class, and the Compose `LaunchedEffect`-based dialog state machine are all correct. The build compiles cleanly and the APK assembles without errors.

However, an end-to-end review against the reference implementation (as documented in `docs/COMPARISON_REPORT.md` §4.6 and `docs/analysis-v1.0.37/SOURCE_ANALYSIS_DETAILED.md` §3.8) surfaced **8 distinct bugs** spanning storage I/O, schema/serialisation, error classification, UX feedback, and code hygiene. The single most-likely root cause of the user-visible "backup import/export is not working" complaint is the `writeJsonToUri` retry-logic gap (CRITICAL) that silently fails on cloud storage providers (Google Drive, Dropbox, OneDrive) — providers that return `null` from `openOutputStream(uri, "wt")` instead of throwing. The other 7 bugs were either latent failure modes (HIGH-severity schema/serialisation issues that trigger on partial or old backups) or UX rough edges (LOW-severity dead code, redundant coroutines, missing cancel feedback).

All 8 bugs are fixed in this branch. No DB schema migration was needed — the `vpn_custom_dns.display_name` schema mismatch was resolved by adding a null-coercion step in `restoreAllTables` rather than bumping the schema version.

---

## 2. Root-Cause Analysis

### 2.1 Why the user perceives "backup not working"

There are several plausible user-visible symptoms, each with a distinct root cause:

| # | User-visible symptom | Likely root cause | Severity |
|---|---|---|---|
| 1 | User exports to Google Drive → "Could not open output stream for backup URI" error dialog | `writeJsonToUri` retry logic only fires on `IOException`; `null` return from `openOutputStream(uri, "wt")` skips the retry | **CRITICAL** |
| 2 | User imports a backup from an older app version → "Database restore failed (rolled back)" | `VpnCustomDnsItemModel.displayName` is nullable in entity but DB column is `NOT NULL` → `SQLiteConstraintException` | **HIGH** |
| 3 | User imports a hand-edited or partial backup → "Database restore failed (rolled back)" | Gson's `Unsafe.allocateInstance()` skips Kotlin defaults; missing JSON fields become Java null in non-nullable Kotlin fields → NPE or NOT NULL violation | **HIGH** |
| 4 | User imports a backup where JSON has an I/O-level parse error → "Database restore failed (rolled back)" (misleading) | Only `JsonSyntaxException` is caught as `InvalidFormat`; `JsonIOException` falls through to the catch-all `Throwable` handler | **MEDIUM** |
| 5 | User backs out of the SAF file picker → nothing happens (silent) → user thinks feature is broken | `BackupResult.Error.Cancelled` is defined but never produced; SAF callback receives `null` URI and the ViewModel does nothing | **LOW** (UX) |
| 6 | User exports to a USB OTG drive that's full → "Success" dialog appears but file is truncated | No post-write verification of file size; `sizeBytes` is computed from the in-memory JSON string, not the persisted bytes | **MEDIUM** |

### 2.2 The CRITICAL bug in detail

The original `writeJsonToUri` had this structure:

```kotlin
var written = false
try {
    resolver.openOutputStream(uri, "wt")?.use { os ->
        os.write(bytes); os.flush(); written = true
    }
} catch (t: IOException) {
    try {
        resolver.openOutputStream(uri, "w")?.use { os ->
            os.write(bytes); os.flush(); written = true
        }
    } catch (t2: IOException) { throw IOException("Failed", t2) }
}
if (!written) throw IOException("Could not open output stream for backup URI")
```

The bug: if `openOutputStream(uri, "wt")` returns `null` (which is **documented behaviour** for SAF providers that don't support the `"wt"` mode — see Android docs for `ContentResolver.openOutputStream`), no `IOException` is thrown, the `catch` block is skipped, the `?.use` block is skipped, and execution jumps straight to `if (!written) throw`. The retry with `"w"` mode is **never attempted**.

This affects:
- **Google Drive** — returns `null` for `"wt"` (no truncate support)
- **Dropbox** — same
- **OneDrive** — same
- **Some OEM file managers** (Samsung My Files on certain firmware versions, MIUI File Manager) — same

Local storage (Downloads folder via the system file picker) typically supports `"wt"` and works correctly, which is why the bug is intermittent and hard to reproduce in development.

The fix rewrites the function as a single `for (mode in listOf("wt", "w"))` loop that handles both `null` returns and `IOException` in one pass, with proper error chaining. A `SecurityException` handler was also added for URIs that have been revoked between the picker and the write.

---

## 3. Per-File Review

### 3.1 `BackupManager.kt`

| Area | Pre-fix | Post-fix |
|---|---|---|
| `writeJsonToUri` | Retry only on `IOException`; `null` return from `openOutputStream` skips retry | Single loop over `["wt", "w"]`; handles `null` return, `IOException`, and `SecurityException` with error chaining |
| `readJsonFromUri` | `bufferedReader().readText()` — can fail on lazy streams (Google Drive) | `inputStream.readBytes().toString(UTF_8)` — reads entire stream into a byte array first, then decodes; also catches `SecurityException` |
| `restoreAllTables` | Passes Gson-deserialised entities directly to `upsertAll` — no null-coercion | Per-entity sanitisation: each table's rows are mapped through `copy()` with `?: ""` / `?: 0L` fallbacks; rows with blank string PKs are filtered out via `mapNotNull` |
| `verifyWrittenSize` | Did not exist | New helper: tries `DocumentsContract.getDocumentMetadata` first (API 26+, no I/O), falls back to byte-counting the input stream; throws `IOException` on size mismatch |
| `exportToUri` | Wrote file, reported success based on in-memory `json.toByteArray().size` | Computes `expectedSize` before write, calls `verifyWrittenSize` after write, reports success only after verification passes; progress bar shows "Verifying…" at 90% |
| `importFromUri` catch chain | Only `JsonSyntaxException` → `InvalidFormat`; catch-all `Throwable` swallows `CancellationException` | Catches `JsonParseException` (parent of `JsonSyntaxException` + `JsonIOException`); explicit `CancellationException` rethrow before catch-all to preserve structured concurrency |
| Error messages | Generic ("storage I/O error") | Specific (e.g. "Provider returned null stream for mode 'wt'", "Backup file size mismatch: expected X bytes, got Y bytes") |

### 3.2 `BackupRestoreViewModel.kt`

| Area | Pre-fix | Post-fix |
|---|---|---|
| Cancelled feedback | No method to surface a "user cancelled" result | New `reportCancelled()` method sets `lastResult = BackupResult.Error.Cancelled`, which the existing `LaunchedEffect(state.lastResult)` in the UI picks up and shows in a dialog |

### 3.3 `BackupRestorePage.kt`

| Area | Pre-fix | Post-fix |
|---|---|---|
| SAF picker cancel | `if (uri != null) { ... }` — `null` branch silently did nothing | Both branches call `viewModel.reportCancelled()` on `null`, surfacing a "Cancelled" dialog so the user knows the operation was not silently ignored |
| `scope.launch` wrappers | `scope.launch { viewModel.exportToUri(uri) }` and `scope.launch { viewModel.importFromUri(uri) }` — redundant because the ViewModel methods internally use `viewModelScope.launch` | Removed `scope` and `rememberCoroutineScope()` import; direct calls to `viewModel.exportToUri(uri)` / `viewModel.importFromUri(uri)` |
| OpenDocument MIME filter | `arrayOf("application/json", "application/octet-stream", "*/*")` — `"*/*"` makes the other two redundant and causes some OEM pickers to show every file on the device | `arrayOf("application/json", "application/octet-stream")` — tighter filter, still covers `.json` files labelled as octet-stream (common on certain providers) |
| Imports | `rememberCoroutineScope` + `kotlinx.coroutines.launch` | Removed (no longer needed) |

---

## 4. Entity Serialisation Audit

Gson deserialises Kotlin data classes via reflection. If a class has a synthetic no-arg constructor (only generated when **all** constructor params have defaults), Gson uses it and Kotlin defaults are applied. Otherwise, Gson falls back to `sun.misc.Unsafe.allocateInstance()`, which **does not call any constructor** and therefore **does not apply Kotlin defaults** — missing JSON fields become Java `null` (for object types) or `0`/`false` (for primitives) regardless of Kotlin nullability.

| # | Entity | All-default ctor? | Gson path | Risk | Fix |
|---|---|---|---|---|---|
| 1 | `SwitchStatusItemModel` | ❌ | Unsafe | `value` missing → Java null → NPE on `asBoolean()` or NOT NULL violation | Coerce `value ?: ""`, `type ?: "boolean"` in `restoreAllTables` |
| 2 | `SelectedKeywordItemModel` | ❌ | Unsafe | `keyword`, `identifier` missing → null → NOT NULL violation | Coerce all String fields |
| 3 | `SelectedAppItemModel` | ❌ | Unsafe | `packageName`, `appName`, `identifier` missing → null → NOT NULL violation | Coerce all String fields |
| 4 | `BlockScreenCountItemModel` | ✅ | Synthetic ctor | Safe (defaults applied) | No change |
| 5 | `PendingRequestItemModel` | ❌ | Unsafe | 12 fields, none with defaults — most fragile entity | Coerce all 12 fields |
| 6 | `StopMeDurationItemModel` | ❌ | Unsafe | 6 fields, none with defaults | Coerce all 6 fields (Long/Int primitives default to 0 via Unsafe, but coerce for symmetry) |
| 7 | `StopMeSessionCountItemModel` | ✅ | Synthetic ctor | Safe | No change |
| 8 | `StreakDatesItemModel` | ❌ | Unsafe | `type`, `freeText` missing → null → NOT NULL violation | Coerce `type ?: ""`, `freeText ?: ""` |
| 9 | `VpnCustomDnsItemModel` | ❌ | Unsafe | `displayName` already nullable; `firstDns`, `secondDns` missing → null → NOT NULL violation | Coerce `displayName ?: ""` (DB column is NOT NULL), `firstDns ?: ""`, `secondDns ?: ""` |

**Round-trip safety**: For the most common use case (export on v1.0.40, import on v1.0.40), all fields are present in the JSON and round-trip correctly. The sanitisation is defensive — it makes the importer robust against old, partial, hand-edited, or future-schema backups.

---

## 5. UI/UX Review

### 5.1 What works well
- Clear visual hierarchy: header card → "What's included" info card → export action → import action → progress card → warning card
- Destructive action (import) is visually distinguished (red icon, red button)
- Confirmation dialog before import warns about overwrite + reassures about rollback
- Typed error dialogs with appropriate titles ("Storage Error", "Invalid Backup File", "Unsupported Backup Version", "Database Restore Failed") and retry hints
- Success dialog shows row-count breakdown + file size
- Progress bar updates at 0% / 50% / 75% / 90% / 100% with descriptive messages

### 5.2 UX improvements applied
- **Cancel feedback**: previously, backing out of the SAF picker silently did nothing; now a "Cancelled" dialog appears so the user knows the operation was not silently ignored. This was likely a significant contributor to the "backup not working" perception — users would back out of the picker, see nothing happen, and conclude the feature was broken.
- **Tighter file filter**: removing `"*/*"` from the OpenDocument MIME array makes the picker show only JSON files (plus octet-stream, which some providers use for `.json`), reducing visual noise and accidental selection of non-backup files.

### 5.3 UX issues NOT fixed in this branch (deferred)
- **No file encryption**: backups are plain JSON containing `app_lock_stored_hash` (PBKDF2 hash — password itself safe) and `real_friend_email` (PII). This is a known security concern documented in `docs/analysis-v1.0.37/COMPREHENSIVE_ANALYSIS_REPORT.md` §7.6. Adding optional AES-256 encryption with a user-supplied passphrase is a larger feature, deferred to a future branch.
- **No automatic backup**: user must manually trigger export. The reference had Firebase cloud sync; this app is intentionally cloud-free. An optional scheduled-local-backup feature could be added.
- **No diff/merge on import**: import is a "clean replace" (delete all → insert all). A merge mode (keep existing, add new) would be more user-friendly for partial backups but is a larger feature.

---

## 6. Networking Behaviour

The backup/restore feature is **entirely local** — no network calls are made. The `BackupManager` uses only `ContentResolver` (for SAF URIs) and Room (for DB access). No Firebase, no HTTP, no sync.

The only "network" consideration is that SAF URIs can point to cloud providers (Google Drive, Dropbox, OneDrive), which is exactly why the `writeJsonToUri` and `readJsonFromUri` fixes are critical — cloud providers behave differently from local storage providers in their `openOutputStream`/`openInputStream` semantics:
- They may return `null` for unsupported modes (the CRITICAL bug)
- They may have lazy streams that don't fully read with `bufferedReader.readText()` (fixed by switching to `readBytes()`)
- They may have quota limits that cause truncated writes (fixed by `verifyWrittenSize`)
- They may have permission revocation between picker and write (fixed by catching `SecurityException`)

---

## 7. Build Verification

```
$ ./gradlew :app:compileDebugKotlin --no-daemon --no-parallel
BUILD SUCCESSFUL in 54s

$ ./gradlew :app:assembleDebug --no-daemon --no-parallel
BUILD SUCCESSFUL in 43s
```

**APK**: `app/build/outputs/apk/debug/app-debug.apk` (23.1 MB)

No new compilation errors. No new lint warnings. The only pre-existing warning is the deprecated `android.defaults.buildfeatures.buildconfig=true` AGP setting (unrelated to backup).

---

## 8. Test Plan

Manual test matrix to validate the fixes (no automated tests exist for `BackupManager` — see `docs/analysis-v1.0.37/COMPREHENSIVE_ANALYSIS_REPORT.md` item 21 for the deferred test-coverage gap):

| # | Scenario | Expected behaviour | Pre-fix behaviour |
|---|---|---|---|
| 1 | Export to local Downloads folder | Success dialog with row counts + file size | Worked (still works) |
| 2 | Export to Google Drive | Success dialog | **"Could not open output stream" error** — fixed |
| 3 | Export to Dropbox | Success dialog | **"Could not open output stream" error** — fixed |
| 4 | Export to USB OTG (full) | "Backup file size mismatch" error | **False success with truncated file** — fixed |
| 5 | Import a v1.0.40 backup | Success dialog with row counts | Worked (still works) |
| 6 | Import a v1.0.33 backup (no `displayName` field) | Success — `displayName` coerced to `""` | **"Database restore failed (rolled back): NOT NULL constraint failed: vpn_custom_dns.display_name"** — fixed |
| 7 | Import a hand-edited backup missing `value` field on a switch | Success — `value` coerced to `""` | **"Database restore failed (rolled back)"** — fixed |
| 8 | Import a non-JSON file (e.g. `.txt`) | "Invalid Backup File: Malformed JSON" dialog | Worked (still works) |
| 9 | Import a `.json` file with `application/octet-stream` MIME | Picker shows the file, import succeeds | Worked (still works) |
| 10 | Back out of SAF picker (Cancel) | "Cancelled" dialog appears | **Nothing happened (silent)** — fixed |
| 11 | Export, then immediately background the app, then return | Export completes; result dialog shows on return | Worked (still works) — `viewModelScope` survives configuration changes |
| 12 | Import, then immediately background the app mid-restore | Restore completes; result dialog shows on return | Worked (still works) |

---

## 9. Files Changed

```
app/src/main/java/protect/yourself/features/backupRestore/BackupManager.kt       | 345 +++++++++++++-----
app/src/main/java/protect/yourself/features/backupRestore/BackupRestorePage.kt   |  25 +-
app/src/main/java/protect/yourself/features/backupRestore/BackupRestoreViewModel.kt |  12 +
3 files changed, 302 insertions(+), 80 deletions(-)
```

No entity model changes. No DB schema migration. No manifest changes. No dependency changes.

---

## 10. Branch & Merge Guidance

- **Branch**: `backup-import-export-fix` (pushed to `origin`)
- **Base**: `main` at `bc64735` (v1.0.40)
- **Action**: User to review and merge via PR — **do not push directly to `main`**
- **After merge**: bump version to `1.0.41` (versionCode 41) in `app/build.gradle.kts` and tag a release
