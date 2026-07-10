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
import protect.yourself.database.streakDates.StreakDatesDao
import protect.yourself.database.streakDates.StreakDatesItemModel
import protect.yourself.database.switchStatus.SwitchStatusDao
import protect.yourself.database.switchStatus.SwitchStatusItemModel
import protect.yourself.database.vpnCustomDns.VpnCustomDnsDao
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
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
        StreakDatesItemModel::class,
        SwitchStatusItemModel::class,
        VpnCustomDnsItemModel::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockScreenCountDao(): BlockScreenCountDao
    abstract fun pendingRequestDao(): PendingRequestDao
    abstract fun selectedAppsListDao(): SelectedAppListAppsDao
    abstract fun selectedKeywordDao(): SelectedKeywordDao
    abstract fun stopMeDurationDao(): StopMeDurationDao
    abstract fun stopMeSessionCountDao(): StopMeSessionCountDao
    abstract fun streakDatesDao(): StreakDatesDao
    abstract fun switchStatusDao(): SwitchStatusDao
    abstract fun vpnCustomDnsDao(): VpnCustomDnsDao

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
                    // so that existing v1.0.33 users keep their data (streak, block count,
                    // keywords, app lists, etc.) when they upgrade to v1.0.34+.
                    // fallbackToDestructiveMigration() is only a last-resort fallback
                    // for any future schema bumps that don't have a migration written.
                    .addMigrations(MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .addCallback(AppDatabaseCallback(context.applicationContext))
                    .build()
                    .also { instance = it }
            }
        }

        /**
         * Migration v8 → v9: adds the `display_name` column to `vpn_custom_dns`.
         *
         * Before this migration, the table had columns (key, first_dns, second_dns,
         * is_selected) — display names were looked up from DefaultDnsPresets.ALL
         * by matching the key. v9 adds a `display_name` column so the VPN management
         * UI can render provider names directly from the DB (and so user-added
         * custom presets can have their own display name).
         *
         * The migration:
         *   1. ALTERs the table to add `display_name TEXT NOT NULL DEFAULT ''`.
         *   2. Backfills the display_name for the 4 default presets by matching key.
         *
         * This preserves all user data (streak, block count, keywords, app lists,
         * switch states, custom DNS presets, etc.) — only the new column is added.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Timber.i("Running migration v8 → v9: adding display_name to vpn_custom_dns")
                // Add the column with a safe default (empty string). NOT NULL so
                // future inserts can never leave it null.
                database.execSQL(
                    "ALTER TABLE vpn_custom_dns ADD COLUMN display_name TEXT NOT NULL DEFAULT ''"
                )
                // Backfill the display names for the 4 default presets. User-added
                // presets (if any exist — not possible in v8 because there was no
                // add UI, but defensive) keep the empty default; the VPN management
                // UI falls back to the key when displayName is blank.
                for (preset in protect.yourself.features.blockerPage.utils.DefaultDnsPresets.ALL) {
                    database.execSQL(
                        "UPDATE vpn_custom_dns SET display_name = ? WHERE `key` = ?",
                        arrayOf(preset.displayName, preset.key)
                    )
                }
                Timber.i("Migration v8 → v9 complete")
            }
        }
    }
}
