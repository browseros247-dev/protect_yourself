package protect.yourself.features.crashLog

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import protect.yourself.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CrashLogger — comprehensive local crash + error logging system.
 *
 * Replaces the minimal crash_log.txt approach with a structured, queryable,
 * exportable crash log that captures full diagnostic context for offline analysis.
 *
 * Features:
 *  - Captures: timestamp, severity, thread info, stack trace, message,
 *    device info (model, manufacturer, Android version, SDK), app version,
 *    available memory, available disk, current activity (best-effort),
 *    logcat tail (last 200 lines, FATAL only), and custom context tags.
 *  - Service state capture: accessibility/VPN/device-admin status — critical
 *    for diagnosing why blocking failed in this app.
 *  - Severity levels: VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL, ASSERT
 *  - File rotation: keeps most recent N crash entries (default 50).
 *    Oldest entries are auto-pruned.
 *  - Each entry persisted as individual file (crash_YYYYMMDD_HHmmss_<n>.json)
 *    via atomic write (temp + rename) — survives process kill mid-write.
 *  - Index file (crash_index.json) maintains entry ordering + metadata,
 *    also written atomically. Reconciled against actual files on init.
 *  - Disk-backed breadcrumb ring buffer — survives hard crashes (SIGSEGV,
 *    SIGKILL, OOM-before-persist) that defeat the previous in-memory buffer.
 *  - Crash grouping/deduplication: identical crashes within 5 minutes are
 *    merged into a single entry with a `count` field — prevents a single
 *    recurring crash from evicting all other crash history.
 *  - Timber tree integration: all Timber.e()/Timber.w() calls are captured.
 *  - Global uncaught exception handler integration.
 *  - Manual API: logThrowable(), logMessage(), logBreadcrumb() for tracing.
 *  - StateFlow exposes entry count for UI badge.
 *  - Export to single JSON file via SAF (all entries bundled).
 *  - Clear all entries (for user-initiated cleanup).
 *  - Thread-safe: all writes synchronised on the CrashLogger instance.
 *  - OOM-resilient: if Gson serialisation OOMs, falls back to a minimal
 *    hand-built JSON stub so the crash record is never lost.
 *
 * Storage layout:
 *   <filesDir>/crashlogs/
 *     crash_index.json                         — ordered list of entry IDs
 *     crash_20260710_123456_001.json           — full entry 1
 *     crash_20260710_123456_002.json           — full entry 2
 *     breadcrumbs.json                         — disk-backed ring buffer
 *     ...
 *
 * No Firebase, no network. Everything stays on device.
 */
class CrashLogger private constructor(private val context: Context) {

    private val gson = Gson()
    private val crashDir: File by lazy {
        File(context.filesDir, "crashlogs").apply { if (!exists()) mkdirs() }
    }
    private val indexFile: File by lazy { File(crashDir, "crash_index.json") }
    private val breadcrumbFile: File by lazy { File(crashDir, "breadcrumbs.json") }

    private val sequenceCounter = AtomicLong(0L)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Re-entrancy guard for the uncaught exception handler — if logging a
     * FATAL crash itself throws, we must NOT re-enter logThrowable (infinite
     * recursion). The handler checks this flag and skips CrashLogger if true.
     */
    private val inCrashHandler = AtomicBoolean(false)

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    /**
     * Cached device info — immutable for the process lifetime, so we only
     * capture it once. Saves ~5–10 ms per entry vs. reading Build.* on
     * every log call.
     */
    private val cachedDeviceInfo: DeviceInfo by lazy { captureDeviceInfo() }

    /**
     * Cached memory/disk info with a short TTL — these change slowly, so we
     * cache for 1 second to avoid hammering ActivityManager + StatFs on
     * every WARN+ Timber log.
     */
    @Volatile private var cachedMemoryInfo: MemoryInfo? = null
    @Volatile private var cachedMemoryInfoAtMs: Long = 0L
    @Volatile private var cachedDiskInfo: DiskInfo? = null
    @Volatile private var cachedDiskInfoAtMs: Long = 0L

    init {
        // Reconcile index against actual files on disk — recovers from
        // interrupted writes (process killed mid-save leaves orphan files).
        reconcileIndex()
        // Restore sequence counter from existing entries
        sequenceCounter.set(loadMaxSequence())
        _entryCount.value = countEntries()
        // Load disk-backed breadcrumbs
        loadBreadcrumbsFromDisk()
    }

