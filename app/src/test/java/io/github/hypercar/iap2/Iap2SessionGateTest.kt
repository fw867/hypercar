package io.github.hypercar.iap2

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class Iap2SessionGateTest {
    @Test fun pausedTicketsNeverReviveWhenManualStartOrCooldownResumes() {
        val gate = Iap2SessionGate()
        val queuedBeforeStop = gate.ticket()!!
        gate.setEnabled(false)
        assertNull(gate.ticket())
        assertFalse(gate.allows(queuedBeforeStop))
        gate.setEnabled(true)
        val manualStart = gate.ticket()!!
        assertFalse(gate.allows(queuedBeforeStop))
        assertTrue(gate.allows(manualStart))
        gate.setEnabled(true) // repeated standby update must not retire an active session
        assertTrue(gate.allows(manualStart))
    }

    @Test fun delayedInboundWorkerCannotStartAfterPauseAndResume() {
        val gate = Iap2SessionGate()
        val ticket = gate.ticket()!!
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val worker = Thread { ready.countDown(); release.await(); started.set(gate.allows(ticket)) }
        worker.start(); ready.await()
        gate.setEnabled(false); gate.setEnabled(true)
        release.countDown(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertFalse(started.get())
        assertTrue(gate.allows(gate.ticket()!!))
    }

    private class Socket : Closeable { var closed = false; override fun close() { closed = true } }

    @Test fun cooldownRejectsConnectionsWithoutRemovingOrReregisteringSdpListener() {
        val gate = Iap2SessionGate()
        var opens = 0
        var server: Socket? = null
        val listener = RecoveringListener<Socket, Socket>(
            ownerAlive = { true },
            open = { Socket().also { opens++; server = it } },
            accept = { Socket() },
            onFailure = { throw it },
            awaitRetry = {},
        )
        val first = listener.acceptNext()!!
        assertTrue(gate.allows(gate.ticket()!!)); first.close()
        gate.setEnabled(false)
        repeat(10) {
            val inbound = listener.acceptNext()!!
            assertNull(gate.ticket()) // the production admission path closes before handshake
            inbound.close()
            assertTrue(listener.isRegistered)
            assertFalse(server!!.closed)
        }
        gate.setEnabled(true)
        val resumed = listener.acceptNext()!!
        assertTrue(gate.allows(gate.ticket()!!)); resumed.close()
        assertEquals(1, opens)
        listener.close()
        assertTrue(server!!.closed)
    }
}
