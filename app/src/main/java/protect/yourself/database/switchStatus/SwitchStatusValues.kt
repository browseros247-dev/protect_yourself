package protect.yourself.database.switchStatus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import protect.yourself.features.blockerPage.identifiers.AccountabilityPartnerTypeIdentifiers
import protect.yourself.features.blockerPage.identifiers.AppLockTypeIdentifiers
import protect.yourself.features.blockerPage.identifiers.VpnConnectionTypeIdentifiers
import timber.log.Timber

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
 * REMOVED accessors (orphans after the XML→Compose migration + Phase 5/6 deferral):
 *  - All social-media-specific getters (Snapchat/Insta/YT/Telegram/Whatsapp)
 *    — kept as SwitchIdentifier keys for DB schema compat, but the typed
 *    accessors had zero call sites.
 *  - All `observeX` flows except `observePornBlockerSwitch` — only
 *    `observePornBlockerSwitch` is consumed by Compose UI.
 *  - `observeAll()` — had zero external call sites.
 *  - `isLongSentenceMessageSet`, `isBlockScreenCountDownTimeSet`,
 *    `isBlockScreenCustomMessageSet`, `isVpnNotificationCustomMessageSet`,
 *    `isVpnDnsCustomListSet`, `isStopMeWhitelistAppsSet` — boolean "is set"
 *    predicates with zero call sites.
 *  - `getLastBackupCreatedTime` — no UI displays backup time.
 *  - `getAppLockType` — AppLockManager reads `"app_lock_type"` directly from
 *    the DAO, bypassing this wrapper.
 *
 * Kept stubs (exercised by SwitchStatusDaoTest):
 *  - isPremiumActive(): always returns `false` — premium removed.
 *  - isEligibleForBannerAd(): always returns `0L` — ads removed.
 *
 * Usage:
 *  - Reads happen via suspend getters (Flow or suspend fun).
 *  - Writes happen via `storeSwitchStatus()` suspend function.
 *  - Getters are extension functions on the DAO so they're testable in isolation.
 */
class SwitchStatusValues(private val dao: SwitchStatusDao) {

    companion object {
        /** TIMER-DEFAULT-01 (v1.0.68): default Close-button countdown when no valid custom value is set. */
        const val DEFAULT_BLOCK_SCREEN_COUNTDOWN_SECONDS = 3
        /** Valid range for a custom countdown (per block-screen design "3-300s"). */
        const val MIN_BLOCK_SCREEN_COUNTDOWN_SECONDS = 1
        const val MAX_BLOCK_SCREEN_COUNTDOWN_SECONDS = 300
    }

    // ===== Reads: Booleans =====