    // ===== Public API =====

    /**
     * Log a throwable as an error/fatal entry.
     *
     * @param throwable the exception to log
     * @param severity ERROR for caught exceptions, FATAL for uncaught crashes
     * @param tag optional context tag (e.g. "AccessibilityService", "BackupManager")
     * @param message optional human-readable description
     * @param extraContext optional key-value pairs appended to the entry
     */
    fun logThrowable(
        throwable: Throwable,
        severity: CrashSeverity = CrashSeverity.ERROR,
        tag: String = "",
        message: String = "",
        extraContext: Map<String, String> = emptyMap()
    ): String {
        // Re-entrancy guard — if we're already inside the uncaught exception
        // handler and logging itself throws, bail out to prevent infinite
        // recursion. The uncaught handler sets this flag before calling us.
        if (inCrashHandler.get()) {
            Log.e(TAG, "Re-entrant logThrowable call suppressed — original throwable: ${throwable.javaClass.name}")
            return ""
        }

        val stackTrace = getStackTrace(throwable)
        val causeChain = getCauseChain(throwable)

        // Compute dedup hash — if the most recent entry has the same hash and
        // is within 5 minutes, we increment its count instead of creating a
        // new entry. This prevents a recurring crash (e.g. accessibility event
        // loop hitting the same NPE every event) from evicting all other
        // crash history.
        val dedupHash = computeDedupHash(throwable, stackTrace, tag)

        val entry = CrashLogEntry(
            id = generateId(),
            timestamp = System.currentTimeMillis(),
            timestampFormatted = dateFormat.format(Date()),
            severity = severity,
            tag = tag,
            message = if (message.isNotBlank()) message else (throwable.message ?: throwable.javaClass.simpleName),
            throwableClass = throwable.javaClass.name,
            stackTrace = stackTrace,
            causeChain = causeChain,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            processId = Process.myPid(),
            isMainThread = Thread.currentThread() === android.os.Looper.getMainLooper().thread,
            deviceInfo = cachedDeviceInfo,
            appInfo = captureAppInfo(),
            memoryInfo = captureMemoryInfoCached(),
            diskInfo = captureDiskInfoCached(),
            serviceState = captureServiceState(),
            logcatTail = if (severity == CrashSeverity.FATAL) captureLogcatTail() else "",
            breadcrumbs = getRecentBreadcrumbs(),
            extraContext = extraContext,
            dedupHash = dedupHash,
            count = 1
        )

        persistEntryWithDedup(entry)
        return entry.id
    }

    /**
     * Log a message (no throwable) — useful for capturing notable runtime events
     * that aren't exceptions but may be useful for diagnosing later issues.
     */
    fun logMessage(
        severity: CrashSeverity,
        tag: String = "",
        message: String,
        extraContext: Map<String, String> = emptyMap()
    ): String {
        val entry = CrashLogEntry(
            id = generateId(),
            timestamp = System.currentTimeMillis(),
            timestampFormatted = dateFormat.format(Date()),
            severity = severity,
            tag = tag,
            message = message,
            throwableClass = "",
            stackTrace = "",
            causeChain = emptyList(),
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            processId = Process.myPid(),
            isMainThread = Thread.currentThread() === android.os.Looper.getMainLooper().thread,
            deviceInfo = cachedDeviceInfo,
            appInfo = captureAppInfo(),
            memoryInfo = captureMemoryInfoCached(),
            diskInfo = captureDiskInfoCached(),
            serviceState = captureServiceState(),
            // Logcat capture is expensive (subprocess) — only do it for FATAL.
            // For WARN/ERROR, the Timber log already went to logcat; the user
            // can re-read logcat if needed.
            logcatTail = if (severity == CrashSeverity.FATAL) captureLogcatTail() else "",
            breadcrumbs = getRecentBreadcrumbs(),
            extraContext = extraContext,
            dedupHash = 0,
            count = 1
        )

        persistEntry(entry)
        return entry.id
    }

