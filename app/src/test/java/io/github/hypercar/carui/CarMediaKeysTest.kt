package io.github.hypercar.carui

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class CarMediaKeysTest {
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
