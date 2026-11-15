package io.github.hypercar.bluetooth

import org.junit.Assert.*
import org.junit.Test

class ReconnectDiscoverabilityPolicyTest {
    @Test fun coldBmwPreparationDoesNotNeedWifiAndSurvivesWifiArrival() {
        val p = ReconnectDiscoverabilityPolicy()
        val prep = ReconnectDiscoverability.bmwPreparationKey("bmw_id85", "AA:BB:CC:DD:EE:FF", true, true, true)
        assertNotNull(prep)
        assertFalse(p.shouldAttempt(true, null, false, false, 0, prep))
        assertTrue(p.shouldAttempt(true, "new wifi", false, false, 5_000, prep))
        assertFalse(p.shouldAttempt(true, "new wifi", true, false, 10_000, prep))
        assertTrue(p.armForWake())
        assertTrue(p.shouldAttempt(true, "new wifi", true, false, 11_000, prep))
    }

    @Test fun coldPreparationRequiresPairedTargetListeningAndAuthorizedAttempt() {
        fun key(profile: String = "bmw_id85", mac: String? = "AA:BB:CC:DD:EE:FF",
                paired: Boolean = true, listening: Boolean = true, allowed: Boolean = true) =
            ReconnectDiscoverability.bmwPreparationKey(profile, mac, paired, listening, allowed)
        assertNull(key(profile = "bmw_id9"))
        assertNull(key(profile = "generic"))
        assertNull(key(mac = null))
        assertNull(key(mac = "not a MAC"))
        assertNull(key(paired = false))
        assertNull(key(listening = false))
        assertNull(key(allowed = false))
        assertEquals(key(), key(mac = "aa:bb:cc:dd:ee:ff"))
    }

    @Test fun disabledOrClaimedColdPreparationCannotOpenWindow() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.shouldAttempt(false, null, false, false, 0, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, false, 10_000, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, true, 14_000, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, false, 15_000, "bt:paired"))
        assertFalse(p.armForWake())
    }

    @Test fun losingColdReadinessRestartsGraceAndDoesNotAuthorizeNetworkFallback() {
        val p = ReconnectDiscoverabilityPolicy()
        p.shouldAttempt(true, null, false, false, 0, "bt:paired")
        assertFalse(p.shouldAttempt(true, null, false, false, 4_000, null))
        assertFalse(p.shouldAttempt(true, null, false, false, 10_000, "bt:paired"))
        assertFalse(p.shouldAttempt(true, null, false, false, 14_999, "bt:paired"))
        assertTrue(p.shouldAttempt(true, null, false, false, 15_000, "bt:paired"))
    }
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
        assertFalse(p.armForWake())
    }

    @Test fun aclWakeCanArmOneExtraWindowAfterTheInitialAttempt() {
        val p = ReconnectDiscoverabilityPolicy()
        assertFalse(p.armForWake())
        assertFalse(p.shouldAttempt(true, "car", true, false, 0))
        assertTrue(p.shouldAttempt(true, "car", true, false, 5_000))
        assertTrue(p.armForWake())
        assertFalse(p.armForWake()) // already armed
        assertTrue(p.shouldAttempt(true, "car", true, false, 6_000))
        assertFalse(p.shouldAttempt(true, "car", true, false, 7_000))
        assertTrue(p.armForWake())
    }
}
