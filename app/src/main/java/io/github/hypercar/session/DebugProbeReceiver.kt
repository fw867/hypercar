package io.github.hypercar.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * **调试用**广播入口（不用打开界面就能触发一次连接/断开）：
 * ```
 * adb shell am broadcast -a io.github.hypercar.PROBE_CONNECT -p io.github.hypercar
 * adb shell am broadcast -a io.github.hypercar.PROBE_DISCONNECT -p io.github.hypercar
 * ```
 *
 * 排查秒连链路 / 掉线自动重连时用；日志照常写进 App 日志与落盘日志。
 */
class DebugProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            HyperCarRepo.init(context)
            when (intent.action) {
                "io.github.hypercar.PROBE_CONNECT" -> HyperCarRepo.connect(context)
                "io.github.hypercar.PROBE_DISCONNECT" -> HyperCarRepo.disconnect()
            }
        }
    }
}
