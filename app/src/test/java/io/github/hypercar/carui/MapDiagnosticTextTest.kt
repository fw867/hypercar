package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class MapDiagnosticTextTest {
    @Test fun cachedFixAgeAndAccuracyAreWhitelistedAndCoordinatesAreNeverReturned() {
        val text = """
            Location Manager State:
                gps provider:
                  listeners:
                    com.autonavi.minimap Request[@+1s0ms HIGH_ACCURACY]
                  user 0:
                    last location=Location[gps 31.123456,120.765432 hAcc=4.5 et=+1d2h3m4s500ms alt=8.1]
                    enabled=true
                  mStarted=true
                  GNSS_KPI_START
        """.trimIndent().replace("gps provider:", "    gps provider:")
        val result = MapDiagnosticText.location(text, "com.autonavi.minimap", 93_784_500 + 60_000L)
        assertTrue(result, result.contains("accuracyM=4.5"))
        assertTrue(result, result.contains("fixAgeS=60"))
        assertTrue(result, result.contains("mapHighAccuracyRequest=true"))
        assertFalse(result.contains("31.123456"))
        assertFalse(result.contains("120.765432"))
        assertFalse(result.contains("alt="))
    }
    @Test fun missingProviderOrFutureTimestampIsUnknownRatherThanFreshFix() {
        assertTrue(MapDiagnosticText.location("permission denied", "map", 1).contains("fixAgeS=unknown"))
        val text="    gps provider:\nlast location=Location[gps 1,2 hAcc=3 et=+10s ]"
        assertTrue(MapDiagnosticText.location(text, "map", 1).contains("fixAgeS=unknown"))
    }
    @Test fun systemDefaultDoesNotConfuseProcessBindingOrExposeNetworkDump() {
        assertEquals("166", MapDiagnosticText.systemDefault("Active default network: 166\nSSID: private-name"))
        assertEquals("none", MapDiagnosticText.systemDefault("Active default network: none"))
        assertEquals("unknown", MapDiagnosticText.systemDefault("processBound=165 DNS=secret"))
    }
}
