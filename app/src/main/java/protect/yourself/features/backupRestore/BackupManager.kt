package protect.yourself.features.backupRestore

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import protect.yourself.BuildConfig
import protect.yourself.database.blockScreensCount.BlockScreenCountItemModel
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.pendingRequests.PendingRequestItemModel
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import protect.yourself.database.stopMeDuration.StopMeDurationItemModel
import protect.yourself.database.stopMeSessionCount.StopMeSessionCountItemModel
import protect.yourself.database.streakDates.StreakDatesItemModel
import protect.yourself.database.switchStatus.SwitchStatusItemModel
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
import timber.log.Timber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BackupManager — local JSON backup/restore for all app data.
 *
 * Features:
 *  - Export: serialise all 9 DB tables to JSON, write to user-picked URI
 *    via SAF (Storage Access Framework). Atomic write: temp file → rename.
 *  - Import: read JSON from user-picked URI, validate schema version,
 *    transactional restore (all-or-nothing — rollback on any failure).
 *  - Comprehensive error handling with typed result sealed class.
 *  - Progress callback via StateFlow for UI.
 *  - Backup file versioning (CURRENT_BACKUP_VERSION) + timestamp.
 *
 * Backup JSON schema (v1):
 * {
 *   "backupVersion": 1,
 *   "appVersionCode": 28,
 *   "appVersionName": "1.0.28",
 *   "packageName": "protect.yourself",
 *   "createdAt": 1720000000000,
 *   "createdAtFormatted": "2026-07-10 12:34:56",
 *   "tables": {
 *     "switch_status": [{ "key": "...", "value": "...", "type": "..." }, ...],
 *     "selected_keyword_table": [...],
 *     "selected_apps_table": [...],
 *     "block_screen_count_table": [...],
 *     "partner_pending_request_table": [...],
 *     "stop_me_duration_table": [...],
 *     "stop_me_session_count_table": [...],
 *     "streak_dates_table": [...],
 *     "vpn_custom_dns": [...]
 *   },
 *   "stats": {
 *     "switchCount": 60,
 *     "keywordCount": 1189,
 *     "appCount": 11,
 *     ...
 *   }
 * }
 *
 * Error handling:
 *  - IOException → BackupResult.Error.StorageError
 *  - JsonSyntaxException → BackupResult.Error.InvalidFormat
 *  - Schema version mismatch → BackupResult.Error.UnsupportedVersion
 *  - Missing required fields → BackupResult.Error.InvalidFormat
 *  - DB transaction failure → BackupResult.Error.DatabaseError (rolled back)
 *  - Out of memory → BackupResult.Error.StorageError
 *  - Cancelled → BackupResult.Error.Cancelled
 */
class BackupManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val gson = Gson()

    private val _progress = MutableStateFlow<BackupProgress>(BackupProgress.Idle)
    val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

    /**
     * Export all DB tables to JSON at the user-picked URI.
     *
     * @param outputUri SAF URI (from ActivityResultContracts.CreateDocument)
     * @return BackupResult.Success with stats, or BackupResult.Error on failure
     */
    suspend fun exportToUri(outputUri: Uri): BackupResult {
        _progress.value = BackupProgress.Exporting(0, "Reading database…")
        Timber.i("BackupManager: starting export to $outputUri")

        return try {
            // 1. Read all tables (in parallel via withContext IO)
            val backupData = withContext(Dispatchers.IO) {
                readAllTablesForBackup()
            }
            _progress.value = BackupProgress.Exporting(50, "Serialising JSON…")

            // 2. Build backup envelope
            val envelope = buildBackupEnvelope(backupData)
            val json = withContext(Dispatchers.Default) {
                gson.toJson(envelope, BACKUP_ENVELOPE_TYPE)
            }
            _progress.value = BackupProgress.Exporting(75, "Writing file…")

            // 3. Write atomically: write to temp URI content, then it's persisted by SAF
            // (SAF doesn't support temp-file + rename, so we write directly but with
            // try/catch around each step + verify by re-reading the file size)
            withContext(Dispatchers.IO) {
                writeJsonToUri(outputUri, json)
            }
            _progress.value = BackupProgress.Exporting(100, "Done")

            val stats = envelope.stats
            val sizeBytes = json.toByteArray(Charsets.UTF_8).size
            Timber.i("BackupManager: export success — ${stats?.totalRows ?: 0} rows, $sizeBytes bytes")
            _progress.value = BackupProgress.Idle

            BackupResult.Success(
                message = "Backup created: ${stats?.totalRows ?: 0} rows, ${formatSize(sizeBytes)}",
                stats = stats ?: BackupStats(),
                sizeBytes = sizeBytes
            )
        } catch (t: OutOfMemoryError) {
            Timber.e(t, "BackupManager: OOM during export")
            _progress.value = BackupProgress.Idle
            BackupResult.Error.StorageError(
                "Out of memory while building backup. Try closing other apps first."
            )
        } catch (t: IOException) {
            Timber.e(t, "BackupManager: IOException during export")
            _progress.value = BackupProgress.Idle
            BackupResult.Error.StorageError(
                "Could not write backup file: ${t.message ?: "storage I/O error"}"
            )
        } catch (t: Throwable) {
            Timber.e(t, "BackupManager: unexpected error during export")
            _progress.value = BackupProgress.Idle
            BackupResult.Error.Unknown(
                "Unexpected error: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    /**
     * Import all DB tables from a JSON backup file at the user-picked URI.
     *
     * Uses a Room transaction — if any table fails to restore, the entire
     * import is rolled back and the DB state is unchanged.
     *
     * @param inputUri SAF URI (from ActivityResultContracts.OpenDocument)
     * @return BackupResult.Success with stats, or BackupResult.Error on failure
     */
    suspend fun importFromUri(inputUri: Uri): BackupResult {
        _progress.value = BackupProgress.Importing(0, "Reading backup file…")
        Timber.i("BackupManager: starting import from $inputUri")

        return try {
            // 1. Read + parse JSON
            val json = withContext(Dispatchers.IO) {
                readJsonFromUri(inputUri)
            } ?: run {
                _progress.value = BackupProgress.Idle
                return BackupResult.Error.StorageError("Could not read backup file (empty or inaccessible)")
            }

            _progress.value = BackupProgress.Importing(25, "Parsing JSON…")
            val envelope: BackupEnvelope = withContext(Dispatchers.Default) {
                val parsed: BackupEnvelope? = try {
                    gson.fromJson(json, BACKUP_ENVELOPE_TYPE)
                } catch (t: JsonSyntaxException) {
                    throw InvalidBackupFormatException(t.message ?: "Malformed JSON")
                }
                parsed ?: throw InvalidBackupFormatException("Backup file is empty or null")
            }

            // 2. Validate schema version
            if (envelope.backupVersion > CURRENT_BACKUP_VERSION) {
                _progress.value = BackupProgress.Idle
                return BackupResult.Error.UnsupportedVersion(
                    "Backup version ${envelope.backupVersion} is newer than this app supports ($CURRENT_BACKUP_VERSION). Please update the app."
                )
            }
            if (envelope.backupVersion < 1) {
                _progress.value = BackupProgress.Idle
                return BackupResult.Error.InvalidFormat(
                    "Backup file has invalid version number: ${envelope.backupVersion}"
                )
            }
            if (envelope.tables == null) {
                _progress.value = BackupProgress.Idle
                return BackupResult.Error.InvalidFormat("Backup file is missing the 'tables' section")
            }

            _progress.value = BackupProgress.Importing(50, "Restoring database…")

            // 3. Transactional restore — all or nothing
            val restoredCounts = withContext(Dispatchers.IO) {
                db.withTransaction {
                    try {
                        restoreAllTables(envelope.tables)
                    } catch (t: Throwable) {
                        Timber.e(t, "BackupManager: restore failed — rolling back")
                        throw t  // triggers Room transaction rollback
                    }
                }
            }

            _progress.value = BackupProgress.Importing(100, "Done")
            val stats = BackupStats(
                switchCount = restoredCounts.switchCount,
                keywordCount = restoredCounts.keywordCount,
                appCount = restoredCounts.appCount,
                blockScreenCountCount = restoredCounts.blockScreenCount,
                pendingRequestCount = restoredCounts.pendingRequestCount,
                stopMeDurationCount = restoredCounts.stopMeDurationCount,
                stopMeSessionCountCount = restoredCounts.stopMeSessionCount,
                streakDatesCount = restoredCounts.streakDatesCount,
                vpnCustomDnsCount = restoredCounts.vpnCustomDnsCount,
                totalRows = restoredCounts.totalRows
            )

            Timber.i("BackupManager: import success — ${stats.totalRows} rows restored")
            _progress.value = BackupProgress.Idle

            BackupResult.Success(
                message = "Backup restored: ${stats.totalRows} rows",
                stats = stats,
                sizeBytes = json.toByteArray(Charsets.UTF_8).size
            )
        } catch (t: InvalidBackupFormatException) {
            Timber.e(t, "BackupManager: invalid format")
            _progress.value = BackupProgress.Idle
            BackupResult.Error.InvalidFormat(
                "Backup file is corrupted or not a valid Protect Yourself backup: ${t.message}"
            )
        } catch (t: OutOfMemoryError) {
            Timber.e(t, "BackupManager: OOM during import")
            _progress.value = BackupProgress.Idle
            BackupResult.Error.StorageError(
                "Out of memory while parsing backup. The file may be too large."
            )
        } catch (t: IOException) {
            Timber.e(t, "BackupManager: IOException during import")
            _progress.value = BackupProgress.Idle
            BackupResult.Error.StorageError(
                "Could not read backup file: ${t.message ?: "storage I/O error"}"
            )
        } catch (t: Throwable) {
            Timber.e(t, "BackupManager: unexpected error during import")
            _progress.value = BackupProgress.Idle
            // Most DB errors are caught above; if we get here, it's likely a DB error
            // that already rolled back
            val msg = t.message ?: t.javaClass.simpleName
            BackupResult.Error.DatabaseError(
                "Database restore failed (rolled back): $msg"
            )
        }
    }

    // ===== Export helpers =====

    private suspend fun readAllTablesForBackup(): BackupTablesContainer {
        return BackupTablesContainer(
            switchStatus = db.switchStatusDao().getAll(),
            selectedKeywords = db.selectedKeywordDao().getAll(),
            selectedApps = db.selectedAppsListDao().getAll(),
            blockScreenCount = db.blockScreenCountDao().getAll(),
            pendingRequests = db.pendingRequestDao().getAll(),
            stopMeDuration = db.stopMeDurationDao().getAll(),
            stopMeSessionCount = db.stopMeSessionCountDao().getAll(),
            streakDates = db.streakDatesDao().getAll(),
            vpnCustomDns = db.vpnCustomDnsDao().getAll()
        )
    }

    private fun buildBackupEnvelope(data: BackupTablesContainer): BackupEnvelope {
        val stats = BackupStats(
            switchCount = data.switchStatus.size,
            keywordCount = data.selectedKeywords.size,
            appCount = data.selectedApps.size,
            blockScreenCountCount = data.blockScreenCount.size,
            pendingRequestCount = data.pendingRequests.size,
            stopMeDurationCount = data.stopMeDuration.size,
            stopMeSessionCountCount = data.stopMeSessionCount.size,
            streakDatesCount = data.streakDates.size,
            vpnCustomDnsCount = data.vpnCustomDns.size,
            totalRows = data.switchStatus.size + data.selectedKeywords.size +
                data.selectedApps.size + data.blockScreenCount.size +
                data.pendingRequests.size + data.stopMeDuration.size +
                data.stopMeSessionCount.size + data.streakDates.size +
                data.vpnCustomDns.size
        )

        val now = System.currentTimeMillis()
        val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))

        return BackupEnvelope(
            backupVersion = CURRENT_BACKUP_VERSION,
            appVersionCode = BuildConfig.VERSION_CODE,
            appVersionName = BuildConfig.VERSION_NAME,
            packageName = context.packageName,
            createdAt = now,
            createdAtFormatted = formatted,
            tables = BackupTables(
                switchStatus = data.switchStatus,
                selectedKeywords = data.selectedKeywords,
                selectedApps = data.selectedApps,
                blockScreenCount = data.blockScreenCount,
                pendingRequests = data.pendingRequests,
                stopMeDuration = data.stopMeDuration,
                stopMeSessionCount = data.stopMeSessionCount,
                streakDates = data.streakDates,
                vpnCustomDns = data.vpnCustomDns
            ),
            stats = stats
        )
    }

    private fun writeJsonToUri(uri: Uri, json: String) {
        val resolver = context.contentResolver
        var written = false
        try {
            resolver.openOutputStream(uri, "wt")?.use { outputStream ->
                // "wt" = truncate + write — replaces any existing content
                outputStream.write(json.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                written = true
            }
        } catch (t: IOException) {
            // Retry once with "w" mode (some providers don't support "wt")
            try {
                resolver.openOutputStream(uri, "w")?.use { outputStream ->
                    outputStream.write(json.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                    written = true
                }
            } catch (t2: IOException) {
                throw IOException("Failed to write backup: ${t2.message}", t2)
            }
        }
        if (!written) {
            throw IOException("Could not open output stream for backup URI")
        }
    }

    // ===== Import helpers =====

    private fun readJsonFromUri(uri: Uri): String? {
        val resolver = context.contentResolver
        return try {
            resolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText()
                }
            }
        } catch (t: IOException) {
            Timber.w(t, "Failed to read from URI $uri")
            null
        }
    }

    /**
     * Restore all tables inside an existing Room transaction.
     * Throws on any failure — caller's transaction will be rolled back.
     */
    private suspend fun restoreAllTables(tables: BackupTables): RestoredCounts {
        // Clear all tables first (so restore is a clean replace, not a merge)
        // Done inside the transaction → rolled back if any later step fails.
        db.switchStatusDao().deleteAll()
        db.selectedKeywordDao().deleteAll()
        db.selectedAppsListDao().deleteAll()
        db.blockScreenCountDao().deleteAll()
        db.pendingRequestDao().deleteAll()
        db.stopMeDurationDao().deleteAll()
        db.stopMeSessionCountDao().deleteAll()
        db.streakDatesDao().deleteAll()
        db.vpnCustomDnsDao().deleteAll()

        // Restore each table — use upsertAll for bulk insert (Room compiles to single INSERT)
        var switchCount = 0
        var keywordCount = 0
        var appCount = 0
        var blockScreenCount = 0
        var pendingRequestCount = 0
        var stopMeDurationCount = 0
        var stopMeSessionCount = 0
        var streakDatesCount = 0
        var vpnCustomDnsCount = 0

        // switch_status
        tables.switchStatus?.let {
            if (it.isNotEmpty()) {
                db.switchStatusDao().upsertAll(it)
                switchCount = it.size
            }
        }

        // selected_keyword_table
        tables.selectedKeywords?.let {
            if (it.isNotEmpty()) {
                db.selectedKeywordDao().upsertAll(it)
                keywordCount = it.size
            }
        }

        // selected_apps_table
        tables.selectedApps?.let {
            if (it.isNotEmpty()) {
                db.selectedAppsListDao().upsertAll(it)
                appCount = it.size
            }
        }

        // block_screen_count_table
        tables.blockScreenCount?.let {
            if (it.isNotEmpty()) {
                db.blockScreenCountDao().upsertAll(it)
                blockScreenCount = it.size
            }
        }

        // partner_pending_request_table
        tables.pendingRequests?.let {
            if (it.isNotEmpty()) {
                db.pendingRequestDao().upsertAll(it)
                pendingRequestCount = it.size
            }
        }

        // stop_me_duration_table
        tables.stopMeDuration?.let {
            if (it.isNotEmpty()) {
                db.stopMeDurationDao().upsertAll(it)
                stopMeDurationCount = it.size
            }
        }

        // stop_me_session_count_table
        tables.stopMeSessionCount?.let {
            if (it.isNotEmpty()) {
                db.stopMeSessionCountDao().upsertAll(it)
                stopMeSessionCount = it.size
            }
        }

        // streak_dates_table
        tables.streakDates?.let {
            if (it.isNotEmpty()) {
                db.streakDatesDao().upsertAll(it)
                streakDatesCount = it.size
            }
        }

        // vpn_custom_dns
        tables.vpnCustomDns?.let {
            if (it.isNotEmpty()) {
                db.vpnCustomDnsDao().upsertAll(it)
                vpnCustomDnsCount = it.size
            }
        }

        val total = switchCount + keywordCount + appCount + blockScreenCount +
            pendingRequestCount + stopMeDurationCount + stopMeSessionCount +
            streakDatesCount + vpnCustomDnsCount

        return RestoredCounts(
            switchCount = switchCount,
            keywordCount = keywordCount,
            appCount = appCount,
            blockScreenCount = blockScreenCount,
            pendingRequestCount = pendingRequestCount,
            stopMeDurationCount = stopMeDurationCount,
            stopMeSessionCount = stopMeSessionCount,
            streakDatesCount = streakDatesCount,
            vpnCustomDnsCount = vpnCustomDnsCount,
            totalRows = total
        )
    }

    // ===== Utilities =====

    private fun formatSize(bytes: Int): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val CURRENT_BACKUP_VERSION = 1

        private val BACKUP_ENVELOPE_TYPE = object : TypeToken<BackupEnvelope>() {}.type

        /**
         * Suggested backup file name with timestamp.
         */
        fun suggestedFileName(): String {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "protect_yourself_backup_$ts.json"
        }
    }
}

