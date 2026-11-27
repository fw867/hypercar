package io.github.hypercar.ui.screens

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.hypercar.session.StartupReadiness
import io.github.hypercar.session.StartupRequirements
import io.github.hypercar.settings.SettingsRevision
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.settings.YadeaConnectionMode
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.StatusHeroCard
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import io.github.hypercar.yadea.YadeaP2p
import io.github.hypercar.yadea.YadeaP2pPeer
import io.github.hypercar.yadea.YadeaPhonePreview
import io.github.hypercar.yadea.YadeaProjectionService
import io.github.hypercar.yadea.YadeaSessionState
import io.github.hypercar.yadea.YadeaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

@Composable
internal fun YadeaHomeScreen(startup: StartupReadiness, state: YadeaSessionState) {
    val context = LocalContext.current
    val revision = SettingsRevision.value
    val host = remember(revision) { SettingsStore.yadeaInstrumentIp }
    val source = remember(revision) { YadeaSource.fromSetting(SettingsStore.carSource) }
    val mode = remember(revision) { SettingsStore.yadeaConnectionMode }
    var showScan by remember(mode, source) { mutableStateOf(false) }
    val sourceAvailable = when (source) {
        YadeaSource.CarWith -> startup.carWithInstalled
        YadeaSource.SystemCarLife -> source.installed(context)
        null -> false
    }
    val startBlocked = when {
        source == null -> "在「设置 → 画面与声音」选择 CarWith 或 CarLife+。"
        !sourceAvailable -> "本机未安装或未启用 ${source.title}。"
        mode == YadeaConnectionMode.Manual && !YadeaProjectionService.isValidInstrumentIp(host) ->
            "在「设置 → 我的车机」填写仪表 IP。"
        mode == YadeaConnectionMode.P2p && !YadeaP2p.supported(context) -> "本机不支持 Wi-Fi Direct。"
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
            profileLabel = "雅迪仪表 · ${mode.label}",
            idleHint = if (mode == YadeaConnectionMode.P2p) "启动后扫描并选择仪表。"
                else "手机接入仪表 Wi-Fi 后启动连接。",
            connectedLabel = "投屏中",
            manualRetryHint = "检查仪表网络和导航来源后重试。",
            onStartClick = {
                if (source != null && startBlocked == null && !YadeaProjectionService.sessionState.value.active) {
                    if (mode == YadeaConnectionMode.P2p) showScan = true
                    else YadeaProjectionService.start(context, source, host = host)
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
    if (showScan && source != null && !state.active) {
        P2pScanDialog(onDismiss = { showScan = false }, onSelect = { peer, scanner ->
            showScan = false
            YadeaProjectionService.startP2p(context, source, peer, scanner)
        })
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

@Composable
private fun P2pScanDialog(onDismiss: () -> Unit, onSelect: (YadeaP2pPeer, YadeaP2p) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissions = remember { YadeaP2p.permissions() }
    var peers by remember { mutableStateOf<List<YadeaP2pPeer>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scanner = remember(context) { YadeaP2p(context) }
    var handedOff by remember(scanner) { mutableStateOf(false) }

    fun startScan() {
        if (scanning || selecting) return
        peers = emptyList()
        error = null
        scanning = true
        scanJob = scope.launch {
            try {
                scanner.discover { peers = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.localizedMessage ?: "扫描失败，请重试。"
            } finally {
                scanning = false
            }
        }
    }
    fun permissionGranted() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (permissionGranted()) startScan()
        else error = "扫描需要附近设备或精确位置权限，请授权后重试。"
    }
    fun requestScan() {
        if (permissionGranted()) startScan()
        else permissionLauncher.launch(permissions)
    }
    LaunchedEffect(Unit) { requestScan() }
    DisposableEffect(scanner) {
        onDispose {
            scanJob?.cancel()
            if (!handedOff) scanner.close()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!selecting) onDismiss() },
        title = { Text("扫描雅迪仪表") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text(error ?: when {
                    selecting -> "正在连接所选仪表…"
                    scanning -> "正在扫描。请先让仪表开启投屏 Wi-Fi Direct。"
                    peers.isEmpty() -> "未发现设备。请确认仪表已开启投屏 Wi-Fi Direct，再重新扫描。"
                    else -> "选择要连接的仪表。"
                })
                peers.forEach { peer ->
                    TextButton(enabled = !selecting, onClick = {
                        selecting = true
                        scanner.finishScanForConnection()
                        scope.launch {
                            scanJob?.cancelAndJoin()
                            handedOff = true
                            onSelect(peer, scanner)
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("${peer.name} · ${peer.address}")
                    }
                }
            }
        },
        dismissButton = { TextButton(enabled = !selecting, onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(enabled = !scanning && !selecting, onClick = ::requestScan) { Text("重新扫描") } },
    )
}
