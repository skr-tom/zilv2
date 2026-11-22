package com.zilu.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Notification channels and helpers shared by reminders, focus timer and completion alerts. */
object Notifier {
    const val CH_REMINDER = "zilu_reminder_v2"
    const val CH_DONE = "zilu_done_v2"
    const val CH_FOCUS = "zilu_focus_v2"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "任务提醒", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_DONE, "专注完成", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_FOCUS, "专注计时", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        ensureChannels(context)
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }
}

object Alarms {
    /** Exact when the user allows it, otherwise the closest the system permits - never silently dropped. */
    fun set(context: Context, whenMillis: Long, pending: PendingIntent) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
        }.onFailure { runCatching { manager.set(AlarmManager.RTC_WAKEUP, whenMillis, pending) } }
    }

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

object ReminderScheduler {
    private fun pending(context: Context, task: DailyTask): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", task.id)
        return PendingIntent.getBroadcast(context, task.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(context: Context, taskId: String) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, taskId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).cancel(pending)
    }

    /** Schedules the next time this task should remind, honouring daily / weekly / one-off repeat. */
    fun schedule(context: Context, task: DailyTask) {
        cancel(context, task.id)
        val time = task.reminderTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return
        val now = LocalDateTime.now()
        var trigger: LocalDateTime? = null
        for (offset in 0..8) {
            val candidate = LocalDateTime.of(LocalDate.now().plusDays(offset.toLong()), time)
            if (candidate.isAfter(now) && task.occursOn(candidate.toLocalDate())) { trigger = candidate; break }
        }
        val at = trigger ?: return
        Alarms.set(context, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pending(context, task))
    }

    fun rescheduleAll(context: Context) {
        ZiluRepository(context).load().tasks.forEach { schedule(context, it) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = ZiluRepository(context).load()
        val task = state.tasks.firstOrNull { it.id == intent.getStringExtra("id") } ?: return
        ReminderScheduler.schedule(context, task) // line up the next occurrence first
        val today = LocalDate.now()
        val done = state.completionLogs.any { it.taskId == task.id && it.date == today.toString() }
        val focusing = state.activeFocus?.taskId == task.id
        if (task.occursOn(today) && !done && !focusing) {
            Notifier.post(context, task.id.hashCode(), Notifier.CH_REMINDER, "自律提醒", "您的「${task.title}」还没有做")
        }
    }
}

/** Alarms are wiped by a reboot or app update; put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.rescheduleAll(context)
    }
}
