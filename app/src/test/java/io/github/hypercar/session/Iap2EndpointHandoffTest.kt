package io.github.hypercar.session

import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.AirplayRouteBinding
import io.github.hypercar.net.BluetoothHotspot
import io.github.hypercar.iap2.Iap2EndpointUpdate
import io.github.hypercar.iap2.RfcommConnections
import io.github.hypercar.iap2.RfcommTransport
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class Iap2EndpointHandoffTest {
    @Test fun revocationBetweenPreparingAndCommittingCannotMakeUncommittedAddressUsable() {
        val owner = sockets.register(Closeable {})
        var checks = 0
        var preparing = false
        val source = io.github.hypercar.iap2.Iap2EndpointGeneration(owner.registration,
            publisher = { !preparing || ++checks == 1 })
        val first = report(owner, "192.168.1.2").copy(sourceGeneration = source)
        assertFalse(send(first) { null })
        preparing = true
        handoff.prepare(null)
        assertEquals(0, deferredCommits)
        assertNull(handoff.take())
    }

    private val sockets = RfcommConnections<Closeable>()
    private val handoff = Iap2EndpointHandoff { _, next -> next }
    private var commits = 0
    private var deferredCommits = 0

    private fun report(transport: RfcommTransport<Closeable>, ip: String) = CarPlayEndpoint(
        null, null, 7000, null, null, null, listOf(ip), null,
        sourceGeneration = transport.endpointGeneration(),
    )

    private fun send(endpoint: CarPlayEndpoint, receiver: ((Iap2EndpointUpdate) -> Boolean?)? = null) =
        handoff.route({ Iap2EndpointUpdate(endpoint, { deferredCommits++ }) { commits++ } }, receiver)

    @Test fun firstNewAddressInClosedInboxGapSurvivesOldSocketCleanupAndIsTakenOnce() {
        val old = sockets.register(Closeable {})
        val teardown = sockets.captureClose()
        val fresh = report(sockets.register(Closeable {}), "192.168.1.2")
        assertFalse(send(fresh) { null }) // The old worker has exited but the session still reports live.
        handoff.prepare(teardown)
        teardown.close()
        assertFalse(send(report(old, "192.168.1.1")))
        assertSame(fresh, handoff.take())
        assertNull(handoff.take())
        assertEquals(0, commits)
        assertEquals(1, deferredCommits)
    }

    @Test fun firstDeferred4301SurvivesTheOldSnapshotButNeedsItsOwnNetworkAndCredentials() {
        val old = sockets.register(Closeable {})
        val loss = sockets.captureWifiClose(old.registration)
        val fresh = sockets.register(Closeable {})
        val route = report(fresh, "192.168.6.2").copy(
            ssid = "NEXT-WIFI", passphrase = "new-passphrase", addressOnlyAirplayReport = true,
            bluetoothHotspot = BluetoothHotspot.fromPeer("12:34:56:78:9A:BC", "NEXT-WIFI"),
            requiresBoundAirplayRoute = true, requiresMatchingAirplayCredentials = true,
            airplayRouteBinding = AirplayRouteBinding(),
        )
        handoff.retainAddress(route)
        handoff.retainAddress(route.copy(airPlayPort = 7010))
        handoff.prepare(loss)
        loss.close()
        assertNull(handoff.take())
        val saved = checkNotNull(handoff.recoveryAddress())
        assertEquals(7000, saved.airPlayPort)
        assertNull(saved.bluetoothHotspot)
        assertNull(saved.verifiedAirplayEndpoint("OLD-WIFI", 1L, hasMatchingCredentials = true) { true })
        assertNull(saved.verifiedAirplayEndpoint("NEXT-WIFI", 2L) { true })
        assertEquals(2L, saved.verifiedAirplayEndpoint("NEXT-WIFI", 2L, hasMatchingCredentials = true) { true }
            ?.verifiedAirplayNetworkHandle)
        assertNull(saved.verifiedAirplayEndpoint("NEXT-WIFI", 3L, hasMatchingCredentials = true) { true })
        fresh.revokeEndpointPublication()
        assertNull(handoff.recoveryAddress())
        assertFalse(saved.whileSourceCurrent { fail("revoked route reached handoff") })
        assertEquals(0, deferredCommits)
    }

    @Test fun earlyAcceptedAddressAndArrivalsDuringTeardownUseOnlySuccessorSocket() {
        val old = sockets.register(Closeable {})
        val teardown = sockets.captureClose()
        val fresh = report(sockets.register(Closeable {}), "192.168.1.2")
        assertTrue(send(fresh) { it.commit(); true })
        handoff.prepare(teardown)
        assertFalse(send(report(old, "192.168.1.1")))
        val latest = fresh.copy(airPlayPort = 7001)
        assertTrue(send(latest))
        assertSame(latest, handoff.take())
        assertEquals(2, commits)
        assertFalse(send(report(old, "192.168.1.1")) { fail("old source reached next round"); true })
    }

    @Test fun retiredLogicalLinkAndRejectedActiveReportDoNotReplay() {
        val teardown = sockets.captureClose()
        val fresh = report(sockets.register(Closeable {}), "192.168.1.2")
        assertFalse(send(fresh) { false })
        handoff.prepare(teardown)
        assertNull(handoff.take())
        handoff.prepare(teardown)
        assertTrue(send(fresh))
        fresh.sourceGeneration!!.retire()
        assertNull(handoff.take())
    }

    @Test fun bufferedReportFromEndedSocketBeforeLossIsNeverPromoted() {
        val old = sockets.register(Closeable {})
        val stale = report(old, "192.168.1.1")
        old.finish()
        assertTrue(stale.sourceIsCurrent) // A normal EOF leaves an accepted route usable in its own round.
        assertFalse(send(stale) { null })
        handoff.prepare(sockets.captureClose())
        assertEquals(0, deferredCommits)
        assertNull(handoff.take())
    }

    @Test fun oldWifiLossPreservesAlreadyAcceptedCafeAndItsFirstRoute() {
        val old = sockets.register(Closeable {})
        sockets.admit(old)
        val retiredRoute = report(old, "192.168.1.1")
        old.endIo()
        sockets.forget(old)
        var replacementClosed = false
        val replacement = sockets.register(Closeable { replacementClosed = true })
        val loss = sockets.captureWifiClose(old.registration)
        sockets.admit(replacement)
        val firstRoute = report(replacement, "192.168.1.2")
        assertFalse(send(firstRoute) { null })
        handoff.prepare(loss)
        loss.close()
        assertFalse(replacementClosed)
        assertFalse(send(retiredRoute))
        assertSame(firstRoute, handoff.take())
        assertEquals(1, deferredCommits)

        val nextRoundLoss = sockets.captureWifiClose(replacement.registration)
        handoff.prepare(nextRoundLoss)
        nextRoundLoss.close()
        assertTrue(replacementClosed)
        assertFalse(send(firstRoute))
        assertNull(handoff.take())
    }

    @Test fun laterWifiLossOnlyClosesTheReplacementForItsOwnNetwork() {
        var closed = false
        val old = sockets.register(Closeable {})
        val replacement = sockets.register(Closeable { closed = true })
        sockets.admit(replacement)
        val staleLoss = sockets.captureWifiClose(old.registration)
        handoff.prepare(staleLoss)
        staleLoss.close()
        val endpoint = report(replacement, "192.168.1.2")
        assertFalse(closed)
        assertTrue(send(endpoint))

        val currentLoss = sockets.captureWifiClose(replacement.registration)
        handoff.prepare(currentLoss)
        currentLoss.close()
        assertTrue(closed)
        assertFalse(send(endpoint))
        assertNull(handoff.take())
    }

    @Test fun timeoutDuringProposalRevokesNewReportsButKeepsPreviouslyAcceptedRoute() {
        val owner = sockets.register(Closeable {})
        val endpoint = report(owner, "192.168.1.1")
        handoff.prepare(null)
        assertTrue(send(endpoint))
        assertFalse(handoff.route({
            owner.revokeEndpointPublication()
            Iap2EndpointUpdate(endpoint.copy(airPlayPort = 7010), { fail() }) { fail() }
        }, null))
        assertSame(endpoint, handoff.take())
        assertEquals(1, commits)
    }

    @Test fun supersededUncommittedAddressCannotReachTheSuccessorInbox() {
        val owner = sockets.register(Closeable {})
        val first = report(owner, "192.168.1.1")
        assertFalse(send(first) { null })
        owner.revokeEndpointPublication()
        handoff.prepare(null)
        assertNull(handoff.take())
        assertEquals(0, deferredCommits)
    }

    @Test fun nullSnapshotWithoutBluetoothAllowsSubsequentFreshConnection() {
        handoff.prepare(null)
        val fresh = report(sockets.register(Closeable {}), "192.168.1.2")
        assertTrue(send(fresh))
        assertSame(fresh, handoff.take())
        assertEquals(1, commits)
    }
}
