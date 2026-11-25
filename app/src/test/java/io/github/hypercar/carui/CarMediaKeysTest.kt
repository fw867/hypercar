package io.github.hypercar.carui

import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class CarMediaKeysTest {
    @Test fun boxB9RequestsPlaybackAndNeverStopsThePlayer() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, CarMediaKeys.keyCode(0xB9))
        assertTrue(CarMediaKeys.requestsPlayback(0xB9, PlaybackState.STATE_PAUSED))
        assertTrue(CarMediaKeys.requestsPlayback(0xB9, null))
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, CarMediaKeys.keyCode(0xB7))
        var delivered = 0
        assertEquals("selected-session", CarMediaKeys.dispatch(0xB9, { delivered = it; true }, { fail("duplicate dispatch") }))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, delivered)
    }
    @Test fun onlyExplicitPlayOrAKnownResumeToggleClaimsAudio() {
        assertTrue(CarMediaKeys.requestsPlayback(0xB0, PlaybackState.STATE_PLAYING))
        assertTrue(CarMediaKeys.requestsPlayback(0xCD, PlaybackState.STATE_PAUSED))
        assertTrue(CarMediaKeys.requestsPlayback(0xCD, PlaybackState.STATE_STOPPED))
        assertFalse(CarMediaKeys.requestsPlayback(0xCD, null))
        for (usage in listOf(0xCD, 0xB1, 0xB5, 0xB6))
            assertFalse(CarMediaKeys.requestsPlayback(usage, PlaybackState.STATE_PLAYING))
    }
    @Test fun consoleControlsMoveFocusWithoutDispatchingPlayerCommands() {
        assertEquals(KeyEvent.KEYCODE_BACK, CarConsoleKey.fromInput(0x30001)?.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, CarConsoleKey.fromInput(0x30002)?.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, CarConsoleKey.fromInput(0x30003)?.keyCode)
        assertEquals(KeyEvent.KEYCODE_TAB, CarConsoleKey.fromInput(0x30008)?.keyCode)
        assertEquals(KeyEvent.META_SHIFT_ON, CarConsoleKey.fromInput(0x30007)?.metaState)
        assertEquals(0, CarConsoleKey.fromInput(0x30008)?.metaState)
        assertNull(CarConsoleKey.fromInput(0xb5))
        assertNull(CarConsoleKey.fromInput(0xb6))
        for (input in 0x30001..0x30008) assertEquals(0, CarMediaKeys.keyCode(input))
    }
    @Test fun consumerUsagesMapToAndroidMediaControls() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, CarMediaKeys.keyCode(0xB5))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, CarMediaKeys.keyCode(0xB6))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, CarMediaKeys.keyCode(0xCD))
        assertEquals(0, CarMediaKeys.keyCode(4))
    }
    @Test fun selectedSessionConsumesKeyWithoutDoubleDispatch() {
        var selected=0
        assertEquals("selected-session", CarMediaKeys.dispatch(0xB5, { selected=it; true }, { fail("duplicate system dispatch") }))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT,selected)
    }
    @Test fun missingRejectingOrFailedSessionFallsBackToSystem() {
        for (throws in listOf(false,true)) {
            var delivered=0
            assertEquals("system-active-session",CarMediaKeys.dispatch(0xB6,{ if(throws) error("dead session") else false },{delivered=it}))
            assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS,delivered)
        }
    }
    @Test fun unknownReportDoesNotReachAnyPlayer() {
        assertEquals("unsupported",CarMediaKeys.dispatch(4,{fail("unexpected session"); false},{fail("unexpected system")}))
    }
}