    suspend fun isPornBlockerSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.PORN_BLOCKER_SWITCH)?.asBoolean() ?: true

    suspend fun isSafeSearchSwitchOn(): Boolean =
        dao.get(SwitchIdentifier.SAFE_SEARCH_SWITCH)?.asBoolean() ?: false

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

    /**
     * Returns the persisted VPN connection mode (NORMAL / POWERFUL / CUSTOM).
     * Defaults to NORMAL when nothing has been set yet.
     *
     * BUG-11 fix: log when the persisted value is OFF so it's visible in
     * logs. OFF is not a valid runtime mode (the OFF state is represented
     * by VPN_SWITCH=false), so encountering OFF here typically indicates
     * either a fresh install (no value set yet) or a backup-restore issue
     * from an older app version that used OFF as a stored value.
     */
    suspend fun getVpnConnectionType(): VpnConnectionTypeIdentifiers {
        val raw = dao.get(SwitchIdentifier.VPN_CONNECTION_TYPE)?.asString()
        val parsed = VpnConnectionTypeIdentifiers.fromString(raw)
        if (parsed == VpnConnectionTypeIdentifiers.OFF) {
            Timber.w("VPN_CONNECTION_TYPE was OFF (raw='$raw') — coercing to NORMAL. This may indicate a fresh install or a backup-restore issue.")
            return VpnConnectionTypeIdentifiers.NORMAL
        }
        return parsed
    }

    /** Persists the VPN connection mode. */
    suspend fun storeVpnConnectionType(type: VpnConnectionTypeIdentifiers) {
        dao.upsert(
            SwitchStatusItemModel(
                key = SwitchIdentifier.VPN_CONNECTION_TYPE,
                value = type.value.toString(),
                type = "long"
            )
        )
    }

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

    suspend fun isTimeDelayDurationSet(): Boolean =
        dao.get(SwitchIdentifier.TIME_DELAY_DURATION_SET)?.asBoolean() ?: false

    suspend fun isRealFriendVisible(): Boolean =
        dao.get(SwitchIdentifier.REAL_FRIEND_VISIBLE)?.asBoolean() ?: false

    suspend fun isBlockScreenRedirectUrlSet(): Boolean =
        dao.get(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL_SET)?.asBoolean() ?: false

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

    /**
     * Block screen Close-button countdown (seconds).
     *
     * TIMER-DEFAULT-01 (v1.0.68): previously this returned 0 when the stored
     * row was missing — and since NO code path in the app ever persists a
     * custom value (grep-verified: no setter/callback seed), the countdown
     * was effectively always disabled. Spec: the app MUST default to
     * [DEFAULT_BLOCK_SCREEN_COUNTDOWN_SECONDS] (3s) whenever a valid custom
     * value has not been set, so a fresh install gets the intended 3-second
     * dwell on every block screen (overlay AND Activity path).
     *
     * A stored custom value is honored only when it parses and falls inside
     * [MIN_BLOCK_SCREEN_COUNTDOWN_SECONDS]..[MAX_BLOCK_SCREEN_COUNTDOWN_SECONDS]
     * — anything missing, unparsable, zero, negative, or above the max counts
     * as "not set" and falls back to the default (fail-safe, never a
     * longer-than-max lock or an instant-close bypass).
     */
    suspend fun getBlockScreenCountDownSeconds(): Int {
        val stored = dao.get(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)?.asInt()
        return if (stored != null &&
            stored in MIN_BLOCK_SCREEN_COUNTDOWN_SECONDS..MAX_BLOCK_SCREEN_COUNTDOWN_SECONDS
        ) {
            stored
        } else {
            if (stored != null) {
                Timber.w(
                    "Invalid block-screen countdown stored (%s) — using default %ds",
                    stored, DEFAULT_BLOCK_SCREEN_COUNTDOWN_SECONDS
                )
            }
            DEFAULT_BLOCK_SCREEN_COUNTDOWN_SECONDS
        }
    }

    /**
     * Clear the persisted block-screen custom message and its "is set" flag.
     * After this call, [getBlockScreenCustomMessage] returns null and the
     * block screen falls back to the localized default message.
     */
    suspend fun clearBlockScreenCustomMessage() {
        dao.upsert(SwitchStatusItemModel(
            key = SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE,
            value = "",
            type = "string"
        ))
        dao.upsert(SwitchStatusItemModel(
            key = SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE_SET,
            value = "false",
            type = "boolean"
        ))
    }

    /**
     * Clear the persisted block-screen motivation image path.
     * After this call, [getBlockScreenStoreImagePath] returns null and the
     * block screen does not show any motivation image.
     *
     * Note: callers that previously took a persistable URI permission for the
     * old value SHOULD also release that permission via
     * `contentResolver.releasePersistableUriPermission(...)`. We do not do it
     * here because SwitchStatusValues does not have access to a
     * ContentResolver.
     */
    suspend fun clearBlockScreenStoreImagePath() {
        dao.upsert(SwitchStatusItemModel(
            key = SwitchIdentifier.BLOCK_SCREEN_STORE_IMAGE_PATH,
            value = "",
            type = "string"
        ))
    }

    suspend fun getVpnNotificationCustomMessage(): String? =
        dao.get(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE)?.asString()?.takeIf { it.isNotBlank() }

    // ===== Reads: Enum-typed =====

    suspend fun getAccountabilityPartnerType(): AccountabilityPartnerTypeIdentifiers {
        val raw = dao.get(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE)?.asString()
        return AccountabilityPartnerTypeIdentifiers.fromString(raw)
    }

    // ===== Premium / ads — REMOVED stubs (kept for tests) =====

    /**
     * Always returns false. Premium is removed in the rebuild.
     * Kept as a method for compatibility with original code paths and tests.
     */
    suspend fun isPremiumActive(): Boolean = false

    /**
     * Always returns 0 (no banner ad eligibility). Ads removed.
     * Kept for compatibility with tests.
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
}
