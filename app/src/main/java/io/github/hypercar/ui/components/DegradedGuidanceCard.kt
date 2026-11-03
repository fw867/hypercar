package io.github.hypercar.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.WifiProtectedSetup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 无 Shizuku 降级模式的运行时引导卡（仅在 Shizuku 未启用时显示）。
 *
 * 三种待办态按优先级呈现，每态给一个可操作动作：
 *  1. 缺运行时权限 → 「去授权」（12/12L=定位，13+=附近设备）。缺它 WifiNetworkSpecifier
 *     的 requestNetwork 直接 onUnavailable，连接必失败——必须前置引导。
 *  2. 权限齐但系统定位服务关闭（仅 12/12L）→ 「去开启定位」（深链系统设置）。
 *  3. 权限/定位就绪但未完成前台首连 → 「测试连接」（在前台连一次，批准系统确认框；
 *     后台 FGS 拉起的首连不保证弹框）。
 *
 * 全部就绪（首连已完成）后本卡隐藏——首页保持干净；「Shizuku 可选未启用」在统计芯片里已提示。
 */
@Composable
fun DegradedGuidanceCard(
    permissionGranted: Boolean,
    locationOn: Boolean,
    firstConnectDone: Boolean,
    onRequestPermission: () -> Unit,

    modifier: Modifier = Modifier,
) {
    // 三种待办态取最紧迫的一种；无待办则不渲染
    val state = when {
        !permissionGranted -> GuidanceState.NeedPermission
        !locationOn -> GuidanceState.NeedLocation
        !firstConnectDone -> GuidanceState.NeedFirstConnect
        else -> return // 全部就绪：隐藏
    }

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        glow = state == GuidanceState.NeedPermission,
        glowColor = AmberWarning,
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = state.icon,
                contentDescription = null,
                tint = state.tint,
                modifier = Modifier.size(30.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未启用 Shizuku · 降级模式",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        state.ActionButton(
            onRequestPermission = onRequestPermission,
    
        )
    }
}

private enum class GuidanceState {
    /** 缺运行时权限：12/12L=定位，13+=附近设备。 */
    NeedPermission,

    /** 权限齐但系统定位服务关闭（仅 12/12L）。 */
    NeedLocation,

    /** 就绪但未完成前台首连（系统确认框未批准）。 */
    NeedFirstConnect;

    val icon
        get() = when (this) {
            NeedPermission -> Icons.Rounded.GpsOff
            NeedLocation -> Icons.Rounded.Place
            NeedFirstConnect -> Icons.Rounded.WifiProtectedSetup
        }

    val tint
        @Composable get() = when (this) {
            NeedPermission -> AmberWarning
            NeedLocation -> AmberWarning
            NeedFirstConnect -> ElectricCyan
        }

    val hint
        get() = when (this) {
            NeedPermission ->
                "连车机热点需「附近设备 / 定位」权限（系统要求，缺失会静默失败）。"
            NeedLocation ->
                "还需在系统里打开「定位服务」——只授权不开定位，Android 12 仍无法连热点。"
            NeedFirstConnect ->
                "权限已就绪。热点首连会弹一次系统确认框，请保持本页在前台完成，之后自动连将静默。"
        }

    @Composable
    fun ActionButton(onRequestPermission: () -> Unit) {
        val context = LocalContext.current
        when (this) {
            NeedPermission -> CapsuleButton(
                text = "去授权",
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                filled = true,
                leadingIcon = Icons.Rounded.GpsOff,
            )

            NeedLocation -> CapsuleButton(
                text = "去开启定位",
                onClick = {
                    // 深链系统定位开关页（无法程序化静默开启）
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                filled = true,
                leadingIcon = Icons.Rounded.Place,
            )
            NeedFirstConnect -> Unit   // 首连提示只留文字，不再放「测试连接」按钮（首页按钮已移除）

        }
    }
}
