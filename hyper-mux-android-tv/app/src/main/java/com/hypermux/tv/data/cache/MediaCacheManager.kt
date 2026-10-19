package com.hypermux.tv.data.cache

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** 下载统计（「我的」页「缓存」分组展示用） */
data class CacheStats(
    /** 有显式下载记录的影片数（「我的」页 "N 部"） */
    val movieCount: Int = 0,
    /** 显式下载任务数（下载中/暂停/失败/完成全部计入） */
    val episodeCount: Int = 0,
    /** 其中已完整下载（可无网播放）的集数 */
    val completedDownloads: Int = 0,
    /** 下载目录当前占用（字节） */
    val bytes: Long = 0L
)

/**
 * 「我的下载」页：**一集**的展示 / 操作数据（全部来源为显式下载任务）。
 */
data class EpisodeDownload(
    /** `"$movieId-$videoId"`（DownloadRequest 的 id，唯一） */
    val mediaId: String,
    val cacheKey: String,
    val videoId: Long,
    val seasonNo: Int,
    val episodeNo: Int,
    val title: String?,
    /** `Download.STATE_*` */
    val state: Int,
    val percent: Float,
    val bytes: Long,
    val totalBytes: Long,
    /** `Download.STOP_REASON_*`（state=STOPPED 时有意义：区分"用户暂停" vs "网络不满足被自动暂停"） */
    val stopReason: Int = Download.STOP_REASON_NONE,
    /** 实时下载速度（字节/秒），由页面 ViewModel 按轮询差值估算，非活动任务为 0 */
    val speedBytesPerSec: Long = 0L
) {
    val displayName: String
        get() = when {
            episodeNo > 0 -> buildString {
                append("第").append(episodeNo).append("集")
                title?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
            }
            !title.isNullOrBlank() -> title!!
            // 没有元信息（旧版本下载下来的、或无法归组的缓存）：至少给出 videoId，便于辨认与删除
            videoId > 0 -> "剧集 #$videoId"
            else -> "缓存内容"
        }

    val isDownloaded: Boolean get() = state == Download.STATE_COMPLETED
    val isActive: Boolean get() = state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val isPaused: Boolean get() = state == Download.STATE_STOPPED
    val isFailed: Boolean get() = state == Download.STATE_FAILED
    val isQueued: Boolean get() = state == Download.STATE_QUEUED
    /** 用户主动暂停（本页暂停按钮写的停止原因；区别于"运行条件不满足被挂起 / 排队未开始"） */
    val isUserPaused: Boolean get() =
        state == Download.STATE_STOPPED && stopReason == MediaCacheManager.STOP_REASON_PAUSED
}

/** 「我的下载」页：**一部影片**的分组（海报 + 标题 + 该剧所有下载中的集） */
data class MovieDownloadGroup(
    val movieId: Long,
    val title: String,
    val coverUrl: String?,
    val episodes: List<EpisodeDownload>
) {
    val completedCount: Int get() = episodes.count { it.isDownloaded }
    val totalBytes: Long get() = episodes.sumOf { it.bytes }
    val activeCount: Int get() = episodes.count { it.isActive }
    val pausedCount: Int get() = episodes.count { it.isPaused || it.isFailed }
    /** 未完成（下载中 / 暂停 / 失败，含等待网络的自动暂停）的集数 */
    val incompleteCount: Int get() = episodes.count { !it.isDownloaded }
    /** 全部集数（海报卡片 "已下 N/M 集"） */
    val episodeCount: Int get() = episodes.size
}

/** 存储占用（「我的下载」页存储条：无容量上限，只展示占用与设备剩余） */
data class CacheStorageInfo(
    val usedBytes: Long,
    val freeBytes: Long
)

/** 单集下载状态（UI 角标 / 进度百分比用；避免 UI 直接依赖 Download 的内部字段） */
data class DownloadState(
    val mediaId: String,
    /** STATE_QUEUED / STATE_DOWNLOADING / STATE_COMPLETED / ... */
    val state: Int,
    val percent: Float,
    val bytesDownloaded: Long,
    val contentLength: Long
) {
    val isCompleted: Boolean get() = state == Download.STATE_COMPLETED
    val isActive: Boolean get() = state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
}

