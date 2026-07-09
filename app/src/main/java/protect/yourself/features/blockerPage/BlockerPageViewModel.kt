package protect.yourself.features.blockerPage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.data.SettingPageItemModel
import protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers
import timber.log.Timber

/**
 * BlockerPageViewModel — manages state for the Blocker (Home) tab.
 *
 * Replaces original Mavericks-based BlockerPageViewModel with ViewModel + StateFlow.
 *
 * Responsibilities:
 *  - Build list of SettingPageItemModel for the UI
 *  - Toggle switch values + persist to Room
 *  - Observe switch changes + update UI
 *  - Handle protective mode checks (Long Sentence, Time Delay, Real Friend)
 */
class BlockerPageViewModel(
    private val db: AppDatabase
) : ViewModel() {

    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    private val _state = MutableStateFlow(BlockerPageState())
    val state: StateFlow<BlockerPageState> = _state.asStateFlow()

    init {
        loadSettingItems()
    }

    /**
     * Build the list of setting items for the Blocker page.
     *
     * Items appear in the original order (matches SettingPageItemIdentifiers enum order).
     */
    private fun loadSettingItems() {
        viewModelScope.launch {
            val items = buildList {
                // ===== SECTION_ALERT =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SECTION_ALERT,
                    title = "Alert",
                    isSection = true
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_SCREEN_COUNT,
                    title = "Block screens",
                    info = "Total times content has been blocked",
                    actionLabel = "0"  // TODO: load from DB
                ))

                // ===== Permissions =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.ACCESSIBILITY_PERMISSION,
                    title = "Accessibility permission",
                    info = "Required to block content",
                    actionLabel = "Grant"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.DISPLAY_POPUP_WINDOW_PERMISSION,
                    title = "Display pop-up window permission",
                    info = "Required to show block screen",
                    actionLabel = "Grant"
                ))

                // ===== SECTION_ACCOUNTABILITY_PARTNER =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SECTION_ACCOUNTABILITY_PARTNER,
                    title = "Protective Mode",
                    isSection = true
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.LONG_SENTENCE,
                    title = "Long Sentence",
                    info = "Type a long message to disable a switch",
                    switchKey = SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.LONG_SENTENCE_CUSTOM_MESSAGE,
                    title = "Long Sentence custom message",
                    info = "Edit the message users must type",
                    actionLabel = "Edit"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.TIME_DELAY,
                    title = "Time Delay",
                    info = "Wait before disabling a switch",
                    switchKey = SwitchIdentifier.TIME_DELAY_DURATION_SET
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.TIME_DELAY_CUSTOM_DURATION,
                    title = "Time Delay duration",
                    info = "Set delay in seconds",
                    actionLabel = "30s"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.REAL_FRIEND,
                    title = "Real Friend",
                    info = "Require friend's approval to disable switches",
                    switchKey = SwitchIdentifier.REAL_FRIEND_VISIBLE
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.DAILY_REPORT,
                    title = "Daily Report",
                    info = "Daily summary notification",
                    switchKey = SwitchIdentifier.DAILY_REPORT_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SUGGEST_PROTECTIVE_MODE,
                    title = "Suggest protective mode",
                    info = "Suggest new protective modes",
                    actionLabel = "Email"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.REQUEST_HISTORY,
                    title = "Request history",
                    info = "View pending + past protective mode requests",
                    actionLabel = "View"
                ))

                // ===== SECTION_CONTENT_BLOCKING =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SECTION_CONTENT_BLOCKING,
                    title = "Content Blocking",
                    isSection = true
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SUPPORTED_BROWSERS,
                    title = "Supported browsers",
                    info = "Browsers where NopoX can block content",
                    actionLabel = "Manage"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SUPPORTED_SOCIAL_MEDIA,
                    title = "Supported social media",
                    info = "Social apps where NopoX can block searches",
                    actionLabel = "Manage"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.PORN_BLOCKER,
                    title = "Porn blocker",
                    info = "Block content based on keyword list",
                    switchKey = SwitchIdentifier.PORN_BLOCKER_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCKER_CUSTOM_KEYWORD_WEBSITE,
                    title = "Blocklist keywords",
                    info = "Add/remove keywords that trigger block",
                    actionLabel = "Manage"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCKLIST_APPS,
                    title = "Blocklist apps",
                    info = "Apps that get blocked on launch",
                    actionLabel = "Manage"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_ALL_WEBSITE,
                    title = "Block all websites",
                    info = "Block every URL (whitelist overrides)",
                    switchKey = SwitchIdentifier.BLOCK_ALL_WEBSITE_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SAFE_SEARCH,
                    title = "SafeSearch enforcement",
                    info = "Force SafeSearch on Google/Bing",
                    switchKey = SwitchIdentifier.SAFE_SEARCH_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_IMAGE_VIDEO_SEARCH,
                    title = "Block image and video search",
                    info = "Block image/video search results",
                    switchKey = SwitchIdentifier.BLOCK_IMAGE_VIDEO_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.MAKE_ANY_BROWSER_SUPPORTED,
                    title = "Make any browser supported",
                    info = "Add any browser to supported list",
                    switchKey = SwitchIdentifier.MAKE_ANY_BROWSER_SUPPORTED_SWITCH
                ))

                // ===== SECTION_INSTA_YT_BLOCKING =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SECTION_INSTA_YT_BLOCKING,
                    title = "Social Media Blocking",
                    isSection = true
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_SNAPCHAT_STORIES,
                    title = "Block Snapchat Stories",
                    switchKey = SwitchIdentifier.BLOCK_SNAPCHAT_STORIES_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_SNAPCHAT_SPOTLIGHT,
                    title = "Block Snapchat Spotlight",
                    switchKey = SwitchIdentifier.BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_INSTA_REELS,
                    title = "Block Instagram Reels",
                    switchKey = SwitchIdentifier.BLOCK_INSTA_REELS_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_INSTA_SEARCH,
                    title = "Block Instagram Search",
                    switchKey = SwitchIdentifier.BLOCK_INSTA_SEARCH_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_WHATSAPP_STATUS,
                    title = "Block WhatsApp Status",
                    switchKey = SwitchIdentifier.BLOCK_WHATSAPP_STATUS_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_YT_SHORTS,
                    title = "Block YouTube Shorts",
                    switchKey = SwitchIdentifier.BLOCK_YT_SHORTS_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_YT_SEARCH,
                    title = "Block YouTube Search",
                    switchKey = SwitchIdentifier.BLOCK_YT_SEARCH_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_TELEGRAM_SEARCH,
                    title = "Block Telegram Search",
                    switchKey = SwitchIdentifier.BLOCK_TELEGRAM_SEARCH_SWITCH
                ))

                // ===== SECTION_UNINSTALL_PROTECTION =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SECTION_UNINSTALL_PROTECTION,
                    title = "Uninstall Protection",
                    isSection = true
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.PREVENT_UNINSTALL_SETTINGS,
                    title = "Prevent uninstall",
                    info = "Block attempts to uninstall NopoX",
                    switchKey = SwitchIdentifier.PREVENT_UNINSTALL_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_NOTIFICATION_DRAWER,
                    title = "Block notification drawer",
                    switchKey = SwitchIdentifier.BLOCK_NOTIFICATION_DRAWER_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_PHONE_REBOOT,
                    title = "Block phone reboot",
                    switchKey = SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_RECENT_APPS,
                    title = "Block Recent Apps screen",
                    switchKey = SwitchIdentifier.BLOCK_RECENT_APPS_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE,
                    title = "Title-based block setting",
                    info = "Block specific settings pages by title",
                    switchKey = SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE_APPS,
                    title = "Setting page blocklist",
                    info = "Manage blocked settings pages",
                    actionLabel = "Manage"
                ))

                // ===== SECTION_ADVANCE_FEATURE =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SECTION_ADVANCE_FEATURE,
                    title = "Advanced feature",
                    isSection = true
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_UNSUPPORTED_BROWSERS,
                    title = "Block unsupported browsers",
                    info = "Block all browsers except whitelisted",
                    switchKey = SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER,
                    title = "Whitelist unsupported browsers",
                    info = "Manage browser whitelist",
                    actionLabel = "Manage"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.VPN,
                    title = "VPN (DNS blocking)",
                    info = "Block adult content at network level",
                    switchKey = SwitchIdentifier.VPN_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.WHITELIST_VPN_APPS,
                    title = "Whitelist VPN apps",
                    info = "Apps that bypass VPN",
                    actionLabel = "Manage"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE,
                    title = "VPN notification message",
                    info = "Custom message for VPN notification",
                    actionLabel = "Edit"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.VPN_NOTIFICATION_HIDE,
                    title = "Hide VPN notification content",
                    switchKey = SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS,
                    title = "Block new install apps",
                    info = "Auto-block newly installed apps",
                    switchKey = SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS,
                    title = "Block in-app browsers",
                    info = "Block in-app browsers inside other apps",
                    switchKey = SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE,
                    title = "Blocked screen image for motivation",
                    info = "Custom image shown on block screen",
                    actionLabel = "Choose"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE,
                    title = "Blocked screen message",
                    info = "Custom message shown on block screen",
                    actionLabel = "Edit"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCKED_SCREEN_COUNTDOWN,
                    title = "Blocked screen countdown",
                    info = "Require waiting N seconds before Close (3-300)",
                    actionLabel = "0s"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP,
                    title = "Custom redirect URL",
                    info = "URL to open when user taps Close",
                    actionLabel = "Edit"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP,
                    title = "Blocklist whitelist detected apps",
                    info = "Apps detected via accessibility events",
                    actionLabel = "Manage"
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SET_APP_LOCK,
                    title = "App lock",
                    info = "Require PIN/password/pattern to open NopoX",
                    switchKey = SwitchIdentifier.SET_APP_LOCK_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.TOUCH_ID,
                    title = "Touch ID (biometric)",
                    info = "Use fingerprint/face to unlock",
                    switchKey = SwitchIdentifier.TOUCH_ID_SWITCH
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.DISABLE_FORGOT_PASSWORD,
                    title = "Disable forgot password",
                    info = "Hide forgot password option",
                    switchKey = SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH
                ))

                // ===== SECTION_FAQ =====
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.SECTION_FAQ,
                    title = "Keep protect.yourself Live",
                    isSection = true
                ))
                add(SettingPageItemModel(
                    identifier = SettingPageItemIdentifiers.KEEP_NOPOX_LIVE,
                    title = "How to keep NopoX running",
                    info = "Battery + performance tips",
                    actionLabel = "View"
                ))
            }

            // Load current switch values
            val itemsWithValues = items.map { item ->
                if (item.switchKey != null) {
                    val value = when (item.switchKey) {
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
                    item.copy(switchValue = value)
                } else item
            }

            _state.update { it.copy(settingItems = itemsWithValues, isLoading = false) }
            Timber.i("BlockerPage loaded ${itemsWithValues.size} setting items")
        }
    }

    /**
     * Toggle a switch — first checks protective mode (Long Sentence, Time Delay, Real Friend).
     * If protective mode is set, the user must satisfy it before the toggle takes effect.
     */
    fun toggleSwitch(item: SettingPageItemModel) {
        val switchKey = item.switchKey ?: return
        val newValue = !item.switchValue

        viewModelScope.launch {
            // Phase 4: simple toggle (no protective mode check yet)
            // Phase 5: add protective mode flow (long sentence dialog, time delay countdown,
            //          real friend approval request)
            switchValues.storeSwitchStatus(switchKey, newValue)

            // Update local state
            _state.update { state ->
                state.copy(
                    settingItems = state.settingItems.map { itItem ->
                        if (itItem.switchKey == switchKey) itItem.copy(switchValue = newValue)
                        else itItem
                    }
                )
            }

            // Refresh accessibility service blocking config
            protect.yourself.features.blockerPage.service.MyAccessibilityService.instance
                ?.refreshBlockingConfig()

            // Handle VPN start/stop
            if (switchKey == SwitchIdentifier.VPN_SWITCH) {
                // Phase 6: properly handle VPN start/stop with VpnService.prepare() permission checks
                // For now, the VPN service is started by the user via system VPN settings
                // or via the VPN permission flow (Phase 6).
            }

            Timber.i("Switch $switchKey toggled to $newValue")
        }
    }

    /**
     * Triggered when user taps an action item (e.g. "Manage" button).
     * Phase 5 will route to the correct sub-page.
     */
    fun onActionClick(item: SettingPageItemModel) {
        Timber.d("Action clicked: ${item.identifier}")
        // Phase 5: route to specific sub-page based on identifier
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

/**
 * State for BlockerPage.
 */
data class BlockerPageState(
    val settingItems: List<SettingPageItemModel> = emptyList(),
    val isLoading: Boolean = true,
    val toastMessage: String? = null
)
