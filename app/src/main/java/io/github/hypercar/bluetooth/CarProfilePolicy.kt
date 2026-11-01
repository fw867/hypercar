package io.github.hypercar.bluetooth

/**
 * 车机 HFP/A2DP 拦截的**纯规则**（可单测；不碰 Looper/蓝牙栈）。
 *
 * 现场依据（2026-11-01 11:05，`dumpsys bluetooth_manager`）：
 * ```
 * 11:05:10.840 HEADSET DISCONNECTED→CONNECTING   ← 手机自己回连 HFP
 * 11:05:13.038 CLASSICAL-DISCONNECTED status=Remote Terminated   ← 车机 0.56s 拆链
 * 11:05:17.237 同样一轮 0.61s
 * ```
 * 两次 iAP2 都没被打开；成功那次（11:07:16）没有 HFP 先行。
 */
object CarProfilePolicy {
    /** 同一地址两次强制断开的最小间隔：太密会和车机 ACL 重连互相打架。 */
    const val MIN_INTERVAL_MILLIS = 3_000L

    fun shouldEnforce(
        targetAddress: String?,
        eventAddress: String?,
        nowMillis: Long,
        lastEnforceAtMillis: Long,
        minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
    ): Boolean {
        val target = targetAddress?.trim().orEmpty()
        val event = eventAddress?.trim().orEmpty()
        if (target.isEmpty() || event.isEmpty()) return false
        if (!target.equals(event, ignoreCase = true)) return false
        return nowMillis - lastEnforceAtMillis >= minIntervalMillis
    }
}
