package io.github.hypercar.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHotspotReconnectTest {
    private val receiver = "02:11:22:33:44:AA"
    private val other = "02:11:22:33:44:BB"

    @Test
    fun manualReconnectToASelectedPairedCarMayRestoreWifi() {
        assertTrue(CarHotspotReconnect.canRestore(true, receiver, receiver.lowercase(), true, desktop = false))
    }

    @Test
    fun automaticConnectionAndDesktopReceiversKeepTheExistingPath() {
        assertFalse(CarHotspotReconnect.canRestore(false, receiver, receiver, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRestore(true, receiver, receiver, true, desktop = true))
    }

    @Test
    fun aCacheNeedsAnExplicitTargetAndMatchingBluetoothAndCarPlayPairing() {
        assertFalse(CarHotspotReconnect.canRestore(true, null, receiver, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRestore(true, receiver, null, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRestore(true, receiver, other, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRestore(true, receiver, receiver, false, desktop = false))
    }

    @Test
    fun cachingRequiresAnOwnedProtectedLocalNetworkFromTheCurrentBluetoothPeer() {
        assertTrue(CarHotspotReconnect.canRemember("test-password", true, true, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("", true, true, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", false, true, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", true, false, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", true, true, false, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", true, true, true, desktop = true))
    }

    @Test
    fun discoveryStartedBeforeCachedJoinCannotAdoptItsNetworkDuringOrAfterCancellation() {
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        val discovery = handoff.discoveryToken()
        assertTrue(handoff.begin(request, receiver))
        assertTrue(handoff.isJoining(request))
        assertNull(handoff.discoveryToken())
        assertFalse(handoff.canCommitDiscovery(discovery))

        handoff.finishJoining(request)
        assertFalse(handoff.canCommitDiscovery(discovery))
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }

    @Test
    fun losingProvisionalNetworkOnlyAffectsItsOwnRequestAndAllowsDiscoveryAfterCleanup() {
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request, receiver))
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
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request, receiver))
        handoff.bind(request, 4)
        handoff.confirm(5)
        assertSame(request, handoff.provisionalRequest(4))
        handoff.confirm(4)
        assertNull(handoff.provisionalRequest(4))
        assertSame(request, handoff.cachedRequest)
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }

    @Test
    fun switchingTargetsInvalidatesCachedWifiUntilCarPlayHasConnected() {
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request, receiver))
        assertFalse(handoff.matchesTarget(other, established = false))
        handoff.bind(request, 4)
        assertTrue(handoff.matchesTarget(receiver.lowercase(), established = false))
        assertFalse(handoff.matchesTarget(null, established = false))
        handoff.confirm(4)
        assertFalse(handoff.matchesTarget(other, established = false))
        assertTrue(handoff.matchesTarget(other, established = true))
        handoff.clear(request)
        assertTrue(handoff.matchesTarget(other, established = false))
    }

    @Test
    fun addressOnlyEndpointWaitsForCachedWifiOrLiveCredentials() {
        assertFalse(CarHotspotReconnect.canUseEndpoint(hasCache = true, carNetworkReady = false, freshCredentials = false))
        assertTrue(CarHotspotReconnect.canUseEndpoint(hasCache = true, carNetworkReady = true, freshCredentials = false))
        assertTrue(CarHotspotReconnect.canUseEndpoint(hasCache = true, carNetworkReady = false, freshCredentials = true))
        assertTrue(CarHotspotReconnect.canUseEndpoint(hasCache = false, carNetworkReady = false, freshCredentials = false))
    }

    @Test
    fun ordinaryVwAdvertisementNetworkAllowsCacheButVerifiedCarNetworkTakesPriority() {
        val home = 3
        assertTrue(CarHotspotReconnect.canReplaceBinding(home, home, "Home", "Car"))
        assertFalse(CarHotspotReconnect.canReplaceBinding(home, home, "Car", "Car"))
        assertFalse(CarHotspotReconnect.canReplaceBinding(home, null, "AnotherCarNetwork", "Car"))
        assertTrue(CarHotspotReconnect.canReplaceBinding<Int>(null, null, null, "Car"))
    }
}
