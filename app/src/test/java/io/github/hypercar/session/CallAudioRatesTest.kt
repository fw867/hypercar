package io.github.hypercar.session

import io.github.hypercar.spike.CallAudioOfferEnd

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
    @Test fun phoneCallYieldsOnlyToActiveScoOnTheSessionCar() {
        val car = "12:34:56:78:9A:BC"
        val headphones = "12:34:56:78:9A:BD"
        var scoPeer: String? = headphones
        val hasSco: (String) -> Boolean = { it == scoPeer }
        assertFalse(CarWithCallAudio.usesCarHfp(true, car, hasSco))
        scoPeer = car
        assertTrue(CarWithCallAudio.usesCarHfp(true, car, hasSco))
        assertFalse(CarWithCallAudio.usesCarHfp(false, car, hasSco))
        assertFalse(CarWithCallAudio.usesCarHfp(true, null, hasSco))
        scoPeer = null
        assertFalse(CarWithCallAudio.usesCarHfp(true, car, hasSco))
    }

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

    @Test fun scoDisconnectionReopensExhaustedAirPlayRatesOnlyOnceForTheSameCall() {
        val rates = CallAudioRates(24_000)
        repeat(4) { rates.advance() }
        assertNull(rates.currentRate)
        assertTrue(rates.retryAfterHfp())
        assertEquals(24_000, rates.currentRate)
        repeat(4) { rates.advance() }
        assertFalse(rates.retryAfterHfp())
        assertNull(rates.currentRate)
    }

    @Test fun terminalRejectionRacingScoStillUsesTheOnlyHfpReturnRetry() {
        val rates = CallAudioRates(24_000)
        rates.completeOffer(3, withdrawn = true)
        assertFalse(rates.canOffer)
        rates.resumeAfterHfp()
        assertTrue(rates.canOffer)

        rates.completeOffer(3, withdrawn = false)
        rates.resumeAfterHfp()
        assertFalse(rates.canOffer)
    }

    @Test fun offerWithdrawnByScoDoesNotConsumeTheCurrentPhoneRate() {
        val rates = CallAudioRates(24_000)
        rates.completeOffer(4, withdrawn = true)
        rates.resumeAfterHfp()
        assertTrue(rates.canOffer)
        assertEquals(24_000, rates.currentRate)
    }

    @Test fun unexpectedEndBeforeSetupCanRetrySameRateOnlyOnce() {
        val rates = CallAudioRates(24_000)
        rates.completeOffer(4, withdrawn = false)
        assertTrue(rates.canOffer)
        assertEquals(24_000, rates.currentRate)
        rates.completeOffer(4, withdrawn = false)
        assertFalse(rates.canOffer)
    }

    @Test fun scopedIdentityRevocationDoesNotSpendStreamRetryBeforeMonitorObservesNewLease() {
        val rates = CallAudioRates(24_000)
        for (endpoint in listOf("identity_revoked_precise_state_changed", "identity_lost_telecom_query_unavailable",
            "call_no_longer_eligible")) {
            val end = CallAudioOfferEnd.fromStatus("identity=telecom_confirmed endpoint=$endpoint")
            rates.completeOffer(1, withdrawn = false, end)
            assertTrue(rates.canOffer)
        }
        val failure = CallAudioOfferEnd.fromStatus("identity=telecom_confirmed endpoint=downlink_read_-3")
        rates.completeOffer(1, withdrawn = false, failure)
        assertTrue(rates.canOffer)
        rates.completeOffer(1, withdrawn = false, failure)
        assertFalse(rates.canOffer)
    }
}