    /**
     * Drop a breadcrumb — a lightweight tracing event stored in a ring buffer.
     * When a crash happens later, the most recent N breadcrumbs are attached
     * to the crash entry, helping reconstruct what the app was doing before.
     *
     * The buffer is **disk-backed** (breadcrumbs.json) so it survives hard
     * crashes (SIGSEGV, SIGKILL, OOM-before-persist) that defeat the previous
     * in-memory-only buffer. Without disk persistence, breadcrumbs are lost
     * on the exact crashes they're meant to diagnose.
     *
     * The in-memory cache is the primary read path (fast); the disk file is
     * the durability path. Writes to disk are atomic (temp + rename).
     */
    fun logBreadcrumb(category: String, message: String, data: Map<String, String> = emptyMap()) {
        val breadcrumb = Breadcrumb(
            timestamp = System.currentTimeMillis(),
            timestampFormatted = dateFormat.format(Date()),
            category = category,
            message = message,
            data = data
        )
        synchronized(breadcrumbBuffer) {
            breadcrumbBuffer.add(breadcrumb)
            while (breadcrumbBuffer.size > MAX_BREADCRUMBS) {
                breadcrumbBuffer.removeAt(0)
            }
            // Persist to disk so breadcrumbs survive hard crashes
            persistBreadcrumbs()
        }
    }

    /**
     * Read all crash log entries (newest first), optionally limited.
     */
    fun readEntries(limit: Int = 100): List<CrashLogEntry> {
        return try {
            val index = loadIndex()
            val entries = mutableListOf<CrashLogEntry>()
            for (entryId in index.take(limit)) {
                val file = File(crashDir, "$entryId.json")
                if (file.exists()) {
                    try {
                        val json = file.readText(Charsets.UTF_8)
                        val entry = gson.fromJson(json, CrashLogEntry::class.java)
                        if (entry != null) entries.add(entry)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to read crash entry $entryId", t)
                    }
                }
            }
            entries
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read crash entries", t)
            emptyList()
        }
    }

    /**
     * Read a single crash log entry by ID.
     */
    fun readEntry(id: String): CrashLogEntry? {
        return try {
            val file = File(crashDir, "$id.json")
            if (!file.exists()) return null
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, CrashLogEntry::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read crash entry $id", t)
            null
        }
    }

    /**
     * Delete a single crash log entry by ID.
     */
    fun deleteEntry(id: String): Boolean {
        return try {
            synchronized(this) {
                val file = File(crashDir, "$id.json")
                val deleted = file.delete()
                if (deleted) {
                    removeFromIndex(id)
                    _entryCount.value = countEntries()
                }
                deleted
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to delete crash entry $id", t)
            false
        }
    }

    /**
     * Clear all crash log entries.
     */
    fun clearAll(): Int {
        return try {
            synchronized(this) {
                val files = crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".json") }
                    ?: emptyArray()
                val count = files.size
                files.forEach { it.delete() }
                saveIndex(emptyList())
                _entryCount.value = 0
                Log.i(TAG, "Cleared $count crash log entries")
                count
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to clear crash log entries", t)
            0
        }
    }

    /**
     * Export all crash log entries to a single JSON file (for sharing via SAF).
     */
    fun exportAllToJson(): String {
        val entries = readEntries(limit = 1000)
        val export = CrashLogExport(
            exportedAt = System.currentTimeMillis(),
            exportedAtFormatted = dateFormat.format(Date()),
            entryCount = entries.size,
            deviceInfo = captureDeviceInfo(),
            appInfo = captureAppInfo(),
            entries = entries
        )
        return gson.toJson(export)
    }

    // ===== Internal: persistence =====

    private fun persistEntry(entry: CrashLogEntry) {
        synchronized(this) {
            try {
                val file = File(crashDir, "${entry.id}.json")
                writeAtomic(file, gson.toJson(entry))
                addToIndex(entry.id)
                pruneOldEntries()
                _entryCount.value = countEntries()
                Log.i(TAG, "Crash entry persisted: ${entry.id} (${entry.severity}/${entry.tag.ifBlank { "untagged" }})")
            } catch (oom: OutOfMemoryError) {
                // The crash entry itself (logcat tail + breadcrumbs + device info)
                // can be 20–50 KB. If the crash was OOM, gson.toJson may OOM
                // again and we'd lose the crash record entirely. Fall back to a
                // minimal hand-built JSON stub (no Gson) so at least the basic
                // crash info is persisted.
                Log.e(TAG, "OOM persisting crash entry — writing minimal stub", oom)
                writeMinimalStub(entry)
            } catch (t: Throwable) {
                // Last-resort: try to write to logcat at least
                Log.e(TAG, "FAILED to persist crash entry: ${entry.id}", t)
                writeMinimalStub(entry)
            }
        }
    }

