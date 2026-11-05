package io.github.hypercar.carui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionResourceLifecycleTest {
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
