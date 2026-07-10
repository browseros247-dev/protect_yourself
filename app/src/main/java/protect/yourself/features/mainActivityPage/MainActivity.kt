package protect.yourself.features.mainActivityPage

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.appPasswordPage.AppLockManager
import protect.yourself.features.appPasswordPage.AppLockScreen
import protect.yourself.features.blockerPage.components.BlockerPageHome
// About tab removed — About info is in Profile page
import protect.yourself.features.mainActivityPage.repository.MainPageScreen
import protect.yourself.features.profilePage.components.ProfilePage
import protect.yourself.features.streakPage.components.StreakPage
import protect.yourself.theme.AppTheme
import protect.yourself.theme.BrandOrange
import timber.log.Timber

/**
 * Main launcher activity.
 *
 * Extends FragmentActivity for BiometricPrompt support (App Lock).
 *
 * Launch flow:
 *  1. Check if terms accepted (first launch)
 *  2. If not accepted → show OnboardingPage
 *  3. If accepted → check if app lock enabled
 *  4. If locked → show AppLockScreen
 *  5. If unlocked → show MainScreen
 *
 * FIX: setContent is called ONCE in onCreate. State changes are observed
 * by Compose via mutableStateOf, which triggers recomposition automatically.
 * Previous version called setContent multiple times (once per state change),
 * which created multiple composition trees and caused UI glitches.
 */
class MainActivity : FragmentActivity() {

    // Observable state — Compose watches this for changes
    private var appState by mutableStateOf(AppState.LOADING)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Call setContent ONCE — Compose will recompose when appState changes
        setContent {
            AppTheme {
                when (appState) {
                    AppState.LOADING -> LoadingScreen()
                    AppState.ONBOARDING -> OnboardingPage(
                        onAccept = { openAccessibilitySettings ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val switchValues = SwitchStatusValues(
                                        AppDatabase.getInstance(this@MainActivity).switchStatusDao()
                                    )
                                    switchValues.storeSwitchStatus(SwitchIdentifier.TERMS_APPROVE_STATUS, true)
                                    Timber.i("Terms accepted")
                                } catch (t: Throwable) {
                                    Timber.e(t, "Failed to save terms acceptance")
                                }
                                appState = AppState.MAIN
                            }
                        }
                    )
                    AppState.LOCKED -> AppLockScreen(onUnlocked = {
                        appState = AppState.MAIN
                    })
                    AppState.MAIN -> MainScreen()
                }
            }
        }

        // Check app state on background thread
        checkAppState()
    }

    /**
     * Re-check lock state when the user returns to the app.
     *
     * CRITICAL: Without this, the app lock only appears on fresh launch (when
     * onCreate is called). When the user backgrounds the app (home button,
     * recent apps, etc.) and returns, onResume is called but onCreate is NOT.
     * So the lock screen never appeared on app return — the user could bypass
     * the lock by simply backgrounding + returning.
     *
     * NopoX behaviour: re-locks on every ON_RESUME if a lock is configured.
     */
    override fun onResume() {
        super.onResume()
        // Only re-lock if the app was previously in MAIN state (i.e. the user
        // was inside the app). Don't re-lock during initial launch (LOADING)
        // or during onboarding.
        if (appState == AppState.MAIN) {
            checkAppState()
        }
    }

    private fun checkAppState() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val switchValues = SwitchStatusValues(
                    AppDatabase.getInstance(this@MainActivity).switchStatusDao()
                )
                val termsAccepted = switchValues.isTermsApproved()
                val lockEnabled = if (termsAccepted) {
                    AppLockManager.getInstance(this@MainActivity).isLockEnabled()
                } else false

                appState = when {
                    !termsAccepted -> AppState.ONBOARDING
                    lockEnabled -> AppState.LOCKED
                    else -> AppState.MAIN
                }
                Timber.i("App state: $appState (terms=$termsAccepted, lock=$lockEnabled)")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to check app state — defaulting to MAIN")
                appState = AppState.MAIN
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
    }
}

enum class AppState { LOADING, ONBOARDING, LOCKED, MAIN }

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = BrandOrange)
            Text(
                text = "Loading Protect Yourself…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun OnboardingPage(onAccept: (openAccessibilitySettings: Boolean) -> Unit) {
    val context = LocalContext.current
    var agreed by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        Text(
            text = "Welcome to Protect Yourself",
            style = MaterialTheme.typography.headlineMedium,
            color = BrandOrange,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "A free, open-source app blocker & focus companion to help you overcome porn addiction and build healthier digital habits.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Features card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Key Features:", fontWeight = FontWeight.Bold, color = BrandOrange)
                Text("• Block adult content via keyword matching (1,189+ keywords)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text("• VPN DNS filtering (Cloudflare Family, OpenDNS, etc.)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text("• Stop Me focus mode with widgets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text("• Streak tracking with achievements", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text("• App lock (PIN/Password/Pattern + Biometric)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text("• Anti-uninstall protection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        // Terms card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Terms & Privacy", fontWeight = FontWeight.Bold, color = BrandOrange)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• The app is provided 'as is' without warranty.\n" +
                        "• Accessibility data is processed locally and never sent to servers.\n" +
                        "• No ads, no trackers, no analytics.\n" +
                        "• Your data stays on your device unless you enable backup/sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Accessibility setup hint
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⚠️ Important: Enable Accessibility", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "After accepting, go to Settings → Accessibility → Protect Yourself → ON. " +
                        "Without this, content blocking won't work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
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
                Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                Text(
                    text = "I have read and agree to the Terms and Privacy Policy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Accept & open accessibility settings
        Button(
            onClick = {
                // Open accessibility settings
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {}
                // Accept terms + transition to main
                onAccept(true)
            },
            enabled = agreed,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandOrange,
                disabledContainerColor = BrandOrange.copy(alpha = 0.3f)
            )
        ) {
            Text("Accept & Open Accessibility Settings", fontWeight = FontWeight.Bold)
        }

        // Skip (also accepts terms, but doesn't open settings)
        TextButton(
            onClick = { onAccept(false) },
            modifier = Modifier.fillMaxWidth(),
            enabled = agreed
        ) {
            Text("Accept & Continue to App")
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun MainScreen() {
    var selectedTab by remember { mutableStateOf(MainPageScreen.Home) }

    Scaffold(
        bottomBar = { AppBottomBar(selectedTab) { selectedTab = it } }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                MainPageScreen.Home -> BlockerPageHome()
                MainPageScreen.Streak -> StreakPage()
                MainPageScreen.Profile -> ProfilePage()
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    selected: MainPageScreen,
    onSelect: (MainPageScreen) -> Unit
) {
    NavigationBar {
        MainPageScreen.all.forEach { screen ->
            NavigationBarItem(
                selected = selected == screen,
                onClick = { onSelect(screen) },
                icon = {
                    Icon(
                        imageVector = screen.vectorIcon(),
                        contentDescription = stringResource(screen.resourceId)
                    )
                },
                label = { Text(stringResource(screen.resourceId)) }
            )
        }
    }
}

private fun MainPageScreen.vectorIcon(): ImageVector = when (this) {
    MainPageScreen.Home -> Icons.Filled.Shield
    MainPageScreen.Streak -> Icons.Filled.LocalFireDepartment
    MainPageScreen.Profile -> Icons.Filled.Person
}
