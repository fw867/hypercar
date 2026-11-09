package io.github.hypercar.session

/**
 * While waiting for a car ctrl invite / inbound iAP2, a classic ACL edge is a chance to
 * refresh discovery — not a reason to start Wi-Fi AirPlay. Cooldown avoids flapping storms.
 */
internal object InviteWakeNudge {
    const val COOLDOWN_MILLIS = 15_000L

    fun shouldNudge(
        sessionLive: Boolean,
        streaming: Boolean,
        wifiSessionKeepable: Boolean,
        hasCtrlAdvertiser: Boolean,
        now: Long,
        lastNudgeAt: Long,
    ): Boolean {
        if (!sessionLive || streaming || wifiSessionKeepable || !hasCtrlAdvertiser) return false
        if (lastNudgeAt > 0L && now - lastNudgeAt < COOLDOWN_MILLIS) return false
        return true
    }
}
