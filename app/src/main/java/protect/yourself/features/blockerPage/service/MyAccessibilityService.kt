package protect.yourself.features.blockerPage.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.URLUtil
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import protect.yourself.core.appCoroutineScope
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
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

    // LC-01 fix (v1.0.56): dedicated scope for selfHealSafe calls that MUST
    // outlive the service. Previously, selfHealSafe was launched on
    // serviceScope, which is cancelled in onDestroy — so the coroutine was
    // killed before it could complete the blocking IPC calls to
    // Settings.Secure (the race condition that caused self-heal to silently
    // fail during service teardown).
    //
    // This scope is NEVER cancelled by the service lifecycle. It is tied to
    // the application process, so coroutines launched here continue running
    // even after onDestroy() returns and serviceScope is cancelled. The OS
    // will reap the scope when the process exits (which is the correct
    // lifecycle — self-heal is meaningless after the process is gone).
    private val selfHealScope = appCoroutineScope(
        scopeName = "MyAccessibilityService-selfHeal",
        dispatcher = kotlinx.coroutines.Dispatchers.IO,
        context = this
    )

    // Cached blocking config (refreshed periodically)
    // BUG-09 fix: all cached fields are @Volatile because they are written from
    // serviceScope.launch (Dispatchers.Default) and read from onAccessibilityEvent
    // (main thread). Without @Volatile, the JVM memory model does not guarantee
    // that the main thread ever sees the updated values — the write may stay in
    // the worker thread's CPU cache indefinitely.
    @Volatile private var cachedBlockKeywords: List<String> = emptyList()
    @Volatile private var cachedWhitelistKeywords: List<String> = emptyList()
    @Volatile private var cachedBlockApps: Set<String> = emptySet()
    @Volatile private var cachedStopMeWhitelist: Set<String> = emptySet()
    @Volatile private var cachedNewInstallBlockApps: Set<String> = emptySet()
    @Volatile private var cachedInAppBrowserBlockApps: Set<String> = emptySet()
    @Volatile private var cachedUnsupportedBrowserWhitelist: Set<String> = emptySet()
    // SET-01 fix: user-selected apps for "Block Settings Page by Title".
    // The settings-by-title check now ONLY fires for packages in this set
    // (or known settings packages). Previously it ran on ALL apps, which
    // caused the main device Settings to be blocked whenever any keyword
    // matched any window text.
    @Volatile private var cachedBlockSettingPageByTitleApps: Set<String> = emptySet()

    @Volatile private var isPornBlockerOn = true
    @Volatile private var isSafeSearchOn = false

    // PB-03 fix (v1.0.55): last URL processed by handleUrlDetected, used to
    // avoid re-processing the SAME url on every accessibility event.
    //
    // NopoX 1.0.53 reference (decompiled MyAccessibilityService.checkPornUrlSearch):
    //   private static String pornPreviousUrl = "";
    //   ...
    //   if (!Intrinsics.areEqual(pornPreviousUrl, lowerCase)) { ... }
    //   pornPreviousUrl = lowerCase;
    //
    // Browsers fire TYPE_WINDOW_CONTENT_CHANGED 5-10 times per page load as
    // the DOM renders. Without this guard, the same url is matched against
    // 1189+ keywords on EVERY event — wasteful, and it re-launches the
    // block overlay after the user dismisses it (because the next content
    // change re-triggers the match). The guard is reset to "" when a block
    // fires so the next navigation is checked fresh.
    @Volatile private var pornPreviousUrl: String = ""
    @Volatile private var isBlockNewInstallOn = false
    @Volatile private var isBlockInAppBrowsersOn = false
    @Volatile private var isBlockUnsupportedBrowsersOn = false
    @Volatile private var isBlockSettingsByTitleOn = false
    @Volatile private var isPreventUninstallOn = false
    @Volatile private var isBlockPhoneRebootOn = false
    // Anti-circumvention switches — re-added (UP-03 fix). These were previously
    // removed from the UI but the detection logic was kept as dead code. They
    // are now wired via loadAllConfig() but default to OFF (the user can
    // enable them via the database or a future UI toggle).
    @Volatile private var isBlockNotificationDrawerOn = false
    @Volatile private var isBlockRecentAppsOn = false
    @Volatile private var cachedSettingTitles: List<String> = emptyList()
    // Package + intent name blocking
    @Volatile private var cachedBlockedPackageNames: Set<String> = emptySet()
    @Volatile private var cachedBlockedIntentNames: Set<String> = emptySet()
    @Volatile private var isBlockPackageIntentOn = false
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
    //
    // ANR-01 fix (v1.0.61): this cache is now PRE-POPULATED on a background
    // thread in onServiceConnected + refreshBlockingConfig. The main-thread
    // isBrowserPackageDetected() call NEVER calls PackageManager directly —
    // it checks this cache first, then falls back to the fast
    // isBrowserByPackageSignature() set-lookup, then triggers an async
    // cache refresh. This eliminates the 5200ms ANR caused by
    // queryIntentActivities() running on the main thread.
    private val browserCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ANR-01 fix: set of packages that are currently being queried
    // asynchronously. Prevents duplicate background queries for the same
    // package.
    private val pendingBrowserQueries =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        // LC-01 fix (v1.0.56): launch selfHealSafe on selfHealScope (NOT
        // serviceScope) so it survives any subsequent onDestroy cancellation.
        // selfHealSafe performs blocking IPC calls to Settings.Secure and
        // PackageManager.getInstalledPackages — each can block 100-500ms on
        // some OEMs (vivo, OPPO, Xiaomi). Combined, these exceeded the 5s
        // ANR threshold on a vivo V2206 (crash_20260712_101552_0004), which
        // is why the v1.0.49 fix moved it off the main thread.
        //
        // NopoX 1.0.53 reference (decompiled line 1511):
        //   AccessibilityPersistUtils.selfHealSafe();
        // NopoX calls it synchronously on the main thread. We use a background
        // coroutine to avoid the ANR — functionally equivalent, just async.
        selfHealScope.launch {
            try {
                protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this@MyAccessibilityService)
                Timber.d("LC-01: selfHealSafe completed in onServiceConnected (background)")
            } catch (t: Throwable) {
                Timber.w(t, "LC-01: selfHealSafe in onServiceConnected failed")
            }
        }

        // ANR-01 fix: pre-populate the browser cache on a background thread.
        // queryIntentActivities() can take 500ms-5s on devices with many
        // installed apps. If we wait for the first onAccessibilityEvent to
        // trigger it lazily, the main thread blocks and the system fires an
        // ANR (crash_20260712_195019_0002: 5200ms block). Pre-populating
        // ensures the cache is warm before any event fires.
        prepopulateBrowserCache()
    }

    /**
     * ANR-01 fix: pre-populate [browserCache] on a background thread.
     *
     * Queries PackageManager for ALL installed browser apps (apps that handle
     * http/https VIEW intents) and adds them to the cache. This runs on
     * [selfHealScope] (Dispatchers.Default) so it never blocks the main
     * thread.
     *
     * Called from [onServiceConnected] and [refreshBlockingConfig].
     */
    private fun prepopulateBrowserCache() {
        selfHealScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val pm = packageManager
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
                val allBrowsers = mutableSetOf<String>()
                httpResolved.forEach { allBrowsers.add(it.activityInfo.packageName) }
                httpsResolved.forEach { allBrowsers.add(it.activityInfo.packageName) }
                // Add all to cache
                for (pkg in allBrowsers) {
                    browserCache[pkg] = true
                }
                val elapsed = System.currentTimeMillis() - startTime
                Timber.i("ANR-01: pre-populated browser cache with ${allBrowsers.size} packages in ${elapsed}ms")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                    "ANR-01",
                    "pre-populated browser cache: ${allBrowsers.size} packages in ${elapsed}ms"
                )
            } catch (t: Throwable) {
                Timber.e(t, "ANR-01: failed to pre-populate browser cache")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                    tag = "ANR-01",
                    message = "Failed to pre-populate browser cache",
                    extraContext = emptyMap()
                )
            }
        }
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
        // LC-01/LC-03 fix (v1.0.56): launch on selfHealScope (NOT serviceScope)
        // so the self-heal survives a subsequent onDestroy().
        //
        // NopoX 1.0.53 reference (decompiled line 144-146):
        //   public boolean onUnbind(Intent intent) {
        //       AccessibilityPersistUtils.selfHealSafe();
        //       return super.onUnbind(intent);
        //   }
        // NopoX calls it synchronously. We use a background coroutine to avoid
        // the main-thread ANR risk documented in crash_20260712_101552_0004.
        //
        // CRITICAL: this is the LAST reliable chance to re-arm the service
        // before Android fully tears it down. onUnbind fires when the last
        // client unbinds — typically because Android is disabling the service
        // (user toggled it off in Settings, or OEM battery optimization
        // killed it). If we don't re-arm here, the service stays disabled
        // until the next app open / boot.
        //
        // The previous implementation launched on serviceScope, which gets
        // cancelled by onDestroy() — so if onDestroy fired quickly after
        // onUnbind (common when Android force-kills the service), the
        // self-heal coroutine was cancelled mid-flight. selfHealScope is
        // never cancelled by the service lifecycle, so this launch is
        // guaranteed to run to completion (or until the process exits).
        selfHealScope.launch {
            try {
                protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this@MyAccessibilityService)
                Timber.i("LC-01: selfHealSafe completed in onUnbind (background)")
            } catch (t: Throwable) {
                Timber.w(t, "LC-01: selfHealSafe in onUnbind failed")
            }
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
            // IAB-02 fix: Block In-App Browsers must also fire on click/long-click
            // events, not just window-state changes. NopoX 1.0.53 runs
            // checkBlockBrowsers on eventType 1 (VIEW_CLICKED), 2
            // (VIEW_LONG_CLICKED), and 32 (WINDOW_STATE_CHANGED). When a user
            // taps a link inside an app that opens an in-app WebView, the
            // click event carries the WebView className + the link URL in its
            // text/contentDescription — that is the primary signal. The
            // previous implementation only ran on WINDOW_STATE_CHANGED, so
            // most in-app browser opens were never caught.
            if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                if (isBlockInAppBrowsersOn && checkInAppBrowserBlock(event, packageName)) {
                    return
                }
            }

            // PU-02 fix: run prevent-uninstall check on ALL event types (not
            // just WINDOW_STATE_CHANGED). NopoX 1.0.53 calls checkPreventUninstall
            // for every event after the initial filtering. The previous rebuild
            // only called isAppInfoPage from handleWindowStateChange, which meant
            // if the user was already on the app info page and a content-change
            // event fired (e.g. scrolling, tapping Uninstall button), the check
            // never ran. Now it runs for every event, matching NopoX.
            if (isPreventUninstallOn &&
                packageName != this.packageName &&
                packageName != "com.android.systemui"
            ) {
                val className = event.className?.toString() ?: ""
                val text = event.text?.joinToString(" ").orEmpty()
                if (isAppInfoPage(packageName, className, text)) {
                    launchBlockActivity(packageName, "block_page_default_pu_message")
                    return
                }
            }

            // SET-03 fix: run settings-page-by-title check on ALL event types
            // (not just WINDOW_STATE_CHANGED). NopoX 1.0.53 calls
            // checkSettingAppKeywordClickBlock for event types 1 (VIEW_CLICKED),
            // 8 (WINDOW_CONTENT_CHANGED), and 32 (WINDOW_STATE_CHANGED). The
            // previous rebuild only called isSettingsPage from
            // handleWindowStateChange, which meant if the user was already on
            // a settings page and a content-change or click event fired (e.g.
            // scrolling, tapping a settings item), the check never ran.
            if (isBlockSettingsByTitleOn &&
                (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
                packageName != this.packageName &&
                packageName != "com.android.systemui"
            ) {
                if (isSettingsPage(packageName, event)) {
                    launchBlockActivity(packageName, "block_page_default_system_keyword_message")
                    return
                }
            }

            when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChange(packageName, event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
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

        // LC-01/LC-02 fix (v1.0.56): REMOVED the selfHealSafe launch that
        // was previously on serviceScope. Two reasons:
        //
        // 1. RACE CONDITION (LC-01): the old code launched selfHealSafe on
        //    serviceScope (line 266) then immediately cancelled serviceScope
        //    (line 276). The coroutine was killed before it could complete
        //    the blocking IPC calls to Settings.Secure — so self-heal NEVER
        //    actually ran during destroy. The entire block was dead code.
        //
        // 2. NOPOX DIVERGENCE (LC-02): NopoX 1.0.53 (the mandatory reference)
        //    does NOT call selfHealSafe in onDestroy at all. Decompiled
        //    MyAccessibilityService.java line 1515-1529:
        //      public void onDestroy() {
        //          super.onDestroy();
        //          try { unregisterReceiver(mAppSystemActionReceiverAllTimeWithData); }
        //          catch (Throwable th) { ... }
        //      }
        //    NopoX relies on onUnbind (which fires BEFORE onDestroy) for the
        //    final self-heal attempt. By the time onDestroy fires, the
        //    service is already being torn down — re-arming is pointless.
        //
        // The selfHealScope coroutines launched in onServiceConnected and
        // onUnbind continue running after onDestroy returns — they are NOT
        // cancelled here. Only serviceScope is cancelled (for the blocking
        // config refresh coroutines, which ARE service-bound and should stop).
        //
        // EDGE CASE: if onUnbind was somehow skipped (e.g. process killed
        //    before onUnbind fired), the selfHealScope coroutine from
        //    onServiceConnected still ran at startup, so the service was
        //    re-armed then. The next process start (boot receiver, app open,
        //    or scheduled work) will call selfHealSafe again.

        // Hide the block overlay if it's visible (otherwise it would persist
        // after the service is destroyed, locking the user out).
        try {
            blockOverlayManager?.hideBlockOverlay()
        } catch (_: Throwable) {}
        // Cancel the service scope to stop blocking-config refresh coroutines.
        // selfHealScope is intentionally NOT cancelled — see LC-01 fix above.
        try { serviceScope.cancel() } catch (_: Throwable) {}
        Timber.i("LC-01: onDestroy complete — serviceScope cancelled, selfHealScope left running for pending self-heal coroutines")
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
        if (isBlockRecentAppsOn && isRecentApps(className)) {
            launchBlockActivity(packageName, "block_recent_apps_bw_message")
            return
        }

        // ===== Now skip SystemUI for the remaining content-blocking checks =====
        // (settings page blocking, app blocking, browser blocking, etc. should
        // never fire on SystemUI windows — they're not user content)
        if (packageName == "com.android.systemui") return

        // ===== Prevent Uninstall =====
        // PU-02 fix: now handled in onAccessibilityEvent for ALL event types
        // (not just WINDOW_STATE_CHANGED). No duplicate check here.

        // ===== Content-blocking checks =====

        // Settings page title blocking (SET-01 fix: rewritten to match NopoX
        // ===== Settings page title blocking =====
        // SET-03 fix: now handled in onAccessibilityEvent for ALL event types
        // (VIEW_CLICKED, WINDOW_CONTENT_CHANGED, WINDOW_STATE_CHANGED).
        // No duplicate check here.

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
        // NIB-03 fix (v1.0.60): add diagnostic logging at every decision branch
        // so "new install blocking not working" reports can be traced through
        // crash logs. Previously there was ZERO logging here — if the switch
        // was off or the cache was empty, the block was silently skipped.
        if (isBlockNewInstallOn && cachedNewInstallBlockApps.contains(packageName)) {
            Timber.i(
                "NIB-03: BLOCKING new install app pkg=$packageName " +
                    "(switch=ON, cacheSize=${cachedNewInstallBlockApps.size})"
            )
            launchBlockActivity(packageName, "block_page_default_new_install_message")
            return
        }
        // NIB-03: log WHY the block didn't fire (only at verbose level to avoid
        // log spam — this runs on every window state change for every app).
        if (isBlockNewInstallOn && !cachedNewInstallBlockApps.contains(packageName)) {
            Timber.v(
                "NIB-03: new install block switch is ON but pkg=$packageName not in cache " +
                    "(cacheSize=${cachedNewInstallBlockApps.size})"
            )
        }

        // Block unsupported browsers
        // NopoX behavior: when this switch is ON, any browser NOT in the supported
        // list AND NOT in the unsupported-browser whitelist gets blocked on launch.
        // A "browser" is defined as an app that handles http/https URLs (via intent filter)
        // OR matches known browser package signatures.
        if (isBlockUnsupportedBrowsersOn && isBrowserPackageDetected(packageName)) {
            // Skip major known browsers — they're "supported" and should not be blocked.
            // Only block truly unknown/obscure browsers that are NOT in the whitelist.
            if (SUPPORTED_BROWSERS.contains(packageName)) {
                Timber.v("Skipping supported browser: $packageName")
                // Still allow the service to scrape URLs from this browser
            } else {
                val isWhitelisted = cachedUnsupportedBrowserWhitelist.contains(packageName)
                if (!isWhitelisted) {
                    Timber.i("Blocking unsupported browser: $packageName")
                    launchBlockActivity(packageName, "block_page_default_unsupported_browser_message")
                    return
                }
            }
        }

        // Block in-app browsers — handled in onAccessibilityEvent (IAB-02 fix)
        // so that click/long-click events are also caught. No inline check
        // here to avoid double-blocking.

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
        // PB-01/PB-02 fix (v1.0.55): URL scraping happens if EITHER porn
        // blocker OR SafeSearch is on. Previously, this was gated behind
        // isPornBlockerOn alone, which meant SafeSearch enforcement stopped
        // working if the user turned off the Porn Blocker but left SafeSearch
        // on. The combined gate is correct — but the individual checks BELOW
        // must each respect their own switch (see PB-01/PB-02 inline comments).
        if (!isPornBlockerOn && !isSafeSearchOn) return

        // SS-04 fix: the ANR-01 fix made isBrowserPackageDetected() return
        // false for browsers not in the knownBrowserPrefixes set AND not yet
        // in the cache. This broke SafeSearch because URL scraping was skipped
        // for those browsers. The fix: when SafeSearch OR Porn Blocker is ON,
        // ALWAYS attempt URL extraction — the URL extraction itself is cheap
        // (it just traverses accessibility nodes looking for a URL), and if
        // no URL is found, we fall through to the content-text check.
        //
        // NopoX 1.0.53 does NOT gate URL scraping on browser detection at all
        // — it always calls getUrlNode() + extractUrlFromEvent() for every
        // content-change event (decompiled onAccessibilityEvent line 6540+).
        // We now follow the same approach: always attempt URL extraction.
        val url = extractUrlFromEvent(event, packageName)
        if (url != null && url.isNotBlank()) {
            handleUrlDetected(packageName, url)
            return  // URL scrape takes priority — don't also do content-text match
        }

        // KB-01 fix: content-text keyword matching for non-browser apps.
        // If no URL was found, check the page content text against the
        // blocklist. This catches apps that display pornographic text in
        // their UI (e.g. a search-results page in a social app) even though
        // we can't extract a URL.
        //
        // PB-02 fix (v1.0.55): this check is GATED by isPornBlockerOn.
        // NopoX 1.0.53 gates this exact branch by `pornBlock` alone.
        if (isPornBlockerOn &&
            packageName != this.packageName &&
            packageName != "com.android.systemui" &&
            !isStaleEvent(event)
        ) {
            val text = extractTextFromEvent(event)
            if (text.isNotBlank() && text.length < MAX_CONTENT_TEXT_LENGTH) {
                val utils = BlockerPageUtils.getInstance()
                val (found, matchedKeyword) = utils.isDetectWord(text, cachedBlockKeywords)
                if (found) {
                    Timber.i("PB-02: content-text match for pkg=$packageName keyword=$matchedKeyword (pornBlocker ON)")
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

        // PB-03 fix (v1.0.55): anti-loop guard. Browsers fire
        // TYPE_WINDOW_CONTENT_CHANGED 5-10× per page load as the DOM renders,
        // each carrying the SAME url. Without this guard, the same url is
        // matched against 1189+ keywords on every event AND the block overlay
        // re-launches after the user dismisses it (next content-change event
        // re-triggers the match). NopoX 1.0.53 uses the exact same pattern
        // (decompiled MyAccessibilityService.checkPornUrlSearch line 1291:
        // `if (!Intrinsics.areEqual(pornPreviousUrl, lowerCase)) { ... }`).
        //
        // The guard is reset to "" in TWO places:
        //   1. When a block fires (so the next navigation is checked fresh)
        //   2. When refreshBlockingConfig runs (so toggling the switch takes
        //      effect immediately for the next event)
        if (pornPreviousUrl == decoded) {
            // Already processed this exact URL — skip to avoid re-block loop.
            // Verbose level because this fires on every content-change event.
            Timber.v("PB-03: skipping duplicate url=$decoded (pornPreviousUrl match)")
            return
        }

        // PB-03: defensive guard against absurdly long URLs (data: URIs,
        // base64 blobs). Matching 1189 keywords against an 8MB string is slow
        // and pointless — no real navigation URL is this long.
        if (decoded.length > MAX_URL_LENGTH_FOR_MATCH) {
            Timber.v("PB-03: url too long (${decoded.length} > $MAX_URL_LENGTH_FOR_MATCH) — skipping match")
            pornPreviousUrl = decoded
            return
        }

        // ============================================================
        // SS-03 fix (v1.0.54): SafeSearch runs FIRST, BEFORE whitelist
        // and keyword-block checks — matching the NopoX 1.0.53 reference
        // APK's checkPornUrlSearch() order exactly.
        //
        // Previously, SafeSearch ran LAST. This meant that if the URL
        // matched a whitelist keyword (e.g. user added "google" to
        // whitelist) OR a block keyword, SafeSearch was silently skipped.
        // This was the PRIMARY root cause of "SafeSearch not working"
        // reports — the redirect never fired because the function
        // returned early on the whitelist/keyword check.
        //
        // NopoX reference order (decompiled):
        //   1. SafeSearch redirect (if switch on AND url is search engine)
        //   2. ytSearchBlock / ytShortsBlock checks
        //   3. Whitelist check (overrides block)
        //   4. blockAllWebsite check
        //   5. Block keyword check
        //
        // We follow the same order: SafeSearch FIRST, then whitelist,
        // then block keyword. After SafeSearch redirect, the browser
        // navigates to the safe URL (which has safe=active / safe.duckduckgo.com
        // / etc.), and the next accessibility event fires for the safe URL —
        // which isSafeSearchUrl() recognises, so no redirect loop occurs.
        // ============================================================
        if (isSafeSearchOn) {
            val safeUrl = utils.getSafeSearchUrl(decoded)
            if (safeUrl != null) {
                Timber.i("SS-03: SafeSearch redirect triggered for pkg=$packageName url=$decoded")
                enforceSafeSearch(packageName, decoded, safeUrl)
                // Do NOT return here — NopoX continues to check whitelist
                // and block keywords on the SAME (original) URL. This is
                // intentional: if the user's search query itself contains
                // a blocked keyword, the block screen should still fire
                // (the SafeSearch redirect only filters IMAGE results, not
                // the search query itself).
                //
                // However, we skip the block if the URL is the safe variant
                // (already redirected) to avoid double-firing.
            } else {
                Timber.v("SS-03: SafeSearch on but no redirect needed for url=$decoded (not a search engine or already safe)")
            }
        }

        // Whitelist check (overrides block).
        //
        // PB-01 note (v1.0.55): the whitelist check runs whether or not the
        // Porn Blocker is on, because SafeSearch also respects the whitelist
        // (a whitelisted URL should not be redirected, since the user
        // explicitly approved it). This matches NopoX 1.0.53 behaviour:
        // the whitelist check is INSIDE `if (pornBlock || blockAllWebsite)`
        // in NopoX — but only because SafeSearch in NopoX does NOT continue
        // to the whitelist check (it returns after the redirect). In our
        // rebuild, SafeSearch does NOT return (intentionally, per SS-03),
        // so we must run the whitelist check here even when Porn Blocker
        // is off, to prevent SafeSearch from redirecting whitelisted URLs.
        if (utils.isSafeUrl(decoded, cachedWhitelistKeywords)) {
            // PB-03: track the whitelisted URL so we don't re-run the
            // (relatively expensive) isSafeUrl match on every content-change
            // event for the same URL.
            pornPreviousUrl = decoded
            Timber.v("PB-01: url whitelisted, skipping block check: $decoded")
            return
        }

        // ============================================================
        // PB-01 fix (v1.0.55): Block keyword match is now GATED by
        // isPornBlockerOn. Previously this ran unconditionally whenever
        // EITHER porn blocker OR SafeSearch was on, which meant turning
        // OFF the Porn Blocker but leaving SafeSearch ON still blocked
        // URLs based on keywords. That contradicted the user's explicit
        // switch toggle and is the primary root cause of "Porn Blocker
        // setting is not functioning correctly" reports.
        //
        // NopoX 1.0.53 reference (decompiled checkPornUrlSearch line 1305):
        //   if (pornBlock || blockAllWebsite) {
        //       ... whitelist check ...
        //       ... blockAllWebsite check ...
        //       ... block keyword check ...
        //   }
        //
        // The rebuild does not have a blockAllWebsite feature, so the
        // gate reduces to `if (pornBlock)`. We keep the gate explicit
        // so it's obvious that block keyword matching is porn-blocker-only.
        // ============================================================
        if (!isPornBlockerOn) {
            // Porn Blocker is OFF — only SafeSearch (if on) ran above.
            // Track the URL so we don't re-process it, then return.
            pornPreviousUrl = decoded
            Timber.v("PB-01: porn blocker OFF — skipping keyword match for url=$decoded")
            return
        }

        // Block keyword match — KB-05: use the renamed matchKeywordInUrl.
        // KB-19: capture the matched keyword and pass it to the block screen.
        val (found, matchedKeyword) = utils.matchKeywordInUrl(decoded, cachedBlockKeywords)
        if (found) {
            // PB-03: RESET the previous-url tracker so the next navigation
            // (after the user dismisses the block screen) is checked fresh.
            // If we left it set to `decoded`, the same URL would be skipped
            // next time — but if the user navigates AWAY and comes BACK to
            // the same URL, we WANT to re-block it. NopoX sets
            // `pornPreviousUrl = ""` after blocking (line 1343/1348).
            pornPreviousUrl = ""
            Timber.i("PB-01: block keyword match for pkg=$packageName url=$decoded keyword=$matchedKeyword (pornBlocker ON)")
            launchBlockActivity(
                packageName,
                "block_page_default_porn_blocker_message",
                matchedKeyword
            )
            return
        }

        // PB-03: no match — track the URL so we don't re-match 1189 keywords
        // against it on every content-change event. NopoX sets
        // `pornPreviousUrl = lowerCase` at the end of checkPornUrlSearch
        // (line 1354).
        pornPreviousUrl = decoded
    }

    // ===== SafeSearch enforcement =====

    /**
     * NopoX-style SafeSearch enforcement.
     *
     * **SS-01 fix (v1.0.54)**: Updated to match the NopoX 1.0.53 reference
     * APK's behaviour. The safe URL is now computed by [BlockerPageUtils.getSafeSearchUrl]
     * which uses NopoX's substring host matching + parameter-append strategy
     * (same host + `&safe=active` for Google, etc.) instead of redirecting
     * to different hosts (forcesafesearch.google.com).
     *
     * When the SafeSearch switch is ON and the user navigates to an unsafe
     * search engine URL (Google, Bing, YouTube, DuckDuckGo, Yahoo, Yandex),
     * the service opens the SafeSearch-enforced variant URL in the same
     * browser tab, replacing the unsafe search page.
     *
     * Safe strategies (per [BlockerPageUtils.getSafeSearchUrl]):
     *   - Google  → append `&safe=active` (same host, any TLD)
     *   - Bing    → append `&adlt=strict` (same host)
     *   - Yahoo   → append `&vm=r` (same host)
     *   - Yandex  → append `&family=yes` (same host — was `family=1`, invalid)
     *   - DuckDuckGo → replace host with `safe.duckduckgo.com`
     *   - YouTube → replace host with `restrict.youtube.com`
     *
     * Throttle: 2-second cooldown per package+URL to prevent redirect loops
     * and rapid-fire intents. The safe variant URL itself is excluded from
     * redirect (via isSafeSearchUrl check in getSafeSearchUrl).
     *
     * Second layer: when VPN is also ON, the family DNS resolvers
     * (Cloudflare 1.1.1.3 / AdGuard 94.140.14.15) enforce SafeSearch at
     * the DNS level — this accessibility redirect is the primary layer
     * when VPN is off, and a backup when VPN is on.
     *
     * @param packageName the browser package name (for intent targeting)
     * @param url the original (unsafe) URL — used for throttle key + logging
     * @param safeUrl the pre-computed safe URL to redirect to (from
     *        [BlockerPageUtils.getSafeSearchUrl]). Passing it in avoids
     *        recomputing it (SS-03 fix — the caller already computed it).
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

    private fun enforceSafeSearch(packageName: String, url: String, safeUrl: String) {
        // SS-03 fix: safeUrl is now passed in by the caller (handleUrlDetected)
        // to avoid recomputing it. Validate it's not blank as a defensive guard.
        if (safeUrl.isBlank()) {
            Timber.w("SS-03: enforceSafeSearch called with blank safeUrl — skipping (url=$url)")
            return
        }

        // KB-23 fix: throttle by URL WITHOUT the query string. The old code
        // compared the full URL, so a URL with a changing query parameter
        // (e.g. `?t=12345` timestamp) would never match the throttle, causing
        // redirect loops. We strip the query before comparing so the throttle
        // fires on the URL path + host only.
        val urlForThrottle = stripQuery(url)
        val now = System.currentTimeMillis()
        if (packageName == lastSafeSearchPackage &&
            urlForThrottle == lastSafeSearchUrl &&
            now - lastSafeSearchTimeMs < SAFE_SEARCH_THROTTLE_MS
        ) {
            Timber.v("SS-03: SafeSearch throttled for pkg=$packageName url=$urlForThrottle (${now - lastSafeSearchTimeMs}ms < ${SAFE_SEARCH_THROTTLE_MS}ms)")
            return
        }
        lastSafeSearchPackage = packageName
        lastSafeSearchTimeMs = now
        lastSafeSearchUrl = urlForThrottle

        Timber.i("SS-03: SafeSearch redirect: $url → $safeUrl (pkg=$packageName)")

        // Log to CrashLogger for diagnostics — this creates a breadcrumb trail
        // so users can verify SafeSearch is firing by reviewing the crash log.
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
            "SafeSearch",
            "redirect: $url → $safeUrl (pkg=$packageName)"
        )

        // Open the SafeSearch-enforced URL in the same browser.
        //
        // Do NOT press GLOBAL_ACTION_HOME before startActivity — that causes
        // a race condition where HOME dismisses the browser before the safe
        // URL intent fires, or the safe URL opens behind the home screen.
        // The new URL loads in the same browser tab, replacing the unsafe
        // search page. The user sees the SafeSearch results directly.
        //
        // NopoX's loadUrl() uses Intent.ACTION_VIEW with FLAG_ACTIVITY_NEW_TASK
        // and targets the same browser package. We follow the same pattern but
        // also add FLAG_ACTIVITY_CLEAR_TOP to ensure the safe URL replaces the
        // unsafe one in the browser's back stack (NopoX doesn't do this, but
        // it's a strict improvement — prevents the user from pressing Back to
        // return to the unsafe search results).
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Open in the same browser the user was using
                setPackage(packageName)
            }
            startActivity(intent)
            Timber.i("SS-03: SafeSearch startActivity succeeded for pkg=$packageName")
        } catch (t: Throwable) {
            // Fallback: if we can't open the safe URL in the same browser
            // (e.g. browser doesn't handle the intent, or the browser package
            // was uninstalled between detection and redirect), try without
            // targeting a specific package. This lets the system pick any
            // browser that can handle the URL.
            Timber.w(t, "SS-03: SafeSearch redirect failed for pkg=$packageName — trying without package target")
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
                Timber.i("SS-03: SafeSearch fallback startActivity succeeded (no package target)")
            } catch (t2: Throwable) {
                // Both attempts failed — show block screen as last resort.
                // This is rare but can happen if no browser is installed.
                Timber.e(t2, "SS-03: SafeSearch redirect fully failed — showing block screen")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t2,
                    severity = protect.yourself.features.crashLog.CrashSeverity.WARN,
                    tag = "SafeSearch",
                    message = "Redirect failed for pkg=$packageName url=$url safeUrl=$safeUrl",
                    extraContext = mapOf(
                        "packageName" to packageName,
                        "originalUrl" to url,
                        "safeUrl" to safeUrl
                    )
                )
                launchBlockActivity(packageName, "block_page_default_safe_search_message")
            }
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
            //
            // URL-01 fix (v1.0.58): also check contentDescription. Recent
            // Chrome versions (2024+) sometimes expose the URL via
            // contentDescription instead of text — especially when the user
            // is typing in the address bar or when the page is still loading.
            // NopoX 1.0.53 only checked text, but Chrome has since changed.
            if (viewIds != null) {
                for (viewId in viewIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                    if (nodes != null && nodes.isNotEmpty()) {
                        val node = nodes[0]
                        // Check text first (NopoX behaviour)
                        val text = node.text?.toString()
                        if (!text.isNullOrBlank()) return text
                        // URL-01: fallback to contentDescription (Chrome 2024+)
                        val desc = node.contentDescription?.toString()
                        if (!desc.isNullOrBlank() && (
                                desc.startsWith("http") || desc.contains("://") ||
                                desc.contains("."))) {
                            Timber.v("URL-01: extracted url from contentDescription for pkg=$packageName viewId=$viewId")
                            return desc
                        }
                    }
                }
            }
            // Strategy 2: fallback — search any EditText-like node with URL text
            //
            // URL-01 fix: also check contentDescription in the recursive search.
            val fallbackUrl = findUrlInNode(root)
            if (fallbackUrl != null) return fallbackUrl
        } catch (t: Throwable) {
            Timber.v("URL extraction failed: ${t.message}")
        }
        return null
    }

    private fun findUrlInNode(node: AccessibilityNodeInfo?, depth: Int = 0, nodeCounter: IntArray = intArrayOf(0)): String? {
        if (node == null) return null
        // KB-20 fix: depth limit to prevent StackOverflow on deeply nested trees.
        if (depth > MAX_NODE_DEPTH) return null
        // ANR-01 fix (v1.0.58): node-count limit to prevent main-thread ANR.
        // Same rationale as collectText — each getChild() is an IPC call.
        if (nodeCounter[0] >= MAX_URL_SEARCH_NODES) return null
        nodeCounter[0]++
        // Check text (NopoX behaviour)
        val text = node.text?.toString() ?: ""
        if (text.isNotBlank() && (text.startsWith("http") || text.contains("://"))) {
            return text
        }
        // URL-01 fix (v1.0.58): also check contentDescription. Some browsers
        // (Chrome 2024+) expose the URL via contentDescription on the address
        // bar's parent container, not as text on the EditText itself.
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.isNotBlank() && (desc.startsWith("http") || desc.contains("://"))) {
            return desc
        }
        for (i in 0 until node.childCount) {
            if (nodeCounter[0] >= MAX_URL_SEARCH_NODES) return null
            val child = node.getChild(i) ?: continue
            val found = findUrlInNode(child, depth + 1, nodeCounter)
            if (found != null) return found
        }
        return null
    }

    // ===== In-App Browser blocking (IAB-01 / IAB-02 fix) =====

    /**
     * Detect and block in-app browsers (WebViews, Facebook browser, Outlook
     * browser) inside user-selected apps.
     *
     * This method is the reference implementation ported from NopoX 1.0.53
     * (`MyAccessibilityService.checkBlockBrowsers` →
     * `blockInAppBrowserApps` branch, lines 8544-8611 in the jadx output).
     *
     * ## Root cause of the original bug
     *
     * The previous implementation called `extractUrlFromEvent(event, pkg)`,
     * which is designed for STANDALONE browsers: it looks up known address-bar
     * view IDs (e.g. `com.android.chrome:id/url_bar`) and falls back to
     * searching for EditText-like nodes containing URL text. In-app browsers
     * (WebViews) do NOT expose their URL in an EditText address bar — the URL
     * is internal to the WebView and not exposed as an accessibility node.
     * As a result, `extractUrlFromEvent` almost always returned `null` for
     * in-app browsers, and the block never fired.
     *
     * ## Correct detection (three independent signals)
     *
     * NopoX 1.0.53 uses three signals. ANY one of them triggers a block:
     *
     *  1. **ClassName match** — concatenate `event.className`,
     *     `sourceNode.className`, and `rootNode.className` (comma-separated)
     *     and check whether it contains any entry from
     *     [BlockerPageUtils.IN_APP_BROWSER_CLASS_NAMES]
     *     (`android.webkit.WebView`, `com.facebook.browser`). When an app
     *     opens a link in an in-app browser, the resulting accessibility
     *     event's className is `android.webkit.WebView` — this is the
     *     primary signal.
     *
     *  2. **URL in text** — combine `event.text`,
     *     `event.contentDescription`, `sourceNode.text`, and
     *     `sourceNode.contentDescription` into a single space-separated
     *     string, split on spaces, and check each token with
     *     [URLUtil.isValidUrl]. This catches links surfaced as plain text
     *     (e.g. in the content description of a clicked link view) even
     *     when the className doesn't match.
     *
     *  3. **Outlook special case** — Microsoft Outlook uses a custom
     *     in-app browser (not a WebView) whose address bar has view ID
     *     [BlockerPageUtils.OUTLOOK_BROWSER_ADDRESS_VIEW_ID]. We look up
     *     that view ID on the source node as a final fallback.
     *
     * If the package is not in [cachedInAppBrowserBlockApps], this method
     * returns `false` without doing any work.
     *
     * @param event the accessibility event
     * @param packageName the package name of the foreground app
     * @return `true` if an in-app browser was detected and blocked,
     *         `false` otherwise
     */
    private fun checkInAppBrowserBlock(event: AccessibilityEvent, packageName: String): Boolean {
        // Fast path: package not in the user-selected in-app browser blocklist.
        // This avoids the (relatively expensive) className / text / view-ID
        // checks for every event from every app.
        if (!cachedInAppBrowserBlockApps.contains(packageName)) {
            return false
        }

        val sourceNode: AccessibilityNodeInfo? = try {
            event.source
        } catch (t: Throwable) {
            Timber.v(t, "IAB: event.source threw for pkg=$packageName")
            null
        }
        val rootNode: AccessibilityNodeInfo? = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            Timber.v(t, "IAB: rootInActiveWindow threw for pkg=$packageName")
            null
        }

        // ===== Signal 1: className match =====
        // Concatenate the three class names (event, source, root) with ","
        // and check whether the result contains any known in-app browser
        // class name. Using `contains` (not equals) because the event
        // className may be a more specific subclass (e.g.
        // `android.webkit.WebView` embedded inside a fullscreen view).
        val eventClassName = try {
            event.className?.toString() ?: ""
        } catch (_: Throwable) { "" }
        val sourceClassName = try {
            sourceNode?.className?.toString() ?: ""
        } catch (_: Throwable) { "" }
        val rootClassName = try {
            rootNode?.className?.toString() ?: ""
        } catch (_: Throwable) { "" }
        val combinedClassNames = "$eventClassName,$sourceClassName,$rootClassName"

        val matchedClassName = BlockerPageUtils.IN_APP_BROWSER_CLASS_NAMES
            .firstOrNull { combinedClassNames.contains(it) }
        if (matchedClassName != null) {
            Timber.i(
                "IAB: blocking pkg=$packageName — className matched " +
                    "'$matchedClassName' (combined='$combinedClassNames')"
            )
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                "IAB-Block",
                "pkg=$packageName signal=className match='$matchedClassName' " +
                    "eventClass='$eventClassName' sourceClass='$sourceClassName' " +
                    "rootClass='$rootClassName'"
            )
            launchBlockActivity(packageName, "block_page_default_in_app_browser_message")
            return true
        }

        // ===== Signal 2: URL in combined text =====
        // Combine event.text + event.contentDescription + sourceNode.text +
        // sourceNode.contentDescription, split on spaces, and check each
        // token with URLUtil.isValidUrl(). This catches links that appear
        // as plain text in the clicked view's content description even when
        // the className is not a WebView.
        val eventText = try {
            event.text?.joinToString(" ") { it?.toString() ?: "" } ?: ""
        } catch (_: Throwable) { "" }
        val eventContentDescription = try {
            event.contentDescription?.toString() ?: ""
        } catch (_: Throwable) { "" }
        val sourceText = try {
            sourceNode?.text?.toString() ?: ""
        } catch (_: Throwable) { "" }
        val sourceContentDescription = try {
            sourceNode?.contentDescription?.toString() ?: ""
        } catch (_: Throwable) { "" }
        val combinedText =
            "$eventText $eventContentDescription $sourceText $sourceContentDescription"

        val matchedUrl = combinedText
            .split(' ')
            .firstOrNull { token -> token.isNotBlank() && URLUtil.isValidUrl(token) }
        if (matchedUrl != null) {
            Timber.i(
                "IAB: blocking pkg=$packageName — URL in text '$matchedUrl' " +
                    "(eventText='$eventText' eventCD='$eventContentDescription' " +
                    "sourceText='$sourceText' sourceCD='$sourceContentDescription')"
            )
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                "IAB-Block",
                "pkg=$packageName signal=url-in-text url='$matchedUrl'"
            )
            launchBlockActivity(packageName, "block_page_default_in_app_browser_message")
            return true
        }

        // ===== Signal 3: Outlook in-app browser address bar =====
        // Microsoft Outlook opens links in its own internal browser (not a
        // WebView). The address bar has a known view ID — if that view is
        // present on the source node, block.
        if (sourceNode != null) {
            val outlookNodes = try {
                sourceNode.findAccessibilityNodeInfosByViewId(
                    BlockerPageUtils.OUTLOOK_BROWSER_ADDRESS_VIEW_ID
                )
            } catch (t: Throwable) {
                Timber.v(t, "IAB: Outlook view ID lookup threw for pkg=$packageName")
                null
            }
            if (outlookNodes != null && outlookNodes.isNotEmpty()) {
                Timber.i(
                    "IAB: blocking pkg=$packageName — Outlook in-app browser " +
                        "address bar view ID found"
                )
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                    "IAB-Block",
                    "pkg=$packageName signal=outlook-address-bar"
                )
                launchBlockActivity(packageName, "block_page_default_in_app_browser_message")
                return true
            }
        }

        // No signal matched — do not block. (The package IS in the
        // blocklist, but no in-app browser activity was detected. This is
        // correct: the user still wants to use the app's non-browser
        // features.)
        return false
    }

    private fun extractTextFromEvent(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        val root = rootInActiveWindow
        if (root != null) {
            try {
                // ANR-01 fix (v1.0.58): pass a node counter to enforce a hard
                // limit on the number of nodes visited. Without this, on complex
                // pages (e.g. Chrome with many tabs), the recursive traversal
                // can visit thousands of nodes — each requiring an IPC round-trip
                // to getChild() — blocking the main thread for 5+ seconds.
                val counter = intArrayOf(0)
                collectText(root, sb, depth = 0, maxDepth = 3, nodeCounter = counter)
            } catch (_: Throwable) {}
        }
        return sb.toString().trim()
    }

    /**
     * ANR-01 fix (v1.0.58): maximum number of nodes collectText will visit
     * before bailing out. 500 nodes × ~2ms IPC per getChild = ~1s worst case —
     * well under the 5s ANR threshold. If the page has more than 500 visible
     * nodes within depth 3, we truncate the text (the block decision is based
     * on keyword matching, which works fine with partial text).
     */
    private val MAX_TEXT_COLLECTION_NODES = MAX_TEXT_COLLECTION_NODES_CONST

    private fun collectText(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        maxDepth: Int,
        nodeCounter: IntArray = intArrayOf(0)
    ) {
        if (depth > maxDepth) return
        // ANR-01: bail out if we've visited too many nodes
        if (nodeCounter[0] >= MAX_TEXT_COLLECTION_NODES) return
        nodeCounter[0]++
        node.text?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            if (nodeCounter[0] >= MAX_TEXT_COLLECTION_NODES) return
            val child = node.getChild(i) ?: continue
            collectText(child, sb, depth + 1, maxDepth, nodeCounter)
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
    private fun isRecentApps(className: String): Boolean {
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

    /**
     * Settings page title blocking — rewritten (SET-01 fix) to match the
     * NopoX 1.0.53 reference implementation.
     *
     * ## Root cause of the over-blocking bug
     *
     * The previous implementation checked the ENTIRE window text (every
     * accessibility text on screen) against keywords, on ALL apps. When the
     * user added even a short keyword like "settings", "storage", "battery",
     * "apps", or "wifi", every app whose window contained that word got
     * blocked — including the main device Settings app. This effectively
     * locked the user out of their own device settings.
     *
     * ## Correct behavior (NopoX 1.0.53 reference)
     *
     * The reference APK's `checkSettingAppKeywordClickBlock` method:
     *   1. Only runs on packages that are EITHER known settings packages
     *      (com.android.settings, OEM variants) OR in the user-selected
     *      `blockSettingPageByTitleApps` list.
     *   2. Extracts the actual page TITLE from specific Settings-app view
     *      IDs (collapsing toolbar / alert title), NOT the entire window
     *      text.
     *   3. Checks ONLY that title text against keywords.
     *   4. Uses word-boundary matching via [BlockerPageUtils.isDetectWord],
     *      not substring `contains`.
     *
     * This method now follows that pattern. The main Settings root page
     * (whose title is just "Settings") will NOT be blocked unless the user
     * explicitly added "settings" as a keyword — and even then, only the
     * root page is affected, not every screen on the device.
     *
     * @param packageName the foreground app package name
     * @param event the accessibility event (used to get the source node for
     *             view-ID lookup)
     * @return `true` if a settings sub-page title matches a blocked keyword
     */
    private fun isSettingsPage(packageName: String, event: AccessibilityEvent): Boolean {
        if (cachedSettingTitles.isEmpty()) return false
        // Don't block our own app
        if (packageName == this.packageName) return false
        // Don't block system UI
        if (packageName == "com.android.systemui") return false

        // SET-01 fix: only check packages that are EITHER known settings
        // packages OR in the user-selected blockSettingPageByTitleApps list.
        // This prevents the check from firing on every app on the device.
        val isSettingsPkg = isSettingsPackage(packageName)
        val isUserSelected = cachedBlockSettingPageByTitleApps.contains(packageName)
        if (!isSettingsPkg && !isUserSelected) return false

        // SET-03 fix: extract the page title from BOTH rootNode AND sourceNode
        // (NopoX uses both). Also combine event.text with the title text and
        // remove spaces before matching — NopoX does exactly this.
        val titleText = extractSettingsPageTitle(event)
        if (titleText.isBlank()) return false

        // SET-03 fix: NopoX concatenates event.text (lowercased, spaces
        // removed) with the title text (lowercased, spaces removed), then
        // runs isDetectWord on the combined string. This catches cases where
        // the keyword appears in the event text but not in the toolbar title.
        val eventTextStr = try {
            event.text?.joinToString(" ") { it?.toString() ?: "" } ?: ""
        } catch (_: Throwable) { "" }
        val combinedText = "$eventTextStr,$titleText"
        // NopoX: remove spaces and lowercase before matching
        val normalizedText = combinedText
            .lowercase(Locale.ROOT)
            .replace(" ", "")

        // Use isDetectWord for word-boundary matching (NopoX pattern).
        val utils = BlockerPageUtils.getInstance()
        val (found, matchedKeyword) = utils.isDetectWord(normalizedText, cachedSettingTitles)
        if (found) {
            Timber.i(
                "SET: blocking settings page pkg=$packageName — " +
                    "title='$titleText' eventText='$eventTextStr' matched keyword='$matchedKeyword'"
            )
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                "SET-Block",
                "pkg=$packageName title='$titleText' keyword='$matchedKeyword'"
            )
            return true
        }
        return false
    }

    /**
     * Extract the actual page title text from a Settings-app window by
     * looking up known toolbar view IDs on BOTH the rootNode AND sourceNode.
     *
     * SET-03 fix: NopoX 1.0.53 looks up view IDs on both p2 (rootNode) and
     * p3 (sourceNode) — the previous rebuild only used event.source. This
     * caused the title extraction to fail on some OEMs where the toolbar
     * views are only accessible via rootNode, not sourceNode.
     *
     * NopoX looks up:
     *   - `com.android.settings:id/collapsing_appbar_extended_title` on rootNode
     *   - `com.android.settings:id/collapsing_toolbar` on rootNode
     *   - `android:id/alertTitle` on sourceNode
     *
     * The texts from all three are concatenated (comma-separated) so that
     * keyword matching can match against any of them.
     */
    private fun extractSettingsPageTitle(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        val sourceNode: AccessibilityNodeInfo? = try {
            event.source
        } catch (_: Throwable) { null }
        val rootNode: AccessibilityNodeInfo? = try {
            rootInActiveWindow
        } catch (_: Throwable) { null }

        // NopoX: look up collapsing_appbar_extended_title and collapsing_toolbar
        // on rootNode, and alertTitle on sourceNode.
        val viewIdsOnRoot = listOf(
            "com.android.settings:id/collapsing_appbar_extended_title",
            "com.android.settings:id/collapsing_toolbar"
        )
        val viewIdsOnSource = listOf(
            "android:id/alertTitle"
        )

        for (viewId in viewIdsOnRoot) {
            try {
                val nodes = rootNode?.findAccessibilityNodeInfosByViewId(viewId)
                if (nodes != null) {
                    for (node in nodes) {
                        val t = node.text?.toString()
                        if (!t.isNullOrBlank()) {
                            if (sb.isNotEmpty()) sb.append(',')
                            sb.append(t)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        for (viewId in viewIdsOnSource) {
            try {
                val nodes = sourceNode?.findAccessibilityNodeInfosByViewId(viewId)
                if (nodes != null) {
                    for (node in nodes) {
                        val t = node.text?.toString()
                        if (!t.isNullOrBlank()) {
                            if (sb.isNotEmpty()) sb.append(',')
                            sb.append(t)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // Fallback: if no toolbar title was found via view IDs, use the
        // event.text (the event's own text payload). This is much narrower
        // than the previous "entire window text" approach — event.text
        // typically contains only the title/label of the window that
        // changed, not every piece of text on screen.
        if (sb.isEmpty()) {
            try {
                event.text?.forEach { t ->
                    if (!t.isNullOrBlank()) {
                        if (sb.isNotEmpty()) sb.append(',')
                        sb.append(t)
                    }
                }
            } catch (_: Throwable) {}
        }

        return sb.toString()
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
     * Detect if the user is on the app info page for OUR package.
     * This is the page where the Uninstall button lives.
     *
     * PU-01 fix (scoped to our app only): rewritten to match the NopoX 1.0.53
     * reference implementation. The previous implementation blocked ANY app's
     * app-info / device-admin / accessibility page because it matched on
     * class-name patterns ("appinfo", "appdetails") and device-admin text
     * patterns WITHOUT requiring our app name to be present in the page text.
     *
     * ## Root cause of the over-blocking
     *
     * The old code had three independent triggers that each returned `true`
     * without checking whether the page was actually about OUR app:
     *   1. ClassName contains "appinfo" / "appdetails" → block (any app's info page)
     *   2. ClassName contains "appinfo" + device-admin text → block (any app's
     *      device admin page)
     *   3. Force-stop text present + className contains "appinfo" → block
     *      (any app's info page, since every info page has a Force stop button)
     *
     * ## Correct behavior (NopoX 1.0.53 reference)
     *
     * The reference's `checkPreventUninstall` method:
     *   - Computes `appNameInText = eventText.contains(appName)` FIRST.
     *   - For app-info / device-admin / force-stop checks, it REQUIRES
     *     `appNameInText` to be true. If our app name is NOT in the page
     *     text, the check does not fire — even if the className matches
     *     "appinfo" or the text contains "device admin".
     *   - For the accessibility-page check, it looks up our app's
     *     accessibility service description text (a long distinctive string
     *     from R.string.accessibility_service_description) via
     *     `accessibilityNodeInfoByText`. This only matches the accessibility
     *     settings page that lists OUR service, not other apps' entries.
     *
     * This method now follows that pattern. Every check requires our app
     * name (or our accessibility description) to be present in the page text.
     *
     * UP-04/UP-05/UP-07: retained try/catch around every branch (NopoX
     * pattern — safe fallback is to NOT block, because a false positive on
     * a legitimate Settings page is worse than a false negative).
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

        // PU-02 fix: the previous PU-01 fix only checked event.text for the app
        // name, but event.text for a WINDOW_STATE_CHANGED event is often just
        // "App info" — NOT "Protect Yourself". This caused the prevent-uninstall
        // check to NEVER fire, so the user could uninstall freely.
        //
        // The NopoX 1.0.53 reference uses TWO sources for app-name detection:
        //   1. event.text.contains(appName) — checks the event text payload
        //   2. accessibilityNodeInfoByText(rootNode, appName) — searches the
        //      ENTIRE node tree (all text on the page) for the app name
        //
        // We now use BOTH: if EITHER source contains the app name, we consider
        // the page to be about our app. This matches the NopoX reference.
        val appName = try {
            getString(protect.yourself.R.string.app_name).lowercase(Locale.ROOT)
        } catch (_: Throwable) { "" }
        val appNameInText = appName.isNotBlank() && lower.contains(appName)

        // PU-02 fix: also search the node tree for the app name. This is the
        // KEY fix — the node tree contains ALL text on the page, including the
        // app name in the title bar. NopoX uses accessibilityNodeInfoByText
        // for this (decompiled line 5849).
        val appNameInNodeTree = if (appName.isNotBlank() && !appNameInText) {
            try {
                val root = rootInActiveWindow
                root?.findAccessibilityNodeInfosByText(appName)?.isNotEmpty() == true
            } catch (_: Throwable) { false }
        } else {
            false
        }
        val appIsOnPage = appNameInText || appNameInNodeTree

        // PU-02 fix: also check for the package uninstaller activity. NopoX
        // checks if className == "com.android.packageinstaller.UninstallerActivity"
        // AND the app name is in the node tree (decompiled line 5963-5988).
        // This catches the "Do you want to uninstall this app?" confirmation
        // dialog.
        if (lowerClass == "com.android.packageinstaller.uninstalleractivity" ||
            lowerClass.contains("uninstalleractivity")) {
            if (appIsOnPage || appNameInNodeTree) {
                Timber.i("PU: blocking uninstaller activity for our app (pkg=$packageName class=$className)")
                return true
            }
        }

        // ===== Check 1: app info page for OUR app =====
        // Requires appIsOnPage (app name in event text OR node tree) AND
        // (className contains "appinfo"/"appdetails" OR an uninstall-related
        // keyword is present).
        if (appIsOnPage) {
            val isAppInfoClass = lowerClass.contains("appinfodashboard") ||
                lowerClass.contains("installedappdetails") ||
                lowerClass.contains("appinfoactivity") ||
                lowerClass.contains("appinfopage") ||
                lowerClass.contains("appinfo") ||
                lowerClass.contains("appdetails") ||
                lowerClass.contains("appdetail") ||
                lowerClass.contains("subsettings")  // NopoX checks SubSettings
            val hasUninstallKeyword = lower.contains("uninstall") ||
                lower.contains("disable") ||
                lower.contains("force stop") ||
                lower.contains("forcestop") ||
                lower.contains("deactivate") ||
                lower.contains("remove") ||
                lower.contains("clear data") ||
                lower.contains("cleardata") ||
                lower.contains("storage") ||
                lower.contains("permissions")
            if (isAppInfoClass || hasUninstallKeyword) {
                Timber.i("PU: blocking app-info page for our app (pkg=$packageName class=$className appNameInText=$appNameInText appNameInNodeTree=$appNameInNodeTree)")
                return true
            }
        }

        // ===== Check 2: device admin deactivation page for OUR app =====
        if (appIsOnPage) {
            val deviceAdminTexts = BlockerPageUtils.DEVICE_ADMIN_TEXTS_TO_MATCH
            for (matchText in deviceAdminTexts) {
                try {
                    if (lower.contains(matchText.lowercase(Locale.ROOT))) {
                        Timber.i("PU: blocking device-admin page for our app (pkg=$packageName text='$matchText')")
                        return true
                    }
                } catch (_: Throwable) {}
            }
        }

        // ===== Check 3: force-stop on OUR app's info page =====
        if (appIsOnPage) {
            val forceStopTexts = BlockerPageUtils.FORCE_STOP_TEXTS_TO_MATCH
            for (matchText in forceStopTexts) {
                try {
                    if (lower.contains(matchText.lowercase(Locale.ROOT))) {
                        Timber.i("PU: blocking force-stop on our app's info page (pkg=$packageName)")
                        return true
                    }
                } catch (_: Throwable) {}
            }
        }

        // ===== Check 4: accessibility settings page for OUR service =====
        // Match the accessibility service description text against both the
        // event text AND the node tree.
        try {
            val accDesc = getString(protect.yourself.R.string.accessibility_service_description)
                .lowercase(Locale.ROOT)
            if (accDesc.isNotBlank()) {
                if (lower.contains(accDesc)) {
                    Timber.i("PU: blocking accessibility settings page for our service (pkg=$packageName — event text match)")
                    return true
                }
                // Also search the node tree for the description
                try {
                    val root = rootInActiveWindow
                    if (root?.findAccessibilityNodeInfosByText(accDesc)?.isNotEmpty() == true) {
                        Timber.i("PU: blocking accessibility settings page for our service (pkg=$packageName — node tree match)")
                        return true
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // ===== Check 5: Samsung multi-select uninstall =====
        // NopoX checks if the source node's viewIdResourceName is
        // "com.sec.android.app.launcher:id/multi_select_uninstall" (decompiled
        // line 5937-5955). This catches the Samsung launcher's batch-uninstall
        // mode.
        try {
            val sourceNode = rootInActiveWindow
            if (sourceNode != null) {
                val viewId = sourceNode.viewIdResourceName
                if (viewId == "com.sec.android.app.launcher:id/multi_select_uninstall") {
                    Timber.i("PU: blocking Samsung multi-select uninstall (pkg=$packageName)")
                    return true
                }
            }
        } catch (_: Throwable) {}

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
    /**
     * ANR-01 fix (v1.0.61): rewritten to NEVER call PackageManager on the
     * main thread.
     *
     * ## Root cause of the ANR
     *
     * The previous implementation called `pm.queryIntentActivities()` on the
     * main thread inside this method. `queryIntentActivities` is a PackageManager
     * IPC call that queries ALL installed apps that handle http/https intents.
     * On devices with many apps (100+), this can take 500ms-5s, blocking the
     * main thread and triggering an ANR (crash_20260712_195019_0002: 5200ms
     * block at this exact method).
     *
     * ## Fix
     *
     * This method is now strictly non-blocking:
     *   1. Check [browserCache] — O(1) ConcurrentHashMap lookup, ~0ms.
     *   2. If not cached, fall back to [isBrowserByPackageSignature] — a fast
     *      set-lookup against known browser package prefixes, ~0ms.
     *   3. Trigger an async cache refresh via [refreshBrowserCacheAsync] so
     *      the next call finds the package in the cache.
     *
     * The cache is pre-populated on a background thread in
     * [onServiceConnected] and [refreshBlockingConfig] via
     * [prepopulateBrowserCache], so most packages will already be cached
     * before the first event fires.
     *
     * The fast package-signature fallback ensures we still detect major
     * browsers (Chrome, Firefox, Edge, Samsung Internet, Brave, Opera, etc.)
     * immediately, even before the cache is populated. Less common browsers
     * may be missed on the very first event, but will be caught on subsequent
     * events after the async cache refresh completes — this is an acceptable
     * trade-off vs. a 5-second ANR that freezes the entire phone.
     */
    private fun isBrowserPackageDetected(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == this.packageName) return false  // Don't block self
        if (packageName == "com.android.systemui") return false

        // Step 1: check cache — O(1), never blocks
        browserCache[packageName]?.let { return it }

        // Step 2: fast package-signature fallback — O(K) set lookup where K
        // = number of known browser prefixes (~20). This catches Chrome,
        // Firefox, Edge, Samsung Internet, Brave, Opera, etc. immediately.
        val isBrowserBySignature = isBrowserByPackageSignature(packageName)
        if (isBrowserBySignature) {
            // Cache the positive result so we don't re-check
            browserCache[packageName] = true
            return true
        }

        // Step 3: trigger async cache refresh for this package. The result
        // will be available in the cache on the next event. This prevents
        // the main-thread ANR while still eventually detecting the browser.
        refreshBrowserCacheAsync(packageName)

        // Return false for now — the async refresh will update the cache.
        // If this is a browser, the next accessibility event for the same
        // package will find it in the cache and block correctly.
        // This is a deliberate trade-off: a possible 1-event miss vs. a
        // 5-second ANR that freezes the phone.
        return false
    }

    /**
     * ANR-01 fix: asynchronously query PackageManager for a single package
     * and update [browserCache]. Runs on [selfHealScope] (Dispatchers.Default)
     * so it never blocks the main thread.
     *
     * Uses [pendingBrowserQueries] to prevent duplicate background queries
     * for the same package.
     */
    private fun refreshBrowserCacheAsync(packageName: String) {
        // Don't queue duplicate queries
        if (!pendingBrowserQueries.add(packageName)) return

        selfHealScope.launch {
            try {
                val pm = packageManager
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
                val isBrowser = httpResolved.any { it.activityInfo.packageName == packageName } ||
                    httpsResolved.any { it.activityInfo.packageName == packageName }
                browserCache[packageName] = isBrowser
                if (isBrowser) {
                    Timber.d("ANR-01: async browser detection — pkg=$packageName is a browser (cached)")
                }
            } catch (t: Throwable) {
                Timber.v(t, "ANR-01: async browser detection failed for pkg=$packageName")
                // Cache the negative result so we don't keep retrying
                browserCache[packageName] = false
            } finally {
                pendingBrowserQueries.remove(packageName)
            }
        }
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
                // Use OncePerSessionLogger to prevent log spam — without this,
                // every block attempt logs the same warning. Crash log analysis
                // (v1.0.46, vivo V2206) showed 14 identical WARN entries in 20
                // minutes, one per block. Logging once per session is sufficient
                // — the user already knows from the first warning.
                protect.yourself.commons.utils.OncePerSessionLogger.warn(
                    key = "overlay_show_failed",
                    message = "Overlay manager returned false — falling back to Activity"
                )
            } else {
                // Use OncePerSessionLogger to prevent log spam — without this,
                // every block attempt logs the same warning. Crash log analysis
                // (v1.0.46, vivo V2206) showed 14 identical WARN entries in 20
                // minutes, one per block. Logging once per session is sufficient
                // — the user already knows from the first warning.
                protect.yourself.commons.utils.OncePerSessionLogger.warn(
                    key = "overlay_permission_missing",
                    message = "SYSTEM_ALERT_WINDOW not granted — falling back to Activity. " +
                        "User should grant it via Settings → Apps → Protect Yourself → " +
                        "Display over other apps."
                )
                // Log to CrashLogger so the user can see the recommendation
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                    "BlockFallback",
                    "Overlay permission missing — using Activity fallback for pkg=$packageName"
                )
                // BUGFIX (v1.0.49): proactively prompt the user to grant the
                // overlay permission. Throttled to once per 24 hours.
                try {
                    protect.yourself.commons.utils.notificationUtils.NotificationHelper
                        .showOverlayPermissionNotification(this)
                } catch (_: Throwable) {}
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
        // ANR-01 fix: also refresh the browser cache in case new apps were
        // installed/removed since the last refresh.
        prepopulateBrowserCache()
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
     * NIB-01 fix (v1.0.60): targeted refresh of the new-install block apps
     * cache ONLY. This is dramatically faster than [refreshBlockingConfig]
     * (which re-reads ALL keywords, ALL apps, ALL switches — 1189+ rows).
     *
     * Called by [AppSystemActionReceiverAllTimeWithData.handlePackageAdded]
     * after inserting a new app into the BLOCK_NEW_INSTALL_APPS list, so the
     * cache is updated within milliseconds instead of waiting for the next
     * periodic refresh (24h) or a full [refreshBlockingConfig] cycle.
     *
     * # Why this matters
     *
     * The previous implementation called [refreshBlockingConfig], which
     * launches a coroutine that re-reads the ENTIRE config. On a device with
     * 1189+ keywords + 200+ apps, this can take 200-500ms. If the user opens
     * the newly installed app within that window, the cache hasn't been
     * updated yet and the block doesn't fire — the user sees the app open
     * normally instead of being blocked.
     *
     * This targeted refresh reads ONLY the BLOCK_NEW_INSTALL_APPS list
     * (typically 0-10 rows) and updates [cachedNewInstallBlockApps] + the
     * [isBlockNewInstallOn] switch in <10ms.
     *
     * # Synchronous variant
     *
     * See [refreshNewInstallBlockSync] for a suspend variant that blocks
     * until the cache is updated — used by the receiver to guarantee the
     * cache is fresh before the user can open the new app.
     */
    fun refreshNewInstallBlockConfig() {
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyAccessibilityService)
                refreshNewInstallBlockSync(db)
            } catch (t: Throwable) {
                Timber.e(t, "NIB-01: failed to refresh new install block config")
            }
        }
    }

    /**
     * NIB-01 fix (v1.0.60): suspend variant of [refreshNewInstallBlockConfig].
     *
     * Reads the BLOCK_NEW_INSTALL_APPS list + the switch state from the DB
     * and updates the cached fields. Called by the PACKAGE_ADDED receiver
     * AFTER the DB insert completes, so the cache is guaranteed to reflect
     * the new app before this method returns.
     *
     * This is the critical fix for the race condition where the user opens
     * the newly installed app before the async [refreshBlockingConfig]
     * coroutine has a chance to update the cache.
     */
    suspend fun refreshNewInstallBlockSync(db: AppDatabase) {
        try {
            val switchValues = SwitchStatusValues(db.switchStatusDao())
            val oldSwitchOn = isBlockNewInstallOn
            isBlockNewInstallOn = switchValues.isBlockNewInstallAppsSwitchOn()
            val oldCacheSize = cachedNewInstallBlockApps.size
            cachedNewInstallBlockApps = db.selectedAppsListDao()
                .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
                .map { it.packageName }.toSet()
            Timber.i(
                "NIB-01: refreshed new install block config — " +
                    "switch=$oldSwitchOn→$isBlockNewInstallOn, " +
                    "cacheSize=$oldCacheSize→${cachedNewInstallBlockApps.size}, " +
                    "packages=${cachedNewInstallBlockApps.take(5)}"
            )
        } catch (t: Throwable) {
            Timber.e(t, "NIB-01: refreshNewInstallBlockSync failed")
            throw t
        }
    }

    /**
     * NIB-02 fix (v1.0.60: adds a package to the in-memory cache immediately,
     * WITHOUT waiting for a DB read. This eliminates the race condition
     * entirely — the package is blocked from the very first accessibility
     * event, even if the DB read in [refreshNewInstallBlockSync] hasn't
     * completed yet.
     *
     * Called by the PACKAGE_ADDED receiver AFTER the DB insert succeeds,
     * BEFORE the async cache refresh. This is a belt-and-suspenders approach:
     *  1. [addToNewInstallBlockCache] — immediate, in-memory, no I/O
     *  2. [refreshNewInstallBlockSync] — authoritative, reads from DB
     *
     * If (1) runs but (2) hasn't yet, the package is still blocked (it's in
     * the cache from step 1). If (2) runs and the DB read returns the same
     * set, no harm done (idempotent). If (2) fails, the cache from (1)
     * persists — better to over-block than under-block for a new install.
     */
    fun addToNewInstallBlockCache(packageName: String) {
        if (packageName.isBlank()) return
        val oldSet = cachedNewInstallBlockApps
        if (oldSet.contains(packageName)) {
            Timber.v("NIB-02: package $packageName already in new install cache — skip")
            return
        }
        cachedNewInstallBlockApps = oldSet + packageName
        Timber.i(
            "NIB-02: added $packageName to new install cache immediately " +
                "(cacheSize=${cachedNewInstallBlockApps.size})"
        )
    }

    /**
     * NIB-02 fix (v1.0.60): removes a package from the in-memory cache
     * immediately. Called when the user manually unblocks an app via the
     * picker UI, so the block stops firing without waiting for a full
     * [refreshBlockingConfig] cycle.
     */
    fun removeFromNewInstallBlockCache(packageName: String) {
        if (packageName.isBlank()) return
        val oldSet = cachedNewInstallBlockApps
        if (!oldSet.contains(packageName)) {
            Timber.v("NIB-02: package $packageName not in new install cache — skip remove")
            return
        }
        cachedNewInstallBlockApps = oldSet - packageName
        Timber.i(
            "NIB-02: removed $packageName from new install cache " +
                "(cacheSize=${cachedNewInstallBlockApps.size})"
        )
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
            // PB-04 fix (v1.0.55): capture the OLD porn-blocker state before
            // overwriting it, so we can log state transitions. This is the
            // primary diagnostic signal for "Porn Blocker not functioning"
            // reports — the log shows exactly when the switch changed and
            // what the new value is.
            val oldPornBlockerOn = isPornBlockerOn
            isPornBlockerOn = switchValues.isPornBlockerSwitchOn()
            if (oldPornBlockerOn != isPornBlockerOn) {
                Timber.i("PB-04: porn blocker switch transition: $oldPornBlockerOn → $isPornBlockerOn — resetting pornPreviousUrl")
                // PB-03: reset the previous-url tracker when the switch
                // changes so the next URL is checked fresh. Otherwise, if
                // the user toggles the switch while on the same page, the
                // anti-loop guard would skip the URL even though the
                // blocking decision should change.
                pornPreviousUrl = ""
            }
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
            // SET-01 fix: load the user-selected app list for settings-by-title
            // blocking. The check now ONLY fires for these apps (or known
            // settings packages), not ALL apps.
            cachedBlockSettingPageByTitleApps = db.selectedAppsListDao()
                .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_APPS.value)
                .map { it.packageName }.toSet()

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
                "pornBlockerOn=$isPornBlockerOn, safeSearchOn=$isSafeSearchOn, " +
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

    companion object {
        const val EXTRA_BLOCK_PACKAGE = "extra_block_package"
        const val EXTRA_BLOCK_MESSAGE_KEY = "extra_block_message_key"
        // KB-19: extra key for the matched keyword, passed to PornBlockActivity.
        const val EXTRA_MATCHED_KEYWORD = "extra_matched_keyword"

        // KB-06: throttle constants.
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

        // ANR-01 fix (v1.0.58): max number of nodes findUrlInNode will visit
        // before bailing out. Each getChild() is a blocking IPC call (~2ms),
        // so 300 nodes = ~600ms worst case — well under the 5s ANR threshold.
        // If the URL isn't found in the first 300 nodes, it's almost certainly
        // not in the address bar (which is always near the root of the view
        // hierarchy).
        private const val MAX_URL_SEARCH_NODES = 300

        // ANR-01 fix (v1.0.58): max number of nodes collectText will visit
        // before bailing out. 500 nodes × ~2ms IPC per getChild = ~1s worst
        // case — well under the 5s ANR threshold.
        private const val MAX_TEXT_COLLECTION_NODES_CONST = 500

        // SS-03: SafeSearch redirect throttle. Prevents redirect loops and
        // rapid-fire intents when the same URL fires multiple accessibility
        // events (which is common — TYPE_WINDOW_CONTENT_CHANGED can fire 5-10
        // times per page load as the browser renders). 2 seconds is enough
        // for the safe URL to load and trigger its own accessibility event
        // (which isSafeSearchUrl() then recognises, breaking the loop).
        private const val SAFE_SEARCH_THROTTLE_MS = 2000L

        // PB-03: max length of a URL we will run keyword matching on. URLs
        // longer than this are almost certainly not real navigation events
        // (they tend to be data: URIs or huge base64 blobs) and matching
        // against them is both slow and prone to false positives.
        private const val MAX_URL_LENGTH_FOR_MATCH = 8192


        // Known major browsers that are considered "supported".
        // These are NEVER blocked by the "Block Unsupported Browsers" toggle.
        // The toggle only blocks truly unknown/obscure browsers not in this list.
        private val SUPPORTED_BROWSERS = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.microsoft.emmx",           // Microsoft Edge
            "com.sec.android.app.sbrowser", // Samsung Internet
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android",
            "com.mi.globalbrowser",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.bromite.bromite",
            "org.torproject.torbrowser",
            "com.kiwibrowser.browser",
            "mark.via",
            "com.junkboat.brave"
        )
        @Volatile
        var instance: MyAccessibilityService? = null
            private set

        /**
         * Check if this accessibility service is enabled (system setting).
         */
        fun isEnabled(context: android.content.Context): Boolean {
            // CRASH FIX: wrap in try/catch — some restricted profiles (work
            // profile, secondary user, kiosk mode) throw SecurityException
            // when reading ENABLED_ACCESSIBILITY_SERVICES.
            return try {
                val expectedComponent = context.packageName + "/" + MyAccessibilityService::class.java.name
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabledServices.contains(expectedComponent)
            } catch (t: Throwable) {
                Timber.w(t, "isEnabled: failed to read ENABLED_ACCESSIBILITY_SERVICES")
                false
            }
        }
    }
}
