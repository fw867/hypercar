package io.github.hypercar.bluetooth

import org.junit.Assert.*
import org.junit.Test

class ReconnectDiscoverabilityPolicyTest {
    @Test fun waitsForNetworkAndServicesThenOnlyAttemptsOnceAcrossWaitRounds() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.shouldAttempt(true, null, true, false, 0))
        assertFalse(p.shouldAttempt(true, "car", false, false, 10_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 12_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 16_999))
        assertTrue(p.shouldAttempt(true, "car", true, false, 17_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 317_000))
        assertFalse(p.shouldAttempt(true, "new network", true, false, 400_000))
    }

    @Test fun networkChangesAndDisablingResetTheGracePeriod() {
        val p = ReconnectDiscoverabilityPolicy()
        p.shouldAttempt(true, "a", true, false, 0)
        assertFalse(p.shouldAttempt(true, "b", true, false, 4_000))
        assertFalse(p.shouldAttempt(false, "b", true, false, 9_000))
        assertFalse(p.shouldAttempt(true, "b", true, false, 10_000))
        assertTrue(p.shouldAttempt(true, "b", true, false, 15_000))
    }

    @Test fun aSessionStartingDuringGraceCancelsTheOpportunityPermanently() {
        val p = ReconnectDiscoverabilityPolicy()
        p.shouldAttempt(true, "a", true, false, 0)
        assertFalse(p.shouldAttempt(true, "a", true, true, 4_000))
        assertFalse(p.shouldAttempt(true, "a", true, false, 60_000))
    }
}
