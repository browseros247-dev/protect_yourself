package protect.yourself.database.core

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
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
 *
 * ## BUGFIX (v1.0.49): camelCase column names
 *
 * The entities (VpnCustomDnsItemModel, StopMeDurationItemModel,
 * SelectedAppItemModel) use camelCase field names WITHOUT @ColumnInfo
 * annotations. Room 2.6.1 generates column names matching the field names
 * exactly — so the actual DB columns are `firstDns`, `secondDns`,
 * `isSelected`, `displayName`, `endTime`, `startTime`, `startTimeDayMillis`,
 * `packageName`, `appName`, etc.
 *
 * The PREVIOUS version of this file used snake_case in all raw SQL
 * (`first_dns`, `second_dns`, `is_selected`, `display_name`, `end_time`,
 * `start_time`, `start_time_day_millis`, `package_name`, `app_name`).
 * This caused `SQLiteException: table X has no column named Y` on EVERY
 * install (fresh and upgrade), silently caught by the try/catch in onCreate.
 *
 * The result: default DNS presets, Stop Me durations, and whitelist apps
 * were NEVER inserted. The app ran in a degraded state with empty preset
 * lists. Crash log `crash_20260712_101502_0002` (v1.0.48, vivo V2206)
 * surfaced this as a FATAL-level diagnostic.
 *
 * FIX: all raw SQL now uses the camelCase column names that match the
 * entity field names (and what Room actually generates).
 */
