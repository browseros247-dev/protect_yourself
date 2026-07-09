package protect.yourself.features.streakPage.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import timber.log.Timber

/**
 * Streak home-screen widget — shows current day count.
 *
 * Phase 5 — full implementation:
 *  - Renders dark background + large white number
 *  - Updates every 24h (per updatePeriodMillis)
 *  - Tap → open app at Streak tab
 *
 * Phase 1: stub.
 */
class StreakWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Timber.d("StreakWidget onUpdate: ${appWidgetIds.size} widgets")
        // TODO Phase 5: render widget with current streak count
    }

    override fun onEnabled(context: Context) {
        Timber.i("First Streak widget placed")
    }

    override fun onDisabled(context: Context) {
        Timber.i("Last Streak widget removed")
    }
}
