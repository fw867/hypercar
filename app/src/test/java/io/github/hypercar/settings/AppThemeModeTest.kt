package io.github.hypercar.settings

import org.junit.Assert.*
import org.junit.Test

class AppThemeModeTest {
    @Test fun systemChoiceTracksBothSystemModes() {
        assertFalse(AppThemeMode.System.isDark(false))
        assertTrue(AppThemeMode.System.isDark(true))
    }

    @Test fun manualChoiceOverridesSystemMode() {
        for (systemDark in listOf(false, true)) {
            assertFalse(AppThemeMode.Light.isDark(systemDark))
            assertTrue(AppThemeMode.Dark.isDark(systemDark))
        }
    }

    @Test fun storedValuesRoundTripAndUnknownValuesPreserveDarkTheme() {
        for (mode in AppThemeMode.entries) assertEquals(mode, AppThemeMode.fromValue(mode.value))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromValue(null))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromValue("unknown"))
    }
}
