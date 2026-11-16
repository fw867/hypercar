package io.github.hypercar.carui

import android.view.KeyEvent

/** Events decoded from a console HID layout by consumer_hid.rs. Rotation follows focus order. */
internal enum class CarConsoleKey(val keyCode: Int, val metaState: Int = 0) {
    BACK(KeyEvent.KEYCODE_BACK),
    SELECT(KeyEvent.KEYCODE_DPAD_CENTER),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    PREVIOUS(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON),
    NEXT(KeyEvent.KEYCODE_TAB);

    companion object {
        fun fromInput(input: Int): CarConsoleKey? = when (input) {
            0x30001 -> BACK
            0x30002 -> SELECT
            0x30003 -> LEFT
            0x30004 -> RIGHT
            0x30005 -> UP
            0x30006 -> DOWN
            0x30007 -> PREVIOUS
            0x30008 -> NEXT
            else -> null
        }
    }
}
