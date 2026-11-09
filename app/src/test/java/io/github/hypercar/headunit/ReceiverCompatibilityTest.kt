package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class ReceiverCompatibilityTest {
    @Test fun softwareDiscoveryRejectsSpeakersWithoutChangingVehicleDiscovery() {
        assertFalse(ReceiverCompatibility.matchesAirplayService("winplay", null, "xiaomi sound-6270._airplay._tcp.local", "L16A"))
        assertTrue(ReceiverCompatibility.matchesAirplayService("winplay", null, "WinPlay._airplay._tcp.local", "WinPlay"))
        assertTrue(ReceiverCompatibility.matchesAirplayService("bmw_id85", "DiPlay", "Xcertplay._airplay._tcp.local", null))
        assertFalse(ReceiverCompatibility.matchesAirplayService("bmw_id85", "DiPlay", "speaker._airplay._tcp.local", null))
        assertTrue(ReceiverCompatibility.matchesAirplayService("bmw_id85", null, "BMW._airplay._tcp.local", "MGU"))
        assertEquals(listOf(17000,7000), HeadUnitProfiles.WINPLAY.fallbackAirplayPorts)
    }

    @Test fun mbuxBootstrapIsRestrictedToTheExperimentalMercedesProfile() {
        assertTrue(HeadUnitProfiles.MERCEDES_MBUX.mbuxScreenBootstrap)
        assertTrue(HeadUnitProfiles.MERCEDES_MBUX.screenResourceLifecycleEnabled(false))
        HeadUnitProfiles.builtin.filter { it.id != "mercedes_mbux" }.forEach {
            assertFalse(it.id, it.mbuxScreenBootstrap)
        }
    }
    @Test fun desktopAudioCompatibilityRequiresDesktopProfileOrCurrentIdentity() {
        assertTrue(ReceiverCompatibility.isDesktopReceiver("winplay", null))
        assertTrue(ReceiverCompatibility.isDesktopReceiver("bmw_id85", "WinPlay receiver"))
        assertTrue(ReceiverCompatibility.isDesktopReceiver("bmw_id85", "DiPlay"))
        assertFalse(ReceiverCompatibility.isDesktopReceiver("bmw_id85", "BMW MGU"))
        assertFalse(ReceiverCompatibility.isDesktopReceiver("generic", null))
    }
    @Test fun desktopIdentityOverridesRetainedBmwProfile() {
        assertFalse(ReceiverCompatibility.useBmwBootstrap("bmw_id85", "DiPlay DiPlay DiPlay"))
        assertFalse(ReceiverCompatibility.useBmwBootstrap("bmw_id85", "WinPlay PC receiver"))
        assertFalse(ReceiverCompatibility.useBmwBootstrap("bmw_id85", "WINPLAY"))
    }
    @Test fun bmwAndUnknownReceiversKeepSelectedBmwBehavior() {
        assertTrue(ReceiverCompatibility.useBmwBootstrap("bmw_id85", "BMW MGU"))
        assertTrue(ReceiverCompatibility.useBmwBootstrap("bmw_id85", null))
        assertFalse(ReceiverCompatibility.useBmwBootstrap("winplay", null))
        assertFalse(ReceiverCompatibility.useBmwBootstrap("generic", "BMW"))
    }
}
