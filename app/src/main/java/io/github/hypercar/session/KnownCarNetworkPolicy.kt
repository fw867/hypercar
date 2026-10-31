package io.github.hypercar.session

/** A remembered SSID is only a discovery hint; AirPlay must still verify this receiver's identity. */
object KnownCarNetworkPolicy {
    fun receiver(ssid: String?, networks: Set<String>, targetMac: String?): String? {
        if (ssid.isNullOrBlank()) return null
        val candidates = networks.mapNotNull { row ->
            val mac = row.substringAfterLast('|', "").uppercase(java.util.Locale.ROOT)
            mac.takeIf {
                row.substringBeforeLast('|', "") == ssid &&
                    PairingEvidence.receiverKey(mac) != null &&
                    (targetMac.isNullOrBlank() || mac.equals(targetMac, ignoreCase = true))
            }
        }.distinct()
        return candidates.singleOrNull()
    }
}
