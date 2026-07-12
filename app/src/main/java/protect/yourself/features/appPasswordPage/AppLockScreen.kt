package protect.yourself.features.appPasswordPage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
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
                _state.update {
                    it.copy(
                        lockType = lockType,
                        touchIdEnabled = touchIdEnabled,
                        forgotPasswordDisabled = forgotPasswordDisabled,
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
        if (input.isBlank()) return

        viewModelScope.launch {
            try {
                val success = manager.verify(input)
                if (success) {
                    _state.update { it.copy(isUnlocked = true, error = null) }
                    onUnlocked()
                } else {
                    val attempts = _state.value.attempts + 1
                    _state.update {
                        it.copy(
                            error = "Incorrect. Attempt #$attempts",
                            input = "",
                            attempts = attempts
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
                        error = "Unlock failed. Please try again.",
                        input = "",
                        attempts = it.attempts + 1
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
        if (input.isBlank()) return

        // Update state with the new input (for visual feedback)
        _state.update { it.copy(input = input, error = null) }

        viewModelScope.launch {
            try {
                val success = manager.verify(input)
                if (success) {
                    _state.update { it.copy(isUnlocked = true, error = null) }
                    onUnlocked()
                } else {
                    val attempts = _state.value.attempts + 1
                    _state.update {
                        it.copy(
                            error = "Incorrect. Attempt #$attempts",
                            input = "",
                            attempts = attempts
                        )
                    }
                }
            } catch (t: Throwable) {
                // CRASH FIX: same as tryUnlock — never crash on the lock screen.
                Timber.e(t, "tryUnlockWithInput failed")
                _state.update {
                    it.copy(
                        error = "Unlock failed. Please try again.",
                        input = "",
                        attempts = it.attempts + 1
                    )
                }
            }
        }
    }

    /**
     * Unlock via biometric — no password needed.
     */
    fun biometricUnlock(onUnlocked: () -> Unit) {
        _state.update { it.copy(isUnlocked = true, error = null) }
        onUnlocked()
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
    val isLoading: Boolean = true
)

/**
 * AppLockScreen — lock screen shown when app comes to foreground.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val viewModel: AppLockViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppLockViewModel.factory(context, AppDatabase.getInstance(context))
    )
    val state by viewModel.state.collectAsState()
    var biometricShown by remember { mutableStateOf(false) }

    if (state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = BrandOrange)
        }
        return
    }

    // Auto-launch biometric prompt ONCE if Touch ID is enabled
    LaunchedEffect(state.touchIdEnabled, state.lockType) {
        if (state.touchIdEnabled && state.lockType != AppLockType.OFF && !state.isUnlocked && !biometricShown) {
            biometricShown = true
            try {
                launchBiometricPrompt(
                    context as androidx.fragment.app.FragmentActivity,
                    onSuccess = { viewModel.biometricUnlock(onUnlocked) },
                    onFailure = { /* Fall back to manual entry */ }
                )
            } catch (_: Throwable) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = BrandOrange,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enter your ${state.lockType.name.lowercase()}",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        when (state.lockType) {
            AppLockType.PIN -> PinPadUI(state, viewModel, onUnlocked)
            AppLockType.PASSWORD -> PasswordUI(state, viewModel, onUnlocked)
            AppLockType.PATTERN -> PatternUI(state, viewModel, onUnlocked)
            AppLockType.OFF -> { Text("No lock enabled") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        state.error?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Biometric button
        if (state.touchIdEnabled) {
            Button(
                onClick = {
                    try {
                        launchBiometricPrompt(
                            context as androidx.fragment.app.FragmentActivity,
                            onSuccess = { viewModel.biometricUnlock(onUnlocked) },
                            onFailure = {}
                        )
                    } catch (_: Throwable) {}
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Use Biometric")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Forgot password
        if (!state.forgotPasswordDisabled) {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:support@protectyourself.app?subject=Forgot%20Password")
                }
                try {
                    context.startActivity(Intent.createChooser(intent, "Send email"))
                } catch (_: Throwable) {}
            }) {
                Text("Forgot password?")
            }
        }
    }
}

@Composable
private fun PinPadUI(
    state: AppLockState,
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit
) {
    // PIN dots display
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) { index ->
            val filled = index < state.input.length
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (filled) BrandOrange else Color.Transparent)
                    .border(2.dp, BrandOrange, CircleShape)
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    // Numeric keypad
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(9) { index ->
            val num = index + 1
            KeypadButton(num.toString()) {
                val newInput = state.input + num.toString()
                if (newInput.length <= 4) {
                    if (newInput.length == 4) {
                        // Auto-unlock — use tryUnlockWithInput to avoid race condition
                        viewModel.tryUnlockWithInput(newInput, onUnlocked)
                    } else {
                        viewModel.onInputChange(newInput)
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier) }
        item {
            KeypadButton("0") {
                val newInput = state.input + "0"
                if (newInput.length <= 4) {
                    if (newInput.length == 4) {
                        viewModel.tryUnlockWithInput(newInput, onUnlocked)
                    } else {
                        viewModel.onInputChange(newInput)
                    }
                }
            }
        }
        item {
            KeypadButton("⌫") {
                if (state.input.isNotEmpty()) {
                    viewModel.onInputChange(state.input.dropLast(1))
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PasswordUI(
    state: AppLockState,
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit
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
        enabled = state.input.length >= 6,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
    ) {
        Text("Unlock", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PatternUI(
    state: AppLockState,
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit
) {
    val selectedDots = state.input.mapNotNull { it.digitToIntOrNull() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(16.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(9) { index ->
                val dotNum = index + 1
                val isSelected = dotNum in selectedDots
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
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
                            // Don't add more dots after 9 (max grid) or after
                            // an auto-unlock attempt has started.
                            if (state.input.length >= 9 || state.isUnlocked) return@clickable
                            val newInput = state.input + dotNum.toString()
                            viewModel.onInputChange(newInput)
                            // Auto-unlock at 4+ dots — use tryUnlockWithInput
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

    TextButton(onClick = { viewModel.clearInput() }) {
        Text("Clear", color = BrandOrange)
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
                Timber.w("Biometric authentication failed")
            }
        }
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Protect Yourself")
        .setSubtitle("Use your fingerprint or face to unlock")
        .setNegativeButtonText("Use PIN/password instead")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()

    try {
        prompt.authenticate(info)
    } catch (t: Throwable) {
        Timber.e(t, "Failed to launch biometric prompt")
        onFailure()
    }
}
