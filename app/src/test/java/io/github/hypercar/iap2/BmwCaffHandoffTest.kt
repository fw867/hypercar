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

    @Test fun retiredReservationBlocksDuplicateUntilOldCleanupThenReleasesItsSlot() {
        val slots = BmwCafeReservations<Socket>()
        val old = Socket()
        val incoming = Socket()
        assertTrue(slots.reserve(4, old, incoming) { fail("cancelled worker queued") })
        slots.retire(4)
        assertNotNull(slots.pending)
        slots.exited(Socket())
        assertNotNull(slots.pending)
        slots.exited(old)
        assertNull(slots.pending)
        assertEquals(1, incoming.closes)
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
