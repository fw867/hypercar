package io.github.hypercar.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AirplaySubnetProbeTest {
    @Test fun extendedScanChecksWinplayPortOnNonCommonHost() = runBlocking {
        val plan = AirplaySubnetPlan("192.168.1.242", 24)
        val hosts = plan.remainingHosts(plan.commonHosts(emptyList()).toSet(), 8192)
        val found = AirplaySubnetProbe.find(hosts, listOf(7000, 7001, 7100, 17000), 16, { false }) { host, port ->
            host == "192.168.1.38" && port == 17000
        }
        assertEquals("192.168.1.38" to 17000, found)
    }

    @Test fun invitationStopsFurtherProbesAndDiscardsRacingHit() = runBlocking {
        var invited = false
        var calls = 0
        val found = AirplaySubnetProbe.find(listOf("192.168.1.38", "192.168.1.39"), listOf(7000, 17000), 1, { invited }) { _, _ ->
            calls++; invited = true; true
        }
        assertNull(found)
        assertEquals(1, calls)
    }

    @Test fun networkAlreadyLostDoesNotOpenAnySocket() = runBlocking {
        val found = AirplaySubnetProbe.find(listOf("192.168.1.38"), listOf(17000), 16, { true }) { _, _ ->
            fail("must not probe a stale network"); false
        }
        assertNull(found)
    }
}
