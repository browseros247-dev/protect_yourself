package protect.yourself.features.streakPage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.features.mainActivityPage.MainActivity
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Streak home-screen widget.
 *
 * Behavior:
 *  - Shows current streak day count
 *  - Tap → open MainActivity at Streak tab
 *  - Uses goAsync() for DB operations (no runBlocking on main thread)
 */
class StreakWidget : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.streak_widget)

        val pendingResult = goAsync()
        scope.launch {
            try {
                val db = protect.yourself.database.core.AppDatabase.getInstance(context)
                val allData = db.streakDatesDao().observeAll()

                // Get synchronous snapshot
                val items = db.streakDatesDao().observeAll().first()

                val currentStreak = calculateConsecutiveStreak(items)
                views.setTextViewText(R.id.txtStreakCount, currentStreak.toString())
            } catch (t: Throwable) {
                Timber.w(t, "Failed to calculate streak for widget")
                views.setTextViewText(R.id.txtStreakCount, "0")
            } finally {
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
                pendingResult.finish()
            }
        }
    }

    /**
     * Calculate consecutive streak days.
     */
    private fun calculateConsecutiveStreak(items: List<protect.yourself.database.streakDates.StreakDatesItemModel>): Int {
        if (items.isEmpty()) return 0

        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = todayCal.timeInMillis

        val sortedByTime = items.sortedByDescending { it.startTime }
        val mostRecentRelapse = sortedByTime.firstOrNull { it.type.isNotBlank() }

        val streakStartDay: Long = if (mostRecentRelapse != null) {
            val relapseCal = Calendar.getInstance().apply {
                timeInMillis = mostRecentRelapse.startTime
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            relapseCal.add(Calendar.DAY_OF_YEAR, 1)
            relapseCal.timeInMillis
        } else {
            val firstActive = sortedByTime.lastOrNull { it.type.isBlank() } ?: return 0
            val firstCal = Calendar.getInstance().apply {
                timeInMillis = firstActive.startTime
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            firstCal.timeInMillis
        }

        val diffMs = todayStart - streakStartDay
        if (diffMs < 0) return 0
        return (TimeUnit.MILLISECONDS.toDays(diffMs).toInt() + 1).coerceAtLeast(0)
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
    }
}
