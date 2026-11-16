package io.github.hypercar.net

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiJoinDiagnosticsTest {
    @Test fun redactedStationIsUnknownEvenWhenAnotherNetworkIsConnected() {
        for (name in listOf(null, "", "<unknown ssid>")) {
            assertEquals("unknown", WifiJoinDiagnostics.ssidMatch(name, "Car"))
        }
        assertEquals("false", WifiJoinDiagnostics.ssidMatch("\"Phone hotspot\"", "Car"))
        assertEquals("true", WifiJoinDiagnostics.ssidMatch("\"Car\"", "Car"))
    }

    @Test fun frameworkCodesKeepSelectionAssociationAndIpFailuresSeparate() {
        val reasons = listOf("unknown", "association", "authentication", "ip_provisioning",
            "ap_not_found", "ap_no_response", "user_reject")
        for ((code, reason) in reasons.withIndex()) {
            assertEquals(reason, WifiJoinDiagnostics.failureReason(code))
        }
        assertEquals("unknown", WifiJoinDiagnostics.failureReason(31))
    }
}
