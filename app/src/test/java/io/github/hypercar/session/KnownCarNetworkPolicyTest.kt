package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class KnownCarNetworkPolicyTest {
    private val first = "11:22:33:44:55:66"
    private val second = "22:33:44:55:66:77"

    @Test fun onlyKnownSsidAndSelectedReceiverMayResume() {
        val networks = setOf("car|$first")
        assertEquals(first, KnownCarNetworkPolicy.receiver("car", networks, null))
        assertEquals(first, KnownCarNetworkPolicy.receiver("car", networks, first.lowercase()))
        assertNull(KnownCarNetworkPolicy.receiver("home", networks, first))
        assertNull(KnownCarNetworkPolicy.receiver("car", networks, second))
        assertNull(KnownCarNetworkPolicy.receiver(null, networks, first))
    }

    @Test fun ambiguousNetworksNeedTargetAndMalformedRecordsAreIgnored() {
        val networks = setOf("car|$first", "car|$second", "car|bad")
        assertNull(KnownCarNetworkPolicy.receiver("car", networks, null))
        assertEquals(second, KnownCarNetworkPolicy.receiver("car", networks, second))
        assertEquals(first, KnownCarNetworkPolicy.receiver("car|wifi", setOf("car|wifi|$first"), null))
    }
}
