package protect.yourself.features.profilePage.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import protect.yourself.BuildConfig
import protect.yourself.theme.BrandOrange

/**
 * ProfilePage — the "Profile" tab content.
 *
 * Items (per user choice):
 *  - Backup/Sync (Phase 5+: Firebase Auth required)
 *  - Import/Export local config
 *  - FAQ + About
 *  - Share app
 *  - Contact email
 *  - Delete account
 */
@Composable
fun ProfilePage() {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    val profileItems = listOf(
        ProfileItem("Backup & Sync", "Sync your data to cloud (requires sign-in)"),
        ProfileItem("Import/Export", "Backup to local file or restore"),
        ProfileItem("FAQ", "Frequently asked questions"),
        ProfileItem("About", "App info, version, licenses"),
        ProfileItem("Share app", "Share Protect Yourself with friends"),
        ProfileItem("Contact us", "Email support"),
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
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
                }
            }
        }

        items(profileItems) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                onClick = {
                    when (item.title) {
                        "Backup & Sync" -> { /* Phase 5+: open SignInSignUpPage */ }
                        "Import/Export" -> { /* Phase 6: file picker */ }
                        "FAQ" -> { /* Phase 6: open FAQ page */ }
                        "About" -> { /* Phase 6: open About page */ }
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
                            context.startActivity(Intent.createChooser(emailIntent, "Send email"))
                        }
                        "Delete account" -> showDeleteDialog = true
                    }
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete account?") },
            text = {
                Text("All data, including streak progress, will be cleared. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    // Phase 5+: Firebase Auth delete + Firestore delete + clear local DB
                    showDeleteDialog = false
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
}

private data class ProfileItem(
    val title: String,
    val subtitle: String,
    val isDestructive: Boolean = false
)
