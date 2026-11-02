package io.github.hypercar.net

import java.io.IOException

/** Link-local unicast is fe80::/10, not just the four commonly printed prefix values. */
object EndpointScope {
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
