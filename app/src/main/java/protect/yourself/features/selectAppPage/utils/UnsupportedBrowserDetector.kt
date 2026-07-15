package protect.yourself.features.selectAppPage.utils

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import protect.yourself.commons.utils.PackageManagerProvider
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects which installed apps are **unsupported browsers** — i.e. apps that
 * can browse the web (declare an intent filter for http/https) but are NOT in
 * the curated [SUPPORTED_BROWSERS] allow-list.
 *
 * Ported from the reference implementation:
 *   - `BlockerPageUtils.getAllBrowserApps()` — queries PackageManager for
 *     everything that can handle `http://www.google.com` (catches both http
 *     and https intent filters in a single query because most browsers
 *     declare both).
 *   - `BlockerPageUtils.getUnSupportedBrowser()` — subtracts
 *     `supportedBrowserAndApps()` from `getAllBrowserApps()`.
 *
 * Why this exists:
 *   The "Whitelist Unsupported Browser" card (Blocker -> Content Blocking ->
 *   Whitelist unsupported browsers -> Manage) previously showed **every**
 * installed app, which made it nearly impossible to find the small set of
 * unsupported browsers the user actually wants to whitelist. After this fix
 * the list is restricted to:
 *   1. Every installed app whose intent-filters can resolve http/https
 *      (i.e. a real browser-capable app) **and** that is not in
 *      [SUPPORTED_BROWSERS]; plus
 *   2. `com.google.android.googlequicksearchbox` (the Google app), which
 *      ships with an in-app browser that does not declare a standard
 *      http intent filter but is treated as an unsupported browser by the
 *      reference implementation.
 *
 * Performance:
 *   - `queryIntentActivities` is O(N) over installed apps and is one of the
 *     cheapest PackageManager calls available — much faster than
 *     `getInstalledApplications(GET_META_DATA)` because it returns only
 *     matching resolve-info instead of the full ApplicationInfo list.
 *   - Results are cached for 60 seconds (configurable via [CACHE_TTL_MS]) so
 *     repeated opens of the page (e.g. back-and-forth navigation) don't
 *     re-query the PackageManager.
 *   - The cache is invalidated automatically when any package is
 *     installed/removed (call [invalidateCache] from your package receiver).
 *
 * Thread-safety:
 *   All public functions are safe to call from any thread. The internal
 *   cache uses [ConcurrentHashMap] and a volatile timestamp.
 */
object UnsupportedBrowserDetector {

    private const val TAG = "UnsupportedBrowserDetector"
    private const val CACHE_TTL_MS = 60_000L // 60 seconds

    /**
     * The set of browser packages that the app considers "supported" and
     * therefore should NEVER appear in the whitelist-unsupported-browser
     * picker.
     *
     * This MUST stay in sync with `MyAccessibilityService.SUPPORTED_BROWSERS`
     * (defined in `features/blockerPage/service/MyAccessibilityService.kt`).
     * Any package listed here is one the accessibility service promises not
     * to block, so it makes no sense to also whitelist it.
     *
     * Why duplicate the list here instead of referencing the accessibility
     * service constant: the accessibility service is a runtime component
     * that is only instantiated by the system. Holding a static reference to
     * it from a UI utility would create an unwanted coupling and would also
     * break if the service is disabled. A standalone constant keeps the
     * detector usable from any thread without a service instance.
     */
    private val SUPPORTED_BROWSERS: Set<String> = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",            // Microsoft Edge
        "com.sec.android.app.sbrowser",  // Samsung Internet
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

    /**
     * Apps that don't declare an http/https intent filter but are still
     * able to browse the web (in-app browser tab) and should be present in
     * the whitelist picker so the user can choose to allow them.
     *
     * Ported directly from the reference `SelectAppPageViewModel$getDisplayAppsList$1`.
     */
    private val EXTRA_IN_APP_BROWSER_PACKAGES: Set<String> = setOf(
        "com.google.android.googlequicksearchbox"
    )

    // ---- cache ----------------------------------------------------------

    @Volatile
    private var cachedUnsupportedBrowsers: Set<String>? = null

    @Volatile
    private var cacheTimestamp: Long = 0L

    /** Per-package browser signature cache, used by [isBrowserByPackageSignature]. */
    private val signatureCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Returns the set of installed packages that are browsers but NOT in
     * the supported-browsers allow-list. Safe to call from any thread.
     *
     * The result is cached for [CACHE_TTL_MS]; call [invalidateCache] to
     * force a refresh (e.g. when a new app is installed).
     */
    fun getUnsupportedBrowserPackages(): Set<String> {
        val now = System.currentTimeMillis()
        val cached = cachedUnsupportedBrowsers
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) {
            Timber.tag(TAG).d("Cache hit: ${cached.size} unsupported browsers (age=${now - cacheTimestamp}ms)")
            return cached
        }

