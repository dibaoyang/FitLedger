package com.fitledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fitledger.MainActivity
import com.fitledger.R
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object Widgets {
    internal const val REFRESH_ACTION = "com.fitledger.widget.REFRESH"
    private const val REFRESH_WORK = "fitledger-widget-refresh"
    private const val PERIODIC_WORK = "fitledger-widget-periodic"
    private var dateReceiverRegistered = false
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isDateAction(intent.action)) {
                showReading(context)
                requestRefresh(context)
            }
        }
    }

    fun requestRefresh(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            if (ids(appContext).isEmpty()) return
            ensurePeriodic(appContext)
            // Replace a still-running read so an update after a successful edit cannot be lost.
            WorkManager.getInstance(appContext).enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
        }.onFailure {
            // A display refresh must never turn a completed foreground save into a failure.
            showError(appContext)
        }
    }

    @Synchronized internal fun ensurePeriodic(context: Context) {
        if (ids(context).isEmpty()) return
        if (!dateReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.applicationContext.registerReceiver(dateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                context.applicationContext.registerReceiver(dateReceiver, filter)
            }
            dateReceiverRegistered = true
        }
        // Persistent periodic fallback also covers date changes while the process is dead.
        // Android may delay work for power management; this is not an exact midnight alarm.
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build())
    }

    @Synchronized internal fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(REFRESH_WORK)
                cancelUniqueWork(PERIODIC_WORK)
            }
        }
        if (dateReceiverRegistered) {
            runCatching { context.applicationContext.unregisterReceiver(dateReceiver) }
            dateReceiverRegistered = false
        }
    }

    internal fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, DailyEnergyWidgetProvider::class.java))

    internal fun isDateAction(action: String?) = action == Intent.ACTION_DATE_CHANGED ||
        action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED

    internal fun showReading(context: Context) {
        runCatching { render(context, WidgetSnapshot(LocalDate.now().toEpochDay(), status = WidgetStatus.READING)) }
    }

    internal fun showError(context: Context) {
        runCatching { render(context, WidgetSnapshot(LocalDate.now().toEpochDay(), status = WidgetStatus.READ_ERROR)) }
    }

    internal fun render(context: Context, snapshot: WidgetSnapshot) {
        val appContext = context.applicationContext
        val text = WidgetFormatting.present(snapshot, LocalDate.now().toEpochDay())
        val manager = AppWidgetManager.getInstance(appContext)
        ids(appContext).forEach { id ->
            val views = RemoteViews(appContext.packageName, R.layout.widget_daily_energy)
            views.setTextViewText(R.id.widget_date, text.date)
            views.setTextViewText(R.id.widget_consumed, "已摄入 ${text.consumed}")
            views.setTextViewText(R.id.widget_target, "目标 ${text.target}")
            views.setTextViewText(R.id.widget_remaining, text.remaining)
            views.setTextViewText(R.id.widget_message, text.message)
            views.setViewVisibility(R.id.widget_energy, if (text.showEnergy) View.VISIBLE else View.GONE)
            val open = PendingIntent.getActivity(appContext, 7100, Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            val refresh = PendingIntent.getBroadcast(appContext, id,
                Intent(appContext, DailyEnergyWidgetProvider::class.java).setAction(REFRESH_ACTION)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            manager.updateAppWidget(id, views)
        }
    }
}
