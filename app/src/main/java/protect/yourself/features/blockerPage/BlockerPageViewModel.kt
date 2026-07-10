package protect.yourself.features.blockerPage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
import protect.yourself.features.blockerPage.data.SettingPageItemModel
import protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers
import protect.yourself.features.blockerPage.identifiers.VpnConnectionTypeIdentifiers
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import timber.log.Timber

/**
 * Navigation events emitted by BlockerPageViewModel.
 */
sealed class BlockerPageNavigation {
    data class OpenSelectAppPage(val title: String, val identifier: SelectedAppListIdentifier) : BlockerPageNavigation()
    data class OpenUrl(val url: String) : BlockerPageNavigation()
    data class ShowToast(val message: String) : BlockerPageNavigation()
    data class EditTextField(val title: String, val currentValue: String, val hint: String, val switchKey: String) : BlockerPageNavigation()
    data class EditNumberField(val title: String, val currentValue: Int, val min: Int, val max: Int, val switchKey: String) : BlockerPageNavigation()
    data object RequestVpnPermission : BlockerPageNavigation()
    data object StopVpn : BlockerPageNavigation()
    data object RestartVpn : BlockerPageNavigation()
    data object OpenVpnManagement : BlockerPageNavigation()
    data object OpenAccessibilitySettings : BlockerPageNavigation()
    data object OpenOverlaySettings : BlockerPageNavigation()
    data object OpenAppLockSetup : BlockerPageNavigation()
    data object OpenKeywordManager : BlockerPageNavigation()
    data class OpenKeywordManagerTab(val tab: protect.yourself.features.keywordManagerPage.KeywordTab) : BlockerPageNavigation()
    data object OpenPackageIntentManager : BlockerPageNavigation()
    data object OpenStopMe : BlockerPageNavigation()
    data object OpenFaq : BlockerPageNavigation()
    data object OpenRequestHistory : BlockerPageNavigation()
    data object PickBlockScreenImage : BlockerPageNavigation()
    data object RequestDeviceAdmin : BlockerPageNavigation()
}

/**
 * BlockerPageViewModel — manages state for the Blocker (Home) tab.
 */
