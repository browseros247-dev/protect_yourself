package protect.yourself.features.blockerPage

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
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
import protect.yourself.features.blockerPage.data.SettingPageItemModel
import protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers
import protect.yourself.features.blockerPage.identifiers.VpnConnectionTypeIdentifiers
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.service.MyVpnService
import timber.log.Timber

/**
 * Navigation events emitted by BlockerPageViewModel.
 */
sealed class BlockerPageNavigation {
    data class OpenSelectAppPage(val title: String, val identifier: SelectedAppListIdentifier) : BlockerPageNavigation()
    data class OpenUrl(val url: String) : BlockerPageNavigation()
    data class ShowToast(val message: String) : BlockerPageNavigation()
    /**
     * Show a toast using a string resource + optional format args. Use this
     * instead of [ShowToast] when the message needs to be localizable — the
     * ViewModel does not have a Context to resolve string resources itself,
     * so it emits the resource ID and the UI layer resolves it.
     */
    data class ShowToastRes(val resId: Int, val args: List<Any> = emptyList()) : BlockerPageNavigation()
    data class EditTextField(val title: String, val currentValue: String, val hint: String, val switchKey: String) : BlockerPageNavigation()
    data class EditNumberField(val title: String, val currentValue: Int, val min: Int, val max: Int, val switchKey: String) : BlockerPageNavigation()
    data object RequestVpnPermission : BlockerPageNavigation()
    data object StopVpn : BlockerPageNavigation()
    data object RestartVpn : BlockerPageNavigation()
    data object OpenVpnManagement : BlockerPageNavigation()
    data object OpenAccessibilitySettings : BlockerPageNavigation()
    data object OpenOverlaySettings : BlockerPageNavigation()
    data object OpenAppLockSetup : BlockerPageNavigation()
    data object OpenUnifiedBlocking : BlockerPageNavigation()
    data object OpenStopMe : BlockerPageNavigation()
    data object OpenFaq : BlockerPageNavigation()
    data object OpenRequestHistory : BlockerPageNavigation()
    data object PickBlockScreenImage : BlockerPageNavigation()
    data object ClearBlockScreenImage : BlockerPageNavigation()
    data object ClearBlockScreenMessage : BlockerPageNavigation()
    /**
     * Emitted when the user taps "Preview block screen". The UI layer launches
     * PornBlockActivity with no extras (so it shows the default message) —
     * the activity itself loads the user's custom message + image from the DB.
     */
    data object PreviewBlockScreen : BlockerPageNavigation()
    data object RequestDeviceAdmin : BlockerPageNavigation()
    /**
     * PM-01 fix: Time Delay enforcement. When Time Delay is the active
     * protective mode and the user tries to toggle a switch, this event
     * is emitted with the delay duration. The UI shows a countdown dialog
     * and calls [BlockerPageViewModel.confirmToggleAfterDelay] when the
     * countdown completes.
     */
    data class RequestTimeDelay(val delaySeconds: Int, val item: SettingPageItemModel) : BlockerPageNavigation()
    /**
     * BUG-05 fix: emitted after a custom DNS preset has been SUCCESSFULLY
     * persisted to the DB. The UI uses this to dismiss the Add Custom DNS
     * dialog only after the DB write completes — previously the dialog was
     * dismissed synchronously based on the (validation-only) return value
     * of [BlockerPageViewModel.addCustomDnsPreset], which meant a DB write
     * failure would leave the user with no visible preset and no clear
     * error context.
     */
    data object VpnCustomDnsPresetAdded : BlockerPageNavigation()
}

/**
 * BlockerPageViewModel — manages state for the Blocker (Home) tab.
 */
