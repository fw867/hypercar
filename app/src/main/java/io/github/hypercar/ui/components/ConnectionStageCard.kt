package io.github.hypercar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hypercar.core.PipelineDiagnostics
import io.github.hypercar.core.PipelineStatus
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/** Sender-side observations. Green confirms only the labelled stage or counter. */
@Composable
fun ConnectionStageCard(pipeline: PipelineDiagnostics, modifier: Modifier = Modifier) {
    val p = pipeline
    val neutral = TextSecondary
    val success = MintGreen
    val error = CoralError
    fun color(status: PipelineStatus) = when (status) {
        PipelineStatus.SUCCESS -> success
        PipelineStatus.ERROR -> error
        PipelineStatus.NEUTRAL -> neutral
    }
    fun observed(yes: Boolean) = if (p.active && yes) success else neutral
    fun age(ms: Long?) = ms?.let { "${it / 1000}s前" } ?: "暂无"
    fun bytes(value: Long?): String = when {
        value == null -> "未知"
        value >= 1024 * 1024 -> "${value / (1024 * 1024)}MB"
        value >= 1024 -> "${value / 1024}KB"
        else -> "${value}B"
    }
    @Composable
    fun Facts(vararg facts: Pair<String, Color>) {
        Text(buildAnnotatedString {
            facts.forEach { (value, tint) -> withStyle(SpanStyle(color = tint)) { append(value) } }
        }, fontSize = 11.sp, lineHeight = 15.sp)
    }

    GlassPanel(modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(6.dp).background(if (p.active) success else neutral, CircleShape))
                Text("连接阶段", color = TextPrimary, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(if (p.active) "实时会话" else "等待数据", color = neutral,
                    fontSize = 10.sp, lineHeight = 13.sp)
            }
            if (p.generation <= 0) {
                Text("正在读取本轮连接状态…", color = neutral, fontSize = 11.sp, lineHeight = 15.sp)
            } else {
                fun activationColor(accepted: Boolean, attempted: Boolean = true) = when {
                    accepted -> success
                    p.activationFailure != null && attempted -> error
                    else -> neutral
                }
                Facts("激活 " to neutral, (if (p.recordAccepted) "RECORD ✓" else "RECORD 等待") to activationColor(p.recordAccepted),
                    "   ·   " to neutral, (if (p.resourcesAccepted) "资源 ✓" else "资源 未完成") to activationColor(p.resourcesAccepted, p.recordAccepted),
                    "   ·   " to neutral, (if (p.videoChannelReady) "视频TCP ✓" else "视频TCP 等待") to activationColor(p.videoChannelReady, p.screenSetupAccepted))
                Facts("控制 " to neutral, (if (p.controlReady) "✓ 就绪" else "协商中") to color(p.controlStatus),
                    "   ·   Wi-Fi iAP2 " to neutral, p.wifiLabel to color(p.wifiStatus))
                Facts("视频 " to neutral, p.videoLabel to color(p.videoStatus),
                    "   ·   音频 " to neutral, (if (p.audioReady) "通道就绪" else "未就绪") to color(p.audioStatus),
                    "   ·   归属 " to neutral, when (p.screenOwned) { true -> "手机"; false -> "车机"; null -> "未知" } to neutral)
                HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
                Facts("视频 " to neutral, "${p.writtenFrames}帧写出" to observed(p.writtenFrames > 0),
                    " / 来源 ${p.sourceUnits}   ·   进入 ${p.uiRequests} / 重建 ${p.rebuilds}" to neutral)
                Facts("音频 " to neutral, "${p.audioPackets}包写出" to observed(p.audioPackets > 0),
                    " / " to neutral, "非零PCM ${p.signalPackets}包" to observed(p.signalPackets > 0))
                Facts("计时 " to neutral, "请求/回包 ${p.timingRequests}/${p.timingReplies}" to color(p.timingStatus),
                    "   ·   最近 ${age(p.timingReplyAgeMs)}" to neutral,
                    if (p.timingSendErrors + p.timingReceiveErrors > 0)
                        "   收发失败 ${p.timingSendErrors + p.timingReceiveErrors}" to color(p.timingStatus)
                    else "" to neutral)
                Facts("TCP " to neutral, "确认 ${bytes(p.tcpAckedBytes)}" to color(p.tcpStatus),
                    "   ·   队列 ${bytes(p.tcpQueuedBytes)}   ·   累计重传 ${p.tcpRetransmissions ?: "未知"}" to neutral)
                Facts("TCP采样 ${age(p.tcpSampleAgeMs)}" to neutral,
                    when {
                        p.tcpInfoError != null -> "   ·   采样失败 ${p.tcpInfoError}" to color(p.tcpStatus)
                        p.tcpStatus == PipelineStatus.ERROR -> "   ·   连接已关闭" to color(p.tcpStatus)
                        else -> "" to neutral
                    })
            }
            Text("本机写出与TCP确认，不代表车机已显示或出声", color = neutral,
                fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}
