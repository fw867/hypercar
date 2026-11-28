package io.github.hypercar.iap2

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BmwCaffHandoffTest {
    private class Socket : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun queuedCafeLosesToPauseOrInvitationButClaimedCafeStaysOpen() {
        for (cancelFirst in listOf(true, false)) {
            val slots = BmwCafeReservations<Socket>()
            val old = Socket()
            val incoming = Socket()
            lateinit var queued: BmwCaffHandoff<Socket>
            assertTrue(slots.reserve(7, old, incoming) { queued = it })
            slots.exited(old)
            slots.retire(6) // A late event from a previous visit to the same car.
            if (cancelFirst) slots.retire(7)
            assertEquals(!cancelFirst, queued.claim(incoming))
            slots.finish(queued)
            slots.retire(7)
            slots.exited(old)
            assertEquals(if (cancelFirst) 1 else 0, incoming.closes)
            assertEquals(0, old.closes)
            assertFalse(slots.reserve(7, old, Socket()) { fail() })
            assertTrue(slots.reserve(8, old, Socket()) {})
        }
    }

    @Test fun retiredReservationReleasesItsSlotBeforeTheOldWorkerExits() {
        val slots = BmwCafeReservations<Socket>()
        val old = Socket()
        val incoming = Socket()
        assertTrue(slots.reserve(4, old, incoming) { fail("cancelled worker queued") })
        slots.retire(4)
        assertNull(slots.pending)
        val nextOld = Socket()
        assertTrue(slots.reserve(5, nextOld, Socket()) {})
        slots.exited(old)
        assertSame(nextOld, slots.pending?.old)
        assertEquals(1, incoming.closes)
    }

    @Test fun standbyCanHandOffASamePeerAfterEofWithoutAnAirplayAttempt() {
        val slots = BmwCafeReservations<RfcommTransport<Socket>>()
        val old = RfcommTransport(Socket()).apply { endIo() }
        val incoming = RfcommTransport(Socket())
        var started = false
        assertEquals(true, slots.reserveAfterPeerClose(0, old, incoming, { _, _ -> true }) {
            started = it.claim(incoming)
        })
        slots.exited(old)
        assertTrue(started)
        assertEquals(0, incoming.socket.closes)
    }

    @Test fun expiredInvitationCannotCancelOrRefuseTheReplacementOnASharedNetwork() {
        val slots = BmwCafeReservations<Socket>()
        val peer = Socket()
        val differentPeer = Socket()
        var current = true
        slots.retire(11, matchesPeer = { it === peer }) { current }
        assertFalse(slots.reserve(11, Socket(), peer) { fail() })
        assertTrue(slots.reserve(11, Socket(), differentPeer) {})
        assertFalse(slots.retire(11, matchesPeer = { it === peer }) { current })
        assertEquals(0, differentPeer.closes)
        slots.clear()
        current = false
        assertTrue(slots.reserve(11, Socket(), peer) {})
        assertFalse(slots.retire(11) { current })
        assertEquals(0, peer.closes)
    }

    @Test fun onlyTheCurrentRoundCanRevokeASocketCarriedThroughRetry() {
        val slots = BmwCafeReservations<Socket>()
        val incoming = Socket()
        assertTrue(slots.reserve(9, Socket(), incoming) {})
        assertFalse(slots.retire(9) { false })
        assertEquals(0, incoming.closes)
        assertTrue(slots.retire(10) { true })
        assertEquals(1, incoming.closes)
        assertNull(slots.pending)
    }

    @Test fun deadlineReleasesClosedOwnerOnceAndLeavesNewReadersFirstDetectIntact() {
        val slots = BmwCafeReservations<Socket>()
        val first = Iap2LinkEngine.MARKER
        val queued = java.io.ByteArrayInputStream(first)
        val old = Socket()
        val incoming = Socket()
        var replies = 0
        assertTrue(slots.reserve(12, old, incoming) { held ->
            assertTrue(held.claim(incoming))
            val engine = Iap2LinkEngine()
            engine.start(0, passive = true)
            engine.feed(queued.readBytes(), 1)
            assertArrayEquals(first, engine.takeOutput())
            replies++
            slots.finish(held)
        })
        val held = checkNotNull(slots.pending)
        assertEquals(first.size, queued.available())
        assertTrue(slots.expire(held) { it === old })
        slots.exited(old)
        assertFalse(slots.expire(held) { fail(); false })
        assertEquals(1, replies)
        assertEquals(0, incoming.closes)
    }

    @Test fun deadlineCancelsOnlyItsOwnSocketIfTheOldSocketCannotBeReleased() {
        val slots = BmwCafeReservations<Socket>()
        val old = Socket()
        val incoming = Socket()
        assertTrue(slots.reserve(12, old, incoming) { fail("unclosed predecessor") })
        val held = checkNotNull(slots.pending)
        assertTrue(slots.expire(held) { false })
        assertEquals(1, incoming.closes)
        val next = Socket()
        assertTrue(slots.reserve(12, old, next) {})
        assertFalse(slots.expire(held) { true })
        assertSame(next, slots.pending?.incoming)
    }

    @Test fun lateWorkerAndOldPauseCannotReleaseNextVisitsReservation() {
        val slots = BmwCafeReservations<Socket>()
        val previous = Socket()
        val first = Socket()
        lateinit var late: BmwCaffHandoff<Socket>
        assertTrue(slots.reserve(10, previous, first) { late = it })
        slots.exited(previous)
        assertTrue(slots.retire(10))
        slots.clear()
        val newOld = Socket()
        val second = Socket()
        lateinit var next: BmwCaffHandoff<Socket>
        assertTrue(slots.reserve(11, newOld, second) { next = it })
        slots.finish(late)
        assertFalse(slots.retire(10))
        assertFalse(late.claim(first))
        slots.exited(newOld)
        assertTrue(next.claim(second))
        slots.finish(next)
        assertFalse(slots.retire(11))
        assertEquals(0, second.closes)
    }

    @Test fun onlyTheCtrlConnectGetCanCancelAnInboundHandoff() {
        val ctrl = io.github.hypercar.net.CarPlayCtrlAdvertiser
        assertTrue(ctrl.isConnectRequest("GET /ctrl-int/1/connect HTTP/1.1"))
        assertTrue(ctrl.isConnectRequest("GET /ctrl-int/1/connect?id=1 HTTP/1.0"))
        assertFalse(ctrl.isConnectRequest("GET /ctrl-int/1/connected HTTP/1.1"))
        assertFalse(ctrl.isConnectRequest("POST /ctrl-int/1/connect HTTP/1.1"))
        assertFalse(ctrl.isConnectRequest("GET /ctrl-int/1/connect"))
    }

    @Test fun sameAttemptSwitchOfNetworkAllowsBReservationAfterConfirmedAInvite() {
        val slots = BmwCafeReservations<Socket>()
        var onA = true
        slots.retire(9) { onA }
        assertFalse(slots.reserve(9, Socket(), Socket()) {})
        onA = false
        val old = Socket()
        val incoming = Socket()
        lateinit var queued: BmwCaffHandoff<Socket>
        assertTrue(slots.reserve(9, old, incoming) { queued = it })
        slots.exited(old)
        assertFalse(slots.retire(9) { onA })
        assertTrue(queued.claim(incoming))
        slots.finish(queued)
        assertFalse(slots.retire(9) { true })
        assertEquals(0, incoming.closes)
    }

    @Test fun acceptedCafeSurvivesMoreThan500msAndOnlyMatchingCaffExitReleasesIt() {
        val old = Socket()
        val incoming = Socket()
        val ready = CountDownLatch(1)
        val held = BmwCaffHandoff(old, incoming) { ready.countDown() }
        val allowCleanup = CountDownLatch(1)
        val worker = Thread {
            allowCleanup.await()
            old.close()
            held.exited(old)
        }
        worker.start()
        try {
            held.exited(Socket())
            assertFalse(ready.await(550, TimeUnit.MILLISECONDS))
            assertFalse(held.claim(incoming))
            assertEquals(0, incoming.closes)
        } finally {
            allowCleanup.countDown()
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
        assertEquals(1, old.closes)
        assertEquals(0, incoming.closes)
        assertEquals(0L, ready.count)
        assertFalse(held.claim(Socket()))
        assertTrue(held.claim(incoming))
        held.exited(old)
        held.cancel()
        assertFalse(held.claim(incoming))
        assertEquals(0, incoming.closes)
    }

    @Test fun closedPrimaryCafeHoldsSamePeersReplacementUntilTheExactWorkerExits() {
        val active = RfcommConnections<Socket>()
        val slots = BmwCafeReservations<RfcommTransport<Socket>>()
        val old = active.register(Socket())
        active.admit(old)
        val handedOffCredentials = old.endpointGeneration()
        val incoming = active.register(Socket())
        val unrelated = active.register(Socket())
        var starts = 0
        fun hold() = slots.reserveAfterPeerClose(12, old, incoming, { a, b ->
            a === old.socket && b === incoming.socket
        }) { handoff ->
            assertTrue(handoff.claim(incoming))
            active.admit(incoming)
            slots.finish(handoff)
            starts++
        }

        assertNull(hold())
        old.readFailed(java.io.EOFException()) {
            assertEquals(1, old.socket.closes)
            assertEquals(true, hold())
        }
        assertTrue(handedOffCredentials.isCurrent)
        slots.exited(unrelated)
        assertEquals(0, starts)
        assertEquals(0, incoming.socket.closes)
        assertSame(old, active.current.get())
        active.forget(old)
        slots.exited(old)
        slots.exited(old)
        active.forget(old)
        assertEquals(1, starts)
        assertSame(incoming, active.current.get())
        assertEquals(0, incoming.socket.closes)
    }

    @Test fun primaryPeerCloseReservationHonorsPeerAndCurrentInvitation() {
        val slots = BmwCafeReservations<RfcommTransport<Socket>>()
        val old = RfcommTransport(Socket())
        val incoming = RfcommTransport(Socket())
        old.endIo()
        assertNull(slots.reserveAfterPeerClose(13, old, incoming, { _, _ -> false }) { fail() })
        slots.retire(13) { true }
        assertEquals(false, slots.reserveAfterPeerClose(13, old, incoming, { _, _ -> true }) { fail() })
        val cancelled = RfcommTransport(Socket())
        cancelled.cancelSocket()
        assertNull(slots.reserveAfterPeerClose(14, cancelled, incoming, { _, _ -> true }) { fail() })
    }

    @Test fun cancellationBeforeOrAfterExitOnlyClosesTheHeldSocketOnce() {
        for (alreadyExited in listOf(false, true)) {
            val old = Socket()
            val incoming = Socket()
            var releases = 0
            val held = BmwCaffHandoff(old, incoming) { releases++ }
            if (alreadyExited) held.exited(old)
            held.cancel()
            held.exited(old)
            held.cancel()
            assertFalse(held.claim(incoming))
            assertEquals(if (alreadyExited) 1 else 0, releases)
            assertEquals(1, incoming.closes)
            assertEquals(0, old.closes)
        }
    }
}
