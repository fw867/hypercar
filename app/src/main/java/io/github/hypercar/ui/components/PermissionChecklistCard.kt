package io.github.hypercar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.perm.AppPermissions
import io.github.hypercar.perm.PermGroup
import io.github.hypercar.perm.PermissionSummary
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 首启权限检查清单。
 *
 * 背景：HyperOS / MIUI 对**侧载安装**的应用默认拒绝通知、定位等权限，且不会主动
 * 提示；此前应用只申请「附近设备」，导致蓝牙（iAP2 / 上车唤醒）一直处于未授权状态，
 * 用户看不到任何异常。
 *
 * **两类行的关键区别**（踩过的坑）：
 *  - **状态行**：权限能用系统 API 验证，所以显示 ✓ / 待授权——附近设备、定位、通知、悬浮窗。
 *  - **操作行**：系统**没有公开 API** 能查（自启动），或 OEM 改了状态也不写回 AOSP 标志
 *    （HyperOS 的「省电策略 → 无限制」不一定反映到 `isIgnoringBatteryOptimizations`），
 *    这类只给"去设置"入口、**不画状态点**——否则用户设置完回来还是灰点，会以为是失败。
 */
@Composable
fun PermissionChecklistCard(
    summary: PermissionSummary,
    locationServiceOn: Boolean,
    onRequest: (PermGroup) -> Unit,
    onRequestAll: () -> Unit,
    onOpenOverlay: () -> Unit,
    onBattery: () -> Unit,
    onAutostart: () -> Unit,
    /** 跳系统「通知使用权」页（音乐卡片必需；不在运行时权限体系里）。 */
    onNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requiredMissing = listOf(
        !summary.nearby,
        summary.locationRequired && !summary.location,
        summary.notificationRequired && !summary.notification,
        // 通知使用权：音乐卡片的数据源，属于"必须"
        !summary.notificationAccess,
    ).count { it }

    // 卡片是否显示：必需项缺失，或还有可优化的项
    val overlayMissing = !summary.overlay
    val batteryMissing = !summary.batteryWhitelisted
    if (requiredMissing == 0 && !overlayMissing && !batteryMissing) return

    // 可选项（自启动/电池）在 HyperOS 上无法回读，用户可主动收起，避免首页常年挂卡
    val context = LocalContext.current
    var optionalDismissed by remember { mutableStateOf(summary.optionalDismissed) }
    if (requiredMissing == 0 && optionalDismissed) return

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        glow = requiredMissing > 0,
        glowColor = if (requiredMissing > 0) AmberWarning else ElectricCyan,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = if (requiredMissing > 0) AmberWarning else ElectricCyan,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = "权限检查",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (requiredMissing > 0) "缺 $requiredMissing 项必需" else "可选优化",
                style = MaterialTheme.typography.labelSmall,
                color = if (requiredMissing > 0) AmberWarning else TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "系统对侧载应用默认拒绝通知/定位等权限，需手动授予；否则对应功能会静默失效。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ---- 必需项（可验证）----
        StatusRow(
            title = "附近设备（蓝牙 + Wi-Fi）",
            detail = "连接车机热点、iAP2 配对、上车自动唤醒",
            granted = summary.nearby,
            required = true,
            onClick = { onRequest(PermGroup.Nearby) },
        )
        if (summary.locationRequired) {
            StatusRow(
                title = "定位",
                detail = if (summary.location && !locationServiceOn) {
                    "已授权，但系统定位服务未开启——Android 12 仍连不上热点"
                } else {
                    "Android 12/12L 连车机热点需要"
                },
                granted = summary.location && locationServiceOn,
                required = true,
                onClick = { onRequest(PermGroup.Location) },
            )
        }
        if (summary.notificationRequired) {
            StatusRow(
                title = "通知",
                detail = "前台服务常驻通知；被隐藏时部分系统会回收服务",
                granted = summary.notification,
                required = true,
                onClick = { onRequest(PermGroup.Notification) },
            )
        }
        // 通知使用权（必做）：音乐卡片靠它读系统播放信息。只能深链系统页，无法弹窗申请。
        StatusRow(
            title = "通知使用权（音乐卡片）",
            detail = "车机音乐卡片读取系统播放信息必需；只读媒体会话，不读取通知内容",
            granted = summary.notificationAccess,
            required = true,
            onClick = onNotificationAccess,
        )

        // ---- 可选优化 ----
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(6.dp))

        // 悬浮窗可用 Settings.canDrawOverlays 验证 → 状态行
        StatusRow(
            title = "后台启动（显示在其他应用上层）",
            detail = "Android 12+ 后台拉起前台服务的一条豁免，上车唤醒依赖它",
            granted = summary.overlay,
            required = false,
            onClick = onOpenOverlay,
        )

        // 电池：能查到就是 ✓；查不到（HyperOS 常见）就退化成操作行，不画灰点
        if (summary.batteryWhitelisted) {
            StatusRow(
                title = "电池优化白名单",
                detail = "已加入白名单，熄屏后不会被低电耗限流",
                granted = true,
                required = false,
                onClick = onBattery,
            )
        } else {
            ActionRow(
                title = "电池优化白名单",
                detail = "避免熄屏后被视为低电耗限流；HyperOS 在「应用信息 → 省电策略」里设为无限制",
                onClick = onBattery,
            )
        }

        // 自启动：能通过 MIUI AppOps（android:auto_start）读到就显示 ✓，
        // 读不到（多数版本）退化成操作行，不画状态点
        when (summary.autostart) {
            true -> StatusRow(
                title = "自启动",
                detail = "已允许；杀进程后仍能收到车机蓝牙广播",
                granted = true,
                required = false,
                onClick = onAutostart,
            )
            else -> ActionRow(
                title = "自启动（HyperOS / MIUI）",
                detail = if (summary.autostart == false) {
                    "当前被系统禁止；未开启时杀进程后收不到蓝牙广播"
                } else {
                    "系统专有开关；不开启则杀进程后收不到蓝牙广播（应用无法回读状态）"
                },
                onClick = onAutostart,
            )
        }

        if (requiredMissing == 0) {
            // 必需项已齐备：给用户一个收起入口（这两项的状态我们永远读不到）
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "不再提示这些可选项",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable {
                        AppPermissions.dismissOptional(context)
                        optionalDismissed = true
                    }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }

        if (requiredMissing > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            CapsuleButton(
                text = "一键授予必需权限",
                onClick = onRequestAll,
                modifier = Modifier.fillMaxWidth(),
                filled = true,
            )
        }
    }
}

/**
 * 状态行：可被系统 API 验证的权限。
 * @param granted 已授权显示绿勾；未授权显示待办圆点
 */
@Composable
private fun StatusRow(
    title: String,
    detail: String,
    granted: Boolean,
    required: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (granted) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (granted) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MintGreen,
                modifier = Modifier.size(15.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background((if (required) AmberWarning else TextSecondary).copy(alpha = 0.35f)),
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) TextSecondary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!granted) {
            Text(
                text = "去授予",
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan,
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 1,
            )
        }
    }
}

/**
 * 操作行：系统无法回读状态的权限（自启动 / HyperOS 省电策略）。
 *
 * **刻意不画状态点**——用户设置完回来仍是"未授权"视觉会像失败；
 * 这里只表达"这里有一个需要你去系统里确认的开关"。
 */
@Composable
private fun ActionRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(CircleShape)
                .background(ElectricCyan.copy(alpha = 0.18f)),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "去设置",
            style = MaterialTheme.typography.labelMedium,
            color = ElectricCyan,
            modifier = Modifier.padding(start = 10.dp),
            maxLines = 1,
        )
    }
}
