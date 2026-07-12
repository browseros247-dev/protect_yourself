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
         * Migration v8 → v9: adds the `displayName` column to `vpn_custom_dns`.
         *
         * BUGFIX (v1.0.49): the column name is now `displayName` (camelCase) to
         * match the @Entity field name and Room 2.6.1's default column naming
         * policy. The previous version used `display_name` (snake_case), which
         * created a dead column that the entity never read or wrote.
         *
         * We try the camelCase column name first. If that fails because the
         * snake_case column already exists (from the old migration), we skip —
         * the defensive repair in AppDatabaseCallback.ensureVpnCustomDnsSchema
         * will handle the mismatch on the next onCreate call.
         */
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
                Timber.i("Migration v8 → v9 complete")
            }
        }
    }
}
