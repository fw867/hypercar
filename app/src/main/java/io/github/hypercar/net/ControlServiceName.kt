package io.github.hypercar.net

/** DNS-SD instance labels are at most 63 UTF-8 bytes; never split a code point. */
internal object ControlServiceName {
    fun forProfile(profileId: String, phoneName: String): String {
        if (profileId != "bmw_id85") return "sender"
        val clean = phoneName.filterNot { it.isISOControl() }.trim().ifEmpty { "HyperCar" }
        val result = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < clean.length) {
            val point = clean.codePointAt(offset)
            val part = String(Character.toChars(point))
            val size = part.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 63) break
            result.append(part)
            bytes += size
            offset += Character.charCount(point)
        }
        return result.toString()
    }
}
