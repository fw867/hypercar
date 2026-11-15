package io.github.hypercar.settings

import org.junit.Assert.*
import org.junit.Test

class VideoCodecPolicyTest {
    @Test fun hevcRequiresPreferenceAndCurrentReceiverPermission() {
        assertEquals(VideoCodec.H264, VideoCodecPolicy.preferred("h264", true))
        assertEquals(VideoCodec.H264, VideoCodecPolicy.preferred("hevc", false))
        assertEquals(VideoCodec.HEVC, VideoCodecPolicy.preferred("hevc", true))
        assertEquals(VideoCodec.H264, VideoCodecPolicy.preferred("unknown", true))
    }

    @Test fun hardwareFailureFallsBackAndReportsActualCodec() {
        val tried = mutableListOf<VideoCodec>()
        val actual = VideoCodecPolicy.start(VideoCodec.HEVC) {
            tried += it
            if (it == VideoCodec.HEVC) error("unsupported Surface/size/rate")
        }
        assertEquals(listOf(VideoCodec.HEVC, VideoCodec.H264), tried)
        assertEquals(VideoCodec.H264, actual)
    }

    @Test fun avcFailureIsNotHiddenOrRetriedAsHevc() {
        val tried = mutableListOf<VideoCodec>()
        assertThrows(IllegalStateException::class.java) {
            VideoCodecPolicy.start(VideoCodec.H264) { tried += it; error("no encoder") }
        }
        assertEquals(listOf(VideoCodec.H264), tried)
    }

    @Test fun capabilityIsResetAndStaleSessionCannotEnableHevc() {
        val state = VideoSessionCapabilities()
        state.begin(1)
        assertTrue(state.accept(1, true))
        assertTrue(state.hevcAllowed)
        state.begin(2)
        assertFalse(state.hevcAllowed)
        assertFalse(state.accept(1, true))
        assertFalse(state.hevcAllowed)
        assertTrue(state.accept(2, true))
        state.clear()
        assertFalse(state.hevcAllowed)
        assertFalse(state.accept(2, true))
    }
}
