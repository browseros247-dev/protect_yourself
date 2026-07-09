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

/**
 * App Room database — Phase 1 placeholder.
 *
 * Full entity/DAO implementations land in Phase 2. For Phase 1, this file
 * declares all 9 entities so the build is green.
 *
 * Original schema version: 7
 * Rebuild starts fresh at version 8 to signal "new lineage".
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
    version = 8,
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
                    "nopox_database.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(AppDatabaseCallback(context.applicationContext))
                    .build()
                    .also { instance = it }
            }
        }

        // Reserved: original had MIGRATION_1_2 through MIGRATION_6_7.
        // We do not need these for the rebuild (fresh install).
        @Suppress("unused")
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // No-op: rebuild uses fresh schema.
            }
        }
    }
}
