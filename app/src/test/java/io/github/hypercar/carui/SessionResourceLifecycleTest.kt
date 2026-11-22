package io.github.hypercar.carui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionResourceLifecycleTest {
    @Test fun restartReplacesDisplayUnderTheSameSessionTicket() = runTest {
        val lifecycle = SessionResourceLifecycle()
        val ticket = lifecycle.ticket()
        var display = 0
        val cleanup: suspend () -> Unit = { display = 0 }
        lifecycle.start(ticket, { true }, cleanup) { display = 17 }
        lifecycle.restart(ticket, { true }, cleanup) {
            assertEquals(0, display)
            display = 18
        }
        assertEquals(18, display)
        assertTrue(lifecycle.isCurrent(ticket))
    }

    @Test fun retiredSessionCannotFinishDisplayRestartAfterCleanupSuspends() = runTest {
        val lifecycle = SessionResourceLifecycle()
        val ticket = lifecycle.ticket()
        var display = 0
        val detached = CompletableDeferred<Unit>()
        lifecycle.start(ticket, { true }, {}) { display = 17 }
        val recovery = async {
            lifecycle.restart(ticket, { true }, { detached.await(); display = 0 }) { display = 18 }
        }
        runCurrent()
        lifecycle.retire()
        detached.complete(Unit)
        assertNull(recovery.await())
        assertEquals(0, display)
    }

    @Test fun reconnectWaitsForMapSurfaceDetachmentBeforeStartingNewEncoder() = runTest {
        val lifecycle = SessionResourceLifecycle()
        lifecycle.start(lifecycle.ticket(), { true }, {}) { Unit }
        val detached = CompletableDeferred<Unit>()
        val retired = lifecycle.retire()
        val stop = async { lifecycle.stop(retired) { detached.await() } }
        runCurrent()
        var newEncoderStarted = false
        val reconnect = async { lifecycle.start(lifecycle.ticket(), { true }, {}) { newEncoderStarted = true } }
        runCurrent()
        assertFalse(newEncoderStarted)
        detached.complete(Unit)
        stop.await(); reconnect.await()
        assertTrue(newEncoderStarted)
    }

    @Test fun stoppingDuringCreationDiscardsPartialResourcesBeforeNextStart() = runTest {
        val lifecycle = SessionResourceLifecycle()
        val release = CompletableDeferred<Unit>()
        var live = false
        val start = async { lifecycle.start(lifecycle.ticket(), { true }, { live = false }) {
            live = true; release.await(); "old"
        } }
        runCurrent(); assertTrue(live)
        val retired = lifecycle.retire()
        val stop = async { lifecycle.stop(retired) { live = false } }
        release.complete(Unit)
        assertNull(start.await()); stop.await(); assertFalse(live)
        assertEquals("new", lifecycle.start(lifecycle.ticket(), { true }, { live = false }) { live = true; "new" })
        assertTrue(live)
    }

    @Test fun delayedOldStopNeverDestroysNewlyStartedResources() = runTest {
        val lifecycle = SessionResourceLifecycle()
        var live = ""
        lifecycle.start(lifecycle.ticket(), { true }, { live = "" }) { live = "old" }
        val retired = lifecycle.retire()
        lifecycle.start(lifecycle.ticket(), { true }, { live = "" }) { live = "new" }
        lifecycle.stop(retired) { live = "" }
        assertEquals("new", live)
    }

    @Test fun queuedStartFromOldGenerationDoesNotCreateResources() = runTest {
        val lifecycle = SessionResourceLifecycle()
        val old = lifecycle.ticket()
        lifecycle.retire()
        var created = false
        assertNull(lifecycle.start(old, { true }, {}) { created = true })
        assertFalse(created)
    }

    @Test fun staleConnectionAttemptDoesNotStartEvenWithCurrentTicket() = runTest {
        val lifecycle = SessionResourceLifecycle()
        var created = false
        assertNull(lifecycle.start(lifecycle.ticket(), { false }, {}) { created = true })
        assertFalse(created)
    }

    @Test fun failedCreationReleasesPartialAllocation() = runTest {
        val lifecycle = SessionResourceLifecycle()
        var live = false
        try {
            lifecycle.start(lifecycle.ticket(), { true }, { live = false }) {
                live = true; error("encoder startup failed")
            }
            fail("failure expected")
        } catch (_: IllegalStateException) { }
        assertFalse(live)
    }

    @Test fun cancellationReleasesPartialAllocation() = runTest {
        val lifecycle = SessionResourceLifecycle()
        var live = false
        val pending = async { lifecycle.start(lifecycle.ticket(), { true }, { live = false }) {
            live = true; CompletableDeferred<Unit>().await()
        } }
        runCurrent(); assertTrue(live)
        pending.cancel(); runCurrent()
        assertFalse(live)
    }
}
