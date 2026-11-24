package io.github.hypercar.iap2

/** A vehicle GNSS fix for in-process consumers. Received time uses the Wi-Fi worker's monotonic clock. */
data class Iap2VehicleLocation(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val receivedAtMillis: Long,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
) {
    override fun toString(): String = "Iap2VehicleLocation(receivedAtMillis=$receivedAtMillis)"
}

/** Owned by one authenticated Wi-Fi control link. Raw NMEA never leaves this class. */
internal class Iap2VehicleLocationSession(private val onLocation: (Iap2VehicleLocation?) -> Unit) {
    private var requested = emptySet<Int>()
    private var fix: Iap2VehicleLocation? = null
    private var fixTime: String? = null
    val hasFix: Boolean get() = fix != null

    fun subscribe(peer: Iap2Messages.Identification): List<Iap2Csm.Frame> {
        val next = peer.locationNmeaTypes
        if (next == requested) return emptyList()
        return buildList {
            if (requested.isNotEmpty()) add(Iap2Csm.Frame(Iap2MessageIds.STOP_LOCATION_INFORMATION, byteArrayOf()))
            reset()
            requested = next
            if (next.isNotEmpty()) {
                val request = next.sorted().fold(byteArrayOf()) { body, id -> body + Iap2Csm.param(id, byteArrayOf()) }
                add(Iap2Csm.Frame(Iap2MessageIds.START_LOCATION_INFORMATION, request))
            }
        }
    }

    fun receive(body: ByteArray, now: Long) {
        if (requested.isEmpty() || body.size !in 1..MAX_BODY_BYTES) return
        val params = Iap2Csm.parseParams(body)
        if (params.sumOf { it.value.size + Iap2Csm.PARAM_HEADER_BYTES } != body.size) return
        // xiap2 0x60e0c..0x60e98 iterates over repeated parameter-0 C strings.
        for ((id, value) in params) {
            if (id != 0 || value.size !in 2..MAX_PARAM_BYTES || value.last() != 0.toByte()) continue
            if (value.dropLast(1).any { it.toInt() !in 32..126 && it.toInt() != 13 && it.toInt() != 10 }) continue
            val nmea = value.decodeToString(endIndex = value.size - 1)
            for (sentence in nmea.lineSequence()) receiveSentence(sentence, now)
        }
    }

    fun expire(now: Long) {
        val previous = fix ?: return
        if (now - previous.receivedAtMillis >= FIX_LIFETIME_MILLIS) clearFix()
    }

    fun reset() {
        requested = emptySet()
        clearFix()
    }

    private fun receiveSentence(sentence: String, now: Long) {
        if (sentence.length !in 9..MAX_SENTENCE_LENGTH || sentence[0] != '$') return
        val star = sentence.lastIndexOf('*')
        if (star != sentence.length - 3) return
        val checksum = sentence.substring(star + 1).toIntOrNull(16) ?: return
        val payload = sentence.substring(1, star)
        if (payload.fold(0) { total, char -> total xor char.code } != checksum) return
        val fields = payload.split(',')
        val header = fields[0]
        if (header.length != 5 || header.take(2) !in GPS_TALKERS) return
        val kind = when (header.substring(2)) { "GGA" -> 1; "RMC" -> 2; else -> return }
        if (kind !in requested || fields.size < 10) return
        val gga = kind == 1
        val utc = fields[1].takeIf(UTC_TIME::matches) ?: return
        val quality = if (gga) fields[6].toIntOrNull() ?: return else null
        if (!gga && fields[2] !in setOf("A", "V")) return
        if (quality == 0 || !gga && (fields[2] == "V" || fields.getOrNull(12) in setOf("N", "S", "M"))) {
            clearFix()
            return
        }
        if (quality != null && quality !in 1..6) return
        val start = if (gga) 2 else 3
        val lat = coordinate(fields[start], fields[start + 1], 2, "N", "S", 90) ?: return
        val lon = coordinate(fields[start + 2], fields[start + 3], 3, "E", "W", 180) ?: return
        val previous = fix.takeIf { fixTime == utc }
        val altitude = if (gga && fields.getOrNull(10) == "M") finiteNumber(fields[9]) else previous?.altitudeMeters
        val speed = if (gga) previous?.speedMetersPerSecond else finiteNumber(fields[7])
            ?.takeIf { it >= 0 }?.times(1852.0 / 3600.0)
        val course = if (gga) previous?.bearingDegrees else finiteNumber(fields[8])?.takeIf { it >= 0 && it < 360 }
        fixTime = utc
        val next = Iap2VehicleLocation(lat, lon, now, altitude, speed, course)
        fix = next
        onLocation(next)
    }

    private fun clearFix() {
        fixTime = null
        if (fix == null) return
        fix = null
        onLocation(null)
    }

    private fun coordinate(value: String, direction: String, digits: Int, positive: String, negative: String, max: Int): Double? {
        val whole = value.substringBefore('.')
        val decimals = value.substringAfter('.', "")
        if (whole.length != digits + 2 || !whole.all { it in '0'..'9' } || !decimals.all { it in '0'..'9' }) return null
        val degrees = whole.take(digits).toInt()
        val minutes = value.drop(digits).toDoubleOrNull() ?: return null
        if (minutes >= 60 || degrees > max || degrees == max && minutes > 0) return null
        val coordinate = degrees + minutes / 60
        return when (direction) { positive -> coordinate; negative -> -coordinate; else -> null }
    }

    private fun finiteNumber(value: String): Double? = value.takeIf(DECIMAL::matches)?.toDoubleOrNull()?.takeIf(Double::isFinite)

    private companion object {
        val UTC_TIME = Regex("(?:[01][0-9]|2[0-3])[0-5][0-9](?:[0-5][0-9]|60)(?:\\.[0-9]+)?")
        val DECIMAL = Regex("-?[0-9]+(?:\\.[0-9]+)?")
        val GPS_TALKERS = setOf("GP", "GN", "GA", "GB", "BD", "GL", "GQ")
        const val MAX_BODY_BYTES = 4096
        const val MAX_PARAM_BYTES = 1024
        const val MAX_SENTENCE_LENGTH = 160
        const val FIX_LIFETIME_MILLIS = 10_000
    }
}
