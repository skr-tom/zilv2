package com.zilu.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object ReminderScheduler {
    private const val CHANNEL = "zilu_reminders"
    fun schedule(context: Context, id: String, title: String, time: String?) {
        if (time.isNullOrBlank()) return
        val localTime = runCatching { LocalTime.parse(time) }.getOrNull() ?: return
        var next = LocalDateTime.now().withHour(localTime.hour).withMinute(localTime.minute).withSecond(0)
        if (next.isBefore(LocalDateTime.now())) next = next.plusDays(1)
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("title", title)
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(AlarmManager::class.java)
        val whenMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Some manufacturers gate exact alarms. Fall back to a normal alarm instead of losing the reminder.
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending) }
            .getOrElse { manager.set(AlarmManager.RTC_WAKEUP, whenMillis, pending) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("zilu_reminders", context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val notification = android.app.Notification.Builder(context, "zilu_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("自律提醒")
            .setContentText("您的「${intent.getStringExtra("title") ?: "任务"}」还没有做")
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
