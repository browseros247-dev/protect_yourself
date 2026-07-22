package protect.yourself.features.backupRestore

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.theme.BrandOrange

/**
 * BackupRestorePage — UI for local JSON backup/restore.
 *
 * Features:
 *  - Export button: opens SAF CreateDocument picker → JSON file written
 *  - Import button: opens SAF OpenDocument picker → JSON file parsed + restored
 *  - Progress bar + status text during operations
 *  - Error dialog with typed error messages + recovery suggestions
 *  - Success dialog with backup stats (row counts, file size)
 *  - Confirmation dialog before import (warns about overwrite)
 *
 * The page uses a BackupRestoreViewModel for state management and
 * calls BackupManager for the actual operations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestorePage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: BackupRestoreViewModel = viewModel(
        factory = BackupRestoreViewModel.factory(context.applicationContext as android.app.Application)
    )
    val state by viewModel.state.collectAsState()
    val progress by viewModel.progress.collectAsState()

    // SAF launcher for export — CreateDocument("application/json")
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            // viewModel.exportToUri internally launches on viewModelScope —
            // no need for an outer scope.launch here.
            viewModel.exportToUri(uri)
        } else {
            // User backed out of the picker without picking a file.
            // Surface a Cancelled dialog so the user knows nothing happened.
            viewModel.reportCancelled()
        }
    }

    // SAF launcher for import — OpenDocument (JSON files only).
    // Use application/json + application/octet-stream (some providers label
    // .json files as octet-stream). We deliberately do NOT add "*/*" because
    // it makes the other MIME types redundant and can cause some OEM pickers
    // to show every file on the device, hiding the JSON filter.
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Show confirmation dialog before importing (destructive — overwrites current data)
            viewModel.showImportConfirmation(uri)
        } else {
            viewModel.reportCancelled()
        }
    }

    // Track if we should show error/success dialogs
    var showErrorDialog by remember { mutableStateOf<BackupResult.Error?>(null) }
    var showSuccessDialog by remember { mutableStateOf<BackupResult.Success?>(null) }

    // React to operation results
    LaunchedEffect(state.lastResult) {
        when (val result = state.lastResult) {
            is BackupResult.Error -> {
                showErrorDialog = result
                viewModel.clearResult()
            }
            is BackupResult.Success -> {
                showSuccessDialog = result
                viewModel.clearResult()
            }
            null -> { /* no-op */ }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(BrandOrange.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Backup, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.size(16.dp))
                    Column {
                        Text(
                            text = "Local Backup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Export your settings, keywords, and history to a JSON file. Import to restore on this or another device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // What gets backed up info card
            InfoCard(
                icon = Icons.Filled.Info,
                title = "What's included",
                items = listOf(
                    "All switch settings (${state.stats.switchCount} entries)",
                    "Blocklist + whitelist keywords (${state.stats.keywordCount} entries)",
                    "Selected apps: browsers, social media, block list, VPN whitelist (${state.stats.appCount} entries)",
                    "Stop Me sessions + schedule (${state.stats.stopMeDurationCount} entries)",
                    "VPN custom DNS presets (${state.stats.vpnCustomDnsCount} entries)",
                    "Block screen count: ${state.stats.blockScreenCountCount}",
                    "Stop Me session count: ${state.stats.stopMeSessionCountCount}",
                    "Pending accountability requests: ${state.stats.pendingRequestCount}"
                )
            )

            // Export button
            ActionCard(
                icon = Icons.Filled.CloudUpload,
                title = "Export Backup",
                subtitle = "Save all data to a JSON file",
                buttonText = "Export",
                enabled = !state.isOperating,
                onClick = {
                    createDocumentLauncher.launch(BackupManager.suggestedFileName())
                }
            )

            // Import button
            ActionCard(
                icon = Icons.Filled.CloudDownload,
                title = "Restore Backup",
                subtitle = "Overwrite current data with a JSON file",
                buttonText = "Restore",
                enabled = !state.isOperating,
                isDestructive = true,
                onClick = {
                    openDocumentLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                }
            )

            // Progress indicator
            if (state.isOperating) {
                ProgressCard(progress = progress)
            }

            // Warning card
            WarningCard()

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // Import confirmation dialog
    if (state.showImportConfirm && state.pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelImportConfirmation() },
            title = { Text("Restore backup?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will permanently overwrite ALL current data with the contents of the backup file. " +
                        "This cannot be undone.\n\n" +
                        "If anything goes wrong during restore, the operation will be rolled back and your current data will be preserved."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = state.pendingImportUri!!
                        viewModel.cancelImportConfirmation()
                        // viewModel.importFromUri internally launches on
                        // viewModelScope — no need for an outer scope.launch.
                        viewModel.importFromUri(uri)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Overwrite & Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImportConfirmation() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error dialog
    showErrorDialog?.let { error ->
        val (title, message, canRetry) = formatError(error)
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            title = { Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (canRetry) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You can try again with a different file or location.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text("OK")
                }
            }
        )
    }

    // Success dialog
    showSuccessDialog?.let { success ->
        AlertDialog(
            onDismissRequest = { showSuccessDialog = null },
            title = { Text("Success", fontWeight = FontWeight.Bold, color = BrandOrange) },
            text = {
                Column {
                    Text(success.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Breakdown:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("• Switch settings: ${success.stats.switchCount}", style = MaterialTheme.typography.bodySmall)
                    Text("• Keywords: ${success.stats.keywordCount}", style = MaterialTheme.typography.bodySmall)
                    Text("• Apps: ${success.stats.appCount}", style = MaterialTheme.typography.bodySmall)
                    Text("• VPN DNS presets: ${success.stats.vpnCustomDnsCount}", style = MaterialTheme.typography.bodySmall)
                    Text("• Stop Me sessions: ${success.stats.stopMeDurationCount}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "File size: ${(success.sizeBytes / 1024.0).let { if (it >= 1024) String.format("%.2f MB", it / 1024) else String.format("%.1f KB", it) }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = null }) {
                    Text("OK", color = BrandOrange)
                }
            }
        )
    }
}

// ===== Sub-components =====

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonText: String,
    enabled: Boolean,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isDestructive) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        else BrandOrange.copy(alpha = 0.15f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isDestructive) MaterialTheme.colorScheme.error else BrandOrange,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                colors = if (isDestructive) {
                    // DARK-CONTRAST-01: pair error container with its onError role —
                    // the dark-scheme error color is now the M3 light tone, so a
                    // default (white) content color would be unreadable.
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors(containerColor = BrandOrange)
                }
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    items: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(
                    text = "Important",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Restoring a backup completely replaces your current data. " +
                        "If the restore fails partway through, the operation is automatically rolled back and your data is preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: BackupProgress) {
    val (percent, message, isIndeterminate) = when (progress) {
        is BackupProgress.Exporting -> Triple(progress.percent, progress.message, false)
        is BackupProgress.Importing -> Triple(progress.percent, progress.message, false)
        BackupProgress.Idle -> Triple(0, "", true)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = BrandOrange
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = if (progress is BackupProgress.Exporting) "Exporting…" else "Restoring…",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (isIndeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = BrandOrange
                )
            } else {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = BrandOrange
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== Helpers =====

private fun formatError(error: BackupResult.Error): Triple<String, String, Boolean> {
    return when (error) {
        is BackupResult.Error.StorageError -> Triple(
            "Storage Error",
            error.message,
            true  // can retry with different file/location
        )
        is BackupResult.Error.InvalidFormat -> Triple(
            "Invalid Backup File",
            error.message,
            true  // can retry with a valid file
        )
        is BackupResult.Error.UnsupportedVersion -> Triple(
            "Unsupported Backup Version",
            error.message,
            false  // need to update app — can't retry with same file
        )
        is BackupResult.Error.DatabaseError -> Triple(
            "Database Restore Failed",
            "${error.message}\n\nYour existing data has been preserved (the failed restore was rolled back).",
            true  // can retry
        )
        is BackupResult.Error.Cancelled -> Triple(
            "Cancelled",
            "The operation was cancelled.",
            false
        )
        is BackupResult.Error.Unknown -> Triple(
            "Unexpected Error",
            error.message,
            true
        )
    }
}
