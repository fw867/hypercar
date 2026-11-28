package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class KnownCarNetworkPolicyTest {
    private val first = "11:22:33:44:55:66"
    private val second = "22:33:44:55:66:77"

    @Test fun dualWifiFindsOnlyTheUnambiguousLiveHandleForThisCar() {
        val saved = setOf("BMW-A|$first", "BMW-B|$second")
        val live = listOf(1 to "Home", 2 to "BMW-A", 3 to "BMW-B")
        assertEquals(2 to "BMW-A", KnownCarNetworkPolicy.associatedHotspot(live, saved, first.lowercase()))
        assertNull(KnownCarNetworkPolicy.associatedHotspot(live, saved, null))
        assertNull(KnownCarNetworkPolicy.associatedHotspot(live + (4 to "BMW-A"), saved, first))
        assertNull(KnownCarNetworkPolicy.associatedHotspot(live, saved + "BMW-A|$second", first))
        assertNull(KnownCarNetworkPolicy.associatedHotspot(listOf(1 to "Home"), saved, first))
    }

    @Test fun sessionSsidComesFromTheNetworkThatCarriesCarplay() {
        assertEquals("BMW", KnownCarNetworkPolicy.sessionSsid(2, "BMW", 1, "Home"))
        assertEquals("BMW", KnownCarNetworkPolicy.sessionSsid(2, null, 2, "BMW"))
        assertNull(KnownCarNetworkPolicy.sessionSsid(2, null, 1, "Home"))
    }

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
