package io.github.hypercar.settings

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** General preflight only. Each actual producer still validates its real size/rate and configure/start. */
internal object VideoHardwareCapabilities {
    val hevcEncoderAvailable: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.isHardwareAccelerated && !info.isSoftwareOnly &&
                    info.supportedTypes.any { it.equals("video/hevc", true) } && runCatching {
                        val caps = info.getCapabilitiesForType("video/hevc")
                        caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                            caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
                    }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }
}
