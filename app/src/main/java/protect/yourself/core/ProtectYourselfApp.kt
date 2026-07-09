package protect.yourself.core

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import protect.yourself.BuildConfig
import protect.yourself.database.core.AppDatabase
import timber.log.Timber

/**
 * Application class for Protect Yourself.
 *
 * Extends Application directly (NOT KillerApplication — the signature killer
 * was removed because it uses reflection to replace IPackageManager, which
 * breaks on Android 14+ and causes crashes during Firebase initialization.
 * The signature killer was only needed for the modified APK to bypass
 * signature checks against the original app — not needed for a fresh install.)
 *
 * All initialization steps are wrapped in try/catch to ensure no single
 * failure crashes the app on launch.
 */
class ProtectYourselfApp : Application(), LifecycleObserver {

    lateinit var appContainer: AppContainer
        private set

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        Timber.d("App backgrounded")
    }

    override fun onCreate() {
        super.onCreate()

        // 0. Install global uncaught exception handler to log crashes to a file
        // This helps debug crashes that happen before Timber is initialized
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

        // 6. Accessibility self-heal + guard (Phase 6)
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
     * to a file in the app's cache directory. This helps debug crashes that
     * happen before any logging is set up.
     *
     * The crash log is written to: /data/data/protect.yourself/cache/crash_log.txt
     * User can retrieve it via: adb shell run-as protect.yourself cat cache/crash_log.txt
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
