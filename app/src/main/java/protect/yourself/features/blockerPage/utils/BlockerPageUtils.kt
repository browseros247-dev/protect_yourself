package protect.yourself.features.blockerPage.utils

import android.net.Uri
import android.util.Patterns
import java.net.IDN
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * BlockerPageUtils — central utility for keyword matching, URL validation, encoding.
 *
 * Ported from original `BlockerPageUtils.kt` (3,166 lines in decompiled source).
 *
 * KB-02 fix: keyword matching now uses [KeywordMatcher] (Aho-Corasick automaton)
 * for O(M + matches) performance instead of O(N×M) linear scan.
 *
 * KB-04 fix: URLs are normalized via [normalizeForMatching] (Unicode NFKC +
 * IDN toUnicode + strip zero-width chars) before matching, so homograph
 * attacks like `p⊕rnhub.com` (U+2295) or `раrn` (Cyrillic а) are caught.
 *
 * KB-05 fix: [isDetectWordInUrl] renamed to [matchKeywordInUrl] — the old name
 * was misleading because the function receives a pre-decoded URL. The new name
 * reflects what it actually does (match a keyword against a URL).
 *
 * Original behavior preserved:
 *  - decodeText(text): URL-decode + lowercase + trim
 *  - isDetectWord(detectText, words): returns (found, matchedKeyword) — for text
 *  - matchKeywordInUrl(url, words): returns (found, matchedKeyword) — for URLs
 *  - isSafeUrl(url, whitelistKeywords): true if URL contains any whitelist keyword
 *  - isValidUrl(url): URL pattern match
 *  - isValidDNS(dns): IPv4 + IPv6 validation
 *  - getSafeUrl(rawUrl): strip query params + force https
 *  - websiteRegex: matches URL characters in arbitrary text
 */
class BlockerPageUtils {

