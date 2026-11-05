package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class AirplaySubnetPlanTest {
    @Test fun winplayAddressIsDiscoveredBeyondCommon24Hosts() {
        val plan = AirplaySubnetPlan("192.168.1.242", 24)
        val common = plan.commonHosts(emptyList())
        assertFalse("192.168.1.38" in common)
        val remaining = plan.remainingHosts(common.toSet(), 8192)
        assertTrue("192.168.1.38" in remaining)
        assertEquals(253, (common + remaining).distinct().size)
        assertFalse("192.168.1.0" in remaining)
        assertFalse("192.168.1.255" in remaining)
        assertFalse(plan.self in remaining)
    }

    @Test fun arpUsesWhitespaceColumnsAndOnlyCompleteNeighborsOnBoundInterface() {
        val plan = AirplaySubnetPlan("192.168.1.242", 24)
        val rows = listOf(
            "IP address HW type Flags HW address Mask Device",
            "192.168.1.38    0x1\t0x2  aa:bb:cc:dd:ee:ff  *  wlan0",
            "192.168.1.39 0x1 0x0 00:00:00:00:00:00 * wlan0",
            "192.168.1.40 0x1 0x2 aa:bb:cc:dd:ee:ff * wlan1",
            "10.0.0.38 0x1 0x2 aa:bb:cc:dd:ee:ff * wlan0",
            "999.168.1.38 0x1 0x2 aa:bb:cc:dd:ee:ff * wlan0",
        )
        assertEquals(listOf("192.168.1.38"), plan.arpPeers(rows, "wlan0"))
    }

    @Test fun bmw20PrefixIncludesReceiverAndExcludesPhoneWithBoundedEnumeration() {
        val plan = AirplaySubnetPlan("172.16.222.105", 20)
        val common = plan.commonHosts(emptyList())
        val rest = plan.remainingHosts(common.toSet(), 8192)
        assertTrue("172.16.222.97" in rest)
        assertEquals(4093, (common + rest).distinct().size)
        assertEquals(10, plan.remainingHosts(emptySet(), 10).size)
        assertFalse(plan.usable("172.16.224.1"))
    }

    @Test fun smallerNetworksDoNotProbeOutsidePrefix() {
        val plan = AirplaySubnetPlan("192.168.1.10", 30)
        assertEquals(listOf("192.168.1.9"), plan.remainingHosts(emptySet(), 8192))
        assertTrue(plan.commonHosts(emptyList()).all(plan::usable))
    }
}
