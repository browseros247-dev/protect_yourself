package protect.yourself.core

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import protect.yourself.BuildConfig
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.crashLog.CrashLogger
import protect.yourself.features.crashLog.CrashLoggingTree
import protect.yourself.features.crashLog.CrashSeverity
import timber.log.Timber

/**
 * Application class for Protect Yourself.
 *
 * Extends Application directly (NO signature killer, NO Firebase).
 *
 * CRASH PREVENTION + LOGGING STRATEGY:
 * 1. All auto-initializers disabled in AndroidManifest.xml (FirebaseInitProvider,
 *    WorkManagerInitializer, ProcessLifecycleInitializer, EmojiCompat, ProfileInstaller)
 * 2. Everything initialized manually here in onCreate() with try/catch
 * 3. CrashLogger initialised FIRST — captures structured crash entries with full
 *    diagnostic context (device info, app info, memory, disk, logcat tail,
 *    breadcrumbs, stack trace, cause chain). Persisted to filesDir/crashlogs/.
 * 4. Global uncaught exception handler routes to CrashLogger (severity FATAL)
 *    before delegating to the previous handler (so the OS still gets the crash).
 * 5. CrashLoggingTree planted alongside Timber.DebugTree so all Timber.e()/w()
 *    calls are captured in the structured crash log too.
 *
 * Firebase was removed entirely because FirebaseInitProvider auto-initializes
 * Firebase BEFORE Application.onCreate() and crashes if the project config is
 * invalid. The app's core features don't need Firebase — CrashLogger provides
 * comprehensive offline crash analysis instead.
 */
class ProtectYourselfApp : Application(), LifecycleObserver, Configuration.Provider {

    lateinit var appContainer: AppContainer
        private set

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        Timber.d("App backgrounded")
    }

    override fun onCreate() {
        super.onCreate()

        // 0. Initialise CrashLogger FIRST — so any crash during subsequent init
        //    steps is captured with full diagnostic context.
        //    Must happen before installCrashHandler() and before any Timber calls.
        CrashLogger.init(this)
        crashLogger = CrashLogger.get()

        // 1. Install global uncaught exception handler (routes to CrashLogger)
        installCrashHandler()

        // 2. Init Timber logging — plants DebugTree + CrashLoggingTree so all
        //    Timber.e()/w() calls are captured in the structured crash log.
        safeInit("TimberLog") { initTimberLog() }

        // Drop a startup breadcrumb so future crashes show app startup time
        crashLogger?.logBreadcrumb("AppLifecycle", "Application.onCreate started")

        // 3. Init Room database (non-blocking — just creates the instance)
        safeInit("RoomDB") { AppDatabase.getInstance(this) }

        // 4. Lifecycle observer
        safeInit("LifecycleObserver") {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        // 5. App container (manual DI) — initialize directly (not in safeInit)
        // so lateinit var is always set
        appContainer = AppContainer(this)

        // 6. Initialize PackageManagerProvider for app picker
        safeInit("PackageManagerProvider") {
            protect.yourself.commons.utils.PackageManagerProvider.init(this)
        }

        // 7. Accessibility self-heal + guard
        //    PackageManagerProvider MUST be initialised before this step
        //    because AccessibilityPersistUtils.ownComponentFlat calls
        //    PackageManagerProvider.getPackageName() lazily.
        safeInit("AccessibilityGuard") {
            // Ensure PackageManagerProvider is initialised even if step 6 failed
            try {
                protect.yourself.commons.utils.PackageManagerProvider.init(this)
            } catch (_: Throwable) {}
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this)
            protect.yourself.features.protectedApps.AccessibilityGuard.getInstance()
                .startWatching(this)
        }

        // 8. Schedule periodic workers (non-critical — app works without them)
        safeInit("WorkManager") {
            protect.yourself.commons.utils.workManager.WorkerUtils.getInstance()
                .initAppDataCheckWorker(this)
        }

        // 9. Create notification channels
        safeInit("NotificationChannels") {
            protect.yourself.commons.utils.notificationUtils.NotificationHelper
                .createAllChannels(this)
        }

        crashLogger?.logBreadcrumb("AppLifecycle", "Application.onCreate completed")
        Timber.i("Protect Yourself v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) initialized")
        Log.i(TAG, "App initialized: $packageName")
    }

    /**
     * WorkManager Configuration.Provider — enables on-demand initialization.
     * WorkManager is initialized when first accessed, NOT during app startup.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    /**
     * Wrapper that catches any exception from an init step and logs it
     * to CrashLogger + logcat without crashing the app.
     */
    private fun safeInit(stepName: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.e(t, "Init step '$stepName' failed (non-fatal)")
            Log.e(TAG, "Init step '$stepName' failed", t)
            crashLogger?.logThrowable(
                throwable = t,
                severity = CrashSeverity.ERROR,
                tag = "AppInit",
                message = "Init step '$stepName' failed (non-fatal)",
                extraContext = mapOf("initStep" to stepName)
            )
        }
    }

    private fun initTimberLog() {
        // Plant DebugTree for logcat + CrashLoggingTree for structured crash logs
        Timber.plant(Timber.DebugTree())
        crashLogger?.let { Timber.plant(CrashLoggingTree(it)) }
    }

    /**
     * Install a global uncaught exception handler that routes crashes to
     * CrashLogger with severity FATAL (full diagnostic context captured)
     * before delegating to the previous handler (so the OS still handles
     * the crash normally — shows "App keeps stopping" dialog etc.).
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashLogger?.logThrowable(
                    throwable = throwable,
                    severity = CrashSeverity.FATAL,
                    tag = "UncaughtExceptionHandler",
                    message = "Uncaught exception on thread ${thread.name}",
                    extraContext = mapOf(
                        "threadName" to thread.name,
                        "threadId" to thread.id.toString(),
                        "isDaemon" to thread.isDaemon.toString()
                    )
                )
                android.util.Log.e(TAG, "FATAL CRASH on ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // Can't even log the crash — give up gracefully
            }
            // Delegate to the previous handler so the OS still gets the crash
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "ProtectYourselfApp"

        @Volatile
        private var crashLogger: CrashLogger? = null

        /**
         * Get the CrashLogger singleton (initialized in onCreate).
         * Returns null if not yet initialised.
         */
        fun getCrashLogger(): CrashLogger? = crashLogger

        @JvmStatic
        fun get(application: Application): ProtectYourselfApp =
            application as ProtectYourselfApp
    }
}

