package protect.yourself.database.core

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import protect.yourself.database.blockScreensCount.BlockScreenCountDao
import protect.yourself.database.blockScreensCount.BlockScreenCountItemModel
import protect.yourself.database.pendingRequests.PendingRequestDao
import protect.yourself.database.pendingRequests.PendingRequestItemModel
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListAppsDao
import protect.yourself.database.selectedKeywords.SelectedKeywordDao
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import protect.yourself.database.stopMeDuration.StopMeDurationDao
import protect.yourself.database.stopMeDuration.StopMeDurationItemModel
import protect.yourself.database.stopMeSessionCount.StopMeSessionCountDao
import protect.yourself.database.stopMeSessionCount.StopMeSessionCountItemModel
import protect.yourself.database.switchStatus.SwitchStatusDao
import protect.yourself.database.switchStatus.SwitchStatusItemModel
import protect.yourself.database.vpnCustomDns.VpnCustomDnsDao
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionAppDao
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionAppItemModel
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionDao
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import timber.log.Timber

/**
 * App Room database — Phase 1 placeholder.
 *
 * Full entity/DAO implementations land in Phase 2. For Phase 1, this file
 * declares all 9 entities so the build is green.
 *
 * Original schema version: 7
 * Rebuild starts fresh at version 8 to signal "new lineage".
 *
 * v9 (Future-Brand): added `display_name` column to `vpn_custom_dns` so the
 * VPN management UI can show provider names (Cloudflare Family, OpenDNS
 * FamilyShield, …) without re-deriving them from DefaultDnsPresets.
 */
