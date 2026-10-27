package com.hypermux.mobile.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.hypermux.mobile.data.cache.MediaCacheManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * ExoPlayer 装配（§6.1）：MKV/Matroska + MP4 + HLS 核心库自带；
 * ±10s 快进退；缓冲 15s~50s；音频变噪自动暂停。
 * 单实例：同 Activity 内切集 = 换 MediaItem，不重建（§6.7）。
 *
 * 两种解码档位（[preferSoftwareDecode]）：
 * - `false`（默认）：**硬解优先** —— 只把硬件解码器喂给 MediaCodec 渲染器，无硬解时落到 FFmpeg 扩展渲染器；
 * - `true`：**FFmpeg 软解优先** —— 扩展渲染器排在 MediaCodec 之前，任何片源都先走 FFmpeg，
 *   FFmpeg 解不了的再由平台解码器（含 `c2.android.*` 软解）兜底。
 *   这一档是给「有硬解但解不了这一路码流」的机型准备的第二跳，见 [PlayerScreen] 的硬解失败自动兜底。
 *
 * 数据源按"源类型"二选一（§6.2 + 离线播放）：
 * - **网络**：[networkDataSourceFactory]，请求头（Referer 等由播放直链接口下发）通过
 *   [DefaultHttpDataSource] 注入（[applyHeaders]；Media3 1.7 的 MediaItem.Builder 已无 setHeaders）。
 * - **本地下载缓存**：已完整显式下载的集，VM 下发本地 URI（[MediaCacheManager.cacheUriOf]，
 *   authority 即缓存 key `video-<videoId>`），[cacheDataSourceFactory] 直接读下载缓存的
 *   span 文件补齐数据——**全程不走上游、不写缓存、断网可播**（缓存由 NoOpCacheEvictor 托管，
 *   只用户显式删除才失效；若是"完整"判定时序上被删了，上游兜底会请求失败并走错误面板）。
 *   两种源在 [play] 里按 URI 选择各自的 MediaSource 工厂装配（玩家实例不重建）。
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    cacheManager: MediaCacheManager,
    /** true = FFmpeg 软解优先档（硬解失败后的兜底档，需要重建播放器才能切换） */
    private val preferSoftwareDecode: Boolean = false
) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()

    /** 网络数据源工厂（播放唯一会触网的路径） */
    private val networkDataSourceFactory = DefaultDataSourceFactory(context, httpDataSourceFactory)

    /**
     * 本地播放数据源工厂：URI 形如 `videocache://video-<videoId>`，
     * [CacheKeyFactory] 把该 URI 映射到下载用的缓存 key（authority 本身），
     * CacheDataSource 按 key 命中 SimpleCache 的 span 文件直接读。
     * upstream 仅作"缓存意外不完整"时的兜底（正常由 VM 的 isFullyCached 把守，不会触发）。
     */
    private val cacheDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cacheManager.cache)
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        .setCacheKeyFactory(CacheKeyFactory { spec ->
            spec.uri?.let { uri ->
                if (uri.scheme == MediaCacheManager.CACHE_URI_SCHEME) {
                    uri.authority ?: uri.toString()
                } else {
                    uri.toString()
                }
            } ?: spec.uri.toString()
        })

    private val networkMediaSourceFactory = DefaultMediaSourceFactory(networkDataSourceFactory)
    private val cacheMediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

    /**
     * Renderers 工厂（解码相关的三处关键配置，缺一不可）：
     * ① [DefaultRenderersFactory.setEnableDecoderFallback] = true：
     *    某个解码器初始化失败时，自动回退尝试候选列表里的下一个解码器（例如硬解失败换软解），
     *    而不是直接把整片判为"无法解码"。
     * ② [DefaultRenderersFactory.setMediaCodecSelector] = [HardwareOnlyCodecSelector]：
     *    **只返回硬件解码器**，把平台软解（c2.android.*，variant=slow-cpu）排除在候选之外。
     *    这样"无硬解"时平台渲染器会声明不支持，从而落到下面的 FFmpeg 扩展渲染器，
     *    形成「硬解 > FFmpeg 软解 > 平台软解」的优先级（平台软解只在 FFmpeg 也解不了时兜底）。
     * ③ [DefaultRenderersFactory.setExtensionRendererMode] = EXTENSION_RENDERER_MODE_ON：
     *    用 [NextRenderersFactory]（NextLib 的预编译 FFmpeg 解码扩展，`DefaultRenderersFactory` 子类）
     *    追加 FfmpegAudioRenderer / FfmpegVideoRenderer。
     *    **默认档必须保持 ON，绝对不能改成 PREFER**：PREFER 会把所有片源都抢去跑 FFmpeg 软解（连有硬解的也抢），
     *    只有在"平台无可用解码器"时才由 ON 交给 FFmpeg，才符合上面的优先级。
     *    覆盖范围：系统无硬解的视频（模拟器/电视盒子上的 HEVC、AV1 等）与音轨（DTS、DTS-HD、TrueHD 等）。
     *    注意 ①②仍然生效（NextRenderersFactory 继承自 DefaultRenderersFactory，两个 setter 照常链式调用）。
     * ④ [preferSoftwareDecode] = true 时反过来：selector 用平台默认（硬解 + 软解全量候选）、
     *    扩展模式用 PREFER（FFmpeg 抢在 MediaCodec 之前）。为什么需要这一档：
     *    ON 档下只要设备**声明**了某个硬解，MediaCodec 渲染器就认为自己能解，FFmpeg 永远轮不到；
     *    而厂商硬解常见"谎报能力"（只支持 8-bit Main、或有分辨率/帧率上限），此时 ① 的 fallback
     *    只能在硬解候选里打转 → 整片报 DECODING_FAILED。换到 PREFER 档才真正把 FFmpeg 顶上来。
     *    PREFER 只允许作用在**视频**上：[SoftwareFirstVideoRenderersFactory] 把音频的扩展模式强制降回 ON，
     *    否则连普通 AAC 都会被抢去跑 FfmpegAudioRenderer（实测在该音轨上 `swr_init` 失败 → 整片报错）。
     */
    private val renderersFactory =
        (if (preferSoftwareDecode) SoftwareFirstVideoRenderersFactory(context) else NextRenderersFactory(context))
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(
                if (preferSoftwareDecode) MediaCodecSelector.DEFAULT else HardwareOnlyCodecSelector
            )
            .setExtensionRendererMode(
                if (preferSoftwareDecode)
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

    /** 当前实例处于哪一档（UI 侧据此决定"重试"该怎么走，也用于日志） */
    val isSoftwareDecodePreferred: Boolean get() = preferSoftwareDecode

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(networkMediaSourceFactory)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        // 缓冲策略：minBuffer 15s / maxBuffer 50s / 起播 2.5s / 卡顿后重播 5s
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
                .build()
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    /**
     * 应用本次播放的请求头（如 Referer），在 [play] 网络分支前调用。
     */
    fun applyHeaders(headers: Map<String, String>?) {
        httpDataSourceFactory.setDefaultRequestProperties(headers ?: emptyMap())
    }

    /**
     * 播放一个媒体项：按源类型选择数据源工厂装配 MediaSource（玩家实例不重建）。
     * 本地缓存入口（[MediaCacheManager.isCacheUri] 的 URI）走 [cacheMediaSourceFactory]，
     * 其余走 [networkMediaSourceFactory]（调用方需先 [applyHeaders]）。
     */
    fun play(item: MediaItem, fromLocalCache: Boolean) {
        val factory: MediaSource.Factory =
            if (fromLocalCache) cacheMediaSourceFactory else networkMediaSourceFactory
        player.setMediaSource(factory.createMediaSource(item))
        player.prepare()
        player.playWhenReady = true
    }

    /** 错误后重试当前媒体项（Media3 1.7 已移除 ExoPlayer.retry()） */
    fun retry() {
        player.prepare()
    }

    fun release() {
        player.release()
    }
}

/**
 * 软解优先档专用的 renderers 工厂：视频沿用调用方设定的扩展模式（PREFER，FFmpeg 抢在 MediaCodec 之前），
 * 但**音频的扩展模式在这里被强制降回 ON**（平台解码器优先，FFmpeg 只兜底平台解不了的音轨）。
 *
 * 为什么必须这么做：`setExtensionRendererMode(PREFER)` 是全局的，会把音频渲染器也一起顶到前面，
 * 于是连普通 AAC 音轨都会交给 FfmpegAudioRenderer；实测 NextLib 的 FFmpeg 音频解码器在
 * `channelCount` 未设的 AAC 上 `swr_init: Invalid argument` 直接失败，整片报 DECODING_FAILED。
 * 视频需要 PREFER 才能压过"谎报能力"的厂商硬解，音频不需要、而且有害。
 */
@OptIn(UnstableApi::class)
private class SoftwareFirstVideoRenderersFactory(context: Context) : NextRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        handler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            handler,
            eventListener,
            out
        )
    }
}
