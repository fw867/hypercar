package io.github.hypercar.iap2

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** CarlifeCarGpsProto fields 1–25 in wire order, including zero values for unknown required fields. */
internal fun Iap2VehicleLocation.carLifeGpsFields(nowMillis: Long): LongArray? {
    if (fixQuality !in 1..6 || latitudeDegrees !in -90.0..90.0 || longitudeDegrees !in -180.0..180.0 ||
        (latitudeDegrees == 0.0 && longitudeDegrees == 0.0)) return null

    val dateTime = utc ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)
    val speed = (speedMetersPerSecond ?: 0.0).takeIf { it in 0.0..120.0 } ?: return null
    val heading = bearingDegrees ?: 0.0
    val radians = Math.toRadians(heading)
    val height = altitudeMeters?.times(10)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
    return longArrayOf(
        1, 0, // antenna state, signal quality
        (latitudeDegrees * 1_000_000).roundToLong(), (longitudeDegrees * 1_000_000).roundToLong(),
        height?.roundToLong() ?: 0, (speed * 360).roundToLong(), (heading * 10).roundToLong(),
        dateTime.year.toLong(), dateTime.monthValue.toLong(), dateTime.dayOfMonth.toLong(),
        dateTime.hour.toLong(), dateTime.minute.toLong(), dateTime.second.toLong(), fixQuality.toLong(),
        ((hdop ?: 0.0) * 10).roundToLong(), 0, 0, satellitesUsed.toLong(), 0, 0, 0,
        (speed * cos(radians) * 100).roundToLong(), (speed * sin(radians) * 100).roundToLong(), 0,
        nowMillis,
    )
}
