package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.iap2.BmwCafeReservations
import java.io.Closeable

class CtrlInvitationInboxTest {
    private val events = mutableListOf<String>()
    private val inbox = CtrlInvitationInbox<String, String, String> { _, invite -> events += invite }

    @Test fun lateAWithIdenticalAddressesCannotPublishOrConsumeB() {
        val a = inbox.select("A")!!
        val old = inbox.accept(a)!!
        val b = inbox.select("B")!!
        inbox.advertise(b)
        inbox.addresses(b, setOf("local"))
        val current = inbox.accept(b)!!
        inbox.offer(current, "local", "B invite")
        inbox.offer(old, "local", "A invite")
        inbox.addresses(a, setOf("local"))
        assertNull(inbox.accept(a))
        assertEquals(listOf("B invite"), events)
        assertEquals("B invite", inbox.latest.get())
    }

    @Test fun validAckPendingAddressIsDiscardedIfEpochChangesBeforeConfirmation() {
        val a = inbox.select("A")!!
        inbox.advertise(a)
        inbox.offer(inbox.accept(a)!!, "local", "ACKed on A")
        assertTrue(inbox.awaitingConfirmation)
        assertTrue(events.isEmpty())

        val b = inbox.select("B")!!
        inbox.advertise(b)
        inbox.addresses(b, setOf("local"))
        inbox.addresses(a, setOf("local"))
        assertFalse(inbox.awaitingConfirmation)
        assertNull(inbox.latest.get())
        assertTrue(events.isEmpty())
    }

    @Test fun earlyBAwaitsBothAdvertisementAndLocalAddressInEitherOrder() {
        for (linksFirst in listOf(true, false)) {
            inbox.select(null)
            val b = inbox.select("B")!!
            val received = inbox.accept(b)!!
            inbox.offer(received, "local", "early")
            inbox.addresses(b, setOf("other"))
            val previous = events.size
            if (linksFirst) inbox.addresses(b, setOf("local")) else inbox.advertise(b)
            assertNull(inbox.latest.get())
            assertTrue(inbox.awaitingConfirmation)
            if (linksFirst) inbox.advertise(b) else inbox.addresses(b, setOf("local"))
            inbox.advertise(b)
            inbox.addresses(b, setOf("local"))
            assertEquals(previous + 1, events.size)
            assertEquals("early", inbox.latest.get())
            assertFalse(inbox.awaitingConfirmation)
        }
    }

    @Test fun sameAddressReinviteCannotBeOverwrittenBySlowerRequestOrConsumedByOldCleanup() {
        val b = inbox.select("B")!!
        inbox.advertise(b)
        inbox.addresses(b, setOf("same"))
        val first = inbox.accept(b)!!
        inbox.offer(first, "same", "one")
        val slow = inbox.accept(b)!!
        inbox.offer(inbox.accept(b)!!, "same", "three")
        inbox.offer(slow, "same", "two")
        assertFalse(inbox.latest.discard("one"))
        assertEquals("three", inbox.latest.get())
        assertEquals(listOf("one", "three"), events)
        inbox.select(null)
        inbox.offer(first, "same", "late")
        assertNull(inbox.latest.get())
        assertEquals(2, events.size)
    }

    @Test fun onlyConfirmedBMayCancelTheQueuedBluetoothHandoff() {
        val slots = BmwCafeReservations<Closeable>()
        val invited = mutableListOf<String>()
        val requests = CtrlInvitationInbox<String, String, String> { epoch, value ->
            slots.retire(1, epoch::isCurrent)
            invited += value
        }
        val a = requests.select("A")!!
        requests.advertise(a)
        requests.addresses(a, setOf("local"))
        val lateA = requests.accept(a)!!
        val b = requests.select("B")!!
        var closes = 0
        val old = Closeable {}
        assertTrue(slots.reserve(1, old, Closeable { closes++ }) {})
        slots.exited(old)
        requests.offer(requests.accept(b)!!, "local", "B")
        requests.offer(lateA, "local", "A")
        requests.advertise(b)
        assertEquals(0, closes)
        assertTrue(invited.isEmpty())
        requests.addresses(b, setOf("local"))
        requests.addresses(b, setOf("local"))
        assertEquals(1, closes)
        assertEquals(listOf("B"), invited)
        assertEquals("B", requests.latest.get())
    }
}
