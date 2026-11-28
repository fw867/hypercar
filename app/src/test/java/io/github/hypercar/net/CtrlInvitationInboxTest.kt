package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.iap2.BmwCafeReservations
import java.io.Closeable

class CtrlInvitationInboxTest {
    @Test fun confirmationAfterTheRetryDeadlineCannotPublishBeforeTheNextPoll() {
        var now = 100L
        val requests = CtrlInvitationInbox<String, String, String>({ now }) { _, _ -> fail("late invitation published") }
        val epoch = requests.select("A")!!
        val receipt = requests.accept(epoch)!!
        requests.advertise(epoch)
        assertTrue(requests.beginAcknowledgement(receipt))
        requests.acknowledge(receipt, "local", "waiting")
        assertFalse(requests.retireConnectRequests(200L))
        now = 201L
        requests.addresses(epoch, setOf("local"))
        assertNull(requests.latest.get())
        assertTrue(requests.retireConnectRequests(200L))
    }

    @Test fun retryCannotDiscardAnAcknowledgedInvitationWaitingForAddressConfirmation() {
        val epoch = inbox.select("A")!!
        val receipt = inbox.accept(epoch)!!
        assertTrue(inbox.beginAcknowledgement(receipt))
        inbox.offer(receipt, "local", "confirmed after ACK")
        inbox.finishAcknowledgement(receipt)
        assertFalse(inbox.retireConnectRequests())
        inbox.advertise(epoch)
        inbox.addresses(epoch, setOf("local"))
        assertEquals("confirmed after ACK", inbox.latest.get())
    }

    @Test fun manualRetryStopsWaitingForAnAcknowledgedInvitationAtItsDeadline() {
        var now = 100L
        val requests = CtrlInvitationInbox<String, String, String>({ now }) { _, _ -> fail("old invitation published") }
        val epoch = requests.select("A")!!
        val receipt = requests.accept(epoch)!!
        assertTrue(requests.beginAcknowledgement(receipt))
        requests.offer(receipt, "local", "not confirmed")
        requests.finishAcknowledgement(receipt)
        assertFalse(requests.retireConnectRequests(200L))
        now = 200L
        assertTrue(requests.retireConnectRequests(200L))
        requests.advertise(epoch)
        requests.addresses(epoch, setOf("local"))
        assertNull(requests.latest.get())
        assertFalse(requests.beginAcknowledgement(requests.accept(epoch)!!))
    }

    @Test fun additionalRequestsCannotExtendTheRetryDeadlineWhileAnAckIsFinishing() {
        var now = 100L
        val requests = CtrlInvitationInbox<String, String, String>({ now }) { _, _ -> }
        val epoch = requests.select("A")!!
        val first = requests.accept(epoch)!!
        assertTrue(requests.beginAcknowledgement(first))
        assertFalse(requests.retireConnectRequests(200L))
        now = 200L
        assertFalse(requests.beginAcknowledgement(requests.accept(epoch)!!))
        assertTrue(requests.canAcknowledge(first))
        requests.finishAcknowledgement(first)
        assertTrue(requests.retireConnectRequests(300L))
    }

    @Test fun retryRetiresLateHeadersAndCannotTakeAnAlreadyAdmittedHttpSuccess() {
        val epoch = inbox.select("A")!!
        val admitted = inbox.accept(epoch)!!
        val late = inbox.accept(epoch)!!
        assertTrue(inbox.beginAcknowledgement(admitted))
        assertFalse(inbox.retireConnectRequests())
        inbox.select("B")
        assertFalse(inbox.canAcknowledge(admitted))
        assertFalse(inbox.retireConnectRequests())
        inbox.finishAcknowledgement(admitted)
        assertTrue(inbox.retireConnectRequests())
        assertFalse(inbox.beginAcknowledgement(late))
        assertFalse(inbox.beginAcknowledgement(inbox.accept(inbox.current!!)!!))
    }

    private val events = mutableListOf<String>()
    private val inbox = CtrlInvitationInbox<String, String, String> { _, invite -> events += invite }

    @Test fun selectedInvitationSurvivesADifferentAddressUntilItsNetworkEpochIsRetired() {
        val requests = CtrlInvitationInbox<String, String, CtrlInvitation<String>> { _, _ -> }
        val wifi = checkNotNull(requests.select("car"))
        requests.advertise(wifi)
        requests.addresses(wifi, setOf("local-v4", "local-v6"))
        val v6 = CtrlInvitation("fe80::1234%wlan0", 100, wifi, "car")
        requests.offer(checkNotNull(requests.accept(wifi)), "local-v6", v6)
        val selected = checkNotNull(requests.latest.get())
        val v4 = CtrlInvitation("192.168.5.1", 118, wifi, "car")
        requests.offer(checkNotNull(requests.accept(wifi)), "local-v4", v4)
        assertSame(v4, requests.latest.get())
        assertEquals("fe80::1234%wlan0", selected.host)
        assertTrue(selected.isOn("car"))
        requests.select(null)
        requests.select("car")
        assertFalse(selected.isOn("car"))
    }

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
            slots.retire(1, currentInvite = epoch::isCurrent)
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
