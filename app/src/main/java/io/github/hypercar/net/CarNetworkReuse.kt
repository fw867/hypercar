package io.github.hypercar.net

/** Fresh link evidence, rather than a remembered SSID/Network handle, authorizes reuse. */
internal object CarNetworkReuse {
    data class Evidence(
        val ssid: String?, val bssid: String?, val hasAddress: Boolean,
        val routeToPeer: Boolean, val pinned: Boolean,
        val ownedRequest: Boolean = false,
    )
    private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    fun mac(value: String?): String? {
        if (value == null || value.length != 17) return null
        if (value.indices.any { if (it % 3 == 2) value[it] != ':' else !hex(value[it]) }) return null
        return value.lowercase(java.util.Locale.ROOT).takeUnless {
            it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00"
        }
    }

    /** Parse only labeled values outside quoted SSIDs; no platform regex dependency. */
    fun parseBssid(status: String): String? {
        var quote: Char? = null
        val matches = linkedSetOf<String>()
        for (i in status.indices) {
            val c = status[i]
            if (quote != null) {
                if (c == quote && (i == 0 || status[i - 1] != '\\')) quote = null
                continue
            }
            if (c == '"' || c == '\'') { quote = c; continue }
            if (!status.regionMatches(i, "BSSID", 0, 5, ignoreCase = true) ||
                (i > 0 && (status[i - 1].isLetterOrDigit() || status[i - 1] == '_'))) continue
            var at = i + 5
            while (at < status.length && status[at].isWhitespace()) at++
            if (at >= status.length || status[at] !in ":=") continue
            at++
            while (at < status.length && status[at].isWhitespace()) at++
            val valueQuote = status.getOrNull(at)?.takeIf { it == '"' || it == '\'' }
            if (valueQuote != null) at++
            if (status.length - at < 17) continue
            val value = mac(status.substring(at, at + 17)) ?: continue
            val after = status.getOrNull(at + 17)
            if (valueQuote != null) {
                if (after != valueQuote) continue
            } else if (after != null && !after.isWhitespace() && after !in ",;)]}") continue
            matches.add(value)
        }
        return matches.singleOrNull()
    }

    /** Shell metadata is optional. Preserve cancellation; failures fall back to normal joining. */
    inline fun readBssid(query: () -> String): String? = try {
        parseBssid(query())
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }

    /** apBssid must be an independently verified AP address, never an iAP2 deviceIdentifier. */
    fun accepts(ssid: String?, apBssid: String?, invited: Boolean, link: Evidence): Boolean {
        if (!link.hasAddress) return false
        if (!ssid.isNullOrBlank()) {
            if (link.ssid != ssid && !link.ownedRequest) return false
        } else if (!link.pinned && !invited) return false
        val expected = mac(apBssid)
        if (apBssid != null && expected == null) return false
        if (expected != null && mac(link.bssid) != expected) return false
        // A matched BSSID can identify the hotspot before iAP2 supplies endpoint addresses.
        return link.routeToPeer || (link.ownedRequest && !ssid.isNullOrBlank()) ||
            (link.pinned && !ssid.isNullOrBlank()) || expected != null
    }
}
