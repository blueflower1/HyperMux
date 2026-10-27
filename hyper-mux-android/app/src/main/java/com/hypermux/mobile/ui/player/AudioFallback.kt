package com.hypermux.mobile.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException

/**
 * 音轨回退阶梯（§6.5 第 3 类的细化；与 TV 端各留一份，和 `PlayerError.kt` / `MediaCodecs.kt` 一样双端镜像）。
 *
 * 为什么需要它：WEB-DL / REMUX 的 MKV 常把「杜比 DDP / DTS」这类**设备不一定能解**的音轨标成 default，
 * 而本项目不设语言/声道偏好（DefaultTrackSelector 这时只按 default 标志走），于是正好选中不安全的那一条；
 * 一失败 ExoPlayer 会让**整片**报错（连画面都没有）。这类片源旁边通常还放着一条百分百能解的
 * AAC 立体声轨 —— 老逻辑一步到位「禁音」把它白白浪费掉，用户只能看到「有画面没声音」或整片失败。
 *
 * 阶梯（每级只走一次；失败过的 mime 记进 [AudioFallbackState.triedMimes]，保证每一跳都真换了东西）：
 *  - CHANNEL_CAP：`maxAudioChannelCount = 2` → 5.1 轨被选择器排除，自然落到立体声轨（实测 4K DDP 片源靠这一跳）
 *  - FORCE_TRACK：对剩下的最安全候选下 `TrackSelectionOverride`（两条都是 5.1 时用；DTS/TrueHD 交给 FFmpeg 渲染器）
 *  - MUTED：确实没有可解的音轨 → 禁音保画面（原有行为，最后一招）
 * 阶梯耗尽才交给既有的「硬解 → FFmpeg 软解优先档」兜底，最后才是错误面板。
 *
 * 判据不靠猜：Media3 的 [ExoPlaybackException] 带 `rendererFormat`（出错的 renderer 当时正在解的那一路 Format），
 * 读它的 `sampleMimeType` 就能分清「音轨失败」与「视频失败」，不会让音轨阶梯抢走视频侧的兜底。
 */

/** 立体声档上限：5.1 / 7.1 轨会被 DefaultTrackSelector 的这条约束滤掉 */
private const val MAX_STEREO_CHANNELS = 2

/** 阶梯最多三跳（CAP / FORCE / MUTE），防止 prepare 无限循环 */
private const val MAX_AUDIO_FALLBACK_ATTEMPTS = 3

/** 阶梯级别，单调递增：只允许往前走，避免 CAP ↔ FORCE 来回打转 */
enum class AudioFallbackStage { NONE, CHANNEL_CAP, FORCE_TRACK, MUTED }

/** 阶梯状态：跨 `prepare()` 保留；换集复位，换解码档位（重建 player）时靠 [restoreOn] 恢复 */
class AudioFallbackState {

    /** 已经失败过的音轨 mime：保证每一跳都换掉一条不同的轨，不重复踩同一个坑 */
    val triedMimes: MutableSet<String> = mutableSetOf()

    var stage: AudioFallbackStage = AudioFallbackStage.NONE
        private set

    var attempts: Int = 0
        private set

    val isMuted: Boolean get() = stage == AudioFallbackStage.MUTED
    val exhausted: Boolean get() = isMuted || attempts >= MAX_AUDIO_FALLBACK_ATTEMPTS

    fun advanceTo(next: AudioFallbackStage) {
        stage = next
        attempts += 1
    }

    fun reset() {
        triedMimes.clear()
        stage = AudioFallbackStage.NONE
        attempts = 0
    }

    /**
     * 换解码档位会重建 ExoPlayer 实例，`trackSelectionParameters` 跟着丢掉 —— 已经生效的声道上限/禁音
     * 必须带过去，否则新实例又会选回那条坏音轨。
     *
     * `TrackSelectionOverride` 故意不带：它的 key 是 `TrackGroup` **实例**，新实例上的 group 多半是另一个
     * 对象，带过去只会得到一个永不命中的哑 override（真要换轨由阶梯重新推导）。
     */
    fun restoreOn(params: TrackSelectionParameters): TrackSelectionParameters {
        if (stage == AudioFallbackStage.NONE) return params
        val builder = params.buildUpon()
        if (isMuted) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        } else {
            builder.setMaxAudioChannelCount(MAX_STEREO_CHANNELS)
        }
        return builder.build()
    }
}

