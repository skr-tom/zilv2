package com.zilu.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 下载完成 / 安装完成后的收尾。静态注册，所以用户切出 App、甚至锁屏，下载完也能收到提醒。
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 新版本已经装上了：清掉待安装记录，撤掉「已下载」通知
                AppUpdater.clearPending(context)
                AppUpdater.cancelNotification(context)
            }

            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id < 0 || id != AppUpdater.pendingId(context)) return

                val file = AppUpdater.readyApk(context)
                val status = AppUpdater.progress(context, id)
                if (file == null || status < 1f) {
                    AppUpdater.clearPending(context)
                    AppUpdater.postFailed(context)
                    return
                }
                AppUpdater.postReady(context, AppUpdater.pendingName(context), file)
            }
        }
    }
}
