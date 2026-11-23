package io.github.hypercar.spike

import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.shizuku.shellQuote

/** Shell launch helpers used by the production car UI. */
object SpikeDisplay {
    internal data class LaunchResult(val ok: Boolean, val detail: String)

    suspend fun amStartComponentOnDisplay(
        displayId: Int,
        component: String,
        clearTask: Boolean = false,
    ): String {
        val flag = if (clearTask) "--activity-clear-task " else ""
        val command = "am start $flag-n ${shellQuote(component)} --display $displayId"
        return amStart(command).detail
    }

    internal fun launcherCommand(displayId: Int, component: String): String =
        "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-n ${shellQuote(component)} --display $displayId"

    internal suspend fun amStartLauncherOnDisplay(displayId: Int, component: String): LaunchResult =
        amStart(launcherCommand(displayId, component))

    private suspend fun amStart(command: String): LaunchResult {
        if (!ShizukuTools.isReady) return LaunchResult(false, "Shizuku 未就绪——跳过 shell 启动")
        var last = ""
        // HyperOS 上第一条 shell 命令偶尔被 Shizuku 以 "s0 was cancelled" 取消（服务刚唤醒），
        // 重试一次基本必成；不要因为一次抖动就判 D2 启动失败。
        repeat(2) { attempt ->
            val r = runCatching {
                ShizukuTools.exec(command, 12_000)
            }
            if (r.isSuccess) {
                val out = r.getOrThrow()
                last = "$command → exit=${out.code} " +
                    "out=${out.out.take(700)} err=${out.err.take(300)}"
                if (out.ok) return LaunchResult(true, last)
            } else {
                last = "am start 异常（第 ${attempt + 1} 次）: ${r.exceptionOrNull()?.message}"
            }
            kotlinx.coroutines.delay(400)
        }
        return LaunchResult(false, last)
    }
}