/**
 * 媒体离线下载层（单例）：**显式下载专用** [SimpleCache]。
 *
 * 设计原则（第七轮重构）：
 * - **取消"边看边缓存"**：播放不再往缓存里写任何字节；只有用户显式发起的下载才落盘。
 * - **无容量上限**：evictor 用 [NoOpCacheEvictor]（不自动淘汰），下载只增不减，由用户在
 *   「我的下载」页显式删除；超空间的风险由入队预检 + 失败文案兜底。
 * - **离线播放**：一集显式下载完成后，播放侧读本地下载缓存（[isFullyCached] + 本地 URI），
 *   其余一律走网络流。
 *
 * key 约定：统一用 `video-$videoId`（见 [cacheKeyOf]）。**不用直链本身当 key**：
 * OpenList/网盘直链常带时效签名（每次换直链都不同），用直链做 key 会导致缓存永远命中不了。
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    /** 影片/单集元信息（片名、海报、季集号）：缓存中心离线展示与分组都依赖它 */
    private val metaStore: DownloadMetaStore
) {
    private val gson = Gson()

    /** 元信息（每集的展示名 + 下载请求头）持久化，进程重启后离线下载仍能补上防盗链头 */
    private val meta: SharedPreferences =
        context.getSharedPreferences("hypermux_media_meta", Context.MODE_PRIVATE)

    /** content index 与 download index 共用一个 DatabaseProvider（Media3 推荐做法） */
    private val databaseProvider = StandaloneDatabaseProvider(context)

    /**
     * 显式下载专用 cache：**无容量上限**（NoOpCacheEvictor 不自动淘汰，
     * 用户显式删除才释放空间，见「我的下载」页）。
     */
    val cache: SimpleCache = SimpleCache(
        File(context.filesDir, CACHE_DIR),
        NoOpCacheEvictor(),
        databaseProvider
    )

    /** 下载用的上游 HTTP 源：与播放侧分离，避免请求头互相污染 */
    private val downloadUpstreamFactory: DataSource.Factory =
        ResolvingDataSource.Factory(DefaultHttpDataSource.Factory().setUserAgent(UA)) { dataSpec ->
            // 在 open 之前把「该 URL 上次换直链拿到的 headers（Referer 等）」补进 DataSpec，
            // 这是 Media3 里给单个下载项注入请求头的正规做法（DefaultHttpDataSource 会读取
            // dataSpec.httpRequestHeaders）。下载任务跨进程重启后 headers 仍从 meta 里取得到。
            headersOf(dataSpec.uri.toString())?.let { dataSpec.withRequestHeaders(it) } ?: dataSpec
        }

    /**
     * 离线下载管理器：显式传入我们的 cache / databaseProvider / 上游工厂。
     * 注意 Media3 1.7.1 **没有** DownloadManager.Builder，只有这个 5 参构造器（已核对 1.7.1 AAR）。
     */
    val downloadManager: DownloadManager = DownloadManager(
        context,
        databaseProvider,
        cache,
        downloadUpstreamFactory,
        Executors.newFixedThreadPool(2)
    )

    private val downloadIndex: DownloadIndex get() = downloadManager.downloadIndex

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /**
     * 是否已为"有活动下载"启动过前台服务（[DownloadService.startForeground]）。
     * 由 [syncState] 统一对账：有活动任务且未启动 → 拉起前台；无活动任务且已启动 → 停服务。
     * 状态收敛在单主线程上，无需加锁。
     */
    private var serviceForeground = false

    /** 所有下载项状态（按 mediaId 索引）：UI 用它在集数格/控制条上画角标与百分比 */
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val _stats = MutableStateFlow(CacheStats())

    /** 缓存占用 / 已完成集数 */
    val stats: StateFlow<CacheStats> = _stats.asStateFlow()

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            syncState()
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?
        ) {
            syncState()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            syncState()
        }
    }

    init {
        downloadManager.addListener(listener)
        syncState()
        // 第七轮一次性迁移：旧版本"边播边缓存"留下的分片不再被使用，就地清理（回收空间）
        migrateLegacyPlaybackCache()
    }

    /**
     * 从 DownloadIndex 重算状态快照（DownloadManager 的所有回调都汇到这里，避免多处算口径不一致）
     */
    fun syncState() {
        val map = LinkedHashMap<String, DownloadState>()
        var completed = 0
        var total = 0
        val movieIds = HashSet<Long>()
        runCatching {
            downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    map[d.request.id] = DownloadState(
                        mediaId = d.request.id,
                        state = d.state,
                        percent = safePercent(d.percentDownloaded),
                        bytesDownloaded = d.bytesDownloaded,
                        contentLength = d.contentLength
                    )
                    if (d.state == Download.STATE_COMPLETED) completed++
                    total++
                    // mediaId = "$movieId-$videoId" → 反解 film 维度（「我的」页 "N 部"）
                    d.request.id.substringBefore('-').toLongOrNull()?.let { movieIds += it }
                }
            }
        }
        _states.value = map
        _stats.value = CacheStats(
            movieCount = movieIds.size,
            episodeCount = total,
            completedDownloads = completed,
            bytes = cachedBytes()
        )
        ensureServiceForeground(map.values.any { it.isActive })
    }

    /**
     * 前台服务对账：有活动任务（下载中 / 排队等条件）时确保 [HyperDownloadService] 在前台运行
     * （后台不被杀 + 系统通知可见进度）；全部完成后停服务（系统自动撤掉通知）。
     * 入队/继续的 intent 本身已带 foreground 标志，这里负责**兜底**（进程冷启动恢复、
     * 回调先行等时序）与**收尾**。
     */
    private fun ensureServiceForeground(hasActive: Boolean) {
        runCatching {
            if (hasActive && !serviceForeground) {
                DownloadService.startForeground(context, HyperDownloadService::class.java)
                serviceForeground = true
            } else if (!hasActive && serviceForeground) {
                context.stopService(Intent(context, HyperDownloadService::class.java))
                serviceForeground = false
            }
        }
    }

    // ---------- 离线播放（已下完的集 → 读本地缓存） ----------

    /**
     * 该集是否已**完整显式下载**（`[0, 全长)` 区间在缓存里一个字节不缺），
     * 是则播放侧可以完全脱离网络播放。
     *
     * 1.7.1 的 SimpleCache 把下载内容写成**多个 ~5MiB 的裸字节 span 文件**
     * （`player_cache/<0-9>/<compressed>.<position>.<time>.v3.exo`，key ↔ 文件只存在于
     * 缓存索引数据库里），没有"整集一个文件"可 `file://` 直读。所以判定改为
     * "区间字节数是否齐全"：总长取 [SimpleCache.getContentMetadata]（DownloadManager
     * 完成时写入 CONTENT_LENGTH），齐全数用 [SimpleCache.getCachedBytes]。
     * 下载严格从 0 顺序写，字节数齐全 ⇔ 覆盖完整。
     *
     * 播放装配见 [com.hypermux.tv.ui.player.PlayerController]：
     * CacheDataSource 按 [isCacheUri] 里的 key 从缓存读，补齐才走上游网络。
     */
    fun isFullyCached(videoId: Long): Boolean {
        if (videoId <= 0L) return false
        val key = cacheKeyOf(videoId)
        return runCatching {
            val meta = cache.getContentMetadata(key) ?: return@runCatching false
            val length = ContentMetadata.getContentLength(meta)
            length > 0L && cache.getCachedBytes(key, 0L, length) == length
        }.getOrDefault(false)
    }

    // ---------- 播放状态查询（播放侧本身已不再写缓存，这里只读显式下载的结果） ----------

    /** 该集已显式下载的字节数（`> 0` 用于「缓存」按钮的图标态 / 详情页角标） */
    fun cachedBytesOf(mediaIdOrUri: String): Long =
        runCatching {
            val key = if (mediaIdOrUri.startsWith("video-")) mediaIdOrUri
            else uriToCacheKey[mediaIdOrUri] ?: mediaIdOrUri
            cache.getCachedBytes(key, 0L, Long.MAX_VALUE)
        }.getOrDefault(0L)

    /** 已缓存判断：优先看缓存命中字节，命中为 0 再看是否被 DownloadManager 标记为完成 */
    fun isCached(mediaIdOrUri: String): Boolean {
        val state = _states.value[mediaIdOrUri]
        if (state?.isCompleted == true) return true
        return cachedBytesOf(mediaIdOrUri) > 0L
    }

    fun cachedBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /** 清空全部缓存（含下载索引里的条目），随后重算统计 */
    fun clearAll() {
        runCatching { downloadManager.removeAllDownloads() }
        runCatching { cache.keys.toList().forEach { cache.removeResource(it) } }
        runCatching { meta.edit().clear().apply() }
        runCatching { metaStore.clear() }
        uriToCacheKey.clear()
        syncState()
    }

    // ---------- 离线下载 ----------

    /**
     * 入队一集离线下载。
     * [mediaId] = `"$movieId-$videoId"`（DownloadRequest 的 id，唯一）；缓存 key = `video-$videoId`，
     * 与播放侧一致，下载完成后播放即命中本地。
     *
     * 元信息（片名/海报/季集号）在入队前先落盘：下载由 Service 托管，进程重启后仍要靠它显示"哪部剧第几集"。
     */
    fun enqueueDownload(
        movieId: Long,
        videoId: Long,
        uri: String,
        headers: Map<String, String>?,
        seasonNo: Int,
        episodeNo: Int,
        videoTitle: String?,
        movieTitle: String,
        coverUrl: String?
    ): String {
        val mediaId = mediaIdOf(movieId, videoId)
        val cacheKey = cacheKeyOf(videoId)
        val episodeMeta = EpisodeMeta(mediaId, movieId, videoId, seasonNo, episodeNo, videoTitle)
        metaStore.putMovie(MovieMeta(movieId, movieTitle, coverUrl))
        metaStore.putEpisode(episodeMeta)
        val request = DownloadRequest.Builder(mediaId, Uri.parse(uri))
            .setCustomCacheKey(cacheKey)
            // Media3 1.7 的 DownloadRequest 没有"headers"字段（也没有 MediaItem 载体），
            // 这里把 headers 一起塞进 data 留档，实际注入请求头走持久化 + ResolvingDataSource（见 headersOf）。
            .setData(gson.toJson(headers ?: emptyMap<String, String>()).toByteArray())
            .build()
        persistHeaders(uri, headers)
        uriToCacheKey[uri] = cacheKey
        // foreground=true：Service 按前台服务启动（配套通知 + 后台不被杀），见 HyperDownloadService
        DownloadService.sendAddDownload(context, HyperDownloadService::class.java, request, true)
        return mediaId
    }

    // ---------- 下载控制（暂停 / 继续 / 仅 Wi-Fi） ----------

    /**
     * 单集暂停。
     * 注意：Media3 1.7 的 `DownloadManager` **没有**单集 pause/resume（只有批量 `pauseDownloads()`），
     * 官方给单集"暂停"的方式是设一个**停止原因**（[Download.STOP_REASON_NONE] = 未暂停）。
     */
    fun pauseDownload(mediaId: String) {
        runCatching {
            DownloadService.sendSetStopReason(
                context,
                HyperDownloadService::class.java,
                mediaId,
                STOP_REASON_PAUSED,
                false
            )
        }
        syncState()
    }

    /** 单集继续：清掉停止原因 */
    fun resumeDownload(mediaId: String) {
        runCatching {
            DownloadService.sendSetStopReason(
                context,
                HyperDownloadService::class.java,
                mediaId,
                Download.STOP_REASON_NONE,
                true
            )
        }
        syncState()
    }

    /**
     * 全部暂停：给所有**未完成且未被手动暂停**的任务逐一设"用户暂停"停止原因。
     * 不能用 `DownloadService.sendPauseDownloads`——1.7.1 里它只切换
     * DownloadManager 的**全局暂停开关**（`downloadsPaused` 标志），
     * 不改每个任务自己的 stop_reason，而 UI/角标/通知全部按单任务渲染，
     * 全局开关对用户可见的"已暂停"行无效（真机：点"全部继续/暂停"无任何效果）。
     */
    fun pauseAll() {
        forEachDownload { d ->
            if (d.state != Download.STATE_COMPLETED && d.state != Download.STATE_REMOVING &&
                d.stopReason == Download.STOP_REASON_NONE
            ) {
                runCatching {
                    DownloadService.sendSetStopReason(
                        context,
                        HyperDownloadService::class.java,
                        d.request.id,
                        STOP_REASON_PAUSED,
                        false
                    )
                }
            }
        }
        syncState()
    }

    /** 全部继续：清掉所有"暂停中"任务（用户暂停 + 计费网络自动挂起）的停止原因，自恢复排程 */
    fun resumeAll() {
        forEachDownload { d ->
            if (d.state != Download.STATE_COMPLETED && d.state != Download.STATE_REMOVING &&
                d.stopReason != Download.STOP_REASON_NONE
            ) {
                runCatching {
                    DownloadService.sendSetStopReason(
                        context,
                        HyperDownloadService::class.java,
                        d.request.id,
                        Download.STOP_REASON_NONE,
                        true
                    )
                }
            }
        }
        syncState()
    }

    /** 遍历现网全部下载项（DownloadIndex 持久化）；访问模式与 [syncState] 一致 */
    private inline fun forEachDownload(block: (Download) -> Unit) {
        runCatching {
            downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) block(cursor.download)
            }
        }.onFailure { Log.w(TAG, "forEachDownload failed", it) }
    }

    /**
     * 仅 Wi-Fi 下载：把 DownloadManager 的运行条件收紧到"非计费网络"。
     * 蜂窝网下正在跑的下载会被系统暂停（DownloadManager 自身行为），恢复 Wi-Fi 后自动继续。
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        runCatching {
            downloadManager.setRequirements(
                Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
            )
        }
    }

    /** 下载占用 / 设备可用空间（「我的下载」页存储条；无上限概念） */
    fun storageInfo(): CacheStorageInfo {
        val free = runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)
        return CacheStorageInfo(usedBytes = cachedBytes(), freeBytes = free)
    }

    /**
     * 显式下载**入队前**的设备剩余空间预检（各下载入口调用）：
     * 剩余低于 [MIN_FREE_BYTES_TO_ENQUEUE] 时由调用方提示"设备空间不足"并拒绝入队——
     * 空间不足硬把文件排进队列，最终只能是下满磁盘后变成"失败"任务，还占着已下部分的空间。
     * StatFs 查询失败时保守拒绝（返回 false）。
     */
    fun canEnqueueForSpace(): Boolean =
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
            .getOrDefault(0L) >= MIN_FREE_BYTES_TO_ENQUEUE

    /**
     * 「我的下载」页的数据源：按影片分组，**只包含显式下载**（下载中 / 等待 / 暂停 / 失败 / 完成）。
     */
    fun groups(): List<MovieDownloadGroup> {
        val episodes = LinkedHashMap<Long, MutableList<EpisodeDownload>>()
        val movieIds = LinkedHashSet<Long>()

        runCatching {
            downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    // 元信息可能缺失（旧版本下载下来的项）：归到"其它下载"兜底组，
                    // 但**仍然要列出来**，否则用户会看到"占着空间却找不到可删的条目"。
                    val meta = metaStore.episode(d.request.id)
                    val key = d.request.customCacheKey
                        ?: meta?.let { cacheKeyOf(it.videoId) }
                        ?: d.request.id
                    val groupId = meta?.movieId ?: UNGROUPED_MOVIE_ID
                    movieIds += groupId
                    episodes.getOrPut(groupId) { mutableListOf() } += EpisodeDownload(
                        mediaId = d.request.id,
                        cacheKey = key,
                        videoId = meta?.videoId ?: 0L,
                        seasonNo = meta?.seasonNo ?: 1,
                        episodeNo = meta?.episodeNo ?: 0,
                        title = meta?.title,
                        state = d.state,
                        percent = safePercent(d.percentDownloaded),
                        bytes = if (d.state == Download.STATE_COMPLETED) {
                            d.contentLength.coerceAtLeast(0L)
                        } else {
                            d.bytesDownloaded.coerceAtLeast(0L)
                        },
                        totalBytes = d.contentLength.coerceAtLeast(0L),
                        stopReason = d.stopReason
                    )
                }
            }
        }

        return movieIds.map { id ->
            val m = metaStore.movie(id)
            MovieDownloadGroup(
                movieId = id,
                title = m?.title?.takeIf { it.isNotBlank() }
                    ?: if (id == UNGROUPED_MOVIE_ID) "其它下载" else "影片 #$id",
                coverUrl = m?.coverUrl,
                episodes = episodes[id].orEmpty()
                    .sortedWith(compareBy({ it.seasonNo }, { it.episodeNo }))
            )
        }
            .filter { it.episodes.isNotEmpty() }
            // 有下载中的排前面，其余按片名
            .sortedWith(compareByDescending<MovieDownloadGroup> { it.activeCount > 0 }.thenBy { it.title })
    }

    /** 删除一条下载条目（DownloadManager 内部会负责删分片） */
    fun removeEpisodeItem(item: EpisodeDownload) {
        removeDownload(item.mediaId)
    }

    /**
     * 删除一部影片的**全部**下载（含进行中的任务），并清理该片元信息
     * （「我的下载」页编辑模式里按影片整卡删除用）。
     */
    fun removeGroup(movieId: Long) {
        val prefix = "$movieId-"
        runCatching {
            downloadIndex.getDownloads().use { cursor ->
                val mediaIds = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val id = cursor.download.request.id
                    if (id.startsWith(prefix)) mediaIds += id
                }
                mediaIds.forEach { downloadManager.removeDownload(it) }
            }
        }
        metaStore.allEpisodes()
            .filter { it.movieId == movieId }
            .forEach { metaStore.removeEpisode(it.mediaId) }
        metaStore.removeMovie(movieId)
        syncState()
    }

    fun isDownloaded(movieId: Long, videoId: Long): Boolean =
        _states.value[mediaIdOf(movieId, videoId)]?.isCompleted == true

    fun downloadState(movieId: Long, videoId: Long): DownloadState? =
        _states.value[mediaIdOf(movieId, videoId)]

    /** 删除单个下载（DownloadManager 内部会负责删分片；随后清掉元信息） */
    fun removeDownload(mediaId: String) {
        runCatching { downloadManager.removeDownload(mediaId) }
        metaStore.removeEpisode(mediaId)
        syncState()
    }

    // ---------- 内部 ----------

    /** `Download.percentDownloaded` 在"未开始 / 已停止"时可能是 NaN，直接拿去布局会炸，统一归一化 */
    private fun safePercent(v: Float): Float = if (v.isFinite()) v.coerceIn(0f, 100f) else 0f

    /** 入队后记住 uri → cacheKey（同一进程内 isCached(uri) 直接可用） */
    private val uriToCacheKey = HashMap<String, String>()

    private fun persistHeaders(uri: String, headers: Map<String, String>?) {
        runCatching {
            meta.edit()
                .putString(headersKey(uri), gson.toJson(headers ?: emptyMap<String, String>()))
                .apply()
        }
    }

    private fun headersOf(uri: String): Map<String, String>? {
        val json = meta.getString(headersKey(uri), null) ?: return null
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type).takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun headersKey(uri: String): String = "h_${uri.hashCode()}"

    /**
     * 第七轮**一次性**迁移（老版本升级场景）：
     * 1) 删掉"边播边缓存"（旧版本播放时隐式写入）留下的缓存分片 —— 新架构播放不再写缓存，
     *    这些分片既不能离线播（无下载记录）也不进「我的下载」，留着只占空间；
     * 2) 清掉对应的孤儿元数据（`v_` 反查索引 + 已无下载项的 `e_`/`m_` 记录，见 [DownloadMetaStore.pruneOrphans]）。
     * 已经**显式下载**完成的内容（下载索引在册）不受影响。
     * 用独立 prefs 标记只跑一次；任何一步异常都不阻塞启动。
     */
    private fun migrateLegacyPlaybackCache() {
        val flag = context.getSharedPreferences("hypermux_cache_migrate", Context.MODE_PRIVATE)
        if (flag.getBoolean(KEY_MIGRATED_V7, false)) return
        runCatching {
            val downloadKeys = HashSet<String>()
            val existingMediaIds = HashSet<String>()
            downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    existingMediaIds += d.request.id
                    d.request.customCacheKey?.let { downloadKeys += it }
                    // 兜底：自定义 key 缺失时按 mediaId 反解 videoId
                    d.request.id.substringAfter('-', "").toLongOrNull()
                        ?.let { downloadKeys += cacheKeyOf(it) }
                }
            }
            val removed = cache.keys.toList()
                .filterNot { it in downloadKeys }
                .also { keys -> keys.forEach { runCatching { cache.removeResource(it) } } }
            metaStore.pruneOrphans(existingMediaIds)
            Log.i(TAG, "cache migrated: removed ${removed.size} legacy playback spans")
        }.onFailure { Log.w(TAG, "cache migration failed（不影响功能）", it) }
        runCatching { flag.edit().putBoolean(KEY_MIGRATED_V7, true).apply() }
    }

    companion object {
        private const val TAG = "MediaCacheManager"

        /** 迁移标记：第七轮"取消边播边缓存"的旧分片清理（一次性） */
        private const val KEY_MIGRATED_V7 = "migrated_v7_downloads_only"

        /** 缓存目录：filesDir/player_cache（应用私有，卸载即清；**改名会导致用户已下载内容全部失效，勿动**） */
        const val CACHE_DIR = "player_cache"

        /** 缓存 key 前缀（`video-<videoId>`）：反解 videoId 时用 */
        const val CACHE_KEY_PREFIX = "video-"

        /** 允许入队下载的最小设备剩余空间（第七轮：无容量上限，靠入队预检 + 失败兜底防写满磁盘） */
        const val MIN_FREE_BYTES_TO_ENQUEUE = 100L * 1024 * 1024

        /** 「我的下载」页里无法归组（没有元信息）的下载项，统一挂到这个虚拟影片下 */
        const val UNGROUPED_MOVIE_ID = 0L

        /**
         * 单集暂停用的停止原因（App 自定义值；[Download.STOP_REASON_NONE] = 0 表示未暂停）。
         * Media3 1.7 无单集 pause API，只能通过"设停止原因"实现单集暂停。
         */
        const val STOP_REASON_PAUSED = 1

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        fun mediaIdOf(movieId: Long, videoId: Long): String = "$movieId-$videoId"

        /** 缓存 key：只用 videoId（全局唯一），避免时效直链导致缓存永远不命中 */
        fun cacheKeyOf(videoId: Long): String = "$CACHE_KEY_PREFIX$videoId"

        /**
         * 本地播放 URI 的 scheme；完整 URI 形如 `videocache://video-<videoId>`
         * （authority 即缓存 key）。播放用它指代"从下载缓存读该集"——
         * 断网可用（数据源不触网），请求头也不需要（不走 HTTP）。
         */
        const val CACHE_URI_SCHEME = "videocache"

        /** 已完整下载的集对应的本地播放 URI（[com.hypermux.tv.ui.player.PlayerController] 负责落地读取） */
        fun cacheUriOf(videoId: Long): String = "$CACHE_URI_SCHEME://${cacheKeyOf(videoId)}"

        /** 播放 URI 是否为本地缓存入口（[cacheUriOf] 的产物） */
        fun isCacheUri(url: String): Boolean = url.startsWith("$CACHE_URI_SCHEME://")
    }
}
