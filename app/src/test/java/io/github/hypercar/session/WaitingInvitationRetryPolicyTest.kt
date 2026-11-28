package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.iap2.RfcommClose

class WaitingInvitationRetryPolicyTest {
    @Test fun retryMarksTheCapturedSocketOnlyWhenItCommitsToTeardown() {
        val events = mutableListOf<String>()
        val request = WaitingInvitationRetryPolicy.Request(9, RfcommClose({ events += "mark" }) { events += "close" })
        assertFalse(request.mayCommit(10, true, true, false, false))
        assertFalse(request.mayCommit(9, false, true, false, false))
        assertFalse(request.mayCommit(9, true, true, true, false))
        // CTRL already published the invitation, but the endpoint polling loop still says waiting.
        assertFalse(request.mayCommit(9, true, true, false, true))
        assertTrue(events.isEmpty())
        if (request.mayCommit(9, true, true, false, false)) {
            val close = request.markLocalStop()
            events += "restart"
            close!!.close()
        }
        assertEquals(listOf("mark", "restart", "close"), events)
    }

    @Test fun onlyManualInviteWaitRestarts() {
        assertTrue(WaitingInvitationRetryPolicy.shouldSoftRestart(true, true, true, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(false, true, true, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(true, false, true, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(true, true, false, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(true, true, true, true))
    }
}
