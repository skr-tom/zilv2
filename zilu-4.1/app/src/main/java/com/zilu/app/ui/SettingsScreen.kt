package com.zilu.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.zilu.app.AppUpdater
import com.zilu.app.DEFAULT_UPDATE_URL
import com.zilu.app.NotificationDiagnostics
import com.zilu.app.Notifier
import com.zilu.app.SoundFeedback
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: AppState,
    onSave: (AppSettings) -> Unit,
    context: Context,
    onCheckUpdate: () -> Unit
) {
    var health by remember { mutableStateOf(NotificationDiagnostics.check(context)) }
    var showTestConfirm by remember { mutableStateOf(false) }

    // 直接拉起系统自带的「经典铃声」列表，用户选完把 URI 存进设置
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = SoundFeedback.pickedUri(result.data)
            // 尽量把自定义铃声的读权限长期保留下来，避免重启后铃声失效
            picked?.let { raw ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onSave(state.settings.copy(ringtoneUri = picked))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ------------------------------------------------ 通知自检（确认收到后永久收起）
        if (!state.settings.notificationVerified) {
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
                    onClick = {
                        Notifier.postTest(context)
                        showTestConfirm = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("发一条测试通知") }
            }
        }

        // ---------------------------------------------------------- 铃声与振动
        SectionTitle("铃声与振动")

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    ringtoneLauncher.launch(SoundFeedback.pickerIntent(context, state.settings.ringtoneUri))
                }
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("提醒铃声", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        SoundFeedback.displayName(context, state.settings.ringtoneUri),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text("选择 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            "点上面一行会打开手机自带的铃声列表，选好的铃声用于到点提醒、专注完成和完成反馈。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        SettingSwitch("提醒时响铃", state.settings.reminderSound) {
            onSave(state.settings.copy(reminderSound = it))
        }
        SettingSwitch("提醒时振动", state.settings.reminderVibrate) {
            onSave(state.settings.copy(reminderVibrate = it))
        }
        SettingSwitch("完成任务时响铃", state.settings.completionSound) {
            onSave(state.settings.copy(completionSound = it))
        }
        SettingSwitch("完成任务时振动", state.settings.completionVibrate) {
            onSave(state.settings.copy(completionVibrate = it))
        }
        Text(
            "小米 / 华为 / OPPO / vivo 还需要在系统设置里允许「自启动」和「后台弹出界面」，否则系统会拦截通知。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        // ---------------------------------------------------------- 外观
        Spacer(Modifier.height(4.dp))
        SectionTitle("外观")

        SettingSwitch("深色模式", state.settings.darkMode) {
            onSave(state.settings.copy(darkMode = it))
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

        // ---------------------------------------------------------- 版本更新
        Spacer(Modifier.height(4.dp))
        SectionTitle("版本更新")

        val localVersion = AppUpdater.localVersion(context)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "当前版本  ${localVersion.second}  (${localVersion.first})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "发现新版本后直接在 App 内下载并覆盖安装，不用卸载，任务 / 计划 / 小记都会保留",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingSwitch("启动时自动检查更新", state.settings.autoCheckUpdate) {
            onSave(state.settings.copy(autoCheckUpdate = it))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheckUpdate, modifier = Modifier.weight(1f)) { Text("检查更新") }
            OutlinedButton(
                onClick = { onSave(state.settings.copy(updateUrl = DEFAULT_UPDATE_URL)) },
                modifier = Modifier.weight(1f)
            ) { Text("恢复默认地址") }
        }

        // 测试 OTA 时容易误点「跳过此版本」，这里给个复位入口，方便反复验证
        if (state.settings.skippedVersionCode > 0) {
            TextButton(onClick = { onSave(state.settings.copy(skippedVersionCode = 0)) }) {
                Text("清除已忽略的版本（当前忽略 ${state.settings.skippedVersionCode}）")
            }
        }

        var urlDraft by remember(state.settings.updateUrl) { mutableStateOf(state.settings.updateUrl) }
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            label = { Text("更新地址（版本清单 JSON）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text("默认指向仓库里的 update.json；也可以填自己的服务器地址或 GitHub Releases API")
            }
        )
        if (urlDraft.trim() != state.settings.updateUrl) {
            Button(
                onClick = { onSave(state.settings.copy(updateUrl = urlDraft.trim())) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存更新地址") }
        }

        if (!AppUpdater.canInstall(context)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "还没允许「安装未知应用」，更新时会先引导你开一次",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber
                )
                TextButton(onClick = { AppUpdater.openInstallSettings(context) }) { Text("去开启") }
            }
        }
        Text(
            "发布新版本时，把新 APK 和 update.json 传到上面的地址即可，App 会自动提示用户更新。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        // ---------------------------------------------------------- 关于
        Spacer(Modifier.height(4.dp))
        SectionTitle("关于")
        Text(
            "自律 ${localVersion.second} · 每日任务 / 长期计划 / 日历 / 每日小记",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Button(onClick = { shareInstalledApp(context) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("分享给好友（微信 / QQ 等）")
        }
        // 万一以后通知又收不到了，这里可以把自检面板调回来
        TextButton(onClick = {
            health = NotificationDiagnostics.check(context)
            if (state.settings.notificationVerified) {
                onSave(state.settings.copy(notificationVerified = false))
            }
        }) { Text("重新检测通知链路") }
    }

    if (showTestConfirm) {
        AlertDialog(
            onDismissRequest = { showTestConfirm = false },
            title = { Text("收到测试通知了吗？") },
            text = {
                Text(
                    "通知栏出现「自律 · 通知测试」，并且手机有响铃 / 振动，就说明链路通了。" +
                        "点「收到了」，以后不再显示上面那块自检面板；没收到就保留，方便继续排查。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(state.settings.copy(notificationVerified = true))
                    showTestConfirm = false
                }) { Text("收到了，收起面板") }
            },
            dismissButton = {
                TextButton(onClick = {
                    health = NotificationDiagnostics.check(context)
                    showTestConfirm = false
                }) { Text("没收到，保留自检") }
            }
        )
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
