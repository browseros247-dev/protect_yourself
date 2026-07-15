package protect.yourself.features.protectedApps

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lists every installed accessibility service (across all apps) and lets the
 * user toggle "protection" for each one. "Protection" means: if
 * WRITE_SECURE_SETTINGS is granted, we will re-arm this service in
 * `enabled_accessibility_services` whenever Android removes it.
 *
 * Our own accessibility service is always protected (the switch is locked ON).
 *
 * Ported from the reference `ProtectedAppsActivity` — converted from ListActivity to
 * Compose.
 *
 * Uses AppCompatActivity + Theme.ProtectYourself.NoActionBar for AppCompat
 * theme compatibility.
 */
class ProtectedAppsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    ProtectedAppsScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { mutableStateListOf<ProtectedServiceEntry>() }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entries.clear()
        entries.addAll(AccessibilityPersistUtils.listAllAccessibilityServices(context))
        loaded = true
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Protected Accessibility Services", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (!loaded) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading…")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // "How this works" info card — scrolls with content + dismissible
                item {
                    var showInfo by remember { mutableStateOf(true) }
                    if (showInfo) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("How this works", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    IconButton(
                                        onClick = { showInfo = false },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Dismiss",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Toggle ON any app whose accessibility service Protect Yourself " +
                                        "should keep enabled. If Android removes a protected service " +
                                        "(e.g. OEM battery optimisation), the app will re-add it " +
                                        "automatically — but only if WRITE_SECURE_SETTINGS is granted.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "Our own accessibility service is always protected.",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // L1 FIX: WRITE_SECURE_SETTINGS permission status indicator
                item {
                    val isGranted = protect.yourself.features.protectedApps.AccessibilityPersistUtils
                        .isWriteSecureSettingsGranted(context)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isGranted)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = if (isGranted)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = if (isGranted)
                                    "WRITE_SECURE_SETTINGS granted — protection is active"
                                else
                                    "WRITE_SECURE_SETTINGS not granted — protection won't work. See setup instructions below.",
                                fontSize = 12.sp,
                                color = if (isGranted)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                items(entries, key = { it.flatComponent }) { entry ->
                    ServiceRow(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(entry: ProtectedServiceEntry) {
    val context = LocalContext.current
    var protected_ by remember(entry.flatComponent) {
        mutableStateOf(entry.isOurs || ProtectedAppsRegistry.contains(context, entry.flatComponent))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon (or fallback circle with first letter)
            if (entry.icon != null) {
                val bmp = remember(entry.flatComponent) {
                    val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(b)
                    entry.icon.setBounds(0, 0, 96, 96)
                    entry.icon.draw(c)
                    b.asImageBitmap()
                }
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = entry.appLabel.firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.appLabel,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    if (entry.isOurs) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "(this app)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    text = entry.serviceClass,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = protected_,
                enabled = !entry.isOurs,  // Lock our own to ON
                onCheckedChange = { newValue ->
                    if (entry.isOurs) return@Switch  // safety
                    val ok = if (newValue) {
                        ProtectedAppsRegistry.add(context, entry.flatComponent)
                    } else {
                        ProtectedAppsRegistry.remove(context, entry.flatComponent)
                    }
                    if (ok) protected_ = newValue
                }
            )
        }
    }
}
