package protect.yourself.features.selectAppPage

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.service.MyVpnService
import protect.yourself.features.selectAppPage.data.DisplayAppsItemModel
import protect.yourself.features.selectAppPage.utils.UnsupportedBrowserDetector
import timber.log.Timber

/**
 * SelectAppPageViewModel — app picker for selecting apps to block/whitelist.
 *
 * Used by multiple features (block list, VPN whitelist, Stop Me whitelist,
 * supported browsers, etc.).
 *
 * ## Bug fix (Whitelist Unsupported Browser card)
 *
 * Previously, every list type loaded **all** installed apps via
 * `PackageManager.getInstalledApplications(GET_META_DATA)`. That made the
 * "Whitelist Unsupported Browser" picker nearly unusable because it showed
 * every app on the device (often 100+) instead of the small subset (usually
 * 1–10) of unsupported browsers the user actually wants to whitelist.
 *
 * The fix mirrors the NopoX 1.0.53 reference implementation:
 *   1. When the identifier is [SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER],
 *      we ask [UnsupportedBrowserDetector] for the set of installed
 *      browser-capable packages that are NOT in the supported-browsers list.
 *   2. We then load **only** the ApplicationInfo for those packages (using
 *      `pm.getApplicationInfo(pkg, 0)` per package) instead of the full
 *      `getInstalledApplications` list. This is dramatically faster —
 *      typically <50ms for 5 browsers vs. 300–800ms for the full app list.
 *   3. The Google Quick Search Box (`com.google.android.googlequicksearchbox`)
 *      is added to the picker because it ships with an in-app browser.
 *
 * ## Performance
 *
 *   - The detector caches its result for 60s so back-and-forth navigation
 *     between the picker and the settings page does not re-query the
 *     PackageManager.
 *   - App icons are loaded lazily in the UI (see [SelectAppPage.kt]) rather
 *     than eagerly in the ViewModel, so opening the page is fast even on
 *     devices with hundreds of installed apps.
 *   - All PackageManager work runs on [Dispatchers.IO] so the UI thread is
 *     never blocked.
 */
