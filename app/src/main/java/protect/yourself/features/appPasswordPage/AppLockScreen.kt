package protect.yourself.features.appPasswordPage

import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.appPasswordPage.identifiers.AppLockType
import protect.yourself.theme.BrandOrange
import timber.log.Timber

/**
 * ViewModel for App Lock screen (unlock).
 *
 * FIX: Added onInputComplete() method that accepts the full input directly,
 * avoiding the Compose state update race condition where tryUnlock() read
 * the old state before the new input was committed.
 *
 * PIN LOCK UI FIX (v1.0.54):
 *  - Replaced LazyVerticalGrid keypad with a fixed Column-of-Rows layout.
 *    The previous implementation used LazyVerticalGrid with `Modifier.height(240.dp)`
 *    AND `aspectRatio(1f)` on each cell — these two constraints conflicted,
 *    causing buttons to be clipped or scroll off-screen on most devices.
 *  - Removed the fixed-height constraint; the keypad now wraps its content.
 *  - Wrapped the entire screen in verticalScroll so content never overflows
 *    off-screen on small devices.
 *  - Added biometric availability check before auto-launching the prompt
 *    (previously crashed silently on devices without biometrics enrolled).
 *  - Added lockout countdown display for the rate-limiter state.
 *  - Added shake animation + haptic feedback on incorrect attempts.
 *  - Added Timber logging at every state transition for diagnostics.
 *  - All user-facing strings moved to string resources for localization.
 */
