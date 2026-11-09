package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class ReceiverCompatibilityTest {
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
