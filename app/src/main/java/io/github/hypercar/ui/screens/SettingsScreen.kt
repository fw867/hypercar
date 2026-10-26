package io.github.hypercar.ui.screens

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
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
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
import io.github.hypercar.net.BluetoothMac
import io.github.hypercar.perm.AppPermissions
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuState
import io.github.hypercar.shizuku.ShizukuTools
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

/**
 * 分辨率预设（**兜底用**）。实际分辨率以车机 `/info` 回报为准，手机回报实际
 * 编码尺寸后还会再覆盖一次；只有两者都拿不到时才落到这里。宽度均为偶数。
 */
private val FpsOptions = listOf(30, 60)

/**
 * 设置页：全部直读直写 [SettingsStore]，改动即时生效（apply() 异步落盘，读路径内存可见）。
 *
 * 排布原则（按"改动频率 × 影响面"自上而下）：
 *   1. **常用**：行为（自动连接/音频）——装好就要调的；
 *   2. **一次性配置**：目标车机、权限与系统设置；
 *   3. **高级**（分隔线以下）：屏幕推送分辨率/帧率（兜底值）、设备身份（蓝牙 MAC）、Shizuku。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, linkConnected: Boolean = false) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 常用 ----
        BehaviorSection()
        // ---- 一次性配置 ----
        HeadUnitSection(connected = linkConnected)
        TargetCarSection()
        PermissionSettingsSection()
        // ---- 高级 ----
        AdvancedMarker()
        ResolutionSection()
        IdentitySection()
        ShizukuSection()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 分隔线 + "高级"：把低频/兜底设置与常用设置分开，避免首屏被淹没。 */
@Composable
private fun AdvancedMarker() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = StrokeGlass.copy(alpha = 0.5f),
        )
        Text(
            text = "高级",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = StrokeGlass.copy(alpha = 0.5f),
        )
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
private fun ShizukuSection() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val state = remember(version) { ShizukuTools.state(context) }

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
        SectionLabel(text = "Shizuku（可选增强）")
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

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "不启用也能用：降级模式用系统 WiFi 授权框连热点（每个热点首次弹一次），" +
                    "且拿不到真实蓝牙 MAC。启用后连热点零弹窗、可读真实 MAC。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
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
    ShizukuState.Ready -> "可以静默加入车机热点（不弹系统确认框），也能用 shell 执行调试命令。"
    ShizukuState.NotGranted ->
        "点下方按钮会弹出 Shizuku 的授权框；若没弹，请先在 Shizuku 里确认服务已启动。"
    ShizukuState.NotRunning ->
        "需要先打开 Shizuku 并启动服务（无线调试配对，或 root 启动）。"
    ShizukuState.NotInstalled ->
        "Shizuku 是可选的：装上后能免除每个热点首次的系统确认框。" +
            "不装也能用；真实蓝牙 MAC 可手动填写，不依赖 Shizuku。"
}

/* ---------- 设备身份（蓝牙 MAC） ---------- */

/**
 * 本机蓝牙 MAC：免 Shizuku 的手动入口。
 *
 * Android 6.0+ 应用层拿不到本机蓝牙地址，读 `settings/secure` 又要 Shizuku/root。
 * 宝马 iD8.5 不校验这个值（稳定伪 ID 即可），但 WinPlay / 部分奥迪会按
 * bluetoothTransportIdentifier 严格过滤并 403——那种接收端下可以用 PC 跑一次
 * `adb shell settings get secure bluetooth_address`，把真值填在这里。
 */
