package protect.yourself.features.blockerPage.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for BlockerPageUtils keyword matching + URL validation.
 *
 * Phase 2 covers core matching logic — 20 tests.
 * Phase 6 will add comprehensive instrumentation tests.
 *
 * Runs under Robolectric because [BlockerPageUtils.isValidUrl] uses
 * `android.util.Patterns.WEB_URL` and [BlockerPageUtils.getSafeUrl] uses
 * `android.net.Uri.parse` — both are Android framework classes that return
 * null/stubs in pure JVM unit tests. Robolectric provides real implementations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockerPageUtilsTest {

    private val utils = BlockerPageUtils()

    // ===== decodeText =====

    @Test
    fun `decodeText lowercases and trims`() {
        assertThat(utils.decodeText("  PORNO  ")).isEqualTo("porno")
        assertThat(utils.decodeText("MixedCase")).isEqualTo("mixedcase")
    }

    @Test
    fun `decodeText URL-decodes single encoding`() {
        assertThat(utils.decodeText("p%6Frn")).isEqualTo("porn")
        assertThat(utils.decodeText("hello%20world")).isEqualTo("hello world")
    }

    @Test
    fun `decodeText URL-decodes double encoding`() {
        // %254F → %4F (after one decode) → O (after second decode)
        assertThat(utils.decodeText("p%254Frn")).isEqualTo("pOrn".lowercase())
    }

    @Test
    fun `decodeText handles invalid encoding gracefully`() {
        assertThat(utils.decodeText("%zz")).isEqualTo("%zz")
    }

    // ===== isDetectWord =====

    @Test
    fun `isDetectWord finds keyword in plain text`() {
        val (found, _) = utils.isDetectWord("hello porno world", listOf("porno"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWord returns false when no keyword matches`() {
        val (found, _) = utils.isDetectWord("hello world", listOf("porno"))
        assertThat(found).isFalse()
    }

    @Test
    fun `isDetectWord returns false for empty word list`() {
        val (found, _) = utils.isDetectWord("anything", emptyList())
        assertThat(found).isFalse()
    }

    @Test
    fun `isDetectWord matches case-insensitively`() {
        val (found, _) = utils.isDetectWord("PORN site", listOf("porn"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWord ignores keywords inside URLs`() {
        // URLs are stripped before keyword match — "porn" inside example.com/porn won't be detected.
        val (found, _) = utils.isDetectWord("click example.com/porn here", listOf("porn"))
        assertThat(found).isFalse()
    }

    @Test
    fun `isDetectWord context returned with 10 chars before and after`() {
        val (_, matched) = utils.isDetectWord("1234567890porno1234567890", listOf("porno"))
        assertThat(matched).contains("porno")
        assertThat(matched).contains("1234567890")
    }

    // ===== isDetectWordInUrl (URL matching — does NOT strip URLs) =====

    @Test
    fun `isDetectWordInUrl finds keyword in URL`() {
        val (found, _) = utils.matchKeywordInUrl("https://pornhub.com", listOf("porn"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWordInUrl finds keyword in domain name`() {
        val (found, _) = utils.matchKeywordInUrl("pornhub.com/video", listOf("porn"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWordInUrl returns false for clean URL`() {
        val (found, _) = utils.matchKeywordInUrl("https://google.com", listOf("porn"))
        assertThat(found).isFalse()
    }

    @Test
    fun `isDetectWordInUrl returns false for empty word list`() {
        val (found, _) = utils.matchKeywordInUrl("https://anything.com", emptyList())
        assertThat(found).isFalse()
    }

    @Test
    fun `isDetectWordInUrl matches case-insensitively`() {
        val (found, _) = utils.matchKeywordInUrl("HTTPS://PORNHUB.COM", listOf("porn"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWordInUrl finds keyword in URL path`() {
        val (found, _) = utils.matchKeywordInUrl("https://reddit.com/r/porn", listOf("porn"))
        assertThat(found).isTrue()
    }

    // ===== isSafeUrl (whitelist) =====

    @Test
    fun `isSafeUrl returns true when URL contains whitelist keyword`() {
        assertThat(utils.isSafeUrl("https://reddit.com/r/nofap", listOf("nofap"))).isTrue()
    }

    @Test
    fun `isSafeUrl returns false when URL has no whitelist keyword`() {
        assertThat(utils.isSafeUrl("https://example.com", listOf("nofap"))).isFalse()
    }

    @Test
    fun `isSafeUrl returns false for empty whitelist`() {
        assertThat(utils.isSafeUrl("https://anything.com", emptyList())).isFalse()
    }

    @Test
    fun `isSafeUrl matches decoded URL`() {
        assertThat(utils.isSafeUrl("https://example.com/no%66ap", listOf("nofap"))).isTrue()
    }

    // ===== isValidUrl =====

    @Test
    fun `isValidUrl accepts http URLs`() {
        assertThat(utils.isValidUrl("http://example.com")).isTrue()
    }

    @Test
    fun `isValidUrl accepts https URLs`() {
        assertThat(utils.isValidUrl("https://example.com/path?q=1")).isTrue()
    }

    @Test
    fun `isValidUrl rejects empty string`() {
        assertThat(utils.isValidUrl("")).isFalse()
    }

    @Test
    fun `isValidUrl rejects plain text`() {
        assertThat(utils.isValidUrl("not a url")).isFalse()
    }

    // ===== isValidDNS =====

    @Test
    fun `isValidDNS accepts valid IPv4`() {
        assertThat(utils.isValidDNS("1.1.1.3")).isTrue()
        assertThat(utils.isValidDNS("208.67.222.123")).isTrue()
        assertThat(utils.isValidDNS("255.255.255.255")).isTrue()
        assertThat(utils.isValidDNS("0.0.0.0")).isTrue()
    }

    @Test
    fun `isValidDNS rejects out-of-range octets`() {
        assertThat(utils.isValidDNS("256.1.1.1")).isFalse()
        assertThat(utils.isValidDNS("1.256.1.1")).isFalse()
        assertThat(utils.isValidDNS("1.1.1.256")).isFalse()
    }

    @Test
    fun `isValidDNS rejects non-IPv4 input`() {
        assertThat(utils.isValidDNS("")).isFalse()
        assertThat(utils.isValidDNS("cloudflare-dns.com")).isFalse()
        assertThat(utils.isValidDNS("1.1.1")).isFalse()
        assertThat(utils.isValidDNS("1.1.1.1.1")).isFalse()
        assertThat(utils.isValidDNS("abc.def.ghi.jkl")).isFalse()
    }

    @Test
    fun `isValidDNS accepts with leading and trailing whitespace`() {
        assertThat(utils.isValidDNS("  1.1.1.3  ")).isTrue()
    }

    // ===== getSafeUrl =====

    @Test
    fun `getSafeUrl strips query params`() {
        val safe = utils.getSafeUrl("https://example.com/path?q=secret&token=abc")
        assertThat(safe).isEqualTo("https://example.com/path")
    }

    @Test
    fun `getSafeUrl forces https scheme`() {
        val safe = utils.getSafeUrl("http://example.com/path")
        assertThat(safe).startsWith("https://")
        assertThat(safe).doesNotContain("http://")
    }

    @Test
    fun `getSafeUrl adds https if missing`() {
        val safe = utils.getSafeUrl("example.com/path")
        assertThat(safe).isEqualTo("https://example.com/path")
    }

    @Test
    fun `getSafeUrl returns empty for blank input`() {
        assertThat(utils.getSafeUrl("")).isEmpty()
        assertThat(utils.getSafeUrl("   ")).isEmpty()
    }

    // ===== getBlockAllWebsiteHint =====

    @Test
    fun `getBlockAllWebsiteHint returns first 20 chars`() {
        val long = "https://verylongurlthatshouldbetruncated.com/path"
        val hint = utils.getBlockAllWebsiteHint(long)
        assertThat(hint).hasLength(20)
        assertThat(hint).isEqualTo(long.take(20))
    }

    @Test
    fun `getBlockAllWebsiteHint returns full string if under 20 chars`() {
        assertThat(utils.getBlockAllWebsiteHint("short")).isEqualTo("short")
    }

    // ===== getSafeSearchUrl (SS-01 fix, v1.0.54) =====
    //
    // Tests verify the NopoX 1.0.53 reference behaviour:
    //  - Google: append &safe=active (same host, requires /search path)
    //  - Bing: append &adlt=strict (same host, requires /search path)
    //  - Yahoo: append &vm=r (same host, requires /search path)
    //  - Yandex: append &family=yes (same host, requires /search path)
    //  - DuckDuckGo: replace host with safe.duckduckgo.com
    //  - YouTube: replace host with restrict.youtube.com

    @Test
    fun `getSafeSearchUrl returns null for blank URL`() {
        assertThat(utils.getSafeSearchUrl("")).isNull()
        assertThat(utils.getSafeSearchUrl("   ")).isNull()
    }

    @Test
    fun `getSafeSearchUrl returns null for non-search-engine URL`() {
        assertThat(utils.getSafeSearchUrl("https://example.com/path")).isNull()
        assertThat(utils.getSafeSearchUrl("https://github.com/repo")).isNull()
    }

    // --- Google ---

    @Test
    fun `getSafeSearchUrl Google appends safe=active for search path`() {
        val safe = utils.getSafeSearchUrl("https://www.google.com/search?q=cats")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("safe=active")
        assertThat(safe).startsWith("https://www.google.com/search")
    }

    @Test
    fun `getSafeSearchUrl Google preserves existing query params`() {
        val safe = utils.getSafeSearchUrl("https://www.google.com/search?q=cats&hl=en")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("q=cats")
        assertThat(safe).contains("hl=en")
        assertThat(safe).contains("safe=active")
    }

    @Test
    fun `getSafeSearchUrl Google country TLD also matched (substring)`() {
        // SS-01 fix: substring matching catches ALL country TLDs, not just
        // the 20 that were hardcoded in the old SAFE_SEARCH_HOST_MAP.
        val safe = utils.getSafeSearchUrl("https://www.google.co.za/search?q=cats")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("safe=active")
        assertThat(safe).contains("google.co.za")
    }

    @Test
    fun `getSafeSearchUrl Google DOES NOT redirect homepage (no search path)`() {
        // SS-01 fix: NopoX only redirects when path contains "search".
        // Homepage must NOT be redirected.
        assertThat(utils.getSafeSearchUrl("https://www.google.com/")).isNull()
        assertThat(utils.getSafeSearchUrl("https://www.google.com")).isNull()
    }

    @Test
    fun `getSafeSearchUrl Google returns null if already has safe=active`() {
        assertThat(utils.getSafeSearchUrl("https://www.google.com/search?q=cats&safe=active")).isNull()
    }

    @Test
    fun `getSafeSearchUrl Google returns null if already has safe=active at query start`() {
        assertThat(utils.getSafeSearchUrl("https://www.google.com/search?safe=active&q=cats")).isNull()
    }

    // --- Bing ---

    @Test
    fun `getSafeSearchUrl Bing appends adlt=strict for search path`() {
        val safe = utils.getSafeSearchUrl("https://www.bing.com/search?q=cats")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("adlt=strict")
        assertThat(safe).startsWith("https://www.bing.com/search")
    }

    @Test
    fun `getSafeSearchUrl Bing does NOT redirect homepage`() {
        assertThat(utils.getSafeSearchUrl("https://www.bing.com/")).isNull()
    }

    @Test
    fun `getSafeSearchUrl Bing returns null if already has adlt=strict`() {
        assertThat(utils.getSafeSearchUrl("https://www.bing.com/search?q=cats&adlt=strict")).isNull()
    }

    // --- Yahoo ---

    @Test
    fun `getSafeSearchUrl Yahoo appends vm=r for search path`() {
        val safe = utils.getSafeSearchUrl("https://search.yahoo.com/search?p=cats")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("vm=r")
        assertThat(safe).startsWith("https://search.yahoo.com/search")
    }

    @Test
    fun `getSafeSearchUrl Yahoo does NOT redirect homepage`() {
        assertThat(utils.getSafeSearchUrl("https://search.yahoo.com/")).isNull()
    }

    @Test
    fun `getSafeSearchUrl Yahoo returns null if already has vm=r`() {
        assertThat(utils.getSafeSearchUrl("https://search.yahoo.com/search?p=cats&vm=r")).isNull()
    }

    // --- Yandex ---

    @Test
    fun `getSafeSearchUrl Yandex appends family=yes (NOT family=1) for search path`() {
        // SS-01 fix: was "family=1" (invalid) — now "family=yes" (correct)
        val safe = utils.getSafeSearchUrl("https://yandex.com/search/?text=cats")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("family=yes")
        assertThat(safe).doesNotContain("family=1")
    }

    @Test
    fun `getSafeSearchUrl Yandex does NOT redirect homepage`() {
        assertThat(utils.getSafeSearchUrl("https://yandex.com/")).isNull()
    }

    @Test
    fun `getSafeSearchUrl Yandex returns null if already has family=yes`() {
        assertThat(utils.getSafeSearchUrl("https://yandex.com/search/?text=cats&family=yes")).isNull()
    }

    // --- DuckDuckGo ---

    @Test
    fun `getSafeSearchUrl DuckDuckGo replaces host with safe dot duckduckgo dot com`() {
        val safe = utils.getSafeSearchUrl("https://duckduckgo.com/?q=cats")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("safe.duckduckgo.com")
        assertThat(safe).contains("q=cats")
    }

    @Test
    fun `getSafeSearchUrl DuckDuckGo DOES redirect homepage (no path check required)`() {
        // Unlike Google/Bing/Yahoo/Yandex, DDG uses host replacement — no
        // /search path requirement. This matches NopoX behaviour.
        val safe = utils.getSafeSearchUrl("https://duckduckgo.com/")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("safe.duckduckgo.com")
    }

    @Test
    fun `getSafeSearchUrl DuckDuckGo returns null if already on safe host`() {
        assertThat(utils.getSafeSearchUrl("https://safe.duckduckgo.com/?q=cats")).isNull()
    }

    // --- YouTube ---

    @Test
    fun `getSafeSearchUrl YouTube replaces host with restrict dot youtube dot com`() {
        val safe = utils.getSafeSearchUrl("https://www.youtube.com/results?search_query=cats")
        assertThat(safe).isNotNull()
        assertThat(safe).contains("restrict.youtube.com")
    }

    @Test
    fun `getSafeSearchUrl YouTube returns null if already on restrict host`() {
        assertThat(utils.getSafeSearchUrl("https://restrict.youtube.com/results?search_query=cats")).isNull()
    }

    // ===== isSafeSearchUrl (SS-02 fix, v1.0.54) =====
    //
    // Tests verify the false-positive fixes:
    //  - "vm=r" no longer matches "vm=red", "vm=remote"
    //  - "family=1" no longer matches "family=10", "bfamily=1"
    //  - "safe=active" no longer matches "bsafe=active"

    @Test
    fun `isSafeSearchUrl returns false for blank URL`() {
        assertThat(utils.isSafeSearchUrl("")).isFalse()
    }

    @Test
    fun `isSafeSearchUrl returns true for safe=active parameter`() {
        assertThat(utils.isSafeSearchUrl("https://google.com/search?q=cats&safe=active")).isTrue()
        assertThat(utils.isSafeSearchUrl("https://google.com/search?safe=active&q=cats")).isTrue()
    }

    @Test
    fun `isSafeSearchUrl returns true for adlt=strict parameter`() {
        assertThat(utils.isSafeSearchUrl("https://bing.com/search?q=cats&adlt=strict")).isTrue()
    }

    @Test
    fun `isSafeSearchUrl returns true for vm=r parameter`() {
        assertThat(utils.isSafeSearchUrl("https://yahoo.com/search?p=cats&vm=r")).isTrue()
    }

    @Test
    fun `isSafeSearchUrl returns true for family=yes parameter`() {
        assertThat(utils.isSafeSearchUrl("https://yandex.com/search?text=cats&family=yes")).isTrue()
    }

    @Test
    fun `isSafeSearchUrl returns true for family=1 parameter (legacy support)`() {
        // Legacy support: some old URLs may have family=1 — still recognized.
        assertThat(utils.isSafeSearchUrl("https://yandex.com/search?text=cats&family=1")).isTrue()
    }

    @Test
    fun `isSafeSearchUrl returns true for safe hosts`() {
        assertThat(utils.isSafeSearchUrl("https://forcesafesearch.google.com/search?q=cats")).isTrue()
        assertThat(utils.isSafeSearchUrl("https://strict.bing.com/search?q=cats")).isTrue()
        assertThat(utils.isSafeSearchUrl("https://restrict.youtube.com/results?search_query=cats")).isTrue()
        assertThat(utils.isSafeSearchUrl("https://safe.duckduckgo.com/?q=cats")).isTrue()
    }

    @Test
    fun `isSafeSearchUrl returns false for non-safe URL`() {
        assertThat(utils.isSafeSearchUrl("https://google.com/search?q=cats")).isFalse()
        assertThat(utils.isSafeSearchUrl("https://example.com/path")).isFalse()
    }

    // --- SS-02 false-positive regression tests ---

    @Test
    fun `isSafeSearchUrl does NOT match vm=red as vm=r (SS-02 fix)`() {
        // OLD BUG: .contains("vm=r") matched "vm=red" → redirect skipped
        assertThat(utils.isSafeSearchUrl("https://yahoo.com/search?p=cats&vm=red")).isFalse()
    }

    @Test
    fun `isSafeSearchUrl does NOT match vm=remote as vm=r (SS-02 fix)`() {
        assertThat(utils.isSafeSearchUrl("https://yahoo.com/search?p=cats&vm=remote")).isFalse()
    }

    @Test
    fun `isSafeSearchUrl does NOT match family=10 as family=1 (SS-02 fix)`() {
        // OLD BUG: .contains("family=1") matched "family=10" → redirect skipped
        assertThat(utils.isSafeSearchUrl("https://yandex.com/search?text=cats&family=10")).isFalse()
    }

    @Test
    fun `isSafeSearchUrl does NOT match bfamily=1 as family=1 (SS-02 fix)`() {
        // OLD BUG: .contains("family=1") matched "bfamily=1" → redirect skipped
        assertThat(utils.isSafeSearchUrl("https://yandex.com/search?text=cats&bfamily=1")).isFalse()
    }

    @Test
    fun `isSafeSearchUrl does NOT match bsafe=active as safe=active (SS-02 fix)`() {
        // OLD BUG: .contains("safe=active") matched "bsafe=active" → redirect skipped
        assertThat(utils.isSafeSearchUrl("https://google.com/search?q=cats&bsafe=active")).isFalse()
    }

    @Test
    fun `isSafeSearchUrl does NOT match safe=active2 as safe=active (SS-02 fix)`() {
        assertThat(utils.isSafeSearchUrl("https://google.com/search?q=cats&safe=active2")).isFalse()
    }

    @Test
    fun `isSafeSearchUrl does NOT match safe=active in URL path (SS-02 fix)`() {
        // "safe=active" appearing in the path (not as a query param) should
        // NOT be considered SafeSearch-enforced.
        assertThat(utils.isSafeSearchUrl("https://example.com/safe=active/page")).isFalse()
    }
}