@Database(
    entities = [
        BlockScreenCountItemModel::class,
        PendingRequestItemModel::class,
        SelectedAppItemModel::class,
        SelectedKeywordItemModel::class,
        StopMeDurationItemModel::class,
        StopMeSessionCountItemModel::class,
        SwitchStatusItemModel::class,
        VpnCustomDnsItemModel::class,
        ScheduledRestrictionItemModel::class,
        ScheduledRestrictionAppItemModel::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockScreenCountDao(): BlockScreenCountDao
    abstract fun pendingRequestDao(): PendingRequestDao
    abstract fun selectedAppsListDao(): SelectedAppListAppsDao
    abstract fun selectedKeywordDao(): SelectedKeywordDao
    abstract fun stopMeDurationDao(): StopMeDurationDao
    abstract fun stopMeSessionCountDao(): StopMeSessionCountDao
    abstract fun switchStatusDao(): SwitchStatusDao
    abstract fun vpnCustomDnsDao(): VpnCustomDnsDao
    abstract fun scheduledRestrictionDao(): ScheduledRestrictionDao
    abstract fun scheduledRestrictionAppDao(): ScheduledRestrictionAppDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "protect_yourself_database.db"
                )
                    // v8 → v9: add display_name column to vpn_custom_dns.
                    // IMPORTANT: we add the migration BEFORE fallbackToDestructiveMigration()
                    // so that existing v1.0.33 users keep their data (block count,
                    // keywords, app lists, etc.) when they upgrade to v1.0.34+.
                    // fallbackToDestructiveMigration() is only a last-resort fallback
                    // for any future schema bumps that don't have a migration written.
                    .addMigrations(MIGRATION_10_11).addMigrations(MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .addCallback(AppDatabaseCallback(context.applicationContext))
                    .build()
                    .also { instance = it }
            }
        }

        /**
         * Migration v8 → v9: adds the `displayName` column to `vpn_custom_dns`.
         *
         * BUGFIX (v1.0.49): the column name is now `displayName` (camelCase) to
         * match the @Entity field name and Room 2.6.1's default column naming
         * policy. The previous version used `display_name` (snake_case), which
         * created a dead column that the entity never read or wrote.
         *
         * SCHEMA-01 fix (v1.0.57): this migration now also delegates to
         * [repairVpnCustomDnsSchema] which handles the full snake_case →
         * camelCase column transition for ALL columns (not just displayName).
         * This catches users upgrading from pre-v1.0.49 builds that had
         * snake_case columns.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS streak_dates_table")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `scheduled_restrictions` (
                        `restrictionKey` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `startTimeMinutes` INTEGER NOT NULL,
                        `endTimeMinutes` INTEGER NOT NULL,
                        `daysOfWeek` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL DEFAULT 1,
                        `isStrictMode` INTEGER NOT NULL DEFAULT 0,
                        `focusProfile` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`restrictionKey`)
                    )"""
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `scheduled_restriction_apps` (
                        `restrictionKey` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `appName` TEXT NOT NULL,
                        PRIMARY KEY(`restrictionKey`, `packageName`),
                        FOREIGN KEY(`restrictionKey`) REFERENCES `scheduled_restrictions`(`restrictionKey`) ON DELETE CASCADE
                    )"""
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Timber.i("Running migration v8 → v9: adding displayName to vpn_custom_dns")
                try {
                    database.execSQL(
                        "ALTER TABLE vpn_custom_dns ADD COLUMN displayName TEXT NOT NULL DEFAULT ''"
                    )
                } catch (_: Throwable) {
                    Timber.w("MIGRATION_8_9: ALTER TABLE ADD COLUMN displayName failed (column may already exist) — defensive repair will handle")
                }
                for (preset in protect.yourself.features.blockerPage.utils.DefaultDnsPresets.ALL) {
                    try {
                        database.execSQL(
                            "UPDATE vpn_custom_dns SET displayName = ? WHERE `key` = ?",
                            arrayOf(preset.displayName, preset.key)
                        )
                    } catch (_: Throwable) {}
                }
                // SCHEMA-01: also repair any snake_case columns from pre-v1.0.49
                repairVpnCustomDnsSchema(database)
                Timber.i("Migration v8 → v9 complete")
            }
        }

        /**
         * Migration v9 → v10: comprehensive vpn_custom_dns schema repair.
         *
         * SCHEMA-01 fix (v1.0.57): this migration addresses the root cause of
         * crash_20260712_160322 (SQLiteException: table vpn_custom_dns has no
         * column named first_dns).
         *
         * ## Root cause
         *
         * The v1.0.49 fix changed raw SQL from snake_case to camelCase to
         * match Room 2.6.1's generated column names. But users who had an
         * older DB (pre-v1.0.49, with snake_case columns) still had corrupt
         * tables after upgrading. The MIGRATION_8_9 only added `displayName` —
         * it did NOT handle the snake_case → camelCase column transition for
         * `firstDns`, `secondDns`, `isSelected`.
         *
         * Additionally, `ensureVpnCustomDnsSchema` only ran in `onCreate`,
         * which does NOT fire on upgrade — so corrupt tables from old installs
         * were never repaired.
         *
         * ## What this migration does
         *
         * 1. Checks if `vpn_custom_dns` has the correct camelCase columns.
         * 2. If not, DROP + CREATE with the correct schema (matching the
         *    VpnCustomDnsItemModel entity).
         * 3. Re-inserts the 4 default DNS presets.
         *
         * This is a safe destructive repair — the vpn_custom_dns table only
         * contains preset entries that can be re-inserted. User-added custom
         * DNS entries would be lost, but this is acceptable because:
         *   - The table was already corrupt (columns missing) and unusable.
         *   - Users can re-add custom DNS entries in the VPN management UI.
         *   - The alternative (leaving the table corrupt) causes a crash on
         *     every DB open.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Timber.i("Running migration v9 → v10: comprehensive vpn_custom_dns schema repair")
                try {
                    repairVpnCustomDnsSchema(database)
                    Timber.i("Migration v9 → v10 complete")
                } catch (t: Throwable) {
                    Timber.e(t, "Migration v9 → v10 failed — fallbackToDestructiveMigration will handle")
                    throw t
                }
            }
        }

        /**
         * Repair the vpn_custom_dns table schema if it's corrupt.
         *
         * This is called from both MIGRATION_9_10 and AppDatabaseCallback.onOpen.
         * It checks whether the table has the correct camelCase columns
         * (matching VpnCustomDnsItemModel). If not, it drops and recreates
         * the table, then re-inserts the default presets.
         *
         * Idempotent — safe to call even if the schema is already correct.
         */
        private fun repairVpnCustomDnsSchema(db: SupportSQLiteDatabase) {
            val expectedColumns = setOf(
                "key", "displayName", "firstDns", "secondDns", "isSelected"
            )
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
                Timber.w(t, "repairVpnCustomDnsSchema: PRAGMA table_info failed — assuming fresh table")
                return
            }

            if (!tableExists || existingColumns.isEmpty()) {
                Timber.d("repairVpnCustomDnsSchema: vpn_custom_dns does not exist — Room will create it")
                return
            }

            val missingColumns = expectedColumns - existingColumns
            if (missingColumns.isEmpty()) {
                Timber.d("repairVpnCustomDnsSchema: schema already correct (columns=${existingColumns.sorted()})")
                return
            }

            Timber.w("repairVpnCustomDnsSchema: missing columns detected: ${missingColumns.sorted()} (existing=${existingColumns.sorted()}) — dropping and recreating table")

            try {
                db.execSQL("DROP TABLE IF EXISTS vpn_custom_dns")
            } catch (dropErr: Throwable) {
                Timber.e(dropErr, "repairVpnCustomDnsSchema: DROP TABLE failed — attempting CREATE anyway")
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

            for (preset in protect.yourself.features.blockerPage.utils.DefaultDnsPresets.ALL) {
                try {
                    db.execSQL(
                        "INSERT OR IGNORE INTO vpn_custom_dns (`key`, displayName, firstDns, secondDns, isSelected) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(preset.key, preset.displayName, preset.firstDns, preset.secondDns, preset.isSelectedByDefault)
                    )
                } catch (_: Throwable) {}
            }

            Timber.i("repairVpnCustomDnsSchema: vpn_custom_dns table recreated with correct schema + ${protect.yourself.features.blockerPage.utils.DefaultDnsPresets.ALL.size} presets re-inserted")
        }
    }
}
