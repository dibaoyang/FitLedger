package com.fitledger.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.fitledger.FitApp
import com.fitledger.MainActivity
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Reminders {
    fun schedule(context: Context,enabled:Boolean,hour:Int,minute:Int) {
        val manager=WorkManager.getInstance(context)
        if(!enabled) {manager.cancelUniqueWork("daily-weigh");return}
        val now=ZonedDateTime.now()
        var next=now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if(!next.isAfter(now)) next=next.plusDays(1)
        val request=PeriodicWorkRequestBuilder<WeighWorker>(24,TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now,next).toMillis(),TimeUnit.MILLISECONDS).build()
        manager.enqueueUniquePeriodicWork("daily-weigh",ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,request)
    }
}
class WeighWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as FitApp
        app.prepareData()
        if(!app.settings.flow.first().reminder || app.database.dao().body(LocalDate.now().toEpochDay())!=null) return Result.success()
        if(Build.VERSION.SDK_INT>=33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("weigh","每日称重",NotificationManager.IMPORTANCE_DEFAULT))
        val pending=PendingIntent.getActivity(app,0,Intent(app,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(1,NotificationCompat.Builder(app,"weigh").setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("记录今天的身体数据").setContentText("称重后，生成今日饮食目标。").setContentIntent(pending).setAutoCancel(true).build())
        return Result.success()
    }
}
