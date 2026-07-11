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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import protect.yourself.core.appCoroutineScope
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

    private val serviceScope = appCoroutineScope(
        scopeName = "MyAccessibilityService",
        dispatcher = kotlinx.coroutines.Dispatchers.Default,
        context = this
    )

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
    // Anti-circumvention switches — re-added (UP-03 fix). These were previously
    // removed from the UI but the detection logic was kept as dead code. They
    // are now wired via loadAllConfig() but default to OFF (the user can
    // enable them via the database or a future UI toggle).
    private var isBlockNotificationDrawerOn = false
    private var isBlockRecentAppsOn = false
    private var cachedSettingTitles: List<String> = emptyList()
    // Package + intent name blocking
    private var cachedBlockedPackageNames: Set<String> = emptySet()
    private var cachedBlockedIntentNames: Set<String> = emptySet()
    private var isBlockPackageIntentOn = false
    // AB-01 fix: Stop Me state is now a persisted end-time timestamp, not an
    // in-memory boolean. This survives process death (reboot, force-stop, OEM
    // killing the service). On every event we check
    // `System.currentTimeMillis() < stopMeEndTime`. NopoX uses the same pattern
    // (`private static long stopMeEndTime`).
    // When stopMeEndTime == 0L, no session is active.
    @Volatile
    private var stopMeEndTime: Long = 0L

    // KB-22 fix: PackageManager-level browser detection cache. Uses
    // ConcurrentHashMap instead of mutableMapOf because onAccessibilityEvent
    // fires on the main thread while refreshBlockingConfig runs on
    // Dispatchers.Default — concurrent access to a plain MutableMap can
    // cause ConcurrentModificationException.
    private val browserCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private var lastBlockedPackage: String? = null
    private var lastBlockTimeMs: Long = 0

    // SafeSearch redirect throttle — prevents redirect loops + rapid-fire intents
    private var lastSafeSearchPackage: String? = null
    private var lastSafeSearchTimeMs: Long = 0
    private var lastSafeSearchUrl: String? = null

    /**
     * BlockOverlayManager — WindowManager overlay for non-dismissible block screens.
     *
     * NopoX uses a WindowManager overlay (TYPE_APPLICATION_OVERLAY) instead of
     * an Activity because Activities can be dismissed via Home/Recents/Back
     * gestures, defeating uninstall prevention. The overlay is created lazily
     * on first use.
     */
    @Volatile
    private var blockOverlayManager: BlockOverlayManager? = null

    /**
     * Get-or-create the BlockOverlayManager. Lazily initialised because it
     * needs the service context which isn't available at construction time.
     */
    private fun getBlockOverlayManager(): BlockOverlayManager {
        return blockOverlayManager ?: synchronized(this) {
            blockOverlayManager ?: BlockOverlayManager(this).also {
                blockOverlayManager = it
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("Accessibility service connected")
        instance = this
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()
            ?.logBreadcrumb("AccessibilityService", "onServiceConnected")
        configureService()
        refreshBlockingConfig()
        // Re-arm persistence the moment the service starts — this catches the
        // case where the service was disabled, then re-enabled by the user via
        // Settings. selfHealSafe is a no-op if WRITE_SECURE_SETTINGS isn't granted.
        try {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this)
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe in onServiceConnected failed")
        }
        // NopoX does NOT show a Toast here — user feedback is exclusively via
        // the block overlay. Toasts from the accessibility service are an
        // anti-pattern (they're noisy and can be missed). Removed per NopoX.
    }

    /**
     * Called by the system when the last client unbinds from this service.
     *
     * This is one of the earliest signals that Android may be about to kill
     * or disable the service. NopoX calls `selfHealSafe` here for the same
     * reason — if WRITE_SECURE_SETTINGS is granted, we can re-arm ourselves
     * in the enabled list before the system finishes tearing us down.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        try {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this)
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe in onUnbind failed")
        }
        return super.onUnbind(intent)
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
        // Last-chance self-heal before the service is fully destroyed.
        // If WRITE_SECURE_SETTINGS is granted, this writes us back into
        // enabled_accessibility_services so the system will restart us.
        try {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this)
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe in onDestroy failed")
        }
        // Hide the block overlay if it's visible (otherwise it would persist
        // after the service is destroyed, locking the user out).
        try {
            blockOverlayManager?.hideBlockOverlay()
        } catch (_: Throwable) {}
        // Cancel the service scope to prevent coroutine leaks (Phase 6 P0 fix)
        try { serviceScope.cancel() } catch (_: Throwable) {}
        // NopoX does NOT show a Toast here — removed per NopoX pattern.
        // User feedback is exclusively via notifications + the block overlay.
    }

    // ===== Window state change handler =====

    /**
     * KB-07 fix: returns true if the event is stale (older than
     * [STALE_EVENT_THRESHOLD_MS]). Stale events can occur when the system
     * delays delivery — matching against a stale URL/text would block the
     * user based on a page they've already navigated away from.
     */
    private fun isStaleEvent(event: AccessibilityEvent): Boolean {
        val now = System.currentTimeMillis()
        val eventTime = event.eventTime
        return eventTime > 0 && (now - eventTime) > STALE_EVENT_THRESHOLD_MS
    }

    private fun handleWindowStateChange(packageName: String, event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        val text = event.text?.joinToString(" ").orEmpty()
        Timber.v("WindowStateChange pkg=$packageName class=$className text=$text")

        // Skip our own app — we never want to block our own block screen,
        // app lock, or main activity. This also prevents infinite re-block loops
        // where the block activity launch triggers a WINDOW_STATE_CHANGED event
        // that re-triggers blocking.
        if (packageName == this.packageName) return

        // ===== Anti-circumvention checks (run FIRST, before prevent-uninstall) =====
        // These MUST run before the prevent-uninstall check because they block
        // the escape paths the user would use to bypass prevent-uninstall:
        //   - Power menu → reboot to safe mode → disable accessibility
        //   - Notification drawer → quick settings tile → Settings → Accessibility
        //   - Recent apps → swipe our app away / force-stop
        //
        // UP-03 fix: do NOT blanket-skip SystemUI here. The AOSP GlobalActionsDialog
        // (power menu), notification panel, and recents overview all come from
        // com.android.systemui. The individual is*() helpers below are
        // class-name-scoped, so they won't false-positive on unrelated SystemUI
        // windows. Only skip SystemUI for the content-blocking checks below.

        // Block phone reboot: detect power menu / ultra power saving
        // (runs first — if the user reaches the power menu, they can reboot
        // to safe mode and bypass everything)
        if (isBlockPhoneRebootOn && isPowerMenu(className, packageName, text)) {
            launchBlockActivity(packageName, "block_phone_reboot_bw_message")
            return
        }

        // Block notification drawer — prevents access to quick settings tiles
        // (Settings gear, airplane mode, etc.)
        if (isBlockNotificationDrawerOn && isNotificationDrawer(className, packageName)) {
            launchBlockActivity(packageName, "block_page_default_notification_drawer_message")
            return
        }

        // Block recent apps — prevents force-stop / uninstall from recents
        if (isBlockRecentAppsOn && isRecentApps(className, packageName)) {
            launchBlockActivity(packageName, "block_recent_apps_bw_message")
            return
        }

        // ===== Now skip SystemUI for the remaining content-blocking checks =====
        // (settings page blocking, app blocking, browser blocking, etc. should
        // never fire on SystemUI windows — they're not user content)
        if (packageName == "com.android.systemui") return

        // ===== Prevent Uninstall: detect when user is on our app info page =====
        // This is the core anti-uninstall check. Runs after anti-circumvention
        // so the escape paths are already blocked.
        if (isPreventUninstallOn && isAppInfoPage(packageName, className, text)) {
            launchBlockActivity(packageName, "block_page_default_pu_message")
            return
        }

        // ===== Content-blocking checks =====

        // Settings page title blocking (NopoX-style: checks settings pages)
        if (isBlockSettingsByTitleOn && isSettingsPage(packageName, text)) {
            launchBlockActivity(packageName, "block_page_default_system_keyword_message")
            return
        }

        // Title-based blocking on ANY app (NopoX-style: also checks all app titles)
        if (isBlockSettingsByTitleOn && isAnyTitleBlocked(packageName, className, text)) {
            launchBlockActivity(packageName, "block_page_default_system_keyword_message")
            return
        }

        // Package + Intent name blocking (NEW feature)
        if (isBlockPackageIntentOn) {
            if (isPackageOrIntentBlocked(packageName, className)) {
                launchBlockActivity(packageName, "block_page_default_block_apps_message")
                return
            }
        }

        // Block apps (blocklist).
        // AB-02 fix: removed the `isPornBlockerOn` gate. Blocklist apps should
        // work independently of the Porn Blocker switch — the user explicitly
        // added these apps to the blocklist, so they should be blocked
        // regardless of whether URL keyword matching is enabled.
        if (cachedBlockApps.contains(packageName)) {
            launchBlockActivity(packageName, "block_page_default_block_apps_message")
            return
        }

        // Block new install apps
        if (isBlockNewInstallOn && cachedNewInstallBlockApps.contains(packageName)) {
            launchBlockActivity(packageName, "block_page_default_new_install_message")
            return
        }

        // Block unsupported browsers
        // NopoX behavior: when this switch is ON, any browser NOT in the supported
        // list AND NOT in the unsupported-browser whitelist gets blocked on launch.
        // A "browser" is defined as an app that handles http/https URLs (via intent filter)
        // OR matches known browser package signatures.
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

        // Stop Me: block apps not in whitelist.
        // AB-01 fix: check persisted end-time instead of in-memory boolean.
        // This survives process death — if the service is killed and
        // reconnected, loadAllConfig reloads stopMeEndTime from the DB.
        val now = System.currentTimeMillis()
        if (stopMeEndTime > 0 && now < stopMeEndTime && !cachedStopMeWhitelist.contains(packageName)) {
            launchBlockActivity(packageName, "block_page_default_stop_me_message")
            return
        } else if (stopMeEndTime > 0 && now >= stopMeEndTime) {
            // Session has expired — clear the timestamp to avoid re-checking.
            stopMeEndTime = 0L
        }
    }

    // ===== Content change handler =====

    private fun handleContentChange(packageName: String, event: AccessibilityEvent) {
        // URL scraping happens if EITHER porn blocker OR SafeSearch is on.
        // Previously, this was gated behind isPornBlockerOn alone, which meant
        // SafeSearch enforcement stopped working if the user turned off the
        // Porn Blocker but left SafeSearch on.
        if (!isPornBlockerOn && !isSafeSearchOn) return

        // Scrape URLs from any detected browser. The "supported browsers" concept
        // was removed — the accessibility service now scrapes from any app that
        // declares a browser intent filter (or matches known browser signatures).
        if (isBrowserPackageDetected(packageName)) {
            val url = extractUrlFromEvent(event, packageName)
            if (url != null && url.isNotBlank()) {
                handleUrlDetected(packageName, url)
            }
            return  // URL scrape takes priority — don't also do content-text match
        }

        // KB-01 fix: content-text keyword matching for non-browser apps.
        // If this package is NOT a browser we scrape URLs from, check the page
        // content text against the blocklist. This catches apps that display
        // pornographic text in their UI (e.g. a search-results page in a
        // social app) even though we can't extract a URL.
        //
        // We only do this for apps that are NOT in the supported-browser list
        // (browsers are handled by URL scraping above) and NOT system UI / our
        // own app. We also skip if the event is stale (KB-07 fix).
        if (packageName != this.packageName &&
            packageName != "com.android.systemui" &&
            !isStaleEvent(event)
        ) {
            val text = extractTextFromEvent(event, packageName)
            if (text.isNotBlank() && text.length < MAX_CONTENT_TEXT_LENGTH) {
                val utils = BlockerPageUtils.getInstance()
                val (found, matchedKeyword) = utils.isDetectWord(text, cachedBlockKeywords)
                if (found) {
                    launchBlockActivity(
                        packageName,
                        "block_page_default_porn_blocker_message",
                        matchedKeyword
                    )
                }
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

        // Block keyword match — KB-05: use the renamed matchKeywordInUrl.
        // KB-19: capture the matched keyword and pass it to the block screen.
        val (found, matchedKeyword) = utils.matchKeywordInUrl(decoded, cachedBlockKeywords)
        if (found) {
            launchBlockActivity(
                packageName,
                "block_page_default_porn_blocker_message",
                matchedKeyword
            )
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
    /**
     * KB-23 fix: strip the query string (and fragment) from a URL for throttle
     * comparison. `https://google.com/search?q=porn&t=12345` → `https://google.com/search`.
     */
    private fun stripQuery(url: String): String {
        val qIdx = url.indexOf('?')
        val fIdx = url.indexOf('#')
        val cut = when {
            qIdx >= 0 && fIdx >= 0 -> minOf(qIdx, fIdx)
            qIdx >= 0 -> qIdx
            fIdx >= 0 -> fIdx
            else -> url.length
        }
        return url.substring(0, cut)
    }

    private fun enforceSafeSearch(packageName: String, url: String) {
        val utils = BlockerPageUtils.getInstance()
        val safeUrl = utils.getSafeSearchUrl(url) ?: return  // null = not a search engine / already safe

        // KB-23 fix: throttle by URL WITHOUT the query string. The old code
        // compared the full URL, so a URL with a changing query parameter
        // (e.g. `?t=12345` timestamp) would never match the throttle, causing
        // redirect loops. We strip the query before comparing so the throttle
        // fires on the URL path + host only.
        val urlForThrottle = stripQuery(url)
        val now = System.currentTimeMillis()
        if (packageName == lastSafeSearchPackage &&
            urlForThrottle == lastSafeSearchUrl &&
            now - lastSafeSearchTimeMs < 2000
        ) {
            return
        }
        lastSafeSearchPackage = packageName
        lastSafeSearchTimeMs = now
        lastSafeSearchUrl = urlForThrottle

        Timber.i("SafeSearch redirect: $url → $safeUrl (pkg=$packageName)")

        // Open the SafeSearch-enforced URL in the same browser.
        //
        // Do NOT press GLOBAL_ACTION_HOME before startActivity — that causes
        // a race condition where HOME dismisses the browser before the safe
        // URL intent fires, or the safe URL opens behind the home screen.
        // The new URL loads in the same browser tab, replacing the unsafe
        // search page. The user sees the SafeSearch results directly.
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

    private fun findUrlInNode(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null) return null
        // KB-20 fix: depth limit to prevent StackOverflow on deeply nested trees.
        if (depth > MAX_NODE_DEPTH) return null
        val text = node.text?.toString() ?: ""
        if (text.isNotBlank() && (text.startsWith("http") || text.contains("://"))) {
            return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findUrlInNode(child, depth + 1)
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

    /**
     * Safe wrapper around [collectText] — catches SecurityException and other
     * Throwable that can be thrown when accessing recycled nodes or nodes from
     * a different package (NopoX pattern). Returns silently on failure.
     *
     * UP-04 fix: needed because [isAppInfoPage] does node-tree traversal as a
     * fallback when event.text is empty.
     */
    private fun safeCollectText(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        maxDepth: Int
    ) {
        try {
            collectText(node, sb, depth, maxDepth)
        } catch (_: Throwable) {
            // Recycled node / SecurityException — silent fallback
        }
    }

    /**
     * Safe wrapper around [AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] —
     * catches SecurityException and returns empty list. NopoX wraps every
     * findAccessibilityNodeInfosByViewId call this way.
     *
     * UP-04 fix.
     */
    private fun safeFindByIds(viewId: String): List<AccessibilityNodeInfo> {
        return try {
            rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Safe wrapper around [AccessibilityNodeInfo.findAccessibilityNodeInfosByText] —
     * catches SecurityException and returns empty list.
     */
    private fun safeFindByText(text: String): List<AccessibilityNodeInfo> {
        return try {
            rootInActiveWindow?.findAccessibilityNodeInfosByText(text) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ===== Detection helpers =====

    /**
     * Detect the notification drawer / quick settings shade.
     *
     * UP-03 fix: no longer dead code — now wired in [handleWindowStateChange]
     * BEFORE the SystemUI blanket-skip.
     *
     * UP-04 fix: wrapped in try/catch (NopoX pattern).
     */
    private fun isNotificationDrawer(className: String, packageName: String): Boolean {
        return try {
            val lower = className.lowercase(Locale.ROOT)
            if (lower.contains("statusbar") ||
                lower.contains("notification") ||
                lower.contains("quicksettings") ||
                lower.contains("notificationpanel") ||
                lower.contains("notificationshade") ||
                lower.contains("control_panel") ||
                lower.contains("settings_shortcut") ||
                (packageName == "com.android.systemui" && lower.contains("shade"))
            ) {
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Detect the recent apps / overview screen.
     *
     * UP-03 fix: no longer dead code — now wired in [handleWindowStateChange]
     * BEFORE the SystemUI blanket-skip.
     *
     * UP-04 fix: wrapped in try/catch (NopoX pattern).
     */
    private fun isRecentApps(className: String, packageName: String): Boolean {
        return try {
            val lower = className.lowercase(Locale.ROOT)
            if (lower.contains("recents") ||
                lower.contains("recentapps") ||
                lower.contains("overview") ||
                lower.contains("taskview") ||
                lower.contains("overview_panel") ||
                lower.contains("recent_apps")
            ) {
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
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
     * NopoX-style title-based blocking on ANY app/page.
     * Checks if the event text contains any blocked title keyword.
     * This goes beyond settings — it blocks ANY window whose title matches.
     *
     * KB-09 fix: we NO LONGER match against the className. Class names are
     * implementation details and change between app versions. A keyword like
     * "settings" matches almost every settings-related class name, causing
     * false positives (e.g. blocking the user's launcher because its class is
     * com.android.launcher3.settings.SettingsActivity). We now match against
     * the event text only — this is what NopoX does and is the correct
     * behaviour. The className parameter is kept in the signature for backward
     * compatibility but is ignored.
     */
    private fun isAnyTitleBlocked(packageName: String, className: String, text: String): Boolean {
        if (cachedSettingTitles.isEmpty()) return false
        // Don't block our own app
        if (packageName == this.packageName) return false
        // Don't block system UI (would freeze the phone)
        if (packageName == "com.android.systemui") return false

        val lowerText = text.lowercase(Locale.ROOT)

        for (title in cachedSettingTitles) {
            val t = title.lowercase(Locale.ROOT).trim()
            if (t.isBlank()) continue
            // KB-09: only check against event text — NOT class name.
            if (lowerText.contains(t)) return true
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
     *
     * UP-04/UP-05/UP-07 fix: enhanced with:
     *   - try/catch around every branch (NopoX pattern — safe fallback is to
     *     not block, because a false positive on a legitimate Settings page
     *     is worse than a false negative)
     *   - OEM-specific class name checks (Samsung, MIUI, Huawei, OnePlus, OPLUS)
     *   - View-ID-based detection via [safeFindByIds] (NopoX uses this heavily)
     *   - Node-tree text traversal via [safeCollectText] as a fallback when
     *     the event text is empty (some OEMs don't populate event.text)
     *   - App-name text search is NO LONGER gated behind a class-name guard
     *     (UP-18 fix) — the class-name guard defeated the purpose because
     *     many OEMs use custom class names that don't contain "appinfo"
     */
    private fun isAppInfoPage(packageName: String, className: String, text: String): Boolean {
        return try {
            isAppInfoPageUnsafe(packageName, className, text)
        } catch (t: Throwable) {
            Timber.w(t, "isAppInfoPage threw — safe fallback is false (don't block)")
            false
        }
    }

    private fun isAppInfoPageUnsafe(packageName: String, className: String, text: String): Boolean {
        // Must be a settings app OR an OEM settings variant
        if (!isSettingsPackage(packageName)) return false

        val lower = text.lowercase(Locale.ROOT)
        val lowerClass = className.lowercase(Locale.ROOT)

        // ===== Check 1: class name indicates an app info / installed app details page =====
        // Covers AOSP + most OEMs
        if (lowerClass.contains("appinfodashboard") ||
            lowerClass.contains("installedappdetails") ||
            lowerClass.contains("appinfoactivity") ||
            lowerClass.contains("appinfopage") ||
            lowerClass.contains("appinfo")
        ) {
            return true
        }

        // OEM-specific class names (UP-07 fix)
        // Samsung: AppInfoDashboardActivity, InstalledAppDetailsActivity
        // MIUI: AppInfoActivity, AppDetailsActivity
        // Huawei: AppInfoActivity, AppDetailsActivity
        // OnePlus/OPLUS: AppInfoActivity
        if (lowerClass.contains("appdetails") ||
            lowerClass.contains("appdetail") ||
            lowerClass.contains("installedappdetails") ||
            lowerClass.contains("appinfodashboard")
        ) {
            return true
        }

        // ===== Check 2: text contains our app name =====
        // UP-18 fix: NO class-name guard. The guard was defeating the purpose
        // because many OEMs use custom class names. The risk of false positives
        // (matching "Protect Yourself" on an unrelated settings page) is low
        // because our app name is distinctive.
        // AB-03 fix (from main): to further reduce false positives, require an
        // uninstall-related keyword in addition to the app name.
        try {
            val appName = getString(protect.yourself.R.string.app_name).lowercase(Locale.ROOT)
            if (appName.isNotBlank() && lower.contains(appName)) {
                // Additional check: the text must ALSO contain an uninstall-related
                // keyword, to avoid matching our app name on unrelated pages
                // (e.g. accessibility settings mentioning our app).
                if (lower.contains("uninstall") ||
                    lower.contains("disable") ||
                    lower.contains("force stop") ||
                    lower.contains("forcestop") ||
                    lower.contains("deactivate") ||
                    lower.contains("remove") ||
                    lower.contains("clear data") ||
                    lower.contains("cleardata") ||
                    lower.contains("storage") ||
                    lower.contains("permissions")
                ) {
                    return true
                }
            }
        } catch (_: Throwable) {}

        // ===== Check 3: device admin text patterns =====
        // These match the Device Admin deactivation page (which is where the
        // user goes to deactivate our Device Admin before uninstalling).
        // AB-03 fix (from main): gate behind app-info class-name check to avoid
        // matching "admin"/"deactivate" on unrelated settings pages.
        if (lowerClass.contains("appinfo") || lowerClass.contains("appdetails") ||
            lowerClass.contains("appinfodashboard") || lowerClass.contains("installedappdetails")) {
            val deviceAdminTexts = BlockerPageUtils.DEVICE_ADMIN_TEXTS_TO_MATCH
            for (matchText in deviceAdminTexts) {
                try {
                    if (lower.contains(matchText.lowercase(Locale.ROOT))) {
                        return true
                    }
                } catch (_: Throwable) {}
            }
        }

        // ===== Check 4: force-stop text patterns =====
        // The "Force stop" button appears on every app info page.
        val forceStopTexts = BlockerPageUtils.FORCE_STOP_TEXTS_TO_MATCH
        for (matchText in forceStopTexts) {
            try {
                if (lower.contains(matchText.lowercase(Locale.ROOT))) {
                    // Force-stop text alone isn't enough — it appears on every
                    // app info page. Only match if our app name is also present
                    // OR if we're on a settings page with an app-info class name.
                    try {
                        val appName = getString(protect.yourself.R.string.app_name).lowercase(Locale.ROOT)
                        if (appName.isNotBlank() && lower.contains(appName)) {
                            return true
                        }
                    } catch (_: Throwable) {}
                    if (lowerClass.contains("appinfo") || lowerClass.contains("appdetail")) {
                        return true
                    }
                }
            } catch (_: Throwable) {}
        }

        // ===== Check 5: node-tree text traversal (fallback) =====
        // Some OEMs don't populate event.text. Walk the node tree to collect
        // text and re-check.
        if (lower.isBlank()) {
            try {
                val root = rootInActiveWindow ?: return false
                val sb = StringBuilder()
                safeCollectText(root, sb, 0, 5)
                val nodeText = sb.toString().lowercase(Locale.ROOT)
                if (nodeText.isNotBlank()) {
                    try {
                        val appName = getString(protect.yourself.R.string.app_name).lowercase(Locale.ROOT)
                        if (appName.isNotBlank() && nodeText.contains(appName) &&
                            (nodeText.contains("uninstall") || nodeText.contains("force stop") ||
                                nodeText.contains("deactivate"))) {
                            return true
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        return false
    }

    /**
     * Check if the package is a settings package (AOSP or OEM variant).
     * UP-07 fix: covers Samsung, MIUI, Huawei, OnePlus, OPLUS settings packages.
     */
    private fun isSettingsPackage(packageName: String): Boolean {
        if (packageName == "com.android.settings") return true
        if (packageName.contains(".settings")) return true
        // OEM settings packages
        if (packageName == "com.miui.securitycenter") return true
        if (packageName == "com.android.settings.miui") return true
        if (packageName == "com.samsung.android.settings") return true
        if (packageName == "com.huawei.systemmanager") return true
        if (packageName == "com.coloros.safecenter") return true
        if (packageName == "com.oppo.safe") return true
        if (packageName == "com.iqiyi.terms") return true  // some OEMs
        return false
    }

    /**
     * Detect power menu / ultra power saving mode.
     * Uses localized strings from BlockerPageUtils.HUAWEI_ULTRA_POWER_SAVING_TEXTS.
     *
     * UP-04 fix: wrapped in try/catch (NopoX pattern).
     */
    private fun isPowerMenu(className: String, packageName: String, text: String): Boolean {
        return try {
            val lower = text.lowercase(Locale.ROOT).replace(" ", "")
            val lowerClass = className.lowercase(Locale.ROOT)
            val lowerPkg = packageName.lowercase(Locale.ROOT)

            // Detect power menu
            if (lowerClass.contains("powerdialog") ||
                lowerClass.contains("shutdownactivity") ||
                lowerClass.contains("globalactions") ||
                lowerClass.contains("powermenu") ||
                lowerClass.contains("shutdownthread") ||
                lowerClass.contains("globalactionsdialog") ||
                lowerClass.contains("globalactionsimpl")
            ) {
                return true
            }

            // Detect power menu by package name (OEM-specific power dialog packages).
            // AB-04 fix (from main): use exact package-name matching instead of substring.
            // The old `contains("shutdown")` matched `com.example.shutdown.helper`
            // (not a power menu) as a false positive.
            val knownPowerMenuPackages = setOf(
                "com.android.server",          // Android system global actions
                "com.miui.powermanager",       // Xiaomi power manager
                "com.huawei.systemmanager",    // Huawei power manager
                "com.samsung.android.app.powermain", // Samsung power menu
                "com.oppo.powermanager",       // OPPO power manager
                "com.coloros.powermanager",    // ColorOS power manager
                "com.android.settings"         // Settings can show shutdown intent
            )
            if (lowerPkg in knownPowerMenuPackages.map { it.lowercase(Locale.ROOT) }.toSet()) {
                return true
            }

            // Detect ultra power saving (localized)
            for (powerText in BlockerPageUtils.HUAWEI_ULTRA_POWER_SAVING_TEXTS) {
                val normalized = powerText.lowercase(Locale.ROOT).replace(" ", "")
                if (normalized.isNotBlank() && lower.contains(normalized)) {
                    return true
                }
            }

            false
        } catch (_: Throwable) {
            false
        }
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
            // NopoX uses http://www.google.com for browser detection.
            // Some older browsers only declare intent filters for http://
            // (not https://), so using http:// catches more browsers.
            // We check both http and https to be thorough.
            val httpIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("http://www.google.com")
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            }
            val httpsIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://example.com")
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            }
            val httpResolved = pm.queryIntentActivities(httpIntent, 0)
            val httpsResolved = pm.queryIntentActivities(httpsIntent, 0)
            val isBrowserByIntentFilter = httpResolved.any { it.activityInfo.packageName == packageName } ||
                httpsResolved.any { it.activityInfo.packageName == packageName }
            isBrowserByIntentFilter || isBrowserByPackageSignature(packageName)
        } catch (t: Throwable) {
            Timber.v(t, "Browser detection failed for $packageName")
            isBrowserByPackageSignature(packageName)
        }

        browserCache[packageName] = isBrowser
        return isBrowser
    }

    /**
     * KB-21 fix: exact package-name matching instead of substring matching.
     * The old `contains("browser")` would match `com.example.browser.helper`
     * (not a browser) as a false positive. We now match against a set of
     * known browser package-name prefixes (the part before the first dot
     * after the vendor, or the full package for short ones).
     *
     * This is a fallback — the primary detection is via PackageManager
     * intent-filter inspection (isBrowserByIntentFilter above).
     */
    private fun isBrowserByPackageSignature(packageName: String): Boolean {
        // Known browser package prefixes. We check if the packageName starts
        // with any of these (e.g. "com.android.chrome" starts with
        // "com.android.chrome"). This avoids the false-positive problem where
        // `contains("browser")` matched non-browser packages.
        val knownBrowserPrefixes = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "org.mozilla.fennec_fdroid",
            "org.mozilla.firefox_beta",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.mi.globalbrowser",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android",
            "org.bromite.bromite",
            "org.torproject.torbrowser",
            "com.kiwibrowser.browser",
            "mark.via",
            "com.junkboat.brave"
        )
        return knownBrowserPrefixes.any { packageName.startsWith(it) }
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        // Legacy method kept for backward compatibility with other callers
        return isBrowserPackageDetected(packageName)
    }

    // ===== Block activity launcher =====

    /**
     * Launch the block screen on top of the offending app.
     *
     * ## Strategy (UP-01 fix)
     *
     * 1. **Try the WindowManager overlay first** via [BlockOverlayManager].
     *    This is non-dismissible (cannot be dismissed by Home/Recents/Back
     *    gestures) and runs a 500ms HOME×5 + BACK×1 kill timer to actually
     *    kill the offending activity underneath. This is what NopoX does.
     *
     * 2. **If the overlay cannot be shown** (SYSTEM_ALERT_WINDOW not granted,
     *    WindowManager unavailable, or addView throws), fall back to the
     *    Activity-based `PornBlockActivity`. This IS dismissible but is
     *    better than nothing.
     *
     * 3. **If the Activity fallback also fails**, press GLOBAL_ACTION_HOME
     *    as a last resort to at least dismiss the offending content.
     *
     * ## Throttling (UP-10 fix)
     *
     * NopoX uses a single-flight guard (`if (isPageShow) return`) — only
     * one block screen visible at a time. There is NO per-package throttle
     * because that creates a bypass window (the user could quickly switch
     * between two blocked apps to bypass a per-package throttle).
     *
     * We replicate this: the BlockOverlayManager has its own single-flight
     * guard via AtomicBoolean. The Activity fallback uses a global throttle
     * (300ms) to prevent storm-launching activities.
     */
    private fun launchBlockActivity(
        packageName: String,
        messageResKey: String,
        matchedKeyword: String? = null
    ) {
        // Log the block attempt to CrashLogger for diagnostics
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
            "BlockAttempt",
            "pkg=$packageName messageKey=$messageResKey keyword=$matchedKeyword"
        )

        // ===== Strategy 1: WindowManager overlay (preferred) =====
        try {
            val mgr = getBlockOverlayManager()
            if (mgr.canDrawOverlays()) {
                val shown = mgr.showBlockOverlay(packageName, messageResKey, matchedKeyword)
                if (shown) {
                    Timber.i("Block overlay shown for pkg=$packageName messageKey=$messageResKey keyword=$matchedKeyword")
                    return
                }
                Timber.w("Overlay manager returned false — falling back to Activity")
            } else {
                Timber.w("SYSTEM_ALERT_WINDOW not granted — falling back to Activity. " +
                    "User should grant it via Settings → Apps → Protect Yourself → " +
                    "Display over other apps.")
                // Log to CrashLogger so the user can see the recommendation
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                    "BlockFallback",
                    "Overlay permission missing — using Activity fallback for pkg=$packageName"
                )
            }
        } catch (t: Throwable) {
            Timber.e(t, "BlockOverlayManager threw — falling back to Activity")
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                throwable = t,
                severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                tag = "BlockOverlay",
                message = "Overlay show failed for pkg=$packageName",
                extraContext = mapOf("packageName" to packageName, "messageKey" to messageResKey)
            )
        }

        // ===== Strategy 2: Activity fallback =====
        // Throttle the Activity fallback (300ms global) to prevent storm-launching.
        val now = System.currentTimeMillis()
        if (now - lastBlockTimeMs < BLOCK_THROTTLE_GLOBAL_MS) {
            return
        }
        lastBlockedPackage = packageName
        lastBlockTimeMs = now

        val intent = Intent(this, protect.yourself.features.blockerPage.ui.PornBlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(EXTRA_BLOCK_PACKAGE, packageName)
            putExtra(EXTRA_BLOCK_MESSAGE_KEY, messageResKey)
            if (!matchedKeyword.isNullOrBlank()) {
                putExtra(EXTRA_MATCHED_KEYWORD, matchedKeyword)
            }
        }
        try {
            startActivity(intent)
            Timber.i("Block Activity (fallback) launched for pkg=$packageName messageKey=$messageResKey")
            // AB-05 fix (from main): press HOME after a short delay to move the
            // offending app to the background. The delay lets the block activity
            // appear first so HOME doesn't dismiss it. Without this, the offending
            // app stays in the back stack and the user returns to it after Close.
            serviceScope.launch {
                kotlinx.coroutines.delay(200)
                try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Timber.e(t, "Activity fallback also failed — pressing HOME as last resort")
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                throwable = t,
                severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                tag = "BlockLaunch",
                message = "Both overlay and Activity failed for pkg=$packageName — pressing HOME",
                extraContext = mapOf("packageName" to packageName, "messageKey" to messageResKey)
            )
            // ===== Strategy 3: HOME press (last resort) =====
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
                loadAllConfig(db, switchValues)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to refresh blocking config")
            }
        }
    }

    /**
     * KB-03 fix: targeted refresh — only re-read the specified keyword list
     * from the DB, instead of re-reading ALL lists (1189+ rows) on every
     * add/delete. The [which] parameter specifies which list changed.
     *
     * Called by KeywordManagerViewModel after add/delete to avoid the full
     * re-read overhead of [refreshBlockingConfig].
     */
    fun refreshKeywordList(which: protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier) {
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyAccessibilityService)
                when (which) {
                    protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier.PORN_BLOCK_WORDS -> {
                        cachedBlockKeywords = db.selectedKeywordDao()
                            .getSelectedByIdentifier(which.value)
                            .map { it.keyword }
                        Timber.i("KB-03: refreshed block keywords (${cachedBlockKeywords.size})")
                    }
                    protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS -> {
                        cachedWhitelistKeywords = db.selectedKeywordDao()
                            .getSelectedByIdentifier(which.value)
                            .map { it.keyword }
                        Timber.i("KB-03: refreshed whitelist keywords (${cachedWhitelistKeywords.size})")
                    }
                    protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS -> {
                        cachedSettingTitles = db.selectedKeywordDao()
                            .getSelectedByIdentifier(which.value)
                            .map { it.keyword }
                            .filter { it.isNotBlank() }
                        Timber.i("KB-03: refreshed setting titles (${cachedSettingTitles.size})")
                    }
                    protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier.BLOCKED_INTENT_NAMES -> {
                        cachedBlockedIntentNames = db.selectedKeywordDao()
                            .getSelectedByIdentifier(which.value)
                            .map { it.keyword }.toSet()
                        Timber.i("KB-03: refreshed blocked intent names (${cachedBlockedIntentNames.size})")
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "KB-03: failed to refresh $which — falling back to full refresh")
                refreshBlockingConfig()
            }
        }
    }

    /**
     * Loads all config from DB. Used by [refreshBlockingConfig] (full refresh).
     */
    private suspend fun loadAllConfig(
        db: AppDatabase,
        switchValues: SwitchStatusValues
    ) {
        try {
            // Switches
            isPornBlockerOn = switchValues.isPornBlockerSwitchOn()
            isSafeSearchOn = switchValues.isSafeSearchSwitchOn()
            isBlockNewInstallOn = switchValues.isBlockNewInstallAppsSwitchOn()
            isBlockInAppBrowsersOn = switchValues.isBlockInAppBrowsersSwitchOn()
            isBlockUnsupportedBrowsersOn = switchValues.isBlockUnsupportedBrowsersSwitchOn()
            isBlockSettingsByTitleOn = switchValues.isBlockSettingPageByTitleSwitchOn()
            isPreventUninstallOn = switchValues.isPreventUninstallSwitchOn()
            isBlockPhoneRebootOn = switchValues.isBlockPhoneRebootSwitchOn()
            // UP-03 fix: load anti-circumvention switches. These default to
            // OFF but can be enabled via the database or a future UI toggle.
            isBlockNotificationDrawerOn = try {
                switchValues.isBlockNotificationDrawerSwitchOn()
            } catch (_: Throwable) { false }
            isBlockRecentAppsOn = try {
                switchValues.isBlockRecentAppsSwitchOn()
            } catch (_: Throwable) { false }

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
            // AB-01/AB-14 fix: restore active Stop Me session from DB so it
            // survives process death (reboot, force-stop, OEM killing service).
            val activeStopMe = db.stopMeDurationDao().getActiveInstantSession(System.currentTimeMillis())
            stopMeEndTime = activeStopMe?.endTime ?: 0L
            if (stopMeEndTime > 0) {
                Timber.i("AB-01: Restored active Stop Me session (endTime=$stopMeEndTime)")
            }
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
            Timber.e(t, "Failed to load blocking config")
        }
    }

    /**
     * Called when a Stop Me session starts/stops.
     * AB-01 fix: accepts an end-time timestamp instead of a boolean.
     * Pass `endTime` = the wall-clock time when the session should end.
     * Pass 0 to stop the session.
     * The accessibility service checks `System.currentTimeMillis() < stopMeEndTime`
     * on every event, so the state survives process death as long as
     * `loadAllConfig` reloads it from the DB.
     */
    fun setStopMeEndTime(endTime: Long) {
        stopMeEndTime = endTime
        Timber.i("Stop Me end time set: $endTime (active=${endTime > 0})")
    }

    /** Returns true if a Stop Me session is currently active. */
    fun isStopMeActive(): Boolean {
        return stopMeEndTime > 0 && System.currentTimeMillis() < stopMeEndTime
    }

    companion object {
        const val EXTRA_BLOCK_PACKAGE = "extra_block_package"
        const val EXTRA_BLOCK_MESSAGE_KEY = "extra_block_message_key"
        // KB-19: extra key for the matched keyword, passed to PornBlockActivity.
        const val EXTRA_MATCHED_KEYWORD = "extra_matched_keyword"

        // KB-06: throttle constants.
        private const val BLOCK_THROTTLE_PER_PACKAGE_MS = 500L
        private const val BLOCK_THROTTLE_GLOBAL_MS = 300L

        // KB-01: max content-text length we'll run keyword matching on. Avoids
        // matching against huge text blobs (e.g. a full article body) which
        // would be slow and produce false positives.
        private const val MAX_CONTENT_TEXT_LENGTH = 5000

        // KB-07: events older than this (in ms) are considered stale and skipped.
        // This prevents matching a URL from a previous page when the event is
        // delayed by the system.
        private const val STALE_EVENT_THRESHOLD_MS = 2000L

        // KB-20: max recursion depth for findUrlInNode to prevent StackOverflow
        // on deeply nested view trees.
        private const val MAX_NODE_DEPTH = 50

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
