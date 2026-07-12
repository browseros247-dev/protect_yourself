package protect.yourself.features.blockerPage.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for preset data integrity.
 *
 * Verifies that preset keywords, DNS presets, Stop Me durations,
 * supported browsers, and supported social media are correctly defined.
 */
class PresetDataTest {

    @Test
    fun `default DNS presets include Cloudflare Family as default`() {
        val cloudflare = DefaultDnsPresets.CLOUDFLARE_FAMILY
        assertThat(cloudflare.firstDns).isEqualTo("1.1.1.3")
        assertThat(cloudflare.secondDns).isEqualTo("1.0.0.3")
        assertThat(cloudflare.isSelectedByDefault).isTrue()
    }

    @Test
    fun `default DNS presets include OpenDNS FamilyShield`() {
        val opendns = DefaultDnsPresets.OPENDNS_FAMILY_SHIELD
        assertThat(opendns.firstDns).isEqualTo("208.67.222.123")
        assertThat(opendns.secondDns).isEqualTo("208.67.220.123")
        assertThat(opendns.isSelectedByDefault).isFalse()
    }

    @Test
    fun `default DNS presets include CleanBrowsing Family`() {
        val cleanbrowsing = DefaultDnsPresets.CLEANBROWSING_FAMILY
        assertThat(cleanbrowsing.firstDns).isEqualTo("185.228.168.168")
        assertThat(cleanbrowsing.secondDns).isEqualTo("185.228.169.168")
    }

    @Test
    fun `default DNS presets include AdGuard Family`() {
        val adguard = DefaultDnsPresets.ADGUARD_FAMILY
        assertThat(adguard.firstDns).isEqualTo("94.140.14.15")
        assertThat(adguard.secondDns).isEqualTo("94.140.15.16")
    }

    @Test
    fun `default DNS presets has 4 entries`() {
        assertThat(DefaultDnsPresets.ALL).hasSize(4)
    }

    @Test
    fun `default Stop Me durations has 4 entries`() {
        assertThat(DefaultStopMeDurations.ALL).hasSize(4)
    }

    @Test
    fun `default Stop Me durations are 15, 30, 60, 120 minutes`() {
        val durations = DefaultStopMeDurations.ALL.map { it.labelMinutes }
        assertThat(durations).containsExactly(15, 30, 60, 120)
    }

    @Test
    fun `default Stop Me durations convert correctly to millis`() {
        assertThat(DefaultStopMeDurations.FIFTEEN_MIN.durationMillis).isEqualTo(15L * 60 * 1000)
        assertThat(DefaultStopMeDurations.THIRTY_MIN.durationMillis).isEqualTo(30L * 60 * 1000)
        assertThat(DefaultStopMeDurations.ONE_HOUR.durationMillis).isEqualTo(60L * 60 * 1000)
        assertThat(DefaultStopMeDurations.TWO_HOURS.durationMillis).isEqualTo(120L * 60 * 1000)
    }

    @Test
    fun `default whitelist apps includes self package`() {
        assertThat(DefaultWhitelistApps.ALL).contains("protect.yourself")
    }

    @Test
    fun `default whitelist apps includes system UI`() {
        assertThat(DefaultWhitelistApps.ALL).contains("com.android.systemui")
    }

    // ===== BlockerPageUtils constants =====

    @Test
    fun `browser URL view IDs include Chrome`() {
        assertThat(BlockerPageUtils.BROWSER_URL_VIEW_IDS).containsKey("com.android.chrome")
        val chromeIds = BlockerPageUtils.BROWSER_URL_VIEW_IDS["com.android.chrome"]
        assertThat(chromeIds).isNotNull()
        assertThat(chromeIds).isNotEmpty()
    }

    @Test
    fun `in-app browser class names include WebView`() {
        assertThat(BlockerPageUtils.IN_APP_BROWSER_CLASS_NAMES).contains("android.webkit.WebView")
    }

    @Test
    fun `device admin texts include admin keyword`() {
        assertThat(BlockerPageUtils.DEVICE_ADMIN_TEXTS_TO_MATCH).contains("device admin")
    }

    @Test
    fun `huawei ultra power saving texts include multiple locales`() {
        val texts = BlockerPageUtils.HUAWEI_ULTRA_POWER_SAVING_TEXTS
        assertThat(texts).contains("Ultra battery saver")  // en
        assertThat(texts).contains("초절전모드")           // ko
        assertThat(texts).contains("Ultrabatería")         // es
        assertThat(texts).contains("Energiesparmodus")     // de
    }
}
