package io.github.hypercar.ui.screens

import androidx.compose.foundation.clickable

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.perm.PermGroup
import io.github.hypercar.perm.PermissionSummary
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.PermissionChecklistCard
import io.github.hypercar.ui.components.StatGrid
import io.github.hypercar.ui.components.StatusHeroCard
import io.github.hypercar.ui.components.ConnectionStageCard
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 首页：英雄大卡 + 统计芯片 + 最近日志预览（最近 5 行，等宽小字，自动滚到底部）。
 * 横屏（BoxWithConstraints）切换为双栏：左英雄卡 + 芯片，右日志。
 */
@Composable
fun HomeScreen(
    startup: io.github.hypercar.session.StartupReadiness,
    onRefreshSetup: () -> Unit,
    snapshot: SessionSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onRequestDegradedPermission: () -> Unit,
    permissions: PermissionSummary,
    locationServiceOn: Boolean,
    onRequestPermission: (PermGroup) -> Unit,
    onRequestAllPermissions: () -> Unit,
    onOpenOverlay: () -> Unit,
    onBatteryOptimization: () -> Unit,
    onAutostart: () -> Unit,
    onOpenShizukuSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoConnect = remember(io.github.hypercar.settings.SettingsRevision.value) {
        io.github.hypercar.settings.SettingsStore.autoConnect
    }
    // 权限检查卡在两种布局里共用
    @Composable
    fun Checklist() {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        PermissionChecklistCard(
            summary = permissions,
            locationServiceOn = locationServiceOn,
            onRequest = onRequestPermission,
            onRequestAll = onRequestAllPermissions,
            onOpenOverlay = onOpenOverlay,
            onBattery = onBatteryOptimization,
            onAutostart = onAutostart,
            onNotificationAccess = {
                io.github.hypercar.carui.MusicBridge.openAccessSettings(ctx)
            },
        )
    }
    @Composable
    fun Setup() {
        if (!startup.manualReady) {
            Text("首次使用 · 完成以下设置后才能连接", color = TextPrimary,
                style = MaterialTheme.typography.titleMedium)
            Checklist()
            CarSourceSection(startup = startup, onRefreshSetup = onRefreshSetup,
                connected = snapshot.link is io.github.hypercar.core.LinkState.Connected ||
                    snapshot.link is io.github.hypercar.core.LinkState.Negotiating)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (!startup.bluetoothEnabled || !startup.wifiEnabled) {
                io.github.hypercar.ui.components.CapsuleButton("开启蓝牙 / Wi-Fi", onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                }, modifier = Modifier.fillMaxWidth(), filled = false)
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Setup()
                    StatusHeroCard(
                        startEnabled = startup.manualReady,
                        startBlockReason = startup.manualIssues.joinToString("；"),
                        link = snapshot.link,
                        connectionAttemptId = snapshot.connectionAttemptId,
                        autoConnectEnabled = autoConnect,
                        onStartClick = onStart,
                        onStopClick = onStop,
                        onToggleAutoConnect = onToggleAutoConnect,
                        autoConnectToggleEnabled = startup.automaticReady || autoConnect,
                        profileLabel = io.github.hypercar.headunit.HeadUnitProfiles.current().label,
                    )
                    StatGrid(
                        snapshot = snapshot,
                        onOpenShizuku = onOpenShizukuSettings,
                        degradedHint = degradedChipHint(snapshot),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (startup.manualReady) Checklist()
                    if (snapshot.link is io.github.hypercar.core.LinkState.Connected) {
                        ConnectionStageCard(snapshot.pipeline.takeIf { it.active }
                            ?: io.github.hypercar.core.PipelineDiagnostics())
                    } else FirstConnectGuideCard()

                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Setup()
                // 第一张卡：实时步骤（Negotiating 时显示 link.step）
                StatusHeroCard(
                    startEnabled = startup.manualReady,
                    startBlockReason = startup.manualIssues.joinToString("；"),
                    link = snapshot.link,
                    connectionAttemptId = snapshot.connectionAttemptId,
                    autoConnectEnabled = autoConnect,
                    onStartClick = onStart,
                    onStopClick = onStop,
                    onToggleAutoConnect = onToggleAutoConnect,
                    autoConnectToggleEnabled = startup.automaticReady || autoConnect,
                        profileLabel = io.github.hypercar.headunit.HeadUnitProfiles.current().label,
                )
                // 四枚状态芯片改成 2×2：原来单行横滑，Shizuku 在窄屏上被挤出屏幕
                StatGrid(
                    snapshot = snapshot,
                    onOpenShizuku = onOpenShizukuSettings,
                    degradedHint = degradedChipHint(snapshot),
                )
                if (startup.manualReady) Checklist()
                if (snapshot.link is io.github.hypercar.core.LinkState.Connected) {
                    ConnectionStageCard(snapshot.pipeline.takeIf { it.active }
                        ?: io.github.hypercar.core.PipelineDiagnostics())
                } else FirstConnectGuideCard()

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Shizuku 芯片上的降级模式提示（原首页下方那张大卡的内容）。
 *
 * 只留一句最要紧的：还差什么动作。null = 不需要提示（Shizuku 已启用）。
 */
private fun degradedChipHint(snapshot: SessionSnapshot): String? = when {
    snapshot.shizukuReady -> null
    io.github.hypercar.settings.SettingsStore.carWithMode -> "CarWith 无需 Shizuku"
    else -> "自绘 UI 必须安装、启动并授权"
}


/**
 * 「首次连接」引导：车机首次配对必须手动点一次，之后同一台车会自动回连。
 *
 * 车机侧的 CarPlay 授权只在系统界面里，应用无法代劳，所以只能引导用户点一次。
 */
@Composable
internal fun FirstConnectGuideCard(modifier: Modifier = Modifier) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
        // 车机档案：品牌/型号决定这套协议按谁的习惯跑，引导里必须点名，
        // 否则用户不知道自己选的对不对（设置里可改）。
        val headUnit = io.github.hypercar.headunit.HeadUnitProfiles.current()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "3. 首次车机配对",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "车机：${headUnit.label}",
                style = MaterialTheme.typography.labelSmall,
                color = io.github.hypercar.ui.theme.ElectricCyan,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "① 完成必需权限和画面来源设置，让「启动连接」可用\n" +
                "② 手机启动连接，在车机 Apple CarPlay 列表选择本机并确认配对\n" +
                "③ 等待显示已连接，再确认车机画面、触摸和声音正常",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        if (!headUnit.pairingHint.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "这台车机：" + headUnit.pairingHint,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "需要自动回连时，完成悬浮窗授权，再到设置开启「上车自动连接」。",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        Text(
            text = "换车机 / 新增品牌？看「车机档案」说明 →",
            style = MaterialTheme.typography.labelSmall,
            color = io.github.hypercar.ui.theme.ElectricCyan,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://github.com/fw867/hypercar/blob/main/docs/HEADUNIT-PROFILES.md",
                            ),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}





