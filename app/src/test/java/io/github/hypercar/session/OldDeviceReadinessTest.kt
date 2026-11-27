package io.github.hypercar.session

import io.github.hypercar.net.CtrlSrvObservation
import io.github.hypercar.net.CtrlSrvEvidence
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class OldDeviceReadinessTest {
    @Test fun pairedCarCanPageAConnectablePhoneOnlyWhenItsRfcommListenerAccepts() {
        val srv = OldDeviceReadiness.Srv("phone.local", 49152)
        val ready = OldDeviceReadiness(7, "156", srv, android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE, rfcommAccepting = true)
        assertTrue(ready.ready)
        assertEquals(false, ready.discoverable)
        assertTrue(ready.copy(scanMode = android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE).ready)
        assertFalse(ready.copy(rfcommAccepting = false).ready)
        assertFalse(ready.copy(scanMode = android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE).ready)
        assertFalse(ready.copy(scanMode = null).ready)
        assertFalse(ready.copy(network = null).ready)
        assertFalse(ready.copy(srv = null).ready)
    }

    @Test fun srvMustMatchCurrentRegistrationNamePortAndTime() {
        val srv = CtrlSrvObservation("phone", "phone.local", 49152, 100)
        assertTrue(srv.matches("Phone", 49152, 100))
        assertFalse(srv.copy(receivedAt = 99).matches("Phone", 49152, 100))
        assertFalse(srv.copy(port = 49151).matches("Phone", 49152, 100))
        assertFalse(srv.matches("Phone (2)", 49152, 100))
    }

    @Test fun addressChangeRequiresNewSrvAndRejectsDelayedPacketsFromOldAddresses() {
        val old = setOf(InetAddress.getByName("10.0.0.2"))
        val new = setOf(InetAddress.getByName("10.0.0.3"))
        val evidence = CtrlSrvEvidence()
        val srv = CtrlSrvObservation("phone", "phone.local", 49152, 100, old)
        evidence.record(srv)
        evidence.addressesChanged(old, 101)
        assertEquals(srv, evidence.latest)
        evidence.addressesChanged(new, 102)
        assertNull(evidence.latest)
        evidence.record(srv.copy(receivedAt = 103))
        evidence.record(srv.copy(localAddresses = new, receivedAt = 101))
        assertNull(evidence.latest)
        val fresh = srv.copy(localAddresses = new, receivedAt = 104)
        evidence.record(fresh)
        assertEquals(fresh, evidence.latest)
    }
}
