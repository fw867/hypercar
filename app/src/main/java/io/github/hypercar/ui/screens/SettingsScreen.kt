@file:Suppress("DEPRECATION")   // 平台 API / 旧图标名已废弃但当前无替代，统一在此抑制
package io.github.hypercar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import android.Manifest
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.carui.CarUiSettings
import io.github.hypercar.perm.AppPermissions
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsRevision
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.settings.ProjectionTarget
import io.github.hypercar.settings.YadeaConnectionMode
import io.github.hypercar.shizuku.ShizukuState
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.ui.components.CarBrandBadge
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.InfoRow
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.SurfaceInset
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import io.github.hypercar.yadea.YadeaProjectionService
import io.github.hypercar.yadea.YadeaInstrumentNetwork
import io.github.hypercar.yadea.yadeaAudioOutputs

/**
 * 分辨率预设（**兜底用**）。实际分辨率以车机 `/info` 回报为准，手机回报实际
 * 编码尺寸后还会再覆盖一次；只有两者都拿不到时才落到这里。宽度均为偶数。
 */
private val FpsOptions = listOf(30, 60)

private enum class SettingsPage(val title: String) {
    Main("设置"),
    Car("我的车机"),
    HeadUnitPicker("选择车机档案"),
    Display("画面与声音"),
    Permissions("权限与后台"),
    Advanced("高级设置"),
    Appearance("主题与外观"),
    Developer("开发者菜单");

    val parent: SettingsPage get() = Main
}

/** Settings navigation changes presentation only; all writes keep their existing handlers. */
@Composable
fun SettingsScreen(
    startup: io.github.hypercar.session.StartupReadiness,
    onRefreshSetup: () -> Unit,
    modifier: Modifier = Modifier,
    linkConnected: Boolean = false,
) {
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.Main.name) }
    var showProjectionApps by rememberSaveable { mutableStateOf(false) }
    val developerMode = remember(SettingsRevision.value) { SettingsStore.developerModeEnabled }
    val showDeveloperMenu = remember(SettingsRevision.value) { SettingsStore.developerMenuVisible }
    val target = remember(SettingsRevision.value) { SettingsStore.projectionTarget }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName }
        ?.takeUnless { it == SettingsPage.Developer && !showDeveloperMenu } ?: SettingsPage.Main
    fun open(destination: SettingsPage) { pageName = destination.name }
    BackHandler(enabled = page != SettingsPage.Main && page != SettingsPage.HeadUnitPicker) { open(page.parent) }

    if (page == SettingsPage.HeadUnitPicker) {
        HeadUnitPickerScreen(connected = linkConnected, modifier = modifier, onClose = { open(SettingsPage.Car) })
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (page != SettingsPage.Main) {
                TextButton(onClick = { open(page.parent) }) { Text("‹ 返回", color = ElectricCyan) }
                Spacer(Modifier.width(8.dp))
            }
            Text(page.title, color = TextPrimary, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 12.dp))
        }
        key(page) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (page) {
                    SettingsPage.Main -> {
                        Text("选择要调整的功能", color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall)
                        GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                            SettingsMenuItem("我的车机", "投屏目标 · ${target.label}") { open(SettingsPage.Car) }
                            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
                            SettingsMenuItem("画面与声音", "画面来源、音频格式与显示参数") { open(SettingsPage.Display) }
                            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
                            SettingsMenuItem("自定义投屏应用", "仅对自绘车机 UI 生效 · 已选 ${CarUiSettings.projectionPackages.size} 个") {
                                showProjectionApps = true
                            }
                            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
                            SettingsMenuItem("主题与外观", "跟随系统、HyperOS 风格浅色与深色") { open(SettingsPage.Appearance) }
                            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
                            SettingsMenuItem("权限与后台", "系统权限、后台运行与 Shizuku 授权") { open(SettingsPage.Permissions) }
                            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
                            SettingsMenuItem("高级设置", "手动蓝牙地址与清除全部配对身份") { open(SettingsPage.Advanced) }
                            if (showDeveloperMenu) {
                                HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
                                SettingsMenuItem("开发者菜单", "连接诊断工具") { open(SettingsPage.Developer) }
                            }
                        }
                    }
                    SettingsPage.Car -> {
                        ProjectionTargetSection(target, linkConnected)
                        if (target == ProjectionTarget.Yadea) {
                            InstrumentNetworkSection(linkConnected)
                        } else {
                            AutoConnectSection(startup)
                            HeadUnitSection(connected = linkConnected, onOpenPicker = { open(SettingsPage.HeadUnitPicker) })
                            TargetCarSection()
                        }
                    }
                    SettingsPage.Display -> {
                        CarSourceSection(startup, onRefreshSetup, connected = linkConnected,
                            title = "画面来源", compact = true, yadea = target == ProjectionTarget.Yadea)
                        if (target == ProjectionTarget.Yadea) {
                            YadeaDisplayAndAudioSection()
                        } else {
                            AudioSection()
                            ResolutionSection()
                            ScreenResourceSection()
                        }
                    }
                    SettingsPage.Permissions -> {
                        PermissionSettingsSection()
                        ShizukuSection(startup)
                    }
                    SettingsPage.Appearance -> AppearanceSection()
                    SettingsPage.Advanced -> {
                        IdentitySection()
                        ResetPairingSection()
                        if (developerMode) DeveloperModeSection(showDeveloperMenu)
                    }
                    SettingsPage.Developer -> DeveloperMenuSection()
                    SettingsPage.HeadUnitPicker -> Unit
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (page == SettingsPage.Main && showProjectionApps) {
        ProjectionAppsDialog { showProjectionApps = false }
    }
}

