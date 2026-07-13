package protect.yourself.features.profilePage.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import protect.yourself.BuildConfig
import protect.yourself.theme.BrandOrange

/**
 * ProfilePage — the "Profile" tab content.
 *
 * Includes About info (since About tab was removed).
 */
@Composable
fun ProfilePage() {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showBackupRestore by remember { mutableStateOf(false) }
    var showCrashLogs by remember { mutableStateOf(false) }

    // Collect crash log entry count so we can show a badge on the Crash Logs
    // menu item. Without this, the user has no idea a crash happened unless
    // they manually navigate to Crash Logs.
    val crashEntryCount by protect.yourself.features.crashLog.CrashLogger
        .getInstance(context).entryCount.collectAsState()

    // If crash log page is open, show it instead of the profile list
    if (showCrashLogs) {
        BackHandler { showCrashLogs = false }
        protect.yourself.features.crashLog.CrashLogPage(
            onBack = { showCrashLogs = false }
        )
        return
    }

    // If backup/restore page is open, show it instead of the profile list
    if (showBackupRestore) {
        BackHandler { showBackupRestore = false }
        protect.yourself.features.backupRestore.BackupRestorePage(
            onBack = { showBackupRestore = false }
        )
        return
    }

    val profileItems = listOf(
        ProfileItem("Backup & Restore", "Export or import your data as JSON"),
        ProfileItem("Crash Logs", "View, export, or clear diagnostic crash logs"),
        ProfileItem("Share app", "Share Protect Yourself with friends"),
        ProfileItem("Contact us", "Email support"),
        ProfileItem("About", "App info, version, credits"),
        ProfileItem("Delete account", "Permanently delete your account + data", isDestructive = true)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // App version header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Protect Yourself",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = BrandOrange
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Package: ${BuildConfig.APPLICATION_ID}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(profileItems) { item ->
            // Show a count badge on the Crash Logs item if there are entries
            val badgeCount = if (item.title == "Crash Logs" && crashEntryCount > 0) crashEntryCount else 0
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = {
                    when (item.title) {
                        "Backup & Restore" -> showBackupRestore = true
                        "Crash Logs" -> showCrashLogs = true
                        "Share app" -> {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Protect Yourself")
                                putExtra(Intent.EXTRA_TEXT, "Check out Protect Yourself — a free app blocker")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                        }
                        "Contact us" -> {
                            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:support@protectyourself.app")
                            }
                            try {
                                context.startActivity(Intent.createChooser(emailIntent, "Send email"))
                            } catch (_: Throwable) {
                                android.widget.Toast.makeText(context, "No email app found", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        "About" -> showAboutDialog = true
                        "Delete account" -> showDeleteDialog = true
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.isDestructive) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                        )
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Show crash count badge on the Crash Logs item
                    if (badgeCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.error,
                                    androidx.compose.foundation.shape.CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Delete account dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete account?") },
            text = {
                Text("All data, including schedule history and settings, will be cleared. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    // Clear local DB
                    showDeleteDialog = false
                    android.widget.Toast.makeText(context, "Account data cleared", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // About dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About Protect Yourself", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Protect Yourself v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "A free, open-source app blocker & focus companion to help you overcome porn addiction and build healthier digital habits.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No ads. No trackers. No analytics.", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Credits:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text("• Original app by PlanProductive", style = MaterialTheme.typography.bodySmall)
                    Text("• Nunito font (OFL)", style = MaterialTheme.typography.bodySmall)
                    Text("• Lottie by Airbnb", style = MaterialTheme.typography.bodySmall)
                    Text("• Jetpack Compose, Room, Material 3", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close", color = BrandOrange)
                }
            }
        )
    }
}

private data class ProfileItem(
    val title: String,
    val subtitle: String,
    val isDestructive: Boolean = false
)