class BlockerPageViewModel(
    application: Application,
    private val db: AppDatabase
) : AndroidViewModel(application) {

    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    private val _state = MutableStateFlow(BlockerPageState())
    val state: StateFlow<BlockerPageState> = _state.asStateFlow()

    /** Dedicated state for the VPN Management page. */
    private val _vpnManagementState = MutableStateFlow(VpnManagementState())
    val vpnManagementState: StateFlow<VpnManagementState> = _vpnManagementState.asStateFlow()

    private val _navigation = MutableSharedFlow<BlockerPageNavigation>(extraBufferCapacity = 5)
    val navigation: SharedFlow<BlockerPageNavigation> = _navigation.asSharedFlow()

    /**
     * CRASH FIX: Safe coroutine launcher that catches all exceptions and
     * emits a user-visible toast instead of crashing. Used for all DB-write
     * operations that could fail due to locked DB, disk-full, constraint
     * violations, or migration glitches.
     */
    private fun safeLaunch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Timber.e(t, "safeLaunch: uncaught exception in ViewModel coroutine")
                try {
                    _navigation.emit(BlockerPageNavigation.ShowToast("Operation failed: ${t.message ?: "unknown error"}"))
                } catch (_: Throwable) {}
            }
        }
    }

    init {
        loadSettingItems()
        // BUG-06 fix: VPN service state reconciliation is handled by the
        // VPN management page's LaunchedEffect + the AppSystemActionReceiver
        // (BUG-12 fix). A polling loop here was removed because it caused
        // Kotlin 1.9 kapt stub generation to fail with "Could not load
        // module <Error module>" — likely a compiler bug with infinite
        // while loops inside init blocks in AndroidViewModel.
    }

    fun loadSettingItems() {
        safeLaunch {
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

            // Apply dependency rules: disable settings whose prerequisite is off.
            val itemsWithDependencies = applyDependencyRules(itemsWithValues)

            // AL-02 fix: hide Touch ID and Disable Forgot Password cards when
            // App Lock is NOT set up. These cards are meaningless without an
            // app lock — the user can't enable Touch ID or disable forgot
            // password if there's no lock configured. Showing them causes
            // confusion (the user sees cards that can't be used) and was
            // reported as "duplicate cards appearing when App Lock is not
            // set up."
            //
            // NopoX 1.0.53 behavior: these toggles only appear after the user
            // sets up an app lock. We replicate this by filtering them out
            // when SET_APP_LOCK_SWITCH is false.
            val appLockEnabled = itemsWithDependencies
                .firstOrNull { it.identifier == SettingPageItemIdentifiers.SET_APP_LOCK }
                ?.switchValue ?: false
            val filteredItems = if (appLockEnabled) {
                itemsWithDependencies
            } else {
                itemsWithDependencies.filterNot { item ->
                    item.identifier == SettingPageItemIdentifiers.TOUCH_ID ||
                        item.identifier == SettingPageItemIdentifiers.DISABLE_FORGOT_PASSWORD
                }
            }

            _state.update { it.copy(settingItems = filteredItems, isLoading = false) }
            Timber.i("BlockerPage loaded ${filteredItems.size} setting items (appLock=$appLockEnabled)")
        }
    }

    /**
     * Apply dependency rules: mark settings as [isDisabled] with a clear
     * [dependencyMessage] when their prerequisite setting is not enabled.
     *
     * This ensures a smooth UX — the user sees WHY a setting can't be toggled
     * and which prerequisite must be enabled first, rather than the switch
     * silently refusing to flip.
     *
     * ## Current dependency rules
     *
     * | Dependent setting              | Prerequisite          | Message |
     * |-------------------------------|-----------------------|---------|
     * | SAFE_SEARCH                   | VPN (DNS blocking)    | "Enable VPN (DNS blocking) first to use SafeSearch enforcement." |
     * | VPN_MANAGE                    | VPN (DNS blocking)    | "Enable VPN first to choose a filtering mode." |
     * | WHITELIST_VPN_APPS            | VPN (DNS blocking)    | "Enable VPN first to manage VPN whitelist apps." |
     * | VPN_NOTIFICATION_MESSAGE      | VPN (DNS blocking)    | "Enable VPN first to customize the notification message." |
     * | VPN_NOTIFICATION_HIDE         | VPN (DNS blocking)    | "Enable VPN first to hide notification content." |
     * | BLOCK_UNSUPPORTED_BROWSERS    | PORN_BLOCKER          | "Enable Porn blocker first to block unsupported browsers." |
     * | WHITELIST_UNSUPPORTED_BROWSER | BLOCK_UNSUPPORTED_BROWSERS | "Enable 'Block unsupported browsers' first to manage its whitelist." |
     * | BLOCK_IN_APP_BROWSERS         | PORN_BLOCKER          | "Enable Porn blocker first to block in-app browsers." |
     * | BLOCK_NEW_INSTALL_APPS        | PORN_BLOCKER          | "Enable Porn blocker first to auto-block new installs." |
     * | BLOCK_SETTING_PAGE_BY_TITLE   | PORN_BLOCKER          | "Enable Porn blocker first to block settings by title." |
     * | BLOCK_WHITELIST_DETECTED_APP  | PORN_BLOCKER          | "Enable Porn blocker first to manage blocklist detected apps." |
     * | BLOCKLIST_APPS                | PORN_BLOCKER          | "Enable Porn blocker first to manage the blocklist." |
     * | UNIFIED_BLOCKING_MANAGEMENT   | PORN_BLOCKER          | "Enable Porn blocker first to manage blocking lists." |
     * | TIME_DELAY_CUSTOM_DURATION    | TIME_DELAY            | "Enable Time Delay first to set a custom duration." |
     * | BLOCK_PHONE_REBOOT            | PREVENT_UNINSTALL     | "Enable Prevent uninstall first to block phone reboot." |
     */
    private suspend fun applyDependencyRules(items: List<SettingPageItemModel>): List<SettingPageItemModel> {
        // Read prerequisite switch states once
        val vpnOn = switchValues.isVpnSwitchOn()
        val pornBlockerOn = switchValues.isPornBlockerSwitchOn()
        val blockUnsupportedBrowsersOn = switchValues.isBlockUnsupportedBrowsersSwitchOn()
        val timeDelayOn = switchValues.isTimeDelayDurationSet()
        val preventUninstallOn = switchValues.isPreventUninstallSwitchOn()

        if (protect.yourself.BuildConfig.DEBUG) {
            Timber.w("DEBUG applyDependencyRules: vpnOn=$vpnOn, pornBlockerOn=$pornBlockerOn, blockUnsupportedBrowsersOn=$blockUnsupportedBrowsersOn, timeDelayOn=$timeDelayOn, preventUninstallOn=$preventUninstallOn")
        }

        return items.map { item ->
            val result = when (item.identifier) {
                // SafeSearch depends on VPN (DNS-level enforcement needs VPN)
                SettingPageItemIdentifiers.SAFE_SEARCH -> {
                    if (!vpnOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable VPN (DNS blocking) first to use SafeSearch enforcement."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                // VPN sub-settings depend on VPN being enabled
                SettingPageItemIdentifiers.VPN_MANAGE -> {
                    if (!vpnOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable VPN first to choose a filtering mode."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.WHITELIST_VPN_APPS -> {
                    if (!vpnOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable VPN first to manage VPN whitelist apps."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE -> {
                    if (!vpnOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable VPN first to customize the notification message."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.VPN_NOTIFICATION_HIDE -> {
                    if (!vpnOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable VPN first to hide notification content."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                // Content-blocking sub-features depend on Porn Blocker
                SettingPageItemIdentifiers.BLOCK_UNSUPPORTED_BROWSERS -> {
                    if (!pornBlockerOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Porn blocker first to block unsupported browsers."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER -> {
                    if (!blockUnsupportedBrowsersOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable 'Block unsupported browsers' first to manage its whitelist."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS -> {
                    if (!pornBlockerOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Porn blocker first to block in-app browsers."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS -> {
                    if (!pornBlockerOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Porn blocker first to auto-block new installs."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE -> {
                    if (!pornBlockerOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Porn blocker first to block settings by title."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP -> {
                    if (!pornBlockerOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Porn blocker first to manage blocklist detected apps."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.BLOCKLIST_APPS -> {
                    if (!pornBlockerOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Porn blocker first to manage the blocklist."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                SettingPageItemIdentifiers.UNIFIED_BLOCKING_MANAGEMENT -> {
                    if (!pornBlockerOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Porn blocker first to manage blocking lists."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                // Time Delay custom duration depends on Time Delay being enabled
                SettingPageItemIdentifiers.TIME_DELAY_CUSTOM_DURATION -> {
                    if (!timeDelayOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Time Delay first to set a custom duration."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                // Block phone reboot depends on Prevent Uninstall (both are anti-circumvention)
                SettingPageItemIdentifiers.BLOCK_PHONE_REBOOT -> {
                    if (!preventUninstallOn) item.copy(
                        isDisabled = true,
                        dependencyMessage = "Enable Prevent uninstall first to block phone reboot."
                    ) else item.copy(isDisabled = false, dependencyMessage = null)
                }
                else -> item
            }
            if (protect.yourself.BuildConfig.DEBUG && result.isDisabled) {
                Timber.w("DEBUG applyDependencyRules: DISABLED '${result.title}' — ${result.dependencyMessage}")
            }
            result
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
                val app = getApplication<Application>()
                item.copy(actionLabel = if (msg.isNullOrBlank())
                    app.getString(protect.yourself.R.string.block_screen_message_action_default)
                else
                    app.getString(protect.yourself.R.string.block_screen_message_action_custom))
            }
            SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP -> {
                val url = switchValues.getBlockScreenRedirectUrl()
                item.copy(actionLabel = if (url.isNullOrBlank()) "None" else "Set")
            }
            SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE -> {
                val path = switchValues.getBlockScreenStoreImagePath()
                // Use the localized action labels: "Choose" when nothing is set,
                // "Change" once an image has been picked. The Clear button is
                // surfaced separately by the UI card.
                val app = getApplication<Application>()
                item.copy(actionLabel = if (path.isNullOrBlank())
                    app.getString(protect.yourself.R.string.block_screen_image_action_choose)
                else
                    app.getString(protect.yourself.R.string.block_screen_image_action_change))
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
        SwitchIdentifier.VPN_SWITCH -> {
            // FIX: Check both the DB value AND the actual service state.
            // If VPN_SWITCH is true but the service is not running (e.g. user
            // revoked VPN permission from system settings), show the switch as OFF.
            val dbValue = switchValues.isVpnSwitchOn()
            if (dbValue && !protect.yourself.features.blockerPage.service.MyVpnService.isRunning()) {
                // Service is not running but DB says ON — sync the DB to false
                Timber.w("VPN_SWITCH=true but service not running — syncing DB to false")
                switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
                false
            } else {
                dbValue
            }
        }
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
        // AUDIT FIX: if the item is disabled (prerequisite not met), show a toast
        // explaining what must be enabled first. Do NOT toggle the switch.
        if (item.isDisabled) {
            safeLaunch {
                val msg = item.dependencyMessage ?: "This setting requires a prerequisite to be enabled first."
                _navigation.emit(BlockerPageNavigation.ShowToast(msg))
                if (protect.yourself.BuildConfig.DEBUG) {
                    Timber.w("DEBUG toggleSwitch: rejected toggle on disabled item '${item.title}' — $msg")
                }
            }
            return
        }
        val switchKey = item.switchKey ?: return
        val newValue = !item.switchValue

        safeLaunch {
            // PM-01 fix: Time Delay enforcement. If Time Delay is the active
            // protective mode and the user is trying to TURN OFF a switch
            // (not ON — enabling is always allowed), show a countdown dialog.
            // The toggle is deferred until the countdown completes.
            //
            // We skip the delay for:
            // - Turning switches ON (always allowed — no delay needed)
            // - Toggling Time Delay itself (would be circular)
            // - Toggling Real Friend itself (mutual exclusion handles it)
            // - VPN switches (have their own permission flow)
            // - App Lock switches (have their own setup flow)
            if (!newValue &&
                switchKey != SwitchIdentifier.TIME_DELAY_DURATION_SET &&
                switchKey != SwitchIdentifier.REAL_FRIEND_VISIBLE &&
                switchKey != SwitchIdentifier.VPN_SWITCH &&
                switchKey != SwitchIdentifier.SET_APP_LOCK_SWITCH &&
                switchKey != SwitchIdentifier.TOUCH_ID_SWITCH &&
                switchKey != SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH
            ) {
                val isTimeDelayActive = switchValues.isTimeDelayDurationSet()
                if (isTimeDelayActive) {
                    val delaySeconds = switchValues.getTimeDelayCustomDurationSeconds()
                    Timber.i("PM-01: Time Delay active — deferring toggle of $switchKey for ${delaySeconds}s")
                    _navigation.emit(BlockerPageNavigation.RequestTimeDelay(delaySeconds, item))
                    return@safeLaunch
                }
            }

            // Special handling for VPN — requires VpnService.prepare()
            if (switchKey == SwitchIdentifier.VPN_SWITCH) {
                if (newValue) {
                    // Request VPN permission before enabling
                    _navigation.emit(BlockerPageNavigation.RequestVpnPermission)
                    return@safeLaunch
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
                    // ROOT CAUSE FIX: reload setting items so dependency rules
                    // are re-evaluated. VPN-dependent settings should now be
                    // disabled (greyed out) with a dependency message.
                    loadSettingItems()
                    return@safeLaunch
                }
            }

            // TOUCH_ID and DISABLE_FORGOT_PASSWORD toggles.
            //
            // DEDUP FIX (v1.0.54): Previously these switches were also rendered
            // inside AppLockSetupPage — meaning two UIs could write to the same
            // switch and drift out of sync. The toggles now live ONLY here on
            // the main settings page (mirrors NopoX 1.0.53 reference behavior).
            //
            // Behavior:
            //   - If turning ON and app lock is NOT set → show error toast,
            //     do not flip the switch.
            //   - If turning ON and app lock IS set → persist true.
            //   - If turning OFF → persist false (always allowed).
            //
            // TOUCH_ID also requires biometric hardware to be available — if
            // the user tries to enable it on a device without biometrics, show
            // a clear error.
            if (switchKey == SwitchIdentifier.TOUCH_ID_SWITCH) {
                if (newValue) {
                    // Turning ON Touch ID — require app lock first.
                    val appLockOn = switchValues.isAppLockSwitchOn()
                    if (!appLockOn) {
                        Timber.w("TOUCH_ID_SWITCH on rejected — app lock is not enabled")
                        _navigation.emit(BlockerPageNavigation.ShowToastRes(
                            protect.yourself.R.string.touch_id_app_lock_not_set_error
                        ))
                        return@safeLaunch
                    }
                    // Optional: warn if biometric hardware is missing. We still
                    // persist the switch (the lock screen will fall back to PIN
                    // entry), but log + toast the user so they know why nothing
                    // happens at unlock time.
                    val ctx = getApplication<Application>()
                    val biometricAvailable = try {
                        protect.yourself.features.appPasswordPage.canUseBiometric(ctx)
                    } catch (t: Throwable) {
                        Timber.w(t, "Biometric availability check failed — assuming unavailable")
                        false
                    }
                    if (!biometricAvailable) {
                        Timber.w("TOUCH_ID_SWITCH enabled but biometric hardware not available/enrolled")
                        _navigation.emit(BlockerPageNavigation.ShowToastRes(
                            protect.yourself.R.string.lock_screen_biometric_not_available
                        ))
                    }
                    switchValues.storeSwitchStatus(SwitchIdentifier.TOUCH_ID_SWITCH, true)
                    _navigation.emit(BlockerPageNavigation.ShowToast("Touch ID enabled"))
                    Timber.i("TOUCH_ID_SWITCH → ON")
                } else {
                    switchValues.storeSwitchStatus(SwitchIdentifier.TOUCH_ID_SWITCH, false)
                    _navigation.emit(BlockerPageNavigation.ShowToast("Touch ID disabled"))
                    Timber.i("TOUCH_ID_SWITCH → OFF")
                }
                // Update the local UI state so the switch reflects the new value.
                _state.update { state ->
                    state.copy(
                        settingItems = state.settingItems.map {
                            if (it.switchKey == switchKey) it.copy(switchValue = newValue)
                            else it
                        }
                    )
                }
                return@safeLaunch
            }

            if (switchKey == SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH) {
                if (newValue) {
                    // Turning ON disable forgot password — require app lock first.
                    val appLockOn = switchValues.isAppLockSwitchOn()
                    if (!appLockOn) {
                        Timber.w("DISABLE_FORGOT_PASSWORD_SWITCH on rejected — app lock is not enabled")
                        _navigation.emit(BlockerPageNavigation.ShowToastRes(
                            protect.yourself.R.string.disable_forgot_password_app_lock_not_set_error
                        ))
                        return@safeLaunch
                    }
                    switchValues.storeSwitchStatus(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, true)
                    _navigation.emit(BlockerPageNavigation.ShowToast("Forgot password option disabled"))
                    Timber.i("DISABLE_FORGOT_PASSWORD_SWITCH → ON")
                } else {
                    switchValues.storeSwitchStatus(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, false)
                    _navigation.emit(BlockerPageNavigation.ShowToast("Forgot password option enabled"))
                    Timber.i("DISABLE_FORGOT_PASSWORD_SWITCH → OFF")
                }
                _state.update { state ->
                    state.copy(
                        settingItems = state.settingItems.map {
                            if (it.switchKey == switchKey) it.copy(switchValue = newValue)
                            else it
                        }
                    )
                }
                return@safeLaunch
            }

            // SET_APP_LOCK ON → open App Lock setup page
            if (switchKey == SwitchIdentifier.SET_APP_LOCK_SWITCH && newValue) {
                _navigation.emit(BlockerPageNavigation.OpenAppLockSetup)
                return@safeLaunch
            }

            // SET_APP_LOCK OFF → disable lock entirely (clears hash + type +
            // touch ID + disable forgot password).
            //
            // AL-01 fix: the previous implementation cleared app_lock_type and
            // app_lock_stored_hash directly in the DB but NEVER stored
            // SET_APP_LOCK_SWITCH=false. This meant isAppLockSwitchOn() still
            // returned true on the next loadSettingItems() call, so the toggle
            // visually stayed ON even though the feature was disabled.
            //
            // The fix uses AppLockManager.disableLock() which properly:
            //   - clears app_lock_type to 0
            //   - clears app_lock_stored_hash to ""
            //   - stores SET_APP_LOCK_SWITCH=false
            //   - stores TOUCH_ID_SWITCH=false
            //   - stores DISABLE_FORGOT_PASSWORD_SWITCH=false (BUG-23 fix)
            // Then we reload all setting items so the UI reflects the change
            // immediately (toggle shows OFF, Touch ID / Disable Forgot Password
            // cards disappear since App Lock is no longer set up).
            if (switchKey == SwitchIdentifier.SET_APP_LOCK_SWITCH && !newValue) {
                try {
                    val appLockManager = protect.yourself.features.appPasswordPage.AppLockManager(
                        getApplication()
                    )
                    appLockManager.disableLock()
                    Timber.i("AL-01: App Lock disabled via AppLockManager — SET_APP_LOCK_SWITCH, TOUCH_ID_SWITCH, DISABLE_FORGOT_PASSWORD_SWITCH all set to false")
                } catch (t: Throwable) {
                    Timber.e(t, "AL-01: AppLockManager.disableLock() failed — falling back to manual DB clear")
                    // Fallback: manually clear the same fields AppLockManager would
                    db.switchStatusDao().upsert(protect.yourself.database.switchStatus.SwitchStatusItemModel(
                        key = "app_lock_type", value = "0", type = "long"
                    ))
                    db.switchStatusDao().upsert(protect.yourself.database.switchStatus.SwitchStatusItemModel(
                        key = "app_lock_stored_hash", value = "", type = "string"
                    ))
                    switchValues.storeSwitchStatus(SwitchIdentifier.SET_APP_LOCK_SWITCH, false)
                    switchValues.storeSwitchStatus(SwitchIdentifier.TOUCH_ID_SWITCH, false)
                    switchValues.storeSwitchStatus(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, false)
                }
                _navigation.emit(BlockerPageNavigation.ShowToast("App lock disabled"))
                // Reload all setting items so the UI reflects the change:
                //   - SET_APP_LOCK toggle shows OFF
                //   - TOUCH_ID and DISABLE_FORGOT_PASSWORD cards are hidden
                //     (they only show when App Lock is set up — AL-02 fix)
                loadSettingItems()
                // Return here — don't fall through to normal toggle
                return@safeLaunch
            }

            // PREVENT_UNINSTALL ON → request Device Admin
            if (switchKey == SwitchIdentifier.PREVENT_UNINSTALL_SWITCH && newValue) {
                _navigation.emit(BlockerPageNavigation.RequestDeviceAdmin)
                // Don't toggle yet — wait for user to grant Device Admin
                // The UI will check if admin was granted and then persist
                return@safeLaunch
            }

            // PREVENT_UNINSTALL OFF → deactivate Device Admin
            if (switchKey == SwitchIdentifier.PREVENT_UNINSTALL_SWITCH && !newValue) {
                // Deactivate Device Admin so the app can be uninstalled
                protect.yourself.features.blockerPage.utils.DeviceAdminUtils.removeActive(getApplication())
                switchValues.storeSwitchStatus(switchKey, false)
                _state.update { state ->
                    state.copy(
                        settingItems = state.settingItems.map {
                            if (it.switchKey == switchKey) it.copy(switchValue = false)
                            else it
                        }
                    )
                }
                MyAccessibilityService.instance?.refreshBlockingConfig()
                _navigation.emit(BlockerPageNavigation.ShowToast("Prevent uninstall disabled — Device Admin deactivated"))
                Timber.i("Prevent uninstall disabled (Device Admin removed)")
                return@safeLaunch
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

            // PM-03 fix: for protective mode switches, skip the incremental
            // UI update and go straight to loadSettingItems() — the old code
            // set all three protective mode switches to false in the UI state
            // BEFORE calling loadSettingItems(), causing a visual flicker where
            // all three briefly appeared OFF.
            if (switchKey == SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET ||
                switchKey == SwitchIdentifier.TIME_DELAY_DURATION_SET ||
                switchKey == SwitchIdentifier.REAL_FRIEND_VISIBLE
            ) {
                // Reload all items from DB — this picks up the correct mutual-
                // exclusion state in one atomic update, no flicker.
                loadSettingItems()
            } else {
                // Non-protective-mode switch — update incrementally for snappy UI
                _state.update { state ->
                    state.copy(
                        settingItems = state.settingItems.map { itItem ->
                            if (itItem.switchKey == switchKey) {
                                itItem.copy(switchValue = newValue)
                            } else {
                                itItem
                            }
                        }
                    )
                }
            }

            // Refresh accessibility service blocking config
            // NIB-01 fix (v1.0.60): for the BLOCK_NEW_INSTALL_APPS_SWITCH,
            // use the targeted refresh instead of the full refreshBlockingConfig.
            // The targeted refresh only reads the BLOCK_NEW_INSTALL_APPS list
            // + the switch state (<10ms) instead of re-reading ALL keywords,
            // ALL apps, ALL switches (200-500ms). This ensures the switch
            // state is updated in the cache immediately, so toggling the
            // switch ON/OFF takes effect without delay.
            val instance = MyAccessibilityService.instance
            if (instance == null) {
                Timber.w("Switch toggle: MyAccessibilityService instance is null — config not refreshed")
            } else {
                if (switchKey == SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH) {
                    try {
                        instance.refreshNewInstallBlockSync(db)
                        Timber.i("NIB-01: targeted refresh of new install block config after switch toggle")
                    } catch (t: Throwable) {
                        Timber.w(t, "NIB-01: targeted refresh failed — falling back to full refresh")
                        instance.refreshBlockingConfig()
                    }
                } else {
                    instance.refreshBlockingConfig()
                }
            }

            // Show toast feedback
            val switchName = item.title
            val action = if (newValue) "enabled" else "disabled"
            _navigation.emit(BlockerPageNavigation.ShowToast("$switchName $action"))

            Timber.i("Switch $switchKey toggled to $newValue")
        }
    }

    /**
     * PM-01 fix: Called after the Time Delay countdown completes. Re-executes
     * the toggle that was deferred. The [item] is the original SettingPageItemModel
     * that the user tried to toggle.
     */
    fun confirmToggleAfterDelay(item: SettingPageItemModel) {
        safeLaunch {
            Timber.i("PM-01: Time Delay completed — proceeding with toggle of ${item.switchKey}")
            // Re-run toggleSwitch but bypass the Time Delay check by calling
            // the internal toggle logic directly. We can't call toggleSwitch()
            // again because it would re-trigger the Time Delay check.
            // Instead, we perform the toggle directly here.
            val switchKey = item.switchKey ?: return@safeLaunch
            val newValue = !item.switchValue

            // Skip VPN, App Lock, and protective-mode special handling —
            // those are handled by toggleSwitch() and shouldn't reach here
            // (they're excluded from the Time Delay check).
            switchValues.storeSwitchStatus(switchKey, newValue)

            _state.update { state ->
                state.copy(
                    settingItems = state.settingItems.map { itItem ->
                        if (itItem.switchKey == switchKey) {
                            itItem.copy(switchValue = newValue)
                        } else {
                            itItem
                        }
                    }
                )
            }

            MyAccessibilityService.instance?.refreshBlockingConfig()

            val switchName = item.title
            val action = if (newValue) "enabled" else "disabled"
            _navigation.emit(BlockerPageNavigation.ShowToast("$switchName $action (after delay)"))
        }
    }

    /**
     * Called after VPN permission is granted — actually start the VPN + persist switch.
     */
    fun onVpnPermissionGranted() {
        safeLaunch {
            switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, true)
            _state.update { state ->
                state.copy(
                    settingItems = state.settingItems.map {
                        if (it.switchKey == SwitchIdentifier.VPN_SWITCH) it.copy(switchValue = true)
                        else it
                    }
                )
            }
            // FIX 2.2: refresh VPN management state so the toggle on the
            // VPN management page flips to ON immediately. Without this,
            // the toggle stays OFF until the user leaves and re-enters the page.
            loadVpnManagementState()
            // ROOT CAUSE FIX: reload setting items so dependency rules are
            // re-evaluated. Without this, VPN-dependent settings (SafeSearch,
            // VPN mode, VPN whitelist, etc.) remain in their stale disabled
            // state even after VPN is enabled.
            loadSettingItems()
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
        safeLaunch {
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
        safeLaunch {
            // Special handling: setting page title input → insert as keyword
            if (switchKey == SwitchIdentifier.BLOCK_SETTING_TITLE_INPUT) {
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
                    _navigation.emit(BlockerPageNavigation.ShowToast("Title '$value' will be blocked in Settings"))
                }
                loadSettingItems()
                return@safeLaunch
            }

            // NEW: Package + Intent name blocking input
            if (switchKey == SwitchIdentifier.BLOCK_PACKAGE_INTENT_INPUT) {
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
                return@safeLaunch
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
            // PM-02 fix: if the user just saved a Real Friend email, open the
            // email app with a pre-filled message to the partner. This doesn't
            // implement a full accountability backend, but it at least sends
            // the partner an initial notification that they've been chosen.
            if (switchKey == SwitchIdentifier.REAL_FRIEND_EMAIL && value.isNotBlank()) {
                val emailSubject = "Protect Yourself: You've been chosen as an accountability partner"
                val emailBody = "Hi,\n\n" +
                    "Your friend has chosen you as their accountability partner in the Protect Yourself app. " +
                    "This means they may occasionally need your support to resist urges. " +
                    "You'll receive an email when they try to disable a protective setting.\n\n" +
                    "Thank you for supporting them on their journey.\n\n" +
                    "— Protect Yourself"
                _navigation.emit(
                    BlockerPageNavigation.OpenUrl(
                        "mailto:$value?subject=${java.net.URLEncoder.encode(emailSubject, "UTF-8")}" +
                        "&body=${java.net.URLEncoder.encode(emailBody, "UTF-8")}"
                    )
                )
            }
            // Also refresh VPN management state if a VPN-related field changed,
            // so the VPN management page reflects the new value live. VPN-08 fix:
            // if the VPN is running, restart it so the new notification text
            // takes effect immediately.
            if (switchKey == SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE) {
                loadVpnManagementState()
                if (switchValues.isVpnSwitchOn()) {
                    _navigation.emit(BlockerPageNavigation.RestartVpn)
                    _navigation.emit(
                        BlockerPageNavigation.ShowToastRes(
                            protect.yourself.R.string.vpn_notification_changes_applied_toast
                        )
                    )
                    return@safeLaunch
                }
            }
            _navigation.emit(BlockerPageNavigation.ShowToast("Saved"))
        }
    }

    /**
     * Save a number field value (from edit dialog).
     */
    fun saveNumberField(switchKey: String, value: Int) {
        safeLaunch {
            switchValues.storeSwitchStatus(switchKey, value)
            loadSettingItems()
            _navigation.emit(BlockerPageNavigation.ShowToast("Saved: $value"))
        }
    }

    /**
     * Persist the URI of a user-picked motivation image.
     *
     * The caller (UI layer) is responsible for taking a persistable read
     * permission on the URI BEFORE calling this — see
     * [BlockerPageHome.imagePickerLauncher]. We just store the string and
     * refresh the action labels.
     *
     * Validates:
     *   - URI is not null/blank
     *   - URI looks like a content:// or file:// scheme
     */
    fun saveBlockScreenImageUri(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            Timber.w("saveBlockScreenImageUri: uriString is null/blank — ignoring")
            safeLaunch {
                _navigation.emit(BlockerPageNavigation.ShowToastRes(
                    protect.yourself.R.string.block_screen_image_pick_failed
                ))
            }
            return
        }
        // Basic scheme validation. We accept content://, file://, and
        // android.resource:// (the latter is unlikely but harmless).
        if (!uriString.startsWith("content:") &&
            !uriString.startsWith("file:") &&
            !uriString.startsWith("android.resource:")) {
            Timber.w("saveBlockScreenImageUri: unsupported scheme for %s", uriString)
            safeLaunch {
                _navigation.emit(BlockerPageNavigation.ShowToastRes(
                    protect.yourself.R.string.block_screen_image_pick_failed
                ))
            }
            return
        }
        safeLaunch {
            switchValues.storeSwitchStatus(
                SwitchIdentifier.BLOCK_SCREEN_STORE_IMAGE_PATH,
                uriString
            )
            loadSettingItems()
            _navigation.emit(BlockerPageNavigation.ShowToastRes(
                protect.yourself.R.string.block_screen_image_picked_toast
            ))
            Timber.i("Block screen motivation image saved: %s", uriString)
        }
    }

    /**
     * Remove the persisted motivation image URI. The UI layer is responsible
     * for releasing the persistable URI permission if one was taken.
     */
    fun clearBlockScreenImage() {
        safeLaunch {
            switchValues.clearBlockScreenStoreImagePath()
            loadSettingItems()
            _navigation.emit(BlockerPageNavigation.ShowToastRes(
                protect.yourself.R.string.block_screen_image_cleared_toast
            ))
            Timber.i("Block screen motivation image cleared")
        }
    }

    /**
     * Reset the custom block screen message back to the localized default.
     */
    fun clearBlockScreenMessage() {
        safeLaunch {
            switchValues.clearBlockScreenCustomMessage()
            loadSettingItems()
            _navigation.emit(BlockerPageNavigation.ShowToastRes(
                protect.yourself.R.string.block_screen_message_cleared_toast
            ))
            Timber.i("Block screen custom message cleared")
        }
    }

    /**
     * Save the custom block screen message with length validation.
     * Empty / blank values are treated as "reset to default" — we clear
     * the stored message instead of saving an empty string.
     */
    fun saveBlockScreenMessage(value: String) {
        val trimmed = value.trim()
        if (trimmed.length > MAX_BLOCK_SCREEN_MESSAGE_CHARS) {
            Timber.w("saveBlockScreenMessage: value too long (%d chars)", trimmed.length)
            safeLaunch {
                _navigation.emit(BlockerPageNavigation.ShowToastRes(
                    protect.yourself.R.string.block_screen_message_too_long_toast,
                    listOf(MAX_BLOCK_SCREEN_MESSAGE_CHARS)
                ))
            }
            return
        }
        safeLaunch {
            if (trimmed.isEmpty()) {
                switchValues.clearBlockScreenCustomMessage()
            } else {
                switchValues.storeSwitchStatus(
                    SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE,
                    trimmed
                )
                switchValues.storeSwitchStatus(
                    SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE_SET,
                    true
                )
            }
            loadSettingItems()
            _navigation.emit(BlockerPageNavigation.ShowToastRes(
                protect.yourself.R.string.block_screen_message_saved_toast
            ))
            Timber.i("Block screen custom message saved (length=%d)", trimmed.length)
        }
    }

    /**
     * Emit a navigation event that asks the UI to launch PornBlockActivity
     * as a preview. The UI layer has the Context needed to startActivity().
     */
    fun previewBlockScreen() {
        safeLaunch {
            _navigation.emit(BlockerPageNavigation.PreviewBlockScreen)
            _navigation.emit(BlockerPageNavigation.ShowToastRes(
                protect.yourself.R.string.block_screen_preview_toast
            ))
        }
    }

    fun onActionClick(item: SettingPageItemModel) {
        // AUDIT FIX: if the item is disabled (prerequisite not met), show a toast
        if (item.isDisabled) {
            safeLaunch {
                val msg = item.dependencyMessage ?: "This setting requires a prerequisite to be enabled first."
                _navigation.emit(BlockerPageNavigation.ShowToast(msg))
                if (protect.yourself.BuildConfig.DEBUG) {
                    Timber.w("DEBUG onActionClick: rejected action on disabled item '${item.title}' — $msg")
                }
            }
            return
        }
        Timber.d("Action clicked: ${item.identifier}")
        safeLaunch {
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
                SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP -> BlockerPageNavigation.OpenSelectAppPage("Blocklist Whitelist Detected Apps", SelectedAppListIdentifier.BLOCK_WHITELIST_DETECTED_APPS)
                SettingPageItemIdentifiers.UNIFIED_BLOCKING_MANAGEMENT -> BlockerPageNavigation.OpenUnifiedBlocking


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
                    val app = getApplication<Application>()
                    BlockerPageNavigation.EditTextField(
                        app.getString(protect.yourself.R.string.block_screen_message_dialog_title),
                        current,
                        app.getString(protect.yourself.R.string.block_screen_message_dialog_hint),
                        SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE
                    )
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
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKLIST_APPS, "Blocklist apps", info = "Apps that get blocked on launch", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SAFE_SEARCH, "SafeSearch enforcement", info = "Enforce SafeSearch on Google, Bing, YouTube, DuckDuckGo, Yahoo, and Yandex. VPN adds DNS-level enforcement.", switchKey = SwitchIdentifier.SAFE_SEARCH_SWITCH))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_UNINSTALL_PROTECTION, "Uninstall Protection", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.PREVENT_UNINSTALL_SETTINGS, "Prevent uninstall", info = "Block attempts to uninstall (requires Device Admin)", switchKey = SwitchIdentifier.PREVENT_UNINSTALL_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_PHONE_REBOOT, "Block phone reboot", info = "Restart blocking automatically after reboot", switchKey = SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.UNIFIED_BLOCKING_MANAGEMENT, "Unified Blocking Management", info = "Manage blocklist, whitelist, setting titles, packages and intents in one place.", actionLabel = "Manage"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_ADVANCE_FEATURE, "Advanced feature", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_UNSUPPORTED_BROWSERS, "Block unsupported browsers", info = "Block any browser that isn't in your supported list or whitelist", switchKey = SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER, "Whitelist unsupported browsers", info = "Allow specific browsers to bypass the unsupported-browser block", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN, "VPN (DNS blocking)", info = "Block adult content at network level. Tap Manage to choose a filtering mode.", switchKey = SwitchIdentifier.VPN_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_MANAGE, "VPN mode", info = "Choose Balanced, Strict, or Custom DNS provider.", actionLabel = "Balanced"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.WHITELIST_VPN_APPS, "Whitelist VPN apps", info = "Apps that bypass VPN", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE, "VPN notification message", info = "Custom message for VPN notification", actionLabel = "Default"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_NOTIFICATION_HIDE, "Hide VPN notification content", switchKey = SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS, "Block new install apps", info = "Auto-block newly installed apps", switchKey = SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS, "Block in-app browsers", info = "Block in-app browsers inside other apps", switchKey = SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH))
        add(SettingPageItemModel(
            SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE,
            getApplication<Application>().getString(protect.yourself.R.string.block_screen_image_title),
            info = getApplication<Application>().getString(protect.yourself.R.string.block_screen_image_info),
            actionLabel = getApplication<Application>().getString(protect.yourself.R.string.block_screen_image_action_choose)
        ))
        add(SettingPageItemModel(
            SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE,
            getApplication<Application>().getString(protect.yourself.R.string.block_screen_message_title),
            info = getApplication<Application>().getString(protect.yourself.R.string.block_screen_message_info),
            actionLabel = getApplication<Application>().getString(protect.yourself.R.string.block_screen_message_action_default)
        ))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKED_SCREEN_COUNTDOWN, "Blocked screen countdown", info = "Require waiting N seconds before Close (3-300)", actionLabel = "Off"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP, "Custom redirect URL", info = "URL to open when user taps Close", actionLabel = "None"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP, "Blocklist whitelist detected apps", info = "Apps detected via accessibility events", actionLabel = "Manage"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_APP_LOCK, "App Lock", isSection = true))
        add(SettingPageItemModel(
            SettingPageItemIdentifiers.SET_APP_LOCK,
            getApplication<Application>().getString(protect.yourself.R.string.set_app_lock_card_title),
            info = getApplication<Application>().getString(protect.yourself.R.string.set_app_lock_card_info),
            switchKey = SwitchIdentifier.SET_APP_LOCK_SWITCH
        ))
        add(SettingPageItemModel(
            SettingPageItemIdentifiers.TOUCH_ID,
            getApplication<Application>().getString(protect.yourself.R.string.set_touch_id_card_title),
            info = getApplication<Application>().getString(protect.yourself.R.string.set_touch_id_card_info),
            switchKey = SwitchIdentifier.TOUCH_ID_SWITCH
        ))
        add(SettingPageItemModel(
            SettingPageItemIdentifiers.DISABLE_FORGOT_PASSWORD,
            getApplication<Application>().getString(protect.yourself.R.string.disable_forgot_password_option),
            info = getApplication<Application>().getString(protect.yourself.R.string.disable_forgot_password_option_info),
            switchKey = SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH
        ))

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
        safeLaunch {
            switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
            loadVpnManagementState()
            loadSettingItems()
        }
    }

    /**
     * Toggles the "hide VPN notification content" switch from the VPN
     * management page. VPN-08 fix: if the VPN is currently running, restart
     * it so the new notification text takes effect immediately (otherwise the
     * user would have to manually restart the VPN to see the change).
     */
    fun setVpnNotificationHidden(hidden: Boolean) {
        safeLaunch {
            switchValues.storeSwitchStatus(SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH, hidden)
            loadVpnManagementState()
            loadSettingItems()
            // VPN-08 fix: restart the VPN so the notification text updates.
            if (switchValues.isVpnSwitchOn()) {
                _navigation.emit(BlockerPageNavigation.RestartVpn)
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.vpn_notification_changes_applied_toast
                    )
                )
            }
        }
    }

    /**
     * Loads the dedicated VPN management page state: current VPN on/off flag,
     * current filtering mode, list of available custom DNS presets, and which
     * preset is currently selected.
     *
     * VPN-15 fix: do NOT reset isLoading=true if the state has already been
     * loaded once. This prevents a brief spinner when the user navigates away
     * from the VPN management page and back — the previous state stays visible
     * while the new data loads in the background.
     */
    fun loadVpnManagementState() {
        safeLaunch {
            val vpnOn = switchValues.isVpnSwitchOn()
            val mode = switchValues.getVpnConnectionType()
            val presets = db.vpnCustomDnsDao().getAll()
            val selectedPresetKey = db.vpnCustomDnsDao().getSelected()?.key
            val hideNotification = switchValues.isVpnNotificationHideSwitchOn()
            val notificationMessage = switchValues.getVpnNotificationCustomMessage() ?: ""
            // BUG-06 fix: include the live service state in the management
            // state so the UI can render the actual connection state.
            // Stored as String to avoid kapt stub generation issues.
            val liveServiceState = MyVpnService.observableVpnState.name

            _vpnManagementState.update { prev ->
                VpnManagementState(
                    isVpnEnabled = vpnOn,
                    currentMode = mode,
                    customDnsPresets = presets,
                    selectedCustomDnsKey = selectedPresetKey,
                    isNotificationHidden = hideNotification,
                    notificationMessage = notificationMessage,
                    // FIX 3.1: always set isLoading=false after a successful load.
                    // The previous logic (prev.isLoading && prev.customDnsPresets.isEmpty())
                    // kept the spinner on forever because on the first load,
                    // prev.customDnsPresets was empty → isLoading stayed true.
                    isLoading = false,
                    // BUG-06 fix: expose live service state.
                    serviceState = liveServiceState
                )
            }
        }
    }

    /**
     * Switches the VPN filtering mode. Persists the new mode, then if the VPN
     * is currently running, asks the UI to restart the service so the new DNS
     * takes effect immediately.
     *
     * VPN-06 fix: early-return if the mode is unchanged — no need to restart
     * the VPN or show a toast when the user taps the already-selected card.
     *
     * VPN-14 fix: use string resources for the toast instead of hardcoded
     * English, and pick the right string based on whether a restart will happen.
     */
    fun setVpnMode(mode: VpnConnectionTypeIdentifiers) {
        safeLaunch {
            // VPN-06 fix: no-op if the mode is unchanged.
            val currentMode = switchValues.getVpnConnectionType()
            if (currentMode == mode) {
                Timber.d("setVpnMode: mode unchanged ($mode) — no-op")
                return@safeLaunch
            }

            switchValues.storeVpnConnectionType(mode)

            // Refresh the VPN management state + the main settings list so the
            // UI reflects the new mode immediately.
            loadVpnManagementState()
            loadSettingItems()

            // Restart the VPN service if it is currently running so the new
            // DNS server takes effect immediately. The actual restart is done
            // by the UI layer (which has the Context) — see RestartVpn handler
            // in BlockerPageHome.kt.
            val willRestart = switchValues.isVpnSwitchOn()
            if (willRestart) {
                _navigation.emit(BlockerPageNavigation.RestartVpn)
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.vpn_mode_changed_toast,
                        listOf(vpnModeLabel(mode))
                    )
                )
            } else {
                // VPN-14 fix: use the no-restart variant of the toast.
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.vpn_mode_changed_no_restart_toast,
                        listOf(vpnModeLabel(mode))
                    )
                )
            }
        }
    }

    /**
     * Selects a custom DNS preset (used when the VPN is in CUSTOM mode).
     * Persists the selection and asks the UI to restart the VPN if it is
     * currently running.
     *
     * VPN-07 fix: only show the "Restarting VPN…" toast when a restart will
     * actually happen (VPN is ON AND in CUSTOM mode). Otherwise show a
     * simpler "updated" toast.
     *
     * VPN-14 fix: use string resources for the toast instead of hardcoded English.
     */
    fun selectCustomDnsPreset(presetKey: String) {
        safeLaunch {
            db.vpnCustomDnsDao().setSelected(presetKey)

            loadVpnManagementState()

            // VPN-07 fix: only restart if the VPN is ON AND in CUSTOM mode.
            val willRestart = switchValues.isVpnSwitchOn() &&
                switchValues.getVpnConnectionType() == VpnConnectionTypeIdentifiers.CUSTOM
            if (willRestart) {
                _navigation.emit(BlockerPageNavigation.RestartVpn)
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.vpn_custom_dns_changed_toast
                    )
                )
            } else {
                // BUG-10 fix: use specific, clearer messages instead of the
                // generic "Custom DNS provider updated." toast which implied
                // the change was active. The previous message confused users
                // who selected a preset while in NORMAL/POWERFUL mode or
                // while the VPN was OFF — they expected the preset to take
                // effect immediately, but it only applies when CUSTOM mode
                // is active AND the VPN is ON.
                val vpnOn = switchValues.isVpnSwitchOn()
                val inCustomMode = switchValues.getVpnConnectionType() == VpnConnectionTypeIdentifiers.CUSTOM
                val messageRes = when {
                    !vpnOn -> protect.yourself.R.string.vpn_custom_dns_updated_vpn_off
                    !inCustomMode -> protect.yourself.R.string.vpn_custom_dns_updated_not_custom
                    else -> protect.yourself.R.string.vpn_custom_dns_updated_toast
                }
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(messageRes)
                )
            }
        }
    }

    /**
     * Returns the user-facing label for a VPN mode.
     * Mirrors the labels used by [MyVpnService] in the foreground notification.
     *
     * BUG-19 fix: use string resources instead of hardcoded English labels.
     * The previous implementation used "Balanced" / "Strict" / "Custom DNS"
     * directly, which meant the VPN_MANAGE setting item's action label was
     * always in English even on non-English devices. The VpnManagementPage
     * already used the localized string resources — this fix makes the main
     * settings page consistent.
     */
    private fun vpnModeLabel(mode: VpnConnectionTypeIdentifiers): String {
        val app = getApplication<Application>()
        return when (mode) {
            VpnConnectionTypeIdentifiers.NORMAL ->
                app.getString(protect.yourself.R.string.vpn_mode_balanced_label)
            VpnConnectionTypeIdentifiers.POWERFUL ->
                app.getString(protect.yourself.R.string.vpn_mode_strict_label)
            VpnConnectionTypeIdentifiers.CUSTOM ->
                app.getString(protect.yourself.R.string.vpn_mode_custom_label)
            VpnConnectionTypeIdentifiers.OFF ->
                app.getString(protect.yourself.R.string.vpn_mode_balanced_label)
        }
    }

    // ===== Custom DNS preset add / delete (NopoX parity gap fix) =====

    /**
     * Adds a user-defined custom DNS preset to the vpn_custom_dns table.
     *
     * Validates that both DNS IPs are well-formed (IPv4 or IPv6) and that the
     * name is non-blank. The new preset is NOT auto-selected — the user must
     * tap it to make it active. This avoids surprising the user by switching
     * their active DNS provider without explicit action.
     *
     * Returns true on success, false on validation failure. The UI shows an
     * error toast on failure (handled by the caller via the return value).
     */
    fun addCustomDnsPreset(name: String, firstDns: String, secondDns: String): Boolean {
        // Validate inputs synchronously so we can return a Boolean to the UI.
        val utils = protect.yourself.features.blockerPage.utils.BlockerPageUtils.getInstance()
        val trimmedName = name.trim()
        val trimmedFirst = firstDns.trim()
        val trimmedSecond = secondDns.trim()
        if (trimmedName.isBlank()) {
            safeLaunch {
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.dns_name_empty_error
                    )
                )
            }
            return false
        }
        if (!utils.isValidDNS(trimmedFirst)) {
            safeLaunch {
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.dns_1_empty_error
                    )
                )
            }
            return false
        }
        if (!utils.isValidDNS(trimmedSecond)) {
            safeLaunch {
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.dns_2_empty_error
                    )
                )
            }
            return false
        }
        // BUG-15 fix: reject DNS 1 == DNS 2 (no failover benefit, confusing UX).
        if (trimmedFirst == trimmedSecond) {
            safeLaunch {
                _navigation.emit(
                    BlockerPageNavigation.ShowToast("DNS 1 and DNS 2 must be different")
                )
            }
            return false
        }

        // BUG-09 fix: async checks for duplicate name / duplicate DNS pair.
        // These require a DB read, so they run inside safeLaunch. If a
        // duplicate is found, we emit a toast and return without inserting.
        // Note: we still return true here because the synchronous validation
        // passed — the dialog will be dismissed by the VpnCustomDnsPresetAdded
        // event ONLY if the insert actually succeeds. If we emit a "duplicate"
        // toast instead, the dialog stays open (no PresetAdded event fires).
        safeLaunch {
            // BUG-09: check for duplicate name (case-insensitive)
            val existing = db.vpnCustomDnsDao().getAll()
            if (existing.any { it.displayName.equals(trimmedName, ignoreCase = true) }) {
                _navigation.emit(
                    BlockerPageNavigation.ShowToast("A preset with this name already exists")
                )
                return@safeLaunch
            }
            // BUG-09: check for duplicate DNS pair
            if (existing.any { it.firstDns == trimmedFirst && it.secondDns == trimmedSecond }) {
                _navigation.emit(
                    BlockerPageNavigation.ShowToast("A preset with these DNS servers already exists")
                )
                return@safeLaunch
            }

            // Generate a unique key — use timestamp to avoid collisions with
            // the default preset keys ("preset_cloudflare_family" etc.).
            // FIX 3.3: use UUID instead of System.currentTimeMillis() to
            // prevent key collisions when the user double-taps Save or the
            // system clock is coarse. OnConflictStrategy.REPLACE would
            // silently overwrite the first preset with the second.
            val key = "user_${java.util.UUID.randomUUID()}"
            val preset = protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel(
                key = key,
                displayName = trimmedName,
                firstDns = trimmedFirst,
                secondDns = trimmedSecond,
                isSelected = false
            )
            // BUG-05 fix: perform the DB write BEFORE emitting the success
            // toast / dismiss event. If upsert() throws (e.g. disk full, DB
            // locked, constraint violation), the safeLaunch catch block will
            // emit "Operation failed: <error>" and the PresetAdded event
            // will NOT fire — the UI keeps the dialog open so the user can
            // retry. Previously the dialog dismissed synchronously based on
            // this function's return value, leaving the user with no clear
            // error context if the DB write failed.
            db.vpnCustomDnsDao().upsert(preset)
            // Mark that the user has edited the custom DNS list — this flag
            // was previously unused; we set it now so a future seed-on-upgrade
            // path could decide not to clobber user presets.
            switchValues.storeSwitchStatus(
                SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET, true
            )
            loadVpnManagementState()
            // BUG-05 fix: emit the PresetAdded event AFTER the DB write
            // succeeds. The UI collects this and dismisses the dialog.
            _navigation.emit(BlockerPageNavigation.VpnCustomDnsPresetAdded)
            _navigation.emit(
                BlockerPageNavigation.ShowToastRes(
                    protect.yourself.R.string.vpn_custom_dns_added_toast
                )
            )
        }
        return true
    }

    /**
     * Deletes a user-defined custom DNS preset. If the deleted preset was the
     * selected one, falls back to the default Cloudflare Family preset (so
     * CUSTOM mode always has a valid upstream).
     *
     * Default presets (key starts with "preset_") cannot be deleted — the UI
     * should hide the delete affordance for them, but we also guard here.
     */
    fun deleteCustomDnsPreset(presetKey: String) {
        safeLaunch {
            if (presetKey.startsWith("preset_")) {
                _navigation.emit(
                    BlockerPageNavigation.ShowToastRes(
                        protect.yourself.R.string.vpn_custom_dns_cannot_delete_default
                    )
                )
                return@safeLaunch
            }
            val selected = db.vpnCustomDnsDao().getSelected()
            db.vpnCustomDnsDao().deleteByKey(presetKey)
            // If we just deleted the selected preset, fall back to Cloudflare.
            if (selected?.key == presetKey) {
                db.vpnCustomDnsDao().setSelected(
                    protect.yourself.features.blockerPage.utils.DefaultDnsPresets.CLOUDFLARE_FAMILY.key
                )
                if (switchValues.isVpnSwitchOn() &&
                    switchValues.getVpnConnectionType() == VpnConnectionTypeIdentifiers.CUSTOM
                ) {
                    _navigation.emit(BlockerPageNavigation.RestartVpn)
                }
            }
            loadVpnManagementState()
            _navigation.emit(
                BlockerPageNavigation.ShowToastRes(
                    protect.yourself.R.string.vpn_custom_dns_deleted_toast
                )
            )
        }
    }

    companion object {
        /**
         * Maximum character count for the custom block screen message.
         * Longer messages would push the close button off-screen on smaller
         * devices. The edit dialog enforces this and shows a live counter.
         */
        const val MAX_BLOCK_SCREEN_MESSAGE_CHARS = 200

        fun factory(application: Application, db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BlockerPageViewModel(application, db) as T
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
 *
 * BUG-06 fix: [serviceState] is the live service state from
 * [MyVpnService.observableVpnState], distinct from [isVpnEnabled] (which
 * reflects the DB-persisted VPN_SWITCH user intent). The UI can use both
 * to display the actual state. Stored as a String to avoid kapt stub
 * generation issues with cross-class enum references.
 */
data class VpnManagementState(
    val isVpnEnabled: Boolean = false,
    val currentMode: VpnConnectionTypeIdentifiers = VpnConnectionTypeIdentifiers.NORMAL,
    val customDnsPresets: List<VpnCustomDnsItemModel> = emptyList(),
    val selectedCustomDnsKey: String? = null,
    val isNotificationHidden: Boolean = false,
    val notificationMessage: String = "",
    val isLoading: Boolean = true,
    val serviceState: String = "IDLE"
)
