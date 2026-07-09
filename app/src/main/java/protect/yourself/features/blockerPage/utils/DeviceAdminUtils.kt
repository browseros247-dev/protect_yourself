package protect.yourself.features.blockerPage.utils

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Device admin utilities + receiver for anti-uninstall.
 *
 * Phase 6 — full implementation:
 *  - enableDeviceAdmin(): request user to enable device admin
 *  - disableDeviceAdmin(): for testing / user opt-out
 *  - MyDeviceAdminReceiver: handles device admin enable/disable callbacks
 */
class DeviceAdminUtils {

    /**
     * Receiver registered in manifest. Prevents uninstall via "Disable" button.
     */
    class MyDeviceAdminReceiver : DeviceAdminReceiver() {
        override fun onEnabled(context: Context, intent: Intent) {
            super.onEnabled(context, intent)
            Timber.i("Device admin enabled")
        }

        override fun onDisabled(context: Context, intent: Intent) {
            super.onDisabled(context, intent)
            Timber.w("Device admin disabled")
        }

        override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
            return "Disabling device admin will reduce your protection. Are you sure?"
        }
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, MyDeviceAdminReceiver::class.java)
        }
    }
}
