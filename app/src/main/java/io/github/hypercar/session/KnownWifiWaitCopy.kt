package io.github.hypercar.session

import io.github.hypercar.headunit.HeadUnitProfiles

/**
 * 已知车机 Wi-Fi 已绑定、本轮还没有新的 iAP2、首扇可发现窗口已结束。
 * 本轮次数 = sessionStartCount - runConnect 记下的 baseline，与可发现窗口同一口径；
 * 进程里更早的 cafe 连入不算这一轮。
 * 宝马这不是蓝牙交棒完成：ctrl 邀请要等车机自己连入。
 * 奔驰不走那两句，进度只说还在等这轮 iAP2 上的 0x4301。
 */
internal object KnownWifiWaitCopy {
    const val HERO = "车机尚未发起 iAP2，ctrl 邀请要等这次蓝牙连入"

    /** Negotiating step while Mercedes is still waiting for this round's 0x4301. */
    const val ENDPOINT_STEP = "等待车机这轮 iAP2 的 0x4301"

    fun waitsForHeadUnitEndpoint(profileId: String?): Boolean =
        profileId == HeadUnitProfiles.MERCEDES_MBUX.id

    fun endpointOpening(): String =
        "SESSION: 还在等车机这轮 iAP2 上的 0x4301；保持蓝牙监听与 Wi-Fi 广播，不预连记忆/扫描端口"

    fun endpointProgress(waitedSeconds: Long): String =
        "SESSION: 还在等车机这轮 iAP2 上的 0x4301，已等 ${waitedSeconds}s"

    /** BMW known-Wi-Fi line, including the per-round session count. Mercedes does not use this. */
    fun bmwBoundProgress(waitedSeconds: Long, sessionsThisRound: Int): String =
        "SESSION: 等待本轮车机 Wi-Fi ctrl 邀请，已等 ${waitedSeconds}s；" +
            "已绑定已知车机网络，宝马二次上车蓝牙常仅短窗口后交棒，" +
            "不以持续 iAP2 会话为成败（本轮蓝牙会话=$sessionsThisRound）"

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

    /**
     * Known-Wi-Fi SESSION line. Mercedes always reports the missing 0x4301.
     * BMW keeps the handoff line until the discoverable window ends, then the ctrl-invite line.
     */
    fun boundProgress(
        profileId: String?,
        waitedSeconds: Long,
        sessionsThisRound: Int,
        awaitingHeadUnitIap2: Boolean,
    ): String = when {
        waitsForHeadUnitEndpoint(profileId) -> endpointProgress(waitedSeconds)
        awaitingHeadUnitIap2 -> progress(waitedSeconds, sessionsThisRound)
        else -> bmwBoundProgress(waitedSeconds, sessionsThisRound)
    }

    /** Step shown while Wi-Fi is bound. Null keeps the caller's generic wait, and clears [HERO]. */
    fun boundNegotiatingStep(profileId: String?, awaitingHeadUnitIap2: Boolean): String? = when {
        waitsForHeadUnitEndpoint(profileId) -> ENDPOINT_STEP
        awaitingHeadUnitIap2 -> HERO
        else -> null
    }
}