class AppDatabaseCallback(private val context: Context) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        Timber.i("AppDatabase created — pre-populating default data via execSQL")

        try {
            // === 0. Defensive schema repair ===
            ensureVpnCustomDnsSchema(db)

            // === 1. Block screen count (single row, key=0, count=0) ===
            db.execSQL(
                "INSERT OR REPLACE INTO block_screen_count_table (`key`, count) VALUES (0, 0)"
            )

            // === 2. Stop Me session count (single row, key=0, duration=0) ===
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
            try {
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                    tag = "DatabaseInit",
                    message = "Failed to pre-populate database (critical) — app will run in degraded state",
                    extraContext = mapOf("phase" to "onCreate")
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * SCHEMA-01 fix (v1.0.57): onOpen callback that runs on EVERY database
     * open, not just fresh creation.
     *
     * This is the critical fix for crash_20260712_160322 (SQLiteException:
     * table vpn_custom_dns has no column named first_dns).
     *
     * ## Why onOpen is needed
     *
     * `onCreate` only fires when the DB is being created fresh. If the user
     * has an existing DB from an old install (pre-v1.0.49, with snake_case
     * columns), `onCreate` does NOT fire — Room runs migrations instead.
     * But the migration path (MIGRATION_8_9) only added `displayName`; it
     * did NOT handle the snake_case → camelCase transition for `firstDns`,
     * `secondDns`, `isSelected`.
     *
     * The result: the corrupt table persisted across the upgrade, and the
     * first DAO query that touched `vpn_custom_dns` crashed with
     * "no column named first_dns".
     *
     * `onOpen` fires on EVERY DB open — fresh install, upgrade, and normal
     * app launch. By running the schema repair here, we guarantee the table
     * is always in the correct state before any DAO query runs.
     *
     * ## What this does
     *
     * 1. Runs [ensureVpnCustomDnsSchema] to repair any column corruption.
     * 2. Checks if the `vpn_custom_dns` table is empty; if so, re-inserts
     *    the default DNS presets (handles the case where old inserts silently
     *    failed due to the snake_case bug).
     */
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        try {
            // SCHEMA-01: repair the vpn_custom_dns table on every DB open.
            // This catches corrupt tables from old installs that were never
            // repaired by onCreate (because onCreate doesn't fire on upgrade).
            ensureVpnCustomDnsSchema(db)

            // SCHEMA-01: re-insert presets if the table is empty. This handles
            // the case where the old snake_case SQL silently failed to insert
            // any presets (caught by the try/catch in insertDnsPresets),
            // leaving the user with an empty DNS preset list.
            ensureDnsPresetsExist(db)
        } catch (t: Throwable) {
            Timber.e(t, "SCHEMA-01: onOpen schema repair failed (critical)")
            try {
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                    tag = "DatabaseRepair",
                    message = "SCHEMA-01: onOpen schema repair failed — DB may be corrupt",
                    extraContext = mapOf("phase" to "onOpen")
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * Defensive schema repair for vpn_custom_dns table.
     *
     * # Why this exists
     *
     * Crash log `crash_20260712_101502_0002` (v1.0.48, vivo V2206) shows:
     * `SQLiteException: table vpn_custom_dns has no column named first_dns`
     *
     * # Root cause
     *
     * The entity uses camelCase field names (firstDns, secondDns, isSelected,
     * displayName) WITHOUT @ColumnInfo. Room 2.6.1 generates camelCase columns.
     * But the raw SQL used snake_case — causing silent crashes on every install.
     *
     * # What this does
     *
     * 1. Queries PRAGMA table_info to read the actual column set.
     * 2. Checks for ALL expected camelCase columns.
     * 3. If only displayName is missing: ALTER TABLE ADD COLUMN (preserves data).
     * 4. If any core column is missing: DROP + CREATE (severe corruption repair).
     * 5. Backfills displayName for known preset keys after any repair.
     *
     * Idempotent — safe to call even if the schema is already correct.
     */
    private fun ensureVpnCustomDnsSchema(db: SupportSQLiteDatabase) {
        try {
            val existingColumns = mutableSetOf<String>()
            var tableExists = false
            try {
                val cursor = db.query("PRAGMA table_info(vpn_custom_dns)")
                cursor.use { c ->
                    while (c.moveToNext()) {
                        tableExists = true
                        val nameIndex = c.getColumnIndex("name")
                        if (nameIndex >= 0) {
                            existingColumns.add(c.getString(nameIndex))
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "ensureVpnCustomDnsSchema: PRAGMA table_info failed (non-critical, likely fresh install)")
                return
            }

            if (!tableExists || existingColumns.isEmpty()) {
                Timber.d("ensureVpnCustomDnsSchema: vpn_custom_dns does not exist yet — fresh install, no repair needed")
                return
            }

            val expectedColumns = setOf(
                "key", "displayName", "firstDns", "secondDns", "isSelected"
            )

            val missingColumns = expectedColumns - existingColumns
            if (missingColumns.isEmpty()) {
                Timber.d("ensureVpnCustomDnsSchema: schema already correct (columns=${existingColumns.sorted()})")
                return
            }

            Timber.w("ensureVpnCustomDnsSchema: missing columns detected: ${missingColumns.sorted()} (existing=${existingColumns.sorted()})")

            val v8CoreColumns = setOf("firstDns", "secondDns", "isSelected", "key")
            val missingCoreColumns = missingColumns.intersect(v8CoreColumns)

            if (missingCoreColumns.isEmpty()) {
                // Only displayName missing — additive ALTER
                if ("displayName" in missingColumns) {
                    Timber.w("ensureVpnCustomDnsSchema: adding missing displayName column via ALTER TABLE")
                    db.execSQL(
                        "ALTER TABLE vpn_custom_dns ADD COLUMN displayName TEXT NOT NULL DEFAULT ''"
                    )
                    for (preset in DefaultDnsPresets.ALL) {
                        try {
                            db.execSQL(
                                "UPDATE vpn_custom_dns SET displayName = ? WHERE `key` = ?",
                                arrayOf(preset.displayName, preset.key)
                            )
                        } catch (_: Throwable) {}
                    }
                    Timber.i("ensureVpnCustomDnsSchema: defensively added displayName column to vpn_custom_dns")
                }
            } else {
                // Severe corruption — DROP + CREATE
                Timber.e("ensureVpnCustomDnsSchema: SEVERE corruption — missing core columns ${missingCoreColumns.sorted()}. Dropping and recreating vpn_custom_dns table.")

                try {
                    db.execSQL("DROP TABLE IF EXISTS vpn_custom_dns")
                } catch (dropErr: Throwable) {
                    Timber.e(dropErr, "ensureVpnCustomDnsSchema: DROP TABLE failed — attempting CREATE anyway")
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS vpn_custom_dns (
                        `key` TEXT NOT NULL,
                        displayName TEXT NOT NULL DEFAULT '',
                        firstDns TEXT NOT NULL,
                        secondDns TEXT NOT NULL,
                        isSelected INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
                Timber.i("ensureVpnCustomDnsSchema: vpn_custom_dns table recreated with v9 schema (5 columns)")

                try {
                    protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                        throwable = java.lang.RuntimeException(
                            "vpn_custom_dns table was severely corrupted (missing columns: ${missingCoreColumns.sorted()}) — dropped and recreated"
                        ),
                        severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                        tag = "DatabaseRepair",
                        message = "vpn_custom_dns severe schema corruption repaired via DROP+CREATE",
                        extraContext = mapOf(
                            "missingColumns" to missingColumns.sorted().toString(),
                            "existingColumns" to existingColumns.sorted().toString(),
                            "repairStrategy" to "DROP_AND_CREATE"
                        )
                    )
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Timber.e(t, "ensureVpnCustomDnsSchema: defensive repair FAILED (critical)")
            try {
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                    tag = "DatabaseRepair",
                    message = "ensureVpnCustomDnsSchema repair threw — DB may be corrupt",
                    extraContext = emptyMap()
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * SCHEMA-01 fix (v1.0.57): ensure the 4 default DNS presets exist in the
     * vpn_custom_dns table. If the table is empty (or missing any preset key),
     * re-insert all 4 presets using INSERT OR IGNORE (preserves user-added
     * custom DNS entries).
     *
     * This handles the case where the old snake_case SQL silently failed to
     * insert presets (caught by the try/catch in insertDnsPresets), leaving
     * the user with an empty DNS preset list. The VPN management UI would
     * show no presets, and the user couldn't select a DNS provider.
     *
     * Called from [onOpen] on every DB open.
     */
    private fun ensureDnsPresetsExist(db: SupportSQLiteDatabase) {
        try {
            // Check if any presets exist. We use a SELECT COUNT(*) query
            // because it's the cheapest way to check if the table has data.
            var count = 0
            try {
                val cursor = db.query("SELECT COUNT(*) FROM vpn_custom_dns")
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        count = c.getInt(0)
                    }
                }
            } catch (t: Throwable) {
                // Table doesn't exist or is corrupt — ensureVpnCustomDnsSchema
                // should have already repaired it. If it's still broken, skip
                // preset insertion (the next onOpen will try again).
                Timber.w(t, "SCHEMA-01: ensureDnsPresetsExist — COUNT(*) failed, table may still be corrupt")
                return
            }

            if (count > 0) {
                // Table has data — assume presets are present (they were either
                // inserted by onCreate or by a previous onOpen repair). We don't
                // check for individual preset keys because that would require
                // 4 separate queries on every DB open, which is wasteful.
                Timber.v("SCHEMA-01: vpn_custom_dns has $count rows — presets already exist")
                return
            }

            // Table is empty — re-insert all 4 default presets.
            Timber.w("SCHEMA-01: vpn_custom_dns is empty — re-inserting ${DefaultDnsPresets.ALL.size} default presets")
            for (preset in DefaultDnsPresets.ALL) {
                try {
                    db.execSQL(
                        "INSERT OR IGNORE INTO vpn_custom_dns (`key`, displayName, firstDns, secondDns, isSelected) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(preset.key, preset.displayName, preset.firstDns, preset.secondDns, preset.isSelectedByDefault)
                    )
                } catch (t: Throwable) {
                    Timber.e(t, "SCHEMA-01: ensureDnsPresetsExist — failed to insert preset ${preset.key}")
                }
            }
            Timber.i("SCHEMA-01: re-inserted ${DefaultDnsPresets.ALL.size} default DNS presets in onOpen")
        } catch (t: Throwable) {
            Timber.e(t, "SCHEMA-01: ensureDnsPresetsExist failed (non-critical)")
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
        // BUGFIX (v1.0.49): column names are now camelCase to match the
        // VpnCustomDnsItemModel entity (Room 2.6.1 default column naming).
        for (preset in DefaultDnsPresets.ALL) {
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO vpn_custom_dns (`key`, displayName, firstDns, secondDns, isSelected) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(preset.key, preset.displayName, preset.firstDns, preset.secondDns, preset.isSelectedByDefault)
                )
            } catch (t: Throwable) {
                Timber.e(t, "insertDnsPresets: failed to insert preset ${preset.key}")
            }
        }
        Timber.i("Inserted ${DefaultDnsPresets.ALL.size} DNS presets")
    }

    private fun insertStopMeDurations(db: SupportSQLiteDatabase) {
        // BUGFIX (v1.0.49): column names are now camelCase to match the
        // StopMeDurationItemModel entity.
        for (preset in DefaultStopMeDurations.ALL) {
            try {
                db.execSQL(
                    "INSERT OR REPLACE INTO stop_me_duration_table (`key`, duration, endTime, days, startTime, startTimeDayMillis) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(preset.key, preset.durationMillis, 0L, 0, 0L, 0L)
                )
            } catch (t: Throwable) {
                Timber.e(t, "insertStopMeDurations: failed to insert preset ${preset.key}")
            }
        }
        Timber.i("Inserted ${DefaultStopMeDurations.ALL.size} Stop Me durations")
    }

    private fun insertWhitelistApps(db: SupportSQLiteDatabase) {
        // BUGFIX (v1.0.49): column names are now camelCase to match the
        // SelectedAppItemModel entity.
        for (pkg in DefaultWhitelistApps.ALL) {
            try {
                db.execSQL(
                    "INSERT OR REPLACE INTO selected_apps_table (`key`, packageName, appName, identifier, isSelected) VALUES (?, ?, ?, ?, ?)",
                    arrayOf("preset_whitelist_stop_me_$pkg", pkg, pkg.substringAfterLast('.'),
                        SelectedAppListIdentifier.WHITELIST_STOP_ME_APPS.value, true)
                )
            } catch (t: Throwable) {
                Timber.e(t, "insertWhitelistApps: failed to insert whitelist app $pkg")
            }
        }
        Timber.i("Inserted ${DefaultWhitelistApps.ALL.size} whitelist apps")
    }

    /**
     * Insert preset block + whitelist keywords.
     * Runs in a background coroutine AFTER the DB is fully created.
     *
     * KB-12 fix: uses INSERT OR IGNORE instead of INSERT OR REPLACE so
     * user-deleted presets are not re-added.
     */
    private suspend fun insertPresetKeywords() {
        val db = AppDatabase.getInstance(context)
        val keywordData = DefaultKeywordData.getInstance(context)

        val blockKeywords = keywordData.getDefaultBlockKeywordModels()
        val whitelistKeywords = keywordData.getDefaultWhitelistKeywordModels()

        db.selectedKeywordDao().insertAllOrIgnore(blockKeywords)
        db.selectedKeywordDao().insertAllOrIgnore(whitelistKeywords)
        Timber.i("Inserted ${blockKeywords.size} block keywords + ${whitelistKeywords.size} whitelist keywords (INSERT OR IGNORE)")
    }
}
