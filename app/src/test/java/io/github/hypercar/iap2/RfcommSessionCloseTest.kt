package io.github.hypercar.iap2

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class RfcommSessionCloseTest {
    @Test fun acceptBeforeWorkerLaunchBelongsToTheStopSnapshot() {
        val closed = mutableListOf<String>()
        val owners = RfcommConnections<Closeable>()
        val old = owners.register(Closeable { closed += "old" })
        val stop = owners.captureClose()
        val fresh = owners.register(Closeable { closed += "fresh" })
        stop.noteLocalStop()
        assertTrue(old.localStopRequested)
        assertFalse(fresh.localStopRequested)
        stop.close()
        assertEquals(listOf("old"), closed)
        // Replacing an active session must leave its pending replacement available.
        owners.captureClose(includeWaiting = false).close()
        assertFalse(fresh.localStopRequested)
        owners.captureClose().close()
        assertEquals(listOf("old", "fresh"), closed)
    }

    @Test fun stoppingInterruptsAnInFlightMfiWriteAndDeniesTheNext() {
        val writing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val owner = RfcommTransport(Closeable { closed.countDown() })
        val writer = thread(isDaemon = true) {
            owner.writeMfi {
                writing.countDown()
                closed.await()
            }
        }
        try {
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            val stop = thread(isDaemon = true) { owner.noteLocalStop() }
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            stop.join(3000)
            writer.join(3000)
            assertFalse(writer.isAlive)
            assertThrows(java.io.InterruptedIOException::class.java) {
                owner.writeMfi { fail("MFi reached the socket after stop") }
            }
        } finally {
            closed.countDown()
        }
    }

    @Test fun socketInterruptsWriterBeforeChannelCloseAndReferencesStayPublished() {
        val writing = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val lock = Any()
        val socket = Closeable { socketClosed.countDown() }
        val owner = RfcommTransport(socket)
        val connections = RfcommConnections<Closeable>()
        connections.session.set(owner)
        connections.current.set(owner)
        var observedOwnership = false
        owner.channel = Closeable {
            synchronized(lock) {
                observedOwnership = connections.session.get() === owner && connections.current.get() === owner
            }
        }
        val writer = thread(isDaemon = true) {
            synchronized(lock) {
                writing.countDown()
                socketClosed.await()
            }
        }
        val finished = CountDownLatch(1)
        try {
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            thread(isDaemon = true) {
                owner.finish()
                connections.forget(owner)
                finished.countDown()
            }
            assertTrue("teardown waited for the writer before closing its socket", finished.await(3, TimeUnit.SECONDS))
            assertTrue(observedOwnership)
            assertNull(connections.session.get())
            assertNull(connections.current.get())
        } finally {
            socketClosed.countDown()
            writer.join(3000)
        }
    }

    @Test fun lateWorkerOnlyClosesItsOwnResources() {
        val closed = mutableListOf<String>()
        val oldSocket = Closeable { closed += "old socket" }
        val oldChannel = Closeable { closed += "old channel" }
        val newSocket = Closeable { closed += "new socket" }
        val newChannel = Closeable { closed += "new channel" }
        val connections = RfcommConnections<Closeable>()
        val old = RfcommTransport(oldSocket).apply { channel = oldChannel }
        val replacement = RfcommTransport(newSocket).apply { channel = newChannel }
        connections.session.set(old)
        connections.current.set(old)
        val closeOld = connections.captureClose()
        connections.session.set(replacement)
        connections.current.set(replacement)
        closeOld.close()
        connections.forget(old)

        assertEquals(listOf("old socket", "old channel"), closed)
        assertSame(replacement, connections.session.get())
        assertSame(replacement, connections.current.get())
        assertFalse(replacement.isCancelled)
    }

    @Test fun lossDuringRfcommGapLeavesLaterInboundUntouched() {
        val connections = RfcommConnections<Closeable>()
        val loss = connections.captureClose()
        val inbound = RfcommTransport(Closeable { fail("later CarPlay connection was closed") })
        connections.session.set(inbound)
        loss.close()
        assertSame(inbound, connections.session.get())
        assertFalse(inbound.localStopRequested)
    }

    @Test fun readFailureInterruptsWriterBeforeTakingChannelLock() {
        val writing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val lock = Any()
        val owner = RfcommTransport(Closeable { closed.countDown() })
        val writer = thread(isDaemon = true) {
            synchronized(lock) {
                writing.countDown()
                closed.await()
            }
        }
        val finished = CountDownLatch(1)
        val error = IOException("RFCOMM EOF")
        try {
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            thread(isDaemon = true) {
                owner.readFailed(error) { cause ->
                    synchronized(lock) {
                        if (cause === error && !owner.isCancelled) finished.countDown()
                    }
                }
            }
            assertTrue(finished.await(3, TimeUnit.SECONDS))
        } finally {
            closed.countDown()
            writer.join(3000)
        }
    }

    @Test fun capturedDialIsMarkedCancelledBeforeSocketCloseEvenIfAdmittedLater() {
        val observed = mutableListOf<Boolean>()
        lateinit var old: RfcommTransport<Closeable>
        old = RfcommTransport(Closeable { observed += old.isCancelled })
        val connections = RfcommConnections<Closeable>()
        connections.pending.set(old)
        val stop = connections.captureClose()
        connections.pending.set(null)
        connections.session.set(old)
        val next = RfcommTransport(Closeable { fail("new pending socket was closed") })
        connections.pending.set(next)
        stop.noteLocalStop()
        assertTrue(old.localStopRequested)
        assertTrue(observed.isEmpty())
        assertFalse(next.localStopRequested)
        stop.close()
        assertEquals(listOf(true), observed)
        assertNull(connections.session.get())
        assertSame(next, connections.pending.get())
    }

    @Test fun gracefulStopAttributesSubsequentEofWithoutClosingTheSocketEarly() {
        var closed = false
        val owner = RfcommTransport(Closeable { closed = true })
        owner.noteLocalStop()
        assertTrue(owner.localStopRequested)
        assertFalse(owner.isCancelled)
        assertFalse(closed)
        owner.readFailed(IOException("EOF after TEARDOWN")) {
            assertTrue(closed)
            assertTrue(owner.isCancelled)
        }
    }

    @Test fun lateLocalStopDoesNotRelabelAnEarlierPeerDisconnect() {
        val owner = RfcommTransport(Closeable {})
        owner.endIo()
        owner.close()
        assertFalse(owner.localStopRequested)
        assertFalse(owner.isCancelled)
    }
}
