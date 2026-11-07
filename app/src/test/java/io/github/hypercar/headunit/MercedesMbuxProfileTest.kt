package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class MercedesMbuxProfileTest {
    private val profile = HeadUnitProfiles.MERCEDES_MBUX

    @Test fun realIap2EndpointIsEnoughWithoutAnUnobservedCtrlInvitation() {
        assertTrue(profile.requireAirplayActivation)
        assertFalse(profile.requireFreshCtrlInvite)
        assertFalse(HeadUnitActivationPolicy.permitsProactive(profile))
        assertFalse(HeadUnitActivationPolicy.needsFallbackGate(profile, false))
        assertTrue(profile.allowOutboundIap2)
        assertEquals(listOf(7000), profile.fallbackAirplayPorts)
    }

    @Test fun declaredMessagesStayEnabledWithoutBmwEaOrDesktopPolicies() {
        assertTrue(profile.sendCarPlayAvailability)
        assertTrue(profile.sendTransportIdentifier)
        assertTrue(profile.replyPowerUpdates)
        assertFalse(profile.openEaSessions)
        assertFalse(profile.dropProfilesBeforeIap2)
        assertNull(profile.touchDisplay)
        assertFalse(profile.validatesDeviceId)
        assertFalse(ReceiverCompatibility.isDesktopReceiver(profile.id, "MB_E-P-4 Mercedes-Benz"))
        assertFalse(ReceiverCompatibility.useBmwBootstrap(profile.id, "MB_E-P-4 Mercedes-Benz"))
        assertFalse(profile.verified)
    }

    @Test fun mbuxKeepsControlOnScreenRejectionWithoutChangingOtherProfilesOrUserSetting() {
        assertTrue(profile.screenResourceLifecycleEnabled(false))
        for (other in HeadUnitProfiles.builtin.filter { it.id != profile.id }) {
            assertFalse(other.id, other.screenResourceLifecycleEnabled(false))
            assertTrue(other.id, other.screenResourceLifecycleEnabled(true))
        }
    }

    @Test fun remoteProfileInheritsLifecycleAndCanExplicitlyOverrideIt() {
        assertEquals(profile, RemoteProfiles.parse("""[{"id":"mercedes_mbux"}]""").single())
        assertEquals(listOf(profile), RemoteProfiles.parse(RemoteProfiles.encode(listOf(profile))))
        val custom = RemoteProfiles.parse("""[{"id":"mercedes_mbux","useScreenResourceLifecycle":false}]""").single()
        assertFalse(custom.screenResourceLifecycleEnabled(false))
        assertTrue(custom.screenResourceLifecycleEnabled(true))
    }
}
