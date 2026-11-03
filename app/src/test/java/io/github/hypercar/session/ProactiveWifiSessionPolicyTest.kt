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
        assertTrue(ProactiveWifiSessionPolicy.allows(true, car, null, true))
        assertTrue(ProactiveWifiSessionPolicy.allows(true, car, car.lowercase(), true))
        assertFalse(ProactiveWifiSessionPolicy.allows(false, car, null, true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true, null, null, true))
        assertFalse(ProactiveWifiSessionPolicy.allows(true, car, null, false))
        assertFalse(ProactiveWifiSessionPolicy.allows(true, car, "22:33:44:55:66:77", true))
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
}
