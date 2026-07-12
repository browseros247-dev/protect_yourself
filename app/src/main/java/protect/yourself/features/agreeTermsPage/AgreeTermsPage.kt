package protect.yourself.features.agreeTermsPage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import protect.yourself.theme.BrandOrange

/**
 * AgreeTermsPage — onboarding first screen.
 *
 * Per user choice: Terms & Privacy + Skip option (no Lottie intros, no battery exemption).
 *
 * Flow:
 *  1. User reads terms + privacy policy
 *  2. User ticks "I have read and agree" checkbox
 *  3. Continue button enabled → opens AccessibilityPermissionPage
 *
 * Skip button: defers to later; app shows banner on main screen until agreed.
 */
@Composable
fun AgreeTermsPage(
    onAgree: () -> Unit,
    onSkip: () -> Unit
) {
    var agreed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Welcome to Protect Yourself",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = BrandOrange
        )

        Text(
            text = "A free, open-source app blocker & focus companion.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Terms
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Terms of Service",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "By using Protect Yourself, you agree that:\n\n" +
                        "1. The app is provided 'as is' without warranty.\n" +
                        "2. You are responsible for your usage decisions.\n" +
                        "3. The app may use Accessibility service to block content.\n" +
                        "4. Your data stays on your device unless you enable backup/sync.\n" +
                        "5. You will not use the app to monitor others without consent.\n\n" +
                        "Full terms available at: protectyourself.app/terms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { /* TODO: open browser to terms URL */ }) {
                    Text("Read full terms")
                }
            }
        }

        // Privacy
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Privacy Policy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Protect Yourself respects your privacy:\n\n" +
                        "• No personal data is collected without your consent.\n" +
                        "• Accessibility data is processed locally and never sent to servers.\n" +
                        "• Crash reports (Crashlytics) help us fix bugs — anonymous.\n" +
                        "• Backup/sync is opt-in and requires sign-in.\n" +
                        "• No ads, no trackers, no analytics beyond crash reports.\n\n" +
                        "Full policy: protectyourself.app/privacy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { /* TODO: open browser to privacy URL */ }) {
                    Text("Read full privacy policy")
                }
            }
        }

        // Agree checkbox
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = agreed,
                    onCheckedChange = { agreed = it }
                )
                Text(
                    text = "I have read and agree to the Terms and Privacy Policy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Continue button
        Button(
            onClick = onAgree,
            enabled = agreed,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
        ) {
            Text("Continue", fontWeight = FontWeight.Bold)
        }

        // Skip button (defers onboarding)
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now")
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

/**
 * AccessibilityPermissionPage — guides user to grant accessibility permission.
 *
 * Per user choice: simple flow (no brand-specific instructions).
 */
@Composable
fun AccessibilityPermissionPage(
    onGranted: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enable Accessibility",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = BrandOrange
        )

        Text(
            text = "Protect Yourself needs Accessibility permission to block adult content " +
                "on your device. Without it, blocking will not work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How to enable:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. Tap 'Open Settings' below\n" +
                        "2. Find 'Protect Yourself' in the accessibility list\n" +
                        "3. Toggle it ON\n" +
                        "4. Return to this app",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
        ) {
            Text("Open Settings", fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = onGranted,
            modifier = Modifier.fillMaxWidth(),
            enabled = protect.yourself.features.blockerPage.service.MyAccessibilityService
                .isEnabled(context)
        ) {
            Text("I've enabled it — Continue", fontWeight = FontWeight.Bold)
        }

        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip (blocking will not work)")
        }
    }
}

/**
 * PopupPermissionPage — guides user to grant Display Pop-up Window permission.
 */
@Composable
fun PopupPermissionPage(
    onGranted: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enable Pop-up Window",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = BrandOrange
        )

        Text(
            text = "Protect Yourself needs Display Pop-up Window permission to show " +
                "the block screen over other apps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
        ) {
            Text("Grant permission", fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = onGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue", fontWeight = FontWeight.Bold)
        }

        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip")
        }
    }
}
