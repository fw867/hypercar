package io.github.hypercar.session

/** A remembered SSID is only a discovery hint; AirPlay must still verify this receiver's identity. */
object KnownCarNetworkPolicy {
    internal fun <T : Any> sessionSsid(network: T, visibleSsid: String?, joinedNetwork: T?, joinedSsid: String?): String? =
        visibleSsid ?: joinedSsid.takeIf { joinedNetwork == network }

    /** Secondary Wi-Fi has no default-station identity. Require one handle and a unique saved SSID owner. */
    internal fun <T> associatedHotspot(live: List<Pair<T, String>>, saved: Set<String>, peer: String?): Pair<T, String>? {
        if (peer.isNullOrBlank()) return null
        return live.singleOrNull { (_, ssid) -> receiver(ssid, saved, null).equals(peer, ignoreCase = true) }
    }

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
