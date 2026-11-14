package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class ProactiveWifiSessionPolicyTest {
    private val car = "11:22:33:44:55:66"

    @Test fun waitsForTenSecondsOfEligibleStableNetwork() {
        val delay = ProactiveWifiSessionDelay()
        assertFalse(delay.ready(true, "130", 1_000))
        assertFalse(delay.ready(true, "130", 10_999))
        assertTrue(delay.ready(true, "130", 11_000))
        assertFalse(delay.ready(true, "131", 11_001))
        assertFalse(delay.ready(true, "131", 21_000))
        assertTrue(delay.ready(true, "131", 21_001))
    }

    @Test fun losingEligibilityOrNetworkRestartsGrace() {
        val delay = ProactiveWifiSessionDelay()
        assertFalse(delay.ready(true, "130", 0))
        assertFalse(delay.ready(false, "130", 9_000))
        assertFalse(delay.ready(true, "130", 10_000))
        assertFalse(delay.ready(true, null, 19_000))
        assertFalse(delay.ready(true, "130", 20_000))
        assertFalse(delay.ready(true, "130", 29_999))
        assertTrue(delay.ready(true, "130", 30_000))
    }

    @Test fun optInRequiresKnownBoundReceiverAndDoesNotOverrideDifferentPeer() {
        assertFalse(ProactiveWifiSessionPolicy.allows(true, car, null, true))
        assertTrue(ProactiveWifiSessionPolicy.allows(true, car, null, true, recoveryAllowed = true))
        assertTrue(ProactiveWifiSessionPolicy.allows(true, car, car.lowercase(), true, recoveryAllowed = true))
        assertFalse(ProactiveWifiSessionPolicy.allows(false, car, null, true, recoveryAllowed = true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true, null, null, true, recoveryAllowed = true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true, car, null, false, recoveryAllowed = true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true, car, "22:33:44:55:66:77", true, recoveryAllowed = true))
    }

    @Test fun desktopReceiverDoesNotTrustAFormerPeerAtTheSameLanSsid() {
        val speakerNow = "192.168.1.253:7000|YK"
        assertNull(ProactiveWifiSessionPolicy.rememberedEndpoint(speakerNow, "YK", desktopReceiver = true))
        assertEquals("192.168.1.253" to 7000,
            ProactiveWifiSessionPolicy.rememberedEndpoint(speakerNow, "YK", desktopReceiver = false))
    }

    @Test fun rememberedEndpointMustBelongToExactNetworkAndBeValidIpv4Port() {
        assertEquals("172.16.222.97" to 7000,
            ProactiveWifiSessionPolicy.rememberedEndpoint("172.16.222.97:7000|car", "car"))
        for (value in listOf("172.16.222.97:7000|home", "172.16.222.97:7000|",
                "fe80::1:7000|car", "999.1.1.1:7000|car", "1.2.3.4:0|car", "1.2.3.4:65536|car")) {
            assertNull(value, ProactiveWifiSessionPolicy.rememberedEndpoint(value, "car"))
        }
        assertNull(ProactiveWifiSessionPolicy.rememberedEndpoint("1.2.3.4:7000|car", null))
    }
    @Test fun knownNetworkOptInRestoresStartWithoutInterruptedSessionGrant() {
        assertEquals(ProactiveWifiSessionPolicy.Mode.KNOWN_NETWORK,
            ProactiveWifiSessionPolicy.mode(true,false,false))
        assertTrue(ProactiveWifiSessionPolicy.allows(true,car,null,true,knownNetworkStart=true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true,car,null,true))
        assertEquals(ProactiveWifiSessionPolicy.Mode.INVITATION_ONLY,
            ProactiveWifiSessionPolicy.mode(false,true,false))
        assertEquals(ProactiveWifiSessionPolicy.Mode.INTERRUPTION_RECOVERY,
            ProactiveWifiSessionPolicy.mode(false,true,true))
    }
    @Test fun newOptInStillRequiresIdentityNetworkMatchingPeerAndNoManualPause() {
        assertFalse(ProactiveWifiSessionPolicy.allows(true,null,null,true,knownNetworkStart=true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true,car,null,false,knownNetworkStart=true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true,car,"22:33:44:55:66:77",true,knownNetworkStart=true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true,car,null,true,knownNetworkStart=true,paused=true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true,car,null,true,recoveryAllowed=true,paused=true))
        assertFalse(ProactiveWifiSessionPolicy.allows(false,car,null,true,knownNetworkStart=true))
    }
    @Test fun enablingBothPreservesImmediateRecoveryWithoutRenewingFirstAttemptPermission() {
        val recovery=WifiSessionRecovery()
        recovery.mediaWorking(car,"car-network",true)
        recovery.interrupted(car,"car-network",0)
        val mode=ProactiveWifiSessionPolicy.mode(true,true,recovery.allows(car,"car-network",0))
        assertEquals(ProactiveWifiSessionPolicy.Mode.INTERRUPTION_RECOVERY,mode)
        val grace=ProactiveWifiSessionDelay()
        assertTrue(grace.ready(true,"network",0,immediate=recovery.firstAttempt(car,"car-network",0)))
        assertTrue(recovery.reserve(car,"car-network",0))
        assertFalse(recovery.firstAttempt(car,"car-network",1))
        assertFalse(grace.ready(true,"network",1_000,immediate=recovery.firstAttempt(car,"car-network",1_000)))
        assertTrue(grace.ready(true,"network",10_000))
        assertEquals(ProactiveWifiSessionPolicy.Mode.KNOWN_NETWORK,
            ProactiveWifiSessionPolicy.mode(true,true,recovery.allows(car,"car-network",120_000)))
        assertFalse(grace.ready(true,"different-network",120_001))
    }
    @Test fun knownNetworkRestorationStillHonoursFailureBackoffAndInvitationOnlySuppression() {
        val retry=KnownWifiActivationRetry()
        val eligible=ProactiveWifiSessionPolicy.allows(true,car,null,true,knownNetworkStart=true)
        assertTrue(retry.allowsRetry(eligible,false,"network",car,0))
        retry.failed("network",car,0)
        assertFalse(retry.allowsRetry(eligible,false,"network",car,9_999))
        assertTrue(retry.allowsRetry(eligible,false,"network",car,10_000))
        retry.suppressUninvitedReplay("network",car)
        assertFalse(retry.allowsRetry(eligible,false,"network",car,100_000))
        assertFalse(retry.allowsRetry(eligible,true,"another-network",car,100_000))
    }

}
