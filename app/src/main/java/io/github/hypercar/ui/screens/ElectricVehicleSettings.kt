package io.github.hypercar.ui.screens

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.ProjectionTarget
import io.github.hypercar.settings.SettingsRevision
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.TextSecondary
import io.github.hypercar.yadea.YadeaConnectionMode
import io.github.hypercar.yadea.YadeaInstrumentNetwork
import io.github.hypercar.yadea.YadeaProjectionService
import io.github.hypercar.yadea.YadeaSource
import io.github.hypercar.yadea.yadeaAudioOutputs
import kotlinx.coroutines.launch

@Composable
internal fun ElectricVehicleSettings(selected: Boolean, connected: Boolean) {
    ProjectionTargetControl(ProjectionTarget.Yadea, selected, connected)
    InstrumentNetworkSection(connected)
    InstrumentSourceSection(connected)
    YadeaDisplayAndAudioSection()
}

@Composable
private fun ProjectionTargetControl(target: ProjectionTarget, selected: Boolean, connected: Boolean) {
    val context = LocalContext.current
    SectionLabel(target.label)
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        when {
            selected -> Text("当前投屏目标", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            connected -> Text("结束当前会话后可切换投屏目标。", color = TextSecondary,
                style = MaterialTheme.typography.bodySmall)
            else -> CapsuleButton(
                "使用 ${target.label}",
                onClick = { HyperCarRepo.setProjectionTarget(context, target) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InstrumentSourceSection(connected: Boolean) {
    val context = LocalContext.current
    val revision = SettingsRevision.value
    val selected = remember(revision) { SettingsStore.yadea.source }
    val available = remember(context, revision) { YadeaSource.entries.filter { it.installed(context) } }
    SectionLabel("画面来源")
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            YadeaSource.entries.forEachIndexed { index, source ->
                SegmentedButton(
                    selected = selected == source,
                    enabled = !connected && source in available,
                    onClick = {
                        if (!connected && source.installed(context)) {
                            SettingsStore.yadea.source = source
                            SettingsRevision.bump()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, YadeaSource.entries.size),
                    label = { Text(source.title) },
                )
            }
        }
        if (available.isEmpty() || selected != null && selected !in available) {
            Text("请先安装或启用手机上的 CarWith 或 CarLife+。", color = AmberWarning,
                style = MaterialTheme.typography.bodySmall)
        }
        when (selected) {
            YadeaSource.CarWith -> if (selected in available) CarWithSettingsShortcut()
            YadeaSource.SystemCarLife -> SystemCarLifeSettingsShortcut(!connected)
            null -> Unit
        }
    }
}

@Composable
private fun InstrumentNetworkSection(connected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(SettingsStore.yadea.instrumentIp) }
    val mode = remember(SettingsRevision.value) { SettingsStore.yadea.connectionMode }
    val currentAddress = address.trim()
    var pinging by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    SectionLabel(text = "雅迪仪表网络")
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            YadeaConnectionMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == mode,
                    enabled = !connected,
                    onClick = {
                        SettingsStore.yadea.connectionMode = option
                        SettingsRevision.bump()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, YadeaConnectionMode.entries.size),
                    label = { Text(option.label) },
                )
            }
        }
        if (mode == YadeaConnectionMode.Manual) {
            OutlinedTextField(
                value = address,
                onValueChange = { input ->
                    address = input
                    SettingsStore.yadea.instrumentIp = input.trim()
                    SettingsRevision.bump()
                },
                enabled = !connected,
                singleLine = true,
                label = { Text("仪表 IP 地址") },
                isError = address.isNotBlank() && !YadeaProjectionService.isValidInstrumentIp(address),
                supportingText = { Text("手机接入仪表 Wi-Fi 后，从主页启动连接。") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = !connected && !pinging && YadeaProjectionService.isValidInstrumentIp(currentAddress),
                onClick = {
                    pinging = true
                    pingResult = null
                    scope.launch {
                        try {
                            pingResult = currentAddress to YadeaInstrumentNetwork.check(context, currentAddress)
                        } finally {
                            pinging = false
                        }
                    }
                },
            ) { Text(if (pinging) "正在 Ping…" else "Ping 仪表") }
            pingResult?.takeIf { it.first == currentAddress }?.second?.let { result ->
                Text(result, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text("仪表开启投屏 Wi-Fi Direct 后，从主页启动扫描并选择仪表。", color = TextSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun YadeaDisplayAndAudioSection() {
    val context = LocalContext.current
    val state by YadeaProjectionService.sessionState.collectAsStateWithLifecycle()
    val routeId by YadeaProjectionService.audioRoute.collectAsStateWithLifecycle()
    var routes by remember(context) { mutableStateOf(yadeaAudioOutputs(context)) }
    var menuOpen by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val manager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                routes = yadeaAudioOutputs(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                routes = yadeaAudioOutputs(context)
            }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    SectionLabel(text = "媒体音频")
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Text("在 Android 蓝牙设置中连接车辆并启用媒体音频。", color = TextSecondary,
            style = MaterialTheme.typography.bodySmall)
        val routeLabel = when (val id = routeId) {
            null -> "跟随系统"
            else -> routes.firstOrNull { it.id == id }?.label ?: "选定设备已断开"
        }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("音频输出 · $routeLabel") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("跟随系统") }, onClick = {
                    YadeaProjectionService.setRoute(null)
                    menuOpen = false
                })
                routes.forEach { route ->
                    DropdownMenuItem(text = { Text(route.label) }, onClick = {
                        YadeaProjectionService.setRoute(route.id)
                        menuOpen = false
                    })
                }
            }
        }
        if (state.active) Text(state.route, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    SectionLabel(text = "显示参数")
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        val display = state.display
        Text(if (display == null) "连接仪表后自动协商分辨率和帧率。"
            else "${display.width} × ${display.height} · ${display.fps} fps（${if (display.negotiated) "仪表确认" else "本地回退"}）",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
