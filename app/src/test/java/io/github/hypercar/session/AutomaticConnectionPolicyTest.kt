package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.session.AutomaticConnectionPolicy.IdleAction.*

class AutomaticConnectionPolicyTest {
    @Test fun pauseBlocksAllAutomaticStartsButNeverManualStarts() {
        for (enabled in listOf(false, true)) {
            assertTrue(AutomaticConnectionPolicy.mayStart(true, enabled, paused = true))
            assertFalse(AutomaticConnectionPolicy.mayStart(false, enabled, paused = true))
            assertFalse(AutomaticConnectionPolicy.keepListeningAfterStop(enabled, paused = true))
            assertEquals(enabled, AutomaticConnectionPolicy.keepIdentityAfterStop(enabled, paused = true))
            assertEquals(if (enabled) KEEP_IDENTITY else STOP_LISTENING, AutomaticConnectionPolicy.modeChanged(enabled, false, paused = true))
            assertEquals(KEEP_SESSION, AutomaticConnectionPolicy.modeChanged(enabled, true, paused = true))
        }
        assertTrue(AutomaticConnectionPolicy.mayStart(false, true, paused = false))
        assertEquals(START_LISTENING, AutomaticConnectionPolicy.modeChanged(true, false, paused = false))
    }

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
