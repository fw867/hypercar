package io.github.hypercar.settings

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VideoEncodingPlanTest {
    @Test fun bmwUltrawideKeepsItsViewportRatio() {
        val plan = VideoEncodingPlan.fit(2560, 744)
        assertEquals(1920, plan.width)
        assertEquals(558, plan.height)
        assertEquals(30, plan.fps)
        assertEquals(180, plan.densityDpi)
    }

    @Test fun manualSixtyFpsKeepsResolutionAndReachesTheEncoderPlan() {
        val defaultPlan = VideoEncodingPlan.fit(2732, 794)
        val sixtyPlan = VideoEncodingPlan.fit(2732, 794, 60)
        assertEquals(30, defaultPlan.fps)
        assertEquals(defaultPlan.width, sixtyPlan.width)
        assertEquals(defaultPlan.height, sixtyPlan.height)
        assertEquals(60, sixtyPlan.fps)
        assertEquals(60, VideoEncodingPlan.fit(2732, 794, 120).fps)
    }

    @Test fun commonAndOversizedViewportsStayWithinTheEncodingBudget() {
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 1920 to 720,
            2560 to 744, 2732 to 794, 3840 to 1116, 7680 to 4320, 721 to 1281)) {
            val plan = VideoEncodingPlan.fit(w, h, 60)
            assertTrue("$w x $h: $plan", plan.width <= 1920 && plan.height <= 1080)
            assertTrue(plan.width * plan.height <= 1_100_000)
            assertEquals(0, plan.width % 2)
            assertEquals(0, plan.height % 2)
            // Even-pixel alignment may change either edge by less than two pixels.
            assertTrue(abs(plan.width.toDouble() / plan.height - w.toDouble() / h) < 0.015)
        }
    }

    @Test fun smallViewportIsNotUpscaledAndLowFrameRateIsRetained() {
        val plan = VideoEncodingPlan.fit(1280, 720, 24)
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertEquals(24, plan.fps)
    }
}
