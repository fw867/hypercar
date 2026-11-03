package io.github.hypercar.session

/** Opt-in shortcut only on a bound, paired receiver network; never override another live peer. */
internal object ProactiveWifiSessionPolicy {
    const val DELAY_MILLIS = 10_000L
    fun allows(enabled: Boolean, receiver: String?, activePeer: String?, networkBound: Boolean): Boolean =
        enabled && networkBound && PairingEvidence.receiverKey(receiver) != null &&
            (activePeer == null || receiver.equals(activePeer, ignoreCase = true))

    fun rememberedEndpoint(value: String, ssid: String?): Pair<String, Int>? {
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

    fun ready(eligible: Boolean, network: String?, now: Long): Boolean {
        if (!eligible || network == null) {
            networkKey = null
            return false
        }
        if (networkKey != network) {
            networkKey = network
            readyAt = now
            return false
        }
        return now - readyAt >= ProactiveWifiSessionPolicy.DELAY_MILLIS
    }
}