@Composable
private fun ProjectionTargetSection(target: ProjectionTarget, connected: Boolean) {
    val context = LocalContext.current
    SectionLabel(text = "投屏目标")
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ProjectionTarget.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == target,
                    enabled = !connected,
                    onClick = { HyperCarRepo.setProjectionTarget(context, option) },
                    shape = SegmentedButtonDefaults.itemShape(index, ProjectionTarget.entries.size),
                    label = { Text(option.label) },
                )
            }
        }
        if (connected) Text("结束当前会话后可切换投屏目标。", color = TextSecondary,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InstrumentNetworkSection(connected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(SettingsStore.yadeaInstrumentIp) }
    val mode = remember(SettingsRevision.value) { SettingsStore.yadeaConnectionMode }
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
                        SettingsStore.yadeaConnectionMode = option
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
                    SettingsStore.yadeaInstrumentIp = input.trim()
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

@Composable
private fun DeveloperModeSection(menuVisible: Boolean) {
    SectionLabel("开发者模式")
    GlassPanel(contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示开发者菜单", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Text("在设置首页显示开发者菜单", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = menuVisible, onCheckedChange = {
                if (!it) HyperCarRepo.disarmBmwCaffExperiment()
                SettingsStore.developerMenuVisible = it
            })
        }
    }
}

