package io.github.hypercar.iap2

import io.github.hypercar.navigation.NavigationCue
import io.github.hypercar.navigation.NavigationLane
import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceSessionTest {
    @Test fun clearsCachedTripFieldsOnAllComponentsBeforePublishingNewSource() {
        val session = RouteGuidanceSession {}
        session.handle(Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, 42) + Iap2Csm.u16(0, 43)))
        val link = cue.copy(estimatedArrivalEpochSeconds = 1_800_000_000, remainingTimeSeconds = 90, remainingDistanceMeters = 1250)
        session.updates(0, true, link)
        val notification = cue.copy(sourceName = "Notification")
        val resets = session.queuedUpdates(500, true, notification)
        assertEquals(listOf(0x5201, 0x5201), resets.map { it.frame.messageId })
        assertEquals(listOf(42, 43), resets.map { Iap2Csm.parseParams(it.frame.body).first().asU16List().single() })
        assertTrue(resets.all { fields(it.frame).getValue(1).single() == 0.toByte() })
        assertTrue(resets[0].commitStart!!.invoke())
        session.declareReceiveSupport(true)
        assertFalse(resets[1].canStart())
        val remainingReset = session.queuedUpdates(501, true, notification).single()
        assertArrayEquals(byteArrayOf(0, 43), fields(remainingReset.frame)[0])
        assertTrue(remainingReset.commitStart!!.invoke())
        val refreshed = session.updates(502, true, notification)
        assertEquals(listOf(0x5202, 0x5201, 0x5202, 0x5201), refreshed.map { it.messageId })
        assertTrue(refreshed.filter { it.messageId == 0x5201 }.all { frame ->
            fields(frame).getValue(1).single() == 1.toByte() && (5..9).none { it in fields(frame) }
        })
    }

    @Test fun clearingMissingTripFieldsIsWithdrawnWhenFullGuidanceReturnsFirst() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        val link = cue.copy(estimatedArrivalEpochSeconds = 1_800_000_000, remainingTimeSeconds = 90, remainingDistanceMeters = 1250)
        session.updates(0, true, link)
        var live = cue
        val reset = session.queuedUpdates(500, true, live, liveCue = { true to live }).single()
        assertArrayEquals(byteArrayOf(0), fields(reset.frame)[1])
        live = link
        assertFalse(reset.liveSourceAllowsFirstWrite!!.invoke())
    }

    @Test fun aQueuedStatusCannotWriteTripFieldsThatTheLatestSampleWithdrew() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        val link = cue.copy(routeGeneration = 10, estimatedArrivalEpochSeconds = 1_800_000_000,
            remainingTimeSeconds = 90, remainingDistanceMeters = 1250)
        var live = link
        session.updates(0, true, link)
        val waiting = session.queuedUpdates(10_000, true, link, liveCue = { true to live }).single()
        assertEquals(0x5201, waiting.frame.messageId)
        assertTrue(waiting.canStart())

        live = link.copy(estimatedArrivalEpochSeconds = null)
        assertFalse(waiting.liveSourceAllowsFirstWrite!!.invoke())
        session.refreshLaneSource(true, live)
        assertFalse(waiting.canStart())
        waiting.onDiscarded!!.invoke()
        val reset = session.queuedUpdates(10_001, true, live).single()
        assertArrayEquals(byteArrayOf(0), fields(reset.frame)[1])
    }

    @Test fun withdrawingTheSegmentDistanceRevokesTheQueuedValueAndClearsTheReceiverCache() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        val withDistance = cue.copy(distanceMeters = 80, routeGeneration = 10)
        var live = withDistance
        session.updates(0, true, withDistance)
        val waiting = session.queuedUpdates(10_000, true, withDistance, liveCue = { true to live }).single()
        assertArrayEquals(byteArrayOf(0, 0, 0, 80), fields(waiting.frame)[0x0a])

        live = withDistance.copy(distanceMeters = null)
        assertFalse(waiting.liveSourceAllowsFirstWrite!!.invoke())
        waiting.onDiscarded!!.invoke()
        val reset = session.updates(10_001, true, live).single()
        assertArrayEquals(byteArrayOf(0), fields(reset)[1])
        val replacement = session.updates(10_002, true, live).last()
        assertArrayEquals(byteArrayOf(1), fields(replacement)[1])
        assertNull(fields(replacement)[0x0a])
    }

    @Test fun sameArrowOnTheNextRouteCannotAuthorizeAnOlderFirstWrite() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        var live = cue.copy(routeGeneration = 10)
        val pending = session.queuedUpdates(1000, true, live, liveCue = { true to live })
        assertTrue(pending.all { it.canStart() })
        live = live.copy(routeGeneration = 11)
        assertTrue(pending.none { it.liveSourceAllowsFirstWrite!!.invoke() })
    }

    @Test fun discardedManeuverCannotLeaveAnOrphanedStatusReference() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        val pending = session.queuedUpdates(1000, true, cue)
        assertEquals(listOf(0x5202, 0x5201), pending.map { it.frame.messageId })
        pending[0].onDiscarded!!.invoke()
        assertFalse(pending[1].canStart())
        val refreshed = session.updates(1001, true, cue)
        assertEquals(listOf(0x5202, 0x5201), refreshed.map { it.messageId })
    }

    @Test fun stoppingNavigationRevokesUnstartedArrowAndClearsPublishedStateImmediately() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        session.updates(0, true, cue)
        val pending = session.queuedUpdates(1000, true, cue.copy(distanceMeters = 200))
        assertEquals(listOf(0x5202, 0x5201), pending.map { it.frame.messageId })
        session.refreshLaneSource(false, null)
        assertTrue(pending.none { it.canStart() })
        val clearing = session.updates(1001, false, null).single()
        assertEquals(0x5201, clearing.messageId)
        assertArrayEquals(byteArrayOf(0), fields(clearing)[1])
        assertTrue(session.updates(12_000, false, null).isEmpty())
    }

    @Test fun obsoleteArrowIsRevokedWhenTheTurnChangesButNotOnDistanceProgress() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        session.refreshLaneSource(true, cue)
        val pending = session.queuedUpdates(1000, true, cue)
        session.refreshLaneSource(true, cue.copy(distanceMeters = 200))
        assertTrue(pending.all { it.canStart() })
        session.refreshLaneSource(true, cue.copy(maneuver = 1, instruction = "左转"))
        assertTrue(pending.none { it.canStart() })
    }

    @Test fun notificationRoadTextAloneRevokesTheQueuedManeuver() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        var live = NavigationCue("右转进入甲路", 2, 200, routeGeneration = 12)
        val queued = session.queuedUpdates(1_000, true, live, liveCue = { true to live })
        assertEquals(listOf(0x5202, 0x5201), queued.map { it.frame.messageId })
        live = live.copy(instruction = "右转进入乙路")
        assertTrue(queued.none { it.liveSourceAllowsFirstWrite!!.invoke() })
        session.refreshLaneSource(true, live)
        assertTrue(queued.none { it.canStart() })
    }

    @Test fun remainingDistanceTextAndUnitDescribeOnlyTheFullRouteDistance() {
        val session = RouteGuidanceSession {}
        session.handle(start(42))
        val short = fields(session.updates(0, true, cue.copy(remainingDistanceMeters = 850)).last())
        assertArrayEquals("850\u0000".encodeToByteArray(), short[0x08])
        assertArrayEquals(byteArrayOf(2), short[0x09])
        val long = fields(session.updates(1000, true, cue.copy(remainingDistanceMeters = 1250)).last())
        assertArrayEquals("1.25\u0000".encodeToByteArray(), long[0x08])
        assertArrayEquals(byteArrayOf(0), long[0x09])
        val clearing = fields(session.updates(2000, true, cue).single())
        assertArrayEquals(byteArrayOf(0), clearing[1])
        val nextTurnOnly = fields(session.updates(2001, true, cue).last())
        assertFalse(nextTurnOnly.keys.any { it in 0x05..0x09 })
        assertTrue(nextTurnOnly.containsKey(0x0a))
    }

    @Test fun declaredLaneGuidancePrecedesReferencesAndClearsOnWithdrawal() {
        val session = RouteGuidanceSession {}
        session.declareReceiveSupport(true, laneGuidanceSupported = true)
        session.handle(start(42))
        val cue = NavigationCue("左转", 1, 100, lanes = listOf(
            NavigationLane(2, true, 1),
            NavigationLane(0, false),
        ))
        val frames = session.updates(0, true, cue)
        assertEquals(listOf(0x5204, 0x5202, 0x5201), frames.map { it.messageId })
        val lanes = Iap2Csm.parseParams(frames[0].body)
        assertEquals(listOf(0, 1, 2, 2), lanes.map { it.id })
        assertEquals(listOf(Iap2Csm.Param(0, byteArrayOf(0, 0)), Iap2Csm.Param(1, byteArrayOf(2)),
            Iap2Csm.Param(2, byteArrayOf(0, 0)), Iap2Csm.Param(2, byteArrayOf(-1, -90)),
            Iap2Csm.Param(3, byteArrayOf(-1, -90))), Iap2Csm.parseParams(lanes[2].value))
        assertArrayEquals(byteArrayOf(0, 0), fields(frames[1])[10])
        assertArrayEquals(byteArrayOf(0, 0), fields(frames[2])[0x10])
        assertArrayEquals(byteArrayOf(0, 1), fields(frames[2])[0x11])
        assertArrayEquals(byteArrayOf(1), fields(frames[2])[0x12])
        assertEquals(listOf(0x5201), session.updates(10_000, true, cue).map { it.messageId })
        val cleared = session.updates(11_000, true, cue.copy(lanes = cue.lanes + NavigationLane(5, false)))
        assertTrue(cleared.none { it.messageId == 0x5204 })
        assertFalse(fields(cleared.first()).containsKey(0x0a))
        assertArrayEquals(byteArrayOf(0, 0), fields(cleared.last())[0x11])
        assertArrayEquals(byteArrayOf(0), fields(cleared.last())[0x12])
        session.declareReceiveSupport(true)
        assertEquals(listOf(0x5202, 0x5201), session.updates(12_000, true, cue).map { it.messageId })
    }

    @Test fun groupedStartAndStopUseAllValidComponentIds() {
        val session = RouteGuidanceSession {}
        val cue = NavigationCue("右转", 2, 100)
        val twoComponents = Iap2Csm.u16(0, 42) + Iap2Csm.u16(0, 43)
        session.handle(Iap2Csm.Frame(0x5200, twoComponents + Iap2Csm.u16(0, 42)))
        val updates = session.updates(0, true, cue)
        assertEquals(listOf(0x5202, 0x5201, 0x5202, 0x5201), updates.map { it.messageId })
        assertEquals(listOf(42, 42, 43, 43), updates.map { Iap2Csm.parseParams(it.body).first().asU16List().single() })
        session.handle(Iap2Csm.Frame(0x5203, twoComponents + Iap2Csm.u8(0, 44)))
        assertEquals(4, session.updates(1_000, true, cue.copy(distanceMeters = 90)).size)
        session.handle(Iap2Csm.Frame(0x5203, twoComponents))
        assertTrue(session.updates(20_000, true, cue).isEmpty())
    }

    @Test fun discardingAnUnstartedClearKeepsItOwedToTheComponent() {
        val session = RouteGuidanceSession {}
        session.declareReceiveSupport(true, laneGuidanceSupported = true)
        session.handle(start(42))
        val withLanes = cue.copy(lanes = listOf(NavigationLane(3, true, 3)))
        session.updates(0, true, withLanes)
        session.refreshLaneSource(true, cue)
        val clear = session.queuedUpdates(10, true, cue).last()
        assertArrayEquals(byteArrayOf(0), fields(clear.frame)[0x12])
        session.declareReceiveSupport(true, maneuverSupported = false)
        assertFalse(clear.canStart())
        val replacement = session.queuedUpdates(11, true, cue).single()
        assertTrue(replacement.canStart())
        assertArrayEquals(byteArrayOf(0), fields(replacement.frame)[0x12])
        assertTrue(replacement.commitStart!!.invoke())
        assertFalse(fields(session.updates(10_011, true, cue).single()).containsKey(0x12))
    }

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
