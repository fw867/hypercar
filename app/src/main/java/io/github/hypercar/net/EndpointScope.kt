package io.github.hypercar.net

import java.io.IOException

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

    fun onNetwork(host: String, interfaceName: String?): String {
        val raw = host.trim()
        if (!isLinkLocal(raw) || raw.contains('%')) return raw
        val iface = interfaceName?.takeIf { it.isNotBlank() }
            ?: throw IOException("车机网络没有 IPv6 scope 接口")
        return "$raw%$iface"
    }
}
