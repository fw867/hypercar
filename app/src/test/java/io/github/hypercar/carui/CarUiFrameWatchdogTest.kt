package io.github.hypercar.carui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarUiFrameWatchdogTest {
    @Test fun failedRestartRetriesTwiceAfterCooldownEvenWhenSourceIsGone() {
        val watch = CarUiFrameWatchdog()
        watch.restartFinished(10_000, rebuilt = false)
        assertFalse(watch.shouldRetry(39_999))
        assertTrue(watch.shouldRetry(40_000))
        watch.restartFinished(41_000, rebuilt = false)
        assertFalse(watch.shouldRetry(70_999))
        assertTrue(watch.shouldRetry(71_000))
        watch.restartFinished(72_000, rebuilt = false)
        assertFalse(watch.shouldRetry(200_000))
        watch.restartFinished(201_000, rebuilt = true)
        assertFalse(watch.shouldRetry(300_000))
    }
    @Test fun requiresLiveProgressBeforeDetectingAnEightSecondStall() {
        val watch = CarUiFrameWatchdog()
        assertFalse(watch.observe(0, true, 50, 50))
        assertFalse(watch.observe(10_000, true, 50, 51))
        assertFalse(watch.observe(20_000, true, 50, 51))
        assertFalse(watch.observe(21_000, true, 60, 60))
        assertFalse(watch.observe(28_999, true, 60, 60))
        assertTrue(watch.observe(29_000, true, 60, 60))
    }

    @Test fun resetsDuringOwnershipOrTcpLossAndIgnoresFramesStillBeingProduced() {
        val watch = CarUiFrameWatchdog()
        watch.observe(0, true, 0, 0)
        watch.observe(1_000, true, 10, 10)
        assertFalse(watch.observe(10_000, true, 11, 10))
        assertFalse(watch.observe(11_000, false, 11, 10))
        assertFalse(watch.observe(30_000, true, 11, 10))
        assertFalse(watch.observe(45_000, true, 11, 10))
        watch.observe(46_000, true, 12, 12)
        assertTrue(watch.observe(54_000, true, 12, 12))
    }

    @Test fun restartRequiresNewFramesAndHonorsCooldownEvenAfterPlaybackResumes() {
        val watch = CarUiFrameWatchdog()
        watch.observe(0, true, 0, 0)
        watch.observe(1_000, true, 10, 10)
        assertTrue(watch.observe(9_000, true, 10, 10))
        watch.restarted(10_000)
        assertFalse(watch.observe(30_000, true, 10, 10))
        watch.observe(31_000, true, 11, 11)
        assertFalse(watch.observe(39_999, true, 11, 11))
        assertTrue(watch.observe(40_000, true, 11, 11))
        watch.restarted(41_000)
        assertFalse(watch.observe(99_000, true, 11, 11))
    }
}
