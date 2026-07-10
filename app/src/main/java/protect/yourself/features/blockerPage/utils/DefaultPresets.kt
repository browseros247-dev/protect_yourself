package protect.yourself.features.blockerPage.utils

import java.util.Locale

/**
 * Default VPN DNS presets — ported from original Protect Yourself.
 *
 * Each preset has a display name, two DNS IPs, and a flag indicating
 * whether it's pre-selected by default.
 *
 * Presets are inserted into vpn_custom_dns table on first launch.
 */
data class DnsPreset(
    val key: String,
    val displayName: String,
    val firstDns: String,
    val secondDns: String,
    val isSelectedByDefault: Boolean = false
)

object DefaultDnsPresets {

    /**
     * Cloudflare Family — blocks malware + adult content.
     * Selected by default (matches original).
     */
    val CLOUDFLARE_FAMILY = DnsPreset(
        key = "preset_cloudflare_family",
        displayName = "Cloudflare Family",
        firstDns = "1.1.1.3",
        secondDns = "1.0.0.3",
        isSelectedByDefault = true
    )

    /**
     * OpenDNS FamilyShield — blocks adult content.
     */
    val OPENDNS_FAMILY_SHIELD = DnsPreset(
        key = "preset_opendns_familyshield",
        displayName = "OpenDNS FamilyShield",
        firstDns = "208.67.222.123",
        secondDns = "208.67.220.123"
    )

    /**
     * CleanBrowsing Family — blocks adult content + mixed content.
     */
    val CLEANBROWSING_FAMILY = DnsPreset(
        key = "preset_cleanbrowsing_family",
        displayName = "CleanBrowsing Family",
        firstDns = "185.228.168.168",
        secondDns = "185.228.169.168"
    )

    /**
     * AdGuard Family — blocks ads + adult content.
     */
    val ADGUARD_FAMILY = DnsPreset(
        key = "preset_adguard_family",
        displayName = "AdGuard Family",
        firstDns = "94.140.14.15",
        secondDns = "94.140.15.16"
    )

    /** All presets to insert on first launch. */
    val ALL: List<DnsPreset> = listOf(
        CLOUDFLARE_FAMILY,
        OPENDNS_FAMILY_SHIELD,
        CLEANBROWSING_FAMILY,
        ADGUARD_FAMILY
    )
}

/**
 * Default Stop Me durations — ported from original.
 *
 * Inserted into stop_me_duration_table on first launch.
 */
data class StopMeDurationPreset(
    val key: String,
    val durationMillis: Long,
    val labelMinutes: Int
)

object DefaultStopMeDurations {

    val FIFTEEN_MIN = StopMeDurationPreset(
        key = "preset_stop_me_15",
        durationMillis = 15L * 60 * 1000,
        labelMinutes = 15
    )

    val THIRTY_MIN = StopMeDurationPreset(
        key = "preset_stop_me_30",
        durationMillis = 30L * 60 * 1000,
        labelMinutes = 30
    )

    val ONE_HOUR = StopMeDurationPreset(
        key = "preset_stop_me_60",
        durationMillis = 60L * 60 * 1000,
        labelMinutes = 60
    )

    val TWO_HOURS = StopMeDurationPreset(
        key = "preset_stop_me_120",
        durationMillis = 120L * 60 * 1000,
        labelMinutes = 120
    )

    val ALL: List<StopMeDurationPreset> = listOf(
        FIFTEEN_MIN,
        THIRTY_MIN,
        ONE_HOUR,
        TWO_HOURS
    )
}

/**
 * Default supported browsers — packages accessibility service will scrape for URLs.
 */
object DefaultSupportedBrowsers {

    data class BrowserApp(
        val packageName: String,
        val displayName: String,
        val viewIdForUrl: String? = null
    )

    val ALL: List<BrowserApp> = listOf(
        BrowserApp("com.android.chrome", "Google Chrome"),
        BrowserApp("org.mozilla.firefox", "Firefox"),
        BrowserApp("com.brave.browser", "Brave"),
        BrowserApp("com.microsoft.emmx", "Microsoft Edge"),
        BrowserApp("com.opera.browser", "Opera"),
        BrowserApp("com.sec.android.app.sbrowser", "Samsung Internet"),
        BrowserApp("com.vivaldi.browser", "Vivaldi"),
        BrowserApp("com.duckduckgo.mobile.android", "DuckDuckGo"),
        BrowserApp("com.mi.globalbrowser", "Mi Browser"),
        BrowserApp("org.mozilla.fennec_fdroid", "Fennec F-Droid"),
        BrowserApp("org.bromite.bromite", "Bromite")
    )
}

/**
 * Default whitelist apps — apps that bypass blocking (the blocker app itself + system UI).
 */
object DefaultWhitelistApps {

    val ALL: List<String> = listOf(
        "protect.yourself",       // self (applicationId)
        "com.android.systemui",   // system UI
        "com.android.settings",   // settings (so user can grant permissions)
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )
}

/**
 * Device brand detection — used to show brand-specific permission instructions.
 * (User chose to skip brand-specific guides, but we keep the detection for future use.)
 */
object DeviceBrandIdentifiers {
    enum class Brand { SAMSUNG, XIAOMI, HUAWEI, OPPO, VIVO, ONEPLUS, PIXEL, OTHER }

    fun detect(): Brand {
        val manufacturer = (android.os.Build.MANUFACTURER ?: "").lowercase(Locale.ROOT)
        return when {
            manufacturer.contains("samsung") -> Brand.SAMSUNG
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> Brand.XIAOMI
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Brand.HUAWEI
            manufacturer.contains("oppo") -> Brand.OPPO
            manufacturer.contains("vivo") -> Brand.VIVO
            manufacturer.contains("oneplus") -> Brand.ONEPLUS
            manufacturer.contains("google") || manufacturer.contains("pixel") -> Brand.PIXEL
            else -> Brand.OTHER
        }
    }
}
