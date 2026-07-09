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
 * Application class for protect.yourself.
 *
 * Initialization order (matches original NopoXApp):
 *  1. ProcessLifecycleOwner observer (for ON_STOP background events)
 *  2. super.onCreate()
 *  3. initRoomDBInstance()
 *  4. initTimberLog()
 *  5. initCrashlytics()
 *  6. BlockerPageUtils.updateAccessibilityBlockingValues() (Phase 3+)
 *  7. setAppContainer()
 *  8. AccessibilityPersistUtils.selfHealSafe() (Phase 6)
 *  9. AccessibilityGuard.startWatching() (Phase 6)
 *
 * Differences from original:
 *  - REMOVED: initBranchSDK (replaced by standard App Links)
 *  - REMOVED: initAmplitude
 *  - REMOVED: initFirebaseAppCheck (skipped — user can re-enable if needed)
 *  - REMOVED: initMavericksInstance (using ViewModel + StateFlow instead)
 *  - REMOVED: BillingDataSource + PremiumPageDataRepository from AppContainer
 */
class NopoXApp : KillerApplication(), LifecycleObserver {

    lateinit var appContainer: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        // Phase 3+: BlockerPageUtils.updateAccessibilityBlockingValues(GlobalScope)
        Timber.d("App backgrounded")
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // 2. Init Room database
        initRoomDBInstance()

        // 3. Init Timber logging
        initTimberLog()

        // 4. Init Crashlytics (kept per user choice; Amplitude + Analytics removed)
        initCrashlytics()

        // 5. App container (manual DI)
        appContainer = AppContainer(this)
        setAppContainer(appContainer)

        // 5b. Initialize PackageManagerProvider for app picker
        protect.yourself.commons.utils.PackageManagerProvider.init(this)

        // 6. Phase 3+: Accessibility self-heal + guard
        // AccessibilityPersistUtils.selfHealSafe()
        // AccessibilityGuard.startWatching(AppCtx)

        // 7. Schedule periodic data check worker
        protect.yourself.commons.utils.workManager.WorkerUtils.getInstance()
            .initAppDataCheckWorker(this)

        Timber.i("NopoXApp initialized — protect.yourself v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Log.i(TAG, "App initialized: ${packageName}")
    }

    private fun initRoomDBInstance() {
        // Eagerly initialize Room database so it's ready before any screen
        AppDatabase.getInstance(this)
    }

    private fun initTimberLog() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In release, plant a Crashlytics tree so errors are reported
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
            // Crashlytics may not be configured if google-services.json is placeholder
            Timber.w(t, "Crashlytics init failed (likely placeholder google-services.json)")
        }
    }

    private fun setAppContainer(container: AppContainer) {
        // container is already set above; this method exists for parity with original
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
