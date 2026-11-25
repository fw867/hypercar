package io.github.hypercar.net

import io.github.hypercar.session.PairingEvidence
import java.util.Base64

/** Historical AP information is a scan preference, never authority to connect on its own. */
internal object CarWifiApMemory {
    fun key(receiver: String?, ssid: String): String? {
        val car = PairingEvidence.receiverKey(receiver) ?: return null
        if (ssid.isBlank()) return null
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(ssid.toByteArray(Charsets.UTF_8))
        return "car_hotspot_ap.$car.$name"
    }

    fun normalize(bssid: String?): String? = WifiStationIdentity.normalize(bssid)

    /** Shell WifiInfo can describe another STA: match the successful Network's local address. */
    fun fromStatus(status: String, localAddresses: Set<String>): String? {
        if (localAddresses.isEmpty()) return null
        val quoted = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val ip = Regex("(?:^|,\\s*)IP:\\s*/?([^,\\s]+)(?=,|$)")
        val completed = Regex("(?:^|,\\s*)Supplicant state:\\s*COMPLETED(?=,|$)")
        return status.lineSequence().filter { it.startsWith("WifiInfo: ") }.mapNotNull { line ->
            val fields = quoted.replace(line.removePrefix("WifiInfo: "), "")
            val address = ip.findAll(fields).toList().singleOrNull()?.groupValues?.get(1)
            if (address !in localAddresses || !completed.containsMatchIn(fields)) null
            else CarNetworkReuse.parseBssid(line)?.let(::normalize)
        }.toList().singleOrNull()
    }
}
