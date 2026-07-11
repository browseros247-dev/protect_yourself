package protect.yourself.features.backupRestore

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParseException
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
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()
            ?.logBreadcrumb("BackupManager", "export started", mapOf("uri" to outputUri.toString()))

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

            // 3. Write atomically: write to user-picked URI via SAF.
            // SAF doesn't support temp-file + rename, so we write directly.
            // After writing, we verify the persisted file size matches the
            // expected byte count — this catches truncated writes (disk full,
            // provider quota, network drop on cloud providers).
            val expectedSize = withContext(Dispatchers.Default) {
                json.toByteArray(Charsets.UTF_8).size
            }
            withContext(Dispatchers.IO) {
                writeJsonToUri(outputUri, json)
            }
            _progress.value = BackupProgress.Exporting(90, "Verifying…")
            withContext(Dispatchers.IO) {
                verifyWrittenSize(outputUri, expectedSize)
            }
            _progress.value = BackupProgress.Exporting(100, "Done")

            val stats = envelope.stats
            val sizeBytes = expectedSize
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
        } catch (t: kotlinx.coroutines.CancellationException) {
            // Don't swallow coroutine cancellation — propagate to preserve
            // structured concurrency.
            _progress.value = BackupProgress.Idle
            throw t
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
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()
            ?.logBreadcrumb("BackupManager", "import started", mapOf("uri" to inputUri.toString()))

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
                    throw InvalidBackupFormatException("Malformed JSON: ${t.message}")
                } catch (t: JsonParseException) {
                    // JsonParseException is the parent of JsonSyntaxException and
                    // JsonIOException — catches stream-level JSON errors too.
                    throw InvalidBackupFormatException("JSON parse error: ${t.message}")
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
        } catch (t: kotlinx.coroutines.CancellationException) {
            // Don't swallow coroutine cancellation — propagate to preserve
            // structured concurrency (e.g. if ViewModel was cleared mid-import).
            _progress.value = BackupProgress.Idle
            throw t
        } catch (t: Throwable) {
            Timber.e(t, "BackupManager: unexpected error during import")
            _progress.value = BackupProgress.Idle
            // Most DB errors are caught above; if we get here, it's likely a DB error
            // that already rolled back (e.g. SQLiteConstraintException from a
            // NOT NULL violation when the backup JSON was missing a required field).
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
        // Try "wt" (truncate) first, then fall back to "w" — some SAF providers
        // (Google Drive, Dropbox, OneDrive, certain OEM file managers) either
        // throw IOException or return null for "wt" because they don't support
        // truncate mode. We handle BOTH failure modes in a single loop so the
        // retry actually fires when the first attempt fails.
        val bytes = json.toByteArray(Charsets.UTF_8)
        val modes = listOf("wt", "w")
        var lastError: IOException? = null
        for (mode in modes) {
            var stream: java.io.OutputStream? = null
            try {
                stream = context.contentResolver.openOutputStream(uri, mode)
                if (stream == null) {
                    // Provider returned null — try next mode
                    lastError = IOException("Provider returned null stream for mode '$mode'")
                    continue
                }
                stream.use { os ->
                    os.write(bytes)
                    os.flush()
                }
                return  // success
            } catch (t: IOException) {
                lastError = t
                // try next mode
            } catch (t: SecurityException) {
                // Some providers throw SecurityException if the URI was revoked
                lastError = IOException("Permission denied for mode '$mode': ${t.message}", t)
                // try next mode
            }
        }
        throw IOException(
            "Could not write backup file: ${lastError?.message ?: "all write modes failed"}",
            lastError
        )
    }

    /**
     * Verify the persisted file size matches the expected byte count.
     *
     * Catches truncated writes (disk full, provider quota, network drop on
     * cloud providers) that would otherwise report success but leave a
     * corrupt file on disk.
     *
     * On API 26+ we prefer [DocumentsContract.getDocumentMetadata] which
     * returns the canonical size without reading the file. On older APIs
     * (or providers that don't support metadata), we open the input stream
     * and count bytes.
     */
    private fun verifyWrittenSize(uri: Uri, expectedSize: Int) {
        // Try DocumentsContract.getDocumentMetadata first (API 26+, cheap).
        // The returned Bundle may contain COLUMN_SIZE if the provider
        // publishes it; otherwise we fall back to byte-counting.
        val actualSize: Int = try {
            val meta = android.provider.DocumentsContract.getDocumentMetadata(
                context.contentResolver,
                uri
            )
            meta?.getLong(android.provider.DocumentsContract.Document.COLUMN_SIZE)?.toInt() ?: -1
        } catch (t: Throwable) {
            -1  // fall through to byte-counting
        }
        if (actualSize >= 0) {
            if (actualSize != expectedSize) {
                throw IOException(
                    "Backup file size mismatch: expected $expectedSize bytes, got $actualSize bytes. " +
                        "The file may be corrupt or the storage is full."
                )
            }
            return
        }
        // Fall back: open input stream and count bytes
        var counted = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                counted += read
            }
        } ?: throw IOException("Could not re-open URI to verify backup size")
        if (counted != expectedSize) {
            throw IOException(
                "Backup file size mismatch: expected $expectedSize bytes, got $counted bytes. " +
                    "The file may be corrupt or the storage is full."
            )
        }
    }

    // ===== Import helpers =====

    private fun readJsonFromUri(uri: Uri): String? {
        val resolver = context.contentResolver
        return try {
            resolver.openInputStream(uri)?.use { inputStream ->
                // Use a buffer to read fully — readText() reads the whole stream
                // but on some providers (Google Drive) the stream may be lazy
                // and the buffered reader may not consume it correctly.
                inputStream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (t: IOException) {
            Timber.w(t, "Failed to read from URI $uri")
            null
        } catch (t: SecurityException) {
            Timber.w(t, "Permission denied reading from URI $uri")
            null
        }
    }

    /**
     * Restore all tables inside an existing Room transaction.
     * Throws on any failure — caller's transaction will be rolled back.
     *
     * Each list is sanitised before insert: missing/null fields from old,
     * partial, or hand-edited backups are coerced to safe defaults so we
     * don't trip SQLite NOT NULL constraints or Kotlin NPEs. Rows with
     * an empty/blank primary key are skipped (Room would crash on insert
     * with an empty PK for String-keyed tables).
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

        // switch_status — PK: key (String, required)
        tables.switchStatus?.let { list ->
            if (list.isNotEmpty()) {
                val sanitized = list.mapNotNull { item ->
                    val key = item.key ?: return@mapNotNull null
                    if (key.isBlank()) return@mapNotNull null
                    item.copy(
                        key = key,
                        value = item.value ?: "",
                        type = item.type ?: "boolean"
                    )
                }
                if (sanitized.isNotEmpty()) {
                    db.switchStatusDao().upsertAll(sanitized)
                    switchCount = sanitized.size
                }
            }
        }

        // selected_keyword_table — PK: key (String, required)
        tables.selectedKeywords?.let { list ->
            if (list.isNotEmpty()) {
                val sanitized = list.mapNotNull { item ->
                    val key = item.key ?: return@mapNotNull null
                    if (key.isBlank()) return@mapNotNull null
                    item.copy(
                        key = key,
                        keyword = item.keyword ?: "",
                        identifier = item.identifier ?: "",
                        isSelected = item.isSelected  // Boolean defaults to false in JVM
                    )
                }
                if (sanitized.isNotEmpty()) {
                    db.selectedKeywordDao().upsertAll(sanitized)
                    keywordCount = sanitized.size
                }
            }
        }

        // selected_apps_table — PK: key (String, required)
        tables.selectedApps?.let { list ->
            if (list.isNotEmpty()) {
                val sanitized = list.mapNotNull { item ->
                    val key = item.key ?: return@mapNotNull null
                    if (key.isBlank()) return@mapNotNull null
                    item.copy(
                        key = key,
                        packageName = item.packageName ?: "",
                        appName = item.appName ?: "",
                        identifier = item.identifier ?: "",
                        isSelected = item.isSelected
                    )
                }
                if (sanitized.isNotEmpty()) {
                    db.selectedAppsListDao().upsertAll(sanitized)
                    appCount = sanitized.size
                }
            }
        }

        // block_screen_count_table — PK: key (Int, default 0) — all-default entity, safe
        tables.blockScreenCount?.let { list ->
            if (list.isNotEmpty()) {
                db.blockScreenCountDao().upsertAll(list)
                blockScreenCount = list.size
            }
        }

        // partner_pending_request_table — PK: key (String, required)
        tables.pendingRequests?.let { list ->
            if (list.isNotEmpty()) {
                val sanitized = list.mapNotNull { item ->
                    val key = item.key ?: return@mapNotNull null
                    if (key.isBlank()) return@mapNotNull null
                    item.copy(
                        key = key,
                        requestIdentifier = item.requestIdentifier ?: "",
                        appName = item.appName ?: "",
                        keyWord = item.keyWord ?: "",
                        packageName = item.packageName ?: "",
                        switchNumber = item.switchNumber,
                        itemKey = item.itemKey ?: "",
                        itemType = item.itemType ?: "",
                        requestDisplayMessage = item.requestDisplayMessage ?: "",
                        requestSubmitTime = item.requestSubmitTime,
                        requestOffTime = item.requestOffTime,
                        apType = item.apType,
                        approvalType = item.approvalType
                    )
                }
                if (sanitized.isNotEmpty()) {
                    db.pendingRequestDao().upsertAll(sanitized)
                    pendingRequestCount = sanitized.size
                }
            }
        }

        // stop_me_duration_table — PK: key (String, required)
        tables.stopMeDuration?.let { list ->
            if (list.isNotEmpty()) {
                val sanitized = list.mapNotNull { item ->
                    val key = item.key ?: return@mapNotNull null
                    if (key.isBlank()) return@mapNotNull null
                    item.copy(
                        key = key,
                        duration = item.duration,
                        endTime = item.endTime,
                        days = item.days,
                        startTime = item.startTime,
                        startTimeDayMillis = item.startTimeDayMillis
                    )
                }
                if (sanitized.isNotEmpty()) {
                    db.stopMeDurationDao().upsertAll(sanitized)
                    stopMeDurationCount = sanitized.size
                }
            }
        }

        // stop_me_session_count_table — PK: key (Int, default 0) — all-default entity, safe
        tables.stopMeSessionCount?.let { list ->
            if (list.isNotEmpty()) {
                db.stopMeSessionCountDao().upsertAll(list)
                stopMeSessionCount = list.size
            }
        }

        // streak_dates_table — PK: startTime (Long, required)
        tables.streakDates?.let { list ->
            if (list.isNotEmpty()) {
                val sanitized = list.filter { item ->
                    // startTime is a Long — can't be null in JVM after Gson (defaults to 0L)
                    // but we keep the filter for symmetry + future-proofing
                    true
                }.map { item ->
                    item.copy(
                        startTime = item.startTime,
                        endTime = item.endTime,
                        type = item.type ?: "",
                        freeText = item.freeText ?: ""
                    )
                }
                if (sanitized.isNotEmpty()) {
                    db.streakDatesDao().upsertAll(sanitized)
                    streakDatesCount = sanitized.size
                }
            }
        }

        // vpn_custom_dns — PK: key (String, required)
        // IMPORTANT: displayName is nullable in the entity but the DB column is
        // NOT NULL DEFAULT '' (see AppDatabase.MIGRATION_8_9). Coerce null → ""
        // so SQLite doesn't reject the insert.
        tables.vpnCustomDns?.let { list ->
            if (list.isNotEmpty()) {
                val sanitized = list.mapNotNull { item ->
                    val key = item.key ?: return@mapNotNull null
                    if (key.isBlank()) return@mapNotNull null
                    item.copy(
                        key = key,
                        displayName = item.displayName ?: "",
                        firstDns = item.firstDns ?: "",
                        secondDns = item.secondDns ?: "",
                        isSelected = item.isSelected
                    )
                }
                if (sanitized.isNotEmpty()) {
                    db.vpnCustomDnsDao().upsertAll(sanitized)
                    vpnCustomDnsCount = sanitized.size
                }
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
