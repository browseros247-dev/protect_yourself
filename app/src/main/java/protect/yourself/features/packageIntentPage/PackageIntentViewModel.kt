package protect.yourself.features.packageIntentPage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 */
class PackageIntentViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    private val _state = MutableStateFlow(PackageIntentState())
    val state: StateFlow<PackageIntentState> = _state.asStateFlow()

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
            }
        }

        // Observe blocked intent names — note: there's no observeSelectedByIdentifier
        // for keywords, so we observe by identifier and filter
        viewModelScope.launch {
            try {
                db.selectedKeywordDao()
                    .observeByIdentifier(SelectedKeywordIdentifier.BLOCKED_INTENT_NAMES.value)
                    .collect { keywords ->
                        _state.update { it.copy(blockedIntents = keywords) }
                    }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe blocked intents")
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
     * Add a new entry. Auto-classifies based on content:
     *  - If contains "." and no spaces → treated as package name
     *  - Otherwise → treated as intent/class name
     */
    fun addEntry(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            try {
                if (trimmed.contains(".") && !trimmed.contains(" ")) {
                    // Package name
                    val item = SelectedAppItemModel(
                        key = "blocked_pkg_${System.currentTimeMillis()}_${trimmed.hashCode()}",
                        packageName = trimmed,
                        appName = trimmed,
                        identifier = SelectedAppListIdentifier.BLOCKED_PACKAGE_NAMES.value,
                        isSelected = true
                    )
                    db.selectedAppsListDao().upsert(item)
                    Timber.i("Added blocked package: $trimmed")
                } else {
                    // Intent/class name
                    val item = SelectedKeywordItemModel(
                        key = "blocked_intent_${System.currentTimeMillis()}_${trimmed.hashCode()}",
                        keyword = trimmed,
                        identifier = SelectedKeywordIdentifier.BLOCKED_INTENT_NAMES.value,
                        isSelected = true
                    )
                    db.selectedKeywordDao().upsert(item)
                    Timber.i("Added blocked intent: $trimmed")
                }

                // Auto-enable the switch if not already on
                if (!switchValues.isBlockPackageIntentSwitchOn()) {
                    switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH, true)
                    _state.update { it.copy(isSwitchOn = true) }
                }

                refreshAccessibility()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to add package/intent entry: $trimmed")
            }
        }
    }

    /**
     * Delete a package name entry by key.
     */
    fun deletePackage(key: String) {
        viewModelScope.launch {
            try {
                db.selectedAppsListDao().deleteByKey(key)
                refreshAccessibility()
                Timber.i("Deleted blocked package: $key")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to delete package: $key")
            }
        }
    }

    /**
     * Delete an intent name entry by key.
     */
    fun deleteIntent(key: String) {
        viewModelScope.launch {
            try {
                db.selectedKeywordDao().deleteByKey(key)
                refreshAccessibility()
                Timber.i("Deleted blocked intent: $key")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to delete intent: $key")
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
            }
        }
    }

    private fun refreshAccessibility() {
        try {
            MyAccessibilityService.instance?.refreshBlockingConfig()
        } catch (_: Throwable) {}
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
