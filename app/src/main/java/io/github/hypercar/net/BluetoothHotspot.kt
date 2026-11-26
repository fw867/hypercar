package io.github.hypercar.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Hotspot advertised by the current Bluetooth peer in 0x5703 or the wireless group of 0x4301. */
@ConsistentCopyVisibility
data class BluetoothHotspot private constructor(val peer: String, val ssid: String) {
    companion object {
        fun fromPeer(peer: String?, ssid: String?): BluetoothHotspot? {
            if (peer.isNullOrBlank() || ssid.isNullOrBlank()) return null
            return BluetoothHotspot(peer, ssid)
        }

        internal suspend fun <N> awaitReadyAssociation(
            isCurrent: () -> Boolean,
            observe: suspend () -> Association<N>,
            isReady: (N) -> Boolean,
            onPending: () -> Unit = {},
        ): Association<N> {
            val absent = Association<N>(false, null)
            var pending = absent
            val resolved = withTimeoutOrNull(VerifiedWifiWait.ASSOCIATED_ADDRESS_WAIT_MS) {
                while (isCurrent()) {
                    val match = observe()
                    if (!isCurrent()) break
                    if (!match.associated || match.network?.let(isReady) == true) return@withTimeoutOrNull match
                    if (!pending.associated) onPending()
                    pending = Association(true, null)
                    delay(250)
                }
                absent
            }
            return if (isCurrent()) resolved ?: pending else absent
        }
    }

    fun applies(owner: Long, attempt: Long, activePeer: String?, selectedPeer: String?): Boolean =
        owner == attempt && (activePeer.isNullOrBlank() || peer.equals(activePeer, true)) &&
            (selectedPeer.isNullOrBlank() || peer.equals(selectedPeer, true))

    data class Association<N>(val associated: Boolean, val network: N?)

    fun <N> association(wifi: Map<N, String?>, connectedStationSsid: String?, joiningStationSsid: String? = null): Association<N> {
        val exact = wifi.filterValues { it == ssid }
        if (exact.isNotEmpty()) return Association(true, exact.keys.singleOrNull())
        if (connectedStationSsid != ssid) return Association(joiningStationSsid == ssid, null)
        // A system STA can appear before Android publishes its Network/addresses. On multi-STA
        // devices a redacted handle is attributable only when it is the sole Wi-Fi candidate.
        return Association(true, wifi.entries.singleOrNull()?.takeIf { it.value == null }?.key)
    }
}
