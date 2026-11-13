package io.github.hypercar.session

/** Opt-in repair only with a recent interrupted-session grant; pairing alone grants nothing. */
internal object ProactiveWifiSessionPolicy {
    const val DELAY_MILLIS = 10_000L
    fun allows(enabled: Boolean, receiver: String?, activePeer: String?, networkBound: Boolean,
        recoveryAllowed: Boolean = false): Boolean =
        enabled && recoveryAllowed && networkBound && PairingEvidence.receiverKey(receiver) != null &&
            (activePeer == null || receiver.equals(activePeer, ignoreCase = true))

    fun rememberedEndpoint(value: String, ssid: String?, desktopReceiver: Boolean = false): Pair<String, Int>? {
        // A shared LAN's SSID does not associate an old address with this software receiver.
        if (desktopReceiver) return null
        if (ssid.isNullOrBlank() || value.substringAfterLast('|', "") != ssid) return null
        val endpoint = value.substringBeforeLast('|')
        val host = endpoint.substringBefore(':')
        val octets = host.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.any { c -> !c.isDigit() } || it.toIntOrNull() !in 0..255 }) return null
        val port = endpoint.substringAfter(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }
}

/** No blocking sleep: normal invitations remain available throughout the stable-network grace. */
internal class ProactiveWifiSessionDelay {
    private var networkKey: String? = null
    private var readyAt = 0L

    fun ready(eligible: Boolean, network: String?, now: Long, immediate: Boolean = false): Boolean {
        if (!eligible || network == null) {
            networkKey = null
            return false
        }
        if (immediate) {
            networkKey = network
            readyAt = now
            return true
        }
        if (networkKey != network) {
            networkKey = network
            readyAt = now
            return false
        }
        return now - readyAt >= ProactiveWifiSessionPolicy.DELAY_MILLIS
    }
}
