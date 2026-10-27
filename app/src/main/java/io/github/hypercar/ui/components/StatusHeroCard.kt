package io.github.hypercar.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.core.LinkState
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.MonoDuration
import io.github.hypercar.ui.theme.NebulaViolet
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 首页英雄大卡：全链路状态的唯一主视觉。
 *
 * - Idle：脉冲圆环 +「等待上车」
 * - Negotiating(step)：旋转弧线 + 步骤文案 + 每 300ms 呼吸点
 * - Connected(carName, sinceMillis)：大青绿勾 + 车名 + mm:ss 每秒刷新
 * - Failed(reason)：红色调 + 原因 + 重试
 * - 底部大按钮：未连接实心青胶囊「启动连接」/ 连接中描边「停止」/ 已连接暗红描边「断开」
 */
@Composable
fun StatusHeroCard(
    link: LinkState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    /** 「退出」：断开 + 停后台循环 + 恢复冻结的小米 CarLife（默认空实现，兼容旧调用）。 */
    onExitClick: () -> Unit = {},
    /** 右上角日志图标（原来首页底部那行「查看全部日志」文字链接）。 */
    onOpenLogs: (() -> Unit)? = null,
    /** 当前车机档案名（显示在标题上方，小字）。 */
    profileLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val isConnected = link is LinkState.Connected
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isConnected) {
        if (!isConnected) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val accent = when (link) {
        is LinkState.Connected -> MintGreen
        is LinkState.Failed -> CoralError
        is LinkState.Negotiating -> ElectricCyan
        is LinkState.Stopping -> AmberWarning
        is LinkState.Idle -> ElectricCyan
    }
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        glow = link !is LinkState.Idle,
        glowColor = accent,
    ) {
        // 右上角日志图标：原来是首页底部一行「查看全部日志」文字链接，
        // 挪到首卡右上角后不占版面，也符合"第一张卡就是入口"的直觉。
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Crossfade(
                    targetState = link,
                    animationSpec = tween(300),
                    label = "hero-state",
                ) { state ->
                    HeroHeadline(link = state, nowMillis = nowMillis, profileLabel = profileLabel)
                }
                HeroAction(
                    link = link,
                    onStartClick = onStartClick,
                    onStopClick = onStopClick,
                    onExitClick = onExitClick,
                )
            }
            if (onOpenLogs != null) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenLogs)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/* ---------- 头部：图形 + 文案 ---------- */

