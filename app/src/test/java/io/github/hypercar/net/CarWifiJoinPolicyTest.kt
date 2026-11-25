package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarWifiJoinPolicyTest {
    @Test fun userRejectionAndAuthenticationFailureNeverCauseASecondPrompt() {
        for (stage in listOf("user_reject", "authentication", "ip_provisioning")) {
            assertFalse(CarWifiJoinPolicy.canRetryHint(stage, timedOut = false))
            assertFalse(CarWifiJoinPolicy.canRetryHint(stage, timedOut = true))
        }
        assertFalse(CarWifiJoinPolicy.canRetryHint(null, timedOut = false))
        assertTrue(CarWifiJoinPolicy.canRetryHint(null, timedOut = true))
        for (stage in listOf("association", "ap_not_found", "ap_no_response"))
            assertTrue(CarWifiJoinPolicy.canRetryHint(stage, timedOut = false))
    }
    @Test fun declaredSecurityControlsAuthenticationWithoutBreakingLegacyCredentials() {
        assertEquals(CarWifiJoinPolicy.Security.WPA2, CarWifiJoinPolicy.security(null, "password"))
        assertEquals(CarWifiJoinPolicy.Security.WPA2, CarWifiJoinPolicy.security(0, "password"))
        assertEquals(CarWifiJoinPolicy.Security.OPEN, CarWifiJoinPolicy.security(0, ""))
        assertEquals(CarWifiJoinPolicy.Security.WPA2, CarWifiJoinPolicy.security(2, "password"))
        assertEquals(CarWifiJoinPolicy.Security.WPA2, CarWifiJoinPolicy.security(3, "password"))
        assertEquals(CarWifiJoinPolicy.Security.WPA3, CarWifiJoinPolicy.security(4, "password"))
        for (type in listOf(1, 99, -1)) assertNull(CarWifiJoinPolicy.security(type, "password"))
        for (type in 1..4) assertNull(CarWifiJoinPolicy.security(type, ""))
    }

    @Test fun currentChannelProvidesFrequencyWithoutInventingAnApIdentity() {
        assertEquals(5785, CarWifiJoinPolicy.frequency(157))
        assertEquals(2412, CarWifiJoinPolicy.frequency(1))
        assertEquals(2484, CarWifiJoinPolicy.frequency(14))
        for (channel in listOf(null, 0, -1, 15, 999)) assertNull(CarWifiJoinPolicy.frequency(channel))
    }
}
