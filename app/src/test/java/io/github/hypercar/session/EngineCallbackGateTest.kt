package io.github.hypercar.session

import io.github.hypercar.net.PendingInvitation
import org.junit.Assert.*
import org.junit.Test

class EngineCallbackGateTest {
    @Test fun projectionRetirementIsImmediateAndCannotBeUndoneByTheOldEngine() {
        val gate = EngineCallbackGate()
        val first = gate.begin()
        gate.setProjection(first, true)
        assertEquals(first, gate.projectionOwner.value)
        gate.endConnection { true }
        gate.setProjection(first, true)
        assertNull(gate.projectionOwner.value)
        val next = gate.begin()
        gate.setProjection(next, true)
        gate.setProjection(first, false)
        assertEquals(next, gate.projectionOwner.value)
        gate.retire()
        assertNull(gate.projectionOwner.value)
    }
    private class Invite(val address: String)

    @Test fun sameAddressInvitationSurvivesOldCleanupAndLateEof() {
        val gate = EngineCallbackGate()
        val first = gate.begin()
        val consumed = Invite("172.16.222.97")
        val invitations = PendingInvitation<Invite>().also { it.set(consumed) }
        val fresh = Invite(consumed.address)
        invitations.set(fresh)
        gate.retire()
        assertFalse(invitations.discard(consumed))
        assertSame(fresh, invitations.get())
        val second = gate.begin()
        val events = mutableListOf<String>()
        gate.deliver(first) { events += "old EOF" }
        gate.deliver(first) { events += "old stopping" }
        gate.deliver(second) { events += "new connected" }
        assertEquals(listOf("new connected"), events)
        assertTrue(gate.isCurrent(second))
    }

    @Test fun queuedFailureMustRecheckOwnerBeforeCleanup() {
        val gate = EngineCallbackGate()
        val old = gate.begin()
        var pending: (() -> Unit)? = null
        var stopped = false
        gate.deliver(old) { pending = { if (gate.isCurrent(old)) stopped = true } }
        gate.retire()
        val next = gate.begin()
        pending!!()
        assertFalse(stopped)
        assertTrue(gate.isCurrent(next))
    }

    @Test fun waitingHasNoEngineOwnerAndRejectsAllOldCallbacks() {
        val gate = EngineCallbackGate()
        val old = gate.begin()
        gate.retire()
        gate.deliver(old) { fail("waiting must not accept old failed/EOF/cleanup") }
        assertFalse(gate.isCurrent(0))
        assertFalse(gate.isCurrent(old))
    }

    @Test fun newStartCannotOvertakeAnAcceptedCallbackReducer() {
        val gate = EngineCallbackGate()
        val old = gate.begin()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val callback = executor.submit {
                gate.deliver(old) {
                    entered.countDown()
                    assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val next = executor.submit<Long> { gate.begin() }
            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                next.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            release.countDown()
            callback.get(2, java.util.concurrent.TimeUnit.SECONDS)
            val owner = next.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(gate.isCurrent(owner))
            gate.deliver(old) { fail("old callback must not cross the new start") }
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
