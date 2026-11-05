package io.github.hypercar.spike

import io.github.hypercar.shizuku.ShizukuTools

/** Shell launch helpers used by the production car UI. */
object SpikeDisplay {
    suspend fun amStartComponentOnDisplay(
        displayId: Int,
        component: String,
        clearTask: Boolean = false,
    ): String {
        if (!ShizukuTools.isReady) return "Shizuku 未就绪——跳过 shell 启动"
        val flag = if (clearTask) "--activity-clear-task " else ""
        var last = ""
        // HyperOS 上第一条 shell 命令偶尔被 Shizuku 以 "s0 was cancelled" 取消（服务刚唤醒），
        // 重试一次基本必成；不要因为一次抖动就判 D2 启动失败。
        repeat(2) { attempt ->
            val r = runCatching {
                ShizukuTools.exec("am start $flag-n $component --display $displayId", 12_000)
            }
            if (r.isSuccess) {
                val out = r.getOrThrow()
                last = "am start $flag-n $component --display $displayId → exit=${out.code} " +
                    "out=${out.out.take(700)} err=${out.err.take(300)}"
                if (out.code == 0) return last
            } else {
                last = "am start 异常（第 ${attempt + 1} 次）: ${r.exceptionOrNull()?.message}"
            }
            kotlinx.coroutines.delay(400)
        }
        return last
    }
}
