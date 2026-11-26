package io.github.hypercar.iap2

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RecoveringListenerTest {
    private class Resource : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun reportsRegistrationAndAcceptFailuresSeparately() {
        var opens = 0
        var accepts = 0
        val incoming = Resource()
        val failures = mutableListOf<RecoveringListener.FailureStage>()
        val loop = RecoveringListener(
            ownerAlive = { true },
            open = { if (++opens == 1) throw IOException("radio off") else Resource() },
            accept = { _: Resource -> if (++accepts == 1) throw IOException("stack restarted") else incoming },
            onFailure = { stage, _ -> failures += stage },
            awaitRetry = {},
        )
        loop.use {
            assertSame(incoming, it.acceptNext())
            incoming.close()
            assertEquals(listOf(RecoveringListener.FailureStage.REGISTER, RecoveringListener.FailureStage.ACCEPT), failures)
            val diagnostics = it.diagnostics
            assertEquals(2, diagnostics.registrations)
            assertEquals(1, diagnostics.registerFailures)
            assertEquals(1, diagnostics.acceptFailures)
            assertEquals(RecoveringListener.FailureStage.ACCEPT, diagnostics.lastFailure)
        }
    }

    @Test fun radioOffAtStartCanRecoverWithoutRestartingTheSession() {
        var attempts = 0
        var waits = 0
        val server = Resource()
        val incoming = Resource()
        val loop = RecoveringListener(
            ownerAlive = { true },
            open = { if (++attempts == 1) throw IOException("radio off") else server },
            accept = { _: Resource -> incoming },
            onFailure = { _, _ -> }, awaitRetry = { waits++ },
        )
        assertSame(incoming, loop.acceptNext())
        assertEquals(2, attempts)
        assertEquals(1, waits)
        assertFalse(incoming.closed)
        loop.close()
        assertTrue(server.closed)
    }

    @Test fun primaryIsPublishedBeforeStartingMarkerAndBeforeFirstAccept() {
        val events = mutableListOf<String>()
        lateinit var primary: RecoveringListener<Resource, Resource>
        primary = RecoveringListener(ownerAlive = { true }, open = { Resource() },
            onRegistered = { assertTrue(primary.isRegistered); events += "marker" },
            accept = { events += "accept"; Resource() }, onFailure = { _, _ -> }, awaitRetry = {})
        primary.acceptNext()
        primary.acceptNext()
        assertEquals(listOf("marker", "accept", "accept"), events)
        primary.close()
    }

    @Test fun failedAcceptClosesTheStaleListenerAndRegistersANewOne() {
        val stale = Resource()
        val replacement = Resource()
        val incoming = Resource()
        var opens = 0
        val loop = RecoveringListener(
            ownerAlive = { true }, open = { if (++opens == 1) stale else replacement },
            accept = { server -> if (server === stale) throw IOException("stack restarted") else incoming },
            onFailure = { _, _ -> }, awaitRetry = {},
        )
        assertSame(incoming, loop.acceptNext())
        assertTrue(stale.closed)
        assertFalse(replacement.closed)
        loop.close()
        assertTrue(replacement.closed)
    }

    @Test fun bluetoothServiceRuntimeFailureDoesNotExitTheAcceptLoop() {
        val stale = Resource()
        val replacement = Resource()
        val incoming = Resource()
        var opens = 0
        var retries = 0
        val loop = RecoveringListener(
            ownerAlive = { true },
            open = { if (++opens == 1) stale else replacement },
            accept = { server -> if (server === stale) throw IllegalStateException("Bluetooth service restarted") else incoming },
            onFailure = { _, _ -> assertTrue(stale.closed) },
            awaitRetry = { retries++ },
        )
        assertSame(incoming, loop.acceptNext())
        assertEquals(1, retries)
        assertFalse(replacement.closed)
        loop.close()
    }

    @Test fun failedPhoneMarkerReopensWhilePrimaryAndDiscoveryRemainRegistered() {
        val primary = Resource()
        val discovery = Resource()
        val stale = Resource()
        val recovered = Resource()
        var opens = 0
        val loop = RecoveringListener(
            ownerAlive = { true },
            open = { if (++opens == 1) stale else recovered },
            accept = { server ->
                if (server === stale) throw IllegalStateException("Phone marker socket lost")
                Resource()
            },
            onFailure = { _, _ ->
                assertTrue(stale.closed)
                assertFalse(primary.closed)
                assertFalse(discovery.closed)
            },
            awaitRetry = {},
        )
        assertNotNull(loop.acceptNext())
        assertEquals(2, opens)
        assertFalse(primary.closed)
        assertFalse(discovery.closed)
        loop.close()
    }

    @Test fun stoppingDuringRegistrationDoesNotLeakALateSocket() {
        val server = Resource()
        lateinit var loop: RecoveringListener<Resource, Resource>
        loop = RecoveringListener(
            ownerAlive = { true }, open = { loop.close(); server },
            accept = { fail("must not accept after stop"); Resource() },
            onFailure = { _, _ -> }, awaitRetry = {},
        )
        assertNull(loop.acceptNext())
        assertTrue(server.closed)
    }

    @Test fun aConnectionAcceptedAfterStopIsClosedAndNeverDelivered() {
        val server = Resource()
        val incoming = Resource()
        lateinit var loop: RecoveringListener<Resource, Resource>
        loop = RecoveringListener(
            ownerAlive = { true }, open = { server },
            accept = { loop.close(); incoming }, onFailure = { _, _ -> }, awaitRetry = {},
        )
        assertNull(loop.acceptNext())
        assertTrue(incoming.closed)
        assertTrue(server.closed)
    }

    @Test fun cancelledGenerationStopsRetryingAndDoesNotCloseAnotherGeneration() {
        var alive = true
        var attempts = 0
        val freshServer = Resource()
        val freshConnection = Resource()
        val old = RecoveringListener<Resource, Resource>(
            ownerAlive = { alive }, open = { attempts++; throw IOException("radio off") },
            accept = { Resource() }, onFailure = { _, _ -> }, awaitRetry = { alive = false },
        )
        assertNull(old.acceptNext())
        val fresh = RecoveringListener(
            ownerAlive = { true }, open = { freshServer }, accept = { _: Resource -> freshConnection },
            onFailure = { _, _ -> }, awaitRetry = {},
        )
        assertSame(freshConnection, fresh.acceptNext())
        old.close()
        assertFalse(freshServer.closed)
        assertEquals(1, attempts)
        fresh.close()
    }

    @Test fun optionalMarkerFailureAndStopLeavePrimaryListenerAvailable() {
        val primaryServer = Resource()
        val primary = RecoveringListener(
            ownerAlive = { true }, open = { primaryServer }, accept = { _: Resource -> Resource() },
            onFailure = { _, _ -> }, awaitRetry = {},
        )
        primary.acceptNext()
        var markerAlive = true
        val marker = RecoveringListener<Resource, Resource>(
            ownerAlive = { markerAlive }, open = { throw IOException("marker unavailable") },
            accept = { fail("failed marker must not deliver a connection"); Resource() },
            onFailure = { _, _ -> }, awaitRetry = { markerAlive = false },
        )
        assertNull(marker.acceptNext())
        marker.close()
        assertTrue(primary.isRegistered)
        assertFalse(primaryServer.closed)
        assertNotNull(primary.acceptNext())
        primary.close()
        assertTrue(primaryServer.closed)
    }
}
