package io.github.hypercar.net

/** onUnavailable covers refusal AND the system request timeout; it does not identify the cause. */
internal object SpecifierJoinFailure {
    fun describe(elapsedMs: Long, timeoutMs: Long, unavailable: Boolean, wifiEnabled: Boolean): String {
        val elapsed = elapsedMs.coerceAtLeast(0)
        val evidence = "耗时=${elapsed}ms，onUnavailable=$unavailable"
        return when {
            !wifiEnabled -> "Wi-Fi 开关已关闭（$evidence）；请开启 Wi-Fi 后重试"
            elapsed >= timeoutMs - 1_000 ->
                "车机 Wi-Fi 入网超时（$evidence）；请保持 HyperCar 在前台并确认系统连接提示，检查热点是否开启；可先在系统 Wi-Fi 中连接车机热点后重试"
            unavailable ->
                "系统未能完成车机 Wi-Fi 请求（$evidence）；可能被拒绝或连接失败，不能仅凭此回调判定原因；请在前台确认连接提示或先在系统 Wi-Fi 中连接车机热点"
            else -> "未取得车机 Wi-Fi 网络句柄（$evidence）；请确认热点连接后重试"
        }
    }
}
