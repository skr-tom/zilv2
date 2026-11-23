package com.zilu.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知相关的一切都集中在这里。
 *
 * 关键点（之前推送不生效的原因）：
 * 1. 通知栏小图标用的是系统资源 android.R.drawable.xxx，在多数国产 ROM / Android 8+ 上会被渲染成白块或直接不显示，
 *    现在改用自绘的白色沙漏矢量图 [R.drawable.ic_stat_zilu]，并配合大图标显示彩色 App logo。
 * 2. 渠道重要性之前是 LOW，通知会被折叠进通知栏底部且不弹横幅。现在提醒/完成都用 HIGH（响铃 + 振动 + 横幅 + 锁屏可见）。
 * 3. 渠道 ID 全部换成 v4 新 ID，老渠道被用户关掉后新建渠道不会继承「已关闭」状态。
 * 4. 精确闹钟改用 USE_EXACT_ALARM（Android 13+ 系统自动授予，无需用户去设置里手动开），
 *    避免 SCHEDULE_EXACT_ALARM 未授权时退化成不精确闹钟（Doze 下可能延迟十几分钟）。
 */
object Notifier {
    private const val CHANNEL_SUFFIX = "_v4"
    const val CH_REMINDER_LOUD = "zilu_reminder_loud$CHANNEL_SUFFIX"
    const val CH_REMINDER_QUIET = "zilu_reminder_quiet$CHANNEL_SUFFIX"
    const val CH_REMINDER_MUTE = "zilu_reminder_mute$CHANNEL_SUFFIX"
    const val CH_DONE = "zilu_done$CHANNEL_SUFFIX"
    const val CH_FOCUS = "zilu_focus$CHANNEL_SUFFIX"

    val ACCENT = 0xFFE0A028.toInt()

    private val idSeed = AtomicInteger((System.currentTimeMillis() % 100000).toInt())

    /** 让每条通知都有独立 ID，避免后一条把前一条覆盖掉。 */
    fun nextId(): Int = 1000 + idSeed.incrementAndGet()

    private fun alarmAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** 无参版本供 Activity / Service 直接调用，自动读取当前设置里的铃声。 */
    fun ensureChannels(context: Context) {
        val settings = runCatching { ZiluRepository(context).load().settings }.getOrDefault(AppSettings())
        ensureChannels(context, settings)
    }

    fun ensureChannels(context: Context, settings: AppSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // 清掉旧版本遗留的渠道，防止用户在设置里看到重复条目
        runCatching {
            manager.notificationChannels
                .filter { it.id.endsWith("_v2") }
                .forEach { manager.deleteNotificationChannel(it.id) }
        }

        val sound: Uri = SoundFeedback.notificationUri(settings.ringtoneUri)
        val loud = NotificationChannel(CH_REMINDER_LOUD, "任务截止提醒（响铃 + 振动）", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "任务到达截止时间时提醒你，会响铃、振动并弹出横幅"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 350L, 200L, 350L)
            enableLights(true)
            lightColor = ACCENT
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(sound, alarmAttributes())
        }
        // 用户关掉响铃但保留振动时走这条渠道
        val quiet = NotificationChannel(CH_REMINDER_QUIET, "任务截止提醒（仅振动）", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "任务到达截止时间时提醒你，不响铃但会振动"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 300L, 180L, 300L)
            setSound(null, null)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val mute = NotificationChannel(CH_REMINDER_MUTE, "任务截止提醒（静音）", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "任务到达截止时间时提醒你，不响铃也不振动"
            enableVibration(false)
            setSound(null, null)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val done = NotificationChannel(CH_DONE, "专注完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "专注倒计时结束时提醒你，按设置响铃并振动"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 250L, 150L, 250L)
            enableLights(true)
            lightColor = ACCENT
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(sound, alarmAttributes())
        }
        // 专注中的常驻通知：低重要性但常驻，系统会用计时器自动刷新倒计时
        val focus = NotificationChannel(CH_FOCUS, "专注倒计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "专注时在通知栏常驻显示倒计时"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        listOf(loud, quiet, mute, done, focus).forEach { manager.createNotificationChannel(it) }
    }

    /** 通知渠道创建后无法修改声音，换铃声时删掉旧的再重建一次。 */
    fun rebuildForRingtone(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            listOf(CH_REMINDER_LOUD, CH_REMINDER_QUIET, CH_REMINDER_MUTE, CH_DONE)
                .forEach { manager.deleteNotificationChannel(it) }
        }
        ensureChannels(context)
    }

    fun reminderChannel(settings: AppSettings): String = when {
        settings.reminderSound -> CH_REMINDER_LOUD
        settings.reminderVibrate -> CH_REMINDER_QUIET
        else -> CH_REMINDER_MUTE
    }

    fun notificationsEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    fun channelBlocked(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(channelId) ?: return true
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** App 图标（彩色），用作通知大图标，让通知栏右侧显示 logo。 */
    private var cachedIcon: Bitmap? = null
    fun appIcon(context: Context): Bitmap? {
        cachedIcon?.let { if (!it.isRecycled) return it }
        return runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeResource(context.resources, R.drawable.zilu_icon, options)
                ?.also { cachedIcon = it }
        }.getOrNull()
    }

