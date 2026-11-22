package com.zilu.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.RingtoneManager
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Keeps a task's countdown alive after the activity is backgrounded. */
class TaskTimerService : Service() {
    private var timer: CountDownTimer? = null
    private var taskId = ""
    private var title = ""
    private var endAt = 0L
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        endAt = intent?.getLongExtra(EXTRA_END_AT, 0L) ?: 0L
        val remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
        createChannel(); startForeground(NOTIFICATION_ID, notification(remaining))
        timer?.cancel(); timer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(left: Long) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(left)) }
            override fun onFinish() {
                ZiluRepository(applicationContext).completeTask(taskId)
                RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { timer?.cancel(); super.onDestroy() }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "自律专注计时", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(left: Long): Notification {
        val text = "%02d:%02d".format(left / 60000, (left / 1000) % 60)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(title).setContentText("专注中 · 剩余 $text").setOngoing(true).setOnlyAlertOnce(true).build()
    }
    companion object {
        private const val CHANNEL = "zilu_focus"; private const val NOTIFICATION_ID = 208
        private const val EXTRA_TASK_ID = "task"; private const val EXTRA_TITLE = "title"; private const val EXTRA_END_AT = "end"
        fun start(context: android.content.Context, task: DailyTask): FocusSession {
            val start = System.currentTimeMillis(); val session = FocusSession(task.id, task.title, start, start + task.minutes * 60_000L, task.minutes)
            val intent = Intent(context, TaskTimerService::class.java).putExtra(EXTRA_TASK_ID, task.id).putExtra(EXTRA_TITLE, task.title).putExtra(EXTRA_END_AT, session.endAtMillis)
            ContextCompat.startForegroundService(context, intent)
            return session
        }
    }
}
