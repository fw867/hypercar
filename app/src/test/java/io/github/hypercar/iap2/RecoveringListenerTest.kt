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

    @Test fun radioOffAtStartCanRecoverWithoutRestartingTheSession() {
        var attempts = 0
        var waits = 0
        val server = Resource()
        val incoming = Resource()
        val loop = RecoveringListener(
            ownerAlive = { true },
            open = { if (++attempts == 1) throw IOException("radio off") else server },
            accept = { _: Resource -> incoming },
            onFailure = {}, awaitRetry = { waits++ },
        )
        assertSame(incoming, loop.acceptNext())
        assertEquals(2, attempts)
        assertEquals(1, waits)
        assertFalse(incoming.closed)
        loop.close()
        assertTrue(server.closed)
    }

    @Test fun failedAcceptClosesTheStaleListenerAndRegistersANewOne() {
        val stale = Resource()
        val replacement = Resource()
        val incoming = Resource()
        var opens = 0
        val loop = RecoveringListener(
            ownerAlive = { true }, open = { if (++opens == 1) stale else replacement },
            accept = { server -> if (server === stale) throw IOException("stack restarted") else incoming },
            onFailure = {}, awaitRetry = {},
        )
        assertSame(incoming, loop.acceptNext())
        assertTrue(stale.closed)
        assertFalse(replacement.closed)
        loop.close()
        assertTrue(replacement.closed)
    }

    @Test fun stoppingDuringRegistrationDoesNotLeakALateSocket() {
        val server = Resource()
        lateinit var loop: RecoveringListener<Resource, Resource>
        loop = RecoveringListener(
            ownerAlive = { true }, open = { loop.close(); server },
            accept = { fail("must not accept after stop"); Resource() },
            onFailure = {}, awaitRetry = {},
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
            accept = { loop.close(); incoming }, onFailure = {}, awaitRetry = {},
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
            accept = { Resource() }, onFailure = {}, awaitRetry = { alive = false },
        )
        assertNull(old.acceptNext())
        val fresh = RecoveringListener(
            ownerAlive = { true }, open = { freshServer }, accept = { _: Resource -> freshConnection },
            onFailure = {}, awaitRetry = {},
        )
        assertSame(freshConnection, fresh.acceptNext())
        old.close()
        assertFalse(freshServer.closed)
        assertEquals(1, attempts)
        fresh.close()
    }
}
