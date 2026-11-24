package io.github.hypercar.iap2

import io.github.hypercar.navigation.NavigationCue
import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceSessionTest {
    private val cue = NavigationCue("右转进入测试路", 2, 300)
    private fun start(id: Int) = Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, id) + Iap2Csm.param(1, byteArrayOf()) + Iap2Csm.param(2, byteArrayOf()))
    private fun fields(f: Iap2Csm.Frame) = Iap2Csm.parseParams(f.body).associate { it.id to it.value }
    @Test fun sourceReadyIsDiagnosedWithoutFabricatingASubscription() {
        val logs = mutableListOf<String>()
        val s = RouteGuidanceSession(logs::add)
        assertTrue(s.updates(0, true, cue).isEmpty())
        assertTrue(logs.single().contains("source_ready_wait_subscription"))
        s.updates(1000, true, cue)
        assertEquals(1, logs.size)
        s.handle(start(42))
        assertEquals(listOf(0x5202, 0x5201), s.updates(2000, true, cue).map { it.messageId })
        assertTrue(logs.any { it.contains("source_and_subscription_ready") })
    }
    @Test fun requiresSubscriptionAndScope() {
        val s = RouteGuidanceSession {}
        assertTrue(s.updates(0, true, cue).isEmpty())
        s.handle(start(42))
        assertTrue(s.updates(1, false, cue).isEmpty())
        assertTrue(s.updates(2000,false,cue).isEmpty())
    }
    @Test fun realComponentAndManeuverBeforeReference() {
        val s = RouteGuidanceSession {}; s.handle(start(0x1234))
        val frames = s.updates(0, true, cue)
        assertEquals(listOf(0x5202, 0x5201), frames.map { it.messageId })
        frames.forEach { assertArrayEquals(byteArrayOf(0x12, 0x34), fields(it)[0]) }
        assertArrayEquals(byteArrayOf(2), fields(frames[0])[3])
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x2c), fields(frames[1])[0x0a])
        assertArrayEquals(byteArrayOf(0, 0), fields(frames[1])[0x0d])
        assertArrayEquals(byteArrayOf(0, 1), fields(frames[1])[0x0e])
        assertFalse(fields(frames[1]).containsKey(5)); assertFalse(fields(frames[0]).containsKey(8))
    }
    @Test fun clearsOnceOnCancellationAndScopeChange() {
        val s = RouteGuidanceSession {}; s.handle(start(42)); s.updates(0, true, cue)
        val clear = s.updates(1000, true, null).single()
        assertEquals(0x5201, clear.messageId)
        assertArrayEquals(byteArrayOf(0), fields(clear)[1])
        assertArrayEquals(byteArrayOf(0, 0), fields(clear)[0x0e])
        assertTrue(s.updates(2000, true, null).isEmpty())
        assertEquals(2, s.updates(3000, true, cue).size)
        assertArrayEquals(byteArrayOf(0), fields(s.updates(4000, false, cue).single())[1])
    }
    @Test fun stopAndNewGenerationDoNotReuseSubscriptions() {
        val s = RouteGuidanceSession {}; s.handle(start(42))
        s.handle(Iap2Csm.Frame(0x5203, Iap2Csm.u16(0, 42)))
        assertTrue(s.updates(0, true, cue).isEmpty())
        assertTrue(RouteGuidanceSession {}.updates(0, true, cue).isEmpty())
    }
    @Test fun rejectsMalformedAndFiltersOptionalFields() {
        val s = RouteGuidanceSession {}
        s.handle(Iap2Csm.Frame(0x5200, byteArrayOf(0, 6, 0, 0, 42)))
        assertTrue(s.updates(0, true, cue).isEmpty())
        s.handle(Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, 42)))
        val f = fields(s.updates(1, true, cue).last())
        assertFalse(f.containsKey(0x13)); assertFalse(f.containsKey(0x14))
    }
    @Test fun rateLimitAndDeduplicate() {
        val s = RouteGuidanceSession {}; s.handle(start(42)); s.updates(0, true, cue)
        assertTrue(s.updates(100, true, cue.copy(distanceMeters = 200)).isEmpty())
        assertTrue(s.updates(1000, true, cue).isEmpty())
        assertEquals(2, s.updates(1001, true, cue.copy(distanceMeters = 200)).size)
    }

    @Test fun unchangedActiveRouteSendsOnlyStatusEveryTenSecondsUntilCleared() {
        val s = RouteGuidanceSession {}
        s.handle(start(0x1234))
        val original = s.updates(0, true, cue)
        assertEquals(listOf(0x5202, 0x5201), original.map { it.messageId })
        assertTrue(s.updates(9_999, true, cue).isEmpty())
        val heartbeat = s.updates(10_000, true, cue).single()
        assertEquals(0x5201, heartbeat.messageId)
        assertArrayEquals(original.last().body, heartbeat.body)
        assertTrue(s.updates(19_999, true, cue).isEmpty())
        assertEquals(0x5201, s.updates(20_000, true, cue).single().messageId)
        val clear = s.updates(21_000, true, cue.copy(state = 0)).single()
        assertEquals(0x5201, clear.messageId)
        assertArrayEquals(byteArrayOf(0), fields(clear)[1])
        assertTrue(s.updates(31_000, true, cue.copy(state = 0)).isEmpty())
    }

    @Test fun structuredCarWithUturnReachesWireAsCpUturnWithRoadAndActualSource() {
        val s = RouteGuidanceSession {}; s.handle(start(42))
        val cue = io.github.hypercar.navigation.CarWithCueMapper.map(2, 5, 35, "test")!!
        val frames = s.updates(0, true, cue)
        assertArrayEquals(byteArrayOf(4), fields(frames[0])[3])
        assertArrayEquals("test\u0000".encodeToByteArray(), fields(frames[0])[4])
        assertArrayEquals("CarWith\u0000".encodeToByteArray(), fields(frames[1])[0x13])
        assertArrayEquals(byteArrayOf(0, 0, 0, 35), fields(frames[1])[0x0a])
        assertFalse(fields(frames[1]).containsKey(7)) // segment total is NOT route remaining distance
    }

    @Test fun activeRouteCarriesRemainingTimeAndDistanceWithoutChangingNextTurnDistance() {
        val s = RouteGuidanceSession {}
        val active = cue.copy(maneuver = null, currentRoad = "测试路", remainingTimeSeconds = 256, remainingDistanceMeters = 1200)
        s.handle(start(42))
        val update = fields(s.updates(0, true, active).single())
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 0), update[0x06])
        assertArrayEquals(byteArrayOf(0, 0, 4, 0xb0.toByte()), update[0x07])
        assertFalse(update.containsKey(0x03)) // No declared road name limit.
        assertFalse(update.containsKey(0x0a)) // The route total cannot substitute for a missing maneuver.
        val maneuver = fields(s.updates(1000, true, active.copy(maneuver = cue.maneuver)).last())
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x2c), maneuver[0x0a])
        val stopped = fields(s.updates(2000, true, active.copy(state = 2)).single())
        assertFalse(stopped.keys.any { it in setOf(0x03, 0x06, 0x07) })
        val invalid = fields(s.updates(3000, true, active.copy(remainingTimeSeconds = -1, remainingDistanceMeters = 0x1_0000_0000)).single())
        assertFalse(invalid.keys.any { it in setOf(0x06, 0x07) })
    }

    @Test fun currentRoadMatchesTheSubscribedComponentsUtf8Limit() {
        fun component(id: Int, limit: Int) = Iap2Csm.group(30,
            Iap2Csm.u16(0, id), Iap2Csm.string(1, "RouteGuidance"), Iap2Csm.u16(2, limit))
        val peer = Iap2Messages.parseIdentification(Iap2Csm.u16(7, 0x5201) +
            component(42, 8) + component(43, 11) + component(44, 8) + component(44, 12))
        assertEquals(mapOf(42 to 8, 43 to 11), peer.currentRoadLimits)
        val s = RouteGuidanceSession {}
        s.declareReceiveSupport(peer.acceptsRouteGuidance, peer.currentRoadLimits)
        for (id in 42..44) s.handle(start(id))
        val road = cue.copy(currentRoad = "东街🧭\u0000路")
        val routeUpdates = s.updates(0, true, road).filter { it.messageId == 0x5201 }.map(::fields)
        assertArrayEquals("东街\u0000".encodeToByteArray(), routeUpdates[0][0x03])
        assertArrayEquals("东街🧭\u0000".encodeToByteArray(), routeUpdates[1][0x03])
        assertFalse(routeUpdates[2].containsKey(0x03))
        val cleared = s.updates(1000, true, road.copy(state = 0))
        assertTrue(cleared.all { !fields(it).containsKey(0x03) })
    }

    @Test fun missingCurrentRoadClearsOnlyComponentsThatReceivedIt() {
        val s = RouteGuidanceSession {}
        s.declareReceiveSupport(true, mapOf(42 to 64))
        s.handle(start(42))
        s.handle(start(43))
        fun routeUpdates(now: Long, currentRoad: String) =
            s.updates(now, true, cue.copy(currentRoad = currentRoad))
                .filter { it.messageId == 0x5201 }.map(::fields)

        assertTrue(routeUpdates(0, "").none { it.containsKey(0x03) })
        val named = routeUpdates(1000, "测试路")
        assertArrayEquals("测试路\u0000".encodeToByteArray(), named[0][0x03])
        assertFalse(named[1].containsKey(0x03))

        val cleared = routeUpdates(2000, "")
        assertArrayEquals(byteArrayOf(0), cleared[0][0x03])
        assertArrayEquals(byteArrayOf(1), cleared[0][1])
        assertFalse(cleared[1].containsKey(0x03))
        assertTrue(routeUpdates(12_000, "").none { it.containsKey(0x03) })
    }

    @Test fun subscriptionWithoutSourceWaitsWithoutInventingInitialGuidance() {
        val s = RouteGuidanceSession {}; s.handle(start(42))
        assertTrue(s.updates(0, true, null).isEmpty())
        assertTrue(s.updates(1000, false, null).isEmpty())
        assertTrue(s.updates(2000, false, cue).isEmpty())
        val active = s.updates(2001, true, cue)
        assertEquals(listOf(0x5202, 0x5201), active.map { it.messageId })
        assertArrayEquals(byteArrayOf(1), fields(active.last())[0x14])
        val clear = s.updates(4000, false, null).single()
        assertArrayEquals(byteArrayOf(0), fields(clear)[1])
        assertTrue(s.updates(6000, false, null).isEmpty())
    }

    @Test fun newComponentDoesNotInheritAnotherComponentsPendingClearOrClearedState() {
        val s = RouteGuidanceSession {}; s.handle(start(42)); s.updates(0, true, cue)
        s.handle(start(43))
        val clear = s.updates(1000, true, null)
        assertEquals(1, clear.size)
        assertArrayEquals(byteArrayOf(0,42), fields(clear.single())[0])
        s.handle(start(44))
        assertTrue(s.updates(2000, true, null).isEmpty())
        s.handle(Iap2Csm.Frame(0x5203,Iap2Csm.u16(0,42)))
        s.handle(start(42))
        assertTrue(s.updates(3000, true, null).isEmpty())
        assertEquals(6, s.updates(4000, true, cue).size)
    }
}
