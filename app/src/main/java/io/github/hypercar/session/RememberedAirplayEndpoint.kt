package io.github.hypercar.session

/** Validates cached addresses without granting permission to start a session. */
internal object RememberedAirplayEndpoint {
    fun parse(value: String, ssid: String?, desktopReceiver: Boolean = false): Pair<String, Int>? {
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
