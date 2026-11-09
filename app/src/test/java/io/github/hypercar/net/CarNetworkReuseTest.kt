package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarNetworkReuseTest {
    private val link = CarNetworkReuse.Evidence("car", "aa:bb:cc:dd:ee:ff", true, true, false)
    @Test fun reuseRequiresLiveAddressSsidAndReceiverEvidence() {
        assertTrue(CarNetworkReuse.accepts("car", "AA:BB:CC:DD:EE:FF", false, link))
        assertFalse(CarNetworkReuse.accepts("other", null, false, link))
        assertFalse(CarNetworkReuse.accepts("car", null, false, link.copy(hasAddress = false)))
        assertFalse(CarNetworkReuse.accepts("car", "aa:bb:cc:dd:ee:00", false, link))
        assertFalse(CarNetworkReuse.accepts("car", "aa:bb:cc:dd:ee:ff", false, link.copy(bssid = "02:00:00:00:00:00")))
    }
    @Test fun savedSsidAndStaleHandleAloneDoNotAuthorizeAnUnownedNetwork() {
        assertFalse(CarNetworkReuse.accepts("car", null, false, link.copy(routeToPeer = false)))
        assertFalse(CarNetworkReuse.accepts("car", null, false, link.copy(ssid = null, pinned = true)))
        assertTrue(CarNetworkReuse.accepts("car", null, false, link.copy(routeToPeer = false, pinned = true)))
    }
    @Test fun sameBssidCanIdentifyEarlyCredentialsAndOpaqueIdsAreNotMistakenForMacs() {
        assertTrue(CarNetworkReuse.accepts("car", link.bssid, false, link.copy(routeToPeer = false)))
        assertTrue(CarNetworkReuse.accepts("car", "receiver-uuid", false, link))
        assertFalse(CarNetworkReuse.accepts("car", "receiver-uuid", false, link.copy(routeToPeer = false)))
    }
    @Test fun missingSsidNeedsAnOwnedLinkOrAnInvitationAndARealPeerRoute() {
        assertFalse(CarNetworkReuse.accepts(null, null, false, link))
        assertTrue(CarNetworkReuse.accepts(null, null, true, link))
        assertTrue(CarNetworkReuse.accepts(null, null, false, link.copy(pinned = true)))
        assertFalse(CarNetworkReuse.accepts(null, null, true, link.copy(routeToPeer = false)))
    }
}
