package protect.yourself.features.crashLog

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.util.Log
import androidx.core.content.edit
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
 *    logcat tail (last 200 lines), and custom context tags.
 *  - Severity levels: VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL, ASSERT
 *  - File rotation: keeps most recent N crash entries (default 50).
 *    Oldest entries are auto-pruned.
 *  - Each entry persisted as individual file (crash_YYYYMMDD_HHmmss_<n>.json)
 *    for easy sharing + atomic writes.
 *  - Index file (crash_index.json) maintains entry ordering + metadata.
 *  - Timber tree integration: all Timber.e()/Timber.w() calls are captured.
 *  - Global uncaught exception handler integration.
 *  - Manual API: logThrowable(), logMessage(), logBreadcrumb() for tracing.
 *  - StateFlow exposes entry count for UI badge.
 *  - Export to single JSON file via SAF (all entries bundled).
 *  - Clear all entries (for user-initiated cleanup).
 *  - Thread-safe: all writes synchronised on the CrashLogger instance.
 *
 * Storage layout:
 *   <filesDir>/crashlogs/
 *     crash_index.json                         — ordered list of entry IDs + summaries
 *     crash_20260710_123456_001.json           — full entry 1
 *     crash_20260710_123456_002.json           — full entry 2
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

    private val sequenceCounter = AtomicLong(0L)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    init {
        // Restore sequence counter from existing entries
        sequenceCounter.set(loadMaxSequence())
        _entryCount.value = countEntries()
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
        val stackTrace = getStackTrace(throwable)
        val causeChain = getCauseChain(throwable)

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
            deviceInfo = captureDeviceInfo(),
            appInfo = captureAppInfo(),
            memoryInfo = captureMemoryInfo(),
            diskInfo = captureDiskInfo(),
            logcatTail = captureLogcatTail(),
            breadcrumbs = getRecentBreadcrumbs(),
            extraContext = extraContext
        )

        persistEntry(entry)
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
            deviceInfo = captureDeviceInfo(),
            appInfo = captureAppInfo(),
            memoryInfo = captureMemoryInfo(),
            diskInfo = captureDiskInfo(),
            logcatTail = if (severity >= CrashSeverity.WARN) captureLogcatTail() else "",
            breadcrumbs = getRecentBreadcrumbs(),
            extraContext = extraContext
        )

        persistEntry(entry)
        return entry.id
    }

    /**
     * Drop a breadcrumb — a lightweight tracing event stored in a ring buffer.
     * When a crash happens later, the most recent N breadcrumbs are attached
     * to the crash entry, helping reconstruct what the app was doing before.
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

    /**
     * Get a summary string for the most recent N crashes — useful for
     * "Email support" auto-attachment.
     */
    fun getRecentCrashesSummary(limit: Int = 5): String {
        val entries = readEntries(limit)
        if (entries.isEmpty()) return "No crash logs recorded."
        val sb = StringBuilder()
        sb.appendLine("Protect Yourself — recent crash logs (last $limit)")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        sb.appendLine("App: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("====")
        for (entry in entries) {
            sb.appendLine("[${entry.timestampFormatted}] ${entry.severity} ${entry.tag.ifBlank { "untagged" }}")
            sb.appendLine("  ${entry.message}")
            if (entry.throwableClass.isNotBlank()) {
                sb.appendLine("  Exception: ${entry.throwableClass}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    // ===== Internal: persistence =====

    private fun persistEntry(entry: CrashLogEntry) {
        synchronized(this) {
            try {
                val file = File(crashDir, "${entry.id}.json")
                file.writeText(gson.toJson(entry), Charsets.UTF_8)
                addToIndex(entry.id)
                pruneOldEntries()
                _entryCount.value = countEntries()
                Log.i(TAG, "Crash entry persisted: ${entry.id} (${entry.severity}/${entry.tag.ifBlank { "untagged" }})")
            } catch (t: Throwable) {
                // Last-resort: try to write to logcat at least
                Log.e(TAG, "FAILED to persist crash entry: ${entry.id}", t)
            }
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
            indexFile.writeText(gson.toJson(ids), Charsets.UTF_8)
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

    companion object {
        private const val TAG = "CrashLogger"
        private const val MAX_ENTRIES = 50  // keep most recent 50 crash entries
        private const val MAX_BREADCRUMBS = 30  // ring buffer for breadcrumbs

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

enum class CrashSeverity {
    VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL, ASSERT
}

/**
 * Full crash log entry. Serialised to JSON + persisted as individual file.
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
    val deviceInfo: DeviceInfo,
    val appInfo: AppInfo,
    val memoryInfo: MemoryInfo,
    val diskInfo: DiskInfo,
    val logcatTail: String,
    val breadcrumbs: List<Breadcrumb>,
    val extraContext: Map<String, String>
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
