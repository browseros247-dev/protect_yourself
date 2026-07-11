package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import protect.yourself.core.appCoroutineScope
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import timber.log.Timber

/**
 * Receiver for package install/remove/replace events.
 *
 * Handles:
 *  - MY_PACKAGE_REPLACED: App was updated — restart services
 *  - PACKAGE_ADDED: New app installed — auto-add to block list if "Block new install apps" is ON
 *  - PACKAGE_REMOVED: App uninstalled — clean up from DB
 */
class AppSystemActionReceiverAllTimeWithData : BroadcastReceiver() {

    private val scope = appCoroutineScope(
        scopeName = "AppSystemActionReceiverAllTimeWithData",
        dispatcher = kotlinx.coroutines.Dispatchers.IO
    )

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("Package event: ${intent.action} data=${intent.data}")
        // Mirror NopoX: attempt self-heal on every package event. The most
        // important one is MY_PACKAGE_REPLACED — after an app update, Android
        // sometimes re-evaluates accessibility permissions and may disable
        // the service. selfHealSafe re-arms it instantly.
        try {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(context)
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe in package-event receiver failed")
        }
        val pendingResult = goAsync()

        scope.launch {
            try {
                val packageName = intent.data?.schemeSpecificPart
                when (intent.action) {
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        // App was updated — refresh accessibility config
                        MyAccessibilityService.instance?.refreshBlockingConfig()
                        Timber.i("App replaced — services refreshed")
                    }

                    Intent.ACTION_PACKAGE_ADDED -> {
                        if (packageName != null) {
                            // Check if "Block new install apps" is ON
                            val db = AppDatabase.getInstance(context)
                            val switchValues = SwitchStatusValues(db.switchStatusDao())
                            if (switchValues.isBlockNewInstallAppsSwitchOn()) {
                                // Auto-add the new app to the block list
                                val pm = context.packageManager
                                val appName = try {
                                    pm.getApplicationLabel(
                                        pm.getApplicationInfo(packageName, 0)
                                    ).toString()
                                } catch (_: Throwable) { packageName }

                                val item = SelectedAppItemModel(
                                    key = "block_new_install_${packageName}",
                                    packageName = packageName,
                                    appName = appName,
                                    identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
                                    isSelected = true
                                )
                                db.selectedAppsListDao().upsert(item)
                                Timber.i("New app auto-blocked: $packageName ($appName)")
                                // AB-15 fix: refresh the accessibility service's
                                // cached config so it knows about the new app
                                // immediately. Without this, the new app wouldn't
                                // be blocked until the next periodic refresh.
                                MyAccessibilityService.instance?.refreshBlockingConfig()
                            }
                        }
                    }

                    Intent.ACTION_PACKAGE_REMOVED -> {
                        if (packageName != null) {
                            // Clean up: remove from all app lists
                            val db = AppDatabase.getInstance(context)
                            for (identifier in SelectedAppListIdentifier.values()) {
                                db.selectedAppsListDao()
                                    .deleteByIdentifierAndPackage(identifier.value, packageName)
                            }
                            Timber.i("Removed app cleaned from DB: $packageName")
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to handle package event: ${intent.action}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
