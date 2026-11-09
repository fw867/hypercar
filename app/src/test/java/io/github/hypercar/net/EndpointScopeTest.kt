package io.github.hypercar.net

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class EndpointScopeTest {
    @Test fun invitationsKeepNumericIpv6ScopeRatherThanFallingThroughToAnotherAirplayDevice() {
        for (host in listOf("192.168.1.206", "fe80::dcf9:473e:5524:40f8%wlan0", "2001:db8::1", "::ffff:192.168.1.206"))
            assertEquals(host, EndpointScope.invitationHost(host))
        assertEquals("fe80::1%wlan0", EndpointScope.onNetwork(EndpointScope.invitationHost("fe80::1")!!, "wlan0"))
        for (host in listOf(null, "", "?", "receiver.local", "999.1.1.1", "fe80::1%", "192.168.1.206%wlan0"))
            assertNull(EndpointScope.invitationHost(host))
    }

    @Test fun allLinkLocalPrefixVariantsUseTheSessionInterface() {
        for (prefix in listOf("fe80", "fe81", "fe9f", "fea1", "febf", "FE80")) {
            assertEquals("$prefix::123%wlan1", EndpointScope.onNetwork("$prefix::123", "wlan1"))
        }
    }
    @Test fun explicitScopeIpv4AndGlobalIpv6ArePreserved() {
        assertEquals("fe80::1%wlan2", EndpointScope.onNetwork("fe80::1%wlan2", "wlan0"))
        assertEquals("192.168.1.1", EndpointScope.onNetwork("192.168.1.1", null))
        assertEquals("2001:db8::1", EndpointScope.onNetwork("2001:db8::1", null))
        assertEquals("fec0::1", EndpointScope.onNetwork("fec0::1", null))
    }
    @Test(expected = IOException::class) fun missingScopeCannotFallThroughToAnotherNetwork() {
        EndpointScope.onNetwork("fe80::1", null)
    }
}
