package io.github.hypercar.session

/**
 * 已知车机 Wi-Fi 已绑定、本轮还没有 iAP2、首扇可发现窗口已结束。
 * 这不是蓝牙交棒完成：ctrl 邀请要等车机自己连入。
 */
internal object KnownWifiWaitCopy {
    const val HERO = "车机尚未发起 iAP2，ctrl 邀请要等这次蓝牙连入"

    fun awaitingHeadUnitIap2(
        wifiBound: Boolean,
        sessionStarts: Int,
        discoverableWindowOver: Boolean,
    ): Boolean = wifiBound && sessionStarts == 0 && discoverableWindowOver

    fun progress(waitedSeconds: Long, sessionStarts: Int): String =
        "SESSION: $HERO，已等 ${waitedSeconds}s（本轮蓝牙会话=$sessionStarts）"
}
