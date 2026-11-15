package io.github.hypercar.settings

enum class VideoCodec(val wireId: Int, val mime: String) {
    H264(0, "video/avc"), HEVC(1, "video/hevc")
}

object VideoCodecPolicy {
    fun storedPreference(value: String?): String = if (value == null || value == "hevc") "hevc" else "h264"
    fun forHardware(preference: String, hardwareHevc: Boolean): String =
        if (preference == "hevc" && hardwareHevc) "hevc" else "h264"
    fun preferred(preference: String, receiverHevcAllowed: Boolean): VideoCodec =
        if (preference == "hevc" && receiverHevcAllowed) VideoCodec.HEVC else VideoCodec.H264

    fun start(preferred: VideoCodec, startEncoder: (VideoCodec) -> Unit): VideoCodec {
        try { startEncoder(preferred); return preferred }
        catch (error: Exception) {
            if (preferred != VideoCodec.HEVC) throw error
            startEncoder(VideoCodec.H264)
            return VideoCodec.H264
        }
    }
}

/** Receiver permission belongs to exactly one connection attempt, never preferences. */
class VideoSessionCapabilities {
    private var attempt: Long? = null
    @Volatile var hevcAllowed: Boolean = false
        private set
    @Synchronized fun begin(id: Long) { attempt = id; hevcAllowed = false }
    @Synchronized fun accept(id: Long, allowed: Boolean): Boolean {
        if (attempt != id) return false
        hevcAllowed = allowed
        return true
    }
    @Synchronized fun clear() { attempt = null; hevcAllowed = false }
}
