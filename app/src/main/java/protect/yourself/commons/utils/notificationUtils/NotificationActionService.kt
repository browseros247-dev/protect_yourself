package protect.yourself.commons.utils.notificationUtils

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service for handling notification button actions
 * (e.g. "Stop" button on Stop Me notification).
 *
 * Phase 6 — full implementation.
 */
class NotificationActionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO Phase 6
        return START_NOT_STICKY
    }
}