    fun openApp(context: Context, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 高优先级通知：横幅 + 响铃/振动 + 锁屏可见。 */
    fun postHigh(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        bigText: String = text,
        vibrate: Boolean = true
    ) {
        if (!notificationsEnabled(context)) return
        ensureChannels(context)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_zilu)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setContentIntent(openApp(context, id))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setColor(ACCENT)
            .setTicker("$title · $text")
            .setOnlyAlertOnce(false)
            // Android 8+ 主要看渠道设置，但显式写一次能兼容旧版本和部分 ROM
            .setVibrate(if (vibrate) longArrayOf(0L, 300L, 180L, 300L) else null)
        appIcon(context)?.let { builder.setLargeIcon(it) }
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
        }
    }

    /** 设置页里的「测试通知」，用来快速验证通知链路、铃声和振动是否都通。 */
    fun postTest(context: Context) {
        val settings = runCatching { ZiluRepository(context).load().settings }.getOrDefault(AppSettings())
        postHigh(
            context,
            nextId(),
            CH_REMINDER_LOUD,
            "自律 · 通知测试",
            "看到这条通知，说明通知链路正常",
            "如果你能看到这条通知，说明通知权限、通知渠道都正常。到点没收到提醒时，请检查「自启动 / 后台运行 / 电池不限制」。",
            vibrate = settings.reminderVibrate
        )
        // 主动补一次振动：国产 ROM 经常忽略渠道里的 vibrationPattern
        if (settings.reminderVibrate) {
            SoundFeedback.vibrate(context, strong = true)
        }
    }
}

/** 精确闹钟封装。 */
object Alarms {
    fun canExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
        } else true

    fun set(context: Context, triggerAtMillis: Long, pending: PendingIntent) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            if (canExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                // 没有精确闹钟权限时退化为系统允许的最精确方式，至少不会静默丢失
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }.onFailure {
            runCatching { manager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending) }
        }
    }

    fun cancel(context: Context, pending: PendingIntent) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending) }
    }
}

/** 任务截止提醒的调度。 */
object ReminderScheduler {
    private const val EXTRA_ID = "taskId"

    fun parseTime(value: String?): LocalTime? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(':', '：')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun pending(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, taskId)
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(context: Context, taskId: String) = Alarms.cancel(context, pending(context, taskId))

    /** 计算下一次该响的时刻，并把闹钟排进去。 */
    fun schedule(context: Context, task: DailyTask) {
        cancel(context, task.id)
        val time = parseTime(task.reminderTime) ?: return
        val now = LocalDateTime.now()
        var trigger: LocalDateTime? = null
        for (offset in 0..15) {
            val candidate = LocalDateTime.of(LocalDate.now().plusDays(offset.toLong()), time)
            if (candidate.isAfter(now) && task.occursOn(candidate.toLocalDate())) {
                trigger = candidate
                break
            }
        }
        val at = trigger ?: return
        Alarms.set(context, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pending(context, task.id))
    }

    fun rescheduleAll(context: Context) {
        ZiluRepository(context).load().tasks.forEach { schedule(context, it) }
    }
}

/** 任务到达截止时间。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = ZiluRepository(context)
        val state = repo.load()
        val taskId = intent.getStringExtra("taskId").orEmpty()
        val task = state.tasks.firstOrNull { it.id == taskId } ?: return

        // 先把下一次排上，避免只提醒一次
        ReminderScheduler.schedule(context, task)

        val today = LocalDate.now()
        val done = state.completionLogs.any { it.taskId == task.id && it.date == today.toString() }
        val focusing = state.activeFocus?.taskId == task.id
        if (!task.occursOn(today) || done || focusing) return

        Notifier.postHigh(
            context,
            // 同一个任务固定用同一个通知 ID，避免每天堆一条
            "zilu-reminder-${task.id}".hashCode(),
            Notifier.reminderChannel(state.settings),
            "「${task.title}」截止时间到了",
            "还有 ${task.planMinutes} 分钟的专注没有完成，点开开始",
            "「${task.title}」的截止时间到了。计划专注 ${task.planMinutes} 分钟，现在还没完成，点开自律开始专注吧。",
            vibrate = state.settings.reminderVibrate
        )
        // 通知渠道的振动在部分 ROM 上会被吞掉，这里再主动振一次兜底
        SoundFeedback.onReminder(context, state.settings)
    }
}

/** 开机 / 应用更新 / 修改时间后重新排闹钟，否则重启手机后所有提醒都会消失。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { ReminderScheduler.rescheduleAll(context) }
    }
}

/** 设置页用来展示通知链路是否健康。 */
data class NotificationHealth(
    val notificationsEnabled: Boolean,
    val reminderChannelBlocked: Boolean,
    val doneChannelBlocked: Boolean,
    val exactAlarmAllowed: Boolean,
    val batteryUnrestricted: Boolean
) {
    val allGood: Boolean
        get() = notificationsEnabled && !reminderChannelBlocked && !doneChannelBlocked && exactAlarmAllowed && batteryUnrestricted
}

object NotificationDiagnostics {
    fun check(context: Context): NotificationHealth {
        val power = context.getSystemService(PowerManager::class.java)
        val ignoringBattery = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        return NotificationHealth(
            notificationsEnabled = Notifier.notificationsEnabled(context),
            reminderChannelBlocked = Notifier.channelBlocked(context, Notifier.CH_REMINDER_LOUD),
            doneChannelBlocked = Notifier.channelBlocked(context, Notifier.CH_DONE),
            exactAlarmAllowed = Alarms.canExact(context),
            batteryUnrestricted = ignoringBattery
        )
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return openAppNotificationSettings(context)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { openAppNotificationSettings(context) }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
    }
}
