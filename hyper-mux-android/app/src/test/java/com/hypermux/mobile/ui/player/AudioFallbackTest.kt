package com.hypermux.mobile.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音轨回退阶梯的行为守卫（`AudioFallback.kt`）。
 *
 * 起因是实测片源 `…2160p.IQ.WEB-DL.H.265.DDP5.1.mkv`：HEVC 画面正常，但**默认音轨是 DDP 5.1**，
 * 设备没有杜比解码时就整片失败，而同一文件里那条百分百能解的 AAC 立体声轨完全轮不到。
 * 这里把「换轨而不是直接静音」变成可执行断言。
 */
class AudioFallbackTest {

    private fun audioTrack(mime: String, channels: Int, label: String? = null): Format =
        Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(channels)
            .setLabel(label)
            .build()

    private fun videoTrack(): Format =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).build()

    /** 把 TrackGroup 摊成候选（selectedFlags[i] = 这条轨当前是否被选中） */
    private fun candidatesOf(group: TrackGroup, vararg selectedFlags: Boolean): List<AudioTrackCandidate> =
        (0 until group.length).map { i ->
            val f = group.getFormat(i)
            AudioTrackCandidate(
                trackGroup = group,
                trackIndex = i,
                mimeType = f.sampleMimeType,
                channelCount = f.channelCount,
                label = f.label,
                isSelected = selectedFlags[i]
            )
        }

    /** 走完一跳：像 PlayerScreen 那样记 mime + 推进阶梯，方便看后续跳往哪走 */
    private fun stepForward(state: AudioFallbackState, action: AudioFallbackAction, failedMime: String?) {
        failedMime?.let { state.triedMimes += it }
        state.advanceTo(action.stage)
    }

    /** 实测片源的形状：默认轨 DDP 5.1 + 备选 AAC 立体声 → 应该先收声道上限，而不是静音 */
    @Test
    fun surroundPlusStereoFallsBackToChannelCap() {
        val group = TrackGroup(
            audioTrack(MimeTypes.AUDIO_E_AC3, 6, "国语·杜比环绕声"),
            audioTrack(MimeTypes.AUDIO_AAC, 2, "国语·立体音效")
        )
        val state = AudioFallbackState()
        val action = planAudioFallback(candidatesOf(group, true, false), MimeTypes.AUDIO_E_AC3, state)

        assertEquals(AudioFallbackStage.CHANNEL_CAP, action?.stage)
    }

    /** 只有一条环绕声轨（DTS 单轨）→ 不该白跑一趟声道上限，直接静音保画面 */
    @Test
    fun singleSurroundTrackGoesStraightToMute() {
        val group = TrackGroup(audioTrack(MimeTypes.AUDIO_DTS, 6, "DTS 5.1"))
        val state = AudioFallbackState()
        val action = planAudioFallback(candidatesOf(group, true), MimeTypes.AUDIO_DTS, state)

        assertEquals(AudioFallbackStage.MUTED, action?.stage)
    }

    /** 两条都是 5.1：声道上限无路可走 → 直接指定另一条（rank 更高的那条交给 FFmpeg 渲染器解） */
    @Test
    fun twoSurroundTracksForceTheAlternative() {
        val group = TrackGroup(
            audioTrack(MimeTypes.AUDIO_E_AC3, 6, "杜比"),
            audioTrack(MimeTypes.AUDIO_DTS_HD, 6, "DTS-HD")
        )
        val state = AudioFallbackState()
        val action = planAudioFallback(candidatesOf(group, true, false), MimeTypes.AUDIO_E_AC3, state)

        assertEquals(AudioFallbackStage.FORCE_TRACK, action?.stage)
    }

    /** 阶梯单调：CAP → FORCE → MUTE，且耗尽后不再给下一步（防 prepare 打转） */
    @Test
    fun ladderAdvancesMonotonicallyAndStopsWhenExhausted() {
        val group = TrackGroup(
            audioTrack(MimeTypes.AUDIO_E_AC3, 6),
            audioTrack(MimeTypes.AUDIO_AAC, 2),
            audioTrack(MimeTypes.AUDIO_DTS, 6)
        )
        val state = AudioFallbackState()
        val candidates = candidatesOf(group, true, false, false)

        val first = planAudioFallback(candidates, MimeTypes.AUDIO_E_AC3, state)
        assertEquals(AudioFallbackStage.CHANNEL_CAP, first?.stage)
        stepForward(state, first!!, MimeTypes.AUDIO_E_AC3)

        // 立体声轨也试过了（比如 NextLib 的 swr_init 那个坑）→ 下一跳不许退回 CAP
        val second = planAudioFallback(candidates, MimeTypes.AUDIO_AAC, state)
        assertEquals(AudioFallbackStage.FORCE_TRACK, second?.stage)
        stepForward(state, second!!, MimeTypes.AUDIO_AAC)

        val third = planAudioFallback(candidates, MimeTypes.AUDIO_DTS, state)
        assertEquals(AudioFallbackStage.MUTED, third?.stage)
        stepForward(state, third!!, MimeTypes.AUDIO_DTS)

        assertTrue(state.exhausted)
        assertTrue(state.isMuted)
        assertNull(planAudioFallback(candidates, MimeTypes.AUDIO_DTS, state))
    }

    /** 已经失败过的 mime 不能再被当成"另一条轨"重试一遍 */
    @Test
    fun triedMimesAreNeverRetried() {
        val group = TrackGroup(
            audioTrack(MimeTypes.AUDIO_E_AC3, 6),
            audioTrack(MimeTypes.AUDIO_AAC, 2)
        )
        val state = AudioFallbackState()
        state.advanceTo(AudioFallbackStage.CHANNEL_CAP)
        state.triedMimes += MimeTypes.AUDIO_AAC
        state.triedMimes += MimeTypes.AUDIO_E_AC3

        val action = planAudioFallback(candidatesOf(group, true, false), MimeTypes.AUDIO_AAC, state)
        assertEquals(AudioFallbackStage.MUTED, action?.stage)
    }

    /** 容器没声明声道数（C.LENGTH_UNSET）时按"可能立体声"处理，否则 DefaultTrackSelector 其实能选它却被我们滤掉 */
    @Test
    fun unknownChannelCountCountsAsStereo() {
        val group = TrackGroup(
            audioTrack(MimeTypes.AUDIO_E_AC3, 6),
            audioTrack(MimeTypes.AUDIO_OPUS, C.LENGTH_UNSET)
        )
        val state = AudioFallbackState()
        val action = planAudioFallback(candidatesOf(group, true, false), MimeTypes.AUDIO_E_AC3, state)

        assertEquals(AudioFallbackStage.CHANNEL_CAP, action?.stage)
    }

    /** 纯 AAC 片源不该触发任何一跳 */
    @Test
    fun singleSafeTrackHasNothingToFallBackToButMute() {
        val group = TrackGroup(audioTrack(MimeTypes.AUDIO_AAC, 2))
        val state = AudioFallbackState()
        val action = planAudioFallback(candidatesOf(group, true), MimeTypes.AUDIO_AAC, state)

        // 只有这一条轨：换无可换 → 静音保画面（与旧行为一致，且只此一跳）
        assertEquals(AudioFallbackStage.MUTED, action?.stage)
    }

    /** 适配器只收音轨组，并保住 TrackGroup 实例 + 组内 index（override 要靠这两样） */
    @Test
    fun adapterExtractsAudioTracksOnly() {
        val audioGroup = TrackGroup(
            audioTrack(MimeTypes.AUDIO_E_AC3, 6, "杜比"),
            audioTrack(MimeTypes.AUDIO_AAC, 2, "立体声")
        )
        val videoGroup = TrackGroup(videoTrack())
        val tracks = Tracks(
            listOf(
                Tracks.Group(videoGroup, true, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                Tracks.Group(audioGroup, true, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false))
            )
        )

        val candidates = audioTrackCandidates(tracks)

        assertEquals(2, candidates.size)
        assertSame(audioGroup, candidates[0].trackGroup)
        assertEquals(0, candidates[0].trackIndex)
        assertTrue(candidates[0].isSelected)
        assertEquals(1, candidates[1].trackIndex)
        assertFalse(candidates[1].isSelected)
        assertEquals("立体声", candidates[1].label)
    }

    /** 换解码档位重建 player 时，已经生效的声道上限/禁音要带过去；没动过阶梯就原样返回 */
    @Test
    fun restoreOnCarriesAppliedConstraintsToRebuiltPlayer() {
        val untouched = AudioFallbackState().restoreOn(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT)
        assertSame(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, untouched)

        val capped = AudioFallbackState()
        capped.advanceTo(AudioFallbackStage.CHANNEL_CAP)
        val cappedParams = capped.restoreOn(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT)
        assertEquals(2, cappedParams.maxAudioChannelCount)
        assertFalse(cappedParams.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))

        val muted = AudioFallbackState()
        muted.advanceTo(AudioFallbackStage.MUTED)
        val mutedParams = muted.restoreOn(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT)
        assertTrue(mutedParams.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
    }

    /** 换集复位：阶梯与 triedMimes 都归零，下一集重新从头判 */
    @Test
    fun resetClearsLadderAndTriedMimes() {
        val state = AudioFallbackState()
        state.advanceTo(AudioFallbackStage.MUTED)
        state.triedMimes += MimeTypes.AUDIO_DTS
        state.reset()

        assertEquals(AudioFallbackStage.NONE, state.stage)
        assertEquals(0, state.attempts)
        assertTrue(state.triedMimes.isEmpty())
        assertFalse(state.exhausted)
    }
}
