package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class KnownWifiActivationRetryTest {
    private val car = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"

    @Test fun failedKnownWifiActivationCanRetryWithoutAnyInvitation() {
        val stable = ProactiveWifiSessionDelay()
        val retry = KnownWifiActivationRetry()
        assertFalse(stable.ready(true, "121", 0))
        assertTrue(stable.ready(true, "121", 10_000))
        assertEquals(10_000L, retry.failed("121", car, 10_000))
        assertFalse(retry.allowsRetry(true, false, "121", car, 19_999))
        assertTrue(retry.allowsRetry(true, false, "121", car, 20_000))
    }

    @Test fun repeatedFailuresBackOffButNeverLatchTrustedNetworkIntoInviteOnly() {
        val retry = KnownWifiActivationRetry()
        var now = 0L
        for (delay in listOf(10_000L, 20_000L, 40_000L) + List(20) { 60_000L }) {
            assertEquals(delay, retry.failed("121", car, now))
            assertFalse(retry.allowsRetry(true, false, "121", car, now + delay - 1))
            now += delay
            assertTrue(retry.allowsRetry(true, false, "121", car, now))
        }
    }

    @Test fun oldEndpointCannotCrossReceiverOrNetworkEvenWhenBothCarsAreKnown() {
        val retry = KnownWifiActivationRetry()
        retry.failed("121", car, 0)
        assertFalse(retry.ready("122", car, 100_000))
        assertFalse(retry.ready("121", other, 100_000))
        // A newly verified network may start a fresh discovery, but cannot reuse the old hint.
        assertTrue(retry.allowsRetry(true, false, "122", car, 100_000))
        assertEquals(10_000L, retry.failed("122", car, 100_000))
        assertFalse(retry.ready("122", car, 109_999))
        assertTrue(retry.ready("122", car, 110_000))
    }

    @Test fun idlePauseStillBlocksRetriesAfterBackoffHasExpired() {
        val retry = KnownWifiActivationRetry()
        retry.failed("121", car, 0)
        assertFalse(retry.allowsRetry(true, true, "121", car, 600_000))
        assertTrue(retry.allowsRetry(true, false, "121", car, 600_000))
    }

    @Test fun settingOffMissingCredentialUnboundWifiOrDifferentActivePeerCannotBypassInviteOnly() {
        val retry = KnownWifiActivationRetry()
        retry.failed("121", car, 0)
        for (eligible in listOf(
            ProactiveWifiSessionPolicy.allows(false, car, null, true),
            ProactiveWifiSessionPolicy.allows(true, null, null, true),
            ProactiveWifiSessionPolicy.allows(true, car, null, false),
            ProactiveWifiSessionPolicy.allows(true, car, other, true),
        )) assertFalse(retry.allowsRetry(eligible, false, "121", car, 100_000))
    }

    @Test fun invalidScopeCannotBecomeAnAutomaticRetry() {
        val retry = KnownWifiActivationRetry()
        assertNull(retry.failed(null, car, 0))
        assertFalse(retry.allowsRetry(true, false, null, car, 100_000))
        assertNull(retry.failed("121", "../../other", 0))
        assertFalse(retry.allowsRetry(true, false, "121", "../../other", 100_000))
    }

    @Test fun macCaseDoesNotResetBackoffAndClearingStartsFresh() {
        val retry = KnownWifiActivationRetry()
        retry.failed("121", car, 0)
        assertEquals(20_000L, retry.failed("121", car.lowercase(), 10_000))
        retry.clear()
        assertFalse(retry.ready("121", car, 100_000))
        assertEquals(10_000L, retry.failed("121", car, 100_000))
    }
}
