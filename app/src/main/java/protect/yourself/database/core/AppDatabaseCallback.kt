package protect.yourself.database.core

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.features.blockerPage.utils.DefaultDnsPresets
import protect.yourself.features.blockerPage.utils.DefaultKeywordData
import protect.yourself.features.blockerPage.utils.DefaultStopMeDurations
import protect.yourself.features.blockerPage.utils.DefaultWhitelistApps
import timber.log.Timber

/**
 * Room database callback that pre-populates the database on first launch.
 *
 * CRITICAL: The `onCreate` callback runs while the database creation transaction
 * is still open. Using DAOs (which open their own transactions) here causes a
 * deadlock / "database is locked" exception that crashes the app.
 *
 * FIX: All pre-population is done via `db.execSQL()` directly on the
 * SupportSQLiteDatabase parameter, which executes within the same transaction
 * as the table creation. This is the correct Room pattern.
 *
 * Keyword pre-population (1189+ entries) is deferred to a background coroutine
 * that runs AFTER the DB is fully created, using DAOs safely.
 */
class AppDatabaseCallback(private val context: Context) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        Timber.i("AppDatabase created — pre-populating default data via execSQL")

        try {
            // === 1. Block screen count (single row, key=0, count=0) ===
            db.execSQL(
                "INSERT OR REPLACE INTO block_screen_count_table (`key`, count) VALUES (0, 0)"
            )

            // === 2. Stop Me session count (single row, key=0, count=0) ===
            db.execSQL(
                "INSERT OR REPLACE INTO stop_me_session_count_table (`key`, duration) VALUES (0, 0)"
            )

            // === 3. Default switches (all OFF except porn_blocker) ===
            insertDefaultSwitches(db)

            // === 4. Default VPN DNS presets ===
            insertDnsPresets(db)

            // === 5. Default Stop Me durations (instant type: days=0) ===
            insertStopMeDurations(db)

            // === 6. Default whitelist apps ===
            insertWhitelistApps(db)

            Timber.i("Core default data inserted via execSQL")

            // === 9. Preset keywords (1189+ entries) — deferred to background ===
            // These are too many to insert synchronously during onCreate.
            // Launch a background coroutine that runs AFTER onCreate returns
            // and the DB transaction is committed.
            //
            // Uses appCoroutineScope so uncaught exceptions are routed to
            // CrashLogger with scope context (instead of being silently lost
            // if the coroutine throws outside the try/catch).
            protect.yourself.core.appCoroutineScope(
                scopeName = "AppDatabaseCallback",
                dispatcher = Dispatchers.IO
            ).launch {
                try {
                    insertPresetKeywords()
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to insert preset keywords (non-fatal)")
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "Failed to pre-populate database (critical)")
        }
    }

    private fun insertDefaultSwitches(db: SupportSQLiteDatabase) {
        val switches = listOf(
            Triple(SwitchIdentifier.PORN_BLOCKER_SWITCH, "true", "boolean"),
            Triple(SwitchIdentifier.SAFE_SEARCH_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_SNAPCHAT_STORIES_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_SNAPCHAT_SPOTLIGHT_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_INSTA_REELS_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_INSTA_SEARCH_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_WHATSAPP_STATUS_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_YT_SHORTS_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_YT_SEARCH_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_TELEGRAM_SEARCH_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.PREVENT_UNINSTALL_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_NOTIFICATION_DRAWER_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_PHONE_REBOOT_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_RECENT_APPS_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_UNSUPPORTED_BROWSERS_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_PACKAGE_INTENT_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.VPN_SWITCH, "false", "boolean"),
            // Default VPN mode = NORMAL (Balanced / Cloudflare Family)
            Triple(SwitchIdentifier.VPN_CONNECTION_TYPE, "1", "long"),
            Triple(SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_IN_APP_BROWSERS_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.SET_APP_LOCK_SWITCH, "false", "boolean"),
            Triple("app_lock_type", "0", "long"),
            Triple(SwitchIdentifier.TOUCH_ID_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE, "0", "long"),
            Triple(SwitchIdentifier.LONG_SENTENCE_MESSAGE_SET, "false", "boolean"),
            Triple(SwitchIdentifier.LONG_SENTENCE_CUSTOM_MESSAGE, "I will not give in to my urges", "string"),
            Triple(SwitchIdentifier.TIME_DELAY_DURATION_SET, "false", "boolean"),
            Triple(SwitchIdentifier.TIME_DELAY_CUSTOM_DURATION, "30", "int"),
            Triple(SwitchIdentifier.REAL_FRIEND_EMAIL, "", "string"),
            Triple(SwitchIdentifier.REAL_FRIEND_VISIBLE, "false", "boolean"),
            Triple(SwitchIdentifier.DAILY_REPORT_SWITCH, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET, "0", "int"),
            Triple(SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE_SET, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE, "", "string"),
            Triple(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL_SET, "false", "boolean"),
            Triple(SwitchIdentifier.BLOCK_SCREEN_REDIRECT_URL, "", "string"),
            Triple(SwitchIdentifier.BLOCK_SCREEN_STORE_IMAGE_PATH, "", "string"),
            Triple(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE_SET, "false", "boolean"),
            Triple(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE, "", "string"),
            Triple(SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET, "false", "boolean"),
            Triple(SwitchIdentifier.STOP_ME_WHITELIST_APPS_SET, "false", "boolean"),
            Triple(SwitchIdentifier.TERMS_APPROVE_STATUS, "false", "boolean"),
            Triple(SwitchIdentifier.RATING_GIVEN_STATUS, "false", "boolean"),
            Triple(SwitchIdentifier.FIREBASE_TOKEN, "", "string"),
            Triple(SwitchIdentifier.LAST_BACKUP_CREATED_TIME, "0", "long"),
            Triple(SwitchIdentifier.USER_DEVICE_CURRENCY_CODE, "", "string")
        )

        for ((key, value, type) in switches) {
            db.execSQL(
                "INSERT OR REPLACE INTO switch_status (`key`, value, type) VALUES (?, ?, ?)",
                arrayOf(key, value, type)
            )
        }
        Timber.i("Inserted ${switches.size} default switch states")
    }

    private fun insertDnsPresets(db: SupportSQLiteDatabase) {
        // Use INSERT OR IGNORE (not REPLACE) so that if a user has previously
        // added a custom preset with a key that collides with a default preset
        // key, we don't clobber their data. In practice this is unlikely
        // (default keys are "preset_cloudflare_family" etc.), but it's the
        // safe choice. On first launch the table is empty so all 4 defaults
        // are inserted.
        //
        // NOTE: this only runs in onCreate() — i.e. only on first install.
        // Upgrades from v8 to v9 use the MIGRATION_8_9 path which ALTERs
        // the existing table and backfills display_name by key.
        for (preset in DefaultDnsPresets.ALL) {
            db.execSQL(
                "INSERT OR IGNORE INTO vpn_custom_dns (`key`, display_name, first_dns, second_dns, is_selected) VALUES (?, ?, ?, ?, ?)",
                arrayOf(preset.key, preset.displayName, preset.firstDns, preset.secondDns, preset.isSelectedByDefault)
            )
        }
        Timber.i("Inserted ${DefaultDnsPresets.ALL.size} DNS presets")
    }

    private fun insertStopMeDurations(db: SupportSQLiteDatabase) {
        for (preset in DefaultStopMeDurations.ALL) {
            db.execSQL(
                "INSERT OR REPLACE INTO stop_me_duration_table (`key`, duration, end_time, days, start_time, start_time_day_millis) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(preset.key, preset.durationMillis, 0L, 0, 0L, 0L)
            )
        }
        Timber.i("Inserted ${DefaultStopMeDurations.ALL.size} Stop Me durations")
    }

    private fun insertWhitelistApps(db: SupportSQLiteDatabase) {
        for (pkg in DefaultWhitelistApps.ALL) {
            db.execSQL(
                "INSERT OR REPLACE INTO selected_apps_table (`key`, package_name, app_name, identifier, is_selected) VALUES (?, ?, ?, ?, ?)",
                arrayOf("preset_whitelist_stop_me_$pkg", pkg, pkg.substringAfterLast('.'),
                    SelectedAppListIdentifier.WHITELIST_STOP_ME_APPS.value, true)
            )
        }
        Timber.i("Inserted ${DefaultWhitelistApps.ALL.size} whitelist apps")
    }

    /**
     * Insert preset block + whitelist keywords.
     * Runs in a background coroutine AFTER the DB is fully created.
     *
     * KB-12 fix: uses INSERT OR IGNORE (via a dedicated DAO method) instead of
     * INSERT OR REPLACE (upsertAll). This ensures that if a user has deleted
     * a preset keyword, re-seeding on app update will NOT re-add it. The
     * preset keys are stable ("preset_block_<idx>", "preset_whitelist_<idx>"),
     * so INSERT OR IGNORE will skip any key that already exists (whether it
     * was inserted by a previous seed or by a user who manually added the
     * same key — though the latter is unlikely).
     */
    private suspend fun insertPresetKeywords() {
        val db = AppDatabase.getInstance(context)
        val keywordData = DefaultKeywordData.getInstance(context)

        val blockKeywords = keywordData.getDefaultBlockKeywordModels()
        val whitelistKeywords = keywordData.getDefaultWhitelistKeywordModels()

        // KB-12: use insertAllOrIgnore (INSERT OR IGNORE) instead of upsertAll
        // (INSERT OR REPLACE) so user-deleted presets are not re-added.
        db.selectedKeywordDao().insertAllOrIgnore(blockKeywords)
        db.selectedKeywordDao().insertAllOrIgnore(whitelistKeywords)
        Timber.i("Inserted ${blockKeywords.size} block keywords + ${whitelistKeywords.size} whitelist keywords (INSERT OR IGNORE)")
    }
}
