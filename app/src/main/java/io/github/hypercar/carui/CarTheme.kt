package io.github.hypercar.carui

import androidx.compose.ui.graphics.Color

/** 车机 UI 的昼夜色板；CarHomeScreen 根据设置/系统深浅色更新 [CarThemeState.dark]。 */
object CarThemeState {
    var dark: Boolean = true
}

val CarBg: Color
    get() = if (CarThemeState.dark) Color(0xFF05050C) else Color(0xFFE8EEF6)

val CarPanel: Color
    get() = if (CarThemeState.dark) Color(0xFF151426) else Color(0xF7F7FBFE)

val CarCard: Color
    get() = if (CarThemeState.dark) Color(0xFF1B2637) else Color(0xFFDCE8F5)

val CarInk: Color
    get() = if (CarThemeState.dark) Color(0xFFF5F5FA) else Color(0xFF1F2A37)

val CarMuted: Color
    get() = if (CarThemeState.dark) Color(0xFFB4B5C5) else Color(0xFF64748B)

val CarGlowA: Color
    get() = if (CarThemeState.dark) Color(0xFF25116A) else Color(0xFFCFE4FA)

val CarAccent: Color
    get() = if (CarThemeState.dark) Color(0xFF22D3EE) else Color(0xFF2F80ED)

val CarGlowB: Color
    get() = if (CarThemeState.dark) Color(0xFF191647) else Color(0xFFE8F2FF)
