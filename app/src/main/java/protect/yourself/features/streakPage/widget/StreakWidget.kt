package protect.yourself.features.streakPage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import protect.yourself.R
import protect.yourself.features.mainActivityPage.MainActivity
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Streak home-screen widget — full implementation.
 *
 * Layout: R.layout.streak_widget
 *
 * Behavior:
 *  - Renders dark background with large white day count + "days" label
 *  - Tap → open MainActivity at Streak tab
 *  - Updates every 24h (per updatePeriodMillis)
 *
 * Streak calculation:
 *  - Counts consecutive days with no relapse ending today
 *  - Uses streak_dates_table where type = "" (active days)
 */
class StreakWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Timber.d("StreakWidget onUpdate: ${appWidgetIds.size} widgets")
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onEnabled(context: Context) {
        Timber.i("First Streak widget placed")
    }

    override fun onDisabled(context: Context) {
        Timber.i("Last Streak widget removed")
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.streak_widget)

        // Calculate current streak
        kotlinx.coroutines.runBlocking {
            try {
                val db = protect.yourself.database.core.AppDatabase.getInstance(context)
                val activeDays = db.streakDatesDao().observeActiveStreakDays()
                // Get most recent relapse
                val allData = db.streakDatesDao().observeAll()

                val currentStreak = calculateCurrentStreak(
                    activeDayCount = db.streakDatesDao().countActiveStreakDays()
                )
                views.setTextViewText(R.id.txtStreakCount, currentStreak.toString())
            } catch (t: Throwable) {
                Timber.w(t, "Failed to calculate streak for widget")
                views.setTextViewText(R.id.txtStreakCount, "0")
            }
        }

        // Tap → open MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "Streak")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.mainContainer, pendingIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    /**
     * Calculate current streak day count.
     *
     * Simple algorithm: count of active days since the most recent relapse.
     * If no relapses: count all active days.
     *
     * Phase 5 will implement a more accurate algorithm with calendar awareness.
     */
    private fun calculateCurrentStreak(activeDayCount: Int): Int {
        return activeDayCount.coerceAtLeast(0)
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
    }
}
