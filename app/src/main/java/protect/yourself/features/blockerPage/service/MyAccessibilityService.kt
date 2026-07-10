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
    private var cachedUnsupportedBrowserWhitelist: Set<String> = emptySet()

    private var isPornBlockerOn = true
    private var isSafeSearchOn = false
    private var isBlockNewInstallOn = false
    private var isBlockInAppBrowsersOn = false
    private var isBlockUnsupportedBrowsersOn = false
    private var isBlockSettingsByTitleOn = false
    private var isPreventUninstallOn = false
    private var isBlockPhoneRebootOn = false
    // BLOCK_NOTIFICATION_DRAWER + BLOCK_RECENT_APPS removed from UI
    private var cachedSettingTitles: List<String> = emptyList()
    // Package + intent name blocking
    private var cachedBlockedPackageNames: Set<String> = emptySet()
    private var cachedBlockedIntentNames: Set<String> = emptySet()
    private var isBlockPackageIntentOn = false
    private var isStopMeRunning = false

    // PackageManager-level browser detection cache: pkg -> isBrowser
    private val browserCache = mutableMapOf<String, Boolean>()

    private var lastBlockedPackage: String? = null
    private var lastBlockTimeMs: Long = 0

    // SafeSearch redirect throttle — prevents redirect loops + rapid-fire intents
    private var lastSafeSearchPackage: String? = null
    private var lastSafeSearchTimeMs: Long = 0
    private var lastSafeSearchUrl: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("Accessibility service connected")
        instance = this
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()
            ?.logBreadcrumb("AccessibilityService", "onServiceConnected")
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
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                throwable = t,
                tag = "AccessibilityService",
                message = "Error handling event from $packageName (type=$eventType)",
                extraContext = mapOf(
                    "packageName" to packageName,
                    "eventType" to eventType.toString(),
                    "eventClass" to (event.className?.toString() ?: "")
                )
            )
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

        // Skip our own app — we never want to block our own block screen,
        // app lock, or main activity. This also prevents infinite re-block loops
        // where the block activity launch triggers a WINDOW_STATE_CHANGED event
        // that re-triggers blocking.
        if (packageName == this.packageName) return

        // Skip SystemUI (status bar, notification shade, recents, etc.) —
        // blocking these can freeze the device.
        if (packageName == "com.android.systemui") return

        // Settings page title blocking (NopoX-style: checks settings pages only)
        // This matches the NopoX reference implementation which filters by
        // com.android.settings or packages containing ".settings" to prevent
        // false positives from non-settings apps.
        if (isBlockSettingsByTitleOn && isSettingsPage(packageName, text)) {
            launchBlockActivity(packageName, "block_page_default_system_keyword_message")
            return
        }

        // Title-based blocking on ANY app — DISABLED by default to prevent false positives.
        // The previous implementation checked ALL apps' text against the keyword list,
        // which caused massive false positives (e.g. keyword "battery" would block
        // any web page about batteries in Chrome). NopoX only blocks settings pages.
        //
        // This check is kept as a separate, more targeted feature: it only fires
        // if the package is NOT com.android.settings (already handled above) and
        // the className matches a blocked title — NOT the event text. Class names
        // are much more specific than arbitrary text, so the false positive risk
        // is much lower.
        if (isBlockSettingsByTitleOn && packageName != "com.android.settings" && !packageName.contains(".settings")) {
            if (isClassNameBlocked(packageName, className)) {
                launchBlockActivity(packageName, "block_page_default_system_keyword_message")
                return
            }
        }

        // Package + Intent name blocking (NEW feature)
        if (isBlockPackageIntentOn) {
            if (isPackageOrIntentBlocked(packageName, className)) {
                launchBlockActivity(packageName, "block_page_default_block_apps_message")
                return
            }
        }

        // Prevent Uninstall: detect when user is on our app info page
        if (isPreventUninstallOn && isAppInfoPage(packageName, className, text)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchBlockActivity(packageName, "block_page_default_pu_message")
            return
        }

        // Block phone reboot: detect power menu / ultra power saving
        if (isBlockPhoneRebootOn && isPowerMenu(className, packageName, text)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchBlockActivity(packageName, "block_phone_reboot_bw_message")
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
        // When this switch is ON, any browser NOT in the unsupported-browser
        // whitelist gets blocked on launch. A "browser" is defined as an app
        // that handles http/https URLs (via intent filter) OR matches known
        // browser package signatures.
        if (isBlockUnsupportedBrowsersOn && isBrowserPackageDetected(packageName)) {
            val isWhitelisted = cachedUnsupportedBrowserWhitelist.contains(packageName)
            if (!isWhitelisted) {
                launchBlockActivity(packageName, "block_page_default_unsupported_browser_message")
                return
            }
        }

        // Block in-app browsers
        if (isBlockInAppBrowsersOn && cachedInAppBrowserBlockApps.contains(packageName)) {
            val url = extractUrlFromEvent(event, packageName)
            if (url != null) {
                launchBlockActivity(packageName, "block_page_default_in_app_browser_message")
                return
            }
        }

        // Stop Me: block apps not in whitelist
        if (isStopMeRunning && !cachedStopMeWhitelist.contains(packageName)) {
            launchBlockActivity(packageName, "block_page_default_stop_me_message")
            return
        }
    }

    // ===== Content change handler =====

    private fun handleContentChange(packageName: String, event: AccessibilityEvent) {
        if (!isPornBlockerOn) return

        // Scrape URLs from any detected browser. The "supported browsers" concept
        // was removed — the accessibility service now scrapes from any app that
        // declares a browser intent filter (or matches known browser signatures).
        // This is simpler and more effective: no need for users to manage a list.
        if (isBrowserPackageDetected(packageName)) {
            val url = extractUrlFromEvent(event, packageName)
            if (url != null && url.isNotBlank()) {
                handleUrlDetected(packageName, url)
            }
        }
    }

    // ===== URL detection + matching =====

    private fun handleUrlDetected(packageName: String, url: String) {
        val utils = BlockerPageUtils.getInstance()
        val decoded = utils.decodeText(url)

        // Whitelist check (overrides block)
        if (utils.isSafeUrl(decoded, cachedWhitelistKeywords)) {
            return
        }

        // Block keyword match — use isDetectWordInUrl (does NOT strip URLs)
        val (found, _) = utils.isDetectWordInUrl(decoded, cachedBlockKeywords)
        if (found) {
            launchBlockActivity(packageName, "block_page_default_porn_blocker_message")
            return
        }

        // SafeSearch enforcement: redirect to safe search if user tries unsafe Google
        if (isSafeSearchOn) {
            enforceSafeSearch(packageName, decoded)
        }
    }

    // ===== SafeSearch enforcement =====

    /**
     * NopoX-style SafeSearch enforcement.
     *
     * When the SafeSearch switch is ON and the user navigates to an unsafe
     * search engine URL (Google, Bing, YouTube, DuckDuckGo), the service:
     *   1. Presses HOME to dismiss the unsafe page
     *   2. Opens the SafeSearch-enforced variant URL in the same browser
     *
     * Safe variant hosts:
     *   - www.google.com  → forcesafesearch.google.com
     *   - www.bing.com     → strict.bing.com
     *   - www.youtube.com  → restrict.youtube.com
     *   - duckduckgo.com   → safe.duckduckgo.com
     *
     * The path and query are preserved so the user's search query is kept.
     *
     * Throttle: 2-second cooldown per package+URL to prevent redirect loops
     * and rapid-fire intents. The safe variant URL itself is excluded from
     * redirect (via isSafeSearchUrl check in getSafeSearchUrl).
     *
     * Second layer: when VPN is also ON, the family DNS resolvers
     * (Cloudflare 1.1.1.3 / AdGuard 94.140.14.15) enforce SafeSearch at
     * the DNS level — this accessibility redirect is the primary layer
     * when VPN is off, and a backup when VPN is on.
     */
    private fun enforceSafeSearch(packageName: String, url: String) {
        val utils = BlockerPageUtils.getInstance()
        val safeUrl = utils.getSafeSearchUrl(url) ?: return  // null = not a search engine / already safe

        // Throttle: 2-second cooldown per package+URL to prevent loops
        val now = System.currentTimeMillis()
        if (packageName == lastSafeSearchPackage &&
            url == lastSafeSearchUrl &&
            now - lastSafeSearchTimeMs < 2000
        ) {
            return
        }
        lastSafeSearchPackage = packageName
        lastSafeSearchTimeMs = now
        lastSafeSearchUrl = url

        Timber.i("SafeSearch redirect: $url → $safeUrl (pkg=$packageName)")

        // 1. Press HOME to dismiss the unsafe search page
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Open the SafeSearch-enforced URL in the same browser
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Open in the same browser the user was using
                setPackage(packageName)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            // Fallback: if we can't open the safe URL in the same browser
            // (e.g. browser doesn't handle the intent), show block screen
            Timber.w(t, "SafeSearch redirect failed — falling back to block screen")
            launchBlockActivity(packageName, "block_page_default_safe_search_message")
        }
    }

    // ===== URL extraction =====

    /**
     * Try to extract the URL from a browser address bar via accessibility node traversal.
     *
     * Strategy:
     *  1. If we have known view IDs for this browser (e.g. com.android.chrome:id/url_bar),
     *     use them to find the URL field directly (most accurate).
     *  2. Fallback: search any EditText-like node with URL text — used for browsers
     *     added via "Make any browser supported" that don't have known view IDs.
     */
    private fun extractUrlFromEvent(event: AccessibilityEvent, packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        val viewIds = BlockerPageUtils.BROWSER_URL_VIEW_IDS[packageName]

        try {
            // Strategy 1: known view IDs
            if (viewIds != null) {
                for (viewId in viewIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                    if (nodes != null && nodes.isNotEmpty()) {
                        val node = nodes[0]
                        val text = node.text?.toString() ?: continue
                        if (text.isNotBlank()) return text
                    }
                }
            }
            // Strategy 2: fallback — search any EditText-like node with URL text
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
        if (cachedSettingTitles.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        return cachedSettingTitles.any { title ->
            val t = title.lowercase(Locale.ROOT).trim()
            t.isNotBlank() && lower.contains(t)
        }
    }

    /**
     * Checks if an app's class name matches any blocked title keyword.
     *
     * This is a more targeted version of the old isAnyTitleBlocked() — it
     * only matches against the class name (e.g. "BatteryInfoActivity"),
     * not arbitrary event text. This dramatically reduces false positives
     * while still catching apps whose activity names contain blocked keywords.
     *
     * Example: keyword "battery" matches "BatteryInfoActivity" but does NOT
     * match a web page about batteries (whose class name would be something
     * like "org.mozilla.gecko.BrowserApp").
     */
    private fun isClassNameBlocked(packageName: String, className: String): Boolean {
        if (cachedSettingTitles.isEmpty()) return false
        // Don't block our own app
        if (packageName == this.packageName) return false
        // Don't block system UI (would freeze the phone)
        if (packageName == "com.android.systemui") return false

        val lowerClass = className.lowercase(Locale.ROOT)

        for (title in cachedSettingTitles) {
            val t = title.lowercase(Locale.ROOT).trim()
            if (t.isBlank()) continue
            // Only check against class name — NOT event text.
            // Class names are specific (e.g. "BatteryInfoActivity") and
            // don't cause the false positive problems that event text does.
            if (lowerClass.contains(t)) return true
        }
        return false
    }

    /**
     * NopoX-style title-based blocking on ANY app/page.
     *
     * @deprecated Use [isClassNameBlocked] instead — this method checks event
     * text which causes false positives (e.g. keyword "battery" blocks any
     * web page about batteries). Kept for reference only.
     */
    @Deprecated("Use isClassNameBlocked instead — event text matching causes false positives", ReplaceWith("isClassNameBlocked(packageName, className)"))
    private fun isAnyTitleBlocked(packageName: String, className: String, text: String): Boolean {
        if (cachedSettingTitles.isEmpty()) return false
        if (packageName == this.packageName) return false
        if (packageName == "com.android.systemui") return false

        val lowerText = text.lowercase(Locale.ROOT)
        val lowerClass = className.lowercase(Locale.ROOT)

        for (title in cachedSettingTitles) {
            val t = title.lowercase(Locale.ROOT).trim()
            if (t.isBlank()) continue
            if (lowerText.contains(t)) return true
            if (lowerClass.contains(t)) return true
        }
        return false
    }

    /**
     * NEW: Package + Intent name blocking.
     * Blocks apps whose package name OR class/intent name matches user-defined entries.
     *
     * - cachedBlockedPackageNames: exact package name matches (e.g. "com.example.app")
     * - cachedBlockedIntentNames: substring matches on class/intent name (e.g. "MainActivity")
     */
    private fun isPackageOrIntentBlocked(packageName: String, className: String): Boolean {
        // Don't block our own app
        if (packageName == this.packageName) return false
        // Don't block system UI
        if (packageName == "com.android.systemui") return false

        // Check exact package name match
        if (cachedBlockedPackageNames.contains(packageName)) {
            return true
        }

        // Check intent/class name substring match
        val lowerClass = className.lowercase(Locale.ROOT)
        for (intentName in cachedBlockedIntentNames) {
            val i = intentName.lowercase(Locale.ROOT).trim()
            if (i.isNotBlank() && lowerClass.contains(i)) {
                return true
            }
        }

        return false
    }

    /**
     * Detect if the user is on the app info page for our package.
     * This is the page where the Uninstall button lives.
     */
    private fun isAppInfoPage(packageName: String, className: String, text: String): Boolean {
        // Must be a settings app
        if (packageName != "com.android.settings" && !packageName.contains(".settings")) {
            return false
        }

        val lower = text.lowercase(Locale.ROOT)
        val lowerClass = className.lowercase(Locale.ROOT)

        // Check if class name indicates an app info / installed app details page
        if (lowerClass.contains("appinfodashboard") ||
            lowerClass.contains("installedappdetails") ||
            lowerClass.contains("appinfoactivity") ||
            lowerClass.contains("appinfopage")
        ) {
            return true
        }

        // Check if text contains our app name
        try {
            val appName = getString(protect.yourself.R.string.app_name).lowercase(Locale.ROOT)
            if (appName.isNotBlank() && lower.contains(appName)) {
                return true
            }
        } catch (_: Throwable) {}

        // Check against device admin text patterns (localized)
        val deviceAdminTexts = BlockerPageUtils.DEVICE_ADMIN_TEXTS_TO_MATCH
        for (matchText in deviceAdminTexts) {
            if (lower.contains(matchText.lowercase(Locale.ROOT))) {
                return true
            }
        }

        return false
    }

    /**
     * Detect power menu / ultra power saving mode.
     * Uses localized strings from BlockerPageUtils.HUAWEI_ULTRA_POWER_SAVING_TEXTS.
     */
    private fun isPowerMenu(className: String, packageName: String, text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).replace(" ", "")
        val lowerClass = className.lowercase(Locale.ROOT)

        // Detect power menu
        if (lowerClass.contains("powerdialog") ||
            lowerClass.contains("shutdownactivity") ||
            lowerClass.contains("globalactions") ||
            lowerClass.contains("powermenu")
        ) {
            return true
        }

        // Detect ultra power saving (localized)
        for (powerText in BlockerPageUtils.HUAWEI_ULTRA_POWER_SAVING_TEXTS) {
            val normalized = powerText.lowercase(Locale.ROOT).replace(" ", "")
            if (normalized.isNotBlank() && lower.contains(normalized)) {
                return true
            }
        }

        return false
    }

    /**
     * Robust browser detection using PackageManager intent-filter inspection.
     *
     * An app is considered a browser if it declares an intent filter for
     * `android.intent.action.VIEW` with category `android.intent.category.BROWSABLE`
     * and scheme `http` or `https`.
     *
     * Falls back to package-name signature matching for older apps that may not
     * declare the standard intent filter but are still browsers.
     *
     * Cached per-package for performance.
     */
    private fun isBrowserPackageDetected(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == this.packageName) return false  // Don't block self
        if (packageName == "com.android.systemui") return false

        // Check cache first
        browserCache[packageName]?.let { return it }

        val isBrowser = try {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://example.com")
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            }
            val resolved = pm.queryIntentActivities(intent, 0)
            val isBrowserByIntentFilter = resolved.any { it.activityInfo.packageName == packageName }
            isBrowserByIntentFilter || isBrowserByPackageSignature(packageName)
        } catch (t: Throwable) {
            Timber.v(t, "Browser detection failed for $packageName")
            isBrowserByPackageSignature(packageName)
        }

        browserCache[packageName] = isBrowser
        return isBrowser
    }

    private fun isBrowserByPackageSignature(packageName: String): Boolean {
        // Common browser package signatures (fallback when intent filter check fails)
        return packageName.contains("browser") ||
            packageName.contains("chrome") ||
            packageName.contains("firefox") ||
            packageName.contains("opera") ||
            packageName.contains("brave") ||
            packageName.contains("edge") ||
            packageName.contains("emmx") ||
            packageName.contains("vivaldi") ||
            packageName.contains("duckduckgo") ||
            packageName.contains("samsung.android.app.sbrowser") ||
            packageName.contains("sec.android.app.sbrowser") ||
            packageName.contains("mi.globalbrowser") ||
            packageName.contains("bromite") ||
            packageName.contains("fennec") ||
            packageName.contains("torbrowser") ||
            packageName.contains("kiwibrowser")
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        // Legacy method kept for backward compatibility with other callers
        return isBrowserPackageDetected(packageName)
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

        // Launch PornBlockActivity on top of the offending app.
        //
        // IMPORTANT: Do NOT press GLOBAL_ACTION_HOME here. Pressing HOME before
        // startActivity was causing the block screen to be immediately dismissed
        // by the system's activity transition — the user never saw the explanation
        // message, the content just "closed immediately". Pressing HOME after
        // startActivity is also wrong because it would dismiss the block activity
        // itself.
        //
        // The block activity launches with FLAG_ACTIVITY_NEW_TASK +
        // FLAG_ACTIVITY_CLEAR_TOP so it appears on top. When the user taps Close,
        // the block activity finishes and the system returns to the home screen
        // (not back to the offending app, because CLEAR_TOP cleared that task).
        //
        // The offending app remains in the background but the user has to
        // actively switch to it, at which point the accessibility service will
        // re-block it.
        val intent = Intent(this, protect.yourself.features.blockerPage.ui.PornBlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(EXTRA_BLOCK_PACKAGE, packageName)
            putExtra(EXTRA_BLOCK_MESSAGE_KEY, messageResKey)
        }
        try {
            startActivity(intent)
            Timber.i("Block screen launched for pkg=$packageName messageKey=$messageResKey")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to launch PornBlockActivity")
            // Fallback: if we can't launch the block activity, press HOME to
            // at least dismiss the offending content
            try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        }
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
                isSafeSearchOn = switchValues.isSafeSearchSwitchOn()
                isBlockNewInstallOn = switchValues.isBlockNewInstallAppsSwitchOn()
                isBlockInAppBrowsersOn = switchValues.isBlockInAppBrowsersSwitchOn()
                isBlockUnsupportedBrowsersOn = switchValues.isBlockUnsupportedBrowsersSwitchOn()
                isBlockSettingsByTitleOn = switchValues.isBlockSettingPageByTitleSwitchOn()
                isPreventUninstallOn = switchValues.isPreventUninstallSwitchOn()
                isBlockPhoneRebootOn = switchValues.isBlockPhoneRebootSwitchOn()
                // BLOCK_NOTIFICATION_DRAWER + BLOCK_RECENT_APPS removed from UI

                // Load setting titles to block from keyword DB
                cachedSettingTitles = db.selectedKeywordDao()
                    .getSelectedByIdentifier(SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS.value)
                    .map { it.keyword }
                    .filter { it.isNotBlank() }

                // Load package + intent name blocking data
                isBlockPackageIntentOn = switchValues.isBlockPackageIntentSwitchOn()
                cachedBlockedPackageNames = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCKED_PACKAGE_NAMES.value)
                    .map { it.packageName }.toSet()
                cachedBlockedIntentNames = db.selectedKeywordDao()
                    .getSelectedByIdentifier(SelectedKeywordIdentifier.BLOCKED_INTENT_NAMES.value)
                    .map { it.keyword }.toSet()

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
                cachedUnsupportedBrowserWhitelist = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER.value)
                    .map { it.packageName }.toSet()

                Timber.i("Blocking config refreshed: ${cachedBlockKeywords.size} block keywords, " +
                    "${cachedWhitelistKeywords.size} whitelist keywords, " +
                    "${cachedBlockApps.size} block apps, " +
                    "${cachedUnsupportedBrowserWhitelist.size} whitelisted browsers, " +
                    "unsupportedBrowsersOn=$isBlockUnsupportedBrowsersOn, " +
                    "packageIntentOn=$isBlockPackageIntentOn")
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
