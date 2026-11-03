package io.github.hypercar.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.hypercar.ui.theme.DarkAppPalette
import io.github.hypercar.ui.theme.LightAppPalette
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPaletteTest {
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    @Test fun lightThemeBodyTextAndActionsHaveReadableContrast() {
        val p = LightAppPalette
        for (color in listOf(p.text, p.secondaryText, p.accent, p.warning, p.error, p.success)) {
            assertTrue("Low contrast: $color", contrast(color, p.panel) >= 4.5f)
        }
        assertTrue(contrast(p.text, p.background) >= 4.5f)
        assertTrue(contrast(p.onAccent, p.accent) >= 4.5f)
    }

    @Test fun darkThemeKeepsReadableBodyAndPrimaryAction() {
        val p = DarkAppPalette
        assertTrue(contrast(p.text, p.background) >= 4.5f)
        assertTrue(contrast(p.onAccent, p.accent) >= 4.5f)
    }
}
