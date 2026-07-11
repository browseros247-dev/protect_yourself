package protect.yourself.features.blockerPage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import protect.yourself.R
import protect.yourself.core.appCoroutineScope
import protect.yourself.features.blockerPage.utils.StopMeManager
import protect.yourself.features.mainActivityPage.MainActivity
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * Stop Me home-screen widget.
 *
 * Behavior:
 *  - Tap → toggles Stop Me session (start 25min if not running, stop if running)
 *  - Shows remaining time if session is active
 *  - Toast feedback on start/stop
 */
class StopMeWidget : AppWidgetProvider() {

    private val scope = appCoroutineScope(
        scopeName = "StopMeWidget",
        dispatcher = kotlinx.coroutines.Dispatchers.IO
    )

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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_START_INSTANT -> {
                Timber.i("Stop Me widget tapped")
                // Use goAsync to allow background processing without ANR
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val db = protect.yourself.database.core.AppDatabase.getInstance(context)
                        val now = System.currentTimeMillis()
                        val active = db.stopMeDurationDao().getActiveInstantSession(now)

                        if (active != null) {
                            // Session running → stop it
                            StopMeManager.getInstance(context).stopActiveSession()
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, "Stop Me: Session stopped", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // No session → start with the last-used duration.
                            // PM-07 fix: was hardcoded 25 min. Now reads the
                            // most recent completed session's duration from DB.
                            // Falls back to 25 min if no history.
                            val recentSession = db.stopMeDurationDao().getAll()
                                .filter { it.days == 0 }
                                .maxByOrNull { it.endTime }
                            val duration = recentSession?.duration
                                ?: TimeUnit.MINUTES.toMillis(25)
                            val mins = TimeUnit.MILLISECONDS.toMinutes(duration)
                            StopMeManager.getInstance(context).startInstantSession(
                                durationMillis = duration
                            )
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, "Stop Me: ${mins}min session started", Toast.LENGTH_SHORT).show()
                            }
                        }

                        // Update all widgets
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val ids = appWidgetManager.getAppWidgetIds(
                            android.content.ComponentName(context, StopMeWidget::class.java)
                        )
                        ids.forEach { id ->
                            updateWidget(context, appWidgetManager, id)
                        }
                    } catch (t: Throwable) {
                        Timber.e(t, "Stop Me widget tap failed")
                    } finally {
                        pendingResult.finish()
                    }
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

        // Use goAsync pattern for DB read
        val pendingResult = goAsync()
        scope.launch {
            try {
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
                            "${context.getString(R.string.stop_me)} (${mins}:${"%02d".format(secs)})"
                        )
                    } else {
                        views.setTextViewText(R.id.txtMessage, context.getString(R.string.stop_me))
                    }
                } else {
                    views.setTextViewText(R.id.txtMessage, context.getString(R.string.stop_me))
                }
            } catch (_: Throwable) {
                views.setTextViewText(R.id.txtMessage, context.getString(R.string.stop_me))
            } finally {
                // Click → toggle session
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
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_START_INSTANT = "protect_yourself.action.WIDGET_START_INSTANT"
    }
}
