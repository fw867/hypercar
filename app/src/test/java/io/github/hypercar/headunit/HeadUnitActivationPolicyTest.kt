package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class HeadUnitActivationPolicyTest {
    @Test fun motorcycleAudioExperimentUsesTheSelectedProfileNotItsBmwProtocolAlias() {
        val motorcycle = HeadUnitProfiles.MOTORCYCLE.connectionPolicy()
        assertEquals("bmw_id85", motorcycle.protocolProfileId)
        assertFalse(motorcycle.airplayAudioEnabled)
        assertTrue(motorcycle.skipBluetoothHandoff)
        assertTrue(motorcycle.useScreenResourceLifecycle)
        for (profile in HeadUnitProfiles.builtin.filter { it.id != motorcycle.id }) {
            assertTrue(profile.id, profile.connectionPolicy().airplayAudioEnabled)
        }
        assertFalse(motorcycle.copy(label = "Remote motorcycle", notes = "cached profile").airplayAudioEnabled)
    }

    @Test fun volkswagenRequiresActivationAndKeepsItsInvitationGate() {
        val vw = HeadUnitProfiles.VOLKSWAGEN
        assertTrue(vw.verified)
        assertTrue(vw.requireAirplayActivation)
        assertTrue(vw.allowOutboundIap2)
        assertFalse(vw.dropProfilesBeforeIap2)
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
