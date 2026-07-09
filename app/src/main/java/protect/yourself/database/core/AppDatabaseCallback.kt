package protect.yourself.database.core

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import protect.yourself.database.blockScreensCount.BlockScreenCountItemModel
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.stopMeDuration.StopMeDurationItemModel
import protect.yourself.database.stopMeSessionCount.StopMeSessionCountItemModel
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusItemModel
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
import protect.yourself.features.blockerPage.utils.DefaultDnsPresets
import protect.yourself.features.blockerPage.utils.DefaultKeywordData
import protect.yourself.features.blockerPage.utils.DefaultStopMeDurations
import protect.yourself.features.blockerPage.utils.DefaultSupportedBrowsers
import protect.yourself.features.blockerPage.utils.DefaultSupportedSocialMedia
import protect.yourself.features.blockerPage.utils.DefaultWhitelistApps
import timber.log.Timber

/**
 * Room database callback that pre-populates the database on first launch.
 *
 * Inserted data:
 *  - 1 BlockScreenCountItemModel (count=0)
 *  - 1 StopMeSessionCountItemModel (duration=0)
 *  - 50+ SwitchStatusItemModel (all default switches)
 *  - 4 VpnCustomDnsItemModel (Cloudflare, OpenDNS, CleanBrowsing, AdGuard)
 *  - 4 StopMeDurationItemModel (15m, 30m, 1h, 2h)
 *  - 11 SelectedAppItemModel for SUPPORTED_BROWSER_APPS
 *  - 5 SelectedAppItemModel for SUPPORTED_SOCIAL_MEDIA_APPS
 *  - 6 SelectedAppItemModel for VPN_WHITELIST_APPS (default whitelist)
 *  - 1189 SelectedKeywordItemModel for PORN_BLOCK_WORDS (preset)
 *  - 20 SelectedKeywordItemModel for PORN_WHITE_LIST_WORDS (preset)
 */
