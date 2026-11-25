package io.github.hypercar.net

import android.net.wifi.SupplicantState
import io.github.hypercar.net.WifiJoinDiagnostics.StationProgress
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

    @Test fun stationProgressKeepsTheExactRequestDuringAssociationOrWhenProgressIsUnobservable() {
        for (state in listOf(SupplicantState.AUTHENTICATING, SupplicantState.ASSOCIATING,
            SupplicantState.ASSOCIATED, SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE)) {
            assertEquals(StationProgress.ACTIVE, WifiJoinDiagnostics.stationProgress("true", state, false))
            assertEquals(StationProgress.ACTIVE, WifiJoinDiagnostics.stationProgress("unknown", state, false))
        }
        assertEquals(StationProgress.ACTIVE, WifiJoinDiagnostics.stationProgress("true", SupplicantState.COMPLETED, false))
        assertEquals(StationProgress.IDLE, WifiJoinDiagnostics.stationProgress("false", SupplicantState.COMPLETED, false))
        assertEquals(StationProgress.IDLE, WifiJoinDiagnostics.stationProgress("unknown", SupplicantState.SCANNING, false))
        assertEquals(StationProgress.UNKNOWN, WifiJoinDiagnostics.stationProgress("unknown", SupplicantState.COMPLETED, false))
        assertEquals(StationProgress.UNKNOWN, WifiJoinDiagnostics.stationProgress("false", SupplicantState.COMPLETED, true))
    }
}
