package io.github.hypercar.net

import kotlinx.coroutines.CancellationException

/** A recent scan can identify an AP; an iAP2 device identifier alone cannot. */
internal data class WifiApHint(val bssid: String, val frequency: Int, val ageSeconds: Double) {
    companion object {
        private val row = Regex("^\\s*([0-9a-fA-F:]{17})\\s+(\\d+)\\s+(-?\\d+)\\s+([0-9.]+)\\s+(.*?)\\s{2,}\\[.*$")
        internal const val MAX_SCAN_AGE_SECONDS = 30.0
        internal const val SCAN_WAIT_MS = 2_400L

        fun fromScan(text: String, ssid: String, deviceIdentifierCandidate: String?): WifiApHint? {
            if (ssid.isBlank()) return null
            val preferred = CarNetworkReuse.mac(deviceIdentifierCandidate)?.takeIf { usable(it) }
            val candidates = text.lineSequence().mapNotNull { line ->
                row.matchEntire(line)?.groupValues?.let { values ->
                    if (values[5] != ssid) return@let null
                    val bssid = CarNetworkReuse.mac(values[1])?.takeIf { usable(it) } ?: return@let null
                    val frequency = values[2].toIntOrNull()?.takeIf { it in 2400..7125 } ?: return@let null
                    val age = values[4].toDoubleOrNull()?.takeIf {
                        it.isFinite() && it in 0.0..MAX_SCAN_AGE_SECONDS
                    } ?: return@let null
                    WifiApHint(bssid, frequency, age)
                }
            }.toList()
            // Prefer only a corroborated device/AP match. Without one, multiple BSSIDs sharing
            // an SSID are ambiguous; scan age alone does not identify the intended receiver.
            candidates.filter { it.bssid == preferred }.minByOrNull { it.ageSeconds }?.let { return it }
            return candidates.groupBy { it.bssid }.values.singleOrNull()?.minByOrNull { it.ageSeconds }
        }

        /** Optional bounded scan assistance; failure leaves the normal SSID request available. */
        suspend fun resolve(
            ssid: String,
            deviceIdentifier: () -> String?,
            scan: suspend () -> String,
            startScan: suspend () -> Unit,
            now: () -> Long,
        ): WifiApHint? {
            suspend fun lookup(): WifiApHint? {
                val text = scan()
                return fromScan(text, ssid, deviceIdentifier()) // identity may arrive during the scan
            }
            return try {
                lookup() ?: run {
                    startScan() // once only; never toggle Wi-Fi or drop a different AP
                    VerifiedWifiWait.await(SCAN_WAIT_MS, now, { true }) { lookup() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
        }

        // Locally administered unicast APs are valid. Reject multicast and placeholder addresses.
        private fun usable(bssid: String): Boolean = bssid.take(2).toInt(16) and 1 == 0
    }
}
