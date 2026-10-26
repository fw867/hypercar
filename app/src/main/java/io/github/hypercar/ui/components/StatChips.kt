package io.github.hypercar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.core.AudioStats
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.core.VideoStats
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.MonoMetric
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.SurfaceInset
import io.github.hypercar.ui.theme.TextSecondary
import java.util.Locale

/**
 * 统计芯片：视频 / 音频 / Shizuku。
 *
 * 布局（现场 2026-10-24 两轮反馈后定稿）：
 *  - 第一行两枚等分：视频 │ 音频；
 *  - Shizuku 单独一行、整宽（与上面两枚总宽对齐），因此可以写完整说明文字；
 *  - 三枚统一装进一张 GlassPanel，与首页其它卡片视觉一致（原来是单行横滑，
 *    Shizuku 在窄屏被挤出屏幕，要左滑才看得到）。
 */
@Composable
fun StatGrid(
    snapshot: SessionSnapshot,
    modifier: Modifier = Modifier,
    /** 联调模式（PC 侧当 HU）：本机引擎不跑，视频/音频统计无从更新，芯片改为说明来源。 */
    externalMode: Boolean = false,
    onOpenShizuku: (() -> Unit)? = null,
    /** 降级模式说明（原首页下方那张大卡，已并到 Shizuku 芯片）。 */
    degradedHint: String? = null,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VideoChip(snapshot.video, externalMode, Modifier.weight(1f))
            AudioChip(snapshot.audio, externalMode, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        ShizukuChip(
            ready = snapshot.shizukuReady,
            onClick = onOpenShizuku,
            degradedHint = degradedHint,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ---------- 单枚芯片 ---------- */

@Composable
private fun StatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceInset.copy(alpha = 0.65f))
            .border(1.dp, StrokeGlass, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            StatusDot(color = accent)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MonoMetric,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------- 各链路状态 → 芯片 ---------- */

@Composable
private fun VideoChip(video: VideoStats, external: Boolean = false, modifier: Modifier = Modifier) {
    val idle = video.fps <= 0
    val accent = if (idle) TextSecondary else ElectricCyan
    val value = if (idle) {
        if (external) "PC 侧推流" else "未推流"
    } else {
        buildString {
            append(video.fps).append(" fps")
            if (video.resolution.isNotEmpty()) {
                append(" · ").append(video.resolution)
            }
            if (video.kbps > 0) {
                append(" · ").append(formatBitrate(video.kbps))
            }
        }
    }
    StatChip(
        label = "视频",
        value = value,
        accent = accent,
        leadingIcon = Icons.Rounded.Videocam,
        modifier = modifier,
    )
}

@Composable
private fun AudioChip(audio: AudioStats, external: Boolean = false, modifier: Modifier = Modifier) {
    val (accent, value) = when {
        audio.ducking -> AmberWarning to "导航闪避中"
        audio.playing -> MintGreen to "播放中"
            else -> TextSecondary to if (external) "PC 侧播放" else "待机"
    }
    StatChip(
        label = "音频",
        value = value,
        accent = accent,
        leadingIcon = Icons.Rounded.Navigation,
        modifier = modifier,
    )
}

@Composable
private fun ShizukuChip(
    ready: Boolean,
    onClick: (() -> Unit)? = null,
    degradedHint: String? = null,
    modifier: Modifier = Modifier,
) {
    // Shizuku 已降级为「可选增强」：没有它也能连车（热点首连弹一次框 + 稳定伪 MAC），
    // 有则体验最优（0 弹窗 + 真实蓝牙 MAC）。故未就绪时用中性灰、文案标「可选」，不报警色。
    // 未就绪时可点进设置页授权（此前只有只读文案，用户找不到入口）。
    StatChip(
        label = "Shizuku",
        value = when {
            ready -> "增强已启用 · 静默入网、真实蓝牙 MAC"
            !degradedHint.isNullOrBlank() -> degradedHint
            else -> "未启用 · 点此设置"
        },
        accent = if (ready) MintGreen else TextSecondary,
        leadingIcon = Icons.Rounded.Security,
        onClick = if (ready) null else onClick,
    )
}

/* ---------- 工具 ---------- */

private fun formatBitrate(kbps: Long): String = if (kbps >= 1000L) {
    String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
} else {
    "$kbps kbps"
}

/** 芯片最小宽度，保证短文案芯片不至于太窄。 */
private val ChipMinWidth = 110.dp







