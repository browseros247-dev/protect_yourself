package protect.yourself.database.switchStatus

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import protect.yourself.features.blockerPage.identifiers.AccountabilityPartnerTypeIdentifiers
import protect.yourself.features.blockerPage.identifiers.AppLockTypeIdentifiers

/**
 * SwitchStatusValues — central accessor for all switch/setting states.
 *
 * Ported from original `SwitchStatusValues.kt` (60+ getter methods).
 *
 * REMOVED getters (premium-related, no longer needed in rebuild):
 *  - getIsPremiumActiveNumber
 *  - getIsEligibleForBannerAdNumber
 *  - getIsIntroPremiumPageActionDoneStatus
 *  - getPremiumFeatureNotificationDisplayDate
 *  - getPremiumFeatureNotificationIndexData
 *  - getPremiumSaleEndNotificationTimeData
 *  - getPurchaseDataInFireStoreStatus
 *  - getPurchaseSuccessEventSubmitStatus
 *  - getOutSideAppOpenFlowIdentifierNumber  (premium upsell flow)
 *  - getForcePuOffStatus  (was a flag for "force PU promo off if premium")
 *  - getPornBlockerSwitchNumber  (was premium-aware count)
 *
 * Added helpers:
 *  - isPremiumActive(): always returns `false` — kept for compatibility but
 *    no longer gates any feature (premium removed).
 *
 * Usage:
 *  - Reads happen via suspend getters (Flow or suspend fun).
 *  - Writes happen via `storeSwitchStatus()` suspend function.
 *  - Getters are extension functions on the DAO so they're testable in isolation.
 */
class SwitchStatusValues(private val dao: SwitchStatusDao) {

    // ===== Reads: Booleans =====

