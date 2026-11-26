package io.github.hypercar.spike

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallAudioTest {
    @Test fun disconnectOrReplacementStopsAnEndpointBeforeItsNextWrite() {
        for (disconnect in listOf(true, false)) {
            var elapsed = 0L
            var foreground = "TC@4"
            val identity = ShellCallIdentity({ elapsed }, { TelecomCallSnapshot.Observed(foreground) })
            val generation = identity.beginTracking()
            identity.trackSubscriptions(generation, setOf(1))
            identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
            identity.trackingReady(generation)
            val lease = checkNotNull(identity.current())
            val endpoint = ShellCallEndpoint(identity, lease)
            val writer = Executors.newSingleThreadExecutor()
            val checked = CountDownLatch(1)
            val readyToWrite = CountDownLatch(1)
            try {
                val pendingWrite = writer.submit(Callable {
                    check(endpoint.isLive() && identity.matchesCached(lease))
                    checked.countDown()
                    check(readyToWrite.await(5, TimeUnit.SECONDS))
                    endpoint.runAudioWhileAuthorized { 1 }
                })
                assertTrue(checked.await(5, TimeUnit.SECONDS))
                if (disconnect) {
                    identity.onDisconnected(generation, 1)
                } else {
                    elapsed = 200
                    foreground = "TC@9"
                    assertTrue(identity.current() != lease)
                }
                readyToWrite.countDown()
                assertNull(pendingWrite.get(5, TimeUnit.SECONDS))
            } finally {
                writer.shutdownNow()
            }
        }
    }

    @Test fun retiredOfferStopsWritesEvenWhenTheCallLeaseIsStillCurrent() {
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val endpoint = ShellCallEndpoint(identity, lease)
        assertEquals(1, endpoint.runWhileAuthorized { 1 })
        assertTrue(endpoint.retire())
        assertTrue(identity.matchesCached(lease))
        assertNull(endpoint.runWhileAuthorized { error("retired offer wrote PCM") })
    }

    @Test fun revocationCanCloseTransportWhileAnAdmittedAudioOperationIsStillInFlight() {
        lateinit var endpoint: ShellCallEndpoint
        val transportClosed = CountDownLatch(1)
        val identity = ShellCallIdentity({ 0L }, { TelecomCallSnapshot.Observed("TC@4") }, {
            endpoint.retire { transportClosed.countDown() }
        })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        endpoint = ShellCallEndpoint(identity, lease)
        val workers = Executors.newFixedThreadPool(2)
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        try {
            val operation = workers.submit(Callable {
                endpoint.runAudioWhileAuthorized {
                    writing.countDown()
                    check(finishWrite.await(5, TimeUnit.SECONDS))
                    1
                }
            })
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val revoked = workers.submit(Callable { identity.onDisconnected(generation, 1) })
            assertTrue(transportClosed.await(5, TimeUnit.SECONDS))
            assertFalse(revoked.isDone)
            assertNull(endpoint.runWhileAuthorized { error("retired offer sent downlink") })
            finishWrite.countDown()
            assertEquals(1, operation.get(5, TimeUnit.SECONDS))
            assertTrue(revoked.get(2, TimeUnit.SECONDS))
            assertNull(endpoint.runAudioWhileAuthorized { error("retired offer wrote uplink") })
        } finally {
            finishWrite.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun aPendingRetryOnlyAllowsAudioForAnAlreadyStartedEndpoint() {
        var elapsed = 0L
        var result: TelecomCallSnapshot = TelecomCallSnapshot.Observed("TC@4")
        val identity = ShellCallIdentity({ elapsed }, { result })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val endpoint = ShellCallEndpoint(identity, lease)

        elapsed = 200
        result = TelecomCallSnapshot.Unavailable()
        assertEquals(lease, identity.current())
        assertEquals(1, endpoint.runAudioWhileAuthorized { 1 })
        assertNull(endpoint.runAudioWhileAuthorized(startBefore = 500) { error("unverified endpoint started") })

        elapsed = 280
        result = TelecomCallSnapshot.Observed("TC@9")
        assertTrue(identity.current() != lease)
        assertNull(endpoint.runAudioWhileAuthorized { error("old endpoint wrote PCM after the retry") })
    }

    @Test fun startupProofCanExpireBeforeAPlatformAudioCallAcquiresTheGate() {
        var elapsed = 0L
        val identity = ShellCallIdentity({ elapsed }, { TelecomCallSnapshot.Observed("TC@4") })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(0, 1, 0))
        identity.trackingReady(generation)
        val lease = checkNotNull(identity.current())
        val endpoint = ShellCallEndpoint(identity, lease)

        elapsed = 199
        var started = false
        assertFalse(identity.confirmsStart(lease, authorize = { deadline ->
            elapsed = deadline
            endpoint.runAudioWhileAuthorized(startBefore = deadline) { started = true } != null
        }))
        assertFalse(started)
        assertTrue(identity.matchesCached(lease))
    }

    @Test fun scopedDiagnosticsDoNotWaitForTheEndpointLifecycleMonitor() {
        val audio = ShellCallAudio(ShellCallIdentity(elapsed = { 0L }))
        val binderThread = Executors.newSingleThreadExecutor()
        try {
            synchronized(audio) {
                val status = binderThread.submit(Callable { audio.diagnosticStatus(1, 1) })
                assertNull(status.get(1, TimeUnit.SECONDS))
            }
        } finally {
            binderThread.shutdownNow()
        }
    }
}
