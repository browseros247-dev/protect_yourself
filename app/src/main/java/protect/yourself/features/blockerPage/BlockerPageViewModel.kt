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
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.data.SettingPageItemModel
import protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.service.MyVpnService
import timber.log.Timber

/**
 * Navigation events emitted by BlockerPageViewModel.
 * BlockerPageHome collects these and navigates accordingly.
 */
sealed class BlockerPageNavigation {
    data class OpenSelectAppPage(val title: String, val identifier: protect.yourself.database.selectedApps.SelectedAppListIdentifier) : BlockerPageNavigation()
    data class OpenUrl(val url: String) : BlockerPageNavigation()
    data class ShowToast(val message: String) : BlockerPageNavigation()
    data object OpenAccessibilitySettings : BlockerPageNavigation()
    data object OpenOverlaySettings : BlockerPageNavigation()
    data object OpenAppLockSetup : BlockerPageNavigation()
    data object OpenKeywordManager : BlockerPageNavigation()
    data object OpenStopMe : BlockerPageNavigation()
    data object OpenFaq : BlockerPageNavigation()
    data object OpenRequestHistory : BlockerPageNavigation()
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

    private val _navigation = MutableSharedFlow<BlockerPageNavigation>(extraBufferCapacity = 5)
    val navigation: SharedFlow<BlockerPageNavigation> = _navigation.asSharedFlow()

    init {
        loadSettingItems()
    }

    private fun loadSettingItems() {
        viewModelScope.launch {
            val items = buildSettingItems()

            // Load current switch values
            val itemsWithValues = items.map { item ->
                if (item.switchKey != null) {
                    val value = loadSwitchValue(item.switchKey)
                    item.copy(switchValue = value)
                } else item
            }

            _state.update { it.copy(settingItems = itemsWithValues, isLoading = false) }
            Timber.i("BlockerPage loaded ${itemsWithValues.size} setting items")
        }
    }

    private suspend fun loadSwitchValue(key: String): Boolean = when (key) {
        SwitchIdentifier.PORN_BLOCKER_SWITCH -> switchValues.isPornBlockerSwitchOn()
        SwitchIdentifier.BLOCK_ALL_WEBSITE_SWITCH -> switchValues.isBlockAllWebsiteSwitchOn()
        SwitchIdentifier.SAFE_SEARCH_SWITCH -> switchValues.isSafeSearchSwitchOn()
        SwitchIdentifier.BLOCK_IMAGE_VIDEO_SWITCH -> switchValues.isBlockImageVideoSwitchOn()
        SwitchIdentifier.MAKE_ANY_BROWSER_SUPPORTED_SWITCH -> switchValues.isMakeAnyBrowserSupportedSwitchOn()
        SwitchIdentifier.BLOCK_SNAPCHAT_STORIES_SWITCH -> switchValues.isBlockSnapchatStoriesSwitchOn()
        SwitchIdentifier.BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH -> switchValues.isBlockSnapchatSpotlightSwitchOn()
        SwitchIdentifier.BLOCK_INSTA_REELS_SWITCH -> switchValues.isBlockInstaReelsSwitchOn()
        SwitchIdentifier.BLOCK_INSTA_SEARCH_SWITCH -> switchValues.isBlockInstaSearchSwitchOn()
        SwitchIdentifier.BLOCK_WHATSAPP_STATUS_SWITCH -> switchValues.isBlockWhatsappStatusSwitchOn()
        SwitchIdentifier.BLOCK_YT_SHORTS_SWITCH -> switchValues.isBlockYtShortsSwitchOn()
        SwitchIdentifier.BLOCK_YT_SEARCH_SWITCH -> switchValues.isBlockYtSearchSwitchOn()
        SwitchIdentifier.BLOCK_TELEGRAM_SEARCH_SWITCH -> switchValues.isBlockTelegramSearchSwitchOn()
        SwitchIdentifier.PREVENT_UNINSTALL_SWITCH -> switchValues.isPreventUninstallSwitchOn()
        SwitchIdentifier.BLOCK_NOTIFICATION_DRAWER_SWITCH -> switchValues.isBlockNotificationDrawerSwitchOn()
        SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH -> switchValues.isBlockPhoneRebootSwitchOn()
        SwitchIdentifier.BLOCK_RECENT_APPS_SWITCH -> switchValues.isBlockRecentAppsSwitchOn()
        SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH -> switchValues.isBlockSettingPageByTitleSwitchOn()
        SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH -> switchValues.isBlockUnsupportedBrowsersSwitchOn()
        SwitchIdentifier.VPN_SWITCH -> switchValues.isVpnSwitchOn()
        SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH -> switchValues.isVpnNotificationHideSwitchOn()
        SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH -> switchValues.isBlockNewInstallAppsSwitchOn()
        SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH -> switchValues.isBlockInAppBrowsersSwitchOn()
        SwitchIdentifier.SET_APP_LOCK_SWITCH -> switchValues.isAppLockSwitchOn()
        SwitchIdentifier.TOUCH_ID_SWITCH -> switchValues.isTouchIdSwitchOn()
        SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH -> switchValues.isDisableForgotPasswordSwitchOn()
        SwitchIdentifier.DAILY_REPORT_SWITCH -> switchValues.isDailyReportSwitchOn()
        SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET -> switchValues.isLongSentenceMessageSet()
        SwitchIdentifier.TIME_DELAY_DURATION_SET -> switchValues.isTimeDelayDurationSet()
        SwitchIdentifier.REAL_FRIEND_VISIBLE -> switchValues.isRealFriendVisible()
        else -> false
    }

