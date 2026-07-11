package protect.yourself.features.blockerPage.utils

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Device admin utilities + receiver for anti-uninstall.
 *
 * ## How Device Admin prevents uninstall
 *
 * When an app is a Device Admin, Android replaces the "Uninstall" button in
 * Settings → Apps with "Disable". Tapping "Disable" opens the Device Admin
 * deactivation screen first — which our accessibility service detects via
 * `isAppInfoPage()` and blocks with the overlay.
 *
 * The `<uses-policies>` element in `res/xml/device_admin.xml` is intentionally
 * empty — we claim no admin capabilities (no WipeData, no ResetPassword, no
 * DisableCamera). This is the minimum-impact use of Device Admin and is the
 * only legal anti-uninstall mechanism on stock Android.
 *
 * ## Error handling
 *
 * All methods are wrapped in try/catch (NopoX pattern). DevicePolicyManager
 * calls can throw on rooted devices or on OEM-modified ROMs. The safe
 * fallback is to return false / do nothing — never crash.
 *
 * Ported from NopoX `DeviceAdminUtils.java` (decompiled).
 */
class DeviceAdminUtils {

    /**
     * Receiver registered in manifest. Prevents uninstall via "Disable" button.
     *
     * UP-09 fix: NopoX's `onDisableRequested` returns an empty CharSequence
     * (it does NOT show a custom warning). The system shows its own default
     * "Deactivate this device admin app?" dialog, which is sufficient. We
     * match NopoX's behaviour — returning a custom CharSequence can cause
     * the dialog to be dismissed on some OEM ROMs.
     */
    class MyDeviceAdminReceiver : DeviceAdminReceiver() {

        override fun onEnabled(context: Context, intent: Intent) {
            try {
                super.onEnabled(context, intent)
                Timber.i("Device admin enabled")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()
                    ?.logBreadcrumb("DeviceAdmin", "enabled")
            } catch (t: Throwable) {
                Timber.w(t, "DeviceAdminReceiver.onEnabled threw")
            }
        }

        override fun onDisabled(context: Context, intent: Intent) {
            try {
                super.onDisabled(context, intent)
                Timber.w("Device admin disabled — user may be attempting uninstall")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()
                    ?.logBreadcrumb("DeviceAdmin", "DISABLED — possible uninstall attempt")
                // Post a high-priority notification warning the user
                try {
                    protect.yourself.commons.utils.notificationUtils.NotificationHelper
                        .showAccessibilityDisabledNotification(context)
                } catch (_: Throwable) {}
            } catch (t: Throwable) {
                Timber.w(t, "DeviceAdminReceiver.onDisabled threw")
            }
        }

        override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
            // UP-09 fix: NopoX returns an empty CharSequence (the system shows
            // its own default dialog). Returning a custom string here can
            // cause the dialog to be dismissed on some OEM ROMs (MIUI, EMUI).
            // We match NopoX's behaviour.
            return ""
        }
    }

    companion object {
        private const val TAG = "DeviceAdminUtils"

        /**
         * Get the ComponentName for our DeviceAdminReceiver.
         * Used by [isActive] and [requestActive].
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, MyDeviceAdminReceiver::class.java)
        }

        /**
         * Is our Device Admin currently active?
         * Safe fallback: false on any error.
         */
        fun isActive(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    ?: return false
                dpm.isAdminActive(getComponentName(context))
            } catch (t: Throwable) {
                Timber.w(t, "$TAG: isActive threw")
                false
            }
        }

        /**
         * Request the user to enable Device Admin.
         * Launches the system's "Activate device admin app" screen.
         */
        fun requestActive(activity: android.app.Activity) {
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(activity))
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Device Admin prevents unauthorized uninstall of Protect Yourself."
                    )
                }
                activity.startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN)
                Timber.i("$TAG: requestActive launched")
            } catch (t: Throwable) {
                Timber.e(t, "$TAG: requestActive failed")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                    tag = TAG,
                    message = "requestActive failed",
                    extraContext = emptyMap()
                )
            }
        }

        /**
         * Remove Device Admin (for testing or user opt-out).
         * Safe: no-op if not active or if DPM throws.
         */
        fun removeActive(context: Context) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    ?: return
                if (dpm.isAdminActive(getComponentName(context))) {
                    dpm.removeActiveAdmin(getComponentName(context))
                    Timber.i("$TAG: removeActive succeeded")
                }
            } catch (t: Throwable) {
                Timber.w(t, "$TAG: removeActive threw")
            }
        }

        private const val REQUEST_CODE_DEVICE_ADMIN = 1001
    }
}