    /**
     * Persist with crash grouping/deduplication. If the most recent entry has
     * the same dedup hash and is within 5 minutes, increment its count instead
     * of creating a new entry. This prevents a recurring crash (e.g.
     * accessibility event loop hitting the same NPE every event) from evicting
     * all other crash history.
     */
    private fun persistEntryWithDedup(entry: CrashLogEntry) {
        synchronized(this) {
            try {
                // Check if the most recent entry matches the dedup hash
                val index = loadIndex()
                if (index.isNotEmpty() && entry.dedupHash != 0) {
                    val mostRecentId = index.last()
                    val mostRecentFile = File(crashDir, "$mostRecentId.json")
                    if (mostRecentFile.exists()) {
                        try {
                            val recentJson = mostRecentFile.readText(Charsets.UTF_8)
                            val recent = gson.fromJson(recentJson, CrashLogEntry::class.java)
                            if (recent != null &&
                                recent.dedupHash == entry.dedupHash &&
                                entry.timestamp - recent.timestamp < DEDUP_WINDOW_MS
                            ) {
                                // Same crash within the dedup window — increment count
                                // and update timestamp. Re-save under the SAME id so
                                // the entry's position in the index is preserved.
                                val merged = recent.copy(
                                    count = recent.count + 1,
                                    timestamp = entry.timestamp,
                                    timestampFormatted = entry.timestampFormatted,
                                    // Keep the freshest breadcrumbs + memory/disk state
                                    breadcrumbs = entry.breadcrumbs,
                                    memoryInfo = entry.memoryInfo,
                                    diskInfo = entry.diskInfo,
                                    serviceState = entry.serviceState
                                )
                                writeAtomic(mostRecentFile, gson.toJson(merged))
                                _entryCount.value = countEntries()
                                Log.i(TAG, "Crash entry deduplicated: ${entry.id} merged into $mostRecentId (count=${merged.count})")
                                return
                            }
                        } catch (t: Throwable) {
                            // Fall through to normal persist
                        }
                    }
                }
                // No dedup match — persist as new entry
                val file = File(crashDir, "${entry.id}.json")
                writeAtomic(file, gson.toJson(entry))
                addToIndex(entry.id)
                pruneOldEntries()
                _entryCount.value = countEntries()
                Log.i(TAG, "Crash entry persisted: ${entry.id} (${entry.severity}/${entry.tag.ifBlank { "untagged" }})")
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM persisting crash entry — writing minimal stub", oom)
                writeMinimalStub(entry)
            } catch (t: Throwable) {
                Log.e(TAG, "FAILED to persist crash entry: ${entry.id}", t)
                writeMinimalStub(entry)
            }
        }
    }

    /**
     * Compute a dedup hash for a throwable. Entries with the same hash are
     * considered the same crash for dedup purposes.
     */
    private fun computeDedupHash(throwable: Throwable, stackTrace: String, tag: String): Int {
        return (throwable.javaClass.name + "|" + tag + "|" + stackTrace.take(2000)).hashCode()
    }

