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
import timber.log.Timber

/**
 * Application class for Protect Yourself.
 *
 * Extends Application directly (NO signature killer, NO Firebase).
 *
 * CRASH PREVENTION STRATEGY:
 * 1. All auto-initializers disabled in AndroidManifest.xml (FirebaseInitProvider,
 *    WorkManagerInitializer, ProcessLifecycleInitializer, EmojiCompat, ProfileInstaller)
 * 2. Everything initialized manually here in onCreate() with try/catch
 * 3. Global uncaught exception handler writes crash logs to cache/crash_log.txt
 *
 * Firebase was removed entirely because FirebaseInitProvider auto-initializes
 * Firebase BEFORE Application.onCreate() and crashes if the project config is
 * invalid. The app's core features don't need Firebase.
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

        // 0. Install global uncaught exception handler to log crashes to a file
        installCrashHandler()

        // 1. Init Timber logging FIRST (so other init steps can log)
        safeInit("TimberLog") { initTimberLog() }

        // 2. Init Room database (non-blocking — just creates the instance)
        safeInit("RoomDB") { AppDatabase.getInstance(this) }

        // 3. Lifecycle observer
        safeInit("LifecycleObserver") {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        // 4. App container (manual DI) — initialize directly (not in safeInit)
        // so lateinit var is always set
        appContainer = AppContainer(this)

        // 5. Initialize PackageManagerProvider for app picker
        safeInit("PackageManagerProvider") {
            protect.yourself.commons.utils.PackageManagerProvider.init(this)
        }

        // 6. Accessibility self-heal + guard
        safeInit("AccessibilityGuard") {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe()
            protect.yourself.features.protectedApps.AccessibilityGuard.getInstance()
                .startWatching(this)
        }

        // 7. Schedule periodic workers (non-critical — app works without them)
        safeInit("WorkManager") {
            protect.yourself.commons.utils.workManager.WorkerUtils.getInstance()
                .initAppDataCheckWorker(this)
        }

        // 8. Create notification channels
        safeInit("NotificationChannels") {
            protect.yourself.commons.utils.notificationUtils.NotificationHelper
                .createAllChannels(this)
        }

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
     * without crashing the app.
     */
    private fun safeInit(stepName: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.e(t, "Init step '$stepName' failed (non-fatal)")
            Log.e(TAG, "Init step '$stepName' failed", t)
        }
    }

    private fun initTimberLog() {
        // Always use DebugTree — simple, no external dependencies, works everywhere
        Timber.plant(Timber.DebugTree())
    }

    /**
     * Install a global uncaught exception handler that writes crash stacktraces
     * to a file in the app's cache directory.
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = java.io.File(cacheDir, "crash_log.txt")
                java.io.PrintWriter(java.io.FileWriter(crashFile, true)).use { writer ->
                    writer.println("=== Crash at ${java.util.Date()} ===")
                    writer.println("Thread: ${thread.name}")
                    writer.println("Exception: ${throwable.javaClass.name}")
                    writer.println("Message: ${throwable.message}")
                    writer.println("Stacktrace:")
                    throwable.printStackTrace(writer)
                    writer.println()
                }
                android.util.Log.e(TAG, "CRASH: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // Can't even write crash log — give up
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "ProtectYourselfApp"

        @JvmStatic
        fun get(application: Application): ProtectYourselfApp =
            application as ProtectYourselfApp
    }
}
