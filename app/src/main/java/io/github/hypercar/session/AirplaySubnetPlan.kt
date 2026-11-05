package io.github.hypercar.session

/** Bounded IPv4 discovery on one already selected receiver network. */
internal class AirplaySubnetPlan(val self: String, val prefix: Int) {
    private val address = requireNotNull(ipv4(self))
    init { require(prefix in 16..30) }
    private val size = 1L shl (32 - prefix)
    private val network = address and (size - 1).inv()
    private val broadcast = network + size - 1

    fun usable(host: String): Boolean = ipv4(host)?.let {
        it > network && it < broadcast && it != address
    } ?: false

    fun arpPeers(lines: List<String>, interfaceName: String?): List<String> = lines.mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 6) return@mapNotNull null
        val flags = columns[2].removePrefix("0x").toIntOrNull(16) ?: return@mapNotNull null
        val matchesInterface = if (interfaceName == null) columns[5].startsWith("wlan") else columns[5] == interfaceName
        columns[0].takeIf { matchesInterface && (flags and 2) != 0 && usable(it) }
    }.distinct()

    fun commonHosts(neighbors: List<String>): List<String> = linkedSetOf<String>().apply {
        addAll(neighbors.filter(::usable))
        val local24 = address and 255L.inv()
        listOf(1L, 2L, 254L).forEach { add(text(local24 + it)) }
        if (prefix < 24) {
            var subnet = network
            while (subnet < broadcast && this.size < 48) {
                add(text(subnet + 1)); add(text(subnet + 254)); subnet += 256
            }
        }
    }.filter(::usable)

    fun remainingHosts(alreadyTried: Set<String>, limit: Int): List<String> {
        require(limit >= 0)
        val hosts = ArrayList<String>()
        var next = network + 1
        while (next < broadcast && hosts.size < limit) {
            val host = text(next++)
            if (host != self && host !in alreadyTried) hosts.add(host)
        }
        return hosts
    }

    companion object {
        private fun ipv4(value: String): Long? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                if (part.isEmpty() || part.any { !it.isDigit() }) return null
                val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                result = (result shl 8) or octet.toLong()
            }
            return result
        }
        private fun text(value: Long) = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 255).toString() }
    }
}
