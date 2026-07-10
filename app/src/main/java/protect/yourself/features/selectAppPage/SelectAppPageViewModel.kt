package protect.yourself.features.selectAppPage

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.features.selectAppPage.data.DisplayAppsItemModel
import timber.log.Timber

/**
 * SelectAppPageViewModel — app picker for selecting apps to block/whitelist.
 *
 * Used by multiple features (block list, VPN whitelist, Stop Me whitelist,
 * supported browsers, etc.).
 *
 * Phase 4: load all installed apps + show selection UI.
 */
class SelectAppPageViewModel(
    private val db: AppDatabase,
    private val identifier: SelectedAppListIdentifier
) : ViewModel() {

    private val _state = MutableStateFlow(SelectAppPageState())
    val state: StateFlow<SelectAppPageState> = _state.asStateFlow()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val pm = protect.yourself.commons.utils.PackageManagerProvider.packageManager
                val installedApps = withContext(Dispatchers.IO) {
                    val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    packages.map { appInfo ->
                        DisplayAppsItemModel(
                            packageName = appInfo.packageName,
                            appName = pm.getApplicationLabel(appInfo).toString(),
                            icon = pm.getApplicationIcon(appInfo),
                            isSelected = false
                        )
                    }.sortedBy { it.appName.lowercase() }
                }

                // Mark already-selected apps
                val selectedKeys = db.selectedAppsListDao()
                    .getSelectedByIdentifier(identifier.value)
                    .associateBy { it.packageName }

                val displayApps = installedApps.map { app ->
                    val isSelected = selectedKeys.containsKey(app.packageName)
                    app.copy(isSelected = isSelected)
                }

                _state.update {
                    it.copy(
                        allApps = displayApps,
                        filteredApps = displayApps,
                        isLoading = false
                    )
                }
                Timber.i("Loaded ${displayApps.size} apps for ${identifier.value}")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load apps")
                _state.update { it.copy(isLoading = false, error = t.message) }
            }
        }
    }

    fun searchApp(query: String) {
        _state.update { state ->
            val filtered = if (query.isBlank()) state.allApps
            else state.allApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
            state.copy(filteredApps = filtered, searchQuery = query)
        }
    }

    fun toggleAppSelection(app: DisplayAppsItemModel) {
        viewModelScope.launch {
            val newSelected = !app.isSelected
            // Update local state immediately for responsive UI
            _state.update { state ->
                state.copy(
                    allApps = state.allApps.map {
                        if (it.packageName == app.packageName) it.copy(isSelected = newSelected)
                        else it
                    },
                    filteredApps = state.filteredApps.map {
                        if (it.packageName == app.packageName) it.copy(isSelected = newSelected)
                        else it
                    }
                )
            }

            // Persist to DB
            val item = SelectedAppItemModel(
                key = "${identifier.value}_${app.packageName}",
                packageName = app.packageName,
                appName = app.appName,
                identifier = identifier.value,
                isSelected = newSelected
            )
            if (newSelected) {
                db.selectedAppsListDao().upsert(item)
            } else {
                db.selectedAppsListDao().deleteByIdentifierAndPackage(identifier.value, app.packageName)
            }
            Timber.d("App ${app.packageName} selection toggled to $newSelected")
        }
    }

    companion object {
        fun factory(
            db: AppDatabase,
            identifier: SelectedAppListIdentifier
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SelectAppPageViewModel(db, identifier) as T
            }
        }
    }
}

data class SelectAppPageState(
    val allApps: List<DisplayAppsItemModel> = emptyList(),
    val filteredApps: List<DisplayAppsItemModel> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null
)
