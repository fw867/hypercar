package io.github.hypercar.net

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/** Link-local unicast is fe80::/10, not just the four commonly printed prefix values. */
object EndpointScope {
    /** Socket peer addresses are numeric; keep IPv6 (and its scope) as authoritative invites. */
    fun invitationHost(peer: String?): String? {
        val raw = peer?.trim()?.takeIf { it.isNotEmpty() && it != "?" } ?: return null
        val address = raw.substringBefore('%')
        if (raw.contains('%') && raw.substringAfter('%').isBlank()) return null
        if (address.contains(':')) {
            if (address.count { it == ':' } < 2 || address.any {
                    it != ':' && it != '.' && it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'
                }) return null
        } else {
            val parts = address.split('.')
            if (raw.contains('%') || parts.size != 4 || parts.any {
                    it.isEmpty() || it.any { c -> c !in '0'..'9' } || it.toIntOrNull() !in 0..255
                }) return null
        }
        return raw
    }

    private val linkLocalPrefix = Regex("(?i)^fe[89ab][0-9a-f]:")
    fun isLinkLocal(host: String): Boolean = linkLocalPrefix.containsMatchIn(host.trim())

    /** Both addresses already belong to one Network. A scope may be printed as a name or an ID. */
    internal fun sameAddressOnNetwork(host: String, other: String): Boolean =
        addressKey(host)?.let { it == addressKey(other) } ?: false

    /** The gate rules out hostnames before invoking Java's numeric address parser. */
    internal fun addressKey(host: String): String? {
        val address = invitationHost(host)?.substringBefore('%') ?: return null
        if (':' !in address) return address.split('.').joinToString(".") { it.toInt().toString() }
        return try {
            InetAddress.getByName(address).hostAddress
        } catch (_: UnknownHostException) {
            null
        }
    }

    fun onNetwork(host: String, interfaceName: String?): String {
        val raw = host.trim()
        if (!isLinkLocal(raw) || raw.contains('%')) return raw
        val iface = interfaceName?.takeIf { it.isNotBlank() }
            ?: throw IOException("车机网络没有 IPv6 scope 接口")
        return "$raw%$iface"
    }
}
