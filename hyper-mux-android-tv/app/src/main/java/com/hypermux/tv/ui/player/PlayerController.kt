package com.hypermux.tv.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * ExoPlayer 装配（§6.1）：MKV/Matroska + MP4 + HLS 核心库自带；
 * ±10s 快进退；缓冲 15s~50s；音频变噪自动暂停。
 * 单实例：同 Activity 内切集 = 换 MediaItem，不重建（§6.7）。
 *
 * 数据源只有一条路：[networkDataSourceFactory]（播放唯一会触网的路径），请求头
 * （Referer 等由播放直链接口下发）通过 [DefaultHttpDataSource] 注入（[applyHeaders]；
 * Media3 1.7 的 MediaItem.Builder 已无 setHeaders）。
 */
@OptIn(UnstableApi::class)
class PlayerController(context: Context) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()

    /** 网络数据源工厂（播放唯一会触网的路径） */
    private val networkDataSourceFactory = DefaultDataSourceFactory(context, httpDataSourceFactory)

    private val networkMediaSourceFactory = DefaultMediaSourceFactory(networkDataSourceFactory)

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
     *    **必须保持 ON，绝对不能改成 PREFER**：PREFER 会把所有片源都抢去跑 FFmpeg 软解（连有硬解的也抢），
     *    只有在"平台无可用解码器"时才由 ON 交给 FFmpeg，才符合上面的优先级。
     *    覆盖范围：系统无硬解的视频（模拟器/电视盒子上的 HEVC、AV1 等）与音轨（DTS、DTS-HD、TrueHD 等）。
     *    注意 ①②仍然生效（NextRenderersFactory 继承自 DefaultRenderersFactory，两个 setter 照常链式调用）。
     */
    private val renderersFactory = NextRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(HardwareOnlyCodecSelector)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

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
     * 播放一个媒体项（网络源）。调用方需先 [applyHeaders]。
     */
    fun play(item: MediaItem) {
        player.setMediaSource(networkMediaSourceFactory.createMediaSource(item))
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
