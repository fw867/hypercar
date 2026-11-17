package io.github.hypercar.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AclHandoffKeepTest {
    @Test fun refreshesOnlyWhileWaitingOnCarWifi() {
        assertTrue(AclHandoffKeep.shouldRefreshInvitation(true, false, true))
        assertFalse(AclHandoffKeep.shouldRefreshInvitation(true, true, true))
        assertFalse(AclHandoffKeep.shouldRefreshInvitation(true, false, false))
        assertFalse(AclHandoffKeep.shouldRefreshInvitation(false, false, true))
    }
}
