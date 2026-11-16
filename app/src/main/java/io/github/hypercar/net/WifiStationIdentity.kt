package io.github.hypercar.net

/** Station identity, never the AP's BSSID. Shell evidence must match this Network's IP. */
internal object WifiStationIdentity {
    fun normalize(value: String?): String? = CarNetworkReuse.mac(value)?.takeIf {
        it.substringBefore(':').toInt(16) and 1 == 0
    }

    fun fromStatus(status: String, localAddresses: Set<String>): String? {
        if (localAddresses.isEmpty()) return null
        val quoted = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val mac = Regex("(?:^|,\\s*)MAC:\\s*([0-9a-fA-F:]{17})(?=,|$)")
        val ip = Regex("(?:^|,\\s*)IP:\\s*/?([^,\\s]+)(?=,|$)")
        return status.lineSequence().filter { it.startsWith("WifiInfo: ") }.mapNotNull { line ->
            val fields = quoted.replace(line.removePrefix("WifiInfo: "), "")
            val address = ip.findAll(fields).toList().singleOrNull()?.groupValues?.get(1)
            if (address !in localAddresses) null else
                mac.findAll(fields).toList().singleOrNull()?.groupValues?.get(1)?.let(::normalize)
        }.toList().singleOrNull()
    }
}
