package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarNetworkReuseTest {
    @Test fun shellBssidParsingHandlesLayoutsWithoutRegex() {
        for (status in listOf(
            "SSID: \"car\", BSSID: AA:BB:CC:DD:EE:FF, IP: 192.168.1.2",
            "bssid = aa:bb:cc:dd:ee:ff\n",
            "BSSID: \"aa:bb:cc:dd:ee:ff\"",
            "BSSID='aa:bb:cc:dd:ee:ff'",
        )) assertEquals(status, "aa:bb:cc:dd:ee:ff", CarNetworkReuse.parseBssid(status))
    }
    @Test fun invalidAmbiguousAndQuotedSsidValuesDoNotAuthorizeReuse() {
        for (status in listOf(
            "", "SSID: \"BSSID: aa:bb:cc:dd:ee:ff\"", "mBSSID: aa:bb:cc:dd:ee:ff",
            "BSSID: 02:00:00:00:00:00", "BSSID: aa:bb:cc:dd:ee:ff:00",
            "BSSID: aa:bb:cc:dd:ee:ffsuffix", "BSSID: aa:bb:cc", "BSSID: gg:bb:cc:dd:ee:ff",
            "BSSID: aa:bb:cc:dd:ee:ff\nBSSID: aa:bb:cc:dd:ee:00",
        )) assertNull(status, CarNetworkReuse.parseBssid(status))
        assertEquals("aa:bb:cc:dd:ee:ff", CarNetworkReuse.parseBssid("SSID: \"BSSID: aa:bb:cc:dd:ee:00\", BSSID: aa:bb:cc:dd:ee:ff"))
    }
    @Test fun optionalShellFailureFallsBackButCancellationStillStopsWork() {
        assertNull(CarNetworkReuse.readBssid { throw IllegalArgumentException("Incorrect Unicode property") })
        assertNull(CarNetworkReuse.readBssid { "Wifi is disabled" })
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            CarNetworkReuse.readBssid { throw java.util.concurrent.CancellationException("stopped") }
        }
    }
    @Test fun macNormalizationUsesAsciiAndRejectsMalformedValues() {
        assertEquals("aa:bb:cc:dd:ee:ff", CarNetworkReuse.mac("AA:BB:CC:DD:EE:FF"))
        for (value in listOf(null, "", "aa:bb:cc:dd:ee:ff:00", "ＡＡ:bb:cc:dd:ee:ff", "aa-bb-cc-dd-ee-ff"))
            assertNull(CarNetworkReuse.mac(value))
    }

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
