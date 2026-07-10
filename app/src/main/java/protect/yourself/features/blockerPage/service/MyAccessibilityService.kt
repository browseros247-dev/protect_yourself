package protect.yourself.features.blockerPage.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.utils.BlockerPageUtils
import timber.log.Timber
import java.util.Locale

/**
 * MyAccessibilityService — core blocking service.
 *
 * Phase 3 implementation:
 *  - Listens for window state + content changes
 *  - Extracts URL from browser address bars via view IDs
 *  - Matches URL/text against keyword blocklist + whitelist
 *  - Detects app switches (blocklist app launch)
 *  - Detects social-media-specific features (YT Shorts, IG Reels, etc.)
 *  - Detects settings pages, recent apps, notification drawer (anti-circumvention)
 *  - Launches PornBlockActivity on block trigger
 *
 * Ported from original `MyAccessibilityService.kt` (3,166 lines decompiled).
 * This implementation covers the primary blocking flows; Phase 6 will add
 * anti-uninstall watchdog and accessibility self-heal.
 */
class MyAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Cached blocking config (refreshed periodically)
    private var cachedBlockKeywords: List<String> = emptyList()
    private var cachedWhitelistKeywords: List<String> = emptyList()
    private var cachedBlockApps: Set<String> = emptySet()
    private var cachedStopMeWhitelist: Set<String> = emptySet()
    private var cachedVpnWhitelist: Set<String> = emptySet()
    private var cachedNewInstallBlockApps: Set<String> = emptySet()
    private var cachedInAppBrowserBlockApps: Set<String> = emptySet()
    private var cachedSupportedBrowsers: Set<String> = emptySet()
    private var cachedSupportedSocialMedia: Set<String> = emptySet()
    private var cachedUnsupportedBrowserWhitelist: Set<String> = emptySet()

    private var isPornBlockerOn = true
    private var isBlockAllWebsiteOn = false
    private var isSafeSearchOn = false
    private var isBlockImageVideoOn = false
    private var isBlockYtShortsOn = false
    private var isBlockYtSearchOn = false
    private var isBlockInstaReelsOn = false
    private var isBlockInstaSearchOn = false
    private var isBlockWhatsappStatusOn = false
    private var isBlockSnapchatStoriesOn = false
    private var isBlockSnapchatSpotlightOn = false
    private var isBlockTelegramSearchOn = false
    private var isBlockNewInstallOn = false
    private var isBlockInAppBrowsersOn = false
    private var isBlockUnsupportedBrowsersOn = false
    private var isBlockSettingsByTitleOn = false
    private var isBlockNotificationDrawerOn = false
    private var isBlockRecentAppsOn = false
    private var isStopMeRunning = false

    private var lastBlockedPackage: String? = null
    private var lastBlockTimeMs: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("Accessibility service connected")
        instance = this
        configureService()
        refreshBlockingConfig()
        // Show toast to confirm service is active
        android.widget.Toast.makeText(this, "Protect Yourself: Accessibility connected", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun configureService() {
        // Configure service info dynamically (also configured via XML, but dynamic allows runtime tuning)
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                notificationTimeout = 100
                flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            serviceInfo = info
        } catch (t: Throwable) {
            Timber.w(t, "Failed to configure accessibility service info")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType

        try {
            when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChange(packageName, event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleContentChange(packageName, event)
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "Error handling accessibility event from $packageName")
        }
    }

    override fun onInterrupt() {
        Timber.w("Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.w("Accessibility service destroyed")
        instance = null
        // Show toast to warn user
        android.widget.Toast.makeText(this, "Protect Yourself: Accessibility disconnected — blocking disabled", android.widget.Toast.LENGTH_LONG).show()
    }

    // ===== Window state change handler =====

    private fun handleWindowStateChange(packageName: String, event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        val text = event.text?.joinToString(" ").orEmpty()
        Timber.v("WindowStateChange pkg=$packageName class=$className text=$text")

        // Anti-circumvention: block notification drawer / recent apps / settings pages
        if (isBlockNotificationDrawerOn && isNotificationDrawer(className, packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchBlockActivity(packageName, "block_page_default_notification_drawer_message")
            return
        }
        if (isBlockRecentAppsOn && isRecentApps(className, packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchBlockActivity(packageName, "block_recent_apps_bw_message")
            return
        }
        if (isBlockSettingsByTitleOn && isSettingsPage(packageName, text)) {
            launchBlockActivity(packageName, "block_page_default_system_keyword_message")
            return
        }

        // Block apps (blocklist)
        if (isPornBlockerOn && cachedBlockApps.contains(packageName)) {
            launchBlockActivity(packageName, "block_page_default_block_apps_message")
            return
        }

        // Block new install apps
        if (isBlockNewInstallOn && cachedNewInstallBlockApps.contains(packageName)) {
            launchBlockActivity(packageName, "block_page_default_new_install_message")
            return
        }

        // Block unsupported browsers
        if (isBlockUnsupportedBrowsersOn &&
            isBrowserPackage(packageName) &&
            !cachedUnsupportedBrowserWhitelist.contains(packageName)
        ) {
            launchBlockActivity(packageName, "block_page_default_unsupported_browser_message")
            return
        }

        // Block in-app browsers
        if (isBlockInAppBrowsersOn && cachedInAppBrowserBlockApps.contains(packageName)) {
            val url = extractUrlFromEvent(event, packageName)
            if (url != null) {
                launchBlockActivity(packageName, "block_page_default_in_app_browser_message")
                return
            }
        }

        // Social media feature blocking
        handleSocialMediaBlocking(packageName, className, text)

        // Stop Me: block apps not in whitelist
        if (isStopMeRunning && !cachedStopMeWhitelist.contains(packageName)) {
            launchBlockActivity(packageName, "block_page_default_stop_me_message")
            return
        }
    }

    // ===== Content change handler =====

    private fun handleContentChange(packageName: String, event: AccessibilityEvent) {
        if (!isPornBlockerOn) return

        // Try to extract URL from browser address bar
        if (cachedSupportedBrowsers.contains(packageName) || isBrowserPackage(packageName)) {
            val url = extractUrlFromEvent(event, packageName)
            if (url != null && url.isNotBlank()) {
                handleUrlDetected(packageName, url)
            }
        }

        // Try to extract text from social media search bars
        if (cachedSupportedSocialMedia.contains(packageName)) {
            val text = extractTextFromEvent(event, packageName)
            if (text.isNotBlank()) {
                handleSocialMediaSearch(packageName, text)
            }
        }
    }

    // ===== URL detection + matching =====

    private fun handleUrlDetected(packageName: String, url: String) {
        val utils = BlockerPageUtils.getInstance()
        val decoded = utils.decodeText(url)

        // Block all websites (whitelist overrides)
        if (isBlockAllWebsiteOn && !utils.isSafeUrl(decoded, cachedWhitelistKeywords)) {
            launchBlockActivity(packageName, "block_page_default_block_all_websites_message")
            return
        }

        // Image/video search blocking
        if (isBlockImageVideoOn && utils.isImageVideoUrl(decoded)) {
            launchBlockActivity(packageName, "block_page_default_img_vid_message")
            return
        }

        // Whitelist check (overrides block)
        if (utils.isSafeUrl(decoded, cachedWhitelistKeywords)) {
            return
        }

        // Block keyword match
        val (found, _) = utils.isDetectWord(decoded, cachedBlockKeywords)
        if (found) {
            launchBlockActivity(packageName, "block_page_default_porn_blocker_message")
            return
        }

        // SafeSearch enforcement: redirect to safe search if user tries unsafe Google
        if (isSafeSearchOn) {
            enforceSafeSearch(packageName, decoded)
        }
    }

    // ===== Social media feature blocking =====

    private fun handleSocialMediaBlocking(packageName: String, className: String, text: String) {
        // YouTube Shorts
        if (isBlockYtShortsOn && packageName == "com.google.android.youtube") {
            if (className.contains("Shorts", ignoreCase = true) ||
                text.contains("/shorts/", ignoreCase = true)
            ) {
                launchBlockActivity(packageName, "block_page_default_block_yt_short_message")
                return
            }
        }
        // Instagram Reels
        if (isBlockInstaReelsOn && packageName == "com.instagram.android") {
            if (className.contains("Reel", ignoreCase = true) ||
                text.contains("reel", ignoreCase = true)
            ) {
                launchBlockActivity(packageName, "block_page_default_block_insta_reel_message")
                return
            }
        }
        // Snapchat Stories / Spotlight
        if (packageName == "com.snapchat.android") {
            if (isBlockSnapchatStoriesOn && className.contains("Story", ignoreCase = true)) {
                launchBlockActivity(packageName, "block_page_default_block_snapchat_stories_message")
                return
            }
            if (isBlockSnapchatSpotlightOn && className.contains("Spotlight", ignoreCase = true)) {
                launchBlockActivity(packageName, "block_page_default_block_snapchat_spotlights_message")
                return
            }
        }
        // WhatsApp Status
        if (isBlockWhatsappStatusOn && packageName == "com.whatsapp") {
            if (className.contains("Status", ignoreCase = true)) {
                launchBlockActivity(packageName, "block_page_default_block_whatsapp_status_message")
                return
            }
        }
    }

    private fun handleSocialMediaSearch(packageName: String, text: String) {
        val utils = BlockerPageUtils.getInstance()
        val decoded = utils.decodeText(text)

        if (packageName == "com.google.android.youtube" && isBlockYtSearchOn) {
            val (found, _) = utils.isDetectWord(decoded, cachedBlockKeywords)
            if (found) {
                launchBlockActivity(packageName, "block_page_default_block_yt_search_message")
                return
            }
        }
        if (packageName == "com.instagram.android" && isBlockInstaSearchOn) {
            val (found, _) = utils.isDetectWord(decoded, cachedBlockKeywords)
            if (found) {
                launchBlockActivity(packageName, "block_page_default_block_insta_search_message")
                return
            }
        }
        if (packageName == "org.telegram.messenger" && isBlockTelegramSearchOn) {
            val (found, _) = utils.isDetectWord(decoded, cachedBlockKeywords)
            if (found) {
                launchBlockActivity(packageName, "block_page_default_block_telegram_search_message")
                return
            }
        }
    }

    // ===== SafeSearch enforcement =====

    private fun enforceSafeSearch(packageName: String, url: String) {
        // Original: redirect www.google.com -> forcesafesearch.google.com
        // We can't modify the URL in the browser, so we block and show message
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains("google.com/search") && !lower.contains("safe=active")) {
            launchBlockActivity(packageName, "block_page_default_safe_search_message")
        }
    }

    // ===== URL extraction =====

    /**
     * Try to extract the URL from a browser address bar via accessibility node traversal.
     */
    private fun extractUrlFromEvent(event: AccessibilityEvent, packageName: String): String? {
        val viewIds = BlockerPageUtils.BROWSER_URL_VIEW_IDS[packageName] ?: return null
        val root = rootInActiveWindow ?: return null

        try {
            for (viewId in viewIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (nodes != null && nodes.isNotEmpty()) {
                    val node = nodes[0]
                    val text = node.text?.toString() ?: continue
                    if (text.isNotBlank()) return text
                }
            }
            // Fallback: search any EditText-like node with URL text
            val fallbackUrl = findUrlInNode(root)
            if (fallbackUrl != null) return fallbackUrl
        } catch (t: Throwable) {
            Timber.v("URL extraction failed: ${t.message}")
        }
        return null
    }

    private fun findUrlInNode(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val text = node.text?.toString() ?: ""
        if (text.isNotBlank() && (text.startsWith("http") || text.contains("://"))) {
            return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findUrlInNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun extractTextFromEvent(event: AccessibilityEvent, packageName: String): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        val root = rootInActiveWindow
        if (root != null) {
            try {
                collectText(root, sb, depth = 0, maxDepth = 3)
            } catch (_: Throwable) {}
        }
        return sb.toString().trim()
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        node.text?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb, depth + 1, maxDepth)
        }
    }

    // ===== Detection helpers =====

    private fun isNotificationDrawer(className: String, packageName: String): Boolean {
        val lower = className.lowercase(Locale.ROOT)
        return lower.contains("statusbar") ||
            lower.contains("notification") ||
            lower.contains("quicksettings") ||
            packageName == "com.android.systemui" && lower.contains("shade")
    }

    private fun isRecentApps(className: String, packageName: String): Boolean {
        val lower = className.lowercase(Locale.ROOT)
        return lower.contains("recents") ||
            lower.contains("recentapps") ||
            lower.contains("overview") ||
            lower.contains("taskview")
    }

    private fun isSettingsPage(packageName: String, text: String): Boolean {
        if (packageName != "com.android.settings" && !packageName.contains(".settings")) return false
        // Check if any setting keyword matches the page title
        val lower = text.lowercase(Locale.ROOT)
        // Phase 3 stub: real implementation reads from setting_keywords_list in DB
        return false  // TODO: query setting_keywords_list and match
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        // Common browser signatures
        return packageName.contains("browser") ||
            packageName.contains("chrome") ||
            packageName.contains("firefox") ||
            packageName.contains("opera") ||
            packageName.contains("brave") ||
            packageName.contains("edge") ||
            packageName.contains("emmx") ||
            packageName.contains("vivaldi") ||
            packageName.contains("duckduckgo")
    }

    // ===== Block activity launcher =====

    private fun launchBlockActivity(packageName: String, messageResKey: String) {
        val now = System.currentTimeMillis()
        // Throttle: don't launch more than once per 500ms per package
        if (packageName == lastBlockedPackage && now - lastBlockTimeMs < 500) {
            return
        }
        lastBlockedPackage = packageName
        lastBlockTimeMs = now

        // Press HOME first to dismiss the offending app
        performGlobalAction(GLOBAL_ACTION_HOME)

        // Launch PornBlockActivity
        val intent = Intent(this, protect.yourself.features.blockerPage.ui.PornBlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(EXTRA_BLOCK_PACKAGE, packageName)
            putExtra(EXTRA_BLOCK_MESSAGE_KEY, messageResKey)
        }
        startActivity(intent)
    }

    // ===== Config refresh =====

    /**
     * Refresh cached blocking config from DB.
     * Called on service connect + periodically by AppDataCheckWorker.
     */
    fun refreshBlockingConfig() {
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyAccessibilityService)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                // Switches
                isPornBlockerOn = switchValues.isPornBlockerSwitchOn()
                isBlockAllWebsiteOn = switchValues.isBlockAllWebsiteSwitchOn()
                isSafeSearchOn = switchValues.isSafeSearchSwitchOn()
                isBlockImageVideoOn = switchValues.isBlockImageVideoSwitchOn()
                isBlockYtShortsOn = switchValues.isBlockYtShortsSwitchOn()
                isBlockYtSearchOn = switchValues.isBlockYtSearchSwitchOn()
                isBlockInstaReelsOn = switchValues.isBlockInstaReelsSwitchOn()
                isBlockInstaSearchOn = switchValues.isBlockInstaSearchSwitchOn()
                isBlockWhatsappStatusOn = switchValues.isBlockWhatsappStatusSwitchOn()
                isBlockSnapchatStoriesOn = switchValues.isBlockSnapchatStoriesSwitchOn()
                isBlockSnapchatSpotlightOn = switchValues.isBlockSnapchatSpotlightSwitchOn()
                isBlockTelegramSearchOn = switchValues.isBlockTelegramSearchSwitchOn()
                isBlockNewInstallOn = switchValues.isBlockNewInstallAppsSwitchOn()
                isBlockInAppBrowsersOn = switchValues.isBlockInAppBrowsersSwitchOn()
                isBlockUnsupportedBrowsersOn = switchValues.isBlockUnsupportedBrowsersSwitchOn()
                isBlockSettingsByTitleOn = switchValues.isBlockSettingPageByTitleSwitchOn()
                isBlockNotificationDrawerOn = switchValues.isBlockNotificationDrawerSwitchOn()
                isBlockRecentAppsOn = switchValues.isBlockRecentAppsSwitchOn()

                // Keywords
                cachedBlockKeywords = db.selectedKeywordDao()
                    .getSelectedByIdentifier(SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value)
                    .map { it.keyword }
                cachedWhitelistKeywords = db.selectedKeywordDao()
                    .getSelectedByIdentifier(SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value)
                    .map { it.keyword }

                // Apps
                cachedBlockApps = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_APPS.value)
                    .map { it.packageName }.toSet()
                cachedStopMeWhitelist = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.WHITELIST_STOP_ME_APPS.value)
                    .map { it.packageName }.toSet()
                cachedVpnWhitelist = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.VPN_WHITELIST_APPS.value)
                    .map { it.packageName }.toSet()
                cachedNewInstallBlockApps = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
                    .map { it.packageName }.toSet()
                cachedInAppBrowserBlockApps = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_IN_APP_BROWSER_APPS.value)
                    .map { it.packageName }.toSet()
                cachedSupportedBrowsers = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.SUPPORTED_BROWSER_APPS.value)
                    .map { it.packageName }.toSet()
                cachedSupportedSocialMedia = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.SUPPORTED_SOCIAL_MEDIA_APPS.value)
                    .map { it.packageName }.toSet()
                cachedUnsupportedBrowserWhitelist = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER.value)
                    .map { it.packageName }.toSet()

                Timber.i("Blocking config refreshed: ${cachedBlockKeywords.size} block keywords, " +
                    "${cachedWhitelistKeywords.size} whitelist keywords, " +
                    "${cachedBlockApps.size} block apps, " +
                    "${cachedSupportedBrowsers.size} supported browsers")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to refresh blocking config")
            }
        }
    }

    /**
     * Called when a Stop Me session starts/stops — accessibility blocks non-whitelisted apps.
     */
    fun setStopMeRunning(running: Boolean) {
        isStopMeRunning = running
        Timber.i("Stop Me running state: $running")
    }

    companion object {
        const val EXTRA_BLOCK_PACKAGE = "extra_block_package"
        const val EXTRA_BLOCK_MESSAGE_KEY = "extra_block_message_key"

        @Volatile
        var instance: MyAccessibilityService? = null
            private set

        /**
         * Check if this accessibility service is enabled (system setting).
         */
        fun isEnabled(context: android.content.Context): Boolean {
            val expectedComponent = context.packageName + "/" + MyAccessibilityService::class.java.name
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expectedComponent)
        }
    }
}
