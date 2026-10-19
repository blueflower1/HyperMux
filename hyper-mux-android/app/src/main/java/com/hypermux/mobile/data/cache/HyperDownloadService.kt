package com.hypermux.mobile.data.cache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.hypermux.mobile.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 离线下载服务（Media3 [DownloadService] 子类），**前台服务**运行。
 *
 * 为什么必须前台：普通后台 Service 在 App 退后台后会被系统（尤其 OEM 省电策略）回收，
 * 进程一旦被冻/杀，下载任务就**静默停住**，而 DownloadManager 仍把状态记为"下载中" ——
 * 用户在页面上看到的就是"进度不动、没有任何提示"。前台服务 + 系统通知解决这个问题：
 * - 下载期间进程/服务不被后台策略回收，切后台、锁屏都能持续跑；
 * - 系统通知栏实时可见进度（或"等待条件"提示），点通知回到 App。
 *
 * 基类 [ForegroundNotificationUpdater] 每秒按 [getForegroundNotification] 刷新并负责
 * 以 `dataSync` 类型调 startForeground —— manifest 需声明 FOREGROUND_SERVICE /
 * FOREGROUND_SERVICE_DATA_SYNC 权限与本 service 的 `foregroundServiceType="dataSync"`。
 * 入队/继续由 [MediaCacheManager] 以 foreground=true 触发；无活动任务时
 * `MediaCacheManager.syncState()` 会 stopService（系统自动撤掉通知）。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class HyperDownloadService : DownloadService(9001) {

    @Inject
    lateinit var cacheManager: MediaCacheManager

    override fun getDownloadManager(): DownloadManager = cacheManager.downloadManager

    /**
     * 不接系统 JobScheduler：我们的下载只需要"App 在前台时尽早跑完"，
     * 引入 PlatformScheduler 还要多注册一个 JobService + 申请权限，收益不大。
     */
    override fun getScheduler(): Scheduler = NoOpScheduler

    /**
     * 前台通知内容（基类每秒刷新一次）：
     * - `notMetRequirements` 非 0（仅 Wi-Fi 下移动网络 / 断网）→ 明示"暂停 + 会自动恢复"，
     *   这是用户最该要的提示——不在 App 里也能看到下载为什么停了；
     * - 有活动任务 → 数量 + 平均进度；
     * - 全部完成 → 短暂显示完成，随后 [MediaCacheManager.syncState] 停掉服务、系统撤通知。
     */
    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        ensureChannel()
        val active = downloads.filter {
            it.state == Download.STATE_DOWNLOADING ||
                it.state == Download.STATE_QUEUED ||
                it.state == Download.STATE_RESTARTING
        }
        val text = when {
            notMetRequirements != 0 -> "下载已暂停 · 条件满足后自动继续"
            active.isEmpty() -> "下载完成"
            else -> {
                val avg = (active.sumOf { it.percentDownloaded.toDouble() } / active.size).toInt()
                "正在下载 · ${active.size} 个任务 · 平均 $avg%"
            }
        }
        val intent = Intent().setClassName(packageName, "com.hypermux.mobile.ui.MainActivity")
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 划掉最近任务不清掉下载：继续在后台把当前队列跑完 */
    override fun onTaskRemoved(rootIntent: Intent) {
        // 队列为空时主动停掉服务，不做无意义的常驻
        if (cacheManager.downloadManager.currentDownloads.isEmpty()) {
            stopSelf()
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "媒体缓存", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "hypermux_downloads"

        /** 空实现调度器：不下发系统级调度任务（见 [getScheduler] 注释） */
        object NoOpScheduler : Scheduler {
            override fun schedule(
                requirements: Requirements,
                servicePackage: String,
                serviceAction: String
            ): Boolean = false

            override fun cancel(): Boolean = false

            override fun getSupportedRequirements(
                requirements: Requirements
            ): Requirements = requirements
        }
    }
}
