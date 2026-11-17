package io.github.hypercar.net

/** Hotspot advertised by the current Bluetooth peer in 0x5703 or the wireless group of 0x4301. */
@ConsistentCopyVisibility
data class BluetoothHotspot private constructor(val peer: String, val ssid: String) {
    companion object {
        fun fromPeer(peer: String?, ssid: String?): BluetoothHotspot? {
            if (peer.isNullOrBlank() || ssid.isNullOrBlank()) return null
            return BluetoothHotspot(peer, ssid)
        }
    }

    fun applies(owner: Long, attempt: Long, activePeer: String?, selectedPeer: String?): Boolean =
        owner == attempt && (activePeer.isNullOrBlank() || peer.equals(activePeer, true)) &&
            (selectedPeer.isNullOrBlank() || peer.equals(selectedPeer, true))

    data class Association<N>(val associated: Boolean, val network: N?)

    fun <N> association(wifi: Map<N, String?>, connectedStationSsid: String?): Association<N> {
        val exact = wifi.filterValues { it == ssid }
        if (exact.isNotEmpty()) return Association(true, exact.keys.singleOrNull())
        if (connectedStationSsid != ssid) return Association(false, null)
        // A system STA can appear before Android publishes its Network/addresses. On multi-STA
        // devices a redacted handle is attributable only when it is the sole Wi-Fi candidate.
        return Association(true, wifi.entries.singleOrNull()?.takeIf { it.value == null }?.key)
    }
}
