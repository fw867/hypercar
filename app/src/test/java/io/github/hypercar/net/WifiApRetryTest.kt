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

    @Test fun disappearedHistoricalApGetsOneUnpinnedFallbackWithinCacheRestoreBudget() = runTest {
        val requests = mutableListOf<Pair<WifiApHint?, Long>>()
        val result = WifiApRetry.join(history, 60_000, { true }, { false }) { hint, wait ->
            requests += hint to wait
            if (hint != null) { delay(wait); null } else "new AP"
        }
        assertEquals("new AP", result)
        assertEquals(listOf(history to 4_000L, null to 60_000L), requests)
        assertEquals(4_000L, testScheduler.currentTime)
    }

    @Test fun successfulExactJoinAndOrdinaryRequestsNeverCreateExtraRequests() = runTest {
        for (hint in listOf(history, history.copy(fromMemory = false), null)) {
            var calls = 0
            assertEquals("existing", WifiApRetry.join(hint, 60_000, { true }, { false }) { _, wait ->
                calls++
                assertEquals(if (hint?.fromMemory == true) 4_000L else 60_000L, wait)
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
        val joined = WifiApRetry.join(history, 60_000, { true }, { state.network() != null }) { hint, _ ->
            if (hint != null) {
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
