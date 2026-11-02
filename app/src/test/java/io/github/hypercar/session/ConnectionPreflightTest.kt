package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class ConnectionPreflightTest {
    @Test fun missingDependenciesHaveSpecificAdviceWithoutDecidingConnectionPolicy() {
        val findings = ConnectionPreflight.findings(ConnectionPreflight.State(false, false, false, false))
        assertEquals(4, findings.size)
        assertTrue(findings.any { it.contains("降级路径") })
        assertTrue(findings.any { it.contains("附近设备") })
    }

    @Test fun readyDeviceHasNoWarningAndUnknownBluetoothIsNotReportedOff() {
        assertTrue(ConnectionPreflight.findings(ConnectionPreflight.State(true, true, true, true)).isEmpty())
        val unknown = ConnectionPreflight.findings(ConnectionPreflight.State(null, true, null, true))
        assertEquals(1, unknown.size)
        assertTrue(unknown.single().contains("未知"))
        assertFalse(unknown.single().contains("未开启"))
    }
}
