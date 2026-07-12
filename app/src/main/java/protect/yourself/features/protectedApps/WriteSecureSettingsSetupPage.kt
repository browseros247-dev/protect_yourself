package protect.yourself.features.protectedApps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import protect.yourself.BuildConfig

/**
 * Compose page that guides the user through granting
 * `android.permission.WRITE_SECURE_SETTINGS` via ADB, and verifies the grant.
 *
 * Why this page exists
 * --------------------
 *
 * Android 13+ blocks apps from programmatically enabling their own
 * accessibility service. If the OEM kills the service for battery
 * optimisation (Xiaomi/Huawei/Samsung do this every 12–48h), the app
 * cannot re-arm itself — until the user opens the app and re-enables
 * accessibility manually.
 *
 * If the user grants `WRITE_SECURE_SETTINGS` via ADB (a one-time setup),
 * the app can re-arm itself instantly via [AccessibilityPersistUtils].
 * This page walks the user through that one-time grant.
 *
 * The grant command (auto-fills the user's package name):
 *
 * ```
 * adb shell pm grant protect.yourself android.permission.WRITE_SECURE_SETTINGS
 * ```
 *
 * After granting, the user taps "I've run the command — verify" and we
 * re-check the permission. If granted, we immediately run selfHealSafe()
 * to arm the service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteSecureSettingsSetupPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Whether WRITE_SECURE_SETTINGS is currently granted. Re-checked on
    // every resume + after the user taps "verify".
    var permissionGranted by remember { mutableStateOf(false) }
    var lastCheckedAt by remember { mutableStateOf(0L) }

    // Initial check + re-check on every resume
    LaunchedEffect(Unit) {
        permissionGranted = AccessibilityPersistUtils.isWriteSecureSettingsGranted(context)
        lastCheckedAt = System.currentTimeMillis()
    }

    val appPkg = remember { context.packageName }
    val adbCommand = remember(appPkg) {
        "adb shell pm grant $appPkg android.permission.WRITE_SECURE_SETTINGS"
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Reliable Accessibility", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== Status card =====
            StatusCard(permissionGranted = permissionGranted, lastCheckedAt = lastCheckedAt)

            // ===== Why this matters =====
            InfoCard(
                title = "Why this matters",
                body = "Android OEMs (Xiaomi, Huawei, Samsung, Oppo, Vivo) aggressively " +
                    "kill background accessibility services for battery optimisation — " +
                    "often every 12–48 hours. Without WRITE_SECURE_SETTINGS, the app " +
                    "cannot re-arm itself, and blocking stops until you manually re-enable " +
                    "the service in Settings.\n\n" +
                    "Granting this permission (one-time, via ADB) lets the app instantly " +
                    "re-arm itself the moment Android removes it."
            )

            // ===== Step 1: Install ADB =====
            StepCard(
                stepNumber = 1,
                title = "Install ADB on your computer",
                body = "Download Android Platform Tools from the official Android developer site. " +
                    "Available for Windows, macOS, and Linux. No full Android Studio install needed."
            ) {
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.android.com/tools/releases/platform-tools")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(intent) } catch (_: Throwable) {}
                }) {
                    Text("Open platform-tools page")
                }
            }

            // ===== Step 2: Enable USB debugging =====
            StepCard(
                stepNumber = 2,
                title = "Enable USB debugging on your phone",
                body = "Settings → About phone → tap Build number 7 times to unlock Developer options.\n\n" +
                    "Then Settings → System → Developer options → enable USB debugging.\n\n" +
                    "Connect your phone to your computer with a USB cable. Accept the RSA key " +
                    "fingerprint prompt on your phone."
            )

            // ===== Step 3: Run the command =====
            StepCard(
                stepNumber = 3,
                title = "Run this ADB command",
                body = "Open a terminal / command prompt on your computer and paste this exact command:"
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = adbCommand,
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("adb-command", adbCommand))
                            scope.launch {
                                snackbarHostState.showSnackbar("Command copied to clipboard")
                            }
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "You should see no output — that means it worked. " +
                        "If you see 'Permission denied' or 'Security exception', " +
                        "double-check the package name matches.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ===== Step 4: Verify =====
            StepCard(
                stepNumber = 4,
                title = "Verify the grant",
                body = "Tap the button below to re-check the permission. " +
                    "If granted, the app will immediately run a self-heal cycle " +
                    "and re-arm the accessibility service."
            ) {
                Button(
                    onClick = {
                        permissionGranted = AccessibilityPersistUtils.isWriteSecureSettingsGranted(context)
                        lastCheckedAt = System.currentTimeMillis()
                        if (permissionGranted) {
                            // Immediately run selfHeal to re-arm
                            try {
                                AccessibilityPersistUtils.selfHealSafe(context)
                            } catch (_: Throwable) {}
                            scope.launch {
                                snackbarHostState.showSnackbar("✓ Permission granted — service re-armed")
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Permission not yet granted. Re-run the ADB command and try again."
                                )
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (permissionGranted)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        if (permissionGranted) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (permissionGranted) "Re-verify & re-arm service" else "I've run the command — verify")
                }
            }

            // ===== Diagnostics card =====
            DiagnosticsCard(context = context, permissionGranted = permissionGranted)

            Spacer(Modifier.height(24.dp))

            // ===== Footer note =====
            Text(
                text = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                    "Package $appPkg",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun StatusCard(permissionGranted: Boolean, lastCheckedAt: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (permissionGranted)
                Color(0xFF1B5E20).copy(alpha = 0.08f)
            else
                Color(0xFFE65100).copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (permissionGranted) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (permissionGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (permissionGranted) "WRITE_SECURE_SETTINGS granted" else "Permission not granted",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (permissionGranted)
                        "Self-heal is active — service will auto-re-arm on disable."
                    else
                        "Service will be killed by OEM battery optimisation every 12–48h.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    body: String,
    content: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepNumber.toString(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Text(body, fontSize = 13.sp, lineHeight = 18.sp)
            content?.invoke()
        }
    }
}

@Composable
private fun DiagnosticsCard(context: Context, permissionGranted: Boolean) {
    val enabledSet = remember(permissionGranted) {
        AccessibilityPersistUtils.getEnabledServicesSet(context)
    }
    val ownEnabled = remember(permissionGranted, enabledSet) {
        AccessibilityPersistUtils.isOwnServiceEnabled(context)
    }
    val protectedCount = remember(permissionGranted) {
        ProtectedAppsRegistry.getComponents(context).size
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnostics", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            DiagnosticRow("WRITE_SECURE_SETTINGS granted", permissionGranted.toString())
            DiagnosticRow("Our service in enabled list", ownEnabled.toString())
            DiagnosticRow("Total enabled accessibility services", enabledSet.size.toString())
            DiagnosticRow("Third-party services we protect", protectedCount.toString())
            DiagnosticRow("Our package", context.packageName)
            DiagnosticRow("Our service component", AccessibilityPersistUtils.ownComponentFlat)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}
