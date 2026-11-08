package io.github.hypercar.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hypercar.navigation.AmapLinkProbe
import io.github.hypercar.session.HyperCarRepo

/**
 * 受 `android.permission.DUMP` 保护的调试入口：`adb shell am broadcast -a io.github.hypercar.DEBUG_AMAP_LINK`
 * 只用于在没有车机会话时单独验证「shell 代绑高德 LinkSDK」这条通道（HUD 正式路径仍受会话开关约束）。
 */
class AmapLinkDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val start = intent.getBooleanExtra("start", true)
        HyperCarRepo.log("HUD-DEBUG: 收到调试广播 start=$start")
        // 顺带验证 MIUI「关联启动」白名单：用本 App 的 uid 直接拉起高德（不走 shell），
        // 如果 MIUI 弹"允许 HyperCar 启动高德地图"就说明这条路能补齐白名单。
        if (intent.getBooleanExtra("startAmap", false)) {
            val launch = android.content.Intent("com.ucar.intent.action.UCAR").apply {
                setComponent(
                    android.content.ComponentName(
                        "com.autonavi.minimap",
                        "com.autonavi.map.activity.UcarSplashActivity",
                    ),
                )
                addCategory("com.ucar.intent.category.UCAR")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val r = runCatching { context.startActivity(launch); "ok" }
                .getOrElse { it.javaClass.simpleName + ": " + it.message }
            HyperCarRepo.log("HUD-DEBUG: selfStartAmap=$r")
        }
        if (start) AmapLinkProbe.startForDebug(context) else AmapLinkProbe.stopForDebug()
    }

    companion object {
        const val ACTION = "io.github.hypercar.DEBUG_AMAP_LINK"
    }
}
