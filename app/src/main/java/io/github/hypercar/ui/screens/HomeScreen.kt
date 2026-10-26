package io.github.hypercar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.perm.PermGroup
import io.github.hypercar.perm.PermissionSummary
import io.github.hypercar.ui.components.DegradedGuidanceCard
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.PermissionChecklistCard
import io.github.hypercar.ui.components.StatGrid
import io.github.hypercar.ui.components.StatusHeroCard
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.MonoLogLine
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 首页：英雄大卡 + 统计芯片 + 最近日志预览（最近 5 行，等宽小字，自动滚到底部）。
 * 横屏（BoxWithConstraints）切换为双栏：左英雄卡 + 芯片，右日志。
 */
@Composable
fun HomeScreen(
    snapshot: SessionSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenDiagnostics: () -> Unit,
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
    // 权限检查卡在两种布局里共用
    @Composable
    fun Checklist() {
        PermissionChecklistCard(
            summary = permissions,
            locationServiceOn = locationServiceOn,
            onRequest = onRequestPermission,
            onRequestAll = onRequestAllPermissions,
            onOpenOverlay = onOpenOverlay,
            onBattery = onBatteryOptimization,
            onAutostart = onAutostart,
        )
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
                    StatusHeroCard(
                        link = snapshot.link,
                        onStartClick = onStart,
                        onStopClick = onStop,
                        onOpenLogs = onOpenDiagnostics,
                        profileLabel = io.github.hypercar.headunit.HeadUnitProfiles.current().label,
                    )
                    StatGrid(
                        snapshot = snapshot,
                        externalMode = io.github.hypercar.settings.SettingsStore.iap2KeepAliveOnly,
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
                    Checklist()
                    FirstConnectGuideCard()

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
                // 第一张卡：实时步骤（Negotiating 时显示 link.step）
                StatusHeroCard(
                    link = snapshot.link,
                    onStartClick = onStart,
                    onStopClick = onStop,
                    onOpenLogs = onOpenDiagnostics,
                        profileLabel = io.github.hypercar.headunit.HeadUnitProfiles.current().label,
                )
                // 四枚状态芯片改成 2×2：原来单行横滑，Shizuku 在窄屏上被挤出屏幕
                StatGrid(
                    snapshot = snapshot,
                    externalMode = io.github.hypercar.settings.SettingsStore.iap2KeepAliveOnly,
                    onOpenShizuku = onOpenShizukuSettings,
                    degradedHint = degradedChipHint(snapshot),
                )
                Checklist()
                FirstConnectGuideCard()

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 「查看全部日志」入口：首页原来的小日志卡占满底部且无法上滑（现场反馈），
 * 改成一行可点文字，日志本身去「诊断」页看。
 */
/**
 * Shizuku 芯片上的降级模式提示（原首页下方那张大卡的内容）。
 *
 * 只留一句最要紧的：还差什么动作。null = 不需要提示（Shizuku 已启用）。
 */
private fun degradedChipHint(snapshot: SessionSnapshot): String? = when {
    snapshot.shizukuReady -> null
    !snapshot.degradedPermissionGranted -> "降级模式 · 点此授权"
    !snapshot.degradedLocationOn -> "降级模式 · 点此开定位"
    !snapshot.degradedFirstConnectDone -> "降级模式 · 点此首连"
    else -> "降级模式 · 点此设置"
}


@Composable
private fun DiagnosticsLink(onOpenDiagnostics: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenDiagnostics)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "查看全部日志 / 诊断",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "→",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
    }
}

/**
 * 最近日志预览：最近 5 行等宽小字，reverseLayout 让最新行贴底并自动跟随。
 */
@Composable
private fun LogPreviewCard(
    logs: List<String>,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent = remember(logs) { logs.takeLast(5) }
    val listState = rememberLazyListState()
    LaunchedEffect(recent.size) {
        if (recent.isNotEmpty()) listState.scrollToItem(0)
    }
    GlassPanel(
        modifier = modifier.clickable(onClick = onOpenDiagnostics),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 12.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近日志",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "全部 →",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (recent.isEmpty()) {
            Text(
                text = "暂无日志，连接一次车机后这里会显示链路轨迹",
                style = MonoLogLine,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(recent.reversed()) { line ->
                    LogLineRow(line = line)
                }
            }
        }
    }
}

/** 单行日志：等宽 11sp，按级别（E/W/I/D）着色。解析逻辑见 [parseLogLine]。 */
@Composable
internal fun LogLineRow(line: String, modifier: Modifier = Modifier) {
    val parsed = remember(line) { parseLogLine(line) }
    val color = logLevelColor(parsed.level)
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = levelTag(parsed.level),
            style = MonoLogLine,
            color = color,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = parsed.text,
            style = MonoLogLine,
            color = if (parsed.level == 'E') color else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
                text = "首次连接（只需做一次）",
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
            text = "① 车机侧开始接收（PC/WinPlay 联调时先在 PC 上起 hub）\n" +
                "② 手机点「启动连接」，等状态到「已连接」\n" +
                "③ 车机侧打开 Apple CarPlay（首次会在车机屏幕上确认）\n" +
                "④ 点列表里的车机蓝牙名称 → 下一步 → 开始连接",
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
        // 适配新车型的说明在仓库文档里：给一个可点的入口
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
        Text(
            text = "做完这一次，同一台车以后会自动回连。第 ④ 步「开始连接」" +
                "只有在 CarWith 检测到车机蓝牙时才可用；列表为空就回第 ① 步确认车机在接收。",
            style = MaterialTheme.typography.labelSmall,
            color = MintGreen,
        )
    }
}





