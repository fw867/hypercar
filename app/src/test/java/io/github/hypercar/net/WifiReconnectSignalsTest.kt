package io.github.hypercar.net

import io.github.hypercar.session.ReconnectBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WifiReconnectSignalsTest {
    @Test fun unrelatedChangesResampleButDoNotRenewTheSameReadyNetwork() {
        val signals = WifiReconnectSignals()
        val ready = setOf("192.168.1.2/24")
        signals.available(41)
        val first = checkNotNull(signals.claimExtraWake("bmw:a", 41, ready))
        signals.available(42)
        signals.signal()
        signals.lost(42)
        assertEquals(null, signals.claimExtraWake("bmw:a", 41, ready))
        assertTrue(signals.isCurrent(first))
    }

    @Test fun onlyChangedUsableEvidenceOrOwnReassociationCanRetry() {
        val signals = WifiReconnectSignals()
        signals.available(41)
        assertEquals(null, signals.claimExtraWake("bmw:a", 41, emptySet()))
        val first = checkNotNull(signals.claimExtraWake("bmw:a", 41, setOf("192.168.1.2/24")))
        val second = checkNotNull(signals.claimExtraWake("bmw:a", 41, setOf("192.168.1.3/24")))
        assertEquals(null, signals.claimExtraWake("bmw:a", 41, setOf("192.168.1.4/24")))
        signals.lost(41)
        assertFalse(signals.isCurrent(first))
        assertFalse(signals.isCurrent(second))
        assertEquals(null, signals.claimExtraWake("bmw:a", 41, setOf("192.168.1.2/24")))
        signals.available(41)
        assertFalse(signals.isCurrent(first))
        checkNotNull(signals.claimExtraWake("bmw:a", 41, setOf("192.168.1.2/24")))
    }

    @Test fun lostClaimCannotSettleAfterTheSameNetworkHandleBecomesAvailableAgain() {
        val signals = WifiReconnectSignals()
        val ready = setOf("192.168.1.2/24")
        signals.available(41)
        val first = checkNotNull(signals.claimExtraWake("bmw:a", 41, ready))
        signals.lost(41)
        signals.available(41)
        var settlements = 0
        assertFalse(signals.commitIfCurrent(first) { settlements++; true })
        assertEquals(0, settlements)
        val current = checkNotNull(signals.claimExtraWake("bmw:a", 41, ready))
        assertTrue(signals.commitIfCurrent(current) { settlements++; true })
        assertEquals(1, settlements)
    }

    @Test fun sameAddressRetryNeedsTheLatestClaimAndOneClearedLease() {
        val signals = WifiReconnectSignals()
        val ready = setOf("192.168.1.2/24")
        signals.available(41)
        signals.available(42)
        val first = checkNotNull(signals.claimExtraWake("bmw:a", 41, ready))
        val unrelated = checkNotNull(signals.claimExtraWake("bmw:a", 42, ready))
        assertEquals(null, signals.claimExtraWake("bmw:a", 41, ready, releasedLeaseRetry = unrelated))
        // A separate receiver may make its own first attempt, but cannot reuse another receiver's ticket.
        checkNotNull(signals.claimExtraWake("bmw:b", 41, ready))
        assertEquals(null, signals.claimExtraWake("bmw:b", 41, ready, releasedLeaseRetry = first))
        checkNotNull(signals.claimExtraWake("bmw:a", 41, ready, releasedLeaseRetry = first))
        assertEquals(null, signals.claimExtraWake("bmw:a", 41, ready, releasedLeaseRetry = first))
    }

    @Test fun rebuildingMonitorRevokesOldClaimsEvenIfHandleAndAddressMatch() {
        val old = WifiReconnectSignals()
        val replaced = WifiReconnectSignals()
        val ready = setOf("192.168.1.2/24")
        old.available(41)
        val obsolete = checkNotNull(old.claimExtraWake("bmw:a", 41, ready))
        old.retire()
        old.available(41) // Late callback from the unregistered observer.
        replaced.available(41)
        val current = checkNotNull(replaced.claimExtraWake("bmw:a", 41, ready))
        assertFalse(old.isCurrent(obsolete))
        assertFalse(replaced.isCurrent(obsolete))
        assertTrue(replaced.isCurrent(current))
    }

    @Test fun topologyChangeSeenDuringActiveSessionIsReplayedAtIdleOnlyOnce() = runTest {
        val signals = WifiReconnectSignals()
        var active = true
        var checks = 0
        backgroundScope.launch {
            signals.collect { if (!signals.deferWhile { active }) checks++ }
        }
        signals.lost(41)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(750)
        testScheduler.runCurrent()
        assertEquals(0, checks)
        active = false
        signals.replayForIdle()
        signals.replayForIdle()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(750)
        testScheduler.runCurrent()
        assertEquals(1, checks)
    }

    @Test fun verifiedBindCanSettleAReleasedClaimWithoutAnotherPassiveWake() {
        val signals = WifiReconnectSignals()
        val budget = ReconnectBudget()
        val receiver = "bmw_id85:a"
        val ready = setOf("192.168.1.2/24")
        repeat(ReconnectBudget.MAX_RETRIES) { budget.failed(receiver, true, it.toLong()) }
        budget.grantWifiWakeAfterProjectionSwitch()
        signals.available(41)
        val claim = checkNotNull(signals.claimExtraWake(receiver, 41, ready))
        val failedBind = budget.reserveWifiWake(receiver, 41) as ReconnectBudget.WifiWake.Extra
        assertTrue(budget.releaseWifiWake(failedBind))
        assertEquals(null, signals.claimExtraWake(receiver, 41, ready))

        assertTrue(signals.commitIfCurrent(claim) {
            val proven = budget.reserveWifiWake(receiver, 41) as ReconnectBudget.WifiWake.Extra
            budget.commitWifiWake(proven, 41)
        })
        assertFalse(budget.mayWake(receiver))
    }

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
