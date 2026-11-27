package io.github.hypercar.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WifiReconnectSignalsTest {
    @Test fun readyCarCanWakeDuringTheSettleWindow() = runTest {
        var carReady = false
        val wake = CompletableDeferred<Long>()
        val checks = mutableListOf<Long>()
        var submissions = 0
        val signals = WifiReconnectSignals()
        backgroundScope.launch {
            signals.collect(tryReady = {
                if (carReady) {
                    submissions++
                    wake.complete(testScheduler.currentTime)
                }
                carReady
            }) { checks += testScheduler.currentTime }
        }
        signals.signal()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(120)
        carReady = true
        signals.signal()
        testScheduler.runCurrent()
        assertTrue(wake.isCompleted)
        assertEquals(120L, wake.await())
        signals.signal()
        testScheduler.runCurrent()
        assertEquals(1, submissions)
        testScheduler.advanceTimeBy(630)
        testScheduler.runCurrent()
        assertEquals(listOf(750L), checks)
    }

    @Test fun unconfirmedNetworkKeepsOriginalSettleDeadlineDespiteLaterSignals() = runTest {
        val checks = mutableListOf<Long>()
        val signals = WifiReconnectSignals()
        backgroundScope.launch { signals.collect { checks += testScheduler.currentTime } }
        signals.signal()
        testScheduler.advanceTimeBy(600)
        signals.signal()
        testScheduler.advanceTimeBy(149)
        assertEquals(emptyList<Long>(), checks)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(listOf(750L), checks)
    }

    @Test fun addressUpdateDuringTheFirstCheckTriggersOneMoreCheck() = runTest {
        val signals = WifiReconnectSignals()
        val firstCheck = CompletableDeferred<Unit>()
        val releaseFirstCheck = CompletableDeferred<Unit>()
        val nextCheck = CompletableDeferred<Unit>()
        var checks = 0
        val worker = backgroundScope.launch {
            signals.collect {
                checks++
                if (checks == 1) {
                    firstCheck.complete(Unit)
                    releaseFirstCheck.await()
                } else nextCheck.complete(Unit)
            }
        }
        signals.signal()
        firstCheck.await()
        repeat(3) { signals.signal() }
        releaseFirstCheck.complete(Unit)
        nextCheck.await()
        assertEquals(2, checks)
        worker.cancel()
    }
}