        val pm = try {
            PackageManagerProvider.packageManager
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "PackageManager not available — returning empty set")
            return emptySet()
        }

        val result: Set<String> = try {
            // Single intent query — much faster than getInstalledApplications.
            // http:// catches browsers that only declare http (older apps).
            // We add https by also querying https:// to catch any browser
            // that only declares https.
            val httpIntent = Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("http://www.google.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val httpsIntent = Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)

            val httpResolved: List<ResolveInfo> = try {
                pm.queryIntentActivities(httpIntent, 0)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "queryIntentActivities(http) failed")
                emptyList()
            }
            val httpsResolved: List<ResolveInfo> = try {
                pm.queryIntentActivities(httpsIntent, 0)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "queryIntentActivities(https) failed")
                emptyList()
            }

            val allBrowserPackages = mutableSetOf<String>()
            httpResolved.forEach { ri ->
                ri?.activityInfo?.packageName?.let { allBrowserPackages.add(it) }
            }
            httpsResolved.forEach { ri ->
                ri?.activityInfo?.packageName?.let { allBrowserPackages.add(it) }
            }

            Timber.tag(TAG).i(
                "Detected ${allBrowserPackages.size} browser-capable apps " +
                    "(http=${httpResolved.size}, https=${httpsResolved.size})"
            )

            // Also include in-app browser packages that bypass intent-filter
            // detection but should still be eligible for whitelisting.
            // Only include them if they are actually installed (otherwise the
            // user sees ghost entries in the picker).
            val installedInAppBrowsers = EXTRA_IN_APP_BROWSER_PACKAGES.filterTo(mutableSetOf()) { pkg ->
                try {
                    // pm.getApplicationInfo throws NameNotFoundException if the
                    // app is not installed; the getLaunchIntentForPackage call
                    // returns null instead, so we check both for robustness.
                    pm.getLaunchIntentForPackage(pkg) != null || runCatching {
                        pm.getApplicationInfo(pkg, 0)
                    }.isSuccess
                } catch (t: Throwable) {
                    false
                }
            }

            // Subtract the supported list — those are never "unsupported".
            val unsupported = (allBrowserPackages + installedInAppBrowsers) - SUPPORTED_BROWSERS
            unsupported
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to compute unsupported browser list")
            // Keep stale cache rather than clobbering it with an empty set,
            // so a transient PackageManager error doesn't wipe the user's
            // view of the list.
            return cached ?: emptySet()
        }

        cachedUnsupportedBrowsers = result
        cacheTimestamp = System.currentTimeMillis()
        Timber.tag(TAG).i("Computed ${result.size} unsupported browsers")
        return result
    }

    /**
     * Returns true if the given package is in [SUPPORTED_BROWSERS] — i.e. the
     * app promises never to block it. Useful for showing a "supported" badge
     * in the picker UI.
     */
    fun isSupportedBrowser(packageName: String): Boolean =
        SUPPORTED_BROWSERS.contains(packageName)

    /**
     * Returns true if the given package is a browser (declares http/https
     * intent filter OR matches a known browser package-name prefix). This
     * is the same logic used by `MyAccessibilityService.isBrowserPackageDetected`
     * but is callable without an accessibility service instance.
     *
     * Used by the picker to show a browser badge for any browser-capable app.
     */
    fun isBrowserPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        signatureCache[packageName]?.let { return it }

        val pm = try {
            PackageManagerProvider.packageManager
        } catch (t: Throwable) {
            return isBrowserByPackageSignature(packageName)
        }

        val isBrowser = try {
            val httpIntent = Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("http://www.google.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val httpsIntent = Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val httpResolved = pm.queryIntentActivities(httpIntent, 0)
            val httpsResolved = pm.queryIntentActivities(httpsIntent, 0)
            val byIntent = httpResolved.any { it.activityInfo?.packageName == packageName } ||
                httpsResolved.any { it.activityInfo?.packageName == packageName }
            byIntent || isBrowserByPackageSignature(packageName)
        } catch (t: Throwable) {
            Timber.tag(TAG).v(t, "Browser detection failed for $packageName")
            isBrowserByPackageSignature(packageName)
        }

        signatureCache[packageName] = isBrowser
        return isBrowser
    }

    /**
     * Fallback browser detection by package-name prefix. Matches the same
     * list used by `MyAccessibilityService.isBrowserByPackageSignature`.
     */
    private fun isBrowserByPackageSignature(packageName: String): Boolean {
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

    /**
     * Force the next call to [getUnsupportedBrowserPackages] to re-query
     * the PackageManager. Call this from a package install/remove receiver.
     */
    fun invalidateCache() {
        cachedUnsupportedBrowsers = null
        cacheTimestamp = 0L
        signatureCache.clear()
        Timber.tag(TAG).d("Cache invalidated")
    }
}
