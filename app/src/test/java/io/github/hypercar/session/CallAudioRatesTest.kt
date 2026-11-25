package io.github.hypercar.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioRatesTest {
    @Test fun readyFailureAdvancesFromTheRateThatActuallyOpened() {
        val rates = CallAudioRates(24_000)
        assertEquals(24_000, rates.currentRate)
        rates.advance() // Opening the preferred rate failed before an offer.
        assertEquals(16_000, rates.currentRate)
        assertTrue(rates.retry(2))
        assertEquals(48_000, rates.currentRate)
        assertTrue(rates.retry(2))
        assertEquals(44_100, rates.currentRate)
        assertFalse(rates.retry(2))
        assertNull(rates.currentRate)
    }

    @Test fun streamRetryKeepsRateAndIsIndependentOfRateFallback() {
        val rates = CallAudioRates(16_000)
        assertTrue(rates.retry(1))
        assertEquals(16_000, rates.currentRate)
        assertTrue(rates.retry(2))
        assertEquals(24_000, rates.currentRate)
        assertFalse(rates.retry(1))
        assertEquals(24_000, rates.currentRate)
    }
}
