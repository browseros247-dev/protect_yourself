package protect.yourself.features.blockerPage.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for BlockerPageUtils keyword matching + URL validation.
 *
 * Phase 2 covers core matching logic — 20 tests.
 * Phase 6 will add comprehensive instrumentation tests.
 */
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
        val (found, _) = utils.isDetectWordInUrl("https://pornhub.com", listOf("porn"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWordInUrl finds keyword in domain name`() {
        val (found, _) = utils.isDetectWordInUrl("pornhub.com/video", listOf("porn"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWordInUrl returns false for clean URL`() {
        val (found, _) = utils.isDetectWordInUrl("https://google.com", listOf("porn"))
        assertThat(found).isFalse()
    }

    @Test
    fun `isDetectWordInUrl returns false for empty word list`() {
        val (found, _) = utils.isDetectWordInUrl("https://anything.com", emptyList())
        assertThat(found).isFalse()
    }

    @Test
    fun `isDetectWordInUrl matches case-insensitively`() {
        val (found, _) = utils.isDetectWordInUrl("HTTPS://PORNHUB.COM", listOf("porn"))
        assertThat(found).isTrue()
    }

    @Test
    fun `isDetectWordInUrl finds keyword in URL path`() {
        val (found, _) = utils.isDetectWordInUrl("https://reddit.com/r/porn", listOf("porn"))
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

    // ===== isImageVideoUrl =====

    @Test
    fun `isImageVideoUrl detects Google Images search`() {
        assertThat(utils.isImageVideoUrl("https://google.com/search?q=test&tbm=isch")).isTrue()
    }

    @Test
    fun `isImageVideoUrl detects Google Videos search`() {
        assertThat(utils.isImageVideoUrl("https://google.com/search?q=test&tbm=vid")).isTrue()
    }

    @Test
    fun `isImageVideoUrl detects Bing image search`() {
        assertThat(utils.isImageVideoUrl("https://bing.com/images/search?q=test")).isTrue()
    }

    @Test
    fun `isImageVideoUrl returns false for regular search`() {
        assertThat(utils.isImageVideoUrl("https://google.com/search?q=test")).isFalse()
    }

    @Test
    fun `isImageVideoUrl is case-insensitive`() {
        assertThat(utils.isImageVideoUrl("HTTPS://GOOGLE.COM/?TBM=ISCH")).isTrue()
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
    fun `isValidDNS accepts with leading/trailing whitespace`() {
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
}
