package io.github.hypercar.autowake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionWakePolicyTest {
    private val car = "9C:DF:03:5D:FB:90"

    @Test fun onlyAnExplicitTargetWithAutoConnectCanWake() {
        assertTrue(CompanionWakePolicy.accepts(true, car, car.lowercase()))
        assertFalse(CompanionWakePolicy.accepts(false, car, car))
        assertFalse(CompanionWakePolicy.accepts(true, null, car))
        assertFalse(CompanionWakePolicy.accepts(true, "", car))
        assertFalse(CompanionWakePolicy.accepts(true, car, null))
        assertFalse(CompanionWakePolicy.accepts(true, car, "00:11:22:33:44:55"))
    }

    @Test fun pendingOrStreamingSessionIsNeverReplaced() {
        assertFalse(CompanionWakePolicy.shouldTrigger(true, car, car, true, 30_000, null))
        assertTrue(CompanionWakePolicy.shouldTrigger(true, car, car, false, 1_000, null))
    }

    @Test fun repeatedPresenceEventsAreThrottledButLaterEntryCanWake() {
        assertFalse(CompanionWakePolicy.shouldTrigger(true, car, car, false, 15_999, 1_000))
        assertTrue(CompanionWakePolicy.shouldTrigger(true, car, car, false, 16_000, 1_000))
        assertFalse(CompanionWakePolicy.shouldTrigger(false, car, car, false, 40_000, 1_000))
    }
}