@Composable
private fun HeroHeadline(link: LinkState, nowMillis: Long, profileLabel: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        HeroGlyph(link)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (link) {
                is LinkState.Idle -> {
                    Text(
                        text = "等待上车",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                    Text(
                        text = "车机蓝牙连上手机后将自动秒连建链",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    if (!profileLabel.isNullOrBlank()) {
                        // 车机型号放在「自动秒连建链」下面、左对齐（与标题同一左起点）
                        Text(
                            text = "当前车机型号：$profileLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary.copy(alpha = 0.8f),
                        )
                    }
                }

                is LinkState.Negotiating -> {
                    Text(
                        text = "正在协商",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = link.step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ElectricCyan,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BreathingDots()
                    }
                }

                is LinkState.Connected -> {
                    Text(
                        text = link.carName,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "已连接",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MintGreen.copy(alpha = 0.9f),
                        )
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Text(
                            text = formatDuration(nowMillis - link.sinceMillis),
                            style = MonoDuration,
                            color = TextPrimary,
                        )
                    }
                }

                is LinkState.Failed -> {
                    Text(
                        text = "连接失败",
                        style = MaterialTheme.typography.titleLarge,
                        color = CoralError,
                    )
                    Text(
                        text = link.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (link.recoverable) "将自动重试，也可点击下方手动重连" else "请检查蓝牙与车机状态后重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarning.copy(alpha = 0.9f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                is LinkState.Stopping -> {
                    Text(
                        text = "正在断开",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                    Text(
                        text = "正在释放会话…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

/* ---------- 图形（每状态一个） ---------- */

@Composable
private fun HeroGlyph(link: LinkState) {
    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (link) {
            is LinkState.Idle -> IdleGlyph()
            is LinkState.Negotiating -> ArcGlyph(
                colors = listOf(ElectricCyan, NebulaViolet, ElectricCyan),
                centerIcon = Icons.Rounded.Bluetooth,
                centerTint = ElectricCyan,
            )
            is LinkState.Stopping -> ArcGlyph(
                colors = listOf(TextSecondary, StrokeGlass, TextSecondary),
                centerIcon = Icons.Rounded.Close,
                centerTint = TextSecondary,
            )
            is LinkState.Connected -> ConnectedGlyph()
            is LinkState.Failed -> FailedGlyph()
        }
    }
}

@Composable
private fun IdleGlyph() {
    val transition = rememberInfiniteTransition(label = "idle-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse-scale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse-alpha",
    )
    Canvas(modifier = Modifier.size(88.dp)) {
        drawCircle(
            color = ElectricCyan.copy(alpha = pulseAlpha),
            radius = size.minDimension / 2f * pulse,
        )
    }
    Canvas(modifier = Modifier.size(88.dp)) {
        drawCircle(
            color = ElectricCyan.copy(alpha = 0.22f),
            radius = size.minDimension / 2f - 1.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
    Icon(
        imageVector = Icons.Rounded.DirectionsCar,
        contentDescription = "待机",
        tint = ElectricCyan.copy(alpha = 0.9f),
        modifier = Modifier.size(38.dp),
    )
}

@Composable
private fun ArcGlyph(
    colors: List<Color>,
    centerIcon: ImageVector,
    centerTint: Color,
) {
    val transition = rememberInfiniteTransition(label = "arc-spin")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "arc-rotation",
    )
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
        label = "arc-breath",
    )
    Canvas(
        modifier = Modifier
            .size(88.dp)
            .rotate(rotation),
    ) {
        val stroke = 3.5.dp.toPx()
        val pad = stroke
        drawArc(
            brush = Brush.sweepGradient(colors),
            startAngle = -90f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(size.width - 2 * pad, size.height - 2 * pad),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            alpha = breath,
        )
    }
    Icon(
        imageVector = centerIcon,
        contentDescription = null,
        tint = centerTint,
        modifier = Modifier.size(30.dp),
    )
}

@Composable
private fun ConnectedGlyph() {
    Canvas(modifier = Modifier.size(88.dp)) {
        drawCircle(
            brush = Brush.linearGradient(
                listOf(MintGreen.copy(alpha = 0.24f), ElectricCyan.copy(alpha = 0.10f)),
            ),
        )
    }
    Canvas(modifier = Modifier.size(88.dp)) {
        drawCircle(
            color = MintGreen.copy(alpha = 0.55f),
            radius = size.minDimension / 2f - 1.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
    Icon(
        imageVector = Icons.Rounded.Check,
        contentDescription = "已连接",
        tint = MintGreen,
        modifier = Modifier.size(44.dp),
    )
}

@Composable
private fun FailedGlyph() {
    Canvas(modifier = Modifier.size(88.dp)) {
        drawCircle(
            brush = Brush.linearGradient(
                listOf(CoralError.copy(alpha = 0.22f), AmberWarning.copy(alpha = 0.10f)),
            ),
        )
    }
    Canvas(modifier = Modifier.size(88.dp)) {
        drawCircle(
            color = CoralError.copy(alpha = 0.55f),
            radius = size.minDimension / 2f - 1.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
    Icon(
        imageVector = Icons.Rounded.Warning,
        contentDescription = "失败",
        tint = CoralError,
        modifier = Modifier.size(40.dp),
    )
}

/** 协商步骤尾部的三点呼吸（300ms 周期）。 */
@Composable
private fun BreathingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "step-dots")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
        label = "dot-alpha",
    )
    Row(
        modifier = modifier.padding(start = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(3) {
            Canvas(modifier = Modifier.size(4.dp)) {
                drawCircle(color = ElectricCyan.copy(alpha = alpha))
            }
        }
    }
}

/* ---------- 底部主按钮 ---------- */

@Composable
private fun HeroAction(
    link: LinkState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onExitClick: () -> Unit = {},
) {
    val buttonModifier = Modifier
        .fillMaxWidth()
        .height(52.dp)
    when (link) {
        is LinkState.Connected -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsuleButton(
                text = "断开",
                onClick = onStopClick,
                modifier = buttonModifier,
                filled = false,
                contentColor = CoralError,
                borderColor = CoralError.copy(alpha = 0.65f),
                leadingIcon = Icons.Rounded.Close,
            )
            // **退出**：断开 + 停掉所有后台循环 + 恢复被冻结的小米 CarLife（之后能去连 CarWith 盒子）。
            CapsuleButton(
                text = "退出",
                onClick = onExitClick,
                modifier = buttonModifier,
                filled = false,
                contentColor = Color(0xFF9AA4B2),
                borderColor = Color(0xFF9AA4B2).copy(alpha = 0.45f),
                leadingIcon = Icons.Rounded.ExitToApp,
            )
        }

        is LinkState.Negotiating -> CapsuleButton(
            text = "停止",
            onClick = onStopClick,
            modifier = buttonModifier,
            filled = false,
            contentColor = ElectricCyan,
            borderColor = ElectricCyan.copy(alpha = 0.6f),
            leadingIcon = Icons.Rounded.Close,
        )

        is LinkState.Stopping -> CapsuleButton(
            text = "停止中…",
            onClick = { },
            modifier = buttonModifier,
            enabled = false,
            filled = false,
            contentColor = TextSecondary,
            borderColor = StrokeGlass,
        )

        is LinkState.Failed -> CapsuleButton(
            text = "重试连接",
            onClick = onStartClick,
            modifier = buttonModifier,
            filled = true,
            leadingIcon = Icons.Rounded.Refresh,
        )

        is LinkState.Idle -> CapsuleButton(
            text = "启动连接",
            onClick = onStartClick,
            modifier = buttonModifier,
            filled = true,
            leadingIcon = Icons.Rounded.PowerSettingsNew,
        )
    }
}

/** mm:ss（分钟上限自然进位，超过 99 分钟显示 3 位分钟）。 */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}