class BlockerPageViewModel(
    private val db: AppDatabase
) : ViewModel() {

    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    private val _state = MutableStateFlow(BlockerPageState())
    val state: StateFlow<BlockerPageState> = _state.asStateFlow()

    /** Dedicated state for the VPN Management page. */
    private val _vpnManagementState = MutableStateFlow(VpnManagementState())
    val vpnManagementState: StateFlow<VpnManagementState> = _vpnManagementState.asStateFlow()

    private val _navigation = MutableSharedFlow<BlockerPageNavigation>(extraBufferCapacity = 5)
    val navigation: SharedFlow<BlockerPageNavigation> = _navigation.asSharedFlow()

    init {
        loadSettingItems()
    }

    fun loadSettingItems() {
        viewModelScope.launch {
            val items = buildSettingItems()

            // Load current switch values + dynamic action labels
            val itemsWithValues = items.map { item ->
                var result = item
                if (item.switchKey != null) {
                    result = result.copy(switchValue = loadSwitchValue(item.switchKey))
                }
                // Load dynamic action labels
                result = loadDynamicActionLabel(result)
                result
            }

            _state.update { it.copy(settingItems = itemsWithValues, isLoading = false) }
            Timber.i("BlockerPage loaded ${itemsWithValues.size} setting items")
        }
    }

    private suspend fun loadDynamicActionLabel(item: SettingPageItemModel): SettingPageItemModel {
        return when (item.identifier) {
            SettingPageItemIdentifiers.BLOCK_SCREEN_COUNT -> {
                val count = db.blockScreenCountDao().getCount()?.count ?: 0
                item.copy(actionLabel = count.toString())
            }
            SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE -> {
                val count = db.selectedKeywordDao()
                    .countByIdentifier(protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS.value)
                item.copy(actionLabel = if (count > 0) "$count titles" else "Add")
            }
            SettingPageItemIdentifiers.BLOCKED_SCREEN_COUNTDOWN -> {
                val secs = switchValues.getBlockScreenCountDownSeconds()
                item.copy(actionLabel = if (secs > 0) "${secs}s" else "Off")
            }
            SettingPageItemIdentifiers.TIME_DELAY_CUSTOM_DURATION -> {
                val secs = switchValues.getTimeDelayCustomDurationSeconds()
                item.copy(actionLabel = "${secs}s")
            }
            SettingPageItemIdentifiers.VPN_MANAGE -> {
                // Show the current VPN mode label + a "Manage" affordance.
                val type = switchValues.getVpnConnectionType()
                item.copy(actionLabel = vpnModeLabel(type))
            }
            SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE -> {
                val msg = switchValues.getVpnNotificationCustomMessage()
                item.copy(actionLabel = if (msg.isNullOrBlank()) "Default" else "Custom")
            }
            SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE -> {
                val msg = switchValues.getBlockScreenCustomMessage()
                item.copy(actionLabel = if (msg.isNullOrBlank()) "Default" else "Custom")
            }
            SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP -> {
                val url = switchValues.getBlockScreenRedirectUrl()
                item.copy(actionLabel = if (url.isNullOrBlank()) "None" else "Set")
            }
            SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE -> {
                val path = switchValues.getBlockScreenStoreImagePath()
                item.copy(actionLabel = if (path.isNullOrBlank()) "Choose" else "Set")
            }
            else -> item
        }
    }

    private suspend fun loadSwitchValue(key: String): Boolean = when (key) {
        SwitchIdentifier.PORN_BLOCKER_SWITCH -> switchValues.isPornBlockerSwitchOn()
        SwitchIdentifier.SAFE_SEARCH_SWITCH -> switchValues.isSafeSearchSwitchOn()
        SwitchIdentifier.PREVENT_UNINSTALL_SWITCH -> switchValues.isPreventUninstallSwitchOn()
        // BLOCK_NOTIFICATION_DRAWER + BLOCK_RECENT_APPS removed from UI
        SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH -> switchValues.isBlockPhoneRebootSwitchOn()
        SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH -> switchValues.isBlockUnsupportedBrowsersSwitchOn()
        SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH -> switchValues.isBlockPackageIntentSwitchOn()
        SwitchIdentifier.VPN_SWITCH -> switchValues.isVpnSwitchOn()
        SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH -> switchValues.isVpnNotificationHideSwitchOn()
        SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH -> switchValues.isBlockNewInstallAppsSwitchOn()
        SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH -> switchValues.isBlockInAppBrowsersSwitchOn()
        SwitchIdentifier.SET_APP_LOCK_SWITCH -> switchValues.isAppLockSwitchOn()
        SwitchIdentifier.TOUCH_ID_SWITCH -> switchValues.isTouchIdSwitchOn()
        SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH -> switchValues.isDisableForgotPasswordSwitchOn()
        SwitchIdentifier.DAILY_REPORT_SWITCH -> switchValues.isDailyReportSwitchOn()
        // LONG_SENTENCE_MESSAGE_SET removed from UI — always ON in background
        SwitchIdentifier.TIME_DELAY_DURATION_SET -> switchValues.isTimeDelayDurationSet()
        SwitchIdentifier.REAL_FRIEND_VISIBLE -> switchValues.isRealFriendVisible()
        else -> false
    }

    fun toggleSwitch(item: SettingPageItemModel) {
        val switchKey = item.switchKey ?: return
        val newValue = !item.switchValue

        viewModelScope.launch {
            // Special handling for VPN — requires VpnService.prepare()
            if (switchKey == SwitchIdentifier.VPN_SWITCH) {
                if (newValue) {
                    // Request VPN permission before enabling
                    _navigation.emit(BlockerPageNavigation.RequestVpnPermission)
                    return@launch
                } else {
                    // Stopping VPN — emit event for UI to stop the service
                    switchValues.storeSwitchStatus(switchKey, newValue)
                    _navigation.emit(BlockerPageNavigation.StopVpn)
                    _state.update { state ->
                        state.copy(
                            settingItems = state.settingItems.map {
                                if (it.switchKey == switchKey) it.copy(switchValue = false)
                                else it
                            }
                        )
                    }
                    return@launch
                }
            }

            // TOUCH_ID and DISABLE_FORGOT_PASSWORD should open App Lock setup
            if (switchKey == SwitchIdentifier.TOUCH_ID_SWITCH || switchKey == SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH) {
                _navigation.emit(BlockerPageNavigation.OpenAppLockSetup)
                return@launch
            }

            // SET_APP_LOCK ON → open App Lock setup page
            if (switchKey == SwitchIdentifier.SET_APP_LOCK_SWITCH && newValue) {
                _navigation.emit(BlockerPageNavigation.OpenAppLockSetup)
                return@launch
            }

            // SET_APP_LOCK OFF → disable lock entirely (clears hash + type + touch ID)
            if (switchKey == SwitchIdentifier.SET_APP_LOCK_SWITCH && !newValue) {
                // Clear lock type, stored hash, and touch ID directly in DB
                db.switchStatusDao().upsert(protect.yourself.database.switchStatus.SwitchStatusItemModel(
                    key = "app_lock_type", value = "0", type = "long"
                ))
                db.switchStatusDao().upsert(protect.yourself.database.switchStatus.SwitchStatusItemModel(
                    key = "app_lock_stored_hash", value = "", type = "string"
                ))
                switchValues.storeSwitchStatus(SwitchIdentifier.TOUCH_ID_SWITCH, false)
                _navigation.emit(BlockerPageNavigation.ShowToast("App lock disabled"))
                // Return here — don't fall through to normal toggle
                return@launch
            }

            // PREVENT_UNINSTALL ON → request Device Admin
            if (switchKey == SwitchIdentifier.PREVENT_UNINSTALL_SWITCH && newValue) {
                _navigation.emit(BlockerPageNavigation.RequestDeviceAdmin)
                // Don't toggle yet — wait for user to grant Device Admin
                // The UI will check if admin was granted and then persist
                return@launch
            }

            // Protective mode switches — only one can be active at a time
            // Long Sentence is ALWAYS active in the background automatically
            // (removed from UI — no user toggle needed)
            when (switchKey) {
                SwitchIdentifier.TIME_DELAY_DURATION_SET -> {
                    if (newValue) {
                        switchValues.storeSwitchStatus(SwitchIdentifier.REAL_FRIEND_VISIBLE, false)
                        switchValues.storeSwitchStatus(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE, 2L)
                        // Long Sentence always ON in background
                        switchValues.storeSwitchStatus(SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET, true)
                        _navigation.emit(BlockerPageNavigation.ShowToast("Time Delay protective mode enabled"))
                    } else {
                        switchValues.storeSwitchStatus(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE, 0L)
                        // Keep Long Sentence ON even when Time Delay is OFF
                        switchValues.storeSwitchStatus(SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET, true)
                        _navigation.emit(BlockerPageNavigation.ShowToast("Time Delay disabled"))
                    }
                }
                SwitchIdentifier.REAL_FRIEND_VISIBLE -> {
                    if (newValue) {
                        switchValues.storeSwitchStatus(SwitchIdentifier.TIME_DELAY_DURATION_SET, false)
                        switchValues.storeSwitchStatus(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE, 3L)
                        // Long Sentence always ON in background
                        switchValues.storeSwitchStatus(SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET, true)
                        val currentEmail = switchValues.getRealFriendEmail() ?: ""
                        _navigation.emit(BlockerPageNavigation.EditTextField(
                            "Real Friend Email",
                            currentEmail,
                            "Enter your accountability partner's email",
                            SwitchIdentifier.REAL_FRIEND_EMAIL
                        ))
                        _navigation.emit(BlockerPageNavigation.ShowToast("Real Friend enabled — enter partner's email"))
                    } else {
                        switchValues.storeSwitchStatus(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE, 0L)
                        switchValues.storeSwitchStatus(SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET, true)
                        _navigation.emit(BlockerPageNavigation.ShowToast("Real Friend disabled"))
                    }
                }
            }

            // Normal toggle
            switchValues.storeSwitchStatus(switchKey, newValue)

            _state.update { state ->
                state.copy(
                    settingItems = state.settingItems.map { itItem ->
                        when (itItem.switchKey) {
                            // Update the toggled switch
                            switchKey -> itItem.copy(switchValue = newValue)
                            // If a protective mode was enabled, reflect disabled state of others
                            SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET,
                            SwitchIdentifier.TIME_DELAY_DURATION_SET,
                            SwitchIdentifier.REAL_FRIEND_VISIBLE -> {
                                // These were already set above via storeSwitchStatus
                                // Reload from the stored values to reflect changes
                                itItem.copy(switchValue = false)
                            }
                            else -> itItem
                        }
                    }
                )
            }

            // For protective mode switches, reload all items to get correct switch states
            if (switchKey == SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET ||
                switchKey == SwitchIdentifier.TIME_DELAY_DURATION_SET ||
                switchKey == SwitchIdentifier.REAL_FRIEND_VISIBLE
            ) {
                loadSettingItems()
            }

            // Refresh accessibility service blocking config
            MyAccessibilityService.instance?.refreshBlockingConfig()

            // Show toast feedback
            val switchName = item.title
            val action = if (newValue) "enabled" else "disabled"
            _navigation.emit(BlockerPageNavigation.ShowToast("$switchName $action"))

            Timber.i("Switch $switchKey toggled to $newValue")
        }
    }

    /**
     * Called after VPN permission is granted — actually start the VPN + persist switch.
     */
    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, true)
            _state.update { state ->
                state.copy(
                    settingItems = state.settingItems.map {
                        if (it.switchKey == SwitchIdentifier.VPN_SWITCH) it.copy(switchValue = true)
                        else it
                    }
                )
            }
            // Refresh VPN management state so the toggle on VpnManagementPage
            // reflects the new ON state immediately.
            loadVpnManagementState()
            _navigation.emit(BlockerPageNavigation.ShowToast("VPN enabled"))
        }
    }

    /**
     * Called after the user returns from the Device Admin activation screen.
     *
     * If admin was granted → persist the switch ON + refresh accessibility service.
     * If admin was NOT granted (user cancelled) → ensure switch stays OFF + show toast.
     */
    fun onDeviceAdminResult(granted: Boolean) {
        viewModelScope.launch {
            if (granted) {
                switchValues.storeSwitchStatus(SwitchIdentifier.PREVENT_UNINSTALL_SWITCH, true)
                _state.update { state ->
                    state.copy(
                        settingItems = state.settingItems.map {
                            if (it.switchKey == SwitchIdentifier.PREVENT_UNINSTALL_SWITCH)
                                it.copy(switchValue = true)
                            else it
                        }
                    )
                }
                MyAccessibilityService.instance?.refreshBlockingConfig()
                _navigation.emit(BlockerPageNavigation.ShowToast("Prevent uninstall enabled — Device Admin active"))
                Timber.i("Prevent uninstall enabled (Device Admin granted)")
            } else {
                // User cancelled — ensure switch stays OFF
                switchValues.storeSwitchStatus(SwitchIdentifier.PREVENT_UNINSTALL_SWITCH, false)
                _state.update { state ->
                    state.copy(
                        settingItems = state.settingItems.map {
                            if (it.switchKey == SwitchIdentifier.PREVENT_UNINSTALL_SWITCH)
                                it.copy(switchValue = false)
                            else it
                        }
                    )
                }
                _navigation.emit(BlockerPageNavigation.ShowToast("Device Admin not granted — Prevent uninstall disabled"))
                Timber.w("Prevent uninstall NOT enabled (Device Admin cancelled)")
            }
        }
    }

    /**
     * Save a text field value (from edit dialog).
     */
    fun saveTextField(switchKey: String, value: String) {
        viewModelScope.launch {
            // Special handling: setting page title input → insert as keyword
            if (switchKey == "block_setting_title_input") {
                if (value.isNotBlank()) {
                    val item = protect.yourself.database.selectedKeywords.SelectedKeywordItemModel(
                        key = "setting_title_${System.currentTimeMillis()}",
                        keyword = value.trim(),
                        identifier = protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS.value,
                        isSelected = true
                    )
                    db.selectedKeywordDao().upsert(item)
                    // Enable the setting page block switch
                    switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH, true)
                    // Refresh accessibility service config
                    MyAccessibilityService.instance?.refreshBlockingConfig()
                    _navigation.emit(BlockerPageNavigation.ShowToast("Title '$value' added. Settings pages + apps with matching class names will be blocked."))
                }
                loadSettingItems()
                return@launch
            }

            // NEW: Package + Intent name blocking input
            if (switchKey == "block_package_intent_input") {
                if (value.isNotBlank()) {
                    val input = value.trim()
                    // If it looks like a package name (contains dots), store as app entry
                    if (input.contains(".") && !input.contains(" ")) {
                        val item = protect.yourself.database.selectedApps.SelectedAppItemModel(
                            key = "blocked_pkg_${System.currentTimeMillis()}",
                            packageName = input,
                            appName = input,
                            identifier = protect.yourself.database.selectedApps.SelectedAppListIdentifier.BLOCKED_PACKAGE_NAMES.value,
                            isSelected = true
                        )
                        db.selectedAppsListDao().upsert(item)
                        _navigation.emit(BlockerPageNavigation.ShowToast("Package '$input' will be blocked"))
                    } else {
                        // Store as intent/class name keyword
                        val item = protect.yourself.database.selectedKeywords.SelectedKeywordItemModel(
                            key = "blocked_intent_${System.currentTimeMillis()}",
                            keyword = input,
                            identifier = protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier.BLOCKED_INTENT_NAMES.value,
                            isSelected = true
                        )
                        db.selectedKeywordDao().upsert(item)
                        _navigation.emit(BlockerPageNavigation.ShowToast("Intent/class '$input' will be blocked"))
                    }
                    // Enable the package+intent switch
                    switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH, true)
                    // Refresh accessibility service config
                    MyAccessibilityService.instance?.refreshBlockingConfig()
                }
                loadSettingItems()
                return@launch
            }

            switchValues.storeSwitchStatus(switchKey, value)
            // Also set the "is set" flag for certain keys
            when (switchKey) {
                SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE -> {
                    switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE_SET, value.isNotBlank())
                }
                SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE -> {
                    switchValues.storeSwitchStatus(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE_SET, value.isNotBlank())
                }
                SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL -> {
                    switchValues.storeSwitchStatus(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL_SET, value.isNotBlank())
                }
            }
            // Reload items to update action labels
            loadSettingItems()
            // Also refresh VPN management state if a VPN-related field changed,
            // so the VPN management page reflects the new value live.
            if (switchKey == SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE) {
                loadVpnManagementState()
            }
            _navigation.emit(BlockerPageNavigation.ShowToast("Saved"))
        }
    }

    /**
     * Save a number field value (from edit dialog).
     */
    fun saveNumberField(switchKey: String, value: Int) {
        viewModelScope.launch {
            switchValues.storeSwitchStatus(switchKey, value)
            loadSettingItems()
            _navigation.emit(BlockerPageNavigation.ShowToast("Saved: $value"))
        }
    }

    fun onActionClick(item: SettingPageItemModel) {
        Timber.d("Action clicked: ${item.identifier}")
        viewModelScope.launch {
            val nav = when (item.identifier) {
                // Permissions
                SettingPageItemIdentifiers.ACCESSIBILITY_PERMISSION -> BlockerPageNavigation.OpenAccessibilitySettings
                SettingPageItemIdentifiers.DISPLAY_POPUP_WINDOW_PERMISSION -> BlockerPageNavigation.OpenOverlaySettings

                // App picker pages
                SettingPageItemIdentifiers.BLOCKLIST_APPS -> BlockerPageNavigation.OpenSelectAppPage("Blocklist Apps", SelectedAppListIdentifier.BLOCK_APPS)
                SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER -> BlockerPageNavigation.OpenSelectAppPage("Whitelist Browsers", SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER)
                SettingPageItemIdentifiers.WHITELIST_VPN_APPS -> BlockerPageNavigation.OpenSelectAppPage("VPN Whitelist Apps", SelectedAppListIdentifier.VPN_WHITELIST_APPS)
                SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS -> BlockerPageNavigation.OpenSelectAppPage("Block In-App Browsers", SelectedAppListIdentifier.BLOCK_IN_APP_BROWSER_APPS)
                SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS -> BlockerPageNavigation.OpenSelectAppPage("Block New Install Apps", SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS)
                SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE -> {
                    // Text input for settings page title to block
                    BlockerPageNavigation.EditTextField(
                        "Title to Block in Settings",
                        "",
                        "Enter a settings page title (e.g. 'battery', 'apps')",
                        "block_setting_title_input"
                    )
                }
                SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE_APPS ->
                    BlockerPageNavigation.OpenKeywordManagerTab(protect.yourself.features.keywordManagerPage.KeywordTab.SETTING_TITLES)
                SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP -> BlockerPageNavigation.OpenSelectAppPage("Blocklist Whitelist Detected Apps", SelectedAppListIdentifier.BLOCK_WHITELIST_DETECTED_APPS)

                // Package + Intent name blocking — open dedicated management page
                SettingPageItemIdentifiers.ADD_PACKAGE_INTENT_TO_BLOCK ->
                    BlockerPageNavigation.OpenPackageIntentManager

                // VPN management page (mode picker + custom DNS manager + advanced settings)
                SettingPageItemIdentifiers.VPN_MANAGE ->
                    BlockerPageNavigation.OpenVpnManagement

                // Edit text fields
                // LONG_SENTENCE_CUSTOM_MESSAGE removed from UI — uses default message
                SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE -> {
                    val current = switchValues.getVpnNotificationCustomMessage() ?: ""
                    BlockerPageNavigation.EditTextField("VPN Notification Message", current, "Custom message for VPN notification", SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE)
                }
                SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE -> {
                    val current = switchValues.getBlockScreenCustomMessage() ?: ""
                    BlockerPageNavigation.EditTextField("Block Screen Message", current, "Custom message shown on block screen", SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE)
                }
                SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP -> {
                    val current = switchValues.getBlockScreenRedirectUrl() ?: ""
                    BlockerPageNavigation.EditTextField("Redirect URL", current, "URL to open when user taps Close (e.g. https://...)", SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL)
                }

                // Edit number fields
                SettingPageItemIdentifiers.TIME_DELAY_CUSTOM_DURATION -> {
                    val current = switchValues.getTimeDelayCustomDurationSeconds()
                    BlockerPageNavigation.EditNumberField("Time Delay (seconds)", current, 1, 300, SwitchIdentifier.TIME_DELAY_CUSTOM_DURATION)
                }
                SettingPageItemIdentifiers.BLOCKED_SCREEN_COUNTDOWN -> {
                    val current = switchValues.getBlockScreenCountDownSeconds()
                    BlockerPageNavigation.EditNumberField("Block Screen Countdown (seconds)", current, 3, 300, SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)
                }

                // Image picker
                SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE -> BlockerPageNavigation.PickBlockScreenImage

                // App lock
                SettingPageItemIdentifiers.SET_APP_LOCK -> BlockerPageNavigation.OpenAppLockSetup

                // Other
                SettingPageItemIdentifiers.BLOCKER_CUSTOM_KEYWORD_WEBSITE -> BlockerPageNavigation.OpenKeywordManager
                SettingPageItemIdentifiers.REQUEST_HISTORY -> BlockerPageNavigation.OpenRequestHistory
                SettingPageItemIdentifiers.SUGGEST_PROTECTIVE_MODE -> BlockerPageNavigation.OpenUrl("mailto:support@protectyourself.app?subject=Suggest%20Protective%20Mode")
                SettingPageItemIdentifiers.KEEP_NOPOX_LIVE -> BlockerPageNavigation.OpenFaq

                else -> {
                    Timber.w("Unhandled action: ${item.identifier}")
                    null
                }
            }
            nav?.let { _navigation.emit(it) }
        }
    }

    private fun buildSettingItems(): List<SettingPageItemModel> = buildList {
        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_ALERT, "Alert", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_SCREEN_COUNT, "Block screens", info = "Total times content has been blocked", actionLabel = "0"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.ACCESSIBILITY_PERMISSION, "Accessibility permission", info = "Required to block content", actionLabel = "Grant"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.DISPLAY_POPUP_WINDOW_PERMISSION, "Display pop-up window permission", info = "Required to show block screen", actionLabel = "Grant"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_ACCOUNTABILITY_PARTNER, "Protective Mode", isSection = true))
        // Long Sentence removed from UI — always active in background automatically
        add(SettingPageItemModel(SettingPageItemIdentifiers.TIME_DELAY, "Time Delay", info = "Wait before disabling a switch", switchKey = SwitchIdentifier.TIME_DELAY_DURATION_SET))
        add(SettingPageItemModel(SettingPageItemIdentifiers.TIME_DELAY_CUSTOM_DURATION, "Time Delay duration", info = "Set delay in seconds", actionLabel = "30s"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.REAL_FRIEND, "Real Friend", info = "Require friend's approval to disable switches", switchKey = SwitchIdentifier.REAL_FRIEND_VISIBLE))
        add(SettingPageItemModel(SettingPageItemIdentifiers.DAILY_REPORT, "Daily Report", info = "Daily summary notification", switchKey = SwitchIdentifier.DAILY_REPORT_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SUGGEST_PROTECTIVE_MODE, "Suggest protective mode", info = "Suggest new protective modes", actionLabel = "Email"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.REQUEST_HISTORY, "Request history", info = "View pending + past protective mode requests", actionLabel = "View"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_CONTENT_BLOCKING, "Content Blocking", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.PORN_BLOCKER, "Porn blocker", info = "Block content based on keyword list", switchKey = SwitchIdentifier.PORN_BLOCKER_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKER_CUSTOM_KEYWORD_WEBSITE, "Blocklist keywords", info = "Add/remove keywords that trigger block", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKLIST_APPS, "Blocklist apps", info = "Apps that get blocked on launch", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SAFE_SEARCH, "SafeSearch enforcement", info = "Redirect Google/Bing/YouTube/DuckDuckGo to SafeSearch variants. VPN adds DNS-level enforcement.", switchKey = SwitchIdentifier.SAFE_SEARCH_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_UNSUPPORTED_BROWSERS, "Block unsupported browsers", info = "Block all browsers except those in your whitelist", switchKey = SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER, "Whitelist unsupported browsers", info = "Allow specific browsers to bypass the unsupported-browser block", actionLabel = "Manage"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_UNINSTALL_PROTECTION, "Uninstall Protection", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.PREVENT_UNINSTALL_SETTINGS, "Prevent uninstall", info = "Block attempts to uninstall (requires Device Admin)", switchKey = SwitchIdentifier.PREVENT_UNINSTALL_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_PHONE_REBOOT, "Block phone reboot", info = "Restart blocking automatically after reboot", switchKey = SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE, "Title-based block setting", info = "Block settings pages by title (e.g. 'battery' blocks the battery page). Also blocks apps whose activity class name matches.", actionLabel = "Add"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE_APPS, "Manage blocked titles", info = "View and remove blocked titles", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_PACKAGE_INTENT, "Package + Intent Blocking", info = "Block apps by package name (e.g. com.example.app) or intent/class name", switchKey = SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.ADD_PACKAGE_INTENT_TO_BLOCK, "Manage blocked packages/intents", info = "Add, view, and remove package + intent entries", actionLabel = "Manage"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_ADVANCE_FEATURE, "Advanced feature", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN, "VPN (DNS blocking)", info = "Block adult content at network level. Tap Manage to choose a filtering mode.", switchKey = SwitchIdentifier.VPN_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_MANAGE, "VPN mode", info = "Choose Balanced, Strict, or Custom DNS provider.", actionLabel = "Balanced"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.WHITELIST_VPN_APPS, "Whitelist VPN apps", info = "Apps that bypass VPN", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE, "VPN notification message", info = "Custom message for VPN notification", actionLabel = "Default"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_NOTIFICATION_HIDE, "Hide VPN notification content", switchKey = SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS, "Block new install apps", info = "Auto-block newly installed apps", switchKey = SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS, "Block in-app browsers", info = "Block in-app browsers inside other apps", switchKey = SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE, "Blocked screen image for motivation", info = "Custom image shown on block screen", actionLabel = "Choose"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE, "Blocked screen message", info = "Custom message shown on block screen", actionLabel = "Default"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKED_SCREEN_COUNTDOWN, "Blocked screen countdown", info = "Require waiting N seconds before Close (3-300)", actionLabel = "Off"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP, "Custom redirect URL", info = "URL to open when user taps Close", actionLabel = "None"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP, "Blocklist whitelist detected apps", info = "Apps detected via accessibility events", actionLabel = "Manage"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_APP_LOCK, "App Lock", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SET_APP_LOCK, "App lock", info = "Require PIN/password/pattern to open app", switchKey = SwitchIdentifier.SET_APP_LOCK_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.TOUCH_ID, "Touch ID (biometric)", info = "Use fingerprint/face to unlock (configure in App Lock)", switchKey = SwitchIdentifier.TOUCH_ID_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.DISABLE_FORGOT_PASSWORD, "Disable Forgot Password", info = "Hide forgot password option (configure in App Lock)", switchKey = SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_FAQ, "Keep Protect Yourself Live", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.KEEP_NOPOX_LIVE, "How to keep app running", info = "Battery + performance tips", actionLabel = "View"))
    }

    // ===== VPN Management Page =====

    /**
     * Turns the VPN off from the VPN management page. Persists the switch
     * state and refreshes both the VPN management state and the main settings
     * list so the UI reflects the change. The actual `MyVpnService.stop()`
     * call is performed by the UI layer (which has the Context).
     */
    fun toggleVpnOff() {
        viewModelScope.launch {
            switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
            loadVpnManagementState()
            loadSettingItems()
        }
    }

    /**
     * Toggles the "hide VPN notification content" switch from the VPN
     * management page. The change takes effect on the next VPN restart.
     */
    fun setVpnNotificationHidden(hidden: Boolean) {
        viewModelScope.launch {
            switchValues.storeSwitchStatus(SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH, hidden)
            loadVpnManagementState()
            loadSettingItems()
        }
    }

    /**
     * Loads the dedicated VPN management page state: current VPN on/off flag,
     * current filtering mode, list of available custom DNS presets, and which
     * preset is currently selected.
     */
    fun loadVpnManagementState() {
        viewModelScope.launch {
            val vpnOn = switchValues.isVpnSwitchOn()
            val mode = switchValues.getVpnConnectionType()
            val presets = db.vpnCustomDnsDao().getAll()
            val selectedPresetKey = db.vpnCustomDnsDao().getSelected()?.key
            val hideNotification = switchValues.isVpnNotificationHideSwitchOn()
            val notificationMessage = switchValues.getVpnNotificationCustomMessage() ?: ""

            _vpnManagementState.update {
                VpnManagementState(
                    isVpnEnabled = vpnOn,
                    currentMode = mode,
                    customDnsPresets = presets,
                    selectedCustomDnsKey = selectedPresetKey,
                    isNotificationHidden = hideNotification,
                    notificationMessage = notificationMessage,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Switches the VPN filtering mode. Persists the new mode, then if the VPN
     * is currently running, asks the UI to restart the service so the new DNS
     * takes effect immediately.
     */
    fun setVpnMode(mode: VpnConnectionTypeIdentifiers) {
        viewModelScope.launch {
            switchValues.storeVpnConnectionType(mode)

            // Refresh the VPN management state + the main settings list so the
            // UI reflects the new mode immediately.
            loadVpnManagementState()
            loadSettingItems()

            val isVpnOn = switchValues.isVpnSwitchOn()

            // Restart the VPN service if it is currently running so the new
            // DNS server takes effect immediately. The actual restart is done
            // by the UI layer (which has the Context) — see RestartVpn handler
            // in BlockerPageHome.kt.
            if (isVpnOn) {
                _navigation.emit(BlockerPageNavigation.RestartVpn)
                _navigation.emit(
                    BlockerPageNavigation.ShowToast(
                        "VPN mode changed to ${vpnModeLabel(mode)}. Restarting VPN…"
                    )
                )
            } else {
                _navigation.emit(
                    BlockerPageNavigation.ShowToast(
                        "VPN mode changed to ${vpnModeLabel(mode)}."
                    )
                )
            }
        }
    }

    /**
     * Selects a custom DNS preset (used when the VPN is in CUSTOM mode).
     * Persists the selection and asks the UI to restart the VPN if it is
     * currently running.
     */
    fun selectCustomDnsPreset(presetKey: String) {
        viewModelScope.launch {
            db.vpnCustomDnsDao().setSelected(presetKey)

            loadVpnManagementState()

            val isVpnOn = switchValues.isVpnSwitchOn()
            val isCustomMode = switchValues.getVpnConnectionType() == VpnConnectionTypeIdentifiers.CUSTOM

            if (isVpnOn && isCustomMode) {
                _navigation.emit(BlockerPageNavigation.RestartVpn)
                _navigation.emit(
                    BlockerPageNavigation.ShowToast("Custom DNS provider updated. Restarting VPN…")
                )
            } else {
                _navigation.emit(
                    BlockerPageNavigation.ShowToast("Custom DNS provider updated.")
                )
            }
        }
    }

    /**
     * Returns the user-facing label for a VPN mode.
     * Mirrors the labels used by [MyVpnService] in the foreground notification.
     */
    private fun vpnModeLabel(mode: VpnConnectionTypeIdentifiers): String = when (mode) {
        VpnConnectionTypeIdentifiers.NORMAL -> "Balanced"
        VpnConnectionTypeIdentifiers.POWERFUL -> "Strict"
        VpnConnectionTypeIdentifiers.CUSTOM -> "Custom DNS"
        VpnConnectionTypeIdentifiers.OFF -> "Balanced"
    }

    companion object {
        fun factory(db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BlockerPageViewModel(db) as T
                }
            }
    }
}

data class BlockerPageState(
    val settingItems: List<SettingPageItemModel> = emptyList(),
    val isLoading: Boolean = true,
    val toastMessage: String? = null
)

/**
 * State for the dedicated VPN Management page.
 */
data class VpnManagementState(
    val isVpnEnabled: Boolean = false,
    val currentMode: VpnConnectionTypeIdentifiers = VpnConnectionTypeIdentifiers.NORMAL,
    val customDnsPresets: List<VpnCustomDnsItemModel> = emptyList(),
    val selectedCustomDnsKey: String? = null,
    val isNotificationHidden: Boolean = false,
    val notificationMessage: String = "",
    val isLoading: Boolean = true
)
