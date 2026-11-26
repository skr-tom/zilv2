package com.zilu.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * 铃声与振动反馈。
 *
 * 为什么单独抽一层：Android 8+ 之后通知的声音/振动由「通知渠道」决定，
 * 渠道一旦创建就不能再改（重建同名渠道还可能被系统恢复成用户之前关掉的状态），
 * 而不少国产 ROM 会直接忽略渠道里的 vibrationPattern —— 这就是之前「到点不震动」的原因。
 * 所以这里做双保险：
 *   1. 通知渠道仍然配置声音 + 振动（负责后台、锁屏、横幅场景）；
 *   2. 提醒/完成时再主动调用系统 Vibrator 振一次，绕开 ROM 对渠道振动的拦截；
 *   3. 用户在 App 内点击完成时，直接播放自选铃声 + 振动（此时不发通知）。
 */
object SoundFeedback {

    /** 打开系统自带的铃声选择页（图 1 那种「经典铃声」列表），选完把 URI 交回 App。 */
    fun pickerIntent(context: Context, current: String?): Intent {
        val existing = parseUri(current)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
            // 不显示「无」：静音统一由设置里的「提醒时响铃 / 完成任务时响铃」开关控制，
            // 避免和「跟随系统默认」混在一起产生歧义
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
        }
    }

    /** 从 ActivityResult 里取出用户选中的铃声；选「无」时返回 null。 */
    fun pickedUri(result: Intent?): String? {
        if (result == null) return null
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val uri = result.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        return uri?.toString()
    }

    fun parseUri(value: String?): Uri? =
        if (value.isNullOrBlank()) null else runCatching { Uri.parse(value) }.getOrNull()

    /** 通知渠道用的声音：用户自选优先，否则系统默认通知音。 */
    fun notificationUri(custom: String?): Uri =
        parseUri(custom)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI

    /** 设置页里显示当前铃声的名字。 */
    fun displayName(context: Context, custom: String?): String {
        val uri = parseUri(custom) ?: return "系统默认"
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "自定义铃声"
        }.getOrDefault("自定义铃声")
    }

    private fun tryPlay(context: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        return runCatching {
            val tone = RingtoneManager.getRingtone(context, uri) ?: return@runCatching false
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            tone.play()
            true
        }.getOrDefault(false)
    }

    /** 播放一次提示音（用于 App 内点击完成）；自定义 URI 读不到时自动回退系统默认音。 */
    fun play(context: Context, settings: AppSettings) {
        if (!settings.completionSound && !settings.reminderSound) return
        val custom = parseUri(settings.ringtoneUri)
        if (custom != null && tryPlay(context, custom)) return
        tryPlay(context, notificationUri(null))
    }

    /** 主动振动一次，绕开 ROM 对通知渠道振动的拦截。 */
    fun vibrate(context: Context, strong: Boolean = false) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() != true) return@runCatching
            val pattern = if (strong) longArrayOf(0L, 320L, 180L, 320L, 180L, 320L)
            else longArrayOf(0L, 240L, 140L, 240L)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    /** 用户在任务 / 计划 / 日历里勾选「完成」时的反馈：铃声 + 振动。 */
    fun celebrate(context: Context, settings: AppSettings) {
        if (settings.completionSound) play(context, settings)
        if (settings.completionVibrate) vibrate(context)
    }

    /** 任务到达截止时间的反馈：通知负责声音，这里兜底补一次振动。 */
    fun onReminder(context: Context, settings: AppSettings) {
        if (settings.reminderVibrate) vibrate(context, strong = true)
    }

    /** 专注完成的反馈：通知负责声音，这里兜底补一次振动。 */
    fun onFocusFinished(context: Context, settings: AppSettings) {
        if (settings.completionVibrate) vibrate(context, strong = true)
    }
}
