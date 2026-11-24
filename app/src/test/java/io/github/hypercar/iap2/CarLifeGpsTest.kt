package io.github.hypercar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class CarLifeGpsTest {
    @Test fun encodesCarLifeUnitsAndUsesTheCarUtcFields() {
        val now = Instant.parse("2026-11-25T12:00:02Z").toEpochMilli()
        val fix = Iap2VehicleLocation(
            -33.865143, 151.2099, 1000, -1.2, 10.0, 90.0,
            utc = LocalDateTime.of(2026, 11, 25, 12, 0, 1),
            fixQuality = 2, satellitesUsed = 8, hdop = 1.1,
        )
        assertArrayEquals(
            longArrayOf(1, 0, -33_865_143, 151_209_900, -12, 3600, 900,
                2026, 11, 25, 12, 0, 1, 2, 11, 0, 0, 8, 0, 0, 0, 0, 1000, 0, now),
            fix.carLifeGpsFields(now),
        )
    }

    @Test fun dropsInvalidFixesAndPreservesAnUnknownHeading() {
        val now = Instant.parse("2026-11-25T12:00:02Z").toEpochMilli()
        val fix = Iap2VehicleLocation(45.0, 5.0, 1000, speedMetersPerSecond = 10.0)
        assertNull(fix.copy(fixQuality = 0).carLifeGpsFields(now))
        assertNull(fix.copy(latitudeDegrees = 0.0, longitudeDegrees = 0.0).carLifeGpsFields(now))
        assertArrayEquals(
            longArrayOf(1, 0, 45_000_000, 5_000_000, 0, 3600, 0,
                2026, 11, 25, 12, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0, 1000, 0, 0, now),
            fix.carLifeGpsFields(now),
        )
    }
}
