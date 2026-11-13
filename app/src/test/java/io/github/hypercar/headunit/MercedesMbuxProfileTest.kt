package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class MercedesMbuxProfileTest {
    private val profile = HeadUnitProfiles.MERCEDES_MBUX

    @Test fun replacementKeepsSavedIdAndRejectsOldBootstrapCaches() {
        assertEquals("mercedes_mbux", profile.id)
        assertFalse(profile.mbuxScreenBootstrap)
        assertFalse(profile.id9ScreenBootstrap)
        assertEquals(1, HeadUnitProfiles.builtin.count { it.id.startsWith("mercedes") })
        val stale = profile.copy(mbuxScreenBootstrap = true)
        for (schema in listOf(8, 9)) {
            assertEquals(profile, RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(stale), schema).single { it.id == profile.id })
            assertFalse(RemoteProfiles.canOverrideBuiltin(profile.id, schema))
        }
        val override = profile.copy(preferMdnsAirplay = false)
        assertEquals(override, RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(override), 10).single { it.id == profile.id })
    }

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
        for (other in HeadUnitProfiles.builtin.filter { it.id != profile.id && it.id != HeadUnitProfiles.BMW_ID9.id }) {
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
