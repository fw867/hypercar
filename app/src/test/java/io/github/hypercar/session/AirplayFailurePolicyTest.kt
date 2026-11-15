package io.github.hypercar.session
import org.junit.Assert.*
import org.junit.Test

class AirplayFailurePolicyTest {
    @Test fun id9ProtocolRejectionWaitsForFreshEvidence() {
        for (message in listOf("屏幕 SETUP 被拒 RTSP/1.0 400 Bad Request",
            "ID9 RESOURCES rejected RTSP status=422", "ID9 SCREEN_CLAIM rejected RTSP status=422",
            "RECORD rejected RTSP status=400")) {
            assertTrue(message, AirplayFailurePolicy.waitsForNewInvitation("bmw_id9", message))
            for (other in listOf("bmw_id85","bmw_id6","mercedes_mbux","volkswagen_mib","winplay")) {
                assertFalse(other, AirplayFailurePolicy.waitsForNewInvitation(other,message))
            }
        }
    }
    @Test fun networkAndIncidentalNumbersKeepExistingRetryPolicy() {
        for (message in listOf("屏幕 SETUP 超时", "SETUP RTSP/1.0 500 Internal Error", "端口4000超时", "SETUP body=400 bytes", "feedback RTSP/1.0 422 Error"))
            assertFalse(message, AirplayFailurePolicy.waitsForNewInvitation("bmw_id9",message))
    }
}
