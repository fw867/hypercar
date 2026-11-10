package io.github.hypercar.session

/**
 * 已知车机 Wi-Fi 已绑定、本轮还没有新的 iAP2、首扇可发现窗口已结束。
 * 本轮次数 = sessionStartCount - runConnect 记下的 baseline，与可发现窗口同一口径；
 * 进程里更早的 cafe 连入不算这一轮。
 * 这不是蓝牙交棒完成：ctrl 邀请要等车机自己连入。
 */
internal object KnownWifiWaitCopy {
    const val HERO = "车机尚未发起 iAP2，ctrl 邀请要等这次蓝牙连入"

    /** New cafe accepts since this connect round started. Earlier rounds stay in the process counter. */
    fun sessionsThisRound(sessionStartCount: Int, roundBaseline: Int): Int =
        (sessionStartCount - roundBaseline).coerceAtLeast(0)

    fun awaitingHeadUnitIap2(
        wifiBound: Boolean,
        sessionStartCount: Int,
        roundBaseline: Int,
        discoverableWindowOver: Boolean,
    ): Boolean = wifiBound &&
        sessionsThisRound(sessionStartCount, roundBaseline) == 0 &&
        discoverableWindowOver

    fun progress(waitedSeconds: Long, sessionsThisRound: Int): String =
        "SESSION: $HERO，已等 ${waitedSeconds}s（本轮蓝牙会话=$sessionsThisRound）"
}
