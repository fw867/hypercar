package io.github.hypercar.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hypercar.session.StartupReadiness
import io.github.hypercar.session.StartupRequirements
import io.github.hypercar.settings.SettingsRevision
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.StatusHeroCard
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import io.github.hypercar.yadea.YadeaPhonePreview
import io.github.hypercar.yadea.YadeaProjectionService
import io.github.hypercar.yadea.YadeaSessionState
import io.github.hypercar.yadea.YadeaSource

@Composable
internal fun YadeaHomeScreen(startup: StartupReadiness, state: YadeaSessionState) {
    val context = LocalContext.current
    val revision = SettingsRevision.value
    val host = remember(revision) { SettingsStore.yadeaInstrumentIp }
    val source = remember(revision) { YadeaSource.fromSetting(SettingsStore.carSource) }
    val sourceAvailable = when (source) {
        YadeaSource.CarWith -> startup.carWithInstalled
        YadeaSource.SystemCarLife -> source.installed(context)
        null -> false
    }
    val startBlocked = when {
        source == null -> "在「设置 → 画面与声音」选择 CarWith 或 CarLife+。"
        !sourceAvailable -> "本机未安装或未启用 ${source.title}。"
        !YadeaProjectionService.isValidInstrumentIp(host) ->
            "在「设置 → 我的车机」填写仪表 IP。"
        !startup.wifiEnabled -> "请先开启手机 Wi-Fi。"
        else -> null
    }
    @Composable
    fun ConnectionCard() {
        StatusHeroCard(
            link = state.link,
            autoConnectEnabled = false,
            startEnabled = startBlocked == null,
            startBlockReason = startBlocked,
            profileLabel = "雅迪仪表",
            idleHint = "手机接入仪表 Wi-Fi 后启动连接。",
            connectedLabel = "投屏中",
            manualRetryHint = "检查仪表网络和导航来源后重试。",
            onStartClick = {
                if (source != null && startBlocked == null && !YadeaProjectionService.sessionState.value.active) {
                    YadeaProjectionService.start(context, source, host = host)
                }
            },
            onStopClick = YadeaProjectionService::stop,
        )
        if (state.awaitingSystemCarLife) {
            CapsuleButton("打开手机车联", modifier = Modifier.fillMaxWidth(), filled = false, onClick = {
                if (!StartupRequirements.openSystemCarLife(context)) {
                    Toast.makeText(context, "请手动打开手机车联中的 CarLife+ 无线连接页", Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    val showPreview = state.active && state.display != null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight && showPreview) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) { ConnectionCard() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { PreviewPanel(state) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ConnectionCard()
                if (showPreview) PreviewPanel(state)
            }
        }
    }
}

@Composable
private fun PreviewPanel(state: YadeaSessionState) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Text("手机导航预览", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        YadeaPhonePreview(modifier = Modifier.padding(vertical = 8.dp))
        Text("在预览上点按或拖动来操作导航。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        state.display?.let {
            Text("仪表 ${it.width} × ${it.height} · ${it.fps} fps（${if (it.negotiated) "仪表确认" else "本地回退"}）",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        state.requestedSize?.let { (width, height) ->
            val actual = state.sourceSize?.let { "${it.first} × ${it.second}" } ?: "等待来源确认"
            Text("来源请求 $width × $height · 实际 $actual",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
