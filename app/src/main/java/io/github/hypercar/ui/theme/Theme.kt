package io.github.hypercar.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import io.github.hypercar.settings.AppThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 渐变集合：全局通过 [LocalGradients] 提供，组件用它保持视觉一致。 */
@Immutable
data class GradientSpecs(
    /** 主强调渐变（实心主按钮等）。 */
    val accent: Brush,
    /** 待机态英雄卡渐变。 */
    val heroIdle: Brush,
    /** 协商态英雄卡渐变。 */
    val heroNegotiating: Brush,
    /** 已连接英雄卡渐变。 */
    val heroConnected: Brush,
    /** 失败态英雄卡渐变。 */
    val heroFailed: Brush,
    /** 屏幕底色渐变。 */
    val screenBase: Brush,
)

/** 玻璃拟态参数：全局通过 [LocalGlass] 提供。 */
@Immutable
data class GlassSpecs(
    /** 面板半透填充。 */
    val panelBrush: Brush,
    /** 1px 描边基色。 */
    val strokeColor: Color,
    /** 描边宽度。 */
    val strokeWidth: Dp = 1.dp,
    /** 圆角。 */
    val cornerRadius: Dp = 20.dp,
    /** 辉光颜色（可选发光）。 */
    val glowColor: Color,
    /** 遮罩色（弹窗）。 */
    val scrimColor: Color,
)

private fun gradientsFor(p: AppPalette) = GradientSpecs(
    accent = Brush.horizontalGradient(listOf(p.accent, p.accent.copy(alpha = 0.80f))),
    heroIdle = Brush.linearGradient(listOf(p.accent, p.secondary)),
    heroNegotiating = Brush.linearGradient(listOf(p.secondary, p.accent)),
    heroConnected = Brush.linearGradient(listOf(p.success, p.accent)),
    heroFailed = Brush.linearGradient(listOf(p.error, p.warning)),
    screenBase = Brush.verticalGradient(if (p.dark)
        listOf(Color(0xFF080C14), p.background, Color(0xFF04060A))
        else listOf(p.background, p.background)),
)

private fun glassFor(p: AppPalette) = GlassSpecs(
    panelBrush = Brush.verticalGradient(if (p.dark)
        listOf(Color(0xB3101826), Color(0xD90A1019)) else listOf(p.panel, p.panel)),
    strokeColor = p.outline,
    glowColor = p.accent,
    scrimColor = p.scrim,
    cornerRadius = if (p.dark) 20.dp else 24.dp,
)

val LocalGradients = staticCompositionLocalOf { gradientsFor(DarkAppPalette) }
val LocalGlass = staticCompositionLocalOf { glassFor(DarkAppPalette) }

@Composable
fun HyperCarTheme(
    mode: AppThemeMode = AppThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val p = if (dark) DarkAppPalette else LightAppPalette
    val gradients = remember(dark) { gradientsFor(p) }
    val glass = remember(dark) { glassFor(p) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colorScheme = base.copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accent.copy(alpha = if (dark) 0.16f else 0.10f),
        onPrimaryContainer = p.accent,
        secondary = p.secondary,
        onSecondary = p.onAccent,
        secondaryContainer = p.secondary.copy(alpha = if (dark) 0.22f else 0.10f),
        onSecondaryContainer = p.secondary,
        tertiary = p.success,
        onTertiary = p.onAccent,
        tertiaryContainer = p.success.copy(alpha = 0.12f),
        onTertiaryContainer = p.success,
        error = p.error,
        onError = p.onAccent,
        errorContainer = p.error.copy(alpha = 0.12f),
        onErrorContainer = p.error,
        background = p.background,
        onBackground = p.text,
        surface = if (dark) p.background else p.panel,
        onSurface = p.text,
        surfaceVariant = p.inset,
        onSurfaceVariant = p.secondaryText,
        surfaceTint = p.accent,
        inverseSurface = p.text,
        inverseOnSurface = p.background,
        inversePrimary = if (dark) Color(0xFF006B7D) else Color(0xFFB0CCFF),
        outline = p.outline,
        outlineVariant = if (dark) Color(0xFF16202E) else p.outline,
        scrim = Color.Black,
        surfaceContainerLowest = if (dark) Color(0xFF04060A) else Color.White,
        surfaceContainerLow = if (dark) Color(0xFF080D15) else Color(0xFFF8F9FC),
        surfaceContainer = if (dark) Color(0xFF0B111C) else Color(0xFFF1F3F8),
        surfaceContainerHigh = if (dark) Color(0xFF101826) else Color(0xFFEBEEF4),
        surfaceContainerHighest = if (dark) Color(0xFF16202F) else Color(0xFFE4E8F0),
    )
    CompositionLocalProvider(
        LocalAppPalette provides p,
        LocalGradients provides gradients,
        LocalGlass provides glass,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = HyperCarTypography, content = content)
    }
}

@Composable
fun HyperCarBackground(modifier: Modifier = Modifier) {
    val base = LocalGradients.current.screenBase
    val p = LocalAppPalette.current
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(base)
        if (p.dark) {
            drawRect(Brush.radialGradient(
                listOf(p.accent.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.92f, -size.height * 0.05f), radius = size.width * 0.85f))
            drawRect(Brush.radialGradient(
                listOf(p.secondary.copy(alpha = 0.13f), Color.Transparent),
                center = Offset(-size.width * 0.10f, size.height * 1.05f), radius = size.width * 0.95f))
        }
    }
}
