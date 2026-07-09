package protect.yourself.commons.utils

import android.content.Context
import android.content.pm.PackageManager

/**
 * Provides access to PackageManager without requiring a Context per call.
 * Initialized in ProtectYourselfApp.onCreate().
 */
object PackageManagerProvider {
    @Volatile
    private var pm: PackageManager? = null

    fun init(context: Context) {
        if (pm == null) {
            pm = context.packageManager
        }
    }

    val packageManager: PackageManager
        get() = pm ?: throw IllegalStateException("PackageManagerProvider not initialized")
}
