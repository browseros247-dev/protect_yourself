package protect.yourself.features.mainActivityPage.components

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import protect.yourself.BuildConfig
import protect.yourself.theme.BrandOrange

/**
 * AboutPage — replaces the original Premium tab.
 *
 * Per user choice: Premium tab → About tab.
 *
 * Content:
 *  - App info (name, version, changelog)
 *  - Help (FAQ, how it works, troubleshooting)
 *  - Contact (email support, report bug)
 *  - Legal (privacy policy, terms)
 *  - Credits (open source libraries)
 */
@Composable
fun AboutPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App info
        AboutCard("About protect.yourself") {
            Text(
                text = "protect.yourself is a free, open-source app blocker & focus companion " +
                    "designed to help you overcome porn addiction and build healthier digital habits.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Package: ${BuildConfig.APPLICATION_ID}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Rebuild info
        AboutCard("About this rebuild") {
            Text(
                text = "This is a free, open-source rebuild of the original app, with all payment " +
                    "functionality, ads, and tracking removed. Every feature is available at no cost.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Removed: Subscriptions, in-app purchases, premium features, paywalls\n" +
                    "• Removed: AdMob banner ads\n" +
                    "• Removed: Amplitude + Firebase Analytics\n" +
                    "• Removed: Branch.io (replaced by standard App Links)\n" +
                    "• Removed: Google reCAPTCHA\n" +
                    "• Kept: All blocking features, VPN, accountability partner, widgets",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Help
        AboutCard("Help") {
            AboutLink("FAQ", "Frequently asked questions") {}
            AboutLink("How it works", "Quick start guide") {}
            AboutLink("Troubleshooting", "Common issues + solutions") {}
        }

        // Contact
        AboutCard("Contact") {
            AboutLink("Email support", "support@protectyourself.app") {}
            AboutLink("Report a bug", "Send a bug report via email") {}
        }

        // Legal
        AboutCard("Legal") {
            AboutLink("Privacy policy", "How we handle your data") {}
            AboutLink("Terms of service", "Terms of use") {}
        }

        // Credits
        AboutCard("Credits") {
            Text(
                text = "Original app by PlanProductive — rebuilt from APK via reverse engineering.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Open source libraries:\n" +
                    "• Jetpack Compose (Apache 2.0)\n" +
                    "• Room (Apache 2.0)\n" +
                    "• Firebase (Apache 2.0)\n" +
                    "• Lottie by Airbnb (Apache 2.0)\n" +
                    "• Nunito font by Vernon Adams (OFL)\n" +
                    "• Timber (Apache 2.0)\n" +
                    "• Splitties (Apache 2.0)\n" +
                    "• Joda-Time (Apache 2.0)\n" +
                    "• Gson (Apache 2.0)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = BrandOrange,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun AboutLink(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
