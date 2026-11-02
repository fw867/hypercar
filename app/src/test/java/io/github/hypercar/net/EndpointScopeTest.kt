package io.github.hypercar.net

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class EndpointScopeTest {
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