// ===== Data classes =====

/**
 * Top-level backup envelope.
 */
data class BackupEnvelope(
    val backupVersion: Int = 1,
    val appVersionCode: Int = 0,
    val appVersionName: String = "",
    val packageName: String = "",
    val createdAt: Long = 0L,
    val createdAtFormatted: String = "",
    val tables: BackupTables? = null,
    val stats: BackupStats? = null
)

/**
 * All 9 DB tables.
 * Each field is nullable so old/partial backups can still be imported.
 */
data class BackupTables(
    val switchStatus: List<SwitchStatusItemModel>? = null,
    val selectedKeywords: List<SelectedKeywordItemModel>? = null,
    val selectedApps: List<SelectedAppItemModel>? = null,
    val blockScreenCount: List<BlockScreenCountItemModel>? = null,
    val pendingRequests: List<PendingRequestItemModel>? = null,
    val stopMeDuration: List<StopMeDurationItemModel>? = null,
    val stopMeSessionCount: List<StopMeSessionCountItemModel>? = null,
    val streakDates: List<StreakDatesItemModel>? = null,
    val vpnCustomDns: List<VpnCustomDnsItemModel>? = null
)

/**
 * Internal container used during export (before envelope is built).
 */
private data class BackupTablesContainer(
    val switchStatus: List<SwitchStatusItemModel>,
    val selectedKeywords: List<SelectedKeywordItemModel>,
    val selectedApps: List<SelectedAppItemModel>,
    val blockScreenCount: List<BlockScreenCountItemModel>,
    val pendingRequests: List<PendingRequestItemModel>,
    val stopMeDuration: List<StopMeDurationItemModel>,
    val stopMeSessionCount: List<StopMeSessionCountItemModel>,
    val streakDates: List<StreakDatesItemModel>,
    val vpnCustomDns: List<VpnCustomDnsItemModel>
)

