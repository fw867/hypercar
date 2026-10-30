package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.session.AutomaticConnectionPolicy.IdleAction.*

class AutomaticConnectionPolicyTest {
    @Test fun manualStartWorksWithBothAutomaticModes() {
        for (enabled in listOf(false, true)) {
            assertTrue(AutomaticConnectionPolicy.mayStart(manual = true, autoConnect = enabled))
        }
        assertFalse(AutomaticConnectionPolicy.mayStart(manual = false, autoConnect = false))
        assertTrue(AutomaticConnectionPolicy.mayStart(manual = false, autoConnect = true))
    }

    @Test fun idleSwitchControlsStandbyInsteadOfStartingASession() {
        assertEquals(START_LISTENING, AutomaticConnectionPolicy.modeChanged(true, false))
        assertEquals(STOP_LISTENING, AutomaticConnectionPolicy.modeChanged(false, false))
    }

    @Test fun switchingAutomaticModeNeverInterruptsAnActiveSession() {
        for (enabled in listOf(false, true)) {
            assertEquals(KEEP_SESSION, AutomaticConnectionPolicy.modeChanged(enabled, true))
        }
    }

    @Test fun manualStopRetainsOnlyTheCapabilitiesSelectedByAutomaticMode() {
        assertTrue(AutomaticConnectionPolicy.keepListeningAfterStop(true))
        assertFalse(AutomaticConnectionPolicy.keepListeningAfterStop(false))
    }
}
