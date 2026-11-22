package com.zilu.app

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps a focus countdown visible in the status bar while the app is in the background.
 * A system alarm is also set for the end time, so the finish alert still arrives if the service is killed.
 */
class TaskTimerService : Service() {
    private var timer: CountDownTimer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val endAt = intent?.getLongExtra(EXTRA_END_AT, 0L) ?: 0L
        Notifier.ensureChannels(this)
        startForeground(NOTIFICATION_ID, notification(title, endAt))
        timer?.cancel()
        val remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
        timer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(left: Long) {}
            override fun onFinish() {
                FocusFinisher.finish(applicationContext, taskId, title)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    /** The system draws the countdown itself, so it keeps ticking even when this process is asleep. */
    private fun notification(title: String, endAt: Long): Notification =
        Notification.Builder(this, Notifier.CH_FOCUS)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title.ifBlank { "专注中" })
            .setContentText("专注中 · 点击回到自律")
            .setContentIntent(Notifier.openApp(this))
            .setWhen(endAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 208
        private const val END_REQUEST_CODE = 9001
        private const val EXTRA_TASK_ID = "task"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_END_AT = "end"

        private fun endPending(context: Context, taskId: String, title: String): PendingIntent {
            val intent = Intent(context, FocusEndReceiver::class.java).putExtra(EXTRA_TASK_ID, taskId).putExtra(EXTRA_TITLE, title)
            return PendingIntent.getBroadcast(context, END_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun cancelEndAlarm(context: Context) {
            val intent = Intent(context, FocusEndReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, END_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).cancel(pending)
        }

        fun start(context: Context, task: DailyTask): FocusSession {
            val start = System.currentTimeMillis()
            val session = FocusSession(task.id, task.title, start, start + task.minutes * 60_000L, task.minutes)
            val intent = Intent(context, TaskTimerService::class.java)
                .putExtra(EXTRA_TASK_ID, task.id).putExtra(EXTRA_TITLE, task.title).putExtra(EXTRA_END_AT, session.endAtMillis)
            ContextCompat.startForegroundService(context, intent)
            Alarms.set(context, session.endAtMillis, endPending(context, task.id, task.title))
            return session
        }

        /** Used when the user quits a focus session early. */
        fun stop(context: Context) {
            context.stopService(Intent(context, TaskTimerService::class.java))
            cancelEndAlarm(context)
        }
    }
}

/** Completes the task and raises the "time is up" alert. Safe to call twice: only the first call does anything. */
object FocusFinisher {
    private const val DONE_NOTIFICATION_ID = 209

    fun finish(context: Context, taskId: String, title: String) {
        val repo = ZiluRepository(context)
        val focus = repo.load().activeFocus
        if (focus == null || focus.taskId != taskId) return // quit early, or already finished
        repo.completeTask(taskId)
        TaskTimerService.stop(context)
        Notifier.post(
            context, DONE_NOTIFICATION_ID, Notifier.CH_DONE,
            "专注完成",
            "「${focus.title.ifBlank { title }}」的 ${focus.minutes} 分钟专注时间到了，任务已完成"
        )
    }
}

class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusFinisher.finish(context, intent.getStringExtra("task").orEmpty(), intent.getStringExtra("title").orEmpty())
    }
}
