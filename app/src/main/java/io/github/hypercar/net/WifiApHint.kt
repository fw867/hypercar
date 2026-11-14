package io.github.hypercar.net

/** An iAP2 device identifier is not always an AP MAC. Require matching SSID/BSSID scan evidence. */
internal data class WifiApHint(val bssid: String, val frequency: Int, val ageSeconds: Double) {
    companion object {
        private val row = Regex("^\\s*([0-9a-fA-F:]{17})\\s+(\\d+)\\s+(-?\\d+)\\s+([0-9.]+)\\s+(.*?)\\s{2,}\\[.*$")
        fun fromScan(text: String, ssid: String, deviceIdentifierCandidate: String?): WifiApHint? {
            val expected = CarNetworkReuse.mac(deviceIdentifierCandidate) ?: return null
            if (expected in setOf("00:00:00:00:00:00", "02:00:00:00:00:00") || expected.take(2).toInt(16) and 1 != 0) return null
            return text.lineSequence().mapNotNull { line -> row.matchEntire(line)?.groupValues?.let { values ->
                if (values[1].lowercase() != expected || values[5] != ssid) null else {
                    val frequency = values[2].toIntOrNull() ?: return@let null
                    val age = values[4].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@let null
                    WifiApHint(expected, frequency, age)
                }
            } }.minByOrNull { it.ageSeconds }
        }
    }
}