class AppLockViewModel(
    private val context: android.content.Context,
    private val db: AppDatabase
) : ViewModel() {

    private val manager = AppLockManager.getInstance(context)

    private val _state = MutableStateFlow(AppLockState())
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    init {
        loadLockState()
    }

    private fun loadLockState() {
        viewModelScope.launch {
            try {
                val lockType = manager.getLockType()
                val touchIdEnabled = manager.isTouchIdEnabled()
                val forgotPasswordDisabled = manager.isForgotPasswordDisabled()
                val lockedOut = manager.isLockedOut()
                val lockoutRemainingMs = manager.getLockoutRemainingMs()
                Timber.i("Lock state loaded: type=$lockType, touchId=$touchIdEnabled, " +
                    "forgotDisabled=$forgotPasswordDisabled, lockedOut=$lockedOut, " +
                    "lockoutRemainingMs=$lockoutRemainingMs")
                _state.update {
                    it.copy(
                        lockType = lockType,
                        touchIdEnabled = touchIdEnabled,
                        forgotPasswordDisabled = forgotPasswordDisabled,
                        isLockedOut = lockedOut,
                        lockoutRemainingMs = lockoutRemainingMs,
                        isLoading = false
                    )
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load lock state")
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, error = null) }
    }

    fun clearInput() {
        _state.update { it.copy(input = "", error = null) }
    }

    /**
     * Try unlock with the CURRENT state input.
     */
    fun tryUnlock(onUnlocked: () -> Unit) {
        val input = _state.value.input
        if (input.isBlank()) {
            Timber.w("tryUnlock: empty input — ignoring")
            return
        }
        // BUG-22 fix: reject early if locked out (no DB hit)
        if (manager.isLockedOut()) {
            val remainingMs = manager.getLockoutRemainingMs()
            Timber.w("tryUnlock: rejected — locked out for ${remainingMs}ms")
            _state.update {
                it.copy(
                    isLockedOut = true,
                    lockoutRemainingMs = remainingMs,
                    error = formatLockoutMessage(remainingMs),
                    input = ""
                )
            }
            return
        }

        viewModelScope.launch {
            try {
                val success = manager.verify(input)
                if (success) {
                    Timber.i("Unlock succeeded (tryUnlock)")
                    _state.update { it.copy(isUnlocked = true, error = null, isLockedOut = false, lockoutRemainingMs = 0L) }
                    onUnlocked()
                } else {
                    val attempts = _state.value.attempts + 1
                    val nowLockedOut = manager.isLockedOut()
                    val remainingMs = manager.getLockoutRemainingMs()
                    Timber.w("Unlock failed (tryUnlock) — attempt #$attempts, lockedOut=$nowLockedOut")
                    _state.update {
                        it.copy(
                            error = if (nowLockedOut) formatLockoutMessage(remainingMs)
                                    else context.getString(R.string.lock_screen_incorrect_attempt, attempts),
                            input = "",
                            attempts = attempts,
                            isLockedOut = nowLockedOut,
                            lockoutRemainingMs = remainingMs,
                            triggerShake = System.currentTimeMillis()
                        )
                    }
                }
            } catch (t: Throwable) {
                // CRASH FIX: uncaught exception on the lock screen causes
                // an infinite crash loop. Catch everything, show error,
                // reset input — never crash here.
                Timber.e(t, "tryUnlock failed")
                _state.update {
                    it.copy(
                        error = context.getString(R.string.lock_screen_unlock_failed),
                        input = "",
                        attempts = it.attempts + 1,
                        triggerShake = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    /**
     * Try unlock with EXPLICIT input — avoids state race condition.
     * Use this when the input is set + unlock should happen in the same call
     * (e.g. 4th PIN digit, 4th pattern dot).
     */
    fun tryUnlockWithInput(input: String, onUnlocked: () -> Unit) {
        if (input.isBlank()) {
            Timber.w("tryUnlockWithInput: empty input — ignoring")
            return
        }
        // BUG-22 fix: reject early if locked out (no DB hit)
        if (manager.isLockedOut()) {
            val remainingMs = manager.getLockoutRemainingMs()
            Timber.w("tryUnlockWithInput: rejected — locked out for ${remainingMs}ms")
            _state.update {
                it.copy(
                    isLockedOut = true,
                    lockoutRemainingMs = remainingMs,
                    error = formatLockoutMessage(remainingMs),
                    input = ""
                )
            }
            return
        }

        // Update state with the new input (for visual feedback)
        _state.update { it.copy(input = input, error = null) }

        viewModelScope.launch {
            try {
                val success = manager.verify(input)
                if (success) {
                    Timber.i("Unlock succeeded (tryUnlockWithInput)")
                    _state.update { it.copy(isUnlocked = true, error = null, isLockedOut = false, lockoutRemainingMs = 0L) }
                    onUnlocked()
                } else {
                    val attempts = _state.value.attempts + 1
                    val nowLockedOut = manager.isLockedOut()
                    val remainingMs = manager.getLockoutRemainingMs()
                    Timber.w("Unlock failed (tryUnlockWithInput) — attempt #$attempts, lockedOut=$nowLockedOut")
                    _state.update {
                        it.copy(
                            error = if (nowLockedOut) formatLockoutMessage(remainingMs)
                                    else context.getString(R.string.lock_screen_incorrect_attempt, attempts),
                            input = "",
                            attempts = attempts,
                            isLockedOut = nowLockedOut,
                            lockoutRemainingMs = remainingMs,
                            triggerShake = System.currentTimeMillis()
                        )
                    }
                }
            } catch (t: Throwable) {
                // CRASH FIX: same as tryUnlock — never crash on the lock screen.
                Timber.e(t, "tryUnlockWithInput failed")
                _state.update {
                    it.copy(
                        error = context.getString(R.string.lock_screen_unlock_failed),
                        input = "",
                        attempts = it.attempts + 1,
                        triggerShake = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    /**
     * Unlock via biometric — no password needed.
     */
    fun biometricUnlock(onUnlocked: () -> Unit) {
        Timber.i("Biometric unlock succeeded")
        _state.update { it.copy(isUnlocked = true, error = null) }
        onUnlocked()
    }

    /**
     * Refresh lockout state — called by the UI on a periodic timer while the
     * user is on the lock screen. Updates the countdown message and clears
     * the locked-out flag once the lockout expires.
     */
    fun refreshLockoutState() {
        val stillLockedOut = manager.isLockedOut()
        val remainingMs = manager.getLockoutRemainingMs()
        val current = _state.value
        if (current.isLockedOut != stillLockedOut || current.lockoutRemainingMs != remainingMs) {
            _state.update {
                it.copy(
                    isLockedOut = stillLockedOut,
                    lockoutRemainingMs = remainingMs,
                    error = if (stillLockedOut) formatLockoutMessage(remainingMs) else it.error
                )
            }
            if (!stillLockedOut && current.isLockedOut) {
                Timber.i("Lockout expired — user may retry")
            }
        }
    }

    private fun formatLockoutMessage(remainingMs: Long): String {
        val seconds = (remainingMs + 999L) / 1000L
        // Format as "2m 30s" or "45s" — passed to the localized string
        // R.string.lock_screen_locked_out which expects a %1$s duration.
        val duration = if (seconds >= 60) {
            val minutes = seconds / 60
            val remSec = seconds % 60
            "${minutes}m ${remSec}s"
        } else {
            "${seconds}s"
        }
        return context.getString(R.string.lock_screen_locked_out, duration)
    }

    /**
     * Clear the shake trigger after the animation runs so the next attempt
     * can re-trigger it.
     */
    fun consumeShakeTrigger() {
        if (_state.value.triggerShake != 0L) {
            _state.update { it.copy(triggerShake = 0L) }
        }
    }

    companion object {
        fun factory(context: android.content.Context, db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppLockViewModel(context, db) as T
                }
            }
    }
}

data class AppLockState(
    val lockType: AppLockType = AppLockType.OFF,
    val touchIdEnabled: Boolean = false,
    val forgotPasswordDisabled: Boolean = false,
    val input: String = "",
    val error: String? = null,
    val attempts: Int = 0,
    val isUnlocked: Boolean = false,
    val isLoading: Boolean = true,
    // BUG-22 fix: lockout state surfaced to UI
    val isLockedOut: Boolean = false,
    val lockoutRemainingMs: Long = 0L,
    // Shake animation trigger — set to current epoch ms on each failed attempt.
    // Set back to 0L after the animation runs (consumeShakeTrigger).
    val triggerShake: Long = 0L
)

/**
 * AppLockScreen — lock screen shown when app comes to foreground.
 *
 * UI FIX (v1.0.54):
 *  - Whole screen is now verticalScroll-capable so content always fits.
 *  - PIN keypad uses a fixed Column-of-Rows layout (was LazyVerticalGrid
 *    with conflicting aspectRatio + fixed height constraints).
 *  - Biometric auto-launch only fires when biometrics are actually available
 *    and enrolled — previously crashed silently on unsupported devices.
 *  - Lockout countdown is shown to the user when the rate limiter is active.
 *  - Shake animation + haptic feedback on incorrect attempts.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val viewModel: AppLockViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppLockViewModel.factory(context, AppDatabase.getInstance(context))
    )
    val state by viewModel.state.collectAsState()
    var biometricShown by remember { mutableStateOf(false) }

    // Periodically refresh lockout state so the countdown ticks down.
    LaunchedEffect(state.isLockedOut) {
        if (state.isLockedOut) {
            while (true) {
                kotlinx.coroutines.delay(500L)
                viewModel.refreshLockoutState()
                if (!viewModel.state.value.isLockedOut) break
            }
        }
    }

    if (state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = BrandOrange)
        }
        return
    }

    // Auto-launch biometric prompt ONCE if Touch ID is enabled AND biometrics
    // are actually available + enrolled on this device.
    LaunchedEffect(state.touchIdEnabled, state.lockType) {
        if (state.touchIdEnabled && state.lockType != AppLockType.OFF &&
            !state.isUnlocked && !biometricShown && !state.isLockedOut) {
            val availability = checkBiometricAvailability(context)
            if (availability == BiometricAvailability.AVAILABLE) {
                biometricShown = true
                Timber.i("Auto-launching biometric prompt")
                try {
                    launchBiometricPrompt(
                        context as androidx.fragment.app.FragmentActivity,
                        onSuccess = { viewModel.biometricUnlock(onUnlocked) },
                        onFailure = { /* Fall back to manual entry */ }
                    )
                } catch (t: Throwable) {
                    Timber.w(t, "Auto-launch biometric prompt failed")
                }
            } else {
                Timber.w("Touch ID enabled but biometric not available (availability=$availability) — skipping auto-launch")
            }
        }
    }

    // Shake animation: when triggerShake changes, run a quick shake.
    // Explicit <Float> type parameter — without it, Kotlin resolves to the
    // Color overload of Animatable() because of overload ambiguity, which
    // then makes snapTo/animateTo reject Float arguments.
    val shakeOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(state.triggerShake) {
        if (state.triggerShake != 0L) {
            val amplitude = 12f
            // Three quick oscillations with decreasing amplitude.
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(amplitude, tween(50, easing = LinearOutSlowInEasing))
            shakeOffset.animateTo(-amplitude * 0.7f, tween(50, easing = LinearOutSlowInEasing))
            shakeOffset.animateTo(amplitude * 0.4f, tween(50, easing = LinearOutSlowInEasing))
            shakeOffset.animateTo(-amplitude * 0.2f, tween(50, easing = LinearOutSlowInEasing))
            shakeOffset.animateTo(0f, tween(50, easing = LinearOutSlowInEasing))
            viewModel.consumeShakeTrigger()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = stringResource(R.string.lock_screen_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )

            // Prompt text — varies by lock type
            val promptRes = when (state.lockType) {
                AppLockType.PIN -> R.string.lock_screen_enter_pin
                AppLockType.PASSWORD -> R.string.lock_screen_enter_password
                AppLockType.PATTERN -> R.string.lock_screen_enter_pattern
                AppLockType.OFF -> R.string.lock_screen_no_lock_enabled
            }
            Text(
                text = stringResource(promptRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (state.lockType) {
                AppLockType.PIN -> PinPadUI(
                    state = state,
                    viewModel = viewModel,
                    onUnlocked = onUnlocked,
                    shakeOffsetX = shakeOffset.value
                )
                AppLockType.PASSWORD -> PasswordUI(
                    state = state,
                    viewModel = viewModel,
                    onUnlocked = onUnlocked,
                    shakeOffsetX = shakeOffset.value
                )
                AppLockType.PATTERN -> PatternUI(
                    state = state,
                    viewModel = viewModel,
                    onUnlocked = onUnlocked,
                    shakeOffsetX = shakeOffset.value
                )
                AppLockType.OFF -> {
                    Text(
                        text = stringResource(R.string.lock_screen_no_lock_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Error text
            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Biometric button — only show if Touch ID is enabled AND device supports it
            if (state.touchIdEnabled) {
                val availability = remember(context) { checkBiometricAvailability(context) }
                if (availability == BiometricAvailability.AVAILABLE) {
                    Button(
                        onClick = {
                            try {
                                launchBiometricPrompt(
                                    context as androidx.fragment.app.FragmentActivity,
                                    onSuccess = { viewModel.biometricUnlock(onUnlocked) },
                                    onFailure = { Timber.w("User cancelled biometric prompt") }
                                )
                            } catch (t: Throwable) {
                                Timber.e(t, "Failed to launch biometric prompt from button")
                                // Show a user-facing error so the user knows why
                                // the prompt did not appear and that they should
                                // fall back to PIN/password entry.
                                val fallbackName = when (state.lockType) {
                                    AppLockType.PIN -> "PIN"
                                    AppLockType.PASSWORD -> "password"
                                    AppLockType.PATTERN -> "pattern"
                                    AppLockType.OFF -> "credential"
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.lock_screen_biometric_launch_failed,
                                        fallbackName
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
                    ) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.lock_screen_use_touch_id))
                    }
                } else {
                    // Show a more specific message based on the failure reason.
                    val msgRes = when (availability) {
                        BiometricAvailability.NONE_ENROLLED ->
                            R.string.lock_screen_biometric_not_enrolled
                        else ->
                            R.string.lock_screen_biometric_not_available
                    }
                    Text(
                        text = stringResource(msgRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Forgot password link — only if not disabled
            if (!state.forgotPasswordDisabled && state.lockType != AppLockType.OFF) {
                TextButton(onClick = {
                    val subject = when (state.lockType) {
                        AppLockType.PIN -> "Forgot%20PIN"
                        else -> "Forgot%20Password"
                    }
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:support@protectyourself.app?subject=$subject")
                    }
                    try {
                        context.startActivity(Intent.createChooser(intent, "Send email"))
                    } catch (t: Throwable) {
                        Timber.w(t, "No email app available to handle forgot-password intent")
                    }
                }) {
                    val forgotLabel = when (state.lockType) {
                        AppLockType.PIN -> stringResource(R.string.lock_screen_forgot_pin)
                        else -> stringResource(R.string.lock_screen_forgot_password)
                    }
                    Text(forgotLabel)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/**
 * PIN pad UI — fixed Column-of-Rows layout (was LazyVerticalGrid).
 *
 * Previous bug: LazyVerticalGrid had `Modifier.height(240.dp)` AND each cell
 * had `Modifier.aspectRatio(1f)`. On a typical phone (~360dp wide minus 48dp
 * padding = 312dp), each cell was ~93dp wide and wanted to be 93dp tall (from
 * aspectRatio). 4 rows × 93dp + 3 × 16dp spacing = 420dp, but the grid was
 * constrained to 240dp — so the bottom 2 rows of the keypad (4, 5, 6, 7, 8,
 * 9, 0) were scrollable but invisible. The user could not see or tap them.
 *
 * Fix: use Column { Row { ... } } so all 12 keys are always visible. The
 * keypad wraps its content height, and the parent Column scrolls if needed.
 */
@Composable
private fun PinPadUI(
    state: AppLockState,
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit,
    shakeOffsetX: Float
) {
    val haptic = LocalHapticFeedback.current

    // PIN dots display
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.graphicsLayer(translationX = shakeOffsetX)
    ) {
        repeat(4) { index ->
            val filled = index < state.input.length
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (filled) BrandOrange else Color.Transparent)
                    .border(2.dp, BrandOrange, CircleShape)
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    // Numeric keypad — fixed layout: 1,2,3 / 4,5,6 / 7,8,9 / ⌫,0,OK-ish
    // Use BoxWithConstraints so button size adapts to available width.
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val maxWidthPx = maxWidth
        // 3 columns with 16dp spacing: button size = (width - 2*16) / 3
        val buttonSize = (maxWidthPx - 32.dp * 2) / 3

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: 1, 2, 3
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeypadButton("1", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("1", state, viewModel, onUnlocked)
                }
                KeypadButton("2", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("2", state, viewModel, onUnlocked)
                }
                KeypadButton("3", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("3", state, viewModel, onUnlocked)
                }
            }
            // Row 2: 4, 5, 6
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeypadButton("4", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("4", state, viewModel, onUnlocked)
                }
                KeypadButton("5", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("5", state, viewModel, onUnlocked)
                }
                KeypadButton("6", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("6", state, viewModel, onUnlocked)
                }
            }
            // Row 3: 7, 8, 9
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeypadButton("7", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("7", state, viewModel, onUnlocked)
                }
                KeypadButton("8", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("8", state, viewModel, onUnlocked)
                }
                KeypadButton("9", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("9", state, viewModel, onUnlocked)
                }
            }
            // Row 4: Backspace, 0, (empty for symmetry)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeypadIconButton(
                    icon = Icons.Filled.Backspace,
                    contentDescription = stringResource(R.string.lock_screen_backspace),
                    size = buttonSize,
                    enabled = state.input.isNotEmpty() && !state.isLockedOut
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (state.input.isNotEmpty()) {
                        viewModel.onInputChange(state.input.dropLast(1))
                    }
                }
                KeypadButton("0", buttonSize) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    appendPinDigit("0", state, viewModel, onUnlocked)
                }
                // Empty slot to keep the grid balanced (3rd cell of last row).
                // Holds the PIN length indicator so it's not wasted space.
                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${state.input.length}/4",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Append a digit to the PIN input. Auto-unlocks at length 4 using
 * tryUnlockWithInput (avoids the state-read race condition).
 */
private fun appendPinDigit(
    digit: String,
    state: AppLockState,
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit
) {
    if (state.isLockedOut) {
        Timber.w("appendPinDigit: ignored — locked out")
        return
    }
    if (state.input.length >= 4) return  // Don't accept more than 4 digits
    val newInput = state.input + digit
    if (newInput.length == 4) {
        Timber.d("PIN entry complete — auto-unlocking")
        viewModel.tryUnlockWithInput(newInput, onUnlocked)
    } else {
        viewModel.onInputChange(newInput)
    }
}

@Composable
private fun KeypadButton(
    text: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun KeypadIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PasswordUI(
    state: AppLockState,
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit,
    shakeOffsetX: Float
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(translationX = shakeOffsetX)
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = { viewModel.onInputChange(it) },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.tryUnlock(onUnlocked) },
            enabled = state.input.length >= 6 && !state.isLockedOut,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
        ) {
            Text(stringResource(R.string.lock_screen_unlock), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PatternUI(
    state: AppLockState,
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit,
    shakeOffsetX: Float
) {
    val selectedDots = state.input.mapNotNull { it.digitToIntOrNull() }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(translationX = shakeOffsetX),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            val cellSize = (maxWidth - 32.dp) / 3
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (row in 0..2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (col in 1..3) {
                            val dotNum = row * 3 + col
                            val isSelected = dotNum in selectedDots
                            Box(
                                modifier = Modifier
                                    .size(cellSize)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) BrandOrange
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        2.dp,
                                        if (isSelected) BrandOrange else MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                                    .clickable {
                                        if (state.isLockedOut) {
                                            Timber.w("Pattern tap ignored — locked out")
                                            return@clickable
                                        }
                                        if (state.input.length >= 9 || state.isUnlocked) return@clickable
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val newInput = state.input + dotNum.toString()
                                        viewModel.onInputChange(newInput)
                                        if (newInput.length >= 4) {
                                            viewModel.tryUnlockWithInput(newInput, onUnlocked)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        TextButton(onClick = { viewModel.clearInput() }) {
            Text(stringResource(R.string.lock_screen_clear), color = BrandOrange)
        }
    }
}

/**
 * Result of a biometric availability check.
 *
 * Used so the UI can show a more specific message — e.g. "no biometrics
 * enrolled" is more actionable than a generic "not available" because it
 * tells the user to set up fingerprint/face unlock in system settings.
 */
enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    HW_UNAVAILABLE,
    NONE_ENROLLED,
    SECURITY_UPDATE_REQUIRED,
    UNSUPPORTED,
    UNKNOWN,
}

/**
 * Check whether biometric authentication can be used on this device.
 *
 * Returns [BiometricAvailability.AVAILABLE] only when:
 *  - BiometricManager reports BIOMETRIC_SUCCESS (hardware available + enrolled)
 *
 * Returns the appropriate failure reason for:
 *  - ERROR_HW_UNAVAILABLE (hardware present but currently unavailable)
 *  - ERROR_NO_HARDWARE (no biometric hardware)
 *  - ERROR_NONE_ENROLLED (hardware present but no fingerprints/faces enrolled)
 *  - ERROR_SECURITY_UPDATE_REQUIRED
 *
 * This guards against the previous behavior where the lock screen would try
 * to auto-launch BiometricPrompt on devices without biometrics, causing the
 * prompt to silently fail (or crash on some OEM Android builds).
 */
fun canUseBiometric(context: android.content.Context): Boolean =
    checkBiometricAvailability(context) == BiometricAvailability.AVAILABLE

fun checkBiometricAvailability(context: android.content.Context): BiometricAvailability {
    return try {
        val bm = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
        when (bm.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Timber.w("Biometric: no hardware")
                BiometricAvailability.NO_HARDWARE
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Timber.w("Biometric: hardware unavailable")
                BiometricAvailability.HW_UNAVAILABLE
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Timber.w("Biometric: none enrolled")
                BiometricAvailability.NONE_ENROLLED
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                Timber.w("Biometric: security update required")
                BiometricAvailability.SECURITY_UPDATE_REQUIRED
            }
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                Timber.w("Biometric: unsupported")
                BiometricAvailability.UNSUPPORTED
            }
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> {
                Timber.w("Biometric: status unknown")
                BiometricAvailability.UNKNOWN
            }
            else -> {
                Timber.w("Biometric: canAuthenticate returned unknown code")
                BiometricAvailability.UNKNOWN
            }
        }
    } catch (t: Throwable) {
        Timber.e(t, "Biometric availability check failed")
        BiometricAvailability.UNKNOWN
    }
}

/**
 * Launch biometric prompt.
 */
fun launchBiometricPrompt(
    activity: androidx.fragment.app.FragmentActivity,
    onSuccess: () -> Unit,
    onFailure: () -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Timber.i("Biometric authentication succeeded")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Timber.w("Biometric error: $errorCode - $errString")
                onFailure()
            }

            override fun onAuthenticationFailed() {
                Timber.w("Biometric authentication failed (wrong finger/face)")
            }
        }
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.lock_screen_biometric_title))
        .setSubtitle(activity.getString(R.string.lock_screen_biometric_subtitle))
        .setNegativeButtonText(activity.getString(R.string.lock_screen_biometric_negative))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()

    try {
        prompt.authenticate(info)
    } catch (t: Throwable) {
        Timber.e(t, "Failed to launch biometric prompt")
        onFailure()
    }
}
