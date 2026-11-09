package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class WaitingInvitationRetryPolicyTest {
    @Test fun onlyManualInviteWaitRestarts() {
        assertTrue(WaitingInvitationRetryPolicy.shouldSoftRestart(true, true, true, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(false, true, true, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(true, false, true, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(true, true, false, false))
        assertFalse(WaitingInvitationRetryPolicy.shouldSoftRestart(true, true, true, true))
    }
}
