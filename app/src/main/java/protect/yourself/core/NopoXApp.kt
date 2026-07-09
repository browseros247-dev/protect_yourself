package protect.yourself.core

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.crashlytics.FirebaseCrashlytics
import protect.yourself.BuildConfig
import protect.yourself.commons.signaturekiller.KillerApplication
import protect.yourself.database.core.AppDatabase
import timber.log.Timber

/**
 * Application class for Protect Yourself.
 *
 * All initialization steps are wrapped in try/catch to ensure no single
 * failure crashes the app on launch. Non-critical failures are logged
 * but don't prevent the app from starting.
 */
class NopoXApp : KillerApplication(), LifecycleObserver {

    lateinit var appContainer: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        Timber.d("App backgrounded")
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Init Timber logging FIRST (so other init steps can log)
        safeInit("TimberLog") { initTimberLog() }

        // 2. Init Room database (non-blocking — just creates the instance)
        safeInit("RoomDB") { AppDatabase.getInstance(this) }

        // 3. Init Crashlytics (optional — may fail if Firebase not configured)
        safeInit("Crashlytics") { initCrashlytics() }

        // 4. Lifecycle observer
        safeInit("LifecycleObserver") {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        // 5. App container (manual DI)
        safeInit("AppContainer") {
            appContainer = AppContainer(this)
        }

        // 6. Initialize PackageManagerProvider for app picker
        safeInit("PackageManagerProvider") {
            protect.yourself.commons.utils.PackageManagerProvider.init(this)
        }

        // 7. Accessibility self-heal + guard (Phase 6)
        safeInit("AccessibilityGuard") {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe()
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
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashlyticsTree())
        }
    }

    private fun initCrashlytics() {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            crashlytics.setCustomKey("build_number", BuildConfig.VERSION_CODE.toLong())
            crashlytics.setCustomKey("package", packageName)
        } catch (t: Throwable) {
            Timber.w(t, "Crashlytics init failed (likely Firebase not configured)")
        }
    }

    /**
     * Timber tree that forwards logs to Firebase Crashlytics.
     * Only WARN+ logs are forwarded to avoid noise.
     */
    private class CrashlyticsTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.WARN) return
            try {
                val crashlytics = FirebaseCrashlytics.getInstance()
                crashlytics.log("${logLevelLabel(priority)}/$tag: $message")
                t?.let { crashlytics.recordException(it) }
            } catch (_: Throwable) {
                // Crashlytics not configured
            }
        }

        private fun logLevelLabel(priority: Int): String = when (priority) {
            Log.ASSERT -> "A"
            Log.ERROR -> "E"
            Log.WARN -> "W"
            Log.INFO -> "I"
            Log.DEBUG -> "D"
            Log.VERBOSE -> "V"
            else -> "?"
        }
    }

    companion object {
        private const val TAG = "NopoXApp"

        @JvmStatic
        fun get(application: Application): NopoXApp =
            application as NopoXApp
    }
}
