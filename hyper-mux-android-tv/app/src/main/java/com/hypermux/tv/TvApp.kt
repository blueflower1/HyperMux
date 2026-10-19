package com.hypermux.tv

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.Logger
import com.hypermux.tv.data.cache.MediaCacheManager
import com.hypermux.tv.data.repo.AuthRepo
import com.hypermux.tv.data.repo.PendingProgressStore
import com.hypermux.tv.data.repo.ProgressSync
import com.hypermux.tv.data.repo.SettingsRepo
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application：
 * 1. Hilt 入口
 * 2. Coil 3 全局 ImageLoader（内存 25%、磁盘 5%；含网络抓取器 + 防盗链 Referer）
 * 3. 启动预载会话与服务器地址（拦截器同步读取的内存缓存）
 * 4. 崩溃兜底：写 8KB ring 日志后重启进程（计划书 §8）
 */
@HiltAndroidApp
class TvApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    lateinit var authRepo: AuthRepo

    @Inject
    lateinit var settingsRepo: SettingsRepo

    @Inject
    lateinit var progressSync: ProgressSync

    @Inject
    lateinit var pendingProgressStore: PendingProgressStore

    @Inject
    lateinit var cacheManager: MediaCacheManager

    override fun onCreate() {
        super.onCreate()
        installImageLoader()
        installCrashHandler()
        appScope.launch {
            authRepo.preload()
            settingsRepo.preload()
            pendingProgressStore.preload()
            // 恢复「仅 Wi-Fi 下载」到 DownloadManager：
            // 进程重启后运行条件会回到默认的 NETWORK，若不重放，用户的"仅 Wi-Fi"设置会失效。
            runCatching { cacheManager.setWifiOnly(settingsRepo.wifiOnlyDownload.first()) }
        }
        // 离线断点补报的两个触发点：App 启动 + 网络「离线→在线」（见 ProgressSync）
        progressSync.start()
    }

    /**
     * Coil 3 全局 ImageLoader。
     *
     * 注意两点（都是踩过的坑）：
     * 1. Coil 3 的**网络抓取器是独立 artifact**（coil-network-okhttp），必须显式注册，
     *    否则 AsyncImage 完全无法加载网图；
     * 2. 豆瓣图床有**防盗链**：不带 Referer 直接返回 418，因此在图片请求上补 Referer。
     *
     * 另外不用「Application 实现 ImageLoader.Factory」的写法——那会让 Hilt 的 KSP
     * 处理器解析不到本类基类（误报 kotlin.Any），改用 SingletonImageLoader.setSafe。
     */
    private fun installImageLoader() {
        SingletonImageLoader.setSafe(object : SingletonImageLoader.Factory {
            override fun newImageLoader(context: Context): ImageLoader {
                Log.i(TAG_IMAGE, "newImageLoader() 生效（Coil 使用本配置）")
                val imageHttp = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val host = req.url.host.lowercase()
                        val builder = req.newBuilder()
                            // 图床防盗链：豆瓣系需要 movie.douban.com 作为 Referer
                            if (host.contains("douban")) {
                                builder.header("Referer", "https://movie.douban.com/")
                            }
                            // 部分 CDN 对空 UA 不友好
                            builder.header("User-Agent", IMAGE_USER_AGENT)
                            chain.proceed(builder.build())
                    }
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                return ImageLoader.Builder(context)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp })) }
                    // 只记 WARN 及以上（release 也生效，便于排查"海报不显示"）
                    .logger(object : Logger {
                        override var minLevel: Logger.Level = Logger.Level.Warn
                        override fun log(
                            tag: String,
                            level: Logger.Level,
                            message: String?,
                            throwable: Throwable?
                        ) {
                            if (throwable != null) {
                                Log.w(TAG_IMAGE, "[$level] $message", throwable)
                            } else {
                                Log.w(TAG_IMAGE, "[$level] $message")
                            }
                        }
                    })
                    .memoryCache {
                        MemoryCache.Builder()
                            .maxSizePercent(context, 0.25)
                            .build()
                    }
                    .diskCache {
                        DiskCache.Builder()
                            .directory(File(context.cacheDir, "image_cache"))
                            .maxSizePercent(0.05)
                            .build()
                    }
                    .build()
            }
        })
    }

    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val log = File(filesDir, "hypermux_crash.log")
                val line = "${System.currentTimeMillis()}|${throwable.javaClass.name}|${throwable.message}\n"
                // 8KB ring：简单截断实现
                if (log.exists() && log.length() + line.length > 8 * 1024) {
                    log.writeText(log.readText().takeLast(4 * 1024) + line)
                } else {
                    log.appendText(line)
                }
            }
            default?.uncaughtException(thread, throwable)
                ?: Process.killProcess(Process.myPid())
        }
    }

    private companion object {
        const val TAG_IMAGE = "HyperMuxImage"
        const val IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
