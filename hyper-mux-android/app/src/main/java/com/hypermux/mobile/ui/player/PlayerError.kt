package com.hypermux.mobile.ui.player

import androidx.media3.common.PlaybackException
import java.io.IOException

/** 播放错误分类（§6.5，与 TV 端一致） */
enum class PlayerErrorKind {
    /** IOException / 超时 → "网络或资源速度较慢"，4s 后自动重试 ×1 */
    NETWORK,

    /** HTTP 4xx（403/404）→ "链接不可用（可能失效）"，不重试 */
    HTTP_BAD,

    /** 解码错误 → "该格式设备不支持（HEVC/10bit?），建议换 H264 源"，不重试 */
    DECODE,

    /** 其他 → "播放出错了"，自动 ×1 → 手动 */
    GENERIC
}

/** 分类结果：文案 + 是否自动重试 */
data class ClassifiedError(
    val kind: PlayerErrorKind,
    val message: String,
    val autoRetry: Boolean
)

/**
 * 播放错误分类（§6.5）。
 *
 * [audioTrackFailure] = 音轨阶梯已经走到静音、这一轮仍失败：文案要落在音轨上（换成 AAC 立体声），
 * 而不是把"设备解不了视频（AV1/HEVC）"的结论套到一个只是音轨坏掉的片源上。
 */
fun classifyPlaybackError(error: PlaybackException, audioTrackFailure: Boolean = false): ClassifiedError {
    val code = error.errorCode
    return when (code) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            ClassifiedError(PlayerErrorKind.HTTP_BAD, "链接不可用（可能已失效）", autoRetry = false)

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED ->
            if (audioTrackFailure) {
                ClassifiedError(
                    PlayerErrorKind.DECODE,
                    "当前设备硬解和软件解码都处理不了该片源的音轨（DTS / 杜比 DDP 等），" +
                        "建议在后台把音轨换成 AAC 立体声",
                    autoRetry = false
                )
            } else {
                ClassifiedError(
                    PlayerErrorKind.DECODE,
                    "当前设备硬解和软件解码都处理不了该片源（AV1 / HEVC / 10bit 等），" +
                        "建议在后台把片源换成 H.264（avc）",
                    autoRetry = false
                )
            }

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            ClassifiedError(
                PlayerErrorKind.NETWORK,
                "网络或资源速度较慢",
                autoRetry = true
            )

        else -> {
            if (error.cause is IOException || error is IOException) {
                ClassifiedError(
                    PlayerErrorKind.NETWORK,
                    "网络或资源速度较慢",
                    autoRetry = true
                )
            } else {
                ClassifiedError(
                    PlayerErrorKind.GENERIC,
                    "播放出错了",
                    autoRetry = true
                )
            }
        }
    }
}
