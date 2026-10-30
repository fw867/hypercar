package io.github.hypercar.settings

import org.junit.Assert.*
import org.junit.Test

class VideoEncodingPlanTest {
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