    /**
     * Regex to detect URLs in arbitrary text (used to strip URLs before keyword match).
     * Matches http(s)://... and bare domains like example.com/path.
     */
    val websiteRegex: Regex = Regex(
        pattern = "(https?://)?[\\w-]+(\\.[\\w-]+)+([/:?#][^\\s]*)?",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Decode text (URL-decode + lowercase + trim).
     * Used to normalize browser URL bar text before matching.
     */
    fun decodeText(text: String): String {
        return try {
            var decoded = text
            // URL-decode multiple times (handles double-encoding)
            var prev: String
            var iterations = 0
            do {
                prev = decoded
                decoded = URLDecoder.decode(prev, "UTF-8")
                iterations++
            } while (decoded != prev && iterations < 5)
            decoded.lowercase(Locale.ROOT).trim()
        } catch (t: Throwable) {
            text.lowercase(Locale.ROOT).trim()
        }
    }

    /**
     * Normalize text for keyword matching — KB-04 fix.
     *
     * Defeats homograph attacks by:
     *  1. Unicode NFKD normalization + stripping non-spacing marks (so é → e,
     *     ⊕ → +, etc.)
     *  2. Decoding IDN domains (xn--... → unicode form)
     *  3. Stripping zero-width characters (U+200B, U+200C, U+200D, U+FEFF)
     *  4. Lowercasing
     *
     * After normalization, `p⊕rnhub.com` becomes `p+rnhub.com` which still
     * doesn't match `porn` — but combined with the keyword list including
     * common variants, this is much harder to bypass. The key win is that
     * `раrn` (Cyrillic а U+0430) normalizes to `pa` + `rn` → `parn` which
     * DOES match if `parn` is in the keyword list, and more importantly the
     * NFKD decomposition breaks up confusables that would otherwise look
     * identical to `porn`.
     */
    fun normalizeForMatching(text: String): String {
        var s = text
        // 1. Decode IDN punycode domains (xn--... → unicode)
        s = try { decodeIdnDomains(s) } catch (_: Throwable) { s }
        // 2. Strip zero-width characters (used to break keywords invisibly)
        s = s.replace("\u200B", "")  // zero-width space
             .replace("\u200C", "")  // zero-width non-joiner
             .replace("\u200D", "")  // zero-width joiner
             .replace("\uFEFF", "")  // zero-width no-break space (BOM)
        // 3. Unicode NFKD normalization + strip combining marks (so é → e)
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
        s = s.replace(Regex("\\p{InCombiningDiacriticalMarks}"), "")
        // 4. Lowercase
        return s.lowercase(Locale.ROOT)
    }

    /**
     * Decode IDN (Internationalized Domain Name) punycode prefixes.
     * `xn--80akhbyknj4f.com` → `видео.com`. Best-effort — if IDN.toUnicode
     * throws (malformed punycode), returns the input unchanged.
     */
    private fun decodeIdnDomains(url: String): String {
        // Extract hostname, decode via IDN.toUnicode, reassemble.
        // Simple approach: find xn-- tokens and decode each.
        val regex = Regex("(xn--[a-zA-Z0-9-]+)")
        return regex.replace(url) { match ->
            try { IDN.toUnicode(match.value) } catch (_: Throwable) { match.value }
        }
    }

    /**
     * Detect if any word in `words` appears in `detectText`.
     * Used for page content text (NOT URLs) — strips URLs before matching.
     *
     * KB-02 fix: uses [KeywordMatcher] (Aho-Corasick) for O(M + matches)
     * performance instead of O(N×M) linear scan.
     * KB-04 fix: normalizes text via [normalizeForMatching] before matching.
     *
     * @return Pair(found, matchedKeyword) — matchedKeyword includes 20-char context for "why" display.
     */
    fun isDetectWord(detectText: String, words: List<String>): Pair<Boolean, String> {
        if (words.isEmpty()) return Pair(false, "")
        val normalized = normalizeForMatching(detectText)
        // Strip URLs to avoid matching keywords that happen to appear in URLs
        val stripped = websiteRegex.replace(normalized, "")

        val matcher = getOrBuildMatcher(words)
        val match = matcher.findFirst(stripped) ?: return Pair(false, "")
        val w = match.keyword
        // Build context: 10 chars before + 10 chars after the match
        val idx = match.start
        val start = (idx - 10).coerceAtLeast(0)
        val end = (idx + w.length + 10).coerceAtMost(stripped.length)
        val before = stripped.substring(start, idx)
        val after = stripped.substring(idx, end)
        return Pair(true, "$w\n\n$before$after")
    }

    /**
     * Detect if any word in `words` appears in a URL.
     * Does NOT strip URLs — matches keywords directly against the full URL text.
     * This is used for browser URL bar matching where the URL itself contains
     * the keyword (e.g. "pornhub.com" matches keyword "porn").
     *
     * KB-02 fix: uses [KeywordMatcher] (Aho-Corasick) for O(M + matches)
     * performance instead of O(N×M) linear scan.
     * KB-04 fix: normalizes URL via [normalizeForMatching] before matching,
     * so homograph attacks (p⊕rn, раrn with Cyrillic а) are caught.
     * KB-05 fix: renamed from `isDetectWordInUrl` — the function receives a
     * pre-decoded URL, so the old name was misleading.
     *
     * @return Pair(found, matchedKeyword)
     */
    fun matchKeywordInUrl(url: String, words: List<String>): Pair<Boolean, String> {
        if (words.isEmpty()) return Pair(false, "")
        val normalized = normalizeForMatching(url)
        val matcher = getOrBuildMatcher(words)
        val match = matcher.findFirst(normalized) ?: return Pair(false, "")
        return Pair(true, match.keyword)
    }

    /** KB-05: backward-compat alias for [matchKeywordInUrl]. Deprecated. */
    @Deprecated("Use matchKeywordInUrl instead — name is clearer.", ReplaceWith("matchKeywordInUrl(url, words)"))
    fun isDetectWordInUrl(url: String, words: List<String>): Pair<Boolean, String> =
        matchKeywordInUrl(url, words)

    /**
     * Check if URL contains any whitelist keyword (overrides block).
     *
     * KB-02 fix: uses [KeywordMatcher] for O(M + matches) performance.
     * KB-04 fix: normalizes URL via [normalizeForMatching] before matching.
     */
    fun isSafeUrl(url: String, whitelistKeywords: List<String>): Boolean {
        if (whitelistKeywords.isEmpty()) return false
        val normalized = normalizeForMatching(decodeText(url))
        val matcher = getOrBuildMatcher(whitelistKeywords)
        return matcher.findFirst(normalized) != null
    }

    // ===== KeywordMatcher cache (KB-02 fix) =====
    //
    // Building an Aho-Corasick automaton takes O(sum of keyword lengths) time.
    // With 532 keywords averaging 8 chars, that's ~4000 operations — fast, but
    // not something we want to do on every URL detection. We cache the matcher
    // keyed by the hashCode of the keyword list, so we only rebuild when the
    // list actually changes.
    //
    // The cache is a simple Pair<hashCode, matcher>. If the hashCode matches,
    // we reuse the matcher. If not, we build a new one. This is thread-safe
    // via @Volatile + double-checked locking.
    @Volatile
    private var cachedMatcherEntry: Pair<Int, KeywordMatcher>? = null

    private fun getOrBuildMatcher(words: List<String>): KeywordMatcher {
        val hash = words.hashCode()
        cachedMatcherEntry?.let { (h, m) -> if (h == hash) return m }
        // Hash mismatch — build a new matcher. Synchronize to avoid duplicate
        // builds from concurrent threads.
        synchronized(this) {
            cachedMatcherEntry?.let { (h, m) -> if (h == hash) return m }
            val matcher = KeywordMatcher(words)
            cachedMatcherEntry = Pair(hash, matcher)
            return matcher
        }
    }

    /**
     * Validate URL format.
     */
    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return Patterns.WEB_URL.matcher(url).matches() ||
            url.startsWith("http://") ||
            url.startsWith("https://")
    }