/** 一条音轨候选（从 Media3 [Tracks] 里抽出来的纯数据，便于 JVM 单测直接构造） */
data class AudioTrackCandidate(
    val trackGroup: TrackGroup,
    val trackIndex: Int,
    val mimeType: String?,
    /** 声道数；`C.LENGTH_UNSET` = 容器没声明 */
    val channelCount: Int,
    /**
     * Media3 给的轨标签。注意 MKV 多半拿不到：`MatroskaExtractor` 把 Track 的 NAME 存在自己的
     * `Track.name` 里，不会写进 `Format.label` —— 所以文案要有通用版兜底。
     */
    val label: String?,
    val isSelected: Boolean
) {
    /**
     * 声道数未知时按「可能是立体声」处理：DefaultTrackSelector 的声道上限只卡 `channelCount > 上限` 的轨，
     * 未声明（-1）的轨照样能被选中，所以这里不能把未知判成"过不了上限"。
     */
    val withinStereoCap: Boolean
        get() = channelCount == C.LENGTH_UNSET || channelCount <= MAX_STEREO_CHANNELS

    /** 越大越"危险"（越依赖 FFmpeg 兜底）；FORCE_TRACK 只敢直接指定 rank ≤ [FORCE_TRACK_MAX_RANK] 的轨 */
    val safetyRank: Int get() = audioSafetyRank(mimeType)
}

/** 可以直接指定试一次的音轨（rank ≤ 此值）：DTS/TrueHD(2) 交给 FFmpeg 渲染器解，比继续瞎撞可控 */
private const val FORCE_TRACK_MAX_RANK = 2

/** 音轨"由谁解"的把握程度：0 = 平台基本都有；1 = 多数设备有 / FFmpeg 稳；2 = 基本只能靠 FFmpeg 兜底 */
private fun audioSafetyRank(mime: String?): Int = when (mime) {
    MimeTypes.AUDIO_AAC,
    MimeTypes.AUDIO_OPUS,
    MimeTypes.AUDIO_VORBIS,
    MimeTypes.AUDIO_MPEG,
    MimeTypes.AUDIO_MPEG_L1,    MimeTypes.AUDIO_MPEG_L2,
    MimeTypes.AUDIO_FLAC,
    MimeTypes.AUDIO_ALAC -> 0

    MimeTypes.AUDIO_AC3,
    MimeTypes.AUDIO_E_AC3,
    MimeTypes.AUDIO_E_AC3_JOC,
    MimeTypes.AUDIO_AC4,
    MimeTypes.AUDIO_RAW,
    MimeTypes.AUDIO_WAV,
    null -> 1

    MimeTypes.AUDIO_TRUEHD,
    MimeTypes.AUDIO_DTS,
    MimeTypes.AUDIO_DTS_HD,
    MimeTypes.AUDIO_DTS_EXPRESS,
    MimeTypes.AUDIO_DTS_X -> 2

    else -> 3
}

/** 当前媒体里的全部音轨候选（无音轨时返回空表） */
fun audioTrackCandidates(tracks: Tracks): List<AudioTrackCandidate> =
    tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group ->
            (0 until group.length).map { i ->
                val format: Format = group.getTrackFormat(i)
                AudioTrackCandidate(
                    trackGroup = group.mediaTrackGroup,
                    trackIndex = i,
                    mimeType = format.sampleMimeType,
                    channelCount = format.channelCount,
                    label = format.label,
                    isSelected = group.isTrackSelected(i)
                )
            }
        }

