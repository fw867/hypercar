package io.github.hypercar.iap2

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class Iap2SessionGateTest {
    @Test fun preparedInboundTicketSurvivesOpeningForHandshakeWithoutEnablingOutboundEarly() {
        val gate = Iap2SessionGate()
        val old = gate.ticket()!!
        gate.setEnabled(false)
        assertNull(gate.inboundTicket())
        gate.prepareInbound()
        val incoming = gate.inboundTicket()!!
        assertNull(gate.ticket())
        assertFalse(gate.allowsOutbound(incoming))
        assertNull(gate.admitInbound(old))
        assertTrue(gate.canHoldInbound(incoming))
        gate.setEnabled(true)
        assertEquals(incoming, gate.admitInbound(incoming))
        assertNull(gate.admitInbound(old))
    }

    @Test fun cancellingPreparationNeverRevivesItsTicketOnOrdinaryStandbyStart() {
        val gate = Iap2SessionGate()
        gate.setEnabled(false)
        gate.prepareInbound()
        val abandoned = gate.inboundTicket()
        gate.setEnabled(false)
        gate.setEnabled(true)
        assertNull(gate.admitInbound(abandoned))
        assertNotNull(gate.admitInbound(gate.inboundTicket()))
    }
    @Test fun admissionKeepsTheTicketObservedAtAcceptEvenAfterListenerResumes() {
        val gate = Iap2SessionGate()
        val oldAccept = gate.ticket()
        gate.setEnabled(false)
        val pausedAccept = gate.ticket()
        gate.setEnabled(true)
        val newAccept = gate.ticket()
        assertNull(gate.admitInbound(oldAccept))
        assertNull(gate.admitInbound(pausedAccept))
        gate.outboundPausedForWifi = true
        assertEquals(newAccept, gate.admitInbound(newAccept))
    }
    @Test fun wifiHandoffLeavesCurrentAndIncomingSessionsUsableAndPausesNewDials() {
        val gate = Iap2SessionGate()
        val current = gate.ticket()!!
        gate.outboundPausedForWifi = true
        assertTrue(gate.allows(current))
        assertTrue(gate.allows(gate.ticket()!!))
        assertFalse(gate.allowsOutbound(current))
        gate.outboundPausedForWifi = false
        assertTrue(gate.allowsOutbound(current))
    }

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
            onFailure = { _, error -> throw error },
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
