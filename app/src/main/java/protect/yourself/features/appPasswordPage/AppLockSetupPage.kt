package protect.yourself.features.appPasswordPage

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
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
 * ViewModel for App Lock setup.
 */
class AppLockSetupViewModel(
    private val context: android.content.Context,
    private val db: AppDatabase
) : ViewModel() {

    private val manager = AppLockManager.getInstance(context)

    private val _state = MutableStateFlow(AppLockSetupState())
    val state: StateFlow<AppLockSetupState> = _state.asStateFlow()

    init {
        loadCurrentState()
    }

    private fun loadCurrentState() {
        viewModelScope.launch {
            try {
                val lockType = manager.getLockType()
                val touchIdEnabled = manager.isTouchIdEnabled()
                val forgotPasswordDisabled = manager.isForgotPasswordDisabled()
                _state.update {
                    it.copy(
                        currentLockType = lockType,
                        touchIdEnabled = touchIdEnabled,
                        forgotPasswordDisabled = forgotPasswordDisabled,
                        isLoading = false
                    )
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load app lock state")
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun selectLockType(type: AppLockType) {
        _state.update { it.copy(selectedLockType = type, setupStep = if (type != AppLockType.OFF) SetupStep.ENTER else SetupStep.NONE, error = null) }
    }

    fun setFirstEntry(value: String) {
        _state.update { it.copy(firstEntry = value, error = null) }
    }

    fun setSecondEntry(value: String) {
        _state.update { it.copy(secondEntry = value, error = null) }
    }

    fun addPatternDot(dot: Int) {
        val current = _state.value.firstEntry
        if (current.none { it.digitToIntOrNull() == dot }) {
            _state.update { it.copy(firstEntry = current + dot.toString(), error = null) }
        }
    }

    fun clearPattern() {
        _state.update { it.copy(firstEntry = "", secondEntry = "", error = null) }
    }

    fun proceedToConfirm() {
        val first = _state.value.firstEntry
        val type = _state.value.selectedLockType
        if (type == null) return

        val minLength = when (type) {
            AppLockType.PIN -> 4
            AppLockType.PASSWORD -> 6
            AppLockType.PATTERN -> 4
            AppLockType.OFF -> return
        }
        if (first.length < minLength) {
            _state.update { it.copy(error = "Must be at least $minLength characters") }
            return
        }
        _state.update { it.copy(setupStep = SetupStep.CONFIRM, secondEntry = "", error = null) }
    }

    fun confirmAndSave(onDone: () -> Unit) {
        val type = _state.value.selectedLockType ?: return
        val first = _state.value.firstEntry
        val second = _state.value.secondEntry

        if (first != second) {
            _state.update { it.copy(error = "Entries do not match. Try again.", secondEntry = "") }
            return
        }

        viewModelScope.launch {
            try {
                manager.setLock(type, first)
                _state.update {
                    it.copy(
                        currentLockType = type,
                        setupStep = SetupStep.NONE,
                        firstEntry = "",
                        secondEntry = "",
                        error = null,
                        toastMessage = "App lock set successfully"
                    )
                }
                onDone()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to save app lock")
                _state.update { it.copy(error = "Failed to save: ${t.message}") }
            }
        }
    }

    fun cancelSetup() {
        _state.update {
            it.copy(
                selectedLockType = null,
                setupStep = SetupStep.NONE,
                firstEntry = "",
                secondEntry = "",
                error = null
            )
        }
    }

    fun toggleTouchId(enabled: Boolean) {
        viewModelScope.launch {
            manager.setTouchIdEnabled(enabled)
            _state.update { it.copy(touchIdEnabled = enabled) }
        }
    }

    fun toggleForgotPasswordDisabled(disabled: Boolean) {
        viewModelScope.launch {
            manager.setForgotPasswordDisabled(disabled)
            _state.update { it.copy(forgotPasswordDisabled = disabled) }
        }
    }

    fun disableLock() {
        viewModelScope.launch {
            manager.disableLock()
            _state.update {
                it.copy(
                    currentLockType = AppLockType.OFF,
                    selectedLockType = null,
                    setupStep = SetupStep.NONE,
                    firstEntry = "",
                    secondEntry = "",
                    error = null,
                    toastMessage = "App lock disabled"
                )
            }
        }
    }

    fun clearToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    companion object {
        fun factory(context: android.content.Context, db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppLockSetupViewModel(context, db) as T
                }
            }
    }
}

data class AppLockSetupState(
    val currentLockType: AppLockType = AppLockType.OFF,
    val selectedLockType: AppLockType? = null,
    val setupStep: SetupStep = SetupStep.NONE,
    val firstEntry: String = "",
    val secondEntry: String = "",
    val touchIdEnabled: Boolean = false,
    val forgotPasswordDisabled: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val toastMessage: String? = null
)

enum class SetupStep { NONE, ENTER, CONFIRM }

/**
 * AppLockSetupPage — full screen for configuring app lock.
 */
@Composable
fun AppLockSetupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: AppLockSetupViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppLockSetupViewModel.factory(context, AppDatabase.getInstance(context))
    )
    val state by viewModel.state.collectAsState()

    if (state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = BrandOrange)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("← Back", color = BrandOrange, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "App Lock",
            style = MaterialTheme.typography.headlineMedium,
            color = BrandOrange,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Require a PIN, password, or pattern to open the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (state.setupStep) {
            SetupStep.NONE -> {
                LockTypeSelector(state, viewModel)
            }
            SetupStep.ENTER -> {
                EnterCredentialsPage(state, viewModel)
            }
            SetupStep.CONFIRM -> {
                ConfirmCredentialsPage(state, viewModel, onBack)
            }
        }

        // Toast
        state.toastMessage?.let { msg ->
            LaunchedEffect(msg) {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                viewModel.clearToast()
            }
        }
    }
}

@Composable
private fun LockTypeSelector(
    state: AppLockSetupState,
    viewModel: AppLockSetupViewModel
) {
    // Current lock status
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Current: ${state.currentLockType.name}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (state.currentLockType != AppLockType.OFF) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.disableLock() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disable App Lock")
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Choose a lock type:",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.SemiBold
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Lock type options
    LockTypeOption("PIN", "4-digit numeric code", AppLockType.PIN, state.selectedLockType) {
        viewModel.selectLockType(AppLockType.PIN)
    }
    Spacer(modifier = Modifier.height(8.dp))
    LockTypeOption("Password", "Alphanumeric (min 6 chars)", AppLockType.PASSWORD, state.selectedLockType) {
        viewModel.selectLockType(AppLockType.PASSWORD)
    }
    Spacer(modifier = Modifier.height(8.dp))
    LockTypeOption("Pattern", "Connect 4+ dots in a 3×3 grid", AppLockType.PATTERN, state.selectedLockType) {
        viewModel.selectLockType(AppLockType.PATTERN)
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Touch ID toggle (only if lock is enabled)
    if (state.currentLockType != AppLockType.OFF) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = BrandOrange
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Touch ID (Biometric)", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Use fingerprint or face to unlock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(
                    checked = state.touchIdEnabled,
                    onCheckedChange = { viewModel.toggleTouchId(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Disable forgot password toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = BrandOrange
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable Forgot Password", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Hide the forgot password option on lock screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(
                    checked = state.forgotPasswordDisabled,
                    onCheckedChange = { viewModel.toggleForgotPasswordDisabled(it) }
                )
            }
        }
    }

    // Show "Set" button if a type was selected
    state.selectedLockType?.let { type ->
        if (type != AppLockType.OFF && state.setupStep == SetupStep.NONE) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.selectLockType(type) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
            ) {
                Text("Set ${type.name}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LockTypeOption(
    title: String,
    subtitle: String,
    type: AppLockType,
    selected: AppLockType?,
    onClick: () -> Unit
) {
    val isSelected = selected == type
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, BrandOrange)
        else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = BrandOrange)
            }
        }
    }
}