    fun toggleSwitch(item: SettingPageItemModel) {
        val switchKey = item.switchKey ?: return
        val newValue = !item.switchValue

        viewModelScope.launch {
            // Special handling for VPN — requires VpnService.prepare()
            if (switchKey == SwitchIdentifier.VPN_SWITCH && newValue) {
                _navigation.emit(BlockerPageNavigation.ShowToast("Please grant VPN permission in system settings"))
                // The actual VPN start happens via VpnService.prepare() in the UI layer
                // For now, just persist the switch — the UI layer will handle VPN permission
                switchValues.storeSwitchStatus(switchKey, newValue)
            } else {
                switchValues.storeSwitchStatus(switchKey, newValue)
            }

            _state.update { state ->
                state.copy(
                    settingItems = state.settingItems.map { itItem ->
                        if (itItem.switchKey == switchKey) itItem.copy(switchValue = newValue)
                        else itItem
                    }
                )
            }

            // Refresh accessibility service blocking config
            MyAccessibilityService.instance?.refreshBlockingConfig()

            Timber.i("Switch $switchKey toggled to $newValue")
        }
    }

    fun onActionClick(item: SettingPageItemModel) {
        Timber.d("Action clicked: ${item.identifier}")
        viewModelScope.launch {
            val nav = when (item.identifier) {
                SettingPageItemIdentifiers.ACCESSIBILITY_PERMISSION -> BlockerPageNavigation.OpenAccessibilitySettings
                SettingPageItemIdentifiers.DISPLAY_POPUP_WINDOW_PERMISSION -> BlockerPageNavigation.OpenOverlaySettings
                SettingPageItemIdentifiers.BLOCKLIST_APPS -> BlockerPageNavigation.OpenSelectAppPage(
                    "Blocklist Apps", protect.yourself.database.selectedApps.SelectedAppListIdentifier.BLOCK_APPS
                )
                SettingPageItemIdentifiers.SUPPORTED_BROWSERS -> BlockerPageNavigation.OpenSelectAppPage(
                    "Supported Browsers", protect.yourself.database.selectedApps.SelectedAppListIdentifier.SUPPORTED_BROWSER_APPS
                )
                SettingPageItemIdentifiers.SUPPORTED_SOCIAL_MEDIA -> BlockerPageNavigation.OpenSelectAppPage(
                    "Supported Social Media", protect.yourself.database.selectedApps.SelectedAppListIdentifier.SUPPORTED_SOCIAL_MEDIA_APPS
                )
                SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER -> BlockerPageNavigation.OpenSelectAppPage(
                    "Whitelist Browsers", protect.yourself.database.selectedApps.SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER
                )
                SettingPageItemIdentifiers.WHITELIST_VPN_APPS -> BlockerPageNavigation.OpenSelectAppPage(
                    "VPN Whitelist Apps", protect.yourself.database.selectedApps.SelectedAppListIdentifier.VPN_WHITELIST_APPS
                )
                SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS -> BlockerPageNavigation.OpenSelectAppPage(
                    "Block In-App Browsers", protect.yourself.database.selectedApps.SelectedAppListIdentifier.BLOCK_IN_APP_BROWSER_APPS
                )
                SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS -> BlockerPageNavigation.OpenSelectAppPage(
                    "Block New Install Apps", protect.yourself.database.selectedApps.SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS
                )
                SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE_APPS -> BlockerPageNavigation.OpenSelectAppPage(
                    "Setting Page Blocklist", protect.yourself.database.selectedApps.SelectedAppListIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_APPS
                )
                SettingPageItemIdentifiers.BLOCKER_CUSTOM_KEYWORD_WEBSITE -> BlockerPageNavigation.OpenKeywordManager
                SettingPageItemIdentifiers.SET_APP_LOCK -> BlockerPageNavigation.OpenAppLockSetup
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
        add(SettingPageItemModel(SettingPageItemIdentifiers.LONG_SENTENCE, "Long Sentence", info = "Type a long message to disable a switch", switchKey = SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET))
        add(SettingPageItemModel(SettingPageItemIdentifiers.LONG_SENTENCE_CUSTOM_MESSAGE, "Long Sentence custom message", info = "Edit the message users must type", actionLabel = "Edit"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.TIME_DELAY, "Time Delay", info = "Wait before disabling a switch", switchKey = SwitchIdentifier.TIME_DELAY_DURATION_SET))
        add(SettingPageItemModel(SettingPageItemIdentifiers.TIME_DELAY_CUSTOM_DURATION, "Time Delay duration", info = "Set delay in seconds", actionLabel = "30s"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.REAL_FRIEND, "Real Friend", info = "Require friend's approval to disable switches", switchKey = SwitchIdentifier.REAL_FRIEND_VISIBLE))
        add(SettingPageItemModel(SettingPageItemIdentifiers.DAILY_REPORT, "Daily Report", info = "Daily summary notification", switchKey = SwitchIdentifier.DAILY_REPORT_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SUGGEST_PROTECTIVE_MODE, "Suggest protective mode", info = "Suggest new protective modes", actionLabel = "Email"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.REQUEST_HISTORY, "Request history", info = "View pending + past protective mode requests", actionLabel = "View"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_CONTENT_BLOCKING, "Content Blocking", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SUPPORTED_BROWSERS, "Supported browsers", info = "Browsers where app can block content", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SUPPORTED_SOCIAL_MEDIA, "Supported social media", info = "Social apps where app can block searches", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.PORN_BLOCKER, "Porn blocker", info = "Block content based on keyword list", switchKey = SwitchIdentifier.PORN_BLOCKER_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKER_CUSTOM_KEYWORD_WEBSITE, "Blocklist keywords", info = "Add/remove keywords that trigger block", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKLIST_APPS, "Blocklist apps", info = "Apps that get blocked on launch", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_ALL_WEBSITE, "Block all websites", info = "Block every URL (whitelist overrides)", switchKey = SwitchIdentifier.BLOCK_ALL_WEBSITE_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SAFE_SEARCH, "SafeSearch enforcement", info = "Force SafeSearch on Google/Bing", switchKey = SwitchIdentifier.SAFE_SEARCH_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_IMAGE_VIDEO_SEARCH, "Block image and video search", info = "Block image/video search results", switchKey = SwitchIdentifier.BLOCK_IMAGE_VIDEO_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.MAKE_ANY_BROWSER_SUPPORTED, "Make any browser supported", info = "Add any browser to supported list", switchKey = SwitchIdentifier.MAKE_ANY_BROWSER_SUPPORTED_SWITCH))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_INSTA_YT_BLOCKING, "Social Media Blocking", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_SNAPCHAT_STORIES, "Block Snapchat Stories", switchKey = SwitchIdentifier.BLOCK_SNAPCHAT_STORIES_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_SNAPCHAT_SPOTLIGHT, "Block Snapchat Spotlight", switchKey = SwitchIdentifier.BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_INSTA_REELS, "Block Instagram Reels", switchKey = SwitchIdentifier.BLOCK_INSTA_REELS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_INSTA_SEARCH, "Block Instagram Search", switchKey = SwitchIdentifier.BLOCK_INSTA_SEARCH_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_WHATSAPP_STATUS, "Block WhatsApp Status", switchKey = SwitchIdentifier.BLOCK_WHATSAPP_STATUS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_YT_SHORTS, "Block YouTube Shorts", switchKey = SwitchIdentifier.BLOCK_YT_SHORTS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_YT_SEARCH, "Block YouTube Search", switchKey = SwitchIdentifier.BLOCK_YT_SEARCH_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_TELEGRAM_SEARCH, "Block Telegram Search", switchKey = SwitchIdentifier.BLOCK_TELEGRAM_SEARCH_SWITCH))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_UNINSTALL_PROTECTION, "Uninstall Protection", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.PREVENT_UNINSTALL_SETTINGS, "Prevent uninstall", info = "Block attempts to uninstall", switchKey = SwitchIdentifier.PREVENT_UNINSTALL_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_NOTIFICATION_DRAWER, "Block notification drawer", switchKey = SwitchIdentifier.BLOCK_NOTIFICATION_DRAWER_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_PHONE_REBOOT, "Block phone reboot", switchKey = SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_RECENT_APPS, "Block Recent Apps screen", switchKey = SwitchIdentifier.BLOCK_RECENT_APPS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE, "Title-based block setting", info = "Block specific settings pages by title", switchKey = SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE_APPS, "Setting page blocklist", info = "Manage blocked settings pages", actionLabel = "Manage"))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_ADVANCE_FEATURE, "Advanced feature", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_UNSUPPORTED_BROWSERS, "Block unsupported browsers", info = "Block all browsers except whitelisted", switchKey = SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER, "Whitelist unsupported browsers", info = "Manage browser whitelist", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN, "VPN (DNS blocking)", info = "Block adult content at network level", switchKey = SwitchIdentifier.VPN_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.WHITELIST_VPN_APPS, "Whitelist VPN apps", info = "Apps that bypass VPN", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE, "VPN notification message", info = "Custom message for VPN notification", actionLabel = "Edit"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.VPN_NOTIFICATION_HIDE, "Hide VPN notification content", switchKey = SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS, "Block new install apps", info = "Auto-block newly installed apps", switchKey = SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS, "Block in-app browsers", info = "Block in-app browsers inside other apps", switchKey = SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE, "Blocked screen image for motivation", info = "Custom image shown on block screen", actionLabel = "Choose"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE, "Blocked screen message", info = "Custom message shown on block screen", actionLabel = "Edit"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCKED_SCREEN_COUNTDOWN, "Blocked screen countdown", info = "Require waiting N seconds before Close (3-300)", actionLabel = "0s"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP, "Custom redirect URL", info = "URL to open when user taps Close", actionLabel = "Edit"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP, "Blocklist whitelist detected apps", info = "Apps detected via accessibility events", actionLabel = "Manage"))
        add(SettingPageItemModel(SettingPageItemIdentifiers.SET_APP_LOCK, "App lock", info = "Require PIN/password/pattern to open app", switchKey = SwitchIdentifier.SET_APP_LOCK_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.TOUCH_ID, "Touch ID (biometric)", info = "Use fingerprint/face to unlock", switchKey = SwitchIdentifier.TOUCH_ID_SWITCH))
        add(SettingPageItemModel(SettingPageItemIdentifiers.DISABLE_FORGOT_PASSWORD, "Disable forgot password", info = "Hide forgot password option", switchKey = SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH))

        add(SettingPageItemModel(SettingPageItemIdentifiers.SECTION_FAQ, "Keep Protect Yourself Live", isSection = true))
        add(SettingPageItemModel(SettingPageItemIdentifiers.KEEP_NOPOX_LIVE, "How to keep app running", info = "Battery + performance tips", actionLabel = "View"))
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
