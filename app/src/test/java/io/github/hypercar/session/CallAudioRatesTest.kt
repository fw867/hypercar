package io.github.hypercar.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioRatesTest {
    @Test fun reconnectWaitsForOldPhoneAudioEndpointToClose() = runTest {
        val allowOldEndpointClose = CompletableDeferred<Unit>()
        var oldEndpointOpen = false
        var replacementStarted = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            CarWithCallAudio.withArmedMonitor({ true }) {
                oldEndpointOpen = true
                allowOldEndpointClose.await()
                oldEndpointOpen = false
            }
        }
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            CarWithCallAudio.withArmedMonitor({ true }) {
                assertFalse(oldEndpointOpen)
                replacementStarted = true
            }
        }
        assertFalse(replacementStarted)
        allowOldEndpointClose.complete(Unit)
        replacement.join()
        assertTrue(replacementStarted)
    }

    @Test fun replacementRetiredWhileWaitingDoesNotOpenPhoneAudio() = runTest {
        val allowOldEndpointClose = CompletableDeferred<Unit>()
        var replacementCurrent = true
        var replacementStarted = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            CarWithCallAudio.withArmedMonitor({ true }) { allowOldEndpointClose.await() }
        }
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            CarWithCallAudio.withArmedMonitor({ replacementCurrent }) { replacementStarted = true }
        }
        replacementCurrent = false
        allowOldEndpointClose.complete(Unit)
        replacement.join()
        assertFalse(replacementStarted)
    }

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
