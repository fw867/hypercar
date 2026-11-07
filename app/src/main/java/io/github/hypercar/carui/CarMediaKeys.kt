package io.github.hypercar.carui

import android.view.KeyEvent

internal object CarMediaKeys {
    fun keyCode(usage: Int): Int = when (usage) {
        0xB0 -> KeyEvent.KEYCODE_MEDIA_PLAY
        0xB1 -> KeyEvent.KEYCODE_MEDIA_PAUSE
        0xB3 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        0xB4 -> KeyEvent.KEYCODE_MEDIA_REWIND
        0xB5 -> KeyEvent.KEYCODE_MEDIA_NEXT
        0xB6 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        0xB7, 0xB9 -> KeyEvent.KEYCODE_MEDIA_STOP
        0xCD -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> 0
    }

    fun dispatch(usage: Int, session: (Int) -> Boolean, system: (Int) -> Unit): String {
        val code = keyCode(usage)
        if (code == 0) return "unsupported"
        if (runCatching { session(code) }.getOrDefault(false)) return "selected-session"
        system(code)
        return "system-active-session"
    }
}