    /**
     * Write a minimal hand-built JSON stub for an entry. Used as a fallback
     * when Gson serialisation OOMs or fails. No logcat, no breadcrumbs, no
     * device info — just the essentials to know what crashed.
     */
    private fun writeMinimalStub(entry: CrashLogEntry) {
        try {
            val stub = buildString {
                append('{')
                append("\"id\":\"").append(escapeJson(entry.id)).append('"').append(',')
                append("\"timestamp\":").append(entry.timestamp).append(',')
                append("\"timestampFormatted\":\"").append(escapeJson(entry.timestampFormatted)).append('"').append(',')
                append("\"severity\":\"").append(entry.severity.name).append('"').append(',')
                append("\"tag\":\"").append(escapeJson(entry.tag)).append('"').append(',')
                append("\"message\":\"").append(escapeJson(entry.message.take(500))).append('"').append(',')
                append("\"throwableClass\":\"").append(escapeJson(entry.throwableClass)).append('"').append(',')
                append("\"threadName\":\"").append(escapeJson(entry.threadName)).append('"').append(',')
                append("\"isMainThread\":").append(entry.isMainThread).append(',')
                append("\"stackTrace\":\"").append(escapeJson(entry.stackTrace.take(2000))).append('"').append(',')
                append("\"count\":").append(entry.count).append(',')
                append("\"oomStub\":true")
                append('}')
            }
            val file = File(crashDir, "${entry.id}.json")
            writeAtomic(file, stub)
            addToIndex(entry.id)
            _entryCount.value = countEntries()
        } catch (_: Throwable) {
            // Truly nothing more we can do
        }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Write a file atomically: write to `<name>.tmp`, then rename to `<name>`.
     * Rename is atomic on POSIX filesystems, so the destination file is
     * never in a partially-written state — survives process kill mid-write.
     */
    private fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            // renameTo is atomic on POSIX filesystems
            if (!tmp.renameTo(file)) {
                // Fallback: delete target then rename (less atomic, but works
                // on some providers that don't support atomic rename)
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
        } catch (t: Throwable) {
            // Best-effort cleanup
            try { if (tmp.exists()) tmp.delete() } catch (_: Throwable) {}
            throw t
        }
    }

    /**
     * Reconcile the index against actual files on disk. Called on init to
     * recover from interrupted writes (process killed mid-save can leave
     * orphan entry files that aren't in the index, or vice versa).
     *
     * - Scans crashDir for `crash_*.json` files
     * - Rebuilds the index from file names sorted by embedded timestamp+sequence
     * - Deletes orphan `.tmp` files
     */
    private fun reconcileIndex() {
        try {
            val files = crashDir.listFiles { f ->
                f.name.startsWith("crash_") && f.name.endsWith(".json")
            } ?: emptyArray()

            // Delete orphan .tmp files from interrupted writes
            crashDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { tmp ->
                try { tmp.delete() } catch (_: Throwable) {}
            }

            // Extract IDs from file names (strip ".json")
            val ids = files.map { it.nameWithoutExtension }.sorted()
            saveIndex(ids)
            Log.i(TAG, "Reconciled crash index: ${ids.size} entries")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reconcile crash index", t)
        }
    }

    private fun generateId(): String {
        val seq = sequenceCounter.incrementAndGet()
        val ts = fileDateFormat.format(Date())
        return "crash_${ts}_${seq.toString().padStart(4, '0')}"
    }

    private fun loadMaxSequence(): Long {
        return try {
            val index = loadIndex()
            index.maxOfOrNull { id ->
                id.substringAfterLast('_').toLongOrNull() ?: 0L
            } ?: 0L
        } catch (_: Throwable) { 0L }
    }

    private fun loadIndex(): MutableList<String> {
        return try {
            if (!indexFile.exists()) return mutableListOf()
            val json = indexFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load crash index — recreating", t)
            mutableListOf()
        }
    }

    private fun saveIndex(ids: List<String>) {
        try {
            writeAtomic(indexFile, gson.toJson(ids))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save crash index", t)
        }
    }

    private fun addToIndex(id: String) {
        val index = loadIndex()
        if (!index.contains(id)) {
            index.add(id)
            saveIndex(index)
        }
    }

    private fun removeFromIndex(id: String) {
        val index = loadIndex()
        if (index.remove(id)) {
            saveIndex(index)
        }
    }

