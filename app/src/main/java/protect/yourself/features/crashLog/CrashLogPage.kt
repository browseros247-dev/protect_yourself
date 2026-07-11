package protect.yourself.features.crashLog

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import protect.yourself.theme.BrandOrange

/**
 * CrashLogPage — UI for viewing + managing crash logs.
 *
 * Features:
 *  - List of recent crash entries (timestamp, severity, tag, message)
 *  - Tap entry → detail view with full stack trace + device info + breadcrumbs
 *  - Export all logs to a single JSON file via SAF
 *  - Clear all logs (with confirmation)
 *  - Live entry count via StateFlow
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: CrashLogViewModel = viewModel(
        factory = CrashLogViewModel.factory(context.applicationContext as android.app.Application)
    )
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // SAF launcher for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch { viewModel.exportToUri(uri) }
        }
    }

    var showClearConfirm by remember { mutableStateOf(false) }
    var showExportSuccess by remember { mutableStateOf<String?>(null) }
    var showExportError by remember { mutableStateOf<String?>(null) }

    // React to export results
    LaunchedEffect(state.exportResult) {
        state.exportResult?.let { result ->
            if (result.isSuccess) {
                showExportSuccess = result.getOrThrow()
            } else {
                showExportError = result.exceptionOrNull()?.message ?: "Unknown error"
            }
            viewModel.clearExportResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Crash Logs")
                        if (state.entries.isNotEmpty()) {
                            Text(
                                text = "${state.entries.size} entries",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandOrange
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.entries.isNotEmpty()) {
                        IconButton(onClick = {
                            val fileName = "protect_yourself_crashlogs_${System.currentTimeMillis()}.json"
                            exportLauncher.launch(fileName)
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Export all logs")
                        }
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear all logs")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Loading crash logs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }

            if (state.entries.isEmpty()) {
                EmptyState()
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
                )
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    CrashEntryRow(entry = entry, onClick = { viewModel.openEntry(entry.id) })
                }
            }
        }
    }

    // Detail dialog
    state.selectedEntry?.let { entry ->
        CrashEntryDetailDialog(
            entry = entry,
            onDismiss = { viewModel.closeEntry() }
        )
    }

    // Clear confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all crash logs?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete all ${state.entries.size} crash log entries. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearAll()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Export success dialog
    showExportSuccess?.let { msg ->
        AlertDialog(
            onDismissRequest = { showExportSuccess = null },
            title = { Text("Exported", fontWeight = FontWeight.Bold, color = BrandOrange) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { showExportSuccess = null }) {
                    Text("OK", color = BrandOrange)
                }
            }
        )
    }

    // Export error dialog
    showExportError?.let { msg ->
        AlertDialog(
            onDismissRequest = { showExportError = null },
            title = { Text("Export Failed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { showExportError = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No crash logs recorded",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "If the app crashes or encounters an error,\nit will be captured here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CrashEntryRow(
    entry: CrashLogEntry,
    onClick: () -> Unit
) {
    val severityColor = when (entry.severity) {
        CrashSeverity.FATAL, CrashSeverity.ASSERT -> MaterialTheme.colorScheme.error
        CrashSeverity.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        CrashSeverity.WARN -> androidx.compose.ui.graphics.Color(0xFFFFA000)  // amber
        CrashSeverity.INFO -> BrandOrange
        CrashSeverity.DEBUG, CrashSeverity.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.severity == CrashSeverity.FATAL)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Severity indicator bar
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 40.dp)
                    .background(severityColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.severity.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = severityColor
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    if (entry.tag.isNotBlank()) {
                        Text(
                            text = entry.tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = entry.timestampFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                // Show dedup count if this crash occurred multiple times
                // within the dedup window (see CrashLogger.persistEntryWithDedup).
                if (entry.count > 1) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "× ${entry.count} (last: ${entry.timestampFormatted})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (entry.throwableClass.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.throwableClass.substringAfterLast('.'),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashEntryDetailDialog(
    entry: CrashLogEntry,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = entry.severity.name + if (entry.tag.isNotBlank()) " — ${entry.tag}" else "",
                    fontWeight = FontWeight.Bold,
                    color = when (entry.severity) {
                        CrashSeverity.FATAL, CrashSeverity.ASSERT, CrashSeverity.ERROR ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = entry.timestampFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Message:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                if (entry.throwableClass.isNotBlank()) {
                    Text("Exception:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = entry.throwableClass,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (entry.causeChain.isNotEmpty()) {
                    Text("Cause chain:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    entry.causeChain.forEachIndexed { i, cause ->
                        Text(
                            text = "$i. $cause",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (entry.stackTrace.isNotBlank()) {
                    Text("Stack trace:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = entry.stackTrace.take(2000) + if (entry.stackTrace.length > 2000) "\n… (truncated)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text("Thread:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "${entry.threadName} (id=${entry.threadId}, pid=${entry.processId})",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Device:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "${entry.deviceInfo.manufacturer} ${entry.deviceInfo.model}\n" +
                        "Android ${entry.deviceInfo.androidVersion} (API ${entry.deviceInfo.sdkInt})\n" +
                        (if (entry.deviceInfo.isEmulator) "(Emulator)\n" else "") +
                        "Build: ${entry.deviceInfo.buildId}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("App:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "v${entry.appInfo.versionName} (${entry.appInfo.versionCode})\n" +
                        entry.appInfo.packageName + if (entry.appInfo.isDebug) " (debug)" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Memory at crash:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "Available: ${formatBytes(entry.memoryInfo.availMemBytes)} / " +
                        "${formatBytes(entry.memoryInfo.totalMemBytes)}\n" +
                        "Low memory: ${if (entry.memoryInfo.lowMemory) "YES" else "no"}\n" +
                        "Runtime: ${formatBytes(entry.memoryInfo.runtimeFreeMemoryBytes)} free / " +
                        "${formatBytes(entry.memoryInfo.runtimeTotalMemoryBytes)} total",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Disk at crash:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "Available: ${formatBytes(entry.diskInfo.availableBytes)} / " +
                        "${formatBytes(entry.diskInfo.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Service state — critical for diagnosing "blocking stopped working"
                Text("Service state:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "Accessibility: ${if (entry.serviceState.accessibilityEnabled) "✓ enabled" else "✗ disabled"}\n" +
                        "VPN: ${if (entry.serviceState.vpnActive) "✓ active" else "✗ inactive"}\n" +
                        "Device admin: ${if (entry.serviceState.deviceAdminActive) "✓ active" else "✗ inactive"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (entry.breadcrumbs.isNotEmpty()) {
                    Text("Breadcrumbs (most recent):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    entry.breadcrumbs.reversed().take(10).forEach { crumb ->
                        Text(
                            text = "[${crumb.timestampFormatted}] ${crumb.category}: ${crumb.message}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (entry.extraContext.isNotEmpty()) {
                    Text("Extra context:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    entry.extraContext.forEach { (k, v) ->
                        Text(
                            text = "$k: $v",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (entry.logcatTail.isNotBlank()) {
                    Text("Logcat tail (last 200 lines):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = entry.logcatTail.take(2000) + if (entry.logcatTail.length > 2000) "\n… (truncated)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = BrandOrange)
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(java.util.Locale.US, "%.2f GB", gb)
        mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1 -> String.format(java.util.Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

// ===== ViewModel =====

class CrashLogViewModel(
    application: android.app.Application,
    private val crashLogger: CrashLogger
) : androidx.lifecycle.AndroidViewModel(application) {

    private val _state = MutableStateFlow(CrashLogState())
    val state: StateFlow<CrashLogState> = _state.asStateFlow()

    init {
        loadEntries()
        // Observe CrashLogger.entryCount so the list live-updates when new
        // crash entries are logged while the page is open (e.g. a background
        // service throws an ERROR via Timber.e → CrashLoggingTree →
        // CrashLogger.logMessage → entryCount increments → we reload).
        viewModelScope.launch {
            crashLogger.entryCount.collect { count ->
                if (count != _state.value.entries.size) {
                    loadEntries()
                }
            }
        }
    }

    fun loadEntries() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val entries = crashLogger.readEntries(limit = 100)
            _state.value = _state.value.copy(
                entries = entries,
                isLoading = false
            )
        }
    }

    fun openEntry(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = crashLogger.readEntry(id)
            if (entry != null) {
                _state.value = _state.value.copy(selectedEntry = entry)
            }
        }
    }

    fun closeEntry() {
        _state.value = _state.value.copy(selectedEntry = null)
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            crashLogger.clearAll()
            loadEntries()
        }
    }

    /**
     * Export all crash log entries to the user-picked SAF URI.
     *
     * Uses the shared [protect.yourself.commons.utils.SafUtils.writeJsonToUri]
     * helper — handles null return, IOException, and SecurityException across
     * both "wt" and "w" modes, plus post-write size verification. This is the
     * same fix applied to BackupManager in commit a1ec981.
     *
     * Re-throws CancellationException to preserve structured concurrency.
     */
    fun exportToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val export = crashLogger.exportAllToJson()
                val bytesWritten = protect.yourself.commons.utils.SafUtils.writeJsonToUri(
                    getApplication<android.app.Application>().contentResolver,
                    uri,
                    export
                )
                val sizeKb = bytesWritten / 1024.0
                // Use the entry count from the export itself — avoids re-reading
                // all entry files from disk just to count them (the original
                // code called crashLogger.readEntries().size which re-read 100
                // JSON files). Parse the count from the export — simpler than
                // changing exportAllToJson's signature.
                val entryCount = countEntriesInExport(export)
                _state.value = _state.value.copy(
                    exportResult = Result.success(
                        "Exported $entryCount crash logs (${
                            String.format(java.util.Locale.US, "%.1f KB", sizeKb)
                        }) to file."
                    )
                )
            } catch (t: kotlinx.coroutines.CancellationException) {
                // Don't swallow coroutine cancellation — propagate to preserve
                // structured concurrency (e.g. if user navigates away mid-export).
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(exportResult = Result.failure(t))
            }
        }
    }

    /**
     * Extract the `entryCount` field from the exported JSON without re-reading
     * all entry files. Uses Gson to parse just the top-level envelope.
     */
    private fun countEntriesInExport(json: String): Int {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<protect.yourself.features.crashLog.CrashLogExport>() {}.type
            val export = com.google.gson.Gson().fromJson<protect.yourself.features.crashLog.CrashLogExport>(json, type)
            export?.entryCount ?: 0
        } catch (_: Throwable) { 0 }
    }

    fun clearExportResult() {
        _state.value = _state.value.copy(exportResult = null)
    }

    companion object {
        fun factory(application: android.app.Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val crashLogger = CrashLogger.getInstance(application)
                    return CrashLogViewModel(application, crashLogger) as T
                }
            }
    }
}

data class CrashLogState(
    val entries: List<CrashLogEntry> = emptyList(),
    val selectedEntry: CrashLogEntry? = null,
    val isLoading: Boolean = false,
    val exportResult: Result<String>? = null
)