@Composable
private fun DeveloperMenuSection() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val oldDeviceReadiness by HyperCarRepo.oldDeviceReadiness.collectAsStateWithLifecycle()
    var copying by remember { mutableStateOf(false) }
    var experimentResult by rememberSaveable { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    val armedTarget by HyperCarRepo.armedBmwCaffExperiment.collectAsStateWithLifecycle()
    var armError by remember { mutableStateOf<String?>(null) }
    var dialing by remember { mutableStateOf(false) }
    var dialResult by rememberSaveable { mutableStateOf<String?>(null) }
    SectionLabel("车机发现诊断")
    GlassPanel(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("等待车机邀请时，手动补发当前服务的通告。不重新注册服务，也不更换端口。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            CapsuleButton(if (sending) "发送中…" else "重发 mDNS 通告", enabled = !sending && !dialing, onClick = send@{
                if (sending || dialing) return@send
                sending = true
                result = null
                scope.launch {
                    try {
                        result = HyperCarRepo.resendCtrlMdnsAnnouncement()
                    } finally {
                        sending = false
                    }
                }
            })
            result?.let { Text(it, color = TextPrimary, style = MaterialTheme.typography.bodySmall) }
        }
    }
    SectionLabel("宝马 iD8.5 主动拨号实验")
    GlassPanel(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("显示菜单不改变正常连接。准备实验后，在首页手动发起的新一轮才会暂停自动 …caff，自动上车和自动重连不受影响。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            CapsuleButton(if (armedTarget != null) "取消准备" else "准备下一轮手动实验", enabled = !dialing && !sending, onClick = {
                dialResult = null
                armError = if (armedTarget == null) HyperCarRepo.armBmwCaffExperiment() else {
                    HyperCarRepo.disarmBmwCaffExperiment()
                    null
                }
            })
            if (armedTarget != null) {
                Text("已准备：到首页手动发起连接；隐藏菜单会取消尚未开始的实验。",
                    color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            }
            armError?.let { Text(it, color = TextPrimary, style = MaterialTheme.typography.bodySmall) }
            Text("实验开始后，可以先重发 mDNS。重发与拨号不能同时进行；车机邀请已应答但仍在等 Wi-Fi 地址确认时，不拨号，也不消耗机会。只有目标车机的经典蓝牙仍在线时才能拨号，本轮最多一次。离开本页不会中断已经开始的拨号，可在诊断日志查看结果。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            CapsuleButton(if (dialing) "探测中…" else "主动拨号 …caff", enabled = !dialing && !sending, onClick = dial@{
                if (dialing || sending) return@dial
                dialing = true
                dialResult = null
                scope.launch {
                    try {
                        dialResult = HyperCarRepo.dialBmwCaffManually()
                    } finally {
                        dialing = false
                    }
                }
            })
            dialResult?.let { Text(it, color = TextPrimary, style = MaterialTheme.typography.bodySmall) }
        }
    }
    SectionLabel("连接实验")
    GlassPanel(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val readiness = oldDeviceReadiness
            val message = if (readiness == null) "等待连接时显示旧设备就绪状态"
            else if (readiness.ready) "已就绪，可以点车机里的旧设备 CarPlay"
            else "等待 ${buildList {
                if (readiness.network == null) add("车机 Wi-Fi 绑定")
                if (readiness.srv == null) add("观察到当前 SRV")
                if (readiness.connectable != true) add("蓝牙可连接")
                if (!readiness.rfcommAccepting) add("iAP2 入站监听开放")
            }.joinToString("、")}"
            Text(message, color = if (readiness?.ready == true) MintGreen else TextSecondary,
                style = MaterialTheme.typography.bodyMedium)
            Text("刷卡解锁后和点旧设备 CarPlay 前分别打点。诊断日志会记录点击前 15 秒、点击后 30 秒的发现和邀请证据。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            CapsuleButton("已刷卡", onClick = {
                experimentResult = HyperCarRepo.markConnectionExperiment(HyperCarRepo.ExperimentMarker.CARD_UNLOCKED)
            })
            CapsuleButton("即将点旧设备 CarPlay", onClick = {
                experimentResult = HyperCarRepo.markConnectionExperiment(HyperCarRepo.ExperimentMarker.ABOUT_TO_SELECT_CARPLAY)
            })
            CapsuleButton("旧设备 CarPlay 报错", onClick = {
                experimentResult = HyperCarRepo.markConnectionExperiment(HyperCarRepo.ExperimentMarker.OLD_CARPLAY_ERROR)
            })
            CapsuleButton("在连接新设备列表点手机", onClick = {
                experimentResult = HyperCarRepo.markConnectionExperiment(HyperCarRepo.ExperimentMarker.NEW_LIST_PHONE_SELECTED)
            })
            CapsuleButton("复制本轮连接快照", enabled = !copying, onClick = copy@{
                if (copying) return@copy
                copying = true
                scope.launch {
                    try {
                        val snapshot = HyperCarRepo.latestConnectionSnapshot()
                        experimentResult = if (snapshot == null) {
                            "暂无连接快照，请先发起一次连接"
                        } else {
                            clipboard.setText(AnnotatedString(snapshot))
                            "已复制本轮连接快照"
                        }
                    } finally {
                        copying = false
                    }
                }
            })
            experimentResult?.let { Text(it, color = TextPrimary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection() {
    val mode = remember(io.github.hypercar.settings.SettingsRevision.value) { SettingsStore.appThemeMode }
    SectionLabel("主题模式")
    GlassPanel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            io.github.hypercar.settings.AppThemeMode.entries.forEachIndexed { index, option ->
                SegmentedButton(selected = mode == option, onClick = { SettingsStore.appThemeMode = option },
                    shape = SegmentedButtonDefaults.itemShape(index, count = 3),
                    label = { Text(option.label, style = MaterialTheme.typography.labelMedium) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("跟随手机系统自动切换；手动选择即时生效。", color = TextSecondary,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text("仅改变 App 的主题风格，车机主题请在车机中设置。", color = TextSecondary,
            style = MaterialTheme.typography.bodySmall)
    }
    GlassPanel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Text(if (io.github.hypercar.ui.theme.LocalAppPalette.current.dark) "当前：深色主题" else "当前：HyperOS 风格浅色主题",
            color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(if (io.github.hypercar.ui.theme.LocalAppPalette.current.dark) "深空背景 · 青色强调 · 玻璃卡片"
            else "浅灰背景 · 蓝色强调 · 白色圆角卡片", color = TextSecondary,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsMenuItem(title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(description, color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text("›", color = ElectricCyan, style = MaterialTheme.typography.titleLarge)
    }
}

/* ---------- Shizuku ---------- */

/**
 * Shizuku 入口。
 *
 * 之前只有一个**只读**状态芯片（`StatChips.ShizukuChip`），而 `requestPermissionIfNeeded`
 * 从未被调用——用户看到"未启用 Shizuku"却没有任何地方可以授权。这里按四种状态给出
 * 对应的下一步动作：安装 → 打开 → 授权 → 已就绪。
 */
@Composable
private fun ShizukuSection(startup: io.github.hypercar.session.StartupReadiness) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val state = remember(version, startup.shizukuReady) { ShizukuTools.state(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) version++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val (statusText, statusColor) = when (state) {
        ShizukuState.Ready -> "已就绪" to MintGreen
        ShizukuState.NotGranted -> "已运行 · 待授权" to AmberWarning
        ShizukuState.NotRunning -> "已安装 · 未启动" to AmberWarning
        ShizukuState.NotInstalled -> "未安装" to TextSecondary
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "Shizuku 授权")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = state.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.hint(),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                ShizukuState.NotInstalled -> CapsuleButton(
                    text = "安装 Shizuku",
                    onClick = { ShizukuTools.openShizukuSite(context) },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )

                ShizukuState.NotRunning -> CapsuleButton(
                    text = "打开 Shizuku 并启动服务",
                    onClick = {
                        if (!ShizukuTools.openShizukuApp(context)) {
                            ShizukuTools.openShizukuSite(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )

                ShizukuState.NotGranted -> CapsuleButton(
                    text = "请求授权",
                    onClick = {
                        ShizukuTools.requestPermissionIfNeeded { _ ->
                            // 无论允许/拒绝都刷新：授权框关闭后状态可能已变
                            version++
                            HyperCarRepo.refreshSystemState()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )

                ShizukuState.Ready -> CapsuleButton(
                    text = "重新检查",
                    onClick = {
                        version++
                        HyperCarRepo.refreshSystemState()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = false,
                )
            }


        }
    }
}

private fun ShizukuState.label(): String = when (this) {
    ShizukuState.Ready -> "Shizuku 已授权"
    ShizukuState.NotGranted -> "Shizuku 服务在运行"
    ShizukuState.NotRunning -> "Shizuku 已安装但服务未启动"
    ShizukuState.NotInstalled -> "未检测到 Shizuku"
}

private fun ShizukuState.hint(): String = when (this) {
    ShizukuState.Ready -> "自绘 UI 所需授权已完成；CarWith 无需此项。"
    ShizukuState.NotGranted ->
        "点下方按钮会弹出 Shizuku 的授权框；若没弹，请先在 Shizuku 里确认服务已启动。"
    ShizukuState.NotRunning ->
        "需要先打开 Shizuku 并启动服务（无线调试配对，或 root 启动）。"
    ShizukuState.NotInstalled ->
        "自绘车机 UI 必须先安装 Shizuku，再启动服务并授权 HyperCar。点击安装可前往 GitHub 最新发布页。"
}

/* ---------- 设备身份（蓝牙 MAC） ---------- */

/**
 * 本机蓝牙 MAC：免 Shizuku 的手动入口。
 *
 * Android 6.0+ 应用层拿不到本机蓝牙地址，读 `settings/secure` 又要 Shizuku/root。
 * 部分接收端会核对 iAP2、mDNS 上报的身份与蓝牙配对地址。
 * 未收到车机邀请时，可填写本机真实蓝牙地址后重新连接。
 */
@Composable
private fun IdentitySection() {
    var dialogVisible by remember { mutableStateOf(false) }
    val version = io.github.hypercar.settings.SettingsRevision.value
    val manual = remember(version) { SettingsStore.manualBtMac }
    val pseudo = remember(version) { SettingsStore.stableDeviceId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "设备身份（蓝牙 MAC）")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = manual ?: "自动",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (manual != null) ElectricCyan else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (manual != null) {
                            "已保存，下次连接继续使用此地址"
                        } else {
                            "自动获取地址；备用标识为 $pseudo"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                CapsuleButton(
                    text = "填写",
                    onClick = { dialogVisible = true },
                    modifier = Modifier.height(44.dp),
                    filled = false,
                    contentColor = ElectricCyan,
                    borderColor = ElectricCyan.copy(alpha = 0.6f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Shizuku 读到真实地址后自动保存。使用备用标识时，首次成功写出视频帧后保存。" +
                    "可手动修改或清除，改动在下次连接生效。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (dialogVisible) {
        ManualBtMacDialog(
            initial = manual.orEmpty(),
            onDismiss = { dialogVisible = false },
            onCleared = {
                SettingsStore.manualBtMac = null
                HyperCarRepo.refreshSystemState()
                dialogVisible = false
            },
            onSaved = { value ->
                SettingsStore.manualBtMac = value
                HyperCarRepo.refreshSystemState()
                dialogVisible = false
            },
        )
    }
}

/** 手动填写本机蓝牙 MAC（格式校验后才允许保存）。 */
@Composable
private fun ManualBtMacDialog(
    initial: String,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val trimmed = text.trim()
    val valid = io.github.hypercar.net.PhoneIdentitySnapshot.normalize(trimmed) != null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(io.github.hypercar.ui.theme.ScrimDeepSpace)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(20.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
                contentPadding = PaddingValues(20.dp),
            ) {
                Text(
                    text = "本机蓝牙 MAC",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "填写手机本机的蓝牙地址。可在手机状态信息中查看，或用电脑执行：\n" +
                        "adb shell settings get secure bluetooth_address\n" +
                        "清除后恢复自动获取，改动在下次连接生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                DialogTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "蓝牙 MAC（AA:BB:CC:DD:EE:FF）",
                    keyboardType = KeyboardType.Ascii,
                )
                if (trimmed.isNotEmpty() && !valid) {
                    Text(
                        text = "格式不对：需要 6 组两位十六进制，用冒号分隔",
                        style = MaterialTheme.typography.labelSmall,
                        color = CoralError,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleButton(
                        text = "清除",
                        onClick = onCleared,
                        modifier = Modifier.height(44.dp),
                        filled = false,
                        contentColor = AmberWarning,
                        borderColor = AmberWarning.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CapsuleButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.height(44.dp),
                        filled = false,
                        contentColor = TextSecondary,
                        borderColor = StrokeGlass,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    CapsuleButton(
                        text = "保存",
                        onClick = { onSaved(trimmed) },
                        modifier = Modifier.height(44.dp),
                        enabled = valid,
                        filled = true,
                    )
                }
            }
        }
    }
}

/* ---------- 权限与系统设置 ---------- */

/**
 * 设置页的权限入口（与首页的「权限检查」卡互补）：
 * 首页卡片在必需项齐备后会隐藏/可收起，这里**常驻**，方便随时回来复查或改系统开关。
 */
@Composable
private fun PermissionSettingsSection() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val summary = remember(version) { AppPermissions.summary(context) }
    val missing = remember(version) { AppPermissions.missingRequired(context) }

    // 从系统设置页返回后自动刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) version++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { version++ }

    val essentialsOk = missing.isEmpty()
    val missingNames = buildList {
        if (!summary.nearby) add("附近设备")
        if (summary.locationRequired && !summary.location) add("定位")
        if (summary.notificationRequired && !summary.notification) add("通知")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "权限与系统设置")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (essentialsOk) "必需权限已就绪" else "缺 ${missingNames.size} 项必需权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (essentialsOk) MintGreen else AmberWarning,
                    )
                    Text(
                        text = if (essentialsOk) {
                            "附近设备与通知已授予"
                        } else {
                            missingNames.joinToString("、") + " 未授予——对应功能会静默失效"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "重新检查",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { version++ }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            if (!essentialsOk) {
                Spacer(modifier = Modifier.height(10.dp))
                CapsuleButton(
                    text = "补授缺失权限",
                    onClick = {
                        val all = missing.flatMap { AppPermissions.perms(it).toList() }.distinct()
                        if (all.isNotEmpty()) launcher.launch(all.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))

            InfoRow(
                label = "自启动",
                value = when (summary.autostart) {
                    true -> "已允许"
                    false -> "已禁止"
                    null -> "去设置"
                },
                onClick = {
                    if (!AppPermissions.openAutostartSettings(context)) {
                        AppPermissions.openAppDetails(context)
                    }
                },
            )
            InfoRow(
                label = "电池优化白名单",
                value = if (summary.batteryWhitelisted) "已加入" else "去设置",
                onClick = { AppPermissions.requestIgnoreBatteryOptimizations(context) },
            )
            InfoRow(
                label = "悬浮窗（后台启动豁免）",
                value = if (summary.overlay) "已允许" else "去设置",
                onClick = { AppPermissions.openOverlaySettings(context) },
            )
            InfoRow(
                label = "音乐卡片通知使用权",
                value = if (io.github.hypercar.carui.MusicBridge.hasAccess(context)) "已授予" else "去设置",
                onClick = { io.github.hypercar.carui.MusicBridge.openAccessSettings(context) },
            )
            InfoRow(
                label = "系统权限页",
                value = "打开",
                onClick = { AppPermissions.openAppDetails(context) },
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "自启动、电池白名单属系统专有设置，部分系统不提供查询接口，" +
                    "显示「去设置」不代表未开启。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------- 目标车机 ---------- */

@Composable
private fun TargetCarSection() {
    val revision = io.github.hypercar.settings.SettingsRevision.value
    val targetMac = SettingsStore.targetCarMac
    val targetName = SettingsStore.targetCarName ?: targetMac ?: "未指定车机"
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dialogVisible by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val selectCar = rememberCarSelection()
    val scope = rememberCoroutineScope()
    var pendingClear by remember { mutableStateOf<Pair<String, String>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val enhanced = remember(expanded, revision) {
        io.github.hypercar.autowake.CarCompanionWake.associatedAddresses(context)
    }
    // 已配对设备（下拉候选）。BLUETOOTH_CONNECT 未授予时给空表，
    // 未指定目标和手动输入仍可用，不将目标过滤描述成主动扫描。
    val bonded = remember(expanded) {
        runCatching {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                emptyList<Pair<String, String>>()
            } else {
                BluetoothAdapter.getDefaultAdapter()
                    ?.bondedDevices
                    ?.map { (it.name ?: "未命名设备") to it.address }
                    ?.sortedBy { it.first }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    // Keep saved CarPlay receivers visible even if their system Bluetooth bond was removed.
    val candidates = remember(bonded, revision) {
        val history = SettingsStore.pairedCars.mapNotNull { row ->
            val mac = row.substringBefore('|')
            if (io.github.hypercar.session.PairingEvidence.receiverKey(mac) == null) null
            else row.substringBeforeLast('|').substringAfter('|').ifBlank { mac } to mac
        }
        (bonded + history).distinctBy { it.second.uppercase(java.util.Locale.ROOT) }.sortedBy { it.first }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "目标车机")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = targetName,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (targetMac == null) TextSecondary else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "点击列表中的车机，选择并授权增强发现",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Text(
                        text = "▾",
                        style = MaterialTheme.typography.titleMedium,
                        color = ElectricCyan,
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(352.dp),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = SurfaceInset,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StrokeGlass),
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "不指定车机",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (targetMac == null) ElectricCyan else TextPrimary,
                                )
                                Text(
                                    text = "不限定目标，由车机发起连接",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                )
                            }
                        },
                        onClick = {
                            selectCar(null, null)
                            expanded = false
                        },
                    )
                    if (candidates.isNotEmpty()) {
                        HorizontalDivider(color = StrokeGlass)
                        candidates.forEach { (name, mac) ->
                            val pairedAt = SettingsStore.pairedCarAt(mac)
                            val paired = SettingsStore.isCarPaired(mac)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (mac.equals(targetMac, ignoreCase = true)) ElectricCyan else TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (paired) {
                                                TargetCarBadge(if (pairedAt != null) "已配对 · " + agoText(pairedAt) else "已配对")
                                            }
                                            if (enhanced.any { it.equals(mac, ignoreCase = true) }) {
                                                if (paired) Spacer(modifier = Modifier.width(6.dp))
                                                TargetCarBadge("增强发现")
                                            }
                                        }
                                        Text(
                                            text = mac,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                        )
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    selectCar(mac, name)
                                },
                                trailingIcon = if (paired) ({
                                    TextButton(
                                        enabled = !clearing,
                                        onClick = {
                                            expanded = false
                                            pendingClear = mac to name
                                        },
                                    ) {
                                        Text("清除配对", color = AmberWarning, style = MaterialTheme.typography.labelMedium)
                                    }
                                }) else null,
                            )
                        }
                    }
                    HorizontalDivider(color = StrokeGlass)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "手动输入 MAC…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                            )
                        },
                        onClick = {
                            expanded = false
                            dialogVisible = true
                        },
                    )
                }
            }
        }
    }

    if (dialogVisible) {
        TargetCarDialog(
            initialMac = SettingsStore.targetCarMac.orEmpty(),
            initialName = SettingsStore.targetCarName.orEmpty(),
            onDismiss = { dialogVisible = false },
            onCleared = {
                selectCar(null, null)
                dialogVisible = false
            },
            onSaved = { mac, name ->
                selectCar(mac, name)
                dialogVisible = false
            },
        )
    }
    val pending = pendingClear
    if (pending != null) {
        ResetPairingDialog(
            targetName = pending.second,
            onDismiss = { pendingClear = null },
            onConfirm = {
                pendingClear = null
                clearing = true
                scope.launch {
                    try {
                        val result = HyperCarRepo.resetPairingIdentity(pending.first)
                        android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
                    } finally { clearing = false }
                }
            },
        )
    }

}

@Composable
private fun ResetPairingSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resetConfirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !resetting) { resetConfirm = true }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("清除全部配对身份", style = MaterialTheme.typography.titleSmall, color = AmberWarning)
                Text(
                    "清除所有车机的本地身份、配对记录与关联。仅清除某台车机，请使用目标车机列表右侧的按钮。",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = AmberWarning)
        }
    }
    if (resetConfirm) {
        ResetPairingDialog(
            onDismiss = { resetConfirm = false },
            onConfirm = {
                resetConfirm = false
                resetting = true
                scope.launch {
                    try {
                        val result = HyperCarRepo.resetAllPairingIdentities()
                        android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
                    } finally { resetting = false }
                }
            },
        )
    }
}

@Composable
private fun TargetCarBadge(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = ElectricCyan,
        maxLines = 1,
        modifier = Modifier.background(ElectricCyan.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 「重置配对身份」确认弹窗：写清会删什么、以及车机端也要删。 */
@Composable
private fun ResetPairingDialog(
    targetName: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(io.github.hypercar.ui.theme.ScrimDeepSpace)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(20.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
                contentPadding = PaddingValues(20.dp),
            ) {
                Text(
                    text = if (targetName == null) "清除全部配对身份" else "清除该车机配对",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    text = if (targetName == null) {
                        "将清除所有车机的本地配对身份、身份备份、已配对标记、Wi-Fi 关联、增强发现授权与 MFi 记忆。\n\n" +
                            "目标车机设为未指定。分辨率、车机型号等设置与系统蓝牙配对保留。\n\n" +
                            "下次连接将重新生成身份；请在车机端删除旧手机记录后重新配对。"
                    } else "仅清除车机：$targetName\n\n" +
                        "· 清除该车机的本地配对身份与已配对标记\n" +
                        "· 清除该车机的 Wi-Fi 关联和增强发现授权\n" +
                        "· 若当前指定的是该车机，则改为未指定\n\n" +
                        "其它车机的配对与设置保留，本机系统蓝牙配对不删除。\n\n" +
                        "下次连接会为该车机生成独立身份。请在该车机端删除旧手机记录后重新配对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    CapsuleButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.height(44.dp),
                        filled = false,
                        contentColor = TextSecondary,
                        borderColor = StrokeGlass,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    CapsuleButton(
                        text = if (targetName == null) "清除全部" else "清除该车机",
                        onClick = onConfirm,
                        modifier = Modifier.height(44.dp),
                        filled = true,
                        contentColor = io.github.hypercar.ui.theme.InkBlack,
                        borderColor = AmberWarning,
                    )
                }
            }
        }
    }
}

/** 目标车机手动配置弹窗：玻璃面板自定义 Dialog（不用 Material 默认弹窗底色）。 */
@Composable
private fun TargetCarDialog(
    initialMac: String,
    initialName: String,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
    onSaved: (String, String) -> Unit,
) {
    var mac by rememberSaveable { mutableStateOf(initialMac) }
    var name by rememberSaveable { mutableStateOf(initialName) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(io.github.hypercar.ui.theme.ScrimDeepSpace)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(20.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吞掉面板内点击，避免误触关闭
                    .pointerInput(Unit) { detectTapGestures { } },
                contentPadding = PaddingValues(20.dp),
            ) {
                Text(
                    text = "目标车机",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "简化实现：手动输入蓝牙 MAC 与名称（与系统蓝牙设置一致）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                DialogTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    label = "蓝牙 MAC（AA:BB:CC:DD:EE:FF）",
                    keyboardType = KeyboardType.Ascii,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "车机名称（如 BMW X6 ID8.5）",
                    keyboardType = KeyboardType.Text,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleButton(
                        text = "清除",
                        onClick = onCleared,
                        modifier = Modifier.height(44.dp),
                        filled = false,
                        contentColor = AmberWarning,
                        borderColor = AmberWarning.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CapsuleButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.height(44.dp),
                        filled = false,
                        contentColor = TextSecondary,
                        borderColor = StrokeGlass,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    CapsuleButton(
                        text = "保存",
                        onClick = { onSaved(mac.trim(), name.trim()) },
                        modifier = Modifier.height(44.dp),
                        filled = true,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SurfaceInset,
            unfocusedContainerColor = SurfaceInset,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = ElectricCyan,
            focusedLabelColor = ElectricCyan,
            unfocusedLabelColor = TextSecondary,
            focusedIndicatorColor = ElectricCyan,
            unfocusedIndicatorColor = StrokeGlass,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/* ---------- 分辨率 / 帧率 ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionSection() {
    var widthText by rememberSaveable { mutableStateOf(SettingsStore.videoWidth.toString()) }
    var heightText by rememberSaveable { mutableStateOf(SettingsStore.videoHeight.toString()) }
    var fps by rememberSaveable { mutableStateOf(SettingsStore.videoFps) }
    var videoCodec by rememberSaveable { mutableStateOf(SettingsStore.videoCodec) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "分辨率与帧率")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "优先使用车机上报的分辨率，无上报时使用以下兜底值。默认 1920×1080，下次连接生效。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { s ->
                        widthText = s.filter { it.isDigit() }.take(5)
                        widthText.toIntOrNull()?.let { if (it >= 320) SettingsStore.videoWidth = it }
                    },
                    label = { Text("宽", style = MaterialTheme.typography.labelMedium) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { s ->
                        heightText = s.filter { it.isDigit() }.take(5)
                        heightText.toIntOrNull()?.let { if (it >= 240) SettingsStore.videoHeight = it }
                    },
                    label = { Text("高", style = MaterialTheme.typography.labelMedium) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        SettingsStore.videoWidth = SettingsStore.DEFAULT_VIDEO_WIDTH
                        SettingsStore.videoHeight = SettingsStore.DEFAULT_VIDEO_HEIGHT
                        widthText = SettingsStore.DEFAULT_VIDEO_WIDTH.toString()
                        heightText = SettingsStore.DEFAULT_VIDEO_HEIGHT.toString()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("恢复默认", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            FpsSegment(fps) { selected ->
                fps = selected
                SettingsStore.videoFps = selected
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("视频编码（下次连接生效）", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("h264" to "H.264", "hevc" to "HEVC 优先").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = videoCodec == value,
                        onClick = { videoCodec = value; SettingsStore.videoCodec = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        label = { Text(label) },
                    )
                }
            }
            Text("HEVC 需要车机和手机支持；CarWith 可硬件转码并铺满车机主屏，不可用时回退 H.264。",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FpsSegment(fps: Int, onFpsChanged: (Int) -> Unit) {
    Text(
        text = "帧率",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )
    Spacer(modifier = Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        FpsOptions.forEachIndexed { index, option ->
            SegmentedButton(
                selected = fps == option,
                onClick = { onFpsChanged(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = FpsOptions.size),
                label = {
                    Text(
                        text = "$option fps",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
    Text(
        text = "所选帧率为目标值。车机声明的帧率上限更低时，以车机上限为准。",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )
}

/* ---------- 开关组 ---------- */

@Composable
private fun AutoConnectSection(startup: io.github.hypercar.session.StartupReadiness) {
    val context = LocalContext.current
    var autoConnect by remember(io.github.hypercar.settings.SettingsRevision.value) { mutableStateOf(SettingsStore.autoConnect) }
    GlassPanel(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            SwitchRow(
                title = "上车自动连接",
                subtitle = if (startup.automaticReady) "启动条件已满足，可开启待机监听与自动重连"
                    else "请先完成：" + startup.automaticIssues.joinToString("；"),
                checked = autoConnect,
                enabled = startup.automaticReady || autoConnect,
                onCheckedChange = {
                    HyperCarRepo.setAutoConnect(context, it)
                    autoConnect = SettingsStore.autoConnect
                },
            )
    }
}

@Composable
private fun ScreenResourceSection() {
    var resourceLifecycle by rememberSaveable { mutableStateOf(SettingsStore.screenResourceLifecycle) }
    val profileLifecycle = remember(io.github.hypercar.settings.SettingsRevision.value) {
        io.github.hypercar.headunit.HeadUnitProfiles.current().useScreenResourceLifecycle
    }
    if (!profileLifecycle) {
        GlassPanel(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            SwitchRow(
                title = "画面资源联动（实验）",
                subtitle = "取得主屏后建立视频通道，车机收回主屏时释放视频并保留音频，下次连接生效",
                checked = resourceLifecycle,
                onCheckedChange = {
                    SettingsStore.screenResourceLifecycle = it
                    resourceLifecycle = SettingsStore.screenResourceLifecycle
                    HyperCarRepo.log("SCREEN-LIFECYCLE: 实验开关=${if (it) "开" else "关"}，下次连接生效")
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSection() {
    var codec by rememberSaveable { mutableStateOf(SettingsStore.audioCodec) }
    GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Text("音频格式", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("auto" to "自动", "pcm" to "PCM", "aac" to "AAC 优先").forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = codec == value,
                    onClick = { codec = value; SettingsStore.audioCodec = value },
                    shape = SegmentedButtonDefaults.itemShape(index, 3), label = { Text(label) })
            }
        }
        Text("自动保留已验证的 PCM；ID9 按能力选择。AAC 优先需车机声明支持，下次连接生效。",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
            modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    // 依赖项关闭时整行置灰、不可点（例如「待机通知」依赖「上车自动连接」）
    val rowAlpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary.copy(alpha = rowAlpha),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = rowAlpha),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ElectricCyan,
                checkedTrackColor = ElectricCyan.copy(alpha = 0.28f),
                checkedBorderColor = ElectricCyan,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceInset,
                uncheckedBorderColor = StrokeGlass,
            ),
        )
    }
}

/** 「3 分钟前」这类相对时间；用于远程车型列表的更新时间提示。 */
private fun agoText(atMillis: Long): String {
    val minutes = (System.currentTimeMillis() - atMillis) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}

/* ---------- 车机档案（品牌/型号） ---------- */

@Composable
private fun HeadUnitSection(connected: Boolean, onOpenPicker: () -> Unit) {
    val selection = remember(SettingsRevision.value) { io.github.hypercar.headunit.HeadUnitProfiles.selection() }
    val current = selection.profile ?: selection.lockedProfile
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "车机档案")
        GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !connected, onClick = onOpenPicker)
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CarBrandBadge(current?.id.orEmpty(), selection.label, size = 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selection.label, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    current?.model?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    val description = selection.blockReason ?: current?.notes.orEmpty()
                    if (description.isNotBlank()) {
                        Text(description, style = MaterialTheme.typography.bodySmall,
                            color = if (selection.blockReason == null) TextSecondary else AmberWarning)
                    }
                }
                Text("›", color = if (connected) TextSecondary else ElectricCyan,
                    style = MaterialTheme.typography.titleLarge)
            }
        }
        if (connected) {
            Text("断开连接后可切换车机档案", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun CarSourceSection(
    startup: io.github.hypercar.session.StartupReadiness,
    onRefreshSetup: () -> Unit,
    connected: Boolean = false,
    title: String = "2. 选择车机画面来源",
    compact: Boolean = false,
    yadea: Boolean = false,
) {
    val ctx = LocalContext.current
    val current = remember(SettingsRevision.value) { SettingsStore.carSource }
    val shizukuState = ShizukuTools.state(ctx)
    fun selectSource(source: String) {
        if (connected) return
        if (source == "car_ui" && (yadea || !ShizukuTools.isReady)) return
        if (source == "carwith" && !io.github.hypercar.session.StartupRequirements.carWithInstalled(ctx)) return
        SettingsStore.carSource = source
        io.github.hypercar.settings.SettingsRevision.bump()
        HyperCarRepo.log("STARTUP: 画面来源=$source，重新检查启动条件")
        onRefreshSetup()
    }
    if (compact || yadea) {
        val sources = buildList {
            if (!yadea) add("car_ui" to "自绘界面")
            add("carwith" to "CarWith")
            add("system_carlife" to "CarLife+")
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = title)
            GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    sources.forEachIndexed { index, (source, label) ->
                        SegmentedButton(
                            selected = current == source,
                            enabled = !connected && when(source) { "car_ui" -> startup.shizukuReady; "carwith" -> startup.carWithInstalled; else -> true },
                            onClick = { selectSource(source) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = sources.size),
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(when {
                    connected -> "断开连接后可切换画面来源。"
                    current == "system_carlife" -> "在手机车联中打开 CarLife+ 的无线连接并开始连接，保持页面等待，HyperCar 会自动接入。"
                    yadea && current == "carwith" -> "按仪表协商的参数投屏，连接后可在主页的预览里操作。"
                    yadea -> "选择 CarWith 或 CarLife+ 作为雅迪仪表的导航来源。"
                    current == "carwith" -> "CarWith 画面按车机屏幕自动换算并按需拉伸，源画面最高 2560×2560，无需手动设置。"
                    !startup.shizukuReady -> "自绘 UI 需先在「权限与后台」完成 Shizuku 授权。"
                    else -> "自绘 UI 使用下方显示设置。"
                }, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (current == "carwith" && startup.carWithInstalled) {
                    CarWithSettingsShortcut()
                }
                if (current == "system_carlife") {
                    SystemCarLifeSettingsShortcut(!connected)
                    if (!yadea) SystemCarLifeCallAudioSwitch(!connected)
                }
                if (!startup.carWithInstalled && current != "system_carlife") {
                    Text("未安装系统 CarWith，此选项不可用。", color = AmberWarning,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = title)
        GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            if (connected) {
                Text("请先断开当前会话，再切换画面来源", color = AmberWarning,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("自绘车机 UI", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(if (compact) "地图与音乐界面，需要 Shizuku 已启动并授权。"
                else "地图、音乐卡片与 Dock。必须先完成 Shizuku 安装、启动和授权。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (startup.shizukuReady) {
                CapsuleButton(if (current == "car_ui") "已选择自绘车机 UI" else "选择自绘车机 UI",
                    onClick = { selectSource("car_ui") }, enabled = !connected,
                    modifier = Modifier.fillMaxWidth(), filled = current == "car_ui")
            } else {
                Text(when (shizukuState) {
                    ShizukuState.NotInstalled -> "第 1 步：下载并安装 Shizuku；第 2 步：启动服务；第 3 步：授权 HyperCar。"
                    ShizukuState.NotRunning -> "已安装：请在 Shizuku 内通过无线调试或 root 启动服务，再返回申请授权。"
                    ShizukuState.NotGranted -> "服务已启动：请申请授权，在 Shizuku 弹窗中允许 HyperCar。"
                    ShizukuState.Ready -> "授权已完成，正在更新状态。"
                }, color = AmberWarning, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                CapsuleButton(when (shizukuState) {
                    ShizukuState.NotInstalled -> "前往 GitHub 下载 Shizuku 最新版"
                    ShizukuState.NotRunning -> "打开 Shizuku，启动服务"
                    ShizukuState.NotGranted -> "申请 Shizuku 授权"
                    ShizukuState.Ready -> "重新检查授权"
                }, onClick = {
                    when (ShizukuTools.state(ctx)) {
                        ShizukuState.NotInstalled -> ShizukuTools.openShizukuSite(ctx)
                        ShizukuState.NotRunning -> ShizukuTools.openShizukuApp(ctx)
                        ShizukuState.NotGranted -> ShizukuTools.requestPermissionIfNeeded {
                            HyperCarRepo.refreshSystemState()
                            onRefreshSetup()
                        }
                        ShizukuState.Ready -> onRefreshSetup()
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !connected)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            Text("CarWith 模式", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(if (startup.carWithInstalled) "已检测到系统 CarWith，无需 Shizuku。未授权 Shizuku 时，入网或蓝牙可发现可能需要按系统提示确认。"
                else "未检测到可用的系统 CarWith（com.miui.carlink），当前设备无法选择此模式。",
                color = if (startup.carWithInstalled) TextSecondary else AmberWarning,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            CapsuleButton(if (current == "carwith") "已选择 CarWith" else "选择 CarWith",
                onClick = { selectSource("carwith") }, enabled = !connected && startup.carWithInstalled,
                modifier = Modifier.fillMaxWidth(), filled = current == "carwith")
            if (startup.carWithInstalled) {
                CarWithSettingsShortcut()
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            Text("手机 CarLife+", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text("通过三星车联、荣耀车联等手机自带的 CarLife+ 投屏，投屏无需 Shizuku。请在手机车联中打开无线连接并开始连接，保持页面等待。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            CapsuleButton(if (current == "system_carlife") "已选择 CarLife+" else "选择 CarLife+",
                onClick = { selectSource("system_carlife") }, enabled = !connected,
                modifier = Modifier.fillMaxWidth(), filled = current == "system_carlife")
            SystemCarLifeSettingsShortcut(!connected)
            if (current == "system_carlife") SystemCarLifeCallAudioSwitch(!connected)
            if (!compact) {
                Spacer(Modifier.height(8.dp))
                Text("从安装或授权页面返回后会自动重新检查。", color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SystemCarLifeCallAudioSwitch(enabled: Boolean) {
    var checked by remember { mutableStateOf(SettingsStore.systemCarLifeCallAudio) }
    SwitchRow(
        title = "CarLife+ 车机通话（实验）",
        subtitle = "使用车机扬声器和麦克风进行蜂窝通话。需启动并授权 Shizuku，下次连接生效。",
        checked = checked,
        onCheckedChange = {
            SettingsStore.systemCarLifeCallAudio = it
            checked = it
        },
        enabled = enabled,
    )
}

@Composable
private fun SystemCarLifeSettingsShortcut(enabled: Boolean) {
    val context=LocalContext.current
    if (io.github.hypercar.session.StartupRequirements.systemCarLifePackage(context) == null) return
    CapsuleButton("打开手机车联", onClick = {
        if (!io.github.hypercar.session.StartupRequirements.openSystemCarLife(context))
            android.widget.Toast.makeText(context,"请手动打开手机车联中的 CarLife+ 无线连接页",android.widget.Toast.LENGTH_LONG).show()
    }, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CarWithSettingsShortcut() {
    val ctx = LocalContext.current
    TextButton(onClick = {
        val intent = android.content.Intent().setClassName(
            io.github.hypercar.session.StartupRequirements.CARWITH_PACKAGE,
            "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity",
        )
        try {
            ctx.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(ctx, "无法打开 CarWith 设置，请从系统设置中进入。",
                android.widget.Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(ctx, "系统限制打开 CarWith 设置，请从系统设置中进入。",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }) {
        Text("打开 CarWith 设置", color = ElectricCyan)
    }
}