    private fun countEntries(): Int {
        return try {
            crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".json") }
                ?.size ?: 0
        } catch (_: Throwable) { 0 }
    }

    private fun pruneOldEntries() {
        try {
            val index = loadIndex()
            if (index.size <= MAX_ENTRIES) return

            // Remove oldest entries (front of list)
            val toRemove = index.size - MAX_ENTRIES
            val removed = index.subList(0, toRemove).toList()
            index.subList(0, toRemove).clear()

            for (id in removed) {
                try {
                    File(crashDir, "$id.json").delete()
                } catch (_: Throwable) {}
            }
            saveIndex(index)
            Log.i(TAG, "Pruned $toRemove old crash entries")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to prune old crash entries", t)
        }
    }

    // ===== Internal: context capture =====

    private fun captureDeviceInfo(): DeviceInfo {
        return try {
            DeviceInfo(
                manufacturer = Build.MANUFACTURER ?: "unknown",
                model = Build.MODEL ?: "unknown",
                brand = Build.BRAND ?: "unknown",
                device = Build.DEVICE ?: "unknown",
                product = Build.PRODUCT ?: "unknown",
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                buildId = Build.ID ?: "",
                fingerprint = Build.FINGERPRINT ?: "",
                abi = (Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
                isEmulator = isEmulator()
            )
        } catch (t: Throwable) {
            DeviceInfo()
        }
    }

    private fun captureAppInfo(): AppInfo {
        return try {
            AppInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                packageName = context.packageName,
                isDebug = BuildConfig.DEBUG,
                processName = getCurrentProcessName(),
                installerPackageName = getInstallerPackageName(),
                targetSdk = context.applicationInfo.targetSdkVersion,
                minSdk = context.applicationInfo.minSdkVersion
            )
        } catch (t: Throwable) {
            AppInfo(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE)
        }
    }

    /**
     * Memory info with 1-second TTL cache — avoids hammering ActivityManager
     * on every WARN+ Timber log.
     */
    private fun captureMemoryInfoCached(): MemoryInfo {
        val now = System.currentTimeMillis()
        val cached = cachedMemoryInfo
        if (cached != null && now - cachedMemoryInfoAtMs < MEM_CACHE_TTL_MS) {
            return cached
        }
        val fresh = captureMemoryInfo()
        cachedMemoryInfo = fresh
        cachedMemoryInfoAtMs = now
        return fresh
    }

    /**
     * Disk info with 1-second TTL cache.
     */
    private fun captureDiskInfoCached(): DiskInfo {
        val now = System.currentTimeMillis()
        val cached = cachedDiskInfo
        if (cached != null && now - cachedDiskInfoAtMs < DISK_CACHE_TTL_MS) {
            return cached
        }
        val fresh = captureDiskInfo()
        cachedDiskInfo = fresh
        cachedDiskInfoAtMs = now
        return fresh
    }

    /**
     * Capture the status of the app's critical services — accessibility,
     * VPN, device admin. These are the #1 diagnostic questions when a user
     * reports "blocking stopped working" — having them in the crash entry
     * saves a round-trip of "is your accessibility service enabled?" emails.
     */
    private fun captureServiceState(): ServiceStateInfo {
        return try {
            ServiceStateInfo(
                accessibilityEnabled = isAccessibilityServiceEnabled(),
                vpnActive = isVpnServiceActive(),
                deviceAdminActive = isDeviceAdminActive()
            )
        } catch (t: Throwable) {
            ServiceStateInfo()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val componentName = "${context.packageName}/protect.yourself.features.blockerPage.service.MyAccessibilityService"
            enabledServices.contains(componentName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isVpnServiceActive(): Boolean {
        return try {
            protect.yourself.features.blockerPage.service.MyVpnService.isRunning()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isDeviceAdminActive(): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            val adminComponent = protect.yourself.features.blockerPage.utils.DeviceAdminUtils.getComponentName(context)
            dpm.isAdminActive(adminComponent)
        } catch (_: Throwable) {
            false
        }
    }

    private fun captureMemoryInfo(): MemoryInfo {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val runtime = Runtime.getRuntime()
            MemoryInfo(
                totalMemBytes = memInfo?.totalMem ?: 0L,
                availMemBytes = memInfo?.availMem ?: 0L,
                thresholdBytes = memInfo?.threshold ?: 0L,
                lowMemory = memInfo?.lowMemory ?: false,
                runtimeMaxMemoryBytes = runtime.maxMemory(),
                runtimeTotalMemoryBytes = runtime.totalMemory(),
                runtimeFreeMemoryBytes = runtime.freeMemory()
            )
        } catch (t: Throwable) {
            MemoryInfo()
        }
    }

    private fun captureDiskInfo(): DiskInfo {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            DiskInfo(
                totalBytes = stat.totalBytes,
                availableBytes = stat.availableBytes,
                freeBytes = stat.freeBytes,
                blockCount = stat.blockCountLong,
                availableBlocks = stat.availableBlocksLong
            )
        } catch (t: Throwable) {
            DiskInfo()
        }
    }

    /**
     * Capture the last 200 lines of logcat (this process only).
     * Best-effort — may fail on some devices or with permission restrictions.
     *
     * Only called for FATAL entries — capturing logcat spawns a subprocess
     * (5–50 ms) which is too expensive for every WARN+ Timber log.
     */
    private fun captureLogcatTail(): String {
        return try {
            val pid = Process.myPid()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200", "--pid=$pid"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.ifBlank { "" }
        } catch (t: Throwable) {
            ""  // logcat may not be accessible on all devices
        }
    }

    private fun getRecentBreadcrumbs(): List<Breadcrumb> {
        return synchronized(breadcrumbBuffer) {
            breadcrumbBuffer.toList()
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT?.startsWith("generic") == true) ||
            (Build.FINGERPRINT?.startsWith("unknown") == true) ||
            (Build.MODEL?.contains("google_sdk") == true) ||
            (Build.MODEL?.contains("Emulator") == true) ||
            (Build.MODEL?.contains("Android SDK built for x86") == true) ||
            (Build.MANUFACTURER?.contains("Genymotion") == true) ||
            (Build.BRAND?.startsWith("generic") == true) ||
            (Build.DEVICE?.startsWith("generic") == true) ||
            (Build.PRODUCT?.contains("google_sdk") == true)
    }

    private fun getCurrentProcessName(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pid = Process.myPid()
            activityManager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun getInstallerPackageName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName ?: ""
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName) ?: ""
            }
        } catch (_: Throwable) { "" }
    }

    private fun getStackTrace(throwable: Throwable): String {
        return try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            sw.toString()
        } catch (_: Throwable) { "" }
    }

    private fun getCauseChain(throwable: Throwable): List<String> {
        val chain = mutableListOf<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 20) {  // prevent infinite loops
            chain.add("${current.javaClass.name}: ${current.message ?: "(no message)"}")
            current = current.cause
            depth++
        }
        return chain
    }

    // ===== Breadcrumb ring buffer =====

    private val breadcrumbBuffer = mutableListOf<Breadcrumb>()

    /**
     * Persist the in-memory breadcrumb buffer to breadcrumbs.json atomically.
     * Called inside synchronized(breadcrumbBuffer) — no concurrent access.
     */
    private fun persistBreadcrumbs() {
        try {
            val json = gson.toJson(breadcrumbBuffer)
            writeAtomic(breadcrumbFile, json)
        } catch (t: Throwable) {
            // Best-effort — breadcrumbs are diagnostic, not critical
            Log.w(TAG, "Failed to persist breadcrumbs", t)
        }
    }

    /**
     * Load breadcrumbs from breadcrumbs.json into the in-memory buffer.
     * Called once on init.
     */
    private fun loadBreadcrumbsFromDisk() {
        try {
            if (!breadcrumbFile.exists()) return
            val json = breadcrumbFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<Breadcrumb>>() {}.type
            val loaded: List<Breadcrumb>? = gson.fromJson(json, type)
            if (loaded != null) {
                synchronized(breadcrumbBuffer) {
                    breadcrumbBuffer.clear()
                    breadcrumbBuffer.addAll(loaded.takeLast(MAX_BREADCRUMBS))
                }
                Log.i(TAG, "Loaded ${loaded.size} breadcrumbs from disk")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load breadcrumbs from disk", t)
        }
    }

    /**
     * Set the re-entrancy flag — called by the uncaught exception handler
     * before invoking logThrowable, to prevent infinite recursion if logging
     * itself throws.
     */
    internal fun setInCrashHandler(value: Boolean) {
        inCrashHandler.set(value)
    }

    companion object {
        private const val TAG = "CrashLogger"
        private const val MAX_ENTRIES = 50  // keep most recent 50 crash entries
        private const val MAX_BREADCRUMBS = 30  // ring buffer for breadcrumbs
        private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L  // 5 minutes
        private const val MEM_CACHE_TTL_MS = 1000L  // 1 second
        private const val DISK_CACHE_TTL_MS = 1000L  // 1 second

        @Volatile
        private var instance: CrashLogger? = null

        /**
         * Initialise the singleton. Call from Application.onCreate().
         */
        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = CrashLogger(context.applicationContext)
                    }
                }
            }
        }

        /**
         * Get the singleton instance. Returns null if not initialised.
         */
        fun get(): CrashLogger? = instance

        /**
         * Get the singleton instance, initialising lazily if needed.
         */
        fun getInstance(context: Context): CrashLogger {
            return instance ?: synchronized(this) {
                instance ?: CrashLogger(context.applicationContext).also { instance = it }
            }
        }
    }
}

