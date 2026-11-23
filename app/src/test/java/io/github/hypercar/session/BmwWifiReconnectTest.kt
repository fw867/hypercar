package io.github.hypercar.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun discoveryStartedBeforeCachedJoinCannotAdoptItsNetworkDuringOrAfterCancellation() {
        val handoff = BmwWifiReconnect.Handoff<Any, Int>()
        val request = Any()
        val discovery = handoff.discoveryToken()
        assertTrue(handoff.begin(request))
        assertTrue(handoff.isJoining(request))
        assertNull(handoff.discoveryToken())
        assertFalse(handoff.canCommitDiscovery(discovery))

        handoff.finishJoining(request)
        assertFalse(handoff.canCommitDiscovery(discovery))
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }

    @Test
    fun losingProvisionalNetworkOnlyAffectsItsOwnRequestAndAllowsDiscoveryAfterCleanup() {
        val handoff = BmwWifiReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request))
        handoff.bind(request, 4)
        handoff.finishJoining(request)
        assertSame(request, handoff.provisionalRequest(4))
        assertNull(handoff.provisionalRequest(5))
        assertNull(handoff.discoveryToken())
        handoff.clear(Any())
        assertSame(request, handoff.provisionalRequest(4))
        handoff.clear(request)
        assertNull(handoff.provisionalRequest(4))
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }

    @Test
    fun acceptedActivationPromotesCachedNetworkToNormalLossHandling() {
        val handoff = BmwWifiReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request))
        handoff.bind(request, 4)
        handoff.confirm(5)
        assertSame(request, handoff.provisionalRequest(4))
        handoff.confirm(4)
        assertNull(handoff.provisionalRequest(4))
        assertSame(request, handoff.cachedRequest)
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }
}
