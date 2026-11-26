package com.zilu.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zilu.app.AppSettings
import com.zilu.app.AppUpdater
import com.zilu.app.UpdateInfo
import kotlinx.coroutines.delay
import java.io.File

/**
 * 应用内更新的全部界面状态都收在这里，主页只挂一个 [UpdateHost]。
 * 设置页的「检查更新」按钮通过 checkTrigger 触发手动检查。
 */
@Composable
fun UpdateHost(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    checkTrigger: Int
) {
    val context = LocalContext.current
    val latestSettings by rememberUpdatedState(settings)
    val localCode = remember { AppUpdater.localVersion(context).first.toInt() }

    var checking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(-1f) }
    var downloadName by remember { mutableStateOf("") }
    var readyFile by remember { mutableStateOf<File?>(null) }
    var needInstallPermission by remember { mutableStateOf(false) }
    /** 因缺少安装权限而暂缓的动作，授权回来后自动继续。 */
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** 上次下载好却没装的包，重新打开 App 时补一个安装入口。 */
    LaunchedEffect(Unit) {
        val pending = AppUpdater.readyApk(context)
        if (pending != null && AppUpdater.pendingCode(context) > localCode) {
            readyFile = pending
        }
    }

    suspend fun performCheck(force: Boolean) {
        // 自动检查保持静默，只有用户手动点「检查更新」才显示加载框
        if (force) checking = true
        val result = runCatching { AppUpdater.check(context, latestSettings.updateUrl) }
        checking = false
        AppUpdater.markChecked(context)
        val found = result.getOrNull()
        when {
            found == null ->
                if (force) message = if (result.isFailure)
                    "检查失败：请确认手机能上网、更新地址填写正确，且地址返回的是 JSON"
                else "当前已是最新版本"

            found.versionCode <= localCode ->
                if (force) message = "当前已是最新版本"

            !force && found.versionCode <= latestSettings.skippedVersionCode -> Unit

            else -> info = found
        }
    }

    // 自动检查：12 小时一次，静默（没有更新不打扰）
    LaunchedEffect(Unit) {
        if (latestSettings.autoCheckUpdate && AppUpdater.shouldAutoCheck(context)) {
            performCheck(force = false)
        }
    }

    // 手动检查：设置页按钮点一次，trigger +1
    LaunchedEffect(checkTrigger) {
        if (checkTrigger > 0) performCheck(force = true)
    }

    // 下载进度轮询；DownloadManager 负责真正的后台下载，切出 App 也不会中断
    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        var last = -1f
        while (true) {
            last = AppUpdater.progress(context, id)
            downloadProgress = last
            if (last >= 1f || last <= -2f) break
            delay(500)
        }
        if (last >= 1f) {
            val file = AppUpdater.readyApk(context)
            if (file != null) {
                readyFile = file
                AppUpdater.postReady(context, downloadName, file)
            } else {
                message = "下载完成但没找到安装包，请重试"
            }
        } else {
            AppUpdater.clearPending(context)
            message = "下载失败，请检查网络后重试"
        }
        downloadId = null
    }

    /** 需要「安装未知应用」权限的动作先挂起，授权回来后自动继续，不打断用户流程。 */
    fun withInstallPermission(action: () -> Unit) {
        if (AppUpdater.canInstall(context)) {
            action()
        } else {
            afterPermission = action
            needInstallPermission = true
        }
    }

    fun beginInstall(file: File) = withInstallPermission {
        if (AppUpdater.install(context, file)) {
            readyFile = null
        } else {
            message = "没能打开安装界面，请在文件管理器里找到下载的 APK 手动安装"
        }
    }

    // 从「允许安装未知应用」设置页返回后，自动继续刚才被挂起的动作
    val installSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (AppUpdater.canInstall(context)) {
            afterPermission?.invoke()
        }
        afterPermission = null
    }

    // ------------------------------------------------------------------ 对话框

    if (checking) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(14.dp))
                    Text("正在获取版本信息…")
                }
            },
            confirmButton = {}
        )
    }

    info?.let { update ->
        AlertDialog(
            onDismissRequest = { if (!update.force) info = null },
            title = { Text("发现新版本 ${update.versionName}") },
            text = {
                Column {
                    Text("当前版本：${AppUpdater.localVersion(context).second}")
                    if (update.sizeBytes > 0) {
                        Text("安装包大小：${"%.1f".format(update.sizeBytes / 1024f / 1024f)} MB")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("更新内容")
                    Text(update.changelog)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "点「立即更新」会在后台下载并弹出系统安装界面。这是覆盖安装，不需要卸载，任务、计划、小记都会保留。",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    info = null
                    withInstallPermission {
                        downloadName = update.versionName
                        downloadProgress = 0f
                        downloadId = AppUpdater.startDownload(context, update)
                    }
                }) { Text("立即更新") }
            },
            dismissButton = {
                if (!update.force) {
                    Row {
                        TextButton(onClick = {
                            onSave(latestSettings.copy(skippedVersionCode = update.versionCode))
                            info = null
                        }) { Text("跳过此版本") }
                        TextButton(onClick = { info = null }) { Text("稍后") }
                    }
                }
            }
        )
    }

    if (downloadId != null) {
        val percent = (downloadProgress.coerceAtLeast(0f) * 100).toInt()
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 ${downloadName}") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceAtLeast(0f) },
                        modifier = Modifier.height(6.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (downloadProgress < 0f) "正在连接…" else "$percent%")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "由系统下载管理器在后台进行，切到桌面也不会中断，完成后会通知你安装。",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {}
        )
    }

    readyFile?.let { file ->
        AlertDialog(
            onDismissRequest = { readyFile = null },
            title = { Text("新版本已下载完成") },
            text = { Text("点「立即安装」进入系统安装界面，确认后即可覆盖安装，数据不会丢失。") },
            confirmButton = {
                TextButton(onClick = { beginInstall(file) }) { Text("立即安装") }
            },
            dismissButton = {
                TextButton(onClick = { readyFile = null }) { Text("稍后") }
            }
        )
    }

    if (needInstallPermission) {
        AlertDialog(
            onDismissRequest = { needInstallPermission = false },
            title = { Text("需要允许安装未知应用") },
            text = {
                Text(
                    "Android 要求先给「自律」打开「安装未知应用」权限，才能从 App 内更新。" +
                        "请在接下来的系统页面里打开开关，然后返回，更新会自动继续。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    needInstallPermission = false
                    installSettingsLauncher.launch(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { needInstallPermission = false }) { Text("取消") }
            }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("版本更新") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }
        )
    }
}
