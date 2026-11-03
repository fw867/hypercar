package io.github.hypercar.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic colors scoped to this composition, including dialogs and inactive tabs. */
@Immutable
data class AppPalette(
    val dark: Boolean,
    val background: Color,
    val panel: Color,
    val outline: Color,
    val accent: Color,
    val secondary: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val text: Color,
    val secondaryText: Color,
    val onAccent: Color,
    val inset: Color,
    val navigation: Color,
    val scrim: Color,
)

val DarkAppPalette = AppPalette(
    true, Color(0xFF05070C), Color(0xB3101826), Color(0xFF1E2A3A),
    Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFF00FFA3), Color(0xFFFFB300),
    Color(0xFFFF5252), Color(0xFFE8F1F8), Color(0xFF7C8DA0), Color(0xFF02040A),
    Color(0xFF0B1220), Color(0xF2080D16), Color(0xB303060B),
)

val LightAppPalette = AppPalette(
    false, Color(0xFFF3F4F7), Color.White, Color(0xFFDFE2E8),
    Color(0xFF1265E8), Color(0xFF1265E8), Color(0xFF117854), Color(0xFF956000),
    Color(0xFFC9343A), Color(0xFF1C1D22), Color(0xFF606571), Color.White,
    Color(0xFFECEEF3), Color.White, Color(0x99000000),
)

val LocalAppPalette = staticCompositionLocalOf { DarkAppPalette }

// Preserve the existing component API while resolving colors per composition.
val DeepSpace: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.background

val PanelGlass: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.panel

val StrokeGlass: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.outline

val ElectricCyan: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.accent

val NebulaViolet: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.secondary

val MintGreen: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.success

val AmberWarning: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.warning

val CoralError: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.error

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.text

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.secondaryText

val InkBlack: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.onAccent

val SurfaceInset: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.inset

val SurfaceNavBar: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.navigation

val ScrimDeepSpace: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.scrim
