package com.zilu.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 专注倒计时的前台服务。
 * 通知栏用自带的沙漏图标常驻显示，切到后台也不会消失；
 * 同时向系统登记一个到点闹钟，即使进程被厂商 ROM 杀掉，到点也能弹提醒。
 */
class TaskTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var taskId: String = ""
    private var title: String = ""
    private var endAt: Long = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (endAt > 0 && System.currentTimeMillis() >= endAt) {
                FocusFinisher.finish(applicationContext, taskId)
            } else {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        endAt = intent?.getLongExtra(EXTRA_END_AT, 0L) ?: 0L

        Notifier.ensureChannels(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 500L)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, Notifier.CH_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_zilu)
            .setContentTitle(if (title.isBlank()) "专注中" else title)
            .setContentText("专注中 · 剩余时间见下方倒计时")
            .setContentIntent(Notifier.openApp(this, 7))
            .setWhen(endAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(Notifier.ACCENT)
        if (Build.VERSION.SDK_INT >= 31) {
            // 让前台通知立刻出现在通知栏，而不是延迟 10 秒
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        Notifier.appIcon(this)?.let { builder.setLargeIcon(it) }
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_zilu),
            "退出专注",
            stopPending(this)
        ).build()
        builder.addAction(stopAction)
        return builder.build()
    }

    companion object {
        const val NOTIFICATION_ID = 208
        const val ACTION_END = "com.zilu.app.action.FOCUS_END"
        const val ACTION_STOP = "com.zilu.app.action.FOCUS_STOP"
        private const val EXTRA_TASK_ID = "task"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_END_AT = "end"
        private const val END_REQUEST_CODE = 9101
        private const val STOP_REQUEST_CODE = 9102

        private fun endPending(context: Context, taskId: String): PendingIntent {
            val intent = Intent(context, FocusEndReceiver::class.java)
                .setAction(ACTION_END)
                .putExtra(EXTRA_TASK_ID, taskId)
            return PendingIntent.getBroadcast(
                context, END_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun stopPending(context: Context): PendingIntent {
            val intent = Intent(context, FocusEndReceiver::class.java).setAction(ACTION_STOP)
            return PendingIntent.getBroadcast(
                context, STOP_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun cancelEndAlarm(context: Context) {
            val intent = Intent(context, FocusEndReceiver::class.java).setAction(ACTION_END)
            val pending = PendingIntent.getBroadcast(
                context, END_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            Alarms.cancel(context, pending)
        }

        /** 开始（或继续）一次专注。剩余时间来自任务自身，支持「做了一半退出后再接着做」。 */
        fun start(context: Context, task: DailyTask): FocusSession {
            val seconds = task.remainingSeconds.coerceIn(1, task.durationSeconds)
            val start = System.currentTimeMillis()
            val session = FocusSession(
                taskId = task.id,
                title = task.title,
                startedAtMillis = start,
                endAtMillis = start + seconds * 1000L,
                durationSeconds = seconds,
                planMinutes = task.planMinutes
            )
            val intent = Intent(context, TaskTimerService::class.java)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_TITLE, task.title)
                .putExtra(EXTRA_END_AT, session.endAtMillis)
            // 先登记闹钟再拉起服务：即使服务启动瞬间失败，到点提醒依然有效
            Alarms.set(context, session.endAtMillis, endPending(context, task.id))
            ContextCompat.startForegroundService(context, intent)
            return session
        }

        /** 用户主动退出专注：只停计时，不改任务状态。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TaskTimerService::class.java)) }
            cancelEndAlarm(context)
        }
    }
}

/**
 * 到点收尾。可以安全地重复调用：只有第一次会真正生效。
 * 由三处触发：服务内的看门狗、系统闹钟 [FocusEndReceiver]、以及 App 在前台时的兜底检查。
 */
object FocusFinisher {
    fun finish(context: Context, taskId: String) {
        val repo = ZiluRepository(context)
        val state = repo.load()
        val focus = state.activeFocus ?: return
        if (focus.taskId != taskId) return

        repo.completeFocus(taskId)
        TaskTimerService.stop(context)

        Notifier.postHigh(
            context,
            Notifier.nextId(),
            Notifier.CH_DONE,
            "专注完成 · ${focus.title}",
            "本次专注 ${focus.planMinutes} 分钟，任务已完成",
            "「${focus.title}」专注完成，本次 ${focus.planMinutes} 分钟。休息一下吧。",
            vibrate = state.settings.completionVibrate
        )
        // 通知负责响铃，这里补一次主动振动，保证完成时有震动反馈
        SoundFeedback.onFocusFinished(context, state.settings)
    }
}

class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TaskTimerService.ACTION_STOP -> {
                // 通知栏上的「退出专注」：按剩余时间回写任务，让下次可以接着做
                val repo = ZiluRepository(context)
                val state = repo.load()
                val focus = state.activeFocus
                if (focus != null) {
                    val left = ((focus.endAtMillis - System.currentTimeMillis()) / 1000L).toInt()
                    val tasks = state.tasks.map {
                        if (it.id == focus.taskId) {
                            it.copy(remainingSeconds = if (left <= 0) it.durationSeconds else left.coerceAtMost(it.durationSeconds))
                        } else it
                    }
                    repo.save(state.copy(tasks = tasks, activeFocus = null))
                }
                TaskTimerService.stop(context)
            }

            else -> FocusFinisher.finish(context, intent.getStringExtra("task").orEmpty())
        }
    }
}