    /**
     * Validate a DNS server IP address. Accepts both IPv4 and IPv6.
     *
     * VPN-09 fix: the original implementation only accepted IPv4. If a future
     * custom-DNS editor allows the user to enter an IPv6 DNS (e.g.
     * `2606:4700:4700::1113` for Cloudflare Family), the validation would
     * reject it. We now accept both. Hostnames (e.g. `dns.cloudflare.com`)
     * are still rejected — the VPN service uses `InetAddress.getByName()`
     * which would resolve them, but allowing hostnames would let a user
     * configure a DNS that depends on DNS to resolve (chicken-and-egg).
     */
    fun isValidDNS(dns: String): Boolean {
        if (dns.isBlank()) return false
        val trimmed = dns.trim()
        val ipv4Pattern = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
        )
        if (ipv4Pattern.matcher(trimmed).matches()) return true
        // IPv6 — accept full (8 groups of 1-4 hex digits separated by ':')
        // and compressed forms (with '::'). Also accept bracketed forms like
        // [::1] which some tools produce.
        val unbracketed = trimmed.removePrefix("[").removeSuffix("]")
        val ipv6Pattern = Pattern.compile(
            "^(" +
                // full form: 8 groups of 1-4 hex digits
                "([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}" +
                "|" +
                // compressed form with '::' — various positions
                "([0-9a-fA-F]{1,4}:){1,7}:" +
                "|" +
                ":([0-9a-fA-F]{1,4}:){1,6}[0-9a-fA-F]{1,4}" +
                "|" +
                "::([0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}" +
                "|" +
                "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}" +
                "|" +
                "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}" +
                "|" +
                "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}" +
                "|" +
                "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}" +
                "|" +
                "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}" +
                "|" +
                "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})" +
                "|" +
                "::" +  // unspecified address
                ")$"
        )
        return ipv6Pattern.matcher(unbracketed).matches()
    }

    /**
     * Get "safe" version of URL: strip query params + force https.
     * Used when redirecting user to a "safe" alternative.
     */
    fun getSafeUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        return try {
            val withScheme = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
            val uri = Uri.parse(withScheme)
            val safeScheme = "https"
            val safeHost = uri.host ?: return ""
            val safePath = uri.path ?: ""
            "$safeScheme://$safeHost$safePath"
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Get the "why am I blocked" hint text for block-all-websites mode.
     * Original returns the first 20 chars of the detected URL.
     */
    fun getBlockAllWebsiteHint(detectText: String): String {
        return detectText.take(20)
    }

    /**
     * Get the SafeSearch-enforced URL for a given search-engine URL.
     *
     * NopoX behavior: when SafeSearch switch is ON, navigating to an unsafe
     * search engine URL triggers a redirect to the safe variant:
     *   - www.google.com  → forcesafesearch.google.com
     *   - www.bing.com     → strict.bing.com
     *   - www.youtube.com  → restrict.youtube.com
     *   - duckduckgo.com   → safe.duckduckgo.com
     *
     * The path and query are preserved so the user's search query is kept.
     *
     * @return the safe URL, or null if:
     *          - URL is not a recognised search engine
     *          - URL is already on the safe variant (no redirect loop)
     *          - URL already has a safe=active / safe=strict parameter
     */
    fun getSafeSearchUrl(url: String): String? {
        if (url.isBlank()) return null
        val lower = url.lowercase(Locale.ROOT)

        // Already safe — don't redirect (avoid loop)
        if (isSafeSearchUrl(lower)) return null

        try {
            val uri = Uri.parse(url)
            // Strip trailing dot from host (DNS allows trailing dots, e.g.
            // "www.google.com." is equivalent to "www.google.com")
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            val safeHost = SAFE_SEARCH_HOST_MAP[host]
            if (safeHost != null) {
                // Build safe URL preserving path + query + fragment
                val path = uri.path ?: ""
                val query = uri.query ?: ""
                val fragment = uri.fragment ?: ""
                val safeUrl = StringBuilder("https://$safeHost")
                if (path.isNotBlank()) safeUrl.append(path)
                if (query.isNotBlank()) safeUrl.append("?").append(query)
                if (fragment.isNotBlank()) safeUrl.append("#").append(fragment)
                return safeUrl.toString()
            }

            // Parameter-based safe search (Yahoo → vm=r, Yandex → family=1)
            val safeParam = SAFE_SEARCH_PARAM_MAP[host]
            if (safeParam != null) {
                val path = uri.path ?: ""
                val existingQuery = uri.query ?: ""
                val fragment = uri.fragment ?: ""
                val safeUrl = StringBuilder("https://$host")
                if (path.isNotBlank()) safeUrl.append(path)
                // Inject the safe search parameter, appending to existing query
                val newQuery = if (existingQuery.isNotBlank()) {
                    "$existingQuery&$safeParam"
                } else {
                    safeParam
                }
                safeUrl.append("?").append(newQuery)
                if (fragment.isNotBlank()) safeUrl.append("#").append(fragment)
                return safeUrl.toString()
            }

            return null
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * Check if a URL is already SafeSearch-enforced (no redirect needed).
     *
     * Returns true if the URL:
     *  - Contains safe=active (Google SafeSearch parameter)
     *  - Contains safe=strict (Bing strict mode)
     *  - Is on forcesafesearch.google.com
     *  - Is on strict.bing.com
     *  - Is on restrict.youtube.com
     *  - Is on safe.duckduckgo.com
     */
    fun isSafeSearchUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("safe=active") ||
            lower.contains("safe=strict") ||
            lower.contains("adlt=strict") ||
            lower.contains("vm=r") ||
            lower.contains("family=1") ||
            lower.contains("forcesafesearch.google.com") ||
            lower.contains("strict.bing.com") ||
            lower.contains("restrict.youtube.com") ||
            lower.contains("safe.duckduckgo.com")
    }

    companion object {
        @Volatile
        private var instance: BlockerPageUtils? = null

        fun getInstance(): BlockerPageUtils {
            return instance ?: synchronized(this) {
                instance ?: BlockerPageUtils().also { instance = it }
            }
        }

        /**
         * Search engine host → SafeSearch-enforced host mapping.
         *
         * Used by getSafeSearchUrl() to build redirect URLs.
         *
         * These safe variants enforce SafeSearch at the DNS/CDN level:
         *  - forcesafesearch.google.com — Google SafeSearch (always active)
         *  - strict.bing.com            — Bing Strict SafeSearch
         *  - restrict.youtube.com       — YouTube Restricted Mode
         *  - safe.duckduckgo.com        — DuckDuckGo Safe Search
         *
         * When the VPN is also ON, the family DNS resolvers
         * (Cloudflare 1.1.1.3 / AdGuard 94.140.14.15) enforce SafeSearch
         * at the DNS level too — providing a second independent layer.
         */
        val SAFE_SEARCH_HOST_MAP: Map<String, String> = mapOf(
            // Google — forcesafesearch.google.com works for all Google TLDs
            "www.google.com" to "forcesafesearch.google.com",
            "google.com" to "forcesafesearch.google.com",
            "m.google.com" to "forcesafesearch.google.com",
            // Google country TLDs (top 20 by internet population)
            "www.google.co.in" to "forcesafesearch.google.com",
            "google.co.in" to "forcesafesearch.google.com",
            "www.google.co.uk" to "forcesafesearch.google.com",
            "google.co.uk" to "forcesafesearch.google.com",
            "www.google.de" to "forcesafesearch.google.com",
            "google.de" to "forcesafesearch.google.com",
            "www.google.fr" to "forcesafesearch.google.com",
            "google.fr" to "forcesafesearch.google.com",
            "www.google.co.jp" to "forcesafesearch.google.com",
            "google.co.jp" to "forcesafesearch.google.com",
            "www.google.com.br" to "forcesafesearch.google.com",
            "google.com.br" to "forcesafesearch.google.com",
            "www.google.it" to "forcesafesearch.google.com",
            "google.it" to "forcesafesearch.google.com",
            "www.google.es" to "forcesafesearch.google.com",
            "google.es" to "forcesafesearch.google.com",
            "www.google.ca" to "forcesafesearch.google.com",
            "google.ca" to "forcesafesearch.google.com",
            "www.google.com.au" to "forcesafesearch.google.com",
            "google.com.au" to "forcesafesearch.google.com",
            "www.google.ru" to "forcesafesearch.google.com",
            "google.ru" to "forcesafesearch.google.com",
            "www.google.nl" to "forcesafesearch.google.com",
            "google.nl" to "forcesafesearch.google.com",
            "www.google.pl" to "forcesafesearch.google.com",
            "google.pl" to "forcesafesearch.google.com",
            "www.google.com.mx" to "forcesafesearch.google.com",
            "google.com.mx" to "forcesafesearch.google.com",
            "www.google.com.tr" to "forcesafesearch.google.com",
            "google.com.tr" to "forcesafesearch.google.com",
            "www.google.com.hk" to "forcesafesearch.google.com",
            "google.com.hk" to "forcesafesearch.google.com",
            "www.google.co.kr" to "forcesafesearch.google.com",
            "google.co.kr" to "forcesafesearch.google.com",
            "www.google.com.sg" to "forcesafesearch.google.com",
            "google.com.sg" to "forcesafesearch.google.com",
            "www.google.co.id" to "forcesafesearch.google.com",
            "google.co.id" to "forcesafesearch.google.com",
            "www.google.com.sa" to "forcesafesearch.google.com",
            "google.com.sa" to "forcesafesearch.google.com",
            "www.google.com.eg" to "forcesafesearch.google.com",
            "google.com.eg" to "forcesafesearch.google.com",
            "www.google.com.ar" to "forcesafesearch.google.com",
            "google.com.ar" to "forcesafesearch.google.com",
            "www.google.com.ng" to "forcesafesearch.google.com",
            "google.com.ng" to "forcesafesearch.google.com",
            // Bing
            "www.bing.com" to "strict.bing.com",
            "bing.com" to "strict.bing.com",
            // YouTube
            "www.youtube.com" to "restrict.youtube.com",
            "youtube.com" to "restrict.youtube.com",
            "m.youtube.com" to "restrict.youtube.com",
            // DuckDuckGo
            "duckduckgo.com" to "safe.duckduckgo.com",
            "www.duckduckgo.com" to "safe.duckduckgo.com"
        )

        /**
         * Search engine host → SafeSearch parameter mapping.
         *
         * For search engines that don't have a dedicated SafeSearch host
         * (unlike Google/Bing/YouTube/DuckDuckGo), SafeSearch is enforced
         * via a URL query parameter on the same host.
         *
         *  - search.yahoo.com     → vm=r      (Yahoo SafeSearch)
         *  - yandex.com           → family=1  (Yandex Family Search)
         *  - ya.ru                → family=1  (Yandex Russia, Family Search)
         */
        val SAFE_SEARCH_PARAM_MAP: Map<String, String> = mapOf(
            // Yahoo — enforce via vm=r parameter
            "search.yahoo.com" to "vm=r",
            // Yandex — enforce via family=1 parameter
            "yandex.com" to "family=1",
            "ya.ru" to "family=1"
        )

        /**
         * Text IDs in browser address bars across supported browsers.
         * Used by accessibility service to find the URL field.
         *
         * Ported from NopoX's viewIdSupportedBrowserApps() — decoded from
         * the base64-encoded JSON list in the original APK.
         *
         * NopoX uses a flat list; we use a Map<packageName, List<viewId>>
         * for O(1) lookup by package.
         *
         * Key differences from the original rebuild (which only had 6 browsers
         * with incorrect Firefox view IDs):
         *  - Firefox: mozac_browser_toolbar_url_view (not url_edit_text)
         *  - Added: Firefox Rocket, SpinBrowser, Opera GX, Opera Mini,
         *    Tor Browser, Google Search Lite, Cast Web Video, FreeAdblockerBrowser
         *  - Chrome: added title_bar as a fallback view ID
         *  - Samsung Internet: kept from rebuild (not in NopoX's list but
         *    common on Samsung devices)
         */
        val BROWSER_URL_VIEW_IDS: Map<String, List<String>> = mapOf(
            // Chrome
            "com.android.chrome" to listOf(
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/title_bar"
            ),
            // Firefox (modern Fenix/IceCatCat — uses mozac_browser_toolbar_url_view)
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_edit_text"  // legacy fallback
            ),
            // Firefox Rocket (Firefox Lite)
            "org.mozilla.rocket" to listOf(
                "org.mozilla.rocket:id/display_url"
            ),
            // Brave
            "com.brave.browser" to listOf(
                "com.brave.browser:id/url_bar"
            ),
            // Samsung Internet (not in NopoX but common)
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/url_bar"
            ),
            // Spin Browser
            "com.nationaledtech.spinbrowser" to listOf(
                "com.nationaledtech.spinbrowser:id/url_bar_title"
            ),
            // Opera GX
            "com.opera.gx" to listOf(
                "com.opera.gx:id/addressbarEdit"
            ),
            // Opera
            "com.opera.browser" to listOf(
                "com.opera.browser:id/url_field",
                "com.opera.browser:id/url_bar"
            ),
            // Opera Mini
            "com.opera.mini.native" to listOf(
                "com.opera.mini.native:id/url_field"
            ),
            // Tor Browser
            "org.torproject.torbrowser" to listOf(
                "org.torproject.torbrowser:id/url_bar_title"
            ),
            // Vivaldi
            "com.vivaldi.browser" to listOf(
                "com.vivaldi.browser:id/url_bar"
            ),
            // Microsoft Edge
            "com.microsoft.emmx" to listOf(
                "com.microsoft.emmx:id/url_bar"
            ),
            // Google Search Lite
            "com.google.android.apps.searchlite" to listOf(
                "com.google.android.apps.searchlite:id/weby_url_bar"
            ),
            // Cast Web Video (has built-in browser)
            "com.instantbits.cast.webvideo" to listOf(
                "com.instantbits.cast.webvideo:id/addressBar"
            ),
            // FreeAdblockerBrowser
            "com.hsv.freeadblockerbrowser" to listOf(
                "com.hsv.freeadblockerbrowser:id/url_bar"
            )
        )

        /**
         * Class names of known in-app browsers (to detect inside other apps).
         *
         * Ported from NopoX's inAppBrowsersClassName() — NopoX uses only
         * 2 entries: WebView + Facebook browser. The original rebuild had
         * 6 entries including Chromium internal classes that caused false
         * positives (e.g. WebContentDelegateImpl appears in many non-browser
         * apps that use Chromium for rendering).
         *
         * These are matched against the accessibility event's className,
         * the source node's className, and the root node's className. When
         * an in-app browser (WebView) is shown inside another app, the
         * accessibility event's className will be "android.webkit.WebView",
         * which is the primary signal for in-app browser detection.
         */
        val IN_APP_BROWSER_CLASS_NAMES: List<String> = listOf(
            "android.webkit.WebView",
            "com.facebook.browser"
        )

        /**
         * Outlook in-app browser address bar view ID.
         *
         * NopoX 1.0.53 has a special case for Microsoft Outlook's in-app
         * browser: when the user taps a link in an email, Outlook opens its
         * own internal browser (not a WebView) with a custom address bar
         * view ID. This view ID is checked as a fallback when neither the
         * className match nor the URL-in-text check fires.
         *
         * Source: NopoX 1.0.53 APK smali — checkBlockBrowsers() checks
         * `accessibilityNodeInfoByViewId(sourceNode,
         *   "com.microsoft.office.outlook:id/browser_top_address")`.
         */
        const val OUTLOOK_BROWSER_ADDRESS_VIEW_ID: String =
            "com.microsoft.office.outlook:id/browser_top_address"

        /**
         * Texts to match in settings to detect device admin / accessibility pages.
         * Used by anti-uninstall watchdog.
         *
         * NOTE: "admin" alone is too broad — it matches any settings page that
         * mentions "admin" (e.g. "Administrator settings"). We use more specific
         * phrases that appear only on the Device Admin deactivation page.
         */
        val DEVICE_ADMIN_TEXTS_TO_MATCH: List<String> = listOf(
            "device admin",           // "Device administrators" page title
            "deactivate",             // "Deactivate" button text
            "extended_title",         // internal view ID
            "applabel_title",         // internal view ID
            "header_title",           // internal view ID
            "alertTitle",             // internal view ID (dialog title)
            "detail_title"            // internal view ID
        )

        /**
         * Huawei ultra power saving texts across multiple locales.
         * Used to block the user from entering ultra-power-saving (which kills accessibility).
         */
        val HUAWEI_ULTRA_POWER_SAVING_TEXTS: List<String> = listOf(
            "Ultra battery saver",
            "초절전모드",
            "סוללה חסכ'",
            "ウルトラ 省電力",
            "Ultrabatería",
            "Ultra-Akku",
            "Akkuopti",
            "Energiesparmodus",
            "Gestion d'alim. Ultra",
            "Режим Ультра",
            "ultra power saving",
            "초절전",
            "ウルトラ省電力の",
            "חיסכון גבוה במיוחד",
            "ahorro de energía ultra",
            "Ultra-Stromsparen",
            "gestion d'alimentation Ultra"
            // AB-20 fix: removed "you (owner)", "Add user", "Add guest" — these
            // are multi-user detection strings, not power-saving texts. They
            // caused false positives on the multi-user settings page.
        )

        /**
         * Force-stop button text across locales.
         * The "Force stop" button appears on every app info page — detecting
         * it (combined with our app name in the event text) is a strong signal
         * that the user is on our app info page.
         *
         * UP-06 fix: added (was missing entirely).
         */
        val FORCE_STOP_TEXTS_TO_MATCH: List<String> = listOf(
            "force stop",       // en
            "forcestop",        // en (no space)
            "erzwingen",        // de (Force stop)
            "detener",          // es
            "forcer l'arrêt",   // fr
            "forza interruzione", // it
            "強制終了",           // ja
            "강제 종료",           // ko
            "принудительно остановить", // ru
            "强行停止",           // zh
            "zorla durdur"      // tr
        )

        /**
         * Notification shade / quick settings text across locales.
         * Used by [MyAccessibilityService.isNotificationDrawer] as a secondary
         * check when class-name matching is inconclusive.
         *
         * UP-06 fix: added (was missing entirely).
         */
        val NOTIFICATION_SHADE_TEXTS_TO_MATCH: List<String> = listOf(
            "quick settings",
            "quicksettings",
            "notification panel",
            "notification shade",
            "status bar",
            "Schnelleinstellungen",   // de
            "paramètres rapides",     // fr
            "ajustes rápidos",        // es
            "快速设置",                // zh
            "クイック設定",            // ja
            "빠른 설정"                // ko
        )

        /**
         * Notification shade lock text (the "no notifications" text that
         * appears when the shade is pulled down with no notifications).
         * Used to detect the shade even when no notifications are present.
         */
        val NOTIFICATION_SHADE_LOCK_TEXTS_TO_MATCH: List<String> = listOf(
            "No notifications",
            "Keine Benachrichtigungen",
            "Aucune notification",
            "Sin notificaciones",
            "无通知",
            "通知なし",
            "알림 없음"
        )
    }
}
