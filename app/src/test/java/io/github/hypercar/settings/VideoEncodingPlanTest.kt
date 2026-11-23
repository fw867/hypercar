package io.github.hypercar.settings

import org.junit.Assert.*
import org.junit.Test

class VideoEncodingPlanTest {
    @Test fun receiverLimitAppliesToEveryEncoderCandidateWithoutRaisingOrReplacingUserPreference() {
        val request = VideoEncodingPlan.fit(2736, 800, 60)
        for (limit in listOf(1, 12, 24, 30, 45, 60, 120)) {
            val capped = request.withReceiverMaxFps(limit)
            assertEquals(minOf(limit, 60), capped.fps)
            assertTrue(capped.encoderCandidates().all { it.fps <= limit })
        }
        assertEquals(request, request.withReceiverMaxFps(null))
        assertEquals(request, request.withReceiverMaxFps(0))
        assertEquals(60, request.fps)
    }

    @Test fun rejectedBmwNativeModeCanFallBackToAvcSizeAndRate() {
        val requested = VideoEncodingPlan.fit(2736, 800, 60)
        val candidates = requested.encoderCandidates()
        assertEquals(requested, candidates.first())
        assertEquals(requested.copy(fps = 30), candidates[1])
        val accepted = candidates.first { it.width <= 1920 && it.fps <= 30 }
        assertEquals(1920, accepted.width)
        assertEquals(560, accepted.height)
        assertEquals(30, accepted.fps)
        assertTrue(accepted.densityDpi < requested.densityDpi)
    }

    @Test fun fallbackNeverUpscalesOrRaisesRequestedFps() {
        for (plan in listOf(VideoEncodingPlan.fit(721, 1281, 24), VideoEncodingPlan.fit(1280, 720, 30))) {
            val modes = plan.encoderCandidates()
            assertEquals(modes.distinct(), modes)
            assertTrue(modes.all { it.width <= plan.width && it.height <= plan.height && it.fps == plan.fps })
            assertTrue(modes.any { it.width < plan.width && it.height < plan.height })
        }
    }

    @Test fun bmwUltrawideUsesNativePixelsAndDefaultsToSixtyFps() {
        val plan = VideoEncodingPlan.fit(2736, 800)
        assertEquals(2736, plan.width)
        assertEquals(800, plan.height)
        assertEquals(60, plan.fps)
        val safeArea = VideoEncodingPlan.fit(2732, 794)
        assertEquals(2732, safeArea.width)
        assertEquals(794, safeArea.height)
    }

    @Test fun manualLowerFrameRateKeepsNativeResolution() {
        val defaultPlan = VideoEncodingPlan.fit(2732, 794)
        val thirtyPlan = VideoEncodingPlan.fit(2732, 794, 30)
        assertEquals(defaultPlan.width, thirtyPlan.width)
        assertEquals(defaultPlan.height, thirtyPlan.height)
        assertEquals(30, thirtyPlan.fps)
        assertEquals(60, VideoEncodingPlan.fit(2732, 794, 120).fps)
    }

    @Test fun commonAndLargeViewportsAreNotDownscaled() {
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 1920 to 720,
            2560 to 744, 2732 to 794, 3840 to 1116, 7680 to 4320, 721 to 1281)) {
            val plan = VideoEncodingPlan.fit(w, h)
            assertEquals(w / 2 * 2, plan.width)
            assertEquals(h / 2 * 2, plan.height)
            assertEquals(60, plan.fps)
        }
    }

    @Test fun smallViewportIsNotUpscaledAndLowFrameRateIsRetained() {
        val plan = VideoEncodingPlan.fit(1280, 720, 24)
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertEquals(24, plan.fps)
    }
}