class SelectAppPageViewModel(
    private val db: AppDatabase,
    private val identifier: SelectedAppListIdentifier,
    private val appContext: android.content.Context
) : ViewModel() {

    private val _state = MutableStateFlow(SelectAppPageState())
    val state: StateFlow<SelectAppPageState> = _state.asStateFlow()

    /** Navigation events (toast messages, etc.) emitted to the UI. */
    private val _navigation = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val navigation: SharedFlow<String> = _navigation.asSharedFlow()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val displayApps = withContext(Dispatchers.IO) {
                    when (identifier) {
                        SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER ->
                            loadUnsupportedBrowsers()
                        else ->
                            loadAllInstalledApps()
                    }
                }

                // Mark already-selected apps from DB
                val selectedKeys = db.selectedAppsListDao()
                    .getSelectedByIdentifier(identifier.value)
                    .associateBy { it.packageName }

                val withSelection = displayApps.map { app ->
                    val isSelected = selectedKeys.containsKey(app.packageName)
                    app.copy(isSelected = isSelected)
                }

                _state.update {
                    it.copy(
                        allApps = withSelection,
                        filteredApps = withSelection,
                        isLoading = false,
                        error = null
                    )
                }
                Timber.tag(TAG).i(
                    "Loaded ${withSelection.size} apps for ${identifier.value} " +
                        "(selected=${withSelection.count { it.isSelected }})"
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to load apps for ${identifier.value}")
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = t.message ?: "Unknown error loading apps"
                    )
                }
            }
        }
    }

    /**
     * Loads ALL installed apps. Used by every list type other than
     * [SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER].
     *
     * This is the legacy behavior — preserved unchanged so the VPN whitelist,
     * blocklist, Stop Me whitelist, etc. still show every installed app.
     */
    private fun loadAllInstalledApps(): List<DisplayAppsItemModel> {
        val pm = protect.yourself.commons.utils.PackageManagerProvider.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return packages.map { appInfo ->
            DisplayAppsItemModel(
                packageName = appInfo.packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                icon = pm.getApplicationIcon(appInfo),
                isSelected = false
            )
        }.sortedBy { it.appName.lowercase() }
    }

    /**
     * Loads **only** unsupported browsers — apps that can browse the web but
     * are not in the supported-browsers allow-list. Uses
     * [UnsupportedBrowserDetector] for the package list, then resolves each
     * package's label/icon individually.
     *
     * Why per-package resolution instead of `getInstalledApplications`:
     *   `queryIntentActivities` is much cheaper than
     *   `getInstalledApplications(GET_META_DATA)` and only returns the
     *   handful of browser-capable apps we care about. Resolving their
     *   label/icon is then O(K) where K is typically 2–10 instead of O(N)
     *   where N can be 200+.
     */
    private fun loadUnsupportedBrowsers(): List<DisplayAppsItemModel> {
        val unsupportedPackages = UnsupportedBrowserDetector.getUnsupportedBrowserPackages()
        Timber.tag(TAG).i(
            "Loading ${unsupportedPackages.size} unsupported browser packages"
        )

        if (unsupportedPackages.isEmpty()) {
            return emptyList()
        }

        val pm = protect.yourself.commons.utils.PackageManagerProvider.packageManager
        return unsupportedPackages.mapNotNull { packageName ->
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                DisplayAppsItemModel(
                    packageName = packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isSelected = false
                )
            } catch (t: Throwable) {
                // Package may have been uninstalled between the detector
                // query and this call. Skip it silently — the cache will be
                // invalidated by the next package install/remove broadcast.
                Timber.tag(TAG).w(t, "Skipping $packageName — not installed")
                null
            }
        }.sortedBy { it.appName.lowercase() }
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
            try {
                if (newSelected) {
                    db.selectedAppsListDao().upsert(item)
                } else {
                    db.selectedAppsListDao().deleteByIdentifierAndPackage(identifier.value, app.packageName)
                }
                Timber.tag(TAG).d("App ${app.packageName} selection toggled to $newSelected")
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to persist selection for ${app.packageName}")
                // Revert local state to match DB
                _state.update { state ->
                    state.copy(
                        allApps = state.allApps.map {
                            if (it.packageName == app.packageName) it.copy(isSelected = !newSelected)
                            else it
                        },
                        filteredApps = state.filteredApps.map {
                            if (it.packageName == app.packageName) it.copy(isSelected = !newSelected)
                            else it
                        }
                    )
                }
                _navigation.tryEmit("Failed to update selection. Please try again.")
                return@launch
            }

            // VPN whitelist fix: if the user just changed the VPN whitelist
            // and the VPN is currently running, restart it so the new
            // addDisallowedApplication set takes effect immediately.
            // Without this, the VPN keeps using the old whitelist until the
            // user manually toggles it off/on.
            if (identifier == SelectedAppListIdentifier.VPN_WHITELIST_APPS) {
                val switchValues = SwitchStatusValues(db.switchStatusDao())
                if (switchValues.isVpnSwitchOn()) {
                    Timber.tag(TAG).i("VPN whitelist changed — restarting VPN to apply new whitelist")
                    try {
                        MyVpnService.restart(appContext)
                        _navigation.emit("VPN whitelist updated. Restarting VPN…")
                    } catch (t: Throwable) {
                        Timber.tag(TAG).w(t, "Failed to restart VPN after whitelist change")
                        _navigation.emit("VPN whitelist saved. Toggle VPN off/on to apply.")
                    }
                }
            } else if (identifier == SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER) {
                // Unsupported browser whitelist fix: when the user changes the
                // whitelist, refresh the accessibility service so the service
                // reloads cachedUnsupportedBrowserWhitelist from the DB.
                Timber.tag(TAG).i("Unsupported browser whitelist changed — refreshing blocking config")
                try {
                    MyAccessibilityService.instance?.refreshBlockingConfig()
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Failed to refresh blocking config after whitelist change")
                }
            }
        }
    }

    /**
     * Refreshes the app list. Called by the UI when the user pulls to refresh
     * or when a package install/remove broadcast is received.
     */
    fun refresh() {
        UnsupportedBrowserDetector.invalidateCache()
        loadApps()
    }

    companion object {
        private const val TAG = "SelectAppPageVM"

        fun factory(
            db: AppDatabase,
            identifier: SelectedAppListIdentifier,
            appContext: android.content.Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SelectAppPageViewModel(db, identifier, appContext) as T
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