class AppDatabaseCallback(private val context: Context) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        Timber.i("AppDatabase created — pre-populating default data")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            prePopulate()
        }
    }

    private suspend fun prePopulate() {
        val db = AppDatabase.getInstance(context)
        Timber.i("Pre-populating default data...")

        // === 1. Block screen count (single row, key=0, count=0) ===
        db.blockScreenCountDao().upsert(BlockScreenCountItemModel(key = 0, count = 0))

        // === 2. Stop Me session count (single row, key=0, count=0) ===
        db.stopMeSessionCountDao().upsert(StopMeSessionCountItemModel(key = 0, duration = 0))

        // === 3. Default switches (all OFF except porn_blocker) ===
        val defaultSwitches = listOf(
            // Content blocking — porn blocker default ON
            SwitchStatusItemModel(SwitchIdentifier.PORN_BLOCKER_SWITCH, "true", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_ALL_WEBSITE_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.SAFE_SEARCH_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_IMAGE_VIDEO_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.MAKE_ANY_BROWSER_SUPPORTED_SWITCH, "false", "boolean"),
            // Social media
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SNAPCHAT_STORIES_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_INSTA_REELS_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_INSTA_SEARCH_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_WHATSAPP_STATUS_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_YT_SHORTS_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_YT_SEARCH_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_TELEGRAM_SEARCH_SWITCH, "false", "boolean"),
            // Uninstall protection
            SwitchStatusItemModel(SwitchIdentifier.PREVENT_UNINSTALL_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_NOTIFICATION_DRAWER_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_RECENT_APPS_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH, "false", "boolean"),
            // Advanced
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.VPN_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.SET_APP_LOCK_SWITCH, "false", "boolean"),
            SwitchStatusItemModel("app_lock_type", "0", "long"),  // OFF
            SwitchStatusItemModel(SwitchIdentifier.TOUCH_ID_SWITCH, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, "false", "boolean"),
            // Accountability partner
            SwitchStatusItemModel(
                SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE, "0", "long"
            ),  // NONE
            SwitchStatusItemModel(SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET, "false", "boolean"),
            SwitchStatusItemModel(
                SwitchIdentifier.LONG_SENTENCE_CUSTOM_MESSAGE,
                "I will not give in to my urges",
                "string"
            ),
            SwitchStatusItemModel(SwitchIdentifier.TIME_DELAY_DURATION_SET, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.TIME_DELAY_CUSTOM_DURATION, "30", "int"),
            SwitchStatusItemModel(SwitchIdentifier.REAL_FRIEND_EMAIL, "", "string"),
            SwitchStatusItemModel(SwitchIdentifier.REAL_FRIEND_VISIBLE, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.DAILY_REPORT_SWITCH, "false", "boolean"),
            // Block screen customization
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET, "0", "int"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE_SET, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE, "", "string"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL_SET, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL, "", "string"),
            SwitchStatusItemModel(SwitchIdentifier.BLOCK_SCREEN_STORE_IMAGE_PATH, "", "string"),
            // VPN customization
            SwitchStatusItemModel(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE_SET, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE, "", "string"),
            SwitchStatusItemModel(SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET, "false", "boolean"),
            // Stop Me
            SwitchStatusItemModel(SwitchIdentifier.STOP_ME_WHITELIST_APPS_SET, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.SUPPORTED_BROWSER_DEFAULT_APP_SET, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.SUPPORTED_SOCIAL_MEDIA_DEFAULT_APP_SET, "false", "boolean"),
            // App state
            SwitchStatusItemModel(SwitchIdentifier.TERMS_APPROVE_STATUS, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.RATING_GIVEN_STATUS, "false", "boolean"),
            SwitchStatusItemModel(SwitchIdentifier.FIREBASE_TOKEN, "", "string"),
            SwitchStatusItemModel(SwitchIdentifier.LAST_BACKUP_CREATED_TIME, "0", "long"),
            SwitchStatusItemModel(SwitchIdentifier.USER_DEVICE_CURRENCY_CODE, "", "string")
        )
        db.switchStatusDao().upsertAll(defaultSwitches)
        Timber.i("Inserted ${defaultSwitches.size} default switch states")

        // === 4. Default VPN DNS presets ===
        val dnsPresets = DefaultDnsPresets.ALL.map { preset ->
            VpnCustomDnsItemModel(
                key = preset.key,
                firstDns = preset.firstDns,
                secondDns = preset.secondDns,
                isSelected = preset.isSelectedByDefault
            )
        }
        dnsPresets.forEach { db.vpnCustomDnsDao().upsert(it) }
        Timber.i("Inserted ${dnsPresets.size} DNS presets")

        // === 5. Default Stop Me durations (instant type: days=0) ===
        val stopMeDurations = DefaultStopMeDurations.ALL.map { preset ->
            StopMeDurationItemModel(
                key = preset.key,
                duration = preset.durationMillis,
                endTime = 0L,
                days = 0,
                startTime = 0L,
                startTimeDayMillis = 0L
            )
        }
        stopMeDurations.forEach { db.stopMeDurationDao().upsert(it) }
        Timber.i("Inserted ${stopMeDurations.size} Stop Me durations")

        // === 6. Default supported browsers ===
        val supportedBrowsers = DefaultSupportedBrowsers.ALL.mapIndexed { index, app ->
            SelectedAppItemModel(
                key = "preset_browser_${app.packageName}",
                packageName = app.packageName,
                appName = app.displayName,
                identifier = SelectedAppListIdentifier.SUPPORTED_BROWSER_APPS.value,
                isSelected = true
            )
        }
        db.selectedAppsListDao().upsertAll(supportedBrowsers)
        Timber.i("Inserted ${supportedBrowsers.size} supported browsers")

        // === 7. Default supported social media ===
        val socialMedia = DefaultSupportedSocialMedia.ALL.mapIndexed { index, app ->
            SelectedAppItemModel(
                key = "preset_social_${app.packageName}",
                packageName = app.packageName,
                appName = app.displayName,
                identifier = SelectedAppListIdentifier.SUPPORTED_SOCIAL_MEDIA_APPS.value,
                isSelected = true
            )
        }
        db.selectedAppsListDao().upsertAll(socialMedia)
        Timber.i("Inserted ${socialMedia.size} supported social media apps")

        // === 8. Default whitelist apps (Stop Me + VPN) ===
        val whitelistApps = DefaultWhitelistApps.ALL.map { pkg ->
            SelectedAppItemModel(
                key = "preset_whitelist_stop_me_$pkg",
                packageName = pkg,
                appName = pkg.substringAfterLast('.'),
                identifier = SelectedAppListIdentifier.WHITELIST_STOP_ME_APPS.value,
                isSelected = true
            )
        }
        db.selectedAppsListDao().upsertAll(whitelistApps)
        Timber.i("Inserted ${whitelistApps.size} whitelist apps")

        // === 9. Preset block + whitelist keywords ===
        val keywordData = DefaultKeywordData.getInstance(context)
        val blockKeywords = keywordData.getDefaultBlockKeywordModels()
        val whitelistKeywords = keywordData.getDefaultWhitelistKeywordModels()
        db.selectedKeywordDao().upsertAll(blockKeywords)
        db.selectedKeywordDao().upsertAll(whitelistKeywords)
        Timber.i("Inserted ${blockKeywords.size} block keywords + ${whitelistKeywords.size} whitelist keywords")

        Timber.i("Pre-population complete")
    }
}
