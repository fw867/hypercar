package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class RememberedAirplayEndpointTest {
    @Test fun desktopReceiverDoesNotTrustAFormerPeerAtTheSameLanSsid() {
        val speakerNow = "192.168.1.253:7000|YK"
        assertNull(RememberedAirplayEndpoint.parse(speakerNow, "YK", desktopReceiver = true))
        assertEquals("192.168.1.253" to 7000,
            RememberedAirplayEndpoint.parse(speakerNow, "YK", desktopReceiver = false))
    }

    @Test fun rememberedEndpointMustBelongToExactNetworkAndBeValidIpv4Port() {
        assertEquals("172.16.222.97" to 7000,
            RememberedAirplayEndpoint.parse("172.16.222.97:7000|car", "car"))
        for (value in listOf("172.16.222.97:7000|home", "172.16.222.97:7000|",
                "fe80::1:7000|car", "999.1.1.1:7000|car", "1.2.3.4:0|car", "1.2.3.4:65536|car")) {
            assertNull(value, RememberedAirplayEndpoint.parse(value, "car"))
        }
        assertNull(RememberedAirplayEndpoint.parse("1.2.3.4:7000|car", null))
    }
}
