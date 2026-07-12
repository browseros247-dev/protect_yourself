package protect.yourself.features.blockerPage.identifiers

/**
 * SettingPageItemIdentifiers — identifies each setting item in the BlockerPage.
 *
 * Used by [protect.yourself.features.blockerPage.BlockerPageViewModel.buildSettingItems]
 * to render the settings list, and by [BlockerPageViewModel.onActionClick] /
 * [BlockerPageViewModel.toggleSwitch] to dispatch user actions.
 *
 * # Removed values (cleaned up)
 *
 * The following values were removed because they were never referenced after
 * the rebuild moved to a Compose-based settings UI:
 *  - Premium section: PREMIUM_LOGIN_ICON, PREMIUM_OFFER, LOGIN_NOW (premium removed)
 *  - Long Sentence: LONG_SENTENCE, LONG_SENTENCE_CUSTOM_MESSAGE (always-on in background,
 *    no UI toggle)
 *  - Standalone keyword manager: BLOCKER_CUSTOM_KEYWORD_WEBSITE (replaced by
 *    UNIFIED_BLOCKING_MANAGEMENT which opens the unified blocking page)
 *  - Social media blocking section: SECTION_INSTA_YT_BLOCKING, BLOCK_SNAPCHAT_STORIES,
 *    BLOCK_SNAPCHAT_SPOTLIGHT, BLOCK_INSTA_REELS, BLOCK_INSTA_SEARCH, BLOCK_WHATSAPP_STATUS,
 *    BLOCK_YT_SHORTS, BLOCK_YT_SEARCH, BLOCK_TELEGRAM_SEARCH (entire section dropped)
 *  - Anti-circumvention (removed from UI but kept in SwitchIdentifier for DB compat):
 *    BLOCK_NOTIFICATION_DRAWER, BLOCK_RECENT_APPS (the switches still exist in
 *    SwitchIdentifier for DB-backup compatibility, but no UI renders them)
 *  - Standalone title/package/intent cards: BLOCK_SETTING_PAGE_BY_TITLE_APPS,
 *    BLOCK_PACKAGE_INTENT, ADD_PACKAGE_INTENT_TO_BLOCK (replaced by
 *    UNIFIED_BLOCKING_MANAGEMENT which merges all blocking lists into one card)
 */
enum class SettingPageItemIdentifiers {
    // Alert section
    BLOCK_SCREEN_COUNT,
    SECTION_ALERT,

    // Permissions
    ACCESSIBILITY_PERMISSION,
    DISPLAY_POPUP_WINDOW_PERMISSION,

    // Protective Mode section
    SECTION_ACCOUNTABILITY_PARTNER,
    TIME_DELAY,
    TIME_DELAY_CUSTOM_DURATION,
    REAL_FRIEND,
    DAILY_REPORT,
    SUGGEST_PROTECTIVE_MODE,
    REQUEST_HISTORY,

    // Content Blocking section
    SECTION_CONTENT_BLOCKING,
    PORN_BLOCKER,
    UNIFIED_BLOCKING_MANAGEMENT,
    BLOCKLIST_APPS,
    SAFE_SEARCH,
    BLOCK_UNSUPPORTED_BROWSERS,
    WHITELIST_UNSUPPORTED_BROWSER,

    // Uninstall Protection section
    SECTION_UNINSTALL_PROTECTION,
    PREVENT_UNINSTALL_SETTINGS,
    BLOCK_PHONE_REBOOT,
    BLOCK_SETTING_PAGE_BY_TITLE,

    // Advanced Features section
    SECTION_ADVANCE_FEATURE,
    VPN,
    // Opens the dedicated VPN management page (mode picker + custom DNS manager).
    VPN_MANAGE,
    WHITELIST_VPN_APPS,
    VPN_NOTIFICATION_MESSAGE,
    VPN_NOTIFICATION_HIDE,
    BLOCK_NEW_INSTALL_APPS,
    BLOCK_IN_APP_BROWSERS,
    BLOCKED_SCREEN_IMAGE,
    BLOCKED_SCREEN_MESSAGE,
    BLOCKED_SCREEN_COUNTDOWN,
    CUSTOM_REDIRECT_URL_APP,
    BLOCK_WHITELIST_DETECTED_APP,

    // App Lock section
    SECTION_APP_LOCK,
    SET_APP_LOCK,
    TOUCH_ID,
    DISABLE_FORGOT_PASSWORD,

    // FAQ section
    SECTION_FAQ,
    KEEP_NOPOX_LIVE
}
