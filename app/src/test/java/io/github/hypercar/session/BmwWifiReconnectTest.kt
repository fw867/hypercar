package io.github.hypercar.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwWifiReconnectTest {
    private val receiver = "02:11:22:33:44:AA"
    private val other = "02:11:22:33:44:BB"

    @Test
    fun manualReconnectToTheSelectedPairedBmwMayRestoreWifi() {
        assertTrue(BmwWifiReconnect.canRestore("bmw_id85", true, receiver, receiver.lowercase(), true))
    }

    @Test
    fun automaticConnectionAndOtherProfilesKeepTheExistingPath() {
        assertFalse(BmwWifiReconnect.canRestore("bmw_id85", false, receiver, receiver, true))
        assertFalse(BmwWifiReconnect.canRestore("bmw_id9", true, receiver, receiver, true))
    }

    @Test
    fun aCacheNeedsAnExplicitTargetAndMatchingBluetoothAndCarPlayPairing() {
        assertFalse(BmwWifiReconnect.canRestore("bmw_id85", true, null, receiver, true))
        assertFalse(BmwWifiReconnect.canRestore("bmw_id85", true, receiver, null, true))
        assertFalse(BmwWifiReconnect.canRestore("bmw_id85", true, receiver, other, true))
        assertFalse(BmwWifiReconnect.canRestore("bmw_id85", true, receiver, receiver, false))
    }

    @Test
    fun openAndOtherProfilesHotspotsAreNotCached() {
        assertTrue(BmwWifiReconnect.canRemember("bmw_id85", "test-password"))
        assertFalse(BmwWifiReconnect.canRemember("bmw_id85", ""))
        assertFalse(BmwWifiReconnect.canRemember("bmw_id9", "test-password"))
    }
}
