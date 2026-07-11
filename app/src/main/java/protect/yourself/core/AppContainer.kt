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
 *  - applicationScope: CoroutineScope (SupervisorJob + IO dispatcher +
 *    AppCoroutineExceptionHandler so uncaught coroutine exceptions are
 *    routed to CrashLogger with scope context — fixes the silent-loss
 *    bug where `async { throw }` without `await` lost the exception
 *    entirely).
 */
class AppContainer(
    private val appContext: Context
) {
    /**
     * Application-scoped coroutine supervisor scope.
     * Use for fire-and-forget background work that should survive ViewModel lifecycle.
     *
     * Installs [AppCoroutineExceptionHandler] so any uncaught exception in a
     * coroutine launched from this scope is logged to CrashLogger with
     * `tag="Coroutine:applicationScope"` + dispatcher + job context.
     */
    val applicationScope: CoroutineScope = appCoroutineScope(
        scopeName = "applicationScope",
        dispatcher = Dispatchers.Default,
        context = appContext
    )

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
