package com.zilu.app

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 一次可用更新。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val force: Boolean = false,
    val sizeBytes: Long = 0L
)

/**
 * 应用内更新（OTA）。
 *
 * 流程：检查 update.json → 比对 versionCode → DownloadManager 后台下载 →
 * 下载完成广播发通知 → 点通知或 App 内按钮拉起系统安装界面 → 覆盖安装。
 *
 * 「不用卸载」的两个硬条件：
 * 1. 新旧 APK **签名一致**（同一个 keystore）；
 * 2. versionCode 递增。
 * 业务数据存在 SharedPreferences("zilu_v4")，属于应用私有目录，覆盖安装会原样保留。
 */
object AppUpdater {
    const val DEFAULT_URL = DEFAULT_UPDATE_URL

    private const val PREFS = "zilu_update"
    private const val KEY_PENDING_ID = "pending_id"
    private const val KEY_PENDING_FILE = "pending_file"
    private const val KEY_PENDING_CODE = "pending_code"
    private const val KEY_PENDING_NAME = "pending_name"
    private const val KEY_LAST_CHECK = "last_check"

    private const val CHANNEL = "zilu_update_v1"
    private const val NOTIFICATION_ID = 9001
    private const val AUTO_CHECK_INTERVAL = 12 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 版本

    /** 本地 versionCode / versionName。不依赖 BuildConfig（AGP 8 默认不生成）。 */
    @Suppress("DEPRECATION")
    fun localVersion(context: Context): Pair<Long, String> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        code to (info.versionName ?: "-")
    }.getOrDefault(0L to "-")

    /** 拉取并解析更新信息；null 表示「没有可用更新」或「配置不完整」。异常由调用方处理。 */
    suspend fun check(context: Context, url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("版本清单返回 HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 支持两种数据源：
     * A. 自建 update.json：{ versionCode, versionName, apkUrl, changelog, force, sizeBytes }
     * B. GitHub Releases latest API：tag_name / body / assets[].browser_download_url，
     *    versionCode 可在 Release 说明里写一行 `versionCode: 6`，否则按 tag 里的数字推算。
     */
    private fun parse(o: JSONObject): UpdateInfo? {
        if (o.has("versionCode") && o.has("apkUrl")) {
            val info = UpdateInfo(
                versionCode = o.optInt("versionCode"),
                versionName = o.optString("versionName", "新版本"),
                apkUrl = o.optString("apkUrl"),
                changelog = o.optString("changelog", "修复若干问题，优化体验"),
                force = o.optBoolean("force", false),
                sizeBytes = o.optLong("sizeBytes", 0L)
            )
            return info.takeIf { it.versionCode > 0 && it.apkUrl.startsWith("http") }
        }

        val assets = o.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var size = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                size = asset.optLong("size", 0L)
                break
            }
        }
        val url = apkUrl?.takeIf { it.startsWith("http") } ?: return null
        val tag = o.optString("tag_name", "")
        val body = o.optString("body", "")
        val explicit = Regex("versionCode\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.toIntOrNull()
        val numbers = Regex("\\d+").findAll(tag.removePrefix("v"))
            .mapNotNull { it.value.toIntOrNull() }.toList()
        val code = explicit ?: when {
            numbers.size >= 3 -> numbers[0] * 10_000 + numbers[1] * 100 + numbers[2]
            numbers.size == 2 -> numbers[0] * 10_000 + numbers[1] * 100
            numbers.size == 1 -> numbers[0]
            else -> 0
        }
        if (code <= 0) return null
        return UpdateInfo(code, tag.ifBlank { "新版本" }, url, body.ifBlank { "发布新版本" }, false, size)
    }

    // ------------------------------------------------------------------ 节流

    fun shouldAutoCheck(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) > AUTO_CHECK_INTERVAL

    fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    // ------------------------------------------------------------------ 下载

    private fun fileName(info: UpdateInfo): String =
        "zilu-${info.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")}.apk"

    /** APK 放在应用私有外部目录，不需要存储权限，FileProvider 也能直接暴露。 */
    fun apkFile(context: Context, name: String): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)

    fun startDownload(context: Context, info: UpdateInfo): Long {
        val name = fileName(info)
        runCatching { apkFile(context, name).delete() }
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("自律 ${info.versionName} 更新")
            setDescription("正在下载新版本，完成后会提示安装")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        prefs(context).edit()
            .putLong(KEY_PENDING_ID, id)
            .putString(KEY_PENDING_FILE, name)
            .putInt(KEY_PENDING_CODE, info.versionCode)
            .putString(KEY_PENDING_NAME, info.versionName)
            .apply()
        return id
    }

    /** 返回 0f..1f；-1f = 进度未知；-2f = 下载失败；1f = 已完成。 */
    fun progress(context: Context, id: Long): Float {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))
        }.getOrNull() ?: return -1f
        var result = -1f
        cursor.use { c ->
            result = if (!c.moveToFirst()) {
                -1f
            } else {
                val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIndex >= 0) c.getInt(statusIndex) else -1
                when {
                    status == DownloadManager.STATUS_SUCCESSFUL -> 1f
                    status == DownloadManager.STATUS_FAILED -> -2f
                    else -> {
                        val totalIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val doneIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val total = if (totalIndex >= 0) c.getLong(totalIndex) else -1L
                        val done = if (doneIndex >= 0) c.getLong(doneIndex) else -1L
                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 0.99f) else -1f
                    }
                }
            }
        }
        return result
    }

    fun pendingId(context: Context): Long = prefs(context).getLong(KEY_PENDING_ID, -1L)
    fun pendingFile(context: Context): String = prefs(context).getString(KEY_PENDING_FILE, "") ?: ""
    fun pendingName(context: Context): String = prefs(context).getString(KEY_PENDING_NAME, "") ?: ""
    fun pendingCode(context: Context): Int = prefs(context).getInt(KEY_PENDING_CODE, 0)

    fun clearPending(context: Context) {
        prefs(context).edit()
            .remove(KEY_PENDING_ID)
            .remove(KEY_PENDING_FILE)
            .remove(KEY_PENDING_CODE)
            .remove(KEY_PENDING_NAME)
            .apply()
    }

    /** 已经下载好、还没安装的 APK（用于 App 启动时补一个「立即安装」入口）。 */
    fun readyApk(context: Context): File? {
        val name = pendingFile(context)
        if (name.isBlank()) return null
        val file = apkFile(context, name)
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    // ------------------------------------------------------------ 安装与权限

    /** Android 8+ 安装 APK 需要「安装未知应用」授权。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 ||
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun openInstallSettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun apkUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** 各厂商包安装器包名不同，显式给常见的几个都授一次 URI 读权限。 */
    private fun grantInstaller(context: Context, uri: Uri) {
        listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller"
        ).forEach { pkg ->
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** 拉起系统安装界面（覆盖安装，数据保留）。返回是否成功发出 Intent。 */
    fun install(context: Context, file: File): Boolean = runCatching {
        if (!file.exists()) return false
        val uri = apkUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        grantInstaller(context, uri)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 通知

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "新版本下载完成后提醒你安装"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun baseBuilder(context: Context, title: String, text: String) =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zilu)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setColor(Notifier.ACCENT)
            .apply { Notifier.appIcon(context)?.let { setLargeIcon(it) } }

    /** 下载完成：点击通知直接进安装界面，不用再回 App 里找。 */
    fun postReady(context: Context, versionName: String, file: File) {
        runCatching {
            ensureChannel(context)
            val uri = apkUri(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // PendingIntent 用 IMMUTABLE，显式授权给各厂商包安装器更稳
            grantInstaller(context, uri)
            val pending = PendingIntent.getActivity(
                context, 77, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = baseBuilder(
                context,
                "自律 $versionName 已下载完成",
                "点这里安装新版本，任务 / 计划 / 小记都会保留"
            ).setContentIntent(pending).build()
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    fun postFailed(context: Context) {
        ensureChannel(context)
        val notification = baseBuilder(
            context,
            "更新下载失败",
            "点开自律，在设置 → 版本更新里重试"
        ).setContentIntent(Notifier.openApp(context, 78)).build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancelNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
