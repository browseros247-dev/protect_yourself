package protect.yourself.commons.utils

import android.content.Context
import android.content.pm.PackageManager

/**
 * Provides access to PackageManager + application context without requiring
 * a Context per call. Initialized in ProtectYourselfApp.onCreate().
 */
object PackageManagerProvider {
    @Volatile
    private var pm: PackageManager? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (pm == null) {
            pm = context.packageManager
        }
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    val packageManager: PackageManager
        get() = pm ?: throw IllegalStateException("PackageManagerProvider not initialized")

    /**
     * The application context. Returns null if [init] has not been called yet
     * (e.g. during very early Application.onCreate before step 6).
     */
    fun getApplicationContext(): Context? = appContext

    /**
     * The application context's package name. Safe to call from anywhere
     * after [init]; throws if not initialised.
     */
    fun getPackageName(): String =
        appContext?.packageName
            ?: throw IllegalStateException("PackageManagerProvider not initialized")
}