@Composable
private fun IdentitySection() {
    var dialogVisible by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
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
                            "手动指定 · 所有协议身份都用它（与 Shizuku 无关，关掉也生效）"
                        } else {
                            "自动：有 Shizuku 用真实 MAC，否则用稳定伪 ID（$pseudo）"
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
                text = "车机用这个值标识本机（AirPlay 会话 SETUP 的 deviceID）。宝马/iD8.5 不校验，" +
                    "WinPlay 与部分奥迪会校验——连不上且日志提示 403 就填真值。" +
                    "填好后与 Shizuku 无关，关掉不影响；改完下次连接生效。",
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
                BluetoothMac.invalidate()
                HyperCarRepo.refreshSystemState()
                version++
                dialogVisible = false
            },
            onSaved = { value ->
                SettingsStore.manualBtMac = value
                BluetoothMac.invalidate()
                HyperCarRepo.refreshSystemState()
                version++
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
    val valid = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(trimmed)

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
                .background(Color(0xB302040A))
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
                    text = "用 PC 执行一次即可获取：\n" +
                        "adb shell settings get secure bluetooth_address\n" +
                        "留空并清除则恢复自动（Shizuku / 稳定伪 ID）。",
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
    // 读一次变更计数器：切换车机型号会 bump，从而让本区块重新组合并显示"自动发现"
    io.github.hypercar.settings.SettingsRevision.value
    val targetName = SettingsStore.targetCarName ?: "自动发现"
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dialogVisible by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 已配对设备（下拉候选）。BLUETOOTH_CONNECT 未授予时给空表，
    // 只保留「自动发现」与「手动输入 MAC…」两项，不至于让下拉空着。
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
                            color = if (targetName == "自动发现") TextSecondary else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "仅列出已配对设备 · 未配置时来者不拒",
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
                    modifier = Modifier.width(320.dp),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = SurfaceInset,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StrokeGlass),
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "自动发现",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (targetName == "自动发现") ElectricCyan else TextPrimary,
                                )
                                Text(
                                    text = "不限制车机，谁来连都接受",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                )
                            }
                        },
                        onClick = {
                            SettingsStore.targetCarMac = null
                            SettingsStore.targetCarName = null
                            expanded = false
                        },
                    )
                    if (bonded.isNotEmpty()) {
                        HorizontalDivider(color = StrokeGlass)
                        bonded.forEach { (name, mac) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (targetName == name) ElectricCyan else TextPrimary,
                                        )
                                        Text(
                                            text = mac,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                        )
                                    }
                                },
                                onClick = {
                                    SettingsStore.targetCarMac = mac
                                    SettingsStore.targetCarName = name
                                    expanded = false
                                },
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
                SettingsStore.targetCarMac = null
                SettingsStore.targetCarName = null
                dialogVisible = false
            },
            onSaved = { mac, name ->
                SettingsStore.targetCarMac = mac.ifBlank { null }
                SettingsStore.targetCarName = name.ifBlank { null }
                dialogVisible = false
            },
        )
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
                .background(Color(0xB302040A))
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
    // 分辨率改为自由输入（原三个预设已删除）：屏幕尺寸随机型而异，
    // 写死选项反而逼用户改代码。输入即生效，偶数对齐在配置下发前完成。
    var widthText by rememberSaveable { mutableStateOf(SettingsStore.videoWidth.toString()) }
    var heightText by rememberSaveable { mutableStateOf(SettingsStore.videoHeight.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "屏幕推送分辨率（兜底值）")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "实际分辨率以车机 /info 回报为准——连上后会自动改成车机原生尺寸，" +
                    "手机在编码器初始化完成后回报的实际值会再次覆盖它。" +
                    "这里只在这两者都拿不到时使用；下发前按偶数对齐。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                        SettingsStore.videoWidth = 2560
                        SettingsStore.videoHeight = 744
                        widthText = "2560"
                        heightText = "744"
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("恢复默认", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Spacer(modifier = Modifier.height(16.dp))
            FpsSegment()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FpsSegment() {
    var fps by rememberSaveable { mutableStateOf(SettingsStore.videoFps.coerceIn(30, 60)) }
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
                onClick = {
                    fps = option
                    SettingsStore.videoFps = option
                },
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
}

/* ---------- 开关组 ---------- */

@Composable
private fun BehaviorSection() {
    var autoConnect by rememberSaveable { mutableStateOf(SettingsStore.autoConnect) }
    var enableAudio by rememberSaveable { mutableStateOf(SettingsStore.enableAudio) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "行为")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        ) {
            SwitchRow(
                title = "上车自动连接",
                subtitle = "车机蓝牙 ACL 连上手机即唤醒协商",
                checked = autoConnect,
                onCheckedChange = {
                    autoConnect = it
                    SettingsStore.autoConnect = it
                },
            )
            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
            SwitchRow(
                title = "音频转发",
                subtitle = "本机音轨直通 AirPlay 通道",
                checked = enableAudio,
                onCheckedChange = {
                    enableAudio = it
                    SettingsStore.enableAudio = it
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

/**
 * 车机档案选择：把品牌差异收敛到 `headunit/HeadUnitProfile.kt` 的档案里。
 *
 * 用户只需选「哪台车机」，其余行为（是否校验 deviceID、端口策略、
 * 0x4300/0xEA00/0x4E0E/0xAE01 的支持情况、触摸像素空间）由档案给出，
 * 新增品牌不需要改这里，只在 [io.github.hypercar.headunit.HeadUnitProfiles.all] 注册。
 */
@Composable
private fun HeadUnitSection(connected: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedId by rememberSaveable { mutableStateOf(SettingsStore.headUnitProfileId) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 远程档案（GitHub 下发）：新增车型不用发版，点一下就能拉到
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }
    var remoteBump by remember { mutableStateOf(0) }
    // 读一下 remoteBump：远程档案更新后强制重组，重新取 all（否则列表还是旧的）
    remoteBump
    val profiles = io.github.hypercar.headunit.HeadUnitProfiles.all
    val current = io.github.hypercar.headunit.HeadUnitProfiles.byId(selectedId)

    // 选中即生效的副作用统一走这里（含「联调/只保 iAP2」的联动，见 profile.keepIap2AliveOnly）
    fun applyProfile(profile: io.github.hypercar.headunit.HeadUnitProfile) {
        SettingsStore.headUnitProfileId = profile.id
        // 原来设置里有个独立的「联调：只保持 iAP2 链路」开关，现在由档案联动：
        // 选 WinPlay 自动打开（AirPlay 由 PC 侧 hub 推），选真车自动关闭。
        SettingsStore.iap2KeepAliveOnly = profile.keepIap2AliveOnly
        // 换车机型号时把「目标车机」重置为自动发现：上一台的名字/MAC 留着，
        // 新车型会因「目标对不上」直接失败，而用户常常忘了改（现场反馈）。
        SettingsStore.targetCarMac = null
        SettingsStore.targetCarName = null
        io.github.hypercar.settings.SettingsRevision.bump()
        selectedId = profile.id
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "车机型号")
        GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !connected) { expanded = true }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${current.label} · ${current.model}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (connected) TextSecondary else ElectricCyan,
                            )
                            if (!current.verified) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "未实测",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberWarning,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AmberWarning.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            text = current.notes,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "▾",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (connected) TextSecondary.copy(alpha = 0.4f) else ElectricCyan,
                    )
                }
                DropdownMenu(
                    expanded = expanded && !connected,
                    onDismissRequest = { expanded = false },
                    // 列表加外框 + 实底：默认菜单与背景几乎同色，条目之间分不清边界（现场反馈）
                    modifier = Modifier.width(320.dp),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = SurfaceInset,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StrokeGlass),
                ) {
                    profiles.forEachIndexed { index, profile ->
                        // 车型之间加分隔线：原来几条连成一片，分不清边界
                        if (index > 0) HorizontalDivider(color = StrokeGlass)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = buildString {
                                            append(profile.label)
                                            append(" · ")
                                            append(profile.model)
                                            if (io.github.hypercar.headunit.RemoteProfiles.isRemote(profile.id)) {
                                                append("（远程）")
                                            }
                                            if (!profile.verified) append("（未实测）")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (profile.id == selectedId) ElectricCyan else TextPrimary,
                                    )
                                    Text(
                                        text = profile.notes,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (profile.keepIap2AliveOnly) {
                                        // 原来这句放在列表下方的页脚，容易看漏；
                                        // 挪进 WinPlay 这一条里，选中前就能看到后果
                                        Text(
                                            text = "选它会进入「只保 iAP2 链路」模式：AirPlay 由 PC 侧 hub 推送",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AmberWarning,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                applyProfile(profile)
                                expanded = false
                            },
                        )
                    }
                    // ---- 远程档案：GitHub 下发（新增车型不用发版）----
                    HorizontalDivider(color = StrokeGlass)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = if (refreshing) "正在更新车型列表…" else "从 GitHub 更新车型列表",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (refreshing) TextSecondary else ElectricCyan,
                                )
                                Text(
                                    text = refreshNote ?: run {
                                        val at = SettingsStore.remoteProfilesFetchedAt
                                        val count = io.github.hypercar.headunit.RemoteProfiles.cached().size
                                        if (at <= 0L) {
                                            "尚未更新过（仅内置 ${io.github.hypercar.headunit.HeadUnitProfiles.builtin.size} 个）"
                                        } else {
                                            "已缓存 $count 个远程档案 · ${agoText(at)}"
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (refreshNote?.startsWith("失败") == true) {
                                        AmberWarning
                                    } else {
                                        TextSecondary
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        onClick = {
                            if (!refreshing) {
                                refreshing = true
                                refreshNote = "正在下载…"
                                scope.launch {
                                    val result = runCatching {
                                        io.github.hypercar.headunit.RemoteProfiles.refresh(context)
                                    }
                                    refreshing = false
                                    refreshNote = result.fold(
                                        onSuccess = { n -> "成功：$n 个档案（新车型已在上方列表）" },
                                        onFailure = { e -> "失败：${e.message ?: "网络不可用"}（继续用缓存）" },
                                    )
                                    if (result.isSuccess) {
                                        remoteBump++
                                        io.github.hypercar.settings.SettingsRevision.bump()
                                        expanded = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (connected) {
                    "连接中不可切换：先点「断开连接」再改（iAP2 握手已按当前档案走完）。"
                } else {
                    "切换后「下次连接」生效。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning.copy(alpha = 0.85f),
            )
        }
    }
}







