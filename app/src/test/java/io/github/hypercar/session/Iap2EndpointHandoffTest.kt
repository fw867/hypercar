package io.github.hypercar.session

import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2EndpointUpdate
import io.github.hypercar.iap2.RfcommConnections
import io.github.hypercar.iap2.RfcommTransport
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class Iap2EndpointHandoffTest {
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

    @Test fun nullSnapshotWithoutBluetoothAllowsSubsequentFreshConnection() {
        handoff.prepare(null)
        val fresh = report(sockets.register(Closeable {}), "192.168.1.2")
        assertTrue(send(fresh))
        assertSame(fresh, handoff.take())
        assertEquals(1, commits)
    }
}
