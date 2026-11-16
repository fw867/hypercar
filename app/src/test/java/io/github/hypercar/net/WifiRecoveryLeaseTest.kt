package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class WifiRecoveryLeaseTest {
    @Test fun pauseFailureAndRepeatedStandbyKeepTheAssociationWithoutTimeBasedRelease() {
        val released = mutableListOf<Any>()
        val lease = WifiRecoveryLease<Any>(released::add)
        val request = Any()
        repeat(100) { lease.retain(request, "bmw:car-a") }
        assertTrue(lease.isRetained(request))
        assertTrue(released.isEmpty())
        assertTrue(lease.adopt(request, "bmw:car-a"))
        lease.close() // Active session owns it; no old idle cleanup may disconnect it.
        assertTrue(released.isEmpty())
    }

    @Test fun realNetworkLossReleasesOnlyItsRetainedRequestOnce() {
        val released = mutableListOf<Any>()
        val lease = WifiRecoveryLease<Any>(released::add)
        val request = Any()
        lease.retain(request, "bmw:car-a")
        lease.close(); lease.close()
        assertEquals(listOf(request), released)
        assertFalse(lease.isRetained(request))
    }

    @Test fun anotherReceiverCannotAdoptTheAssociationOrCauseFailureToReleaseIt() {
        val released = mutableListOf<Any>()
        val lease = WifiRecoveryLease<Any>(released::add)
        val request = Any()
        lease.retain(request, "bmw:car-a")
        assertFalse(lease.adopt(request, "bmw:car-b"))
        assertFalse(lease.adopt(Any(), "bmw:car-a"))
        assertTrue(lease.isRetained(request))
        assertTrue(released.isEmpty())
        assertTrue(lease.adopt(request, "bmw:car-a"))
    }

    @Test fun earlyJoinWithoutCapturedPeerRequiresFreshBluetoothConfirmationToResume() {
        val released = mutableListOf<Any>()
        val lease = WifiRecoveryLease<Any>(released::add)
        val request = Any()
        lease.retain(request, "")
        assertFalse(lease.adopt(request, "bmw:car-a"))
        assertTrue(lease.adopt(request, "bmw:car-a", confirmedBluetoothHotspot = true))
        lease.close()
        assertTrue(released.isEmpty())
    }
}
