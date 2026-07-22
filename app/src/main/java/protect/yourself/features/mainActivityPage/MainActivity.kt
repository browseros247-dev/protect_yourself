package protect.yourself.features.mainActivityPage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import protect.yourself.commons.utils.permissionUtils.OnboardingPermissions
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.appPasswordPage.AppLockManager
import protect.yourself.features.appPasswordPage.AppLockScreen
import protect.yourself.features.blockerPage.components.BlockerPageHome
// About tab removed — About info is in Profile page
import protect.yourself.features.mainActivityPage.repository.MainPageScreen
import protect.yourself.features.profilePage.components.ProfilePage
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
 *     (step 1: terms & privacy, step 2: permission checklist — OB-PERM v1.0.66)
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

    // Deep-link target tab. Set when the activity is launched/re-launched
    // via StreakWidget (which puts EXTRA_OPEN_TAB = "Streak" on the intent).
    // Consumed by MainScreen on the next recomposition.
    private var pendingTab by mutableStateOf<MainPageScreen?>(null)

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
                    AppState.MAIN -> MainScreen(
                        requestedTab = pendingTab,
                        onTabConsumed = { pendingTab = null }
                    )
                }
            }
        }

        // Read deep-link extra on initial launch (widget tap when app not running)
        readTabExtra(intent)

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
     * Reference behaviour: re-locks on every ON_RESUME if a lock is configured.
     */
    override fun onResume() {
        super.onResume()
        // Mirror the reference: attempt self-heal every time the app comes to the
        // foreground. This is the highest-value call site — if the service
        // was killed while the app was backgrounded, this re-arms it the
        // instant the user opens the app again.
        try {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this)
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe in onResume failed")
        }
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
                    lockEnabled -> {
                        // LOCKSESSION-01 fix (v1.0.65): reset the retained
                        // lock-screen ViewModel BEFORE Compose recomposes into
                        // AppLockScreen, so not even a single frame shows the
                        // previous session's input. The composable's own
                        // LaunchedEffect(Unit) → beginLockSession() performs
                        // the same reset as a redundant invariant; both are
                        // idempotent. The ViewModel is keyed by class with the
                        // default key, so this resolves to the SAME instance
                        // the composable uses (or creates it lazily on first
                        // lock).
                        try {
                            androidx.lifecycle.ViewModelProvider(
                                this@MainActivity,
                                protect.yourself.features.appPasswordPage.AppLockViewModel.factory(
                                    this@MainActivity,
                                    AppDatabase.getInstance(this@MainActivity)
                                )
                            )[protect.yourself.features.appPasswordPage.AppLockViewModel::class.java]
                                .beginLockSession()
                        } catch (t: Throwable) {
                            Timber.w(t, "Pre-lock session reset failed (non-fatal — composable will retry)")
                        }
                        AppState.LOCKED
                    }
                    else -> AppState.MAIN
                }
                Timber.i("App state: $appState (terms=$termsAccepted, lock=$lockEnabled)")
            } catch (t: Throwable) {
                // BUG-25 fix: default to ONBOARDING instead of MAIN on DB error.
                // Defaulting to MAIN would bypass the terms + lock checks,
                // letting the user into the app without accepting terms or
                // unlocking — a security hole. ONBOARDING is the safe default
                // because it forces the user to re-accept terms (and the lock
                // check re-runs on the next checkAppState call).
                Timber.e(t, "Failed to check app state — defaulting to ONBOARDING (safe fallback)")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                    tag = "MainActivity",
                    message = "checkAppState failed — defaulting to ONBOARDING",
                    extraContext = mapOf("fallback" to "ONBOARDING")
                )
                appState = AppState.ONBOARDING
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Read deep-link extra when the activity is already running and the
        // user taps a widget (e.g. StreakWidget → switch to Streak tab).
        readTabExtra(intent)
    }

    /**
     * Read the EXTRA_OPEN_TAB string from a launch/re-launch intent and set
     * [pendingTab] so [MainScreen] can switch tabs on the next recomposition.
     * No-op when the extra is absent (normal launcher launch).
     */
    private fun readTabExtra(intent: Intent?) {
        val tabRoute = intent?.getStringExtra(EXTRA_OPEN_TAB)
        if (!tabRoute.isNullOrBlank()) {
            pendingTab = MainPageScreen.fromRoute(tabRoute)
            Timber.i("Deep-link: requested tab '$tabRoute' → $pendingTab")
        }
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

/**
 * OB-PERM (v1.0.66): onboarding is now a two-step flow.
 *  - TERMS: welcome + terms & privacy acceptance (unchanged content).
 *  - PERMISSIONS: actionable checklist for the permissions the app's core
 *    features actually need (notifications, battery exemption, exact alarms,
 *    accessibility service) — previously never requested (OB-PERM-01..04).
 */
private enum class OnboardingStep { TERMS, PERMISSIONS }

/**
 * @param onAccept terms accepted; `openAccessibilitySettings` tells the caller
 *  whether the user wanted to jump straight to accessibility settings.
 *  (Kept for API compatibility — the permissions step now owns all settings
 *  navigation, so the new flow always passes `false`.)
 */
@Composable
private fun OnboardingPage(onAccept: (openAccessibilitySettings: Boolean) -> Unit) {
    // OB-PERM-06: rememberSaveable so the current step survives rotation and
    // process re-creation while the user sits on a system permission screen.
    var step by rememberSaveable { mutableStateOf(OnboardingStep.TERMS.name) }

    // Back on the permissions step returns to terms instead of exiting the app.
    BackHandler(enabled = step == OnboardingStep.PERMISSIONS.name) {
        step = OnboardingStep.TERMS.name
    }

    when (OnboardingStep.valueOf(step)) {
        OnboardingStep.TERMS -> OnboardingTermsStep(onContinue = {
            Timber.i("Onboarding: terms accepted → permissions step")
            logOnboardingBreadcrumb("terms_continue")
            step = OnboardingStep.PERMISSIONS.name
        })
        OnboardingStep.PERMISSIONS -> OnboardingPermissionsStep(onFinish = {
            // Terms are persisted + state flips to MAIN inside onAccept.
            onAccept(false)
        })
    }
}

@Composable
private fun OnboardingTermsStep(onContinue: () -> Unit) {
    // OB-PERM-06: keep the checkbox across rotation / process re-creation.
    var agreed by rememberSaveable { mutableStateOf(false) }
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
                Text("• Scheduled app restrictions (internet + launch blocking)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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

        // Onboarding step hint (OB-PERM-03: now an actionable checklist, not passive text)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⚠️ Important: Permissions", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "On the next step you can grant the required permissions — " +
                        "accessibility, notifications and background running. " +
                        "Without these, protection features will not work.",
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

        Button(
            onClick = onContinue,
            enabled = agreed,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandOrange,
                disabledContainerColor = BrandOrange.copy(alpha = 0.3f)
            )
        ) {
            Text("Accept & Continue", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

/**
 * OB-PERM-01/02/03/04: actionable permission checklist with live grant state.
 *
 * - States are re-evaluated (a) after every action result and (b) on ON_RESUME
 *   (returning from a system settings screen) via a LifecycleEventObserver.
 * - Skippable by design: the user can continue with missing permissions; the
 *   skip is logged (kinds only) for diagnostics.
 * - Every settings launch goes through [launchSystemScreen], which catches
 *   ActivityNotFound/Security exceptions and surfaces a toast instead of
 *   crashing (broken OEM Settings providers are common in the wild).
 */
@Composable
private fun OnboardingPermissionsStep(onFinish: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }

    // Refresh grant states when returning from any system screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // After a denied notification prompt, Android won't re-show the dialog
    // (and the second denial is sticky) — route the row to app notification
    // settings instead so the user still has a working path.
    var notifDeniedOnce by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Timber.i("Onboarding: POST_NOTIFICATIONS granted=$granted")
        logOnboardingBreadcrumb("post_notifications_result", mapOf("granted" to granted.toString()))
        if (!granted) notifDeniedOnce = true
        refreshTick++
    }

    val rows = remember(refreshTick) { OnboardingPermissions.evaluate(context.applicationContext) }
    val readyCount = rows.count { !it.applicable || it.granted }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "App Permissions",
            style = MaterialTheme.typography.headlineMedium,
            color = BrandOrange,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Protect Yourself needs a few permissions to keep blocking reliably. " +
                "Tap each item below — granted items are checked automatically.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        rows.forEach { row ->
            PermissionRowCard(
                row = row,
                actionLabel = when (row.kind) {
                    OnboardingPermissions.Kind.NOTIFICATIONS -> "Allow"
                    OnboardingPermissions.Kind.BATTERY_OPTIMIZATION -> "Exempt"
                    OnboardingPermissions.Kind.EXACT_ALARMS -> "Enable"
                    OnboardingPermissions.Kind.ACCESSIBILITY -> "Open"
                },
                onAction = {
                    handleOnboardingPermissionAction(
                        kind = row.kind,
                        context = context,
                        requestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                logOnboardingBreadcrumb("post_notifications_request")
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        openNotificationSettingsDirectly = notifDeniedOnce
                    )
                }
            )
        }

        Text(
            text = "$readyCount of ${rows.size} ready",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        if (!OnboardingPermissions.allRequiredGranted(rows)) {
            Text(
                text = "You can continue without them, but protection will stay limited " +
                    "until the required permissions are granted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = {
                val missing = OnboardingPermissions.missingKinds(rows)
                if (missing.isNotEmpty()) {
                    Timber.w("Onboarding: continuing with missing permissions: $missing")
                    logOnboardingBreadcrumb("finish_with_missing", mapOf("missing" to missing.joinToString(",")))
                } else {
                    Timber.i("Onboarding: all permissions granted")
                    logOnboardingBreadcrumb("finish_all_granted")
                }
                onFinish()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
        ) {
            Text("Continue to App", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun PermissionRowCard(
    row: OnboardingPermissions.Row,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (row.kind) {
                    OnboardingPermissions.Kind.NOTIFICATIONS -> Icons.Filled.Notifications
                    OnboardingPermissions.Kind.BATTERY_OPTIMIZATION -> Icons.Filled.BatterySaver
                    OnboardingPermissions.Kind.EXACT_ALARMS -> Icons.Filled.Alarm
                    OnboardingPermissions.Kind.ACCESSIBILITY -> Icons.Filled.Accessibility
                },
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(28.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = when {
                            row.granted -> "  ✓ Granted"
                            !row.applicable -> "  • Not required here"
                            row.urgency == OnboardingPermissions.Urgency.REQUIRED -> "  • Required"
                            else -> "  • Recommended"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            row.granted -> MaterialTheme.colorScheme.primary
                            row.urgency == OnboardingPermissions.Urgency.REQUIRED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Text(
                    text = row.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (row.applicable && !row.granted) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/** Dispatch a permission-row action with two-level fallbacks. Never throws. */
private fun handleOnboardingPermissionAction(
    kind: OnboardingPermissions.Kind,
    context: android.content.Context,
    requestNotificationPermission: () -> Unit,
    openNotificationSettingsDirectly: Boolean
) {
    Timber.i("Onboarding: permission action kind=$kind")
    when (kind) {
        OnboardingPermissions.Kind.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !openNotificationSettingsDirectly) {
                requestNotificationPermission()
            } else {
                launchSystemScreen(context, OnboardingPermissions.appNotificationSettingsIntent(context), "notification settings")
            }
        OnboardingPermissions.Kind.BATTERY_OPTIMIZATION -> {
            // Direct whitelist dialog first; fall back to the generic list
            // (some OEMs block the direct request intent entirely).
            if (!launchSystemScreen(context, OnboardingPermissions.batteryOptimizationRequestIntent(context), "battery exemption dialog")) {
                if (!launchSystemScreen(context, OnboardingPermissions.batteryOptimizationSettingsIntent(), "battery optimization settings")) {
                    Toast.makeText(context, "Couldn't open battery settings — please exempt Protect Yourself manually.", Toast.LENGTH_LONG).show()
                }
            }
        }
        OnboardingPermissions.Kind.EXACT_ALARMS -> {
            if (!launchSystemScreen(context, OnboardingPermissions.exactAlarmSettingsIntent(context), "alarms & reminders settings")) {
                Toast.makeText(context, "Couldn't open alarm settings — please enable \"Alarms & reminders\" manually.", Toast.LENGTH_LONG).show()
            }
        }
        OnboardingPermissions.Kind.ACCESSIBILITY -> {
            if (!launchSystemScreen(context, OnboardingPermissions.accessibilitySettingsIntent(), "accessibility settings")) {
                Toast.makeText(context, "Couldn't open accessibility settings — please enable Protect Yourself manually.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** startActivity wrapper that fails safe (log + breadcrumb) instead of crashing. */
private fun launchSystemScreen(context: android.content.Context, intent: Intent, what: String): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Timber.e(t, "Onboarding: failed to open $what")
        logOnboardingBreadcrumb("settings_launch_failed", mapOf("target" to what))
        false
    }
}

/** Best-effort crash-log breadcrumb; never throws (crash logger may be uninitialized). */
private fun logOnboardingBreadcrumb(message: String, data: Map<String, String> = emptyMap()) {
    try {
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()
            ?.logBreadcrumb("onboarding", message, data)
    } catch (_: Throwable) {
        // Intentionally swallowed — onboarding must not crash on logging.
    }
}

@Composable
private fun MainScreen(
    requestedTab: MainPageScreen? = null,
    onTabConsumed: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(MainPageScreen.Home) }

    // Phase 5: Schedule editor sub-page state.
    // null = show schedule list; "new" = create new; any other string = edit that key.
    var scheduleEditKey by remember { mutableStateOf<String?>(null) }
    var showScheduleEditor by remember { mutableStateOf(false) }

    // Consume a deep-link tab request (e.g. from widget) by switching
    // to the requested tab once, then clearing the request.
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            selectedTab = requestedTab
            onTabConsumed()
        }
    }

    // Back handler for schedule editor
    androidx.activity.compose.BackHandler(
        enabled = selectedTab == MainPageScreen.Schedule && showScheduleEditor
    ) {
        showScheduleEditor = false
    }

    Scaffold(
        bottomBar = {
            // Hide bottom bar when schedule editor is open
            if (!(selectedTab == MainPageScreen.Schedule && showScheduleEditor)) {
                AppBottomBar(selectedTab) { selectedTab = it }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                MainPageScreen.Home -> BlockerPageHome()
                MainPageScreen.Schedule -> {
                    if (showScheduleEditor) {
                        protect.yourself.features.schedulePage.components.ScheduleEditorPage(
                            editKey = scheduleEditKey,
                            onBack = {
                                showScheduleEditor = false
                                scheduleEditKey = null
                            }
                        )
                    } else {
                        protect.yourself.features.schedulePage.components.SchedulePage(
                            onCreateSchedule = {
                                scheduleEditKey = null
                                showScheduleEditor = true
                            },
                            onEditSchedule = { key ->
                                scheduleEditKey = key
                                showScheduleEditor = true
                            }
                        )
                    }
                }
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
    MainPageScreen.Schedule -> Icons.Filled.Schedule
    MainPageScreen.Profile -> Icons.Filled.Person
}