/**
 * 这次失败是不是音轨侧的失败。
 *
 * 首选 `ExoPlaybackException.rendererFormat`（Media3 直接告诉我们是哪一路 Format 出的错）；
 * 拿不到时退到「AudioTrack 专属错误码」+「当前是否真的选中了音轨」。
 */
fun isAudioDecodeError(error: PlaybackException, candidates: List<AudioTrackCandidate>): Boolean {
    val mime = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
    if (mime != null) return mime.startsWith("audio/")

    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> candidates.any { it.isSelected }

        else -> false
    }
}

/** 失败的那一路音轨 mime（拿不到时退到"当前已选中的音轨 mime"） */
fun failedAudioMime(error: PlaybackException, candidates: List<AudioTrackCandidate>): String? {
    val mime = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
    if (mime != null && mime.startsWith("audio/")) return mime
    return candidates.firstOrNull { it.isSelected }?.mimeType
}

/** 阶梯的一跳：改什么 + 给用户的一句话 */
sealed interface AudioFallbackAction {
    val stage: AudioFallbackStage
    val userMessage: String
}

private data class ChannelCapAction(
    override val userMessage: String = "该片源的环绕声音轨无法解码，已切换到立体声音轨"
) : AudioFallbackAction {
    override val stage = AudioFallbackStage.CHANNEL_CAP
}

private data class ForceTrackAction(
    val candidate: AudioTrackCandidate,
    override val userMessage: String
) : AudioFallbackAction {
    override val stage = AudioFallbackStage.FORCE_TRACK
}

private data class MuteAction(
    override val userMessage: String = "该片源音轨（DTS / 杜比等）无法解码，已静音播放"
) : AudioFallbackAction {
    override val stage = AudioFallbackStage.MUTED
}

/**
 * 决定下一跳（不改任何状态，纯函数便于单测）。返回 null = 阶梯没有可用的下一步，
 * 调用方交给「软解优先档」兜底 / 错误面板。
 */
fun planAudioFallback(
    candidates: List<AudioTrackCandidate>,
    failedMime: String?,
    state: AudioFallbackState
): AudioFallbackAction? {
    if (state.exhausted) return null

    // 还能换的轨：刚失败的那条不算（不管选没选中），失败过的 mime 也不再重试
    val rest = candidates.filter { c ->
        val mime = c.mimeType
        when {
            mime == null -> !c.isSelected
            failedMime != null && mime == failedMime -> false
            state.triedMimes.contains(mime) -> false
            else -> true
        }
    }

    if (state.stage < AudioFallbackStage.CHANNEL_CAP && rest.any { it.withinStereoCap }) {
        return ChannelCapAction()
    }

    if (state.stage < AudioFallbackStage.FORCE_TRACK) {
        val force = rest
            .filter { it.safetyRank <= FORCE_TRACK_MAX_RANK }
            .minByOrNull { it.safetyRank }
        if (force != null) {
            val label = force.label?.takeIf { it.isNotBlank() }
            return ForceTrackAction(
                candidate = force,
                userMessage = if (label != null) "已切换音轨重试：$label" else "已切换其它音轨重试"
            )
        }
    }

    if (state.stage < AudioFallbackStage.MUTED) return MuteAction()
    return null
}

/** 把一跳落到 player 上，并推进阶梯状态 */
fun applyAudioFallback(player: Player, action: AudioFallbackAction, state: AudioFallbackState) {
    val builder = player.trackSelectionParameters.buildUpon()
    when (action) {
        is ChannelCapAction -> builder.setMaxAudioChannelCount(MAX_STEREO_CHANNELS)

        is ForceTrackAction -> {
            // 声道上限会把 override 指定的那条轨直接滤掉（两条轨都是 5.1 时就会互相打架）→ 这里显式放开
            builder.setMaxAudioChannelCount(Int.MAX_VALUE)
            builder.clearOverrides()
            builder.setOverrideForType(
                TrackSelectionOverride(action.candidate.trackGroup, listOf(action.candidate.trackIndex))
            )
        }

        is MuteAction -> builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
    }
    player.trackSelectionParameters = builder.build()
    state.advanceTo(action.stage)
}