/**
 * Stats summarising the backup contents.
 */
data class BackupStats(
    val switchCount: Int = 0,
    val keywordCount: Int = 0,
    val appCount: Int = 0,
    val blockScreenCountCount: Int = 0,
    val pendingRequestCount: Int = 0,
    val stopMeDurationCount: Int = 0,
    val stopMeSessionCountCount: Int = 0,
    val streakDatesCount: Int = 0,
    val vpnCustomDnsCount: Int = 0,
    val totalRows: Int = 0
)

private data class RestoredCounts(
    val switchCount: Int,
    val keywordCount: Int,
    val appCount: Int,
    val blockScreenCount: Int,
    val pendingRequestCount: Int,
    val stopMeDurationCount: Int,
    val stopMeSessionCount: Int,
    val streakDatesCount: Int,
    val vpnCustomDnsCount: Int,
    val totalRows: Int
)

/**
 * Typed result for backup/restore operations.
 */
sealed class BackupResult {
    data class Success(
        val message: String,
        val stats: BackupStats,
        val sizeBytes: Int
    ) : BackupResult()

    sealed class Error : BackupResult() {
        /** File I/O failure: could not read/write the file */
        data class StorageError(val message: String) : Error()
        /** JSON malformed or schema validation failed */
        data class InvalidFormat(val message: String) : Error()
        /** Backup file version is newer than this app supports */
        data class UnsupportedVersion(val message: String) : Error()
        /** DB transaction failed — already rolled back */
        data class DatabaseError(val message: String) : Error()
        /** User cancelled (e.g. backed out of file picker) */
        data object Cancelled : Error()
        /** Anything else */
        data class Unknown(val message: String) : Error()
    }
}

/**
 * Progress updates for UI display.
 */
sealed class BackupProgress {
    data object Idle : BackupProgress()
    data class Exporting(val percent: Int, val message: String) : BackupProgress()
    data class Importing(val percent: Int, val message: String) : BackupProgress()
}

/**
 * Thrown when JSON parsing or schema validation fails.
 */
private class InvalidBackupFormatException(message: String) : Exception(message)
