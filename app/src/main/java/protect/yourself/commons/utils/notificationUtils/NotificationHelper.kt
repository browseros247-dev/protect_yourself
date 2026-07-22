package protect.yourself.commons.utils.notificationUtils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import protect.yourself.R
import timber.log.Timber
import protect.yourself.features.mainActivityPage.MainActivity

/**
 * NotificationHelper — central notification channel + builder utilities.
 *
 * Channels (per user choice: daily report + Stop Me only):
 *  - daily_report_channel: daily summary notification
 *  - stop_me_channel: Stop Me foreground service notification (created in MyVpnService)
 *  - accessibility_alert_channel: high-priority alert when accessibility is disabled
 */
object NotificationHelper {

    const val CHANNEL_DAILY_REPORT = "daily_report_channel"
    const val CHANNEL_ACCESSIBILITY_ALERT = "accessibility_alert_channel"
    const val CHANNEL_GENERAL = "general_channel"

    const val NOTIF_ID_DAILY_REPORT = 2001
    const val NOTIF_ID_ACCESSIBILITY_ALERT = 2002
    const val NOTIF_ID_OVERLAY_PERMISSION = 2003
    const val NOTIF_ID_VPN_PERMISSION_REQUIRED = 2004

    private const val OVERLAY_PREFS = "overlay_permission_prefs"
    private const val KEY_LAST_OVERLAY_NOTIF_MS = "last_overlay_notif_ms"
    private const val OVERLAY_THROTTLE_MS = 24 * 60 * 60 * 1000L  // 24 hours

    /**
     * Create all notification channels.
     * Called from ProtectYourselfApp.onCreate.
     */
    fun createAllChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = context.getSystemService(NotificationManager::class.java)

        // Daily report channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY_REPORT,
                context.getString(R.string.daily_report_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily summary of blocking activity"
                setShowBadge(false)
            }
        )

        // Accessibility alert channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACCESSIBILITY_ALERT,
                "Accessibility alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts when accessibility service is disabled"
                setShowBadge(true)
            }
        )

        // General channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GENERAL,
                "General",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "General notifications"
                setShowBadge(false)
            }
        )
    }

    /**
     * OB-PERM-05 (v1.0.66): fail-fast gate — skip building + posting when the
     * app-level notification toggle is OFF (POST_NOTIFICATIONS denied on API
     * 33+, or user blocked notifications in settings). Previously every caller
     * built the notification, did PendingIntent plumbing and called notify()
     * which silently no-op'd (logcat noise, wasted work, and a potential
     * SecurityException on strict OEM builds).
     *
     * Fail-OPEN on read errors: a broken settings read must never silence a
     * critical protection alert — posting while disabled is a safe platform
     * no-op anyway.
     */
    private fun canPostNotifications(context: Context, what: String, notifId: Int): Boolean {
        val enabled = try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (t: Throwable) {
            Timber.w(t, "NotificationHelper: areNotificationsEnabled() failed — treating as enabled")
            true
        }
        if (!enabled) {
            Timber.w(
                "NotificationHelper: skipping '$what' (id=$notifId) — notifications are disabled " +
                    "(grant POST_NOTIFICATIONS via onboarding or system settings)"
            )
        }
        return enabled
    }

    /**
     * Show daily report notification.
     *
     * Streak feature removed in v1.0.62 — no longer includes streak days.
     */
    fun showDailyReportNotification(
        context: Context,
        blockCount: Int
    ) {
        if (!canPostNotifications(context, "daily report", NOTIF_ID_DAILY_REPORT)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY_REPORT)
            .setSmallIcon(R.drawable.ic_fire)
            .setContentTitle(context.getString(R.string.daily_report_title))
            .setContentText(
                context.getString(R.string.daily_report_text, blockCount)
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_DAILY_REPORT, notification)
    }

    /**
     * AUDIT FIX: Show a notification prompting the user to grant VPN permission.
     *
     * Called by MyVpnService.setScheduledBlockApps() when a scheduled internet
     * block rule is active but VPN permission hasn't been granted. The user
     * must open the app and enable VPN manually — we cannot launch the VPN
     * permission dialog from a background context.
     */
    /**
     * Posts a "VPN permission required" notification.
     *
     * VPN-NOTIF-04 fix (v1.0.64): the copy used to be hardcoded to the
     * scheduled-app-restriction scenario, but this notification is ALSO
     * posted from the boot-restore path when VPN consent was revoked —
     * where the schedule-specific text was misleading. Callers now pass
     * scenario-appropriate copy; the defaults preserve the original
     * scheduled-restriction wording for the existing ScheduleEngine call site.
     */
    fun showVpnPermissionRequiredNotification(
        context: Context,
        title: String = "Scheduled app restriction needs VPN permission",
        text: String = "Tap to open the app and enable VPN to block internet for scheduled apps.",
        bigText: String = "A scheduled app restriction is active, but the app needs VPN permission " +
            "to block internet access for the selected apps. Tap to open the app and " +
            "enable VPN in the Blocker settings."
    ) {
        if (!canPostNotifications(context, "vpn permission required", NOTIF_ID_VPN_PERMISSION_REQUIRED)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ACCESSIBILITY_ALERT)
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_VPN_PERMISSION_REQUIRED, notification)
    }

    /**
     * Show accessibility disabled alert (high-priority).
     */
    fun showAccessibilityDisabledNotification(context: Context) {
        if (!canPostNotifications(context, "accessibility disabled", NOTIF_ID_ACCESSIBILITY_ALERT)) return
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ACCESSIBILITY_ALERT)
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle("Protect Yourself: Blocking disabled!")
            .setContentText("Tap to re-enable accessibility service")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_ACCESSIBILITY_ALERT, notification)
    }

    /**
     * BUGFIX (v1.0.49): Show a notification prompting the user to grant the
     * SYSTEM_ALERT_WINDOW ("Display over other apps") permission.
     *
     * Crash log analysis showed the warning was logged but the user was never
     * proactively prompted. Without the overlay permission, the block screen
     * falls back to a dismissible Activity — weakening anti-circumvention.
     *
     * Throttled to once per 24 hours to prevent notification fatigue.
     */
    fun showOverlayPermissionNotification(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(OVERLAY_PREFS, 0)
        val lastMs = prefs.getLong(KEY_LAST_OVERLAY_NOTIF_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastMs < OVERLAY_THROTTLE_MS) return
        if (!canPostNotifications(ctx, "overlay permission", NOTIF_ID_OVERLAY_PERMISSION)) return

        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName)
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val pending = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ACCESSIBILITY_ALERT)
                .setSmallIcon(R.drawable.ic_info)
                .setContentTitle("Improve blocking: grant overlay permission")
                .setContentText("Tap to allow \"Display over other apps\" for a stronger block screen")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(
                        "Protect Yourself can show a non-dismissible block screen when it detects " +
                        "blocked content, but this requires the \"Display over other apps\" permission. " +
                        "Without it, the block screen can be dismissed. Tap to grant the permission."
                    ))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID_OVERLAY_PERMISSION, notification)
            prefs.edit().putLong(KEY_LAST_OVERLAY_NOTIF_MS, now).apply()
        } catch (t: Throwable) {
            android.util.Log.w("NotificationHelper", "showOverlayPermissionNotification failed", t)
        }
    }
}
