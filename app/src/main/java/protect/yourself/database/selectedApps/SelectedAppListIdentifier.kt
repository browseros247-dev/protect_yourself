package protect.yourself.database.selectedApps

/**
 * Categories of selected apps (port from original SelectedAppListIdentifier.kt).
 *
 * The original used enum with string `value` persisted to Room.
 */
enum class SelectedAppListIdentifier(val value: String) {
    ALL_APPS("all_apps"),
    BLOCK_APPS("block_apps"),
    BLOCK_SETTING_PAGE_BY_TITLE_APPS("block_setting_page_by_title_apps"),
    VPN_WHITELIST_APPS("vpn_whitelist_apps"),
    BLOCK_IN_APP_BROWSER_APPS("block_in_app_browser_apps"),
    BLOCK_NEW_INSTALL_APPS("block_new_install_apps_item"),
    WHITELIST_UNSUPPORTED_BROWSER("whitelist_unsupported_browser"),
    WHITELIST_STOP_ME_APPS("whitelist_stop_me_apps"),
    BLOCK_WHITELIST_DETECTED_APPS("block_whitelist_detected_apps"),
    BLOCKED_PACKAGE_NAMES("blocked_package_names");
}
