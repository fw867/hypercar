package io.github.hypercar.bluetooth

import java.io.Closeable

/** 有限时长的经典可发现窗口；只接管原本可连接、不可发现的状态。 */
internal class DiscoverabilityLease(
    private val port: Port,
    private val schedule: (Long, () -> Unit) -> Closeable,
    private val log: (String) -> Unit,
) {
    interface Port {
        fun mode(): Int
        fun timeoutSeconds(): Long
        fun setMode(mode: Int)
        fun setTimeoutSeconds(seconds: Long)
    }
    private data class Window(val token: String, val previousTimeout: Long, val seconds: Long,
        var timer: Closeable? = null)
    private var window: Window? = null

    @Synchronized fun begin(token: String, seconds: Int): String {
        if (window != null) return "SKIP 已有可发现窗口"
        if (seconds !in 1..30) return "ERR 可发现窗口必须为 1..30 秒"
        return try {
            val mode = port.mode()
            if (mode != 21) return "SKIP 原扫描模式=$mode，保留系统/用户状态"
            val previous = port.timeoutSeconds()
            check(previous >= 0) { "无法读取原可发现超时" }
            window = Window(token, previous, seconds.toLong())
            // 设置系统超时供状态核对；实测 HyperOS 不会仅靠它自动退出，独立定时清理必不可少。
            port.setTimeoutSeconds(seconds.toLong())
            port.setMode(23)
            window!!.timer = schedule(seconds * 1_000L) { end(token) }
            "OK 开启经典蓝牙可发现 ${seconds}s（原模式=$mode 原超时=${previous}s）"
        } catch (t: Exception) {
            end(token)
            "ERR 开启可发现失败：${t.cause?.message ?: t.message}"
        }
    }

    @Synchronized fun end(token: String): String {
        val current = window?.takeIf { it.token == token } ?: return "SKIP 窗口已结束或不属于本轮"
        window = null
        current.timer?.close()
        val result = try {
            // 超时已被设置页/其他应用更改时，不再接管其可发现状态。
            if (port.timeoutSeconds() != current.seconds) "SKIP 超时已被系统/用户修改，保留当前状态"
            else {
                val mode = port.mode()
                if (mode == 23) port.setMode(21)
                port.setTimeoutSeconds(current.previousTimeout)
                "OK 可发现窗口结束，恢复原超时；原当前模式=$mode"
            }
        } catch (t: Exception) { "ERR 可发现窗口清理：${t.cause?.message ?: t.message}" }
        log(result)
        return result
    }
}
