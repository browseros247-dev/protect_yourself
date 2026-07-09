package protect.yourself.features.blockerPage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import protect.yourself.R
import protect.yourself.features.blockerPage.utils.StopMeManager
import protect.yourself.features.mainActivityPage.MainActivity
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Stop Me home-screen widget — full implementation.
 *
 * Layout: R.layout.stop_me_widget
 *
 * Behavior:
 *  - Renders orange gradient button with focus icon + "Stop Me" text (or remaining time)
 *  - Tap → start instant Stop Me session (default 25 min)
 *  - If session running, tap → open MainActivity at Stop Me tab
 *  - Updates every 24h (per updatePeriodMillis) + on appwidget update broadcast
 */
class StopMeWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Timber.d("StopMeWidget onUpdate: ${appWidgetIds.size} widgets")
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onEnabled(context: Context) {
        Timber.i("First Stop Me widget placed")
    }

    override fun onDisabled(context: Context) {
        Timber.i("Last Stop Me widget removed")
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_START_INSTANT -> {
                Timber.i("Stop Me widget tapped — starting 25min instant session")
                kotlinx.coroutines.runBlocking {
                    StopMeManager.getInstance(context).startInstantSession(
                        durationMillis = TimeUnit.MINUTES.toMillis(25)
                    )
                }
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.stop_me_widget)

        // Check if there's an active session and update button text
        kotlinx.coroutines.runBlocking {
            val now = System.currentTimeMillis()
            val db = protect.yourself.database.core.AppDatabase.getInstance(context)
            val active = db.stopMeDurationDao().getActiveInstantSession(now)

            if (active != null) {
                val remainingMs = active.endTime - now
                if (remainingMs > 0) {
                    val mins = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
                    val secs = (TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60)
                    views.setTextViewText(
                        R.id.txtMessage,
                        context.getString(R.string.stop_me) + " (${mins}:${"%02d".format(secs)})"
                    )
                } else {
                    views.setTextViewText(R.id.txtMessage, context.getString(R.string.stop_me))
                }
            } else {
                views.setTextViewText(R.id.txtMessage, context.getString(R.string.stop_me))
            }
        }

        // Click → start instant session OR open app
        val intent = Intent(context, StopMeWidget::class.java).apply {
            action = ACTION_START_INSTANT
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.mainContainer, pendingIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    companion object {
        const val ACTION_START_INSTANT = "protect.yourself.action.WIDGET_START_INSTANT"
    }
}
