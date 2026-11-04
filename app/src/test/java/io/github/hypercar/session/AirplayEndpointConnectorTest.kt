package io.github.hypercar.session

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AirplayEndpointConnectorTest {
    private val old = AirplayTarget("192.168.1.1", 7000)
    private val fresh = AirplayTarget("192.168.1.2", 17000)

    @Test fun sameHostInvitationPreservesExplicitPortButChangedHostUsesFallback() {
        val hints=AirplayEndpointHints()
        assertEquals(7000,hints.invited(old.host).port)
        hints.advertised(fresh)
        assertEquals(fresh,hints.invited(fresh.host))
        assertEquals(old,hints.invited(old.host))
    }

    @Test fun connectionWindowAlsoBoundsFallbackPortList() = runTest {
        var time=0L;val opened=mutableListOf<AirplayTarget>()
        val c=AirplayEndpointConnector(current={true},now={time},
            open={t:AirplayTarget->opened+=t;time+=500;null as Int?},close={_:Int->},invalidate={},
            updates={emptyList()},discover={emptyList()},pause={time+=400})
        assertNull(c.connect(listOf(old,old.copy(port=7100),old.copy(port=17000)),800))
        assertEquals(listOf(old,old.copy(port=7100)),opened)
    }

    @Test fun refusalInvalidatesAndReconnectsToRealNewListener() = runTest {
        val deadPort = ServerSocket(0).use { it.localPort }
        ServerSocket(0).use { server ->
            val expired = AirplayTarget("127.0.0.1", deadPort)
            val live = AirplayTarget("127.0.0.1", server.localPort)
            val invalidated = mutableListOf<AirplayTarget>()
            var resolves = 0
            val c = AirplayEndpointConnector(current = { true }, now = { testScheduler.currentTime },
                open = { target: AirplayTarget -> runCatching { Socket(target.host, target.port) }.getOrNull() },
                close = { s: Socket -> s.close() }, invalidate = { invalidated += it }, updates = { emptyList() },
                discover = { resolves++; listOf(live) }, pause = { testScheduler.advanceTimeBy(400) })
            val connected = c.connect(listOf(expired), 8000)!!
            assertEquals(live, connected.target); assertEquals(listOf(expired), invalidated)
            assertEquals(1, resolves)
            server.accept().close(); connected.socket.close()
        }
    }

    @Test fun newIapEndpointPreemptsFallbackAndDiscovery() = runTest {
        val opened = mutableListOf<AirplayTarget>(); var update: AirplayTarget? = null
        val c = AirplayEndpointConnector(current = { true }, now = { 0L },
            open = { target: AirplayTarget -> opened += target; if (target == old) { update = fresh; null } else 42 },
            close = { _: Int -> }, invalidate = {},
            updates = { update?.let { update = null; listOf(it) } ?: emptyList() },
            discover = { fail("fresh iAP endpoint must bypass discovery"); emptyList() }, pause = {})
        assertEquals(fresh, c.connect(listOf(old, old.copy(port = 7100)), 8000)!!.target)
        assertEquals(listOf(old, fresh), opened)
    }

    @Test fun invitationArrivingDuringDiscoveryWinsOverMdns() = runTest {
        var update: AirplayTarget? = null
        val c = AirplayEndpointConnector(current = { true }, now = { 0L },
            open = { target: AirplayTarget -> if (target == fresh) 42 else null }, close = { _: Int -> }, invalidate = {},
            updates = { update?.let { update = null; listOf(it) } ?: emptyList() },
            discover = { update = fresh; listOf(old.copy(port = 17001)) }, pause = {})
        assertEquals(fresh, c.connect(listOf(old), 8000)!!.target)
    }

    @Test fun endpointUpdatedDuringSuccessfulConnectClosesObsoleteSocket() = runTest {
        var update: AirplayTarget? = null; val closed = mutableListOf<Int>()
        val c = AirplayEndpointConnector(current = { true }, now = { 0L },
            open = { target: AirplayTarget -> if (target == old) { update = fresh; 41 } else 42 },
            close = { fd: Int -> closed += fd }, invalidate = {},
            updates = { update?.let { update = null; listOf(it) } ?: emptyList() },
            discover = { fail("fresh target must bypass discovery"); emptyList() }, pause = {})
        val connected = c.connect(listOf(old), 8000)!!
        assertEquals(fresh, connected.target); assertEquals(42, connected.socket)
        assertEquals(listOf(41), closed)
    }

    @Test fun timeoutIsBoundedAndDiscoveryRunsOnce() = runTest {
        var time = 0L; var queries = 0; var invalidations = 0
        val c = AirplayEndpointConnector(current = { true }, now = { time },
            open = { _: AirplayTarget -> null as Int? }, close = { _: Int -> }, invalidate = { invalidations++ },
            updates = { emptyList() }, discover = { queries++; emptyList() }, pause = { time += 400 })
        assertNull(c.connect(listOf(old), 1200))
        assertEquals(1, queries); assertEquals(1, invalidations); assertEquals(1200L, time)
    }

    @Test fun sessionChangeDiscardsDiscoveredEndpointAndClosesRacingSocket() = runTest {
        var current = true; var closed = false
        val c = AirplayEndpointConnector(current = { current }, now = { 0L },
            open = { _: AirplayTarget -> current = false; 42 }, close = { _: Int -> closed = true },
            invalidate = { fail("expired session must not invalidate cache") }, updates = { emptyList() },
            discover = { fail("expired session must not discover"); emptyList() }, pause = {})
        try { c.connect(listOf(old), 8000); fail("must cancel") } catch (_: CancellationException) {}
        assertTrue(closed)
        current = true
        val d = AirplayEndpointConnector(current = { current }, now = { 0L },
            open = { _: AirplayTarget -> null as Int? }, close = { _: Int -> }, invalidate = {}, updates = { emptyList() },
            discover = { current = false; listOf(fresh) }, pause = {})
        try { d.connect(listOf(old), 8000); fail("must cancel") } catch (_: CancellationException) {}
    }
}
