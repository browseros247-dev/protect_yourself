package protect.yourself.features.blockerPage.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import timber.log.Timber

/**
 * Stop Me home-screen widget.
 *
 * Phase 3 — full implementation:
 *  - Renders orange gradient button with focus icon + "Stop Me" text
 *  - Tap → start instant Stop Me session (default 25min, configurable)
 *  - Updates every 24h (per updatePeriodMillis)
 *
 * Phase 1: stub.
 */
class StopMeWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Timber.d("StopMeWidget onUpdate: ${appWidgetIds.size} widgets")
        // TODO Phase 3: render widget via RemoteViews
    }

    override fun onEnabled(context: Context) {
        Timber.i("First StopMe widget placed")
    }

    override fun onDisabled(context: Context) {
        Timber.i("Last StopMe widget removed")
    }
}
