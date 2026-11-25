package io.github.hypercar.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiApRetryTest {
    private val history = WifiApHint("3a:f9:f5:c6:8c:3c", 5745, 1.0, fromMemory = true)

    @Test fun failedCarChannelRequestRetriesTheScannedAp() = runTest {
        val requests = mutableListOf<WifiApRetry.Target>()
        val waits = mutableListOf<Long>()
        val result = WifiApRetry.join(history, 60_000, { true }, { false }, protocolFrequency = 5320,
            now = { testScheduler.currentTime }) { target, budget ->
            requests += target
            waits += budget.associationWaitMs
            if (requests.size == 1) { delay(5_000); null } else "scanned AP"
        }
        assertEquals("scanned AP", result)
        assertEquals(listOf("iap2", "scan"), requests.map { it.source })
        assertEquals(listOf(5320, 5745), requests.map { it.frequency })
        assertEquals(listOf(null, history), requests.map { it.ap })
        assertEquals(listOf(60_000L, 55_000L), waits)
    }

    @Test fun agreeingScanCanPinTheApWithoutRepeatingTheSameChannelRequest() = runTest {
        val requests = mutableListOf<WifiApRetry.Target>()
        WifiApRetry.join<String>(history, 60_000, { true }, { false }, protocolFrequency = history.frequency) { target, _ ->
            requests += target
            null
        }
        assertEquals(listOf("iap2", "full_scan"), requests.map { it.source })
        assertEquals(listOf(history, null), requests.map { it.ap })
    }

    @Test fun unchangedRequestCanReachTheScanFallbackWhenTheCarChannelHasNoProgress() = runTest {
        val now = { testScheduler.currentTime }
        val result = WifiApRetry.join(history, 60_000, { true }, { false }, protocolFrequency = 5320, now = now) { target, budget ->
            if (target.source == "scan") return@join target.frequency
            val callback = VerifiedWifiWait.awaitCallback(budget.associationWaitMs, now, { true },
                poll = { delay(it); false }, hintCheckMillis = budget.hintCheckMillis, keepHint = { false })
            assertEquals(VerifiedWifiWait.CallbackWait.HINT_EXPIRED, callback)
            null
        }
        assertEquals(history.frequency, result)
        assertEquals(12_000L, now())
    }

    @Test fun channelFallbackSharesOneDeadlineAndExhaustedRequestsNeverRestart() = runTest {
        for (firstDuration in listOf(12_000L, 60_000L)) {
            val waits = mutableListOf<Long>()
            val result = WifiApRetry.join<String>(null, 60_000, { true }, { false }, protocolFrequency = 5320,
                now = { testScheduler.currentTime }) { _, budget ->
                waits += budget.associationWaitMs
                if (waits.size == 1) { delay(firstDuration); null } else "full scan"
            }
            assertEquals(if (firstDuration == 60_000L) null else "full scan", result)
            assertEquals(if (firstDuration == 60_000L) listOf(60_000L) else listOf(60_000L, 48_000L), waits)
        }
    }

    @Test fun rejectedHintNeverSubmitsAnotherRequest() = runTest {
        var calls = 0
        assertNull(WifiApRetry.join<String>(null, 60_000, { true }, { false }, protocolFrequency = 5320,
            retryAllowed = { false }) { _, _ -> calls++; null })
        assertEquals(1, calls)
    }

    @Test fun protocolChannelFailureFallsBackWithoutWithdrawingDeliveredAssociation() = runTest {
        for (delivered in listOf(false, true)) {
            val waits = mutableListOf<Long>()
            var associated = false
            val result = WifiApRetry.join<String>(null, 60_000, { true }, { associated }, protocolFrequency = 5320,
                now = { testScheduler.currentTime }) { _, budget ->
                waits += budget.associationWaitMs
                if (waits.size == 1) { associated = delivered; null } else "full scan"
            }
            assertEquals(if (delivered) null else "full scan", result)
            assertEquals(if (delivered) listOf(60_000L) else listOf(60_000L, 60_000L), waits)
        }
    }

    @Test fun disappearedHistoricalApGetsOneUnpinnedFallbackWithinCacheRestoreBudget() = runTest {
        val now = { testScheduler.currentTime }
        val requests = mutableListOf<Pair<WifiApHint?, Long>>()
        val result = WifiApRetry.join(history, 60_000, { true }, { false }, now = now) { target, budget ->
            requests += target.ap to budget.associationWaitMs
            assertEquals(60_000L, budget.deadlineMillis)
            if (target.ap == null) {
                assertNull(budget.hintCheckMillis)
                return@join "new AP"
            }
            val callback = VerifiedWifiWait.awaitCallback(budget.associationWaitMs, now, { true },
                poll = { delay(it); false }, hintCheckMillis = budget.hintCheckMillis, keepHint = { false })
            assertEquals(VerifiedWifiWait.CallbackWait.HINT_EXPIRED, callback)
            null
        }
        assertEquals("new AP", result)
        assertEquals(listOf(history to 60_000L, null to 56_000L), requests)
        assertEquals(4_000L, testScheduler.currentTime)
    }

    @Test fun historicalApAssociatedNearFastDeadlineCanStillWaitForAddresses() = runTest {
        val now = { testScheduler.currentTime }
        var associated = false
        val network = WifiApRetry.join(history, 60_000, { true }, { associated }, now = now) { selected, budget ->
            assertEquals(history, selected.ap)
            assertEquals(4_000L, budget.hintCheckMillis)
            delay(3_500)
            associated = true
            val remaining = VerifiedWifiWait.addressBudget(budget.deadlineMillis - now(), associated)
            VerifiedWifiWait.await(remaining, now, { true }) {
                "same AP".takeIf { now() >= 7_500 }
            }
        }
        assertEquals("same AP", network)
        assertEquals(7_500L, now())
    }

    @Test fun historicalApCanDeliverItsFirstNetworkCallbackAfterFourSeconds() = runTest {
        val now = { testScheduler.currentTime }
        val requests = mutableListOf<WifiApHint?>()
        val result = WifiApRetry.join(history, 60_000, { true }, { false }, now = now) { target, budget ->
            requests += target.ap
            if (target.ap == null) return@join "replacement AP"
            assertEquals("scan", target.source)
            val callback = VerifiedWifiWait.awaitCallback(budget.associationWaitMs, now, { true }, poll = {
                delay(it)
                now() >= 7_000
            }, hintCheckMillis = budget.hintCheckMillis, keepHint = { true })
            "original AP".takeIf { callback == VerifiedWifiWait.CallbackWait.SIGNAL }
        }
        assertEquals("original AP", result)
        assertEquals(listOf(history), requests)
        assertEquals(7_000L, now())
    }

    @Test fun successfulExactJoinAndOrdinaryRequestsNeverCreateExtraRequests() = runTest {
        for (hint in listOf(history, history.copy(fromMemory = false), null)) {
            var calls = 0
            assertEquals("existing", WifiApRetry.join(hint, 60_000, { true }, { false }) { _, budget ->
                calls++
                assertEquals(60_000L, budget.associationWaitMs)
                "existing"
            })
            assertEquals(1, calls)
        }
        var calls = 0
        assertNull(WifiApRetry.join<String>(history.copy(fromMemory = false), 60_000, { true }, { false }) { _, _ ->
            calls++; null
        })
        assertEquals(1, calls)
    }

    @Test fun targetSwitchAndDeliveredAssociationBothPreventRetry() = runTest {
        for (delivered in listOf(false, true)) {
            var current = true
            var associated = false
            var calls = 0
            assertNull(WifiApRetry.join<String>(history, 60_000, { current }, { associated }) { _, _ ->
                calls++
                if (delivered) associated = true else current = false
                null
            })
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationPropagatesWithoutRetryOrUnregisteringLiveRequest() = runTest {
        var calls = 0
        try {
            WifiApRetry.join<String>(history, 60_000, { true }, { false }) { _, _ ->
                calls++
                throw CancellationException("car changed")
            }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun exhaustedFallbackDoesNotLoop() = runTest {
        var calls = 0
        assertNull(WifiApRetry.join<String>(history, 60_000, { true }, { false }) { _, _ -> calls++; null })
        assertEquals(2, calls)
    }

    @Test fun automaticJoinClaimsItsAclOnceButRechecksTargetBeforeFallbackSubmission() {
        var current = true
        var claims = 0
        var submissions = 0
        val admission = WifiApRetry.Admission({ current }) { request ->
            claims++
            if (claims != 1) false else { request(); true }
        }
        assertFalse(admission.isCurrent())
        assertTrue(admission.submit { submissions++ })
        assertTrue(admission.submit { submissions++ })
        assertEquals(1, claims)
        assertEquals(2, submissions)
        current = false
        assertFalse(admission.isCurrent())
        assertFalse(admission.submit { fail("submitted to old car") })
    }

    @Test fun deniedInitialAdmissionDoesNotAuthorizeFallback() {
        val admission = WifiApRetry.Admission({ true }) { false }
        assertFalse(admission.submit { fail("unadmitted request") })
        assertFalse(admission.isCurrent())
    }

    @Test fun retryRejectsLateOldCallbackAndKeepsNewRequestReusable() = runTest {
        val state = WifiRequestAssociation<Any, String>()
        val old = Any()
        val fresh = Any()
        val joined = WifiApRetry.join(history, 60_000, { true }, { state.network() != null }) { target, _ ->
            if (target.ap != null) {
                assertTrue(state.register(old))
                assertSame(old, state.cancelPending(old))
                null
            } else {
                assertTrue(state.register(fresh))
                assertFalse(state.available(old, "old AP"))
                assertTrue(state.available(fresh, "new AP"))
                state.network()
            }
        }
        assertEquals("new AP", joined)
        assertEquals("new AP", state.finish(keepConnected = true) { fail("existing hotspot withdrawn") })
        assertTrue(state.owns("new AP"))
    }
}
