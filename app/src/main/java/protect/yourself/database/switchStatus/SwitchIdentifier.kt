package protect.yourself.database.switchStatus

/**
 * All switch keys used in the original Protect Yourself app (60+ keys, exhaustive).
 * Ported from jadx-decompiled SwitchStatusValues.
 *
 * IMPORTANT: Do NOT remove keys even if the feature is removed — preserves
 * DB schema compatibility for users who restore from original backups.
 */
object SwitchIdentifier {
    // Content blocking
    const val PORN_BLOCKER_SWITCH = "porn_blocker_switch"
    const val SAFE_SEARCH_SWITCH = "safe_search_switch"
    const val MAKE_ANY_BROWSER_SUPPORTED_SWITCH = "make_any_browser_supported_switch"

    // Insta/YT/Social
    const val BLOCK_SNAPCHAT_STORIES_SWITCH = "block_snapchat_stories_switch"
    const val BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH = "block_snapchat_spotlight_switch"
    const val BLOCK_INSTA_REELS_SWITCH = "block_insta_reels_switch"
    const val BLOCK_INSTA_SEARCH_SWITCH = "block_insta_search_switch"
    const val BLOCK_WHATSAPP_STATUS_SWITCH = "block_whatsapp_status_switch"
    const val BLOCK_YT_SHORTS_SWITCH = "block_yt_shorts_switch"
    const val BLOCK_YT_SEARCH_SWITCH = "block_yt_search_switch"
    const val BLOCK_TELEGRAM_SEARCH_SWITCH = "block_telegram_search_switch"

    // Uninstall protection
    const val PREVENT_UNINSTALL_SWITCH = "prevent_uninstall_switch"
    const val BLOCK_NOTIFICATION_DRAWER_SWITCH = "block_notification_drawer_switch"
    const val BLOCK_PHONE_REBOOT_SWITCH = "block_phone_reboot_switch"
    const val BLOCK_RECENT_APPS_SWITCH = "block_recent_apps_switch"
    const val BLOCK_SETTING_PAGE_BY_TITLE_SWITCH = "block_setting_page_by_title_switch"

    // Advanced
    const val BLOCK_UNSUPPORTED_BROWSERS_SWITCH = "block_unsupported_browsers_switch"
    const val BLOCK_PACKAGE_INTENT_SWITCH = "block_package_intent_switch"
    const val VPN_SWITCH = "vpn_switch"
    // VPN connection mode (NORMAL=1, POWERFUL=2, CUSTOM=3). Stored as long.
    // See [protect.yourself.features.blockerPage.identifiers.VpnConnectionTypeIdentifiers].
    const val VPN_CONNECTION_TYPE = "vpn_connection_type"
    const val VPN_NOTIFICATION_HIDE_SWITCH = "vpn_notification_hide_switch"
    const val BLOCK_NEW_INSTALL_APPS_SWITCH = "block_new_install_apps_switch"
    const val BLOCK_IN_APP_BROWSERS_SWITCH = "block_in_app_browsers_switch"
    const val SET_APP_LOCK_SWITCH = "set_app_lock_switch"
    const val TOUCH_ID_SWITCH = "touch_id_switch"
    const val DISABLE_FORGOT_PASSWORD_SWITCH = "disable_forgot_password_switch"

    // Accountability partner / Protective modes
    const val ACCOUNTABILITY_PARTNER_TYPE = "accountability_partner_type"
    const val LONG_SENTENCE_MESSAGE_SET = "long_sentence_message_set"
    const val LONG_SENTENCE_CUSTOM_MESSAGE = "long_sentence_custom_message"
    const val TIME_DELAY_DURATION_SET = "time_delay_duration_set"
    const val TIME_DELAY_CUSTOM_DURATION = "time_delay_custom_duration"
    const val REAL_FRIEND_EMAIL = "real_friend_email"
    const val REAL_FRIEND_VISIBLE = "real_friend_visible"
    const val DAILY_REPORT_SWITCH = "daily_report_switch"

    // Block screen customization
    const val BLOCK_SCREEN_COUNT_DOWN_TIME_SET = "block_screen_count_down_time_set"
    const val BLOCK_SCREEN_CUSTOM_MESSAGE_SET = "block_screen_custom_message_set"
    const val BLOCK_SCREEN_CUSTOM_MESSAGE = "block_screen_custom_message"
    const val BLOCK_SCREEN_REDIRECT_URL_SET = "block_screen_redirect_url_set"
    const val BLOCK_SCREEN_REDIRECT_URL = "block_screen_redirect_url"
    const val BLOCK_SCREEN_STORE_IMAGE_PATH = "block_screen_store_image_path"

