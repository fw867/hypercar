package io.github.hypercar.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hypercar.ui.theme.DeepSpace
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.LocalGlass

/**
 * 玻璃拟态表面 Modifier：
 * 半透面板填充 + 1px 渐变描边 + 圆角（默认 20dp）+ 可选外辉光。
 * 不带内容，只负责“面”；内容用 [GlassPanel] 或直接叠加。
 */
@Composable
fun Modifier.glassPanel(
    cornerRadius: Dp = LocalGlass.current.cornerRadius,
    glow: Boolean = false,
    glowColor: Color = LocalGlass.current.glowColor,
): Modifier {
    val glass = LocalGlass.current
    val dark = io.github.hypercar.ui.theme.LocalAppPalette.current.dark
    val accent = ElectricCyan
    val shape = RoundedCornerShape(cornerRadius)
    val strokeBrush = remember(glass, cornerRadius, accent, dark) {
        Brush.verticalGradient(
            listOf(
                if (dark) accent.copy(alpha = 0.28f) else glass.strokeColor,
                glass.strokeColor,
                if (dark) NebulaVioletStroke else glass.strokeColor,
            ),
        )
    }
    val glowPaint = remember {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }
    val glowModifier = if (glow && dark) {
        Modifier.drawWithCache {
            val strokeWidthPx = 2.dp.toPx()
            val radiusPx = cornerRadius.toPx()
            glowPaint.strokeWidth = strokeWidthPx
            glowPaint.maskFilter = BlurMaskFilter(radiusPx * 1.25f, BlurMaskFilter.Blur.NORMAL)
            // android.graphics.Paint.color 是 ARGB Int，需把 Compose Color 转过去
            glowPaint.color = glowColor.copy(alpha = 0.28f).toArgb()
            onDrawBehind {
                // 带 BlurMaskFilter 的辉光必须走 nativeCanvas（Compose 的 drawRoundRect 不接 Paint）
                drawIntoCanvas { canvas ->
                    val inset = strokeWidthPx / 2f
                    val rect = RectF(
                        inset,
                        inset,
                        size.width - inset,
                        size.height - inset,
                    )
                    canvas.nativeCanvas.drawRoundRect(rect, radiusPx, radiusPx, glowPaint)
                }
            }
        }
    } else {
        Modifier
    }
    return this
        .then(glowModifier)
        .shadow(if (dark) 0.dp else 1.dp, shape, clip = false)
        .clip(shape)
        .background(glass.panelBrush)
        .border(glass.strokeWidth, strokeBrush, shape)
}

/** 玻璃拟态卡片：半透背景 + 1px 描边 + 圆角 20dp + 可选辉光，内容统一 20dp 内边距。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LocalGlass.current.cornerRadius,
    glow: Boolean = false,
    glowColor: Color = LocalGlass.current.glowColor,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.glassPanel(cornerRadius = cornerRadius, glow = glow, glowColor = glowColor)
            .padding(contentPadding),
        content = content,
    )
}

/** 章节小标题（设置页 / 首页区块）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = ElectricCyan.copy(alpha = 0.85f),
        modifier = modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * 胶囊按钮（主按钮全宽 52dp，最小触达 44dp）：
 * [filled] = true 电光青实心；false 描边（用于停止 / 断开等次要动作）。
 *
 * 文字颜色默认值随 [filled] 自动切换：实心用深空色（压在亮底上），
 * 描边用 [containerColor]（压在深色背景上）。此前默认恒为 `DeepSpace`，
 * 描边按钮没有底色时就是「深色字 + 深色背景」——字直接看不见。
 */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    containerColor: Color = ElectricCyan,
    contentColor: Color = Color.Unspecified,
    borderColor: Color = ElectricCyan,
    leadingIcon: ImageVector? = null,
    /**
     * 左右内边距。默认 20dp；**四等分一行的窄按钮要传小值**
     * （20dp × 2 + 图标 + 两个汉字会超宽，系统把最后一个字吞掉，2026-10-25 诊断页截图）。
     */
    horizontalPadding: Dp = 20.dp,
) {
    val shape = CircleShape
    val resolvedContent = if (contentColor == Color.Unspecified) {
        if (filled) DeepSpace else containerColor
    } else {
        contentColor
    }
    val tint = resolvedContent.copy(alpha = if (enabled) 1f else 0.4f)
    val borderTint = borderColor.copy(alpha = if (enabled) borderColor.alpha else borderColor.alpha * 0.4f)
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(
                        Brush.horizontalGradient(
                            listOf(containerColor, containerColor.copy(alpha = 0.82f)),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        borderTint.copy(alpha = borderTint.alpha * if (filled) 0f else 1f),
                        borderTint,
                    ),
                ),
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
        )
    }
}

/** 状态小圆点（统计芯片 / 状态条用）。 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, dotSize: Dp = 8.dp) {
    Canvas(modifier = modifier.size(dotSize)) {
        drawCircle(color = color.copy(alpha = 0.22f))
        drawCircle(color = color, radius = dotSize.toPx() / 3.2f)
    }
}

/** 玻璃面板描边渐变里使用的暗紫端（避免循环引用，这里内联定义）。 */
private val NebulaVioletStroke = Color(0x337C4DFF)
