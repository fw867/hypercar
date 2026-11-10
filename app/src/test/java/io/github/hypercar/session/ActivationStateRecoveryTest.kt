package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class ActivationStateRecoveryTest {
    private val car = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"
    private val signals = CarActivationWakeEvidence()
    private val retry = KnownWifiActivationRetry()

    private fun fail(now: Long) {
        retry.failed("170", car, now, true, signals.snapshot(car)?.connectedRevision ?: 0L)
    }
    private fun recover(now: Long, eligible: Boolean = true, paused: Boolean = false,
                        network: String = "170", peer: String = car) =
        retry.recoverAfterStateChange(eligible, paused, network, peer, now, signals.snapshot(peer))

    @Test fun elapsedTimeAloneOrPreFailureConnectionDoesNotReopenActivation() {
        signals.observe(car, true, 0)
        fail(1_000)
        assertNull(recover(600_000))
        signals.observe(car, true, 650_000) // duplicate, not a real new connection
        assertNull(recover(700_000))
        signals.observe(car, false, 710_000) // old connection retiring is not a new wake either
        assertNull(recover(750_000))
        assertFalse(retry.ready("170", car, 750_000))
    }

    @Test fun newConnectionRequiresBothCooldownAndQuietPeriod() {
        fail(0)
        signals.observe(car, true, 1_000)
        assertNull(recover(29_999))
        assertEquals(KnownWifiActivationRetry.StateRetry(1, 1), recover(30_000))
        assertTrue(retry.ready("170", car, 30_000))
        assertNull(recover(40_000)) // a reservation is consumed only once
    }

    @Test fun aclFlappingRestartsQuietPeriodAndShortLinkMayRetireToWifi() {
        fail(0)
        for (at in listOf(35_000L, 40_000L, 45_000L)) {
            signals.observe(car, true, at)
            signals.observe(car, false, at + 500)
            assertNull(recover(at + 999))
        }
        assertNull(recover(55_499))
        assertEquals(KnownWifiActivationRetry.StateRetry(1, 1), recover(55_500))
    }

    @Test fun sameEvidenceCannotRecoverAnotherFailedActivation() {
        fail(0)
        signals.observe(car, true, 1_000)
        assertNotNull(recover(30_000))
        fail(31_000)
        assertNull(recover(100_000))
        signals.observe(car, false, 101_000)
        assertNull(recover(120_000))
        signals.observe(car, true, 121_000)
        assertEquals(KnownWifiActivationRetry.StateRetry(2, 2), recover(131_000))
    }

    @Test fun repeatedWakeCyclesNeverReplenishTwoStateRecoveries() {
        var now = 0L
        for (i in 1..2) {
            fail(now)
            signals.observe(car, true, now + 1_000)
            signals.observe(car, false, now + 2_000)
            assertEquals(KnownWifiActivationRetry.StateRetry(i, i), recover(now + 30_000))
            now += 31_000
        }
        fail(now)
        repeat(6) { i ->
            signals.observe(car, true, now + 1_000 + i * 50_000)
            signals.observe(car, false, now + 2_000 + i * 50_000)
            assertNull(recover(now + 40_000 + i * 50_000))
        }
        assertFalse(retry.ready("170", car, now + 1_000_000))
    }

    @Test fun stateRecoveryUsesExistingFiveRetryBudget() {
        repeat(4) { retry.failed("170", car, it * 60_000L) }
        fail(250_000)
        signals.observe(car, true, 251_000)
        assertEquals(KnownWifiActivationRetry.StateRetry(1, 5), recover(280_000))
        fail(281_000)
        signals.observe(car, false, 282_000)
        signals.observe(car, true, 283_000)
        assertNull(recover(400_000))
        assertFalse(retry.ready("170", car, 400_000))
    }

    @Test fun settingsPauseReceiverAndNetworkGatesCannotBeBypassed() {
        fail(0)
        signals.observe(car, true, 1_000)
        signals.observe(other, true, 1_000)
        assertNull(recover(30_000, eligible = false))
        assertNull(recover(30_000, paused = true))
        assertNull(recover(30_000, network = "171"))
        assertNull(recover(30_000, peer = other))
        assertNotNull(recover(30_000)) // denied checks spent no budget
    }

    @Test fun newManualRoundCanStartFreshButDoesNotReuseBlockedEndpoint() {
        fail(0)
        signals.observe(car, true, 1_000)
        assertNotNull(recover(30_000))
        retry.clear()
        assertFalse(retry.ready("170", car, 40_000))
        assertTrue(retry.allowsRetry(true, false, "170", car, 40_000))
        fail(41_000)
        assertNull(recover(100_000)) // the current connection is part of the new failure baseline
    }

    @Test fun transportFailureKeepsOrdinaryBackoffInsteadOfUsingWakeRecovery() {
        assertEquals(10_000L, retry.failed("170", car, 0))
        signals.observe(car, true, 1_000)
        assertNull(recover(30_000))
        assertTrue(retry.ready("170", car, 10_000))
    }

    @Test fun recordedBmwBurstDoesNotRecoverUntilLastDisconnectSettles() {
        // Relative to 18:14:21.169 failure in the 2026-11-09 log; no NFC timestamp is inferred.
        fail(0)
        val connections = listOf(39_726L to 40_390L, 43_882L to 44_361L,
            45_457L to 46_049L, 47_022L to 47_507L, 50_228L to 50_822L,
            51_824L to 52_309L, 53_436L to 54_090L, 55_026L to 55_525L,
            60_480L to 61_017L)
        for ((connected, disconnected) in connections) {
            signals.observe(car, true, connected)
            assertNull(recover(connected))
            signals.observe(car, false, disconnected)
            assertNull(recover(disconnected))
        }
        assertNull(recover(71_016))
        assertEquals(KnownWifiActivationRetry.StateRetry(1, 1), recover(71_017))
    }

    @Test fun uninvitedRecordCloseDoesNotReplayAfterAclSettles() {
        assertTrue(suppressUninvitedPortReplay(
            proactive = true, fromInvite = false, ctrlInvited = false, iap2Started = false,
            knownWifi = true, port = 7000, closedAfterRecord = true))
        assertFalse(suppressUninvitedPortReplay(
            proactive = true, fromInvite = false, ctrlInvited = true, iap2Started = false,
            knownWifi = true, port = 7000, closedAfterRecord = true))
        assertFalse(suppressUninvitedPortReplay(
            proactive = true, fromInvite = false, ctrlInvited = false, iap2Started = true,
            knownWifi = true, port = 7000, closedAfterRecord = true))
        assertFalse(suppressUninvitedPortReplay(
            proactive = true, fromInvite = false, ctrlInvited = false, iap2Started = false,
            knownWifi = true, port = 7100, closedAfterRecord = true))
        assertFalse(suppressUninvitedPortReplay(
            proactive = true, fromInvite = false, ctrlInvited = false, iap2Started = false,
            knownWifi = false, port = 7000, closedAfterRecord = true))
        retry.suppressUninvitedReplay("170", car)
        signals.observe(car, true, 1_000)
        signals.observe(car, false, 1_500)
        assertNull(recover(60_000))
        assertFalse(retry.ready("170", car, 60_000))
        assertFalse(retry.allowsRetry(true, false, "170", car, 60_000))
        assertNull(retry.failed("170", car, 60_000))
        assertNull(recover(120_000))
        retry.clear()
        fail(130_000)
        signals.observe(car, true, 131_000)
        assertEquals(KnownWifiActivationRetry.StateRetry(1, 1), recover(160_000))
    }
}
