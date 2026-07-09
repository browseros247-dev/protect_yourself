package protect.yourself.features.appPasswordPage

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import protect.yourself.features.appPasswordPage.identifiers.AppLockType
import protect.yourself.theme.BrandOrange

/**
 * AppPasswordPage — lock screen shown when app comes to foreground.
 *
 * Phase 4: PIN/password entry. Phase 5: pattern lock + biometric.
 *
 * Behavior:
 *  - User enters stored PIN/password
 *  - On match: app unlocks (callback)
 *  - On mismatch: show error + clear field
 *  - "Forgot password" link: opens email reset flow (if not disabled)
 */
@Composable
fun AppPasswordPage(
    lockType: AppLockType,
    storedPasswordHash: String,
    onUnlocked: () -> Unit,
    onForgotPassword: () -> Unit,
    isForgotPasswordDisabled: Boolean = false
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(0) }

    // Auto-launch biometric prompt if Touch ID is enabled
    // Phase 5: add biometric prompt integration

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Enter ${lockType.name.lowercase()}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                label = { Text(lockType.name) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (lockType) {
                        AppLockType.PIN -> KeyboardType.NumberPassword
                        else -> KeyboardType.Password
                    }
                ),
                singleLine = true,
                isError = error != null,
                supportingText = {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    // Phase 5: hash input + compare to storedPasswordHash
                    // For now, simple equality check (Phase 6 will hash with PBKDF2)
                    if (input == storedPasswordHash) {  // TODO Phase 6: use proper hash compare
                        onUnlocked()
                    } else {
                        attempts++
                        error = "Incorrect ${lockType.name.lowercase()}. Attempt #$attempts"
                        input = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = input.isNotBlank()
            ) {
                Text("Unlock")
            }

            if (!isForgotPasswordDisabled) {
                TextButton(onClick = onForgotPassword) {
                    Text("Forgot password?")
                }
            }
        }
    }
}

/**
 * Check if biometric authentication is available on this device.
 */
fun isBiometricAvailable(context: android.content.Context): Boolean {
    val biometricManager = BiometricManager.from(context)
    return biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Launch biometric prompt.
 * Phase 5: full implementation with BiometricPrompt.
 */
fun launchBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onFailure: () -> Unit
) {
    val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        }
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock protect.yourself")
        .setSubtitle("Use your fingerprint or face to unlock")
        .setNegativeButtonText("Use PIN/password instead")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()

    prompt.authenticate(info)
}
