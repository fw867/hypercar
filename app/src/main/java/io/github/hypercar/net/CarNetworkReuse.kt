package io.github.hypercar.net

/** Fresh link evidence, rather than a remembered SSID/Network handle, authorizes reuse. */
internal object CarNetworkReuse {
    data class Evidence(
        val ssid: String?, val bssid: String?, val hasAddress: Boolean,
        val routeToPeer: Boolean, val pinned: Boolean,
    )
    fun mac(value: String?): String? = value?.lowercase()?.takeIf {
        it.matches(Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")) &&
            it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00"
    }
    fun accepts(ssid: String?, receiverId: String?, invited: Boolean, link: Evidence): Boolean {
        if (!link.hasAddress) return false
        if (!ssid.isNullOrBlank()) {
            if (link.ssid != ssid) return false
        } else if (!link.pinned && !invited) return false
        val expected = mac(receiverId)
        if (expected != null && mac(link.bssid) != expected) return false
        // A matched BSSID can identify the hotspot before iAP2 supplies endpoint addresses.
        return link.routeToPeer || (link.pinned && !ssid.isNullOrBlank()) || expected != null
    }
}
