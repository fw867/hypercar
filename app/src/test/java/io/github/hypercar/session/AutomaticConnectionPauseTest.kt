package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class AutomaticConnectionPauseTest {
    private val pause = AutomaticConnectionPause.start(7, 100_000, 1_800_000_000_000)

    @Test fun fullTenMinutesAndExactExpiry() {
        assertEquals(600_000L, pause.remaining(7, 100_000, 1_800_000_000_000))
        assertEquals(1L, pause.remaining(7, 699_999, 1_800_000_599_999))
        assertEquals(0L, pause.remaining(7, 700_000, 1_800_000_600_000))
        assertEquals(0L, pause.remaining(7, 900_000, 1_800_000_800_000))
    }

    @Test fun processRestartRestoresOriginalDeadline() {
        val restored = AutomaticConnectionPause.decode(pause.encode())!!
        assertEquals(pause, restored)
        assertEquals(420_000L, restored.remaining(7, 280_000, 1_800_000_180_000))
    }

    @Test fun wallClockChangesDoNotShortenOrExtendSameBootPause() {
        assertEquals(500_000L, pause.remaining(7, 200_000, 1_900_000_000_000))
        assertEquals(500_000L, pause.remaining(7, 200_000, 1_700_000_000_000))
    }

    @Test fun rebootUsesWallDeadlineInsteadOfPreviousBootUptime() {
        assertEquals(200_000L, pause.remaining(8, 1_000, 1_800_000_400_000))
        assertEquals(0L, pause.remaining(8, 1_000, 1_800_000_700_000))
        assertEquals(200_000L, pause.remaining(-1, 1_000, 1_800_000_400_000))
    }

    @Test fun corruptedOrMissingRecordIsIgnored() {
        for (value in listOf("", "x|2|3", "1|x|3", "1|2|x", "1|2|3|4", "1|-2|3")) {
            assertNull(value, AutomaticConnectionPause.decode(value))
        }
    }

    @Test fun repeatedManualStopStartsANewTenMinuteWindow() {
        val next = AutomaticConnectionPause.start(7, 300_000, 1_800_000_200_000)
        assertEquals(600_000L, next.remaining(7, 300_000, 1_800_000_200_000))
    }
}
