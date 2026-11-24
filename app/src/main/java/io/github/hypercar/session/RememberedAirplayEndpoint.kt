package io.github.hypercar.session

/** Validates cached addresses without granting permission to start a session. */
internal object RememberedAirplayEndpoint {
    private const val UNBOUND_FORMAT = "v2;"

    fun format(target: AirplayTarget, ssid: String?): String? {
        if (target.verifiedNetworkHandle != null || ssid.isNullOrBlank() || !isIpv4(target.host)) return null
        return "$UNBOUND_FORMAT${target.host}:${target.port}|$ssid"
    }

    fun parse(value: String, ssid: String?, desktopReceiver: Boolean = false): Pair<String, Int>? {
        // A shared LAN's SSID does not associate an old address with this software receiver.
        // Older entries cannot distinguish a route verified on a specific Network.
        if (desktopReceiver || !value.startsWith(UNBOUND_FORMAT)) return null
        if (ssid.isNullOrBlank() || value.substringAfter('|', "") != ssid) return null
        val endpoint = value.removePrefix(UNBOUND_FORMAT).substringBefore('|')
        val host = endpoint.substringBefore(':')
        if (!isIpv4(host)) return null
        val port = endpoint.substringAfter(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    private fun isIpv4(host: String): Boolean =
        host.split('.').let { octets -> octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 } }
}
