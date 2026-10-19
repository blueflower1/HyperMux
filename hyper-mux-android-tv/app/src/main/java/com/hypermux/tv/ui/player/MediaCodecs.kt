package com.hypermux.tv.ui.player

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import android.media.MediaCodecInfo as PlatformCodecInfo

/**
 * 自定义解码器选择器：**只返回硬件解码器**，刻意把平台软解（c2.android.* / slow-cpu）排除在候选之外，
 * 由此形成本项目「硬解 > FFmpeg 软解 > 平台软解」的解码优先级：
 *
 *  - 有硬解 → 本 selector 返回硬解，MediaCodec 渲染器正常声明支持并优先使用；
 *  - 无硬解（模拟器、部分电视盒子、老设备上的 HEVC / AV1）→ 本 selector 返回**空列表**，
 *    MediaCodec（平台）渲染器因此声明「不支持该 mime」，播放器自动落到 NextLib 追加的
 *    FfmpegVideoRenderer / FfmpegAudioRenderer 做软解；平台软解只在 FFmpeg 也解不了时才由默认链路回退。
 *
 * 为什么不让平台软解进候选：`c2.android.hevc.decoder`、`c2.android.av1.decoder` 这类 variant=slow-cpu
 * 的软件解码器一旦出现在候选里，MediaCodec 渲染器就会认为「可解码」从而**先于 FFmpeg** 被选中，
 * 而 4K HEVC 上平台软解极慢、体验远差于 FFmpeg 软解。把它们剔除，等于把软解场景让给 FFmpeg。
 *
 * 实现方式：直接用平台 API 枚举解码器——
 *  - 只取解码器（`isEncoder == false`）且 `supportedTypes` 命中目标 mime（含 video/av01、video/hevc）；
 *  - 跳过别名解码器（API 29+ 的 alias）与非 secure 场景下名字带 `.secure` 的变体；
 *  - **只保留 `isHardwareAccelerated == true` 且非 `softwareOnly` 的解码器**（API<29 无这些属性时按名字前缀判断，
 *    见 [isHardwareAccelerated] / [isSoftwareOnly]）。
 */
object HardwareOnlyCodecSelector : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        // 本项目只做普通（非 DRM、非隧道）播放：加密/隧道场景交回默认实现，避免选到不满足要求的解码器
        if (requiresSecureDecoder || requiresTunnelingDecoder) {
            return defaultDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }

        val result = ArrayList<MediaCodecInfo>()
        val seenNames = HashSet<String>()
        try {
            for (platformInfo in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (platformInfo.isEncoder) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && platformInfo.isAlias) continue

                val name = platformInfo.name ?: continue
                if (!requiresSecureDecoder && name.endsWith(".secure")) continue

                val matchedMime = platformInfo.supportedTypes
                    ?.firstOrNull { it.equals(mimeType, ignoreCase = true) }
                    ?: continue

                // 只认硬解：硬解标记为假、或被判定为软件实现的一律跳过（软解让给 FFmpeg 扩展渲染器）
                val hardwareAccelerated = isHardwareAccelerated(platformInfo, name)
                val softwareOnly = isSoftwareOnly(platformInfo, name)
                if (!hardwareAccelerated || softwareOnly) continue

                val capabilities = try {
                    platformInfo.getCapabilitiesForType(matchedMime)
                } catch (t: Throwable) {
                    // 个别解码器查询能力会抛异常，放弃该能力的描述即可，不要因此丢掉候选
                    null
                }

                if (!seenNames.add(name)) continue
                result.add(
                    MediaCodecInfo.newInstance(
                        name,
                        mimeType,
                        matchedMime,
                        capabilities,
                        // 如实传入判定结果，保证 Media3 的排序与能力判断正确
                        /* hardwareAccelerated= */ hardwareAccelerated,
                        /* softwareOnly= */ softwareOnly,
                        /* vendor= */ isVendor(platformInfo),
                        /* forceDisableAdaptive= */ false,
                        /* forceSecure= */ false
                    )
                )
            }
        } catch (t: Throwable) {
            // 极端兜底：平台枚举整体失败时退回默认实现，至少保证不崩（正常路径不会走到这里）
            return defaultDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }

        // 无硬解时 result 为空 → 平台渲染器声明「不支持」→ 落到 NextLib 的 FFmpeg 渲染器
        // （注意：这里**不能**退回默认实现，否则会把平台软解重新塞进候选，破坏「FFmpeg 优先于平台软解」）
        return result
    }

    private fun defaultDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> = try {
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType, requiresSecureDecoder, requiresTunnelingDecoder
        )
    } catch (t: Throwable) {
        emptyList()
    }

    private fun isHardwareAccelerated(info: PlatformCodecInfo, name: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else !looksSoftwareOnly(name)

    private fun isSoftwareOnly(info: PlatformCodecInfo, name: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
        else looksSoftwareOnly(name)

    private fun isVendor(info: PlatformCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isVendor
        else false

    /** API < 29 没有 isSoftwareOnly()，按名字推断（与 Media3 内部策略一致的常见前缀） */
    private fun looksSoftwareOnly(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") ||
            n.startsWith("c2.android.") ||
            n.startsWith("c2.google.") ||
            n.startsWith("omx.ffmpeg.") ||
            n.startsWith("arc.") ||
            n.contains(".sw.")
    }
}

/**
 * 该解码器名是否为**软件解码器**（用于"正在使用软件解码，4K 可能卡顿"的一次性提示）。
 * 优先查平台解码器表拿 `isHardwareAccelerated`，查不到再按名字前缀兜底。
 */
fun isSoftwareDecoderName(decoderName: String?): Boolean {
    val name = decoderName ?: return false
    return try {
        val platformInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .firstOrNull { it.name == name }
        when {
            platformInfo == null -> looksSoftwareOnlyName(name)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> !platformInfo.isHardwareAccelerated
            else -> looksSoftwareOnlyName(name)
        }
    } catch (t: Throwable) {
        looksSoftwareOnlyName(name)
    }
}

private fun looksSoftwareOnlyName(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("omx.google.") ||
        n.startsWith("c2.android.") ||
        n.startsWith("c2.google.") ||
        n.startsWith("omx.ffmpeg.") ||
        n.startsWith("arc.") ||
        n.contains(".sw.")
}
