package com.fitledger.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class DailyEnergyWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Widgets.REFRESH_ACTION || Widgets.isDateAction(intent.action)) {
            Widgets.showReading(context)
            Widgets.requestRefresh(context)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Widgets.showReading(context)
        Widgets.requestRefresh(context)
    }

    override fun onEnabled(context: Context) { Widgets.requestRefresh(context) }
    override fun onDisabled(context: Context) { Widgets.cancel(context) }
}