    // VPN customization
    const val VPN_NOTIFICATION_CUSTOM_MESSAGE_SET = "vpn_notification_custom_message_set"
    const val VPN_NOTIFICATION_CUSTOM_MESSAGE = "vpn_notification_custom_message"
    const val VPN_DNS_CUSTOM_LIST_SET = "vpn_dns_custom_list_set"

    // Stop Me
    const val STOP_ME_WHITELIST_APPS_SET = "stop_me_whitelist_apps_set"
    const val SUPPORTED_BROWSER_DEFAULT_APP_SET = "supported_browser_default_app_set"

    // App state
    const val TERMS_APPROVE_STATUS = "terms_approve_status"
    const val RATING_GIVEN_STATUS = "rating_given_status"
    const val FIREBASE_TOKEN = "firebase_token"
    const val LAST_BACKUP_CREATED_TIME = "last_backup_created_time"
    const val USER_DEVICE_CURRENCY_CODE = "user_device_currency_code"

    // REMOVED (premium-related) — kept here for documentation, NOT persisted in rebuild
    // isPremiumActive, isEligibleForBannerAd, isIntroPremiumPageActionDone, etc.

    /** All keys defined above (for validation / migration / debugging). */
    val ALL: Set<String> = setOf(
        PORN_BLOCKER_SWITCH,
        SAFE_SEARCH_SWITCH,
        MAKE_ANY_BROWSER_SUPPORTED_SWITCH,
        BLOCK_SNAPCHAT_STORIES_SWITCH,
        BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH,
        BLOCK_INSTA_REELS_SWITCH,
        BLOCK_INSTA_SEARCH_SWITCH,
        BLOCK_WHATSAPP_STATUS_SWITCH,
        BLOCK_YT_SHORTS_SWITCH,
        BLOCK_YT_SEARCH_SWITCH,
        BLOCK_TELEGRAM_SEARCH_SWITCH,
        PREVENT_UNINSTALL_SWITCH,
        BLOCK_NOTIFICATION_DRAWER_SWITCH,
        BLOCK_PHONE_REBOOT_SWITCH,
        BLOCK_RECENT_APPS_SWITCH,
        BLOCK_SETTING_PAGE_BY_TITLE_SWITCH,
        BLOCK_UNSUPPORTED_BROWSERS_SWITCH,
        BLOCK_PACKAGE_INTENT_SWITCH,
        VPN_SWITCH,
        VPN_CONNECTION_TYPE,
        VPN_NOTIFICATION_HIDE_SWITCH,
        BLOCK_NEW_INSTALL_APPS_SWITCH,
        BLOCK_IN_APP_BROWSERS_SWITCH,
        SET_APP_LOCK_SWITCH,
        TOUCH_ID_SWITCH,
        DISABLE_FORGOT_PASSWORD_SWITCH,
        ACCOUNTABILITY_PARTNER_TYPE,
        LONG_SENTENCE_MESSAGE_SET,
        LONG_SENTENCE_CUSTOM_MESSAGE,
        TIME_DELAY_DURATION_SET,
        TIME_DELAY_CUSTOM_DURATION,
        REAL_FRIEND_EMAIL,
        REAL_FRIEND_VISIBLE,
        DAILY_REPORT_SWITCH,
        BLOCK_SCREEN_COUNT_DOWN_TIME_SET,
        BLOCK_SCREEN_CUSTOM_MESSAGE_SET,
        BLOCK_SCREEN_CUSTOM_MESSAGE,
        BLOCK_SCREEN_REDIRECT_URL_SET,
        BLOCK_SCREEN_REDIRECT_URL,
        BLOCK_SCREEN_STORE_IMAGE_PATH,
        VPN_NOTIFICATION_CUSTOM_MESSAGE_SET,
        VPN_NOTIFICATION_CUSTOM_MESSAGE,
        VPN_DNS_CUSTOM_LIST_SET,
        STOP_ME_WHITELIST_APPS_SET,
        SUPPORTED_BROWSER_DEFAULT_APP_SET,
        TERMS_APPROVE_STATUS,
        RATING_GIVEN_STATUS,
        FIREBASE_TOKEN,
        LAST_BACKUP_CREATED_TIME,
        USER_DEVICE_CURRENCY_CODE
    )
}
