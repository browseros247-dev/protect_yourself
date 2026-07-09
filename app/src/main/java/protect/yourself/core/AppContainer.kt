package protect.yourself.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection container.
 *
 * Replaces the original AppContainer which held:
 *  - applicationScope: GlobalScope
 *  - billingDataSource: BillingDataSource          [REMOVED - billing stripped]
 *  - premiumPageDataRepository: PremiumPageDataRepository [REMOVED - premium stripped]
 *
 * Rebuild adds:
 *  - appDatabase: AppDatabase (Room)
 *  - applicationScope: CoroutineScope (SupervisorJob + IO dispatcher)
 */
class AppContainer(
    private val appContext: Context
) {
    /**
     * Application-scoped coroutine supervisor scope.
     * Use for fire-and-forget background work that should survive ViewModel lifecycle.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Room database (singleton).
     * Phase 2 will populate all DAOs.
     */
    val appDatabase: protect.yourself.database.core.AppDatabase
        get() = protect.yourself.database.core.AppDatabase.getInstance(appContext)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
        }
    }
}
