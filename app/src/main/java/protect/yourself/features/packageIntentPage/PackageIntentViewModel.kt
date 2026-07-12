package protect.yourself.features.packageIntentPage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.utils.BlockingValidator
import protect.yourself.features.blockerPage.utils.ValidationResult
import timber.log.Timber

/**
 * PackageIntentViewModel — state management for the Package+Intent blocking page.
 *
 * Manages two lists:
 *  - Blocked package names (exact match) — stored in selected_apps_table
 *    under identifier BLOCKED_PACKAGE_NAMES
 *  - Blocked intent/class names (substring match) — stored in selected_keyword_table
 *    under identifier BLOCKED_INTENT_NAMES
 *
 * User can:
 *  - Add new entry (auto-classified: if contains dots + no spaces → package, else intent)
 *  - Delete any entry by key
 *  - Toggle the master switch (BLOCK_PACKAGE_INTENT_SWITCH)
 *
 * Emits [PackageIntentEvent] one-shot events for UI feedback (toast / inline
 * validation error) via [events].
 */
class PackageIntentViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    private val _state = MutableStateFlow(PackageIntentState())
    val state: StateFlow<PackageIntentState> = _state.asStateFlow()

    /**
     * One-shot UI events (add/delete/error feedback).
     * Consumed by the UnifiedBlockingPage via [collect].
     */
    private val _events = MutableSharedFlow<PackageIntentEvent>(
        extraBufferCapacity = 8
    )
    val events: SharedFlow<PackageIntentEvent> = _events.asSharedFlow()

    init {
        observeData()
    }

    private fun observeData() {
        // Observe blocked package names
        viewModelScope.launch {
            try {
                db.selectedAppsListDao()
                    .observeSelectedByIdentifier(SelectedAppListIdentifier.BLOCKED_PACKAGE_NAMES.value)
                    .collect { packages ->
                        _state.update { it.copy(blockedPackages = packages) }
                    }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe blocked packages")
                _events.emit(
                    PackageIntentEvent.Error(
                        "Failed to load blocked packages: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }

        // Observe blocked intent names
        viewModelScope.launch {
            try {
                db.selectedKeywordDao()
                    .observeByIdentifier(SelectedKeywordIdentifier.BLOCKED_INTENT_NAMES.value)
                    .collect { keywords ->
                        _state.update { it.copy(blockedIntents = keywords) }
                    }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe blocked intents")
                _events.emit(
                    PackageIntentEvent.Error(
                        "Failed to load blocked intents: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }

        // Load initial switch state
        viewModelScope.launch {
            try {
                val isOn = switchValues.isBlockPackageIntentSwitchOn()
                _state.update { it.copy(isSwitchOn = isOn) }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load package+intent switch state")
            }
        }
    }

    /**
     * Add a new package-name entry. Validates format (must contain a dot,
     * lowercase letters / digits / dots / underscores only, no spaces) and
     * duplicate before persisting.
     *
     * Auto-enables the master switch on first add.
     */
    fun addPackageEntry(input: String) {
        val result = BlockingValidator.validatePackageName(
            input,
            _state.value.blockedPackages.map { it.packageName }
        )
        if (result is ValidationResult.Valid) {
            val normalized = result.normalized
            viewModelScope.launch {
                try {
                    val item = SelectedAppItemModel(
                        key = "blocked_pkg_${System.currentTimeMillis()}_${normalized.hashCode()}",
                        packageName = normalized,
                        appName = normalized,
                        identifier = SelectedAppListIdentifier.BLOCKED_PACKAGE_NAMES.value,
                        isSelected = true
                    )
                    db.selectedAppsListDao().upsert(item)
                    Timber.i("Added blocked package: $normalized")
                    autoEnableSwitchIfNeeded()
                    refreshAccessibility()
                    _events.emit(PackageIntentEvent.Added(normalized, "packages"))
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to add blocked package: $normalized")
                    _events.emit(
                        PackageIntentEvent.Error(
                            "Failed to add: ${t.message ?: "unknown error"}"
                        )
                    )
                }
            }
        } else {
            Timber.w("Rejected package entry '$input': $result")
            viewModelScope.launch {
                _events.emit(PackageIntentEvent.ValidationFailed(result))
            }
        }
    }

    /**
     * Add a new intent/class-name entry. Validates format (no spaces, valid
     * Java identifier characters, no leading digit) and duplicate before
     * persisting.
     *
     * Auto-enables the master switch on first add.
     */
    fun addIntentEntry(input: String) {
        val result = BlockingValidator.validateIntentName(
            input,
            _state.value.blockedIntents.map { it.keyword }
        )
        if (result is ValidationResult.Valid) {
            val normalized = result.normalized
            viewModelScope.launch {
                try {
                    val item = SelectedKeywordItemModel(
                        key = "blocked_intent_${System.currentTimeMillis()}_${normalized.hashCode()}",
                        keyword = normalized,
                        identifier = SelectedKeywordIdentifier.BLOCKED_INTENT_NAMES.value,
                        isSelected = true
                    )
                    db.selectedKeywordDao().upsert(item)
                    Timber.i("Added blocked intent: $normalized")
                    autoEnableSwitchIfNeeded()
                    refreshAccessibility()
                    _events.emit(PackageIntentEvent.Added(normalized, "intents"))
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to add blocked intent: $normalized")
                    _events.emit(
                        PackageIntentEvent.Error(
                            "Failed to add: ${t.message ?: "unknown error"}"
                        )
                    )
                }
            }
        } else {
            Timber.w("Rejected intent entry '$input': $result")
            viewModelScope.launch {
                _events.emit(PackageIntentEvent.ValidationFailed(result))
            }
        }
    }

    /**
     * Delete a package name entry by key.
     */
    fun deletePackage(key: String) {
        viewModelScope.launch {
            try {
                val existing = _state.value.blockedPackages.firstOrNull { it.key == key }
                db.selectedAppsListDao().deleteByKey(key)
                refreshAccessibility()
                Timber.i("Deleted blocked package: $key")
                _events.emit(
                    PackageIntentEvent.Deleted(existing?.packageName ?: "entry", "packages")
                )
            } catch (t: Throwable) {
                Timber.e(t, "Failed to delete package: $key")
                _events.emit(
                    PackageIntentEvent.Error(
                        "Failed to delete: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }
    }

    /**
     * Delete an intent name entry by key.
     */
    fun deleteIntent(key: String) {
        viewModelScope.launch {
            try {
                val existing = _state.value.blockedIntents.firstOrNull { it.key == key }
                db.selectedKeywordDao().deleteByKey(key)
                refreshAccessibility()
                Timber.i("Deleted blocked intent: $key")
                _events.emit(
                    PackageIntentEvent.Deleted(existing?.keyword ?: "entry", "intents")
                )
            } catch (t: Throwable) {
                Timber.e(t, "Failed to delete intent: $key")
                _events.emit(
                    PackageIntentEvent.Error(
                        "Failed to delete: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }
    }

    /**
     * Toggle the master switch.
     */
    fun toggleSwitch() {
        viewModelScope.launch {
            try {
                val newValue = !_state.value.isSwitchOn
                switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH, newValue)
                _state.update { it.copy(isSwitchOn = newValue) }
                refreshAccessibility()
                Timber.i("Package+Intent switch toggled to $newValue")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to toggle package+intent switch")
                _events.emit(
                    PackageIntentEvent.Error(
                        "Failed to toggle switch: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }
    }

    /**
     * Set the master switch to a specific value (used by the unified UI's
     * explicit Switch widget).
     */
    fun setSwitchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH, enabled)
                _state.update { it.copy(isSwitchOn = enabled) }
                refreshAccessibility()
                Timber.i("Package+Intent switch set to $enabled")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to set package+intent switch to $enabled")
                _events.emit(
                    PackageIntentEvent.Error(
                        "Failed to toggle switch: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }
    }

    /**
     * Auto-enable the master switch on first add. Mirrors the setting-title
     * switch behaviour in [KeywordManagerViewModel.addSettingTitleKeyword].
     * Without this, the user could add entries that silently do nothing.
     */
    private suspend fun autoEnableSwitchIfNeeded() {
        try {
            if (!switchValues.isBlockPackageIntentSwitchOn()) {
                switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH, true)
                _state.update { it.copy(isSwitchOn = true) }
                Timber.i("Auto-enabled BLOCK_PACKAGE_INTENT_SWITCH")
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to auto-enable BLOCK_PACKAGE_INTENT_SWITCH")
        }
    }

    private fun refreshAccessibility() {
        try {
            MyAccessibilityService.instance?.refreshBlockingConfig()
        } catch (t: Throwable) {
            Timber.w(t, "refreshBlockingConfig failed — service may not be running")
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PackageIntentViewModel(application) as T
                }
            }
    }
}

data class PackageIntentState(
    val blockedPackages: List<SelectedAppItemModel> = emptyList(),
    val blockedIntents: List<SelectedKeywordItemModel> = emptyList(),
    val isSwitchOn: Boolean = false
)

/**
 * One-shot UI events emitted by [PackageIntentViewModel].
 *
 * The UnifiedBlockingPage collects these via [PackageIntentViewModel.events]
 * and shows appropriate feedback (Toast / inline error) to the user.
 */
sealed class PackageIntentEvent {
    /** Entry was successfully added to the named list. */
    data class Added(val entry: String, val listName: String) : PackageIntentEvent()

    /** Entry was successfully deleted from the named list. */
    data class Deleted(val entry: String, val listName: String) : PackageIntentEvent()

    /** Input failed validation. [result] carries the specific failure. */
    data class ValidationFailed(val result: ValidationResult) : PackageIntentEvent()

    /** An unexpected error occurred (DB write failure, etc). */
    data class Error(val message: String) : PackageIntentEvent()
}