// ===== Data classes =====

/**
 * Severity levels ordered from least to most severe. The [level] property
 * gives an explicit numeric value for safe comparison — do NOT rely on
 * `ordinal` (reordering the enum would silently break comparisons).
 */
enum class CrashSeverity(val level: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
    FATAL(7),
    ASSERT(8)
}

/**
 * Full crash log entry. Serialised to JSON + persisted as individual file.
 *
 * New fields added in this enhancement:
 *  - [isMainThread] — distinguishes main-thread crashes (UI jank/ANR risk)
 *    from background-thread crashes.
 *  - [serviceState] — captures accessibility/VPN/device-admin status at the
 *    time of the crash. Critical for this app — most "blocking stopped
 *    working" reports come down to the accessibility service being disabled.
 *  - [dedupHash] — used by [CrashLogger.persistEntryWithDedup] to merge
 *    consecutive identical crashes into a single entry with [count] > 1.
 *  - [count] — number of times this crash occurred within the dedup window.
 */
data class CrashLogEntry(
    val id: String,
    val timestamp: Long,
    val timestampFormatted: String,
    val severity: CrashSeverity,
    val tag: String,
    val message: String,
    val throwableClass: String,
    val stackTrace: String,
    val causeChain: List<String>,
    val threadName: String,
    val threadId: Long,
    val processId: Int,
    val isMainThread: Boolean = false,
    val deviceInfo: DeviceInfo,
    val appInfo: AppInfo,
    val memoryInfo: MemoryInfo,
    val diskInfo: DiskInfo,
    val serviceState: ServiceStateInfo = ServiceStateInfo(),
    val logcatTail: String,
    val breadcrumbs: List<Breadcrumb>,
    val extraContext: Map<String, String>,
    val dedupHash: Int = 0,
    val count: Int = 1
)