@Composable
private fun EnterCredentialsPage(
    state: AppLockSetupState,
    viewModel: AppLockSetupViewModel
) {
    val type = state.selectedLockType ?: return
    val isPattern = type == AppLockType.PATTERN

    Text(
        text = "Enter your ${type.name.lowercase()}",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = when (type) {
            AppLockType.PIN -> "Must be at least 4 digits"
            AppLockType.PASSWORD -> "Must be at least 6 characters"
            AppLockType.PATTERN -> "Connect at least 4 dots"
            AppLockType.OFF -> ""
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(24.dp))

    if (isPattern) {
        PatternLockView(
            dots = state.firstEntry,
            onDotAdded = { viewModel.addPatternDot(it) },
            onClear = { viewModel.clearPattern() }
        )
    } else {
        OutlinedTextField(
            value = state.firstEntry,
            onValueChange = { viewModel.setFirstEntry(it) },
            label = { Text(type.name) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = when (type) {
                    AppLockType.PIN -> KeyboardType.NumberPassword
                    else -> KeyboardType.Password
                }
            ),
            singleLine = true,
            isError = state.error != null,
            supportingText = { state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { viewModel.cancelSetup() }, modifier = Modifier.weight(1f)) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = { viewModel.proceedToConfirm() },
            enabled = state.firstEntry.length >= when (type) {
                AppLockType.PIN -> 4
                AppLockType.PASSWORD -> 6
                AppLockType.PATTERN -> 4
                AppLockType.OFF -> 0
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
        ) {
            Text("Next")
        }
    }
}

@Composable
private fun ConfirmCredentialsPage(
    state: AppLockSetupState,
    viewModel: AppLockSetupViewModel,
    onBack: () -> Unit
) {
    val type = state.selectedLockType ?: return
    val isPattern = type == AppLockType.PATTERN

    Text(
        text = "Confirm your ${type.name.lowercase()}",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Re-enter to confirm",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(24.dp))

    if (isPattern) {
        PatternLockView(
            dots = state.secondEntry,
            onDotAdded = { dot ->
                val current = state.secondEntry
                if (dot !in current.map { it.digitToInt() }) {
                    viewModel.setSecondEntry(current + dot.toString())
                }
            },
            onClear = { viewModel.setSecondEntry("") }
        )
    } else {
        OutlinedTextField(
            value = state.secondEntry,
            onValueChange = { viewModel.setSecondEntry(it) },
            label = { Text("Confirm ${type.name}") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = when (type) {
                    AppLockType.PIN -> KeyboardType.NumberPassword
                    else -> KeyboardType.Password
                }
            ),
            singleLine = true,
            isError = state.error != null,
            supportingText = { state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { viewModel.cancelSetup() }, modifier = Modifier.weight(1f)) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = { viewModel.confirmAndSave(onBack) },
            enabled = state.secondEntry.length >= when (type) {
                AppLockType.PIN -> 4
                AppLockType.PASSWORD -> 6
                AppLockType.PATTERN -> 4
                AppLockType.OFF -> 0
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange)
        ) {
            Text("Confirm & Save")
        }
    }
}

/**
 * PatternLockView — 3x3 grid of dots for pattern input.
 */
@Composable
fun PatternLockView(
    dots: String,
    onDotAdded: (Int) -> Unit,
    onClear: () -> Unit
) {
    val selectedDots = dots.map { it.digitToInt() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                                width = 2.dp,
                                color = if (isSelected) BrandOrange
                                else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable { onDotAdded(dotNum) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        TextButton(onClick = onClear) {
            Text("Clear", color = BrandOrange)
        }
    }
}

