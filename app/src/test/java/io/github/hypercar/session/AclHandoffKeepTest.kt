package io.github.hypercar.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AclHandoffKeepTest {
    @Test fun boundSessionStaysUp() {
        assertTrue(AclHandoffKeep.keepInviteWait(true, false, true, false))
        assertTrue(AclHandoffKeep.keepInviteWait(true, true, true, false))
    }

    @Test fun knownCarWifiKeepsUnboundNegotiate() {
        assertTrue(AclHandoffKeep.keepInviteWait(true, false, false, true))
    }

    @Test fun missingCarWifiOrIdleOrStreamingUnboundDisconnects() {
        assertFalse(AclHandoffKeep.keepInviteWait(true, false, false, false))
        assertFalse(AclHandoffKeep.keepInviteWait(false, false, false, true))
        assertFalse(AclHandoffKeep.keepInviteWait(true, true, false, true))
    }
}
