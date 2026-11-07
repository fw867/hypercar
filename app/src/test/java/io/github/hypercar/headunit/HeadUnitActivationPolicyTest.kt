package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class HeadUnitActivationPolicyTest {
    @Test fun volkswagenNeverBypassesItsInvitationGateWithTheGlobalWifiShortcut() {
        val vw = HeadUnitProfiles.VOLKSWAGEN
        assertTrue(vw.verified)
        assertTrue(vw.requireAirplayActivation)
        assertTrue(vw.allowOutboundIap2)
        assertFalse(vw.dropProfilesBeforeIap2)
        assertFalse(HeadUnitActivationPolicy.permitsProactive(vw))
        assertFalse(HeadUnitActivationPolicy.permitsProactive(vw.copy(allowProactiveWifiSession = true)))
        assertTrue(HeadUnitActivationPolicy.permitsProactive(HeadUnitProfiles.BMW_ID85))
        assertTrue(HeadUnitActivationPolicy.needsFallbackGate(vw, false))
        assertFalse(HeadUnitActivationPolicy.needsFallbackGate(HeadUnitProfiles.BMW_ID85, false))
        assertTrue(HeadUnitActivationPolicy.needsFallbackGate(HeadUnitProfiles.BMW_ID85, true))
    }

    @Test fun volkswagenRejectsOldMissingAndFutureInvites() {
        val vw = HeadUnitProfiles.VOLKSWAGEN
        assertFalse(HeadUnitActivationPolicy.acceptsInvitation(vw, 0, 50_000))
        assertFalse(HeadUnitActivationPolicy.acceptsInvitation(vw, 7_000, 50_000))
        assertFalse(HeadUnitActivationPolicy.acceptsInvitation(vw, 50_001, 50_000))
        assertTrue(HeadUnitActivationPolicy.acceptsInvitation(vw, 43_000, 50_000))
        assertTrue(HeadUnitActivationPolicy.acceptsInvitation(vw, 30_000, 50_000))
        assertFalse(HeadUnitActivationPolicy.acceptsInvitation(vw, 29_999, 50_000))
        assertFalse(HeadUnitActivationPolicy.acceptsInvitation(vw, 49_000, 50_000, sameNetwork = false))
        assertTrue(HeadUnitActivationPolicy.acceptsInvitation(HeadUnitProfiles.BMW_ID85, 7_000, 50_000))
    }

    @Test fun currentWifiAdvertisingRequiresAnExplicitMatchingPeer() {
        val vw = HeadUnitProfiles.VOLKSWAGEN
        val target = "AA:BB:CC:DD:EE:01"
        assertFalse(HeadUnitActivationPolicy.canAdvertiseCurrentWifi(vw, null, null))
        assertFalse(HeadUnitActivationPolicy.canAdvertiseCurrentWifi(vw, "invalid", null))
        assertFalse(HeadUnitActivationPolicy.canAdvertiseCurrentWifi(vw, target, "AA:BB:CC:DD:EE:02"))
        assertTrue(HeadUnitActivationPolicy.canAdvertiseCurrentWifi(vw, target, null))
        assertTrue(HeadUnitActivationPolicy.canAdvertiseCurrentWifi(vw, target, target.lowercase()))
        assertFalse(HeadUnitActivationPolicy.canAdvertiseCurrentWifi(HeadUnitProfiles.BMW_ID85, target, null))
    }
}
