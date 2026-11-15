package io.github.hypercar.bluetooth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverableWindowTest {
    @Test fun cancellationAlwaysFinishesTokenCleanup() = runTest {
        var started = false
        var cleaned = false
        val job = launch {
            runDiscoverableWindow(start = { started = true; true }, keepOpen = { true }, stop = {
                delay(10)
                cleaned = true
            })
        }
        runCurrent()
        assertTrue(started)
        job.cancelAndJoin()
        assertTrue(cleaned)
    }

    @Test fun windowExpiresAfterThirtySecondsEvenWithoutSession() = runTest {
        var stoppedAt = -1L
        val job = launch {
            runDiscoverableWindow(start = { true }, keepOpen = { true }, stop = {
                stoppedAt = testScheduler.currentTime
            })
        }
        runCurrent()
        advanceTimeBy(29_999)
        assertTrue(job.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(30_000L, stoppedAt)
    }

    @Test fun losingReadinessClosesWindowBeforeTimeout() = runTest {
        var ready = true
        var stopped = false
        val job = launch {
            runDiscoverableWindow(start = { true }, keepOpen = { ready }, stop = { stopped = true })
        }
        runCurrent()
        ready = false
        advanceTimeBy(250)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(stopped)
    }

    @Test fun cancellationDuringStartStillCleansTheToken() = runTest {
        var cleaned = false
        val job = launch {
            runDiscoverableWindow(start = { cancel(); true }, keepOpen = { true }, stop = { cleaned = true })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(cleaned)
    }
}