    suspend fun isPornBlockerSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.PORN_BLOCKER_SWITCH)?.asBoolean() ?: true

    suspend fun isSafeSearchSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.SAFE_SEARCH_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockSnapchatStoriesSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_SNAPCHAT_STORIES_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockSnapchatSpotlightSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockInstaReelsSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_INSTA_REELS_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockInstaSearchSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_INSTA_SEARCH_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockWhatsappStatusSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_WHATSAPP_STATUS_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockYtShortsSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_YT_SHORTS_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockYtSearchSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_YT_SEARCH_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockTelegramSearchSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_TELEGRAM_SEARCH_SWITCH)?.asBoolean() ?: false

    suspend fun isPreventUninstallSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.PREVENT_UNINSTALL_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockNotificationDrawerSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_NOTIFICATION_DRAWER_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockPhoneRebootSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockRecentAppsSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_RECENT_APPS_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockSettingPageByTitleSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockUnsupportedBrowsersSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockPackageIntentSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH)?.asBoolean() ?: false

    suspend fun isVpnSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.VPN_SWITCH)?.asBoolean() ?: false

    suspend fun isVpnNotificationHideSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockNewInstallAppsSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH)?.asBoolean() ?: false

    suspend fun isBlockInAppBrowsersSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH)?.asBoolean() ?: false

    suspend fun isAppLockSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.SET_APP_LOCK_SWITCH)?.asBoolean() ?: false

    suspend fun isTouchIdSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.TOUCH_ID_SWITCH)?.asBoolean() ?: false

    suspend fun isDisableForgotPasswordSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH)?.asBoolean() ?: false

    suspend fun isDailyReportSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.DAILY_REPORT_SWITCH)?.asBoolean() ?: false

    suspend fun isLongSentenceMessageSet(): Boolean =
        dao.get(SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET)?.asBoolean() ?: false

    suspend fun isTimeDelayDurationSet(): Boolean =
        dao.get(SwitchIdentifier.TIME_DELAY_DURATION_SET)?.asBoolean() ?: false

    suspend fun isRealFriendVisible(): Boolean =
        dao.get(SwitchIdentifier.REAL_FRIEND_VISIBLE)?.asBoolean() ?: false

    suspend fun isBlockScreenCountDownTimeSet(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)?.asBoolean() ?: false

    suspend fun isBlockScreenCustomMessageSet(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE_SET)?.asBoolean() ?: false

    suspend fun isBlockScreenRedirectUrlSet(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL_SET)?.asBoolean() ?: false

    suspend fun isVpnNotificationCustomMessageSet(): Boolean =
        dao.get(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE_SET)?.asBoolean() ?: false

    suspend fun isVpnDnsCustomListSet(): Boolean =
        dao.get(SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET)?.asBoolean() ?: false

    suspend fun isStopMeWhitelistAppsSet(): Boolean =
        dao.get(SwitchIdentifier.STOP_ME_WHITELIST_APPS_SET)?.asBoolean() ?: false

    suspend fun isTermsApproved(): Boolean =
        dao.get(SwitchIdentifier.TERMS_APPROVE_STATUS)?.asBoolean() ?: false

    suspend fun isRatingGiven(): Boolean =
        dao.get(SwitchIdentifier.RATING_GIVEN_STATUS)?.asBoolean() ?: false

    // ===== Reads: Strings / Ints / Longs =====

    suspend fun getLongSentenceCustomMessage(): String =
        dao.get(SwitchIdentifier.LONG_SENTENCE_CUSTOM_MESSAGE)?.asString()
            ?: "I will not give in to my urges"

    suspend fun getTimeDelayCustomDurationSeconds(): Int =
        dao.get(SwitchIdentifier.TIME_DELAY_CUSTOM_DURATION)?.asInt()
            ?: 30

    suspend fun getRealFriendEmail(): String? =
        dao.get(SwitchIdentifier.REAL_FRIEND_EMAIL)?.asString()?.takeIf { it.isNotBlank() }

    suspend fun getBlockScreenCustomMessage(): String? =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE)?.asString()?.takeIf { it.isNotBlank() }

    suspend fun getBlockScreenRedirectUrl(): String? =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL)?.asString()?.takeIf { it.isNotBlank() }

    suspend fun getBlockScreenStoreImagePath(): String? =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_STORE_IMAGE_PATH)?.asString()?.takeIf { it.isNotBlank() }

    suspend fun getBlockScreenCountDownSeconds(): Int =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)?.asInt() ?: 0

    suspend fun getVpnNotificationCustomMessage(): String? =
        dao.get(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE)?.asString()?.takeIf { it.isNotBlank() }

    suspend fun getFirebaseToken(): String? =
        dao.get(SwitchIdentifier.FIREBASE_TOKEN)?.asString()?.takeIf { it.isNotBlank() }

    suspend fun getLastBackupCreatedTime(): Long =
        dao.get(SwitchIdentifier.LAST_BACKUP_CREATED_TIME)?.asLong() ?: 0L

    suspend fun getUserDeviceCurrencyCode(): String? =
        dao.get(SwitchIdentifier.USER_DEVICE_CURRENCY_CODE)?.asString()?.takeIf { it.isNotBlank() }

    // ===== Reads: Enum-typed =====

    suspend fun getAccountabilityPartnerType(): AccountabilityPartnerTypeIdentifiers {
        val raw = dao.get(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE)?.asString()
        return AccountabilityPartnerTypeIdentifiers.fromString(raw)
    }

    suspend fun getAppLockType(): AppLockTypeIdentifiers {
        val raw = dao.get(SwitchIdentifier.SET_APP_LOCK_SWITCH)?.asString()
        // AppLockTypeIdentifiers stored as Long under the SET_APP_LOCK_SWITCH key
        // when SET_APP_LOCK_SWITCH is true, the actual type is stored elsewhere.
        // Original code uses a separate "app_lock_type" key — add it for clarity.
        val typeRaw = dao.get("app_lock_type")?.asString()
        return AppLockTypeIdentifiers.fromString(typeRaw)
    }

    // ===== Premium / ads — REMOVED stubs =====

    /**
     * Always returns false. Premium is removed in the rebuild.
     * Kept as a method for compatibility with original code paths.
     */
    suspend fun isPremiumActive(): Boolean = false

    /**
     * Always returns 0 (no banner ad eligibility). Ads removed.
     */
    suspend fun isEligibleForBannerAd(): Long = 0L

    // ===== Writes =====

    suspend fun storeSwitchStatus(key: String, value: Boolean) {
        dao.upsert(SwitchStatusItemModel(key = key, value = value.toString(), type = "boolean"))
    }

    suspend fun storeSwitchStatus(key: String, value: Int) {
        dao.upsert(SwitchStatusItemModel(key = key, value = value.toString(), type = "int"))
    }

    suspend fun storeSwitchStatus(key: String, value: Long) {
        dao.upsert(SwitchStatusItemModel(key = key, value = value.toString(), type = "long"))
    }

    suspend fun storeSwitchStatus(key: String, value: String) {
        dao.upsert(SwitchStatusItemModel(key = key, value = value, type = "string"))
    }

    // ===== Flow observation (for Compose UI) =====

    fun observePornBlockerSwitch(): Flow<Boolean> =
        dao.observe(SwitchIdentifier.PORN_BLOCKER_SWITCH).map { it?.asBoolean() ?: true }

    fun observeSafeSearchSwitch(): Flow<Boolean> =
        dao.observe(SwitchIdentifier.SAFE_SEARCH_SWITCH).map { it?.asBoolean() ?: false }

    fun observeVpnSwitch(): Flow<Boolean> =
        dao.observe(SwitchIdentifier.VPN_SWITCH).map { it?.asBoolean() ?: false }

    fun observePreventUninstallSwitch(): Flow<Boolean> =
        dao.observe(SwitchIdentifier.PREVENT_UNINSTALL_SWITCH).map { it?.asBoolean() ?: false }

    fun observeAppLockSwitch(): Flow<Boolean> =
        dao.observe(SwitchIdentifier.SET_APP_LOCK_SWITCH).map { it?.asBoolean() ?: false }

    fun observeTouchIdSwitch(): Flow<Boolean> =
        dao.observe(SwitchIdentifier.TOUCH_ID_SWITCH).map { it?.asBoolean() ?: false }

    fun observeAccountabilityPartnerType(): Flow<AccountabilityPartnerTypeIdentifiers> =
        dao.observe(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE).map {
            AccountabilityPartnerTypeIdentifiers.fromString(it?.asString())
        }

    fun observeAll(): Flow<List<SwitchStatusItemModel>> = dao.observeAll()

    companion object {
        @Volatile
        private var instance: SwitchStatusValues? = null

        fun getInstance(context: Context): SwitchStatusValues {
            return instance ?: synchronized(this) {
                instance ?: SwitchStatusValues(
                    protect.yourself.database.core.AppDatabase.getInstance(context).switchStatusDao()
                ).also { instance = it }
            }
        }
    }
}
