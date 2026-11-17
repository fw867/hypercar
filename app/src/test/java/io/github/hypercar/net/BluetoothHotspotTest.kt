package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class BluetoothHotspotTest {
    @Test fun onlyFreshBluetoothParametersCanStartANewJoin() {
        val hotspot = BluetoothHotspot.fromPeer(car, ssid)!!
        val endpoint = io.github.hypercar.iap2.CarPlayEndpoint(ssid, "fixture-pass", 7000, null, null,
            null, emptyList(), null, bluetoothHotspot = hotspot)
        assertTrue(endpoint.canRequestHotspot(hotspot))
        assertFalse(endpoint.copy(fromInvite = true).canRequestHotspot(hotspot))
        assertFalse(endpoint.copy(proactive = true).canRequestHotspot(hotspot))
        assertFalse(endpoint.copy(bluetoothHotspot = null).canRequestHotspot(hotspot))
        assertFalse(endpoint.canRequestHotspot(BluetoothHotspot.fromPeer("another peer", ssid)))
        assertFalse(endpoint.canRequestHotspot(BluetoothHotspot.fromPeer(car, "another SSID")))
    }
    private val car = "12:34:56:78:9A:BC"
    private val ssid = "DIRECT-BMW test"

    @Test fun firstConnectionAndAclRetirementUseOnlyThisAttemptsCredentials() {
        val ap = BluetoothHotspot.fromPeer(car, ssid)!!
        assertTrue(ap.applies(4, 4, car, null))
        assertTrue(ap.applies(4, 4, null, car.lowercase()))
        assertFalse(ap.applies(4, 5, null, car))
        assertFalse(ap.applies(4, 4, "other", car))
        assertFalse(ap.applies(4, 4, car, "other"))
        assertNull(BluetoothHotspot.fromPeer(null, ssid))
        assertNull(BluetoothHotspot.fromPeer(car, ""))
    }

    @Test fun matchingStationWithoutAnAddressOrUnambiguousHandleWaitsInsteadOfRequestingAgain() {
        val ap = BluetoothHotspot.fromPeer(car, ssid)!!
        assertEquals(7, ap.association(mapOf(7 to null), ssid).network)
        assertEquals(8, ap.association(mapOf(7 to "home", 8 to ssid), null).network)
        for (visible in listOf(emptyMap(), mapOf(7 to null, 8 to null))) {
            val match = ap.association(visible, ssid)
            assertTrue(match.associated)
            assertNull(match.network)
        }
        assertFalse(ap.association(mapOf(7 to "home"), "home").associated)
        assertNull(ap.association(mapOf(7 to "home"), ssid).network)
    }
}
