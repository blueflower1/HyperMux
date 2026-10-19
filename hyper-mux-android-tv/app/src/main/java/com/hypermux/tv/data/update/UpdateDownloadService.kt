package com.hypermux.tv.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hypermux.tv.R
import com.hypermux.tv.data.api.toApiErr
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 更新包下载服务（前台服务，模式对齐 [com.hypermux.tv.data.cache.HyperDownloadService]）：
 * - 前台运行 + 系统通知展示进度：切后台 / 锁屏不被回收，避免"下载悄悄停住"；
 * - 边下边算 SHA-256（流式），校验通过才把临时文件改名为最终包，坏包不留盘；
 * - 复用 App 的 OkHttp：占位 host 由 ServerUrlInterceptor 重写为用户配置的服务器，
 *   与 Retrofit 请求同一来源（读到额外放宽，适配大包慢网）。
 * MVP 无断点续传：失败后从「立即更新」重新下整包。
 */
@AndroidEntryPoint
class UpdateDownloadService : Service() {

    @Inject
    lateinit var http: OkHttpClient

    @Inject
    lateinit var state: UpdateDownloadState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本实例是否已有下载在跑（实例级防重入；不能读共享 down 状态，见 [onStartCommand] 注释） */
    private var running = false

    /** 纯 startService 使用，不暴露 Binder */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FGS 契约：startForegroundService 后 ~5s 内必须调 startForeground，
        // 否则系统杀进程（ForegroundServiceDidNotStartInTimeException → FATAL）。
        // 部分 ROM 上从 start 调用到 onStartCommand 实际投递的延迟可观，
        // 所以先用前两行声明前台态，把致命窗口压到最小。
        // 防重入用实例级 [running]，不读共享 down 状态：VM 在 start 前已把共享状态
        // 置为 Loading，这里若读 it 会「永远在跑」而自停（真机踩过的坑）。
        state.markServiceStarted()
        startForegroundNow("正在下载更新包…", 0f)
        if (intent == null || intent.getStringExtra(EX_URL).isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent.getIntExtra(EX_VERSION_CODE, -1)
        if (code < 0 || running) {
            // 参数缺失，或本实例已有下载在跑（防重入）
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        val versionName = intent.getStringExtra(EX_VERSION_NAME).orEmpty()
        runDownload(
            url = intent.getStringExtra(EX_URL).orEmpty(),
            sha256 = intent.getStringExtra(EX_SHA256).orEmpty(),
            versionCode = code,
            versionName = versionName,
            expectSize = intent.getLongExtra(EX_SIZE, -1L)
        )
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, sha256: String, versionCode: Int, versionName: String, expectSize: Long) {
        scope.launch {
            val dir = File(filesDir, DIR_APKS).apply { mkdirs() }
            val tmp = File(dir, "hypermux-update-$versionCode.tmp.apk")
            val final = File(dir, "hypermux-update-$versionCode.apk")
            tmp.delete()
            val client = http.newBuilder()
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        finishFailed("下载失败：HTTP ${resp.code}")
                        return@use
                    }
                    val body = resp.body
                    if (body == null) {
                        finishFailed("服务器返回内容为空")
                        return@use
                    }
                    val total = if (expectSize > 0) expectSize else body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var lastTick = 0L
                    val buffer = ByteArray(64 * 1024)
                    state.loading(0, total)
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                received += n
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastTick >= 500) {
                                    lastTick = now
                                    state.loading(received, total)
                                    val pct = if (total > 0) (received.toDouble() / total).toFloat() else 0f
                                    tickNotif("正在下载更新 v$versionName", pct)
                                }
                            }
                        }
                    }
                    state.verifying()
                    tickNotif("下载完成，正在校验文件完整性…", 1f)
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(sha256, ignoreCase = true)) {
                        tmp.delete()
                        finishFailed("文件完整性校验失败，请点「立即更新」重新下载")
                        return@use
                    }
                    if (!tmp.renameTo(final)) {
                        tmp.delete()
                        finishFailed("安装包写入失败，请重试")
                        return@use
                    }
                    state.ready(final, versionCode, versionName)
                    tickNotif("更新包已就绪，回到 App 即可安装", 1f)
                    // 让完成通知可见几秒再停服务（立即 stop 会抢在通知落屏前撤掉）
                    scope.launch {
                        delay(5_000)
                        stopSelf()
                    }
                }
            } catch (e: IOException) {
                tmp.delete()
                finishFailed(e.toApiErr().msg)
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "更新下载异常：${e.javaClass.simpleName} ${e.message}")
                finishFailed("更新下载失败，请稍后重试")
            }
        }
    }

    private fun finishFailed(msg: String) {
        state.failed(msg)
        tickNotif(msg, 0f)
        stopSelf()
    }

    /* ---------------- 通知 ---------------- */

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun tickNotif(text: String, percent: Float) {
        // 进度刷新在 IO 线程，通知更新回主线程，避开 OEM 对后台线程 updateForeground 的差异
        mainHandler.post { startForegroundNow(text, percent) }
    }

    /**
     * 统一走 [ServiceCompat.startForeground] 并显式带 dataSync 类型：
     * manifest 声明了 foregroundServiceType 时，Android 14+ 用不带类型的
     * startForeground 会抛 MissingForegroundServiceTypeException。
     */
    private fun startForegroundNow(text: String, percent: Float) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotif(text, percent),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onDestroy() {
        running = false
        state.markServiceStopped()
        super.onDestroy()
    }

    private fun buildNotif(text: String, percent: Float): Notification {
        ensureChannel()
        val intent = Intent().setClassName(packageName, "com.hypermux.tv.ui.MainActivity")
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pctText = if (percent > 0f && percent < 1f) " · ${(percent * 100).toInt()}%" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$text$pctText")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (percent * 100).toInt(), percent <= 0f)
            .build()
    }

    companion object {
        const val TAG = "UpdateDlSvc"

        const val EX_URL = "extra_update_url"
        const val EX_SHA256 = "extra_sha256"
        const val EX_VERSION_CODE = "extra_version_code"
        const val EX_VERSION_NAME = "extra_version_name"
        const val EX_SIZE = "extra_size_bytes"

        /** 落盘目录（filesDir 下，FileProvider 以 files-path 暴露给安装器） */
        const val DIR_APKS = "updates"

        const val CHANNEL_ID = "hypermux_updates"
        const val NOTIF_ID = 9002
    }
}
