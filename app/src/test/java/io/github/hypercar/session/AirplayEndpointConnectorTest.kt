package io.github.hypercar.session

import io.github.hypercar.iap2.Iap2EndpointGeneration
import io.github.hypercar.iap2.Iap2LinkChannel
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AirplayEndpointConnectorTest {
    @Test fun eachAddressRetainsItsExplicitSrvPortWhenInvited() {
        val hints = AirplayEndpointHints(7000)
        val ipv4 = AirplayTarget("192.0.2.4", 7001)
        val ipv6 = AirplayTarget("2001:db8::4", 7001)
        hints.advertised(ipv4)
        hints.advertised(ipv6)
        assertEquals(ipv4, hints.invitation(Any(), ipv4.host))
        assertEquals(ipv6, hints.invitation(Any(), ipv6.host))
    }

    @Test fun equivalentIpv6InvitationRetainsExplicitPortAndOriginalScope() {
        val hints = AirplayEndpointHints()
        val target = AirplayTarget("fe80::1%wlan0", 17000, 5)
        hints.advertised(target)
        assertEquals(target, hints.invitation(Any(), "fe80:0:0:0:0:0:0:1%18"))
    }

    @Test fun detectDuringConnectDoesNotHandOffOrInvalidateTheRetiredCandidate() = runTest {
        val channel = object : Iap2LinkChannel {
            override var linkGeneration = 0L
            override val isAlive = true
            override fun awaitReady(timeoutMillis: Long) = true
            override fun peerMaxControlPayload(): Int? = null
            override fun recvControl(timeoutMillis: Long): ByteArray? = null
            override fun sendControl(bytes: ByteArray, awaitAck: Boolean) = true
            override fun close() = Unit
        }
        val queued = old.copy(sourceGeneration = channel.endpointGeneration(0))
        val closed = mutableListOf<Int>()
        val invalidated = mutableListOf<AirplayTarget>()
        val connector = AirplayEndpointConnector(current = { true }, now = { 0L }, open = { target ->
            if (target == queued) {
                channel.linkGeneration++
                41
            } else 42
        }, close = { fd: Int -> closed += fd }, invalidate = { invalidated += it },
            updates = { emptyList() }, discover = { listOf(fresh) }, pause = {})
        val retained = connector.connect(listOf(queued), 8000)
        assertEquals(fresh, retained?.target)
        assertEquals(42, retained?.socket)
        assertEquals(listOf(41), closed)
        assertTrue(invalidated.isEmpty())
    }

    @Test fun sourceRetiredAtFinalOwnershipClaimClosesControlFd() = runTest {
        val source = Iap2EndpointGeneration()
        var checks = 0
        var closed = false
        var adopted = false
        val result = runCatching {
            handOffControlSocket(Mutex(), Any(), closeUnclaimed = { closed = true },
                checkCurrent = { if (++checks == 2) source.retire() }, prepare = {}, sourceGeneration = { source }) { _, transfer ->
                transfer { adopted = true }
            }
        }
        assertTrue(result.exceptionOrNull() is AirplayEndpointChanged)
        assertTrue(closed)
        assertFalse(adopted)
    }

    private val old = AirplayTarget("192.168.1.1", 7000)
    private val fresh = AirplayTarget("192.168.1.2", 17000)

    @Test fun existingIpv6InvitationWinsBeforeAnySpeakerCandidateIsOpened() = runTest {
        val hints = AirplayEndpointHints()
        val identity = Any()
        val invitedHost = "fe80::dcf9:473e:5524:40f8%wlan0"
        val speaker = AirplayTarget("192.168.1.253", 7000)
        val opened = mutableListOf<AirplayTarget>()
        val connector = AirplayEndpointConnector(current = { true }, now = { 0L },
            open = { t: AirplayTarget -> opened += t; 42 }, close = { _: Int -> }, invalidate = {},
            updates = { listOfNotNull(hints.invitation(identity, invitedHost)) },
            discover = { fail("invitation must not fall back to unrelated mDNS"); emptyList() }, pause = {})
        assertEquals(AirplayTarget(invitedHost, 7000), connector.connect(listOf(speaker), 8000)!!.target)
        assertEquals(listOf(AirplayTarget(invitedHost, 7000)), opened)
        assertNull(hints.invitation(identity, invitedHost))
        assertEquals(AirplayTarget(invitedHost, 7000), hints.invitation(Any(), invitedHost))
    }

    @Test fun softwareInvitationUsesDiscoveredPortAndLegacyDefaultIsUnchanged() {
        val host = "fe80::1%wlan0"
        assertEquals(17000,AirplayEndpointHints(17000).invitation(Any(),host)!!.port)
        assertEquals(7000,AirplayEndpointHints().invitation(Any(),host)!!.port)
    }

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

    @Test fun sameRouteReportAdoptsTheOpenSocketWithItsNewSource() = runTest {
        val source = Iap2EndpointGeneration()
        val report = old.copy(verifiedNetworkHandle = 5, sourceGeneration = source)
        var update: AirplayTarget? = null
        val opened = mutableListOf<AirplayTarget>()
        val closed = mutableListOf<Int>()
        val connector = AirplayEndpointConnector(current = { true }, now = { 0L },
            open = { target: AirplayTarget ->
                opened += target
                if (target == old) update = report
                opened.size
            }, close = { fd: Int -> closed += fd }, invalidate = {},
            updates = { update?.let { update = null; listOf(it) }.orEmpty() },
            discover = { fail("the verified route is already connected"); emptyList() }, pause = {})

        val connected = connector.connect(listOf(old), 8000)!!
        assertEquals(report, connected.target)
        assertEquals(1, connected.socket)
        assertEquals(listOf(old), opened)
        assertTrue(closed.isEmpty())
    }

    @Test fun sameHostAndPortOnAnotherVerifiedNetworkAreADifferentRoute() = runTest {
        val initial = old.copy(verifiedNetworkHandle = 5)
        val report = old.copy(verifiedNetworkHandle = 9)
        var update: AirplayTarget? = null
        val closed = mutableListOf<Int>()
        val connector = AirplayEndpointConnector(current = { true }, now = { 0L },
            open = { target: AirplayTarget -> if (target == initial) { update = report; 41 } else 42 },
            close = { fd: Int -> closed += fd }, invalidate = {},
            updates = { update?.let { update = null; listOf(it) }.orEmpty() },
            discover = { emptyList() }, pause = {})

        val connected = connector.connect(listOf(initial), 8000)!!
        assertEquals(report, connected.target)
        assertEquals(42, connected.socket)
        assertEquals(listOf(41), closed)
    }

    @Test fun aLateReportBoundToAnotherNetworkCannotReplaceAnOpenInvitationSocket() = runTest {
        val report = old.copy(verifiedNetworkHandle = 9, sourceGeneration = Iap2EndpointGeneration())
        var update: AirplayTarget? = null
        val connector = AirplayEndpointConnector(current = { true }, now = { 0L },
            open = { target: AirplayTarget ->
                assertEquals(old, target)
                update = report
                41
            }, close = { _: Int -> fail("the invitation socket belongs to the bound Network") }, invalidate = {},
            updates = { update?.let { update = null; listOf(it) }.orEmpty() },
            discover = { emptyList() }, pause = {}, networkHandle = 5)

        val connected = connector.connect(listOf(old), 8000)!!
        assertEquals(old, connected.target)
        assertEquals(41, connected.socket)
    }

    @Test fun laterBrowseFindsUpdatedPortAfterAnEmptyAndAStaleResult() = runTest {
        var time = 0L
        var queries = 0
        val updated = old.copy(port = 17000)
        val opened = mutableListOf<AirplayTarget>()
        val c = AirplayEndpointConnector(current = { true }, now = { time },
            open = { target: AirplayTarget -> opened += target; 42.takeIf { target == updated } },
            close = { _: Int -> }, invalidate = {}, updates = { emptyList() },
            discover = {
                when (++queries) {
                    1 -> emptyList()
                    2 -> listOf(old)
                    else -> listOf(updated)
                }
            }, pause = { time += 400 })
        assertEquals(updated, c.connect(listOf(old), 1200)?.target)
        assertEquals(listOf(old, old, old, updated), opened)
    }

    @Test fun timeoutBoundsRepeatedDiscoveryAndCacheInvalidation() = runTest {
        var time = 0L; var queries = 0; var invalidations = 0
        val c = AirplayEndpointConnector(current = { true }, now = { time },
            open = { _: AirplayTarget -> null as Int? }, close = { _: Int -> }, invalidate = { invalidations++ },
            updates = { emptyList() }, discover = { queries++; emptyList() }, pause = { time += 400 })
        assertNull(c.connect(listOf(old), 1200))
        assertEquals(3, queries); assertEquals(1, invalidations); assertEquals(1200L, time)
    }

    @Test fun browseReceivesOnlyTheRemainingWindowAndLateSocketIsClosed() = runTest {
        var time = 0L
        val budgets = mutableListOf<Long>()
        val closed = mutableListOf<Int>()
        val c = AirplayEndpointConnector(current = { true }, now = { time },
            open = { target: AirplayTarget ->
                time += 300
                42.takeIf { target == fresh }
            }, close = { fd: Int -> closed += fd }, invalidate = {}, updates = { emptyList() },
            discover = { remaining -> budgets += remaining; listOf(fresh) }, pause = {})
        assertNull(c.connect(listOf(old), 500))
        assertEquals(listOf(200L), budgets)
        assertEquals(listOf(42), closed)
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
