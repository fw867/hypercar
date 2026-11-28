package io.github.hypercar.session

/** One recovery chain per receiver; brief control connections do not replenish it. */
internal class ReconnectBudget {
    data class Retry(val number: Int, val delayMs: Long)
    sealed interface WifiWake {
        data object Regular : WifiWake
        class Extra(val networkHandle: Long) : WifiWake
    }
    private var peer: String? = null
    private var failures = 0
    private var recovering = false
    private var healthySince: Long? = null
    private var wifiWakeAfterProjectionSwitch = false
    private var reservedWifiWake: WifiWake.Extra? = null

    @Synchronized fun start(receiver: String?, manual: Boolean = false) {
        if (manual || (receiver != null && peer != null && peer != receiver)) clear()
        if (receiver != null) peer = receiver
    }

    /** Called only after the current native pipeline has actually written video. */
    @Synchronized fun videoReady(receiver: String?, now: Long) {
        start(receiver)
        if (healthySince == null) healthySince = now
    }

    @Synchronized fun failed(receiver: String?, hadLink: Boolean, now: Long): Retry? {
        start(receiver)
        val stable = healthySince?.let { now >= it && now - it >= STABLE_MS } == true
        if (stable) {
            failures = 0
            wifiWakeAfterProjectionSwitch = false
            reservedWifiWake = null
        }
        healthySince = null
        if (!hadLink && !recovering) return null
        recovering = true
        if (failures >= DELAYS.size) return null
        return Retry(failures + 1, DELAYS[failures++])
    }

    @Synchronized fun mayWake(receiver: String?): Boolean =
        (receiver != null && peer != null && receiver != peer) || failures < DELAYS.size ||
            (receiver != null && receiver == peer && wifiWakeAfterProjectionSwitch && reservedWifiWake == null)

    @Synchronized fun grantWifiWakeAfterProjectionSwitch() {
        wifiWakeAfterProjectionSwitch = peer != null && failures >= DELAYS.size
        reservedWifiWake = null
    }

    @Synchronized fun reserveWifiWake(receiver: String?, verifiedNetwork: Long?): WifiWake? {
        if (verifiedNetwork == null || !mayWake(receiver)) return null
        if (receiver != peer || !wifiWakeAfterProjectionSwitch) return WifiWake.Regular
        return WifiWake.Extra(verifiedNetwork).also { reservedWifiWake = it }
    }

    @Synchronized fun commitWifiWake(reservation: WifiWake.Extra, boundNetwork: Long): Boolean {
        if (reservation.networkHandle != boundNetwork || !releaseWifiWake(reservation)) return false
        wifiWakeAfterProjectionSwitch = false
        return true
    }

    @Synchronized fun releaseWifiWake(reservation: WifiWake.Extra): Boolean {
        if (reservedWifiWake !== reservation) return false
        reservedWifiWake = null
        return true
    }

    @Synchronized fun clear() {
        peer = null; failures = 0; recovering = false; healthySince = null
        wifiWakeAfterProjectionSwitch = false
        reservedWifiWake = null
    }

    companion object {
        fun receiverForRetry(profileId: String, enginePeer: String?, activeBluetoothPeer: String?,
            passiveWifiReceiver: String?, fallbackReceiverKey: String?): String? =
            PairingEvidence.canvasKey(profileId, enginePeer, activeBluetoothPeer)
                ?: passiveWifiReceiver ?: fallbackReceiverKey?.let { "$profileId:$it" }

        const val STABLE_MS = 120_000L
        const val MAX_RETRIES = 5
        private val DELAYS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
    }
}
