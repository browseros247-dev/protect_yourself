package protect.yourself.features.protectedApps

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber
import protect.yourself.features.blockerPage.utils.DeviceAdminUtils

/**
 * DeviceAdminManager — manages Device Admin activation for anti-uninstall.
 *
 * Ported from original DeviceAdminUtils (Phase 6).
 *
 * Behavior:
 *  - isActive(): check if device admin is enabled
 *  - requestActive(): launch system Device Admin activation screen
 *  - removeActive(): deactivate device admin (for user opt-out)
 */
class DeviceAdminManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent: ComponentName =
        DeviceAdminUtils.getComponentName(context)

    /** Returns true if device admin is currently active for this app. */
    fun isActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    /**
     * Launch system Device Admin activation screen.
     * Caller should call this from an Activity, not a Service.
     */
    fun requestActive() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Device Admin prevents unauthorized uninstall of protect.yourself."
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Deactivate device admin (allows uninstall).
     */
    fun removeActive() {
        try {
            dpm.removeActiveAdmin(adminComponent)
            Timber.i("Device admin removed")
        } catch (t: Throwable) {
            Timber.w(t, "Failed to remove device admin")
        }
    }

    companion object {
        @Volatile
        private var instance: DeviceAdminManager? = null

        fun getInstance(context: Context): DeviceAdminManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceAdminManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