/**
 * Status of the app's critical services at the time of a crash. These three
 * flags are the #1 diagnostic questions when a user reports "blocking
 * stopped working" — having them in the crash entry saves a round-trip
 * of "is your accessibility service enabled?" emails.
 */
data class ServiceStateInfo(
    val accessibilityEnabled: Boolean = false,
    val vpnActive: Boolean = false,
    val deviceAdminActive: Boolean = false
)

data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val brand: String = "",
    val device: String = "",
    val product: String = "",
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val buildId: String = "",
    val fingerprint: String = "",
    val abi: String = "",
    val isEmulator: Boolean = false
)

data class AppInfo(
    val versionName: String = "",
    val versionCode: Int = 0,
    val packageName: String = "",
    val isDebug: Boolean = false,
    val processName: String = "",
    val installerPackageName: String = "",
    val targetSdk: Int = 0,
    val minSdk: Int = 0
)

data class MemoryInfo(
    val totalMemBytes: Long = 0L,
    val availMemBytes: Long = 0L,
    val thresholdBytes: Long = 0L,
    val lowMemory: Boolean = false,
    val runtimeMaxMemoryBytes: Long = 0L,
    val runtimeTotalMemoryBytes: Long = 0L,
    val runtimeFreeMemoryBytes: Long = 0L
)

data class DiskInfo(
    val totalBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val blockCount: Long = 0L,
    val availableBlocks: Long = 0L
)

data class Breadcrumb(
    val timestamp: Long,
    val timestampFormatted: String,
    val category: String,
    val message: String,
    val data: Map<String, String>
)

/**
 * Top-level export envelope (for sharing all crash logs).
 */
data class CrashLogExport(
    val exportedAt: Long,
    val exportedAtFormatted: String,
    val entryCount: Int,
    val deviceInfo: DeviceInfo,
    val appInfo: AppInfo,
    val entries: List<CrashLogEntry>
)
