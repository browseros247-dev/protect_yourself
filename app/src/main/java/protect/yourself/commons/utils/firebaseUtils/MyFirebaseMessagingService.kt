package protect.yourself.commons.utils.firebaseUtils

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import timber.log.Timber

/**
 * Firebase Messaging Service.
 *
 * Phase 5+: handle incoming push notifications (daily report reminders,
 * accountability partner approval notifications).
 *
 * Phase 1: stub — just logs incoming messages.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Timber.d("FCM received: ${message.messageId} data=${message.data}")
        // TODO Phase 5: route to notification builder
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.i("FCM token refreshed: $token")
        // TODO Phase 5: persist token to SwitchStatus (FIREBASE_TOKEN) + Firestore
    }
}
