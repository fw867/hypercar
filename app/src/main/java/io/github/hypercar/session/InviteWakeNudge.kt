package io.github.hypercar.session

/**
 * BMW secondary boarding often auto-joins car Wi-Fi first; classic ACL is only a short
 * wake window and then retires. Use ACL edges to refresh Wi-Fi ctrl discovery — not to
 * demand a lasting BT iAP2 session, and not to start experimental proactive AirPlay.
 */
internal object InviteWakeNudge {
    const val COOLDOWN_MILLIS = 15_000L
    /** Handoff to Wi-Fi is brief; allow a ctrl refresh soon after the connect nudge. */
    const val HANDOFF_COOLDOWN_MILLIS = 3_000L

    fun shouldNudge(
        sessionLive: Boolean,
        streaming: Boolean,
        wifiSessionKeepable: Boolean,
        hasCtrlAdvertiser: Boolean,
        now: Long,
        lastNudgeAt: Long,
        handoffToWifi: Boolean = false,
    ): Boolean {
        if (!sessionLive || streaming || wifiSessionKeepable || !hasCtrlAdvertiser) return false
        val cooldown = if (handoffToWifi) HANDOFF_COOLDOWN_MILLIS else COOLDOWN_MILLIS
        if (lastNudgeAt > 0L && now - lastNudgeAt < cooldown) return false
        return true
    }

    /** Discoverable only helps while ACL may still be up; skip it on Wi-Fi handoff. */
    fun armDiscoverable(handoffToWifi: Boolean): Boolean = !handoffToWifi
}
