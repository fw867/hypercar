package io.github.hypercar.yadea

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YadeaCleanupTest {
    @Test fun delayedStopOnlyMatchesItsOwnSession() {
        val old = YadeaStopTicket()
        val current = YadeaStopTicket()
        assertTrue(old.accepts(old.id))
        assertFalse(current.accepts(old.id))
        assertFalse(current.accepts(null))
        assertTrue(current.accepts(current.id))
    }

    @Test fun delayedCleanupAndCallerShareTheFirstCloseDeadline() {
        var now = 100L
        val deadline = YadeaCleanupDeadline(6_000) { now }
        assertFalse(deadline.started)
        assertTrue(deadline.start())
        now += 4_750
        assertFalse(deadline.start())
        assertEquals(1_250L, deadline.remainingMillis())
        now += 1_250
        assertEquals(0L, deadline.remainingMillis())
        now += 300
        assertEquals(0L, deadline.remainingMillis())
    }

    @Test fun idleBeforeLateConnectSuccessDoesNotCompleteCleanup() {
        val cleanup = YadeaPendingConnectCleanup()
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = null, NONE))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = null, NONE))
        assertEquals(CANCEL, cleanup.afterGroupCheck(accepted = true, NONE))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = true, PENDING))
        assertEquals(OBSERVE_IDLE, cleanup.afterGroupCheck(accepted = true, ACCEPTED))
    }

    @Test fun rejectedConnectNeverCancelsAnUnrelatedNegotiation() {
        val cleanup = YadeaPendingConnectCleanup()
        assertEquals(DONE, cleanup.afterGroupCheck(accepted = false, NONE))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = false, PENDING))
    }

    @Test fun removingTheOwnedGroupBeforeConnectReplyDoesNotSendAnotherCancel() {
        val cleanup = YadeaPendingConnectCleanup()
        cleanup.recordMatchingGroupGone()
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = null, NONE))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = true, PENDING))
        assertEquals(OBSERVE_IDLE, cleanup.afterGroupCheck(accepted = true, NONE))
    }

    @Test fun rejectedCancellationAllowsOneRetryWithoutCheckingIdleFirst() {
        val cleanup = YadeaPendingConnectCleanup()
        val rejected = YadeaPendingConnectCleanup.Cancellation.Rejected
        assertEquals(CANCEL, cleanup.afterGroupCheck(accepted = true, NONE))
        assertEquals(CANCEL, cleanup.afterGroupCheck(accepted = true, rejected))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = true, rejected))
    }

    @Test fun missingGroupReplyCannotAuthorizeCancellationOrProveIdle() {
        val cleanup = YadeaPendingConnectCleanup()
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = null, NONE, UNKNOWN))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = true, NONE, UNKNOWN))
        assertEquals(CANCEL, cleanup.afterGroupCheck(accepted = true, NONE))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = true, ACCEPTED, UNKNOWN))
        assertEquals(OBSERVE_IDLE, cleanup.afterGroupCheck(accepted = true, ACCEPTED))
    }

    @Test fun foreignGroupCannotCompleteOrCancelAnUnresolvedConnection() {
        val cleanup = YadeaPendingConnectCleanup()
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = null, NONE))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = null, NONE, FOREIGN))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = true, NONE, UNKNOWN))
        assertEquals(WAIT, cleanup.afterGroupCheck(accepted = true, NONE, FOREIGN))
        assertEquals(CANCEL, cleanup.afterGroupCheck(accepted = true, NONE))
    }

    @Test fun actionReplyDirectlyWakesCleanupWithoutABroadcast() = runTest {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val reply = YadeaP2pActionResult(changes)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { changes.receive() }
        assertFalse(waiting.isCompleted)
        reply.complete(-1)
        waiting.await()
        assertEquals(-1, reply.result.await())
        assertTrue(changes.tryReceive().isFailure)
        reply.complete(0)
        assertTrue(changes.tryReceive().isFailure)
    }

    @Test fun carPlayHandoffWaitsForDismissedScannerEvenWithoutProjectionSession() = runTest {
        val scanner = Any()
        val stopStarted = CompletableDeferred<Any>()
        val stopResult = CompletableDeferred<Boolean>()
        val handoff = YadeaScanHandoff<Any> { owner ->
            stopStarted.complete(owner)
            stopResult.await()
        }
        handoff.track(scanner)
        val switching = async { handoff.closeCurrent() }
        assertEquals(scanner, stopStarted.await())
        assertFalse(switching.isCompleted)
        stopResult.complete(false)
        assertFalse(switching.await())
        assertTrue(handoff.closeCurrent())
    }

    private companion object {
        val CANCEL = YadeaPendingConnectCleanup.Action.Cancel
        val WAIT = YadeaPendingConnectCleanup.Action.Wait
        val DONE = YadeaPendingConnectCleanup.Action.Done
        val OBSERVE_IDLE = YadeaPendingConnectCleanup.Action.ObserveIdle
        val NONE = YadeaPendingConnectCleanup.Cancellation.None
        val PENDING = YadeaPendingConnectCleanup.Cancellation.Pending
        val ACCEPTED = YadeaPendingConnectCleanup.Cancellation.Accepted
        val UNKNOWN = YadeaPendingConnectCleanup.Group.Unknown
        val FOREIGN = YadeaPendingConnectCleanup.Group.Foreign
    }
}
