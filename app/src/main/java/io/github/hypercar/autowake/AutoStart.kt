package io.github.hypercar.autowake

import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * 会话层注入的「车机蓝牙事件 → 会话」回调（由 Application 初始化时接线）。
 *
 * **契约（会话层实现本回调时必须遵守，避免 receiver↔service 循环）**：
 *  - connected=true：若 [HyperCarService.isRunning] 为 false，调用
 *    `context.startForegroundService(HyperCarService.startIntent(ctx, device))` 拉起前台服务
 *    （这一步可能被系统拦下——Android 12+ 后台 FGS 豁免清单里没有「蓝牙广播」，本项目靠
 *    SYSTEM_ALERT_WINDOW 满足；需捕获 ForegroundServiceStartNotAllowedException 只记日志）；
 *    若已在前台（isRunning=true，说明是 service 回调过来触发会话），则直接执行会话握手重活。
 *  - connected=false：执行会话拆除（幂等），并确保前台服务收摊
 *    （`context.startService(HyperCarService.stopIntent(ctx, device))`）。
 *    **只应在 [HyperCarService.isRunning] 为 true 时投递**：service 收到 ACTION_STOP 会
 *    回灌一次 handler(false)，无条件投递就形成 handler↔service 的 startService 死循环
 *    （2026-10-23 实测每秒数百轮、日志刷屏且下车后不停）。
 *  - 回调可能被 receiver 与 service 先后各调一次，**必须幂等**；重活一律放后台线程。
 */
object AutoStart {
    @Volatile
    // The service handoff preserves the original edge timestamp.
    var handler: ((Context, BluetoothDevice, Boolean, Boolean, Long) -> Unit)? = null
}
