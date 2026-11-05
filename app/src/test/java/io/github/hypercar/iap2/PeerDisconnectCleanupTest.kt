package io.github.hypercar.iap2

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class PeerDisconnectCleanupTest {
    private class Socket(val peer: String) { var closed = 0 }

    @Test fun closesCapturedPeerOnce() {
        val socket = Socket("BMW")
        val ref = AtomicReference<Socket?>(socket)
        val close = PeerDisconnectCleanup.capture(ref, { it.peer == "BMW" }, { it.closed++ })!!
        close(); close()
        assertNull(ref.get())
        assertEquals(1, socket.closed)
    }

    @Test fun ignoresAnotherPeer() {
        val socket = Socket("DiPlay")
        val ref = AtomicReference<Socket?>(socket)
        assertNull(PeerDisconnectCleanup.capture(ref, { it.peer == "BMW" }, { it.closed++ }))
        assertSame(socket, ref.get())
        assertEquals(0, socket.closed)
    }

    @Test fun delayedAclCleanupCannotCloseNewSessionEvenWithSameAddress() {
        val old = Socket("BMW")
        val next = Socket("BMW")
        val ref = AtomicReference<Socket?>(old)
        val close = PeerDisconnectCleanup.capture(ref, { it.peer == "BMW" }, { it.closed++ })!!
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread { ready.countDown(); release.await(); close() }
        worker.start(); ready.await()
        ref.set(next)
        release.countDown(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertSame(next, ref.get())
        assertEquals(0, next.closed)
    }

    @Test fun emptySessionDoesNotCreateDeferredWildcardCleanup() {
        val ref = AtomicReference<Socket?>(null)
        assertNull(PeerDisconnectCleanup.capture(ref, { true }, { it.closed++ }))
    }
}
