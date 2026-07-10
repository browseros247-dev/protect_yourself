package protect.yourself.features.blockerPage.utils

import android.net.Uri
import android.util.Patterns
import timber.log.Timber
import java.net.URLDecoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * BlockerPageUtils — central utility for keyword matching, URL validation, encoding.
 *
 * Ported from original `BlockerPageUtils.kt` (3,166 lines in decompiled source).
 * Phase 2 implements the core matching logic; Phase 3 will add accessibility integration.
 *
 * Original behavior preserved:
 *  - decodeText(text): URL-decode + lowercase + trim
 *  - encodeText(text): URL-encode
 *  - isDetectWord(detectText, words): returns (found, matchedKeyword)
 *  - isSafeUrl(url, whitelistKeywords): true if URL contains any whitelist keyword
 *  - isValidUrl(url): URL pattern match
 *  - isValidDNS(dns): IPv4 validation
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
     * Encode text for URL usage.
     */
    fun encodeText(text: String): String {
        return try {
            java.net.URLEncoder.encode(text, "UTF-8")
        } catch (t: Throwable) {
            text
        }
    }

    /**
     * Detect if any word in `words` appears in `detectText`.
     * Used for page content text (NOT URLs) — strips URLs before matching.
     *
     * @return Pair(found, matchedKeyword) — matchedKeyword includes 20-char context for "why" display.
     */
    fun isDetectWord(detectText: String, words: List<String>): Pair<Boolean, String> {
        if (words.isEmpty()) return Pair(false, "")
        val lower = detectText.lowercase(Locale.ROOT)
        // Strip URLs to avoid matching keywords that happen to appear in URLs
        val stripped = websiteRegex.replace(lower, "")

        for (word in words) {
            if (word.isBlank()) continue
            val w = word.lowercase(Locale.ROOT).trim()
            if (stripped.contains(w)) {
                // Build context: 10 chars before + 10 chars after the match
                val idx = stripped.indexOf(w)
                val start = (idx - 10).coerceAtLeast(0)
                val end = (idx + w.length + 10).coerceAtMost(stripped.length)
                val before = stripped.substring(start, idx)
                val after = stripped.substring(idx, end)
                return Pair(true, "$w\n\n$before$after")
            }
        }
        return Pair(false, "")
    }

    /**
     * Detect if any word in `words` appears in a URL.
     * Does NOT strip URLs — matches keywords directly against the full URL text.
     * This is used for browser URL bar matching where the URL itself contains
     * the keyword (e.g. "pornhub.com" matches keyword "porn").
     *
     * @return Pair(found, matchedKeyword)
     */
    fun isDetectWordInUrl(url: String, words: List<String>): Pair<Boolean, String> {
        if (words.isEmpty()) return Pair(false, "")
        val lower = url.lowercase(Locale.ROOT)

        for (word in words) {
            if (word.isBlank()) continue
            val w = word.lowercase(Locale.ROOT).trim()
            if (lower.contains(w)) {
                return Pair(true, w)
            }
        }
        return Pair(false, "")
    }

    /**
     * Check if URL contains any whitelist keyword (overrides block).
     */
    fun isSafeUrl(url: String, whitelistKeywords: List<String>): Boolean {
        if (whitelistKeywords.isEmpty()) return false
        val lower = decodeText(url)
        return whitelistKeywords.any { kw ->
            kw.isNotBlank() && lower.contains(kw.lowercase(Locale.ROOT).trim())
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
     * Get the device brand identifier.
     */
    fun getDeviceBrand(): DeviceBrandIdentifiers.Brand {
        return DeviceBrandIdentifiers.detect()
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
            val host = uri.host?.lowercase() ?: return null
            val safeHost = SAFE_SEARCH_HOST_MAP[host] ?: return null

            // Build safe URL preserving path + query + fragment
            val path = uri.path ?: ""
            val query = uri.query ?: ""
            val fragment = uri.fragment ?: ""
            val safeUrl = StringBuilder("https://$safeHost")
            if (path.isNotBlank()) safeUrl.append(path)
            if (query.isNotBlank()) safeUrl.append("?").append(query)
            if (fragment.isNotBlank()) safeUrl.append("#").append(fragment)
            return safeUrl.toString()
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
            lower.contains("forcesafesearch.google.com") ||
            lower.contains("strict.bing.com") ||
            lower.contains("restrict.youtube.com") ||
            lower.contains("safe.duckduckgo.com")
    }

    /**
     * Check if a URL belongs to a search engine that SafeSearch can enforce.
     */
    fun isSearchEngineUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("google.com") ||
            lower.contains("bing.com") ||
            lower.contains("youtube.com") ||
            lower.contains("duckduckgo.com")
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
            // Google
            "www.google.com" to "forcesafesearch.google.com",
            "google.com" to "forcesafesearch.google.com",
            "m.google.com" to "forcesafesearch.google.com",
            "www.google.co.in" to "forcesafesearch.google.com",
            "www.google.co.uk" to "forcesafesearch.google.com",
            "www.google.de" to "forcesafesearch.google.com",
            "www.google.fr" to "forcesafesearch.google.com",
            "www.google.jp" to "forcesafesearch.google.com",
            "www.google.com.br" to "forcesafesearch.google.com",
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
         * Text IDs in browser address bars across supported browsers.
         * Used by accessibility service to find the URL field.
         *
         * Ported from original viewIdSupportedBrowserApps.
         * Phase 3: accessibility service uses this to scrape URLs.
         */
        val BROWSER_URL_VIEW_IDS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf(
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/url_field"
            ),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/url_edit_text",
                "org.mozilla.gecko:id/url_edit_text"
            ),
            "com.brave.browser" to listOf(
                "com.brave.browser:id/url_bar",
                "com.brave.browser:id/url_field"
            ),
            "com.microsoft.emmx" to listOf(
                "com.microsoft.emmx:id/url_bar",
                "com.microsoft.emmx:id/url_field"
            ),
            "com.opera.browser" to listOf(
                "com.opera.browser:id/url_field",
                "com.opera.browser:id/url_bar"
            ),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/url_bar"
            )
        )

        /**
         * Class names of known in-app browsers (to detect inside other apps).
         */
        val IN_APP_BROWSER_CLASS_NAMES: List<String> = listOf(
            "org.chromium.content.browser.WebContentDelegateImpl",
            "android.webkit.WebView",
            "com.google.android.webview",
            "com.android.webview",
            "org.chromium.chrome.browser.ChromeActivity",
            "com.google.android.gms.security.safetysample.SafeBrowsingWebViewActivity"
        )

        /**
         * Texts to match in settings to detect device admin / accessibility pages.
         * Used by anti-uninstall watchdog.
         */
        val DEVICE_ADMIN_TEXTS_TO_MATCH: List<String> = listOf(
            "admin", "extended_title", "applabel_title", "header_title", "alertTitle", "detail_title"
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
            "gestion d'alimentation Ultra",
            "you (owner)",
            "Add user",
            "Add guest"
        )

        /**
         * Xiaomi autostart permission page text (per locale).
         */
        fun autoStartXiaomiTextToMatch(): String {
            return when (java.util.Locale.getDefault().language) {
                "de" -> "autostart"
                "en" -> "autostart"
                "es" -> "Inicio automático"
                "fa" -> "راه‌اندازی خودکار"
                "fr" -> "démarrage automatique"
                "in" -> "Mulai otomatis"
                "it" -> "Avvio automatico"
                "iw" -> "הפעלה אוטומטית"
                "ja" -> "自動起動"
                "ko" -> "자동 시작"
                "pl" -> "autostart"
                "ru" -> "Автозапуск"
                "tr" -> "Otomatik başlangıç"
                "zh" -> "自启动"
                else -> "autostart"
            }
        }
    }
}
