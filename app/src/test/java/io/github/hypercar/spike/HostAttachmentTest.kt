package io.github.hypercar.spike

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAttachmentTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failedJournalDeliveryGoesIdleUntilDemandAndAnAcknowledgementSuppressesFurtherDeliveries() = runTest {
        val attempts = mutableListOf<Long>()
        var available = false
        val journal = OutputJournalDelivery(backgroundScope, {
            attempts += testScheduler.currentTime
            available
        }, {})
        journal.request()
        journal.request()
        advanceTimeBy(60_000)
        assertTrue(attempts.size in 2..10)
        assertEquals(attempts.distinct(), attempts)
        val beforeIdle = attempts.toList()
        advanceTimeBy(180_000)
        assertEquals(beforeIdle, attempts)
        available = true
        journal.request()
        runCurrent()
        assertEquals(beforeIdle + 240_000L, attempts)
        journal.request()
        advanceTimeBy(60_000)
        assertEquals(beforeIdle + 240_000L, attempts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun disconnectCancelsPendingJournalRetry() = runTest {
        var attempts = 0
        val journal = OutputJournalDelivery(backgroundScope, { attempts++; false }, {})
        journal.request()
        runCurrent()
        journal.close()
        journal.request()
        advanceTimeBy(60_000)
        assertEquals(1, attempts)
    }

    @Test fun installedProfilesUseSeparateShizukuRecordsAndTheOwnerRetainsItsExistingRecord() {
        assertEquals(null, userServiceTag(12_345))
        val work = userServiceTag(1_012_345)
        val secondary = userServiceTag(1_112_345)
        assertTrue(work != null && secondary != null && work != secondary)
        assertEquals(work, userServiceTag(1_054_321))
    }

    @Test fun replacingBrokerOrTicketWhileHostRepliesRequiresAColdBind() = runTest {
        val initial = Triple(Any(), "service", "broker")
        for (replacement in listOf(initial.copy(first = Any()), initial.copy(second = "new-service"),
                initial.copy(third = "new-broker"), null, initial)) {
            var current: Triple<Any, String, String>? = initial
            val confirmed = confirmCurrentHost({ current }) {
                current = replacement
                true
            }
            assertEquals(initial == replacement, confirmed)
        }
    }

    @Test fun legacyContextIsPinnedOnlyAfterVerifyingTheFullUidInTheCallersUser() {
        // HyperCar is installed for user 0 and user 10. Either profile can own its own service.
        val resolved = mutableListOf<Pair<String, Int>>()
        val contexts = ShellHostContext<Int>(null, { it }) { pkg, caller ->
            resolved += pkg to caller
            if (caller >= 1_000_000) 1_012_345 else 12_345
        }
        assertFalse(contexts.authorize(1_054_321))
        assertEquals(null, contexts.context)
        assertTrue(contexts.authorize(1_012_345))
        assertEquals(1_012_345, contexts.context)
        assertFalse(contexts.authorize(12_345))
        assertTrue(contexts.authorize(1_012_345))
        assertEquals(listOf(io.github.hypercar.BuildConfig.APPLICATION_ID to 1_054_321,
            io.github.hypercar.BuildConfig.APPLICATION_ID to 1_012_345), resolved)
    }

    @Test fun constructorSuppliedContextNeverSwitchesProfiles() {
        val contexts = ShellHostContext<Int>(12_345, { it }) { _, _ -> error("unexpected lookup") }
        assertFalse(contexts.authorize(1_012_345))
        assertTrue(contexts.authorize(12_345))
    }

    @Test fun shizukuApplicationForWorkProfileUsesTheProfileForPackageLookups() {
        val workUid = 1_012_345
        val contexts = ShellHostContext(workUid to 0, { it.first }) { _, uid ->
            assertEquals(workUid, uid)
            uid to 10
        }
        assertFalse(contexts.authorize(12_345))
        assertTrue(contexts.authorize(workUid))
        assertEquals(workUid to 10, contexts.context)
    }

    @Test fun coldBindHasOneDeadlineForSdkAndHostReply() = runTest {
        val request = CompletableDeferred<Boolean>()
        val callback = CompletableDeferred<String>()
        assertEquals("绑定超时（12s）", awaitUserServiceBinding(request, callback, 12_000))
        request.complete(true)
        assertEquals("绑定超时（12s）", awaitUserServiceBinding(request, callback, 12_000))
        callback.complete("connected")
        assertEquals("connected", awaitUserServiceBinding(request, callback, 12_000))
    }

    @Test fun aLateHostReplyDoesNotKeepTheConnectionWaiting() = runTest {
        val remote = CompletableDeferred<Boolean>()
        val probe = backgroundScope.async { remote.await() }
        assertFalse(awaitHostAttachment(probe, 750))
        assertTrue(probe.isActive)
        remote.complete(true)
        assertTrue(awaitHostAttachment(probe, 750))
    }
}
