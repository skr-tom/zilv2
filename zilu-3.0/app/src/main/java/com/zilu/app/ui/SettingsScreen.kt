package com.zilu.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zilu.app.AppSettings
import com.zilu.app.AppState
import com.zilu.app.NotificationDiagnostics
import com.zilu.app.Notifier
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: AppState, onSave: (AppSettings) -> Unit, context: Context) {
    var health by remember { mutableStateOf(NotificationDiagnostics.check(context)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ---------------------------------------------------------- 通知诊断
        SectionTitle("通知与提醒")

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (health.allGood) Color(0xFFE7F6EE) else Color(0xFFFFF0E6),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (health.allGood) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (health.allGood) DoneGreen else WarnAmber
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (health.allGood) "通知链路正常" else "通知可能收不到，请按下面逐项打开",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF3A2C08)
                    )
                }
                Text(
                    "设置好这几项，任务到点才会准时弹出来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5B5040)
                )
            }
        }

        HealthRow(
            ok = health.notificationsEnabled,
            title = "允许发送通知",
            detail = "关闭后所有提醒都不会显示",
            action = "去开启",
            onAction = { NotificationDiagnostics.openAppNotificationSettings(context) }
        )
        HealthRow(
            ok = !health.reminderChannelBlocked,
            title = "「任务截止提醒」渠道未被关闭",
            detail = "渠道被关闭时通知会静默丢弃",
            action = "去检查",
            onAction = { NotificationDiagnostics.openChannelSettings(context, Notifier.CH_REMINDER_LOUD) }
        )
        HealthRow(
            ok = !health.doneChannelBlocked,
            title = "「专注完成提醒」渠道未被关闭",
            detail = "专注倒计时结束的提醒依赖这个渠道",
            action = "去检查",
            onAction = { NotificationDiagnostics.openChannelSettings(context, Notifier.CH_DONE) }
        )
        HealthRow(
            ok = health.exactAlarmAllowed,
            title = "允许精确闹钟",
            detail = "否则省电模式下提醒会延迟",
            action = "去开启",
            onAction = { NotificationDiagnostics.openExactAlarmSettings(context) }
        )
        HealthRow(
            ok = health.batteryUnrestricted,
            title = "后台不被限制",
            detail = "把电池策略设为「不限制」，避免计时被冻结",
            action = "去设置",
            onAction = { NotificationDiagnostics.requestIgnoreBatteryOptimizations(context) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { health = NotificationDiagnostics.check(context) },
                modifier = Modifier.weight(1f)
            ) { Text("重新检测") }
            Button(
                onClick = { Notifier.postTest(context) },
                modifier = Modifier.weight(1f)
            ) { Text("发一条测试通知") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒时响铃", Modifier.weight(1f))
            Switch(
                checked = state.settings.reminderSound,
                onCheckedChange = { onSave(state.settings.copy(reminderSound = it)) }
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒时振动", Modifier.weight(1f))
            Switch(
                checked = state.settings.reminderVibrate,
                onCheckedChange = { onSave(state.settings.copy(reminderVibrate = it)) }
            )
        }
        Text(
            "小米 / 华为 / OPPO / vivo 还需要在系统设置里允许「自启动」和「后台弹出界面」，否则系统会拦截通知。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        // ---------------------------------------------------------- 外观
        Spacer(Modifier.height(4.dp))
        SectionTitle("外观")

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("深色模式", Modifier.weight(1f))
            Switch(
                checked = state.settings.darkMode,
                onCheckedChange = { onSave(state.settings.copy(darkMode = it)) }
            )
        }

        Text("字体大小 ${(state.settings.fontScale * 100).roundToInt()}%")
        Slider(
            value = state.settings.fontScale,
            onValueChange = { onSave(state.settings.copy(fontScale = it)) },
            valueRange = 0.85f..1.3f
        )

        Text("主题颜色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("琥珀金", "松石绿", "暮光紫").forEach { name ->
                FilterChip(
                    selected = state.settings.themeName == name,
                    onClick = { onSave(state.settings.copy(themeName = name)) },
                    label = { Text(name) }
                )
            }
        }

        // ---------------------------------------------------------- 关于
        Spacer(Modifier.height(4.dp))
        SectionTitle("关于")
        Text(
            "自律 4.0 · 每日任务 / 长期计划 / 日历 / 每日小记",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Button(onClick = { shareInstalledApp(context) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("分享给好友（微信 / QQ 等）")
        }
        TextButton(onClick = { health = NotificationDiagnostics.check(context) }) {
            Text("刷新通知状态")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun HealthRow(
    ok: Boolean,
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) DoneGreen else WarnAmber,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (!ok) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun shareInstalledApp(context: Context) {
    runCatching {
        val source = File(context.applicationInfo.sourceDir)
        val copy = File(context.cacheDir, "zilu.apk")
        source.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享自律"))
    }
}

/** 供 MainActivity 判断是否需要引导用户打开通知。 */
fun needsNotificationPermission(context: Context): Boolean = !Notifier.notificationsEnabled(context)
