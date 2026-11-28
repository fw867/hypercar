package io.github.hypercar.navigation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.iap2.Iap2Csm
import io.github.hypercar.iap2.RouteGuidanceSession

class AmapLinkNavigationStateTest {
    @Test fun hudUsesNotificationWhenLinkExpiresOrTheNewBindingHasNoUsableTurn() {
        val state = AmapLinkNavigationState()
        val initial = state.begin()
        assertTrue(state.accept(initial, 4, info(), 1_000))
        val notification = ClusterRouteObservation(NavigationCue("通知左转", 1, 80), 16_000, null, guidanceAt = 16_000)
        assertEquals(notification.cue, state.hudSnapshot(16_001, notification))
        val rebound = state.begin()
        assertEquals(notification.cue, state.hudSnapshot(16_002, notification))
        assertTrue(state.accept(rebound, 4, info(turn = 300), 16_003))
        assertEquals(notification.cue, state.hudSnapshot(16_004, notification))
        assertTrue(state.accept(rebound, 4, info(), 16_005))
        assertEquals(2, state.hudSnapshot(16_006, notification)?.maneuver)
    }

    @Test fun hudDoesNotReviveANotificationOlderThanAnExplicitLinkEnd() {
        val state = AmapLinkNavigationState()
        val token = state.begin()
        assertTrue(state.accept(token, 4, info(), 1_000))
        val old = ClusterRouteObservation(NavigationCue("通知左转", 1, 80), 1_900, null, guidanceAt = 1_900)
        assertTrue(state.accept(token, 100, null, 2_000))
        assertEquals(0, state.hudSnapshot(2_001, old)?.state)
        state.begin()
        assertNull(state.hudSnapshot(2_002, old))
        val next = old.copy(startedAt = 2_100, confirmedStartedAt = 2_100, guidanceAt = 2_100)
        assertEquals(next.cue, state.hudSnapshot(2_101, next))
    }

    @Test fun repeatedOldNotificationDoesNotTakeOverAfterTerminalButANewRouteCan() {
        val link = AmapLinkNavigationState()
        val token = link.begin()
        val store = NavigationCueStore()
        val notice = NavigationCue("通知左转", 1, 80)
        assertTrue(link.accept(token, 4, info(), 1_000))
        store.put(store.generation(), "old", notice, 1_100)
        assertTrue(link.accept(token, 100, null, 2_000))
        store.put(store.generation(), "old", notice, 2_100)
        assertEquals(0, link.hudSnapshot(2_200, store.clusterRoute(2_200))?.state)
        store.remove("old")
        store.put(store.generation(), "old", notice, 2_300)
        assertFalse(link.ownsRoute(2_300, store.clusterRoute(2_300)))
        val next = notice.copy(instruction = "新路线")
        store.put(store.generation(), "new", next, 4_000)
        assertEquals("新路线", link.hudSnapshot(4_000, store.clusterRoute(4_000))?.instruction)
        assertTrue(link.ownsRoute(4_000, store.clusterRoute(4_000)))
    }

    @Test fun ownershipSurvivesTemporaryLossButEndsOnExplicitLinkStop() {
        val link = AmapLinkNavigationState()
        val token = link.begin()
        val none = ClusterRouteObservation(null, null, null)
        assertTrue(link.accept(token, 4, info(), 1_000))
        val firstGeneration = link.hudSnapshot(1_000, none)?.routeGeneration
        assertNotNull(firstGeneration)
        assertEquals(0, link.hudSnapshot(16_001, none)?.state)
        assertTrue(link.ownsRoute(16_001, none))
        link.clear(token)
        val rebound = link.begin()
        assertTrue(link.ownsRoute(16_002, none))
        assertTrue(link.accept(rebound, 4, info(), 16_010))
        assertEquals(firstGeneration, link.hudSnapshot(16_010, none)?.routeGeneration)
        assertFalse(link.ownsRoute(61_011, none))
        assertTrue(link.accept(rebound, 100, null, 62_000))
        assertFalse(link.ownsRoute(62_000, none))
        assertTrue(link.accept(rebound, 4, info(), 64_000))
        assertNotEquals(firstGeneration, link.hudSnapshot(64_000, none)?.routeGeneration)
    }

    @Test fun notificationArrivalRetiresLinkGenerationBeforeQueuedGuidanceCanReturn() {
        val link = AmapLinkNavigationState()
        val token = link.begin()
        val store = NavigationCueStore()
        val guidance = NavigationCue("通知左转", 1, 80)
        store.put(store.generation(), "route", guidance, 900, postTime = 10_900)
        assertTrue(link.accept(token, 4, info(), 1_000))
        val activeGeneration = link.hudSnapshot(1_000, store.clusterRoute(1_000))?.routeGeneration
        assertNotNull(activeGeneration)
        store.put(store.generation(), "route", guidance.copy(state = 2), 1_050, postTime = 11_050)
        val arrival = store.clusterRoute(1_050)
        link.observeNotification(1_050, arrival)
        assertFalse(link.accept(token, 4, info(), 1_060))
        assertEquals(2, link.hudSnapshot(1_050, arrival)?.state)
        assertFalse(link.ownsRoute(1_050, arrival))
        assertEquals(2, link.hudSnapshot(1_060, arrival)?.state)
        link.clear(token)
        val rebound = link.begin()
        assertFalse(link.accept(rebound, 4, info(turn = 2), 1_070))
        assertTrue(link.accept(rebound, 4, info(), 3_050))
        assertNotEquals(activeGeneration, link.hudSnapshot(3_050, arrival)?.routeGeneration)
    }

    @Test fun cachedNotificationCannotReturnAfterUsableReboundLinkGuidanceExpires() {
        val link = AmapLinkNavigationState()
        val initial = link.begin()
        val store = NavigationCueStore()
        val notice = NavigationCue("旧路线", 1, 80)
        assertTrue(link.accept(initial, 4, info(), 1_000))
        store.put(store.generation(), "old", notice, 1_100, postTime = 11_100)
        link.clear(initial)
        val rebound = link.begin()
        assertEquals("旧路线", link.hudSnapshot(9_999, store.clusterRoute(9_999))?.instruction)
        assertTrue(link.accept(rebound, 4, info(turn = 2), 10_000))
        assertEquals(0, link.hudSnapshot(25_001, store.clusterRoute(25_001))?.state)
    }

    @Test fun androidRemovalAllowsAFreshSameKeyRouteAfterLinkStops() {
        val link = AmapLinkNavigationState()
        val token = link.begin()
        val store = NavigationCueStore()
        val old = NavigationCue("旧路线", 1, 80)
        val next = NavigationCue("新路线", 2, 120)
        assertTrue(link.accept(token, 4, info(), 1_000))
        store.put(store.generation(), "route", old, 1_100, postTime = 11_100)
        assertTrue(link.accept(token, 100, null, 2_000))
        link.clear(token)
        store.remove("route", postTime = 12_200)
        store.put(store.generation(), "route", old, 2_300, postTime = 11_100)
        assertNull(link.hudSnapshot(2_300, store.clusterRoute(2_300)))
        store.put(store.generation(), "route", next, 6_000, postTime = 16_000)
        assertEquals("新路线", link.hudSnapshot(6_000, store.clusterRoute(6_000))?.instruction)
    }

    @Test fun notificationFallbackRetainsTheLinkLeaseUntilAReplacementAfterConfirmedRemoval() {
        val link = AmapLinkNavigationState()
        val token = link.begin()
        val store = NavigationCueStore()
        val old = NavigationCue("旧路线", 1, 80)
        val next = NavigationCue("新路线", 2, 120)
        assertTrue(link.accept(token, 4, info(), 1_000))
        store.put(store.generation(), "first", old, 1_100, postTime = 11_100)
        val firstGeneration = link.ownedRouteGeneration(1_100, store.clusterRoute(1_100))
        assertNotNull(firstGeneration)
        link.clear(token)
        assertEquals(firstGeneration, link.hudSnapshot(1_200, store.clusterRoute(1_200))?.routeGeneration)
        store.remove("first", postTime = 11_100, at = 1_500)
        store.put(store.generation(), "second", next, 4_000, postTime = 14_000)
        val replacement = store.clusterRoute(4_000)
        link.observeNotification(4_000, replacement)
        assertNotEquals(firstGeneration, link.ownedRouteGeneration(4_000, replacement))
        assertEquals(replacement.routeGeneration, link.hudSnapshot(4_000, replacement)?.routeGeneration)
    }

    @Test fun lateLinkGuidanceCannotTakeOverAfterANewNotificationRouteStarts() {
        val link = AmapLinkNavigationState { 1_700_000_000_000L }
        val token = link.begin()
        val store = NavigationCueStore()
        val old = NavigationCue("右转进入旧路", 2, 80)
        val next = NavigationCue("左转进入新路", 1, 300)
        val stale = info(distance = 80).put("nextRouteName", "旧路").put("routeRemainTime", 420)
        assertTrue(link.accept(token, 4, stale, 1_000))
        store.put(store.generation(), "first", old, 1_100, postTime = 11_100)
        val cluster = ClusterNavigationRoute()
        cluster.update(1_100, link.clusterRoute(1_100), store.clusterRoute(1_100))
        store.remove("first", postTime = 11_100, at = 2_000)
        store.put(store.generation(), "second", next, 17_000, postTime = 27_000)
        val replacement = store.clusterRoute(17_000)
        link.observeNotification(17_000, replacement)
        assertFalse(link.accept(token, 4, stale, 17_001))
        val fallback = link.hudSnapshot(17_001, replacement)
        assertEquals(300L, fallback?.distanceMeters)
        assertNull(fallback?.estimatedArrivalEpochSeconds)
        assertEquals(replacement.routeGeneration, fallback?.routeGeneration)

        val matching = info(turn = 2, distance = 298).put("nextRouteName", "新路").put("routeRemainTime", 370)
        assertTrue(link.accept(token, 4, matching, 17_002))
        val current = link.hudSnapshot(17_002, replacement)
        assertEquals(298L, current?.distanceMeters)
        assertEquals(1_700_000_370L, current?.estimatedArrivalEpochSeconds)
        assertEquals(replacement.routeGeneration, current?.routeGeneration)
        assertFalse(link.accept(token, 4, stale, 17_003))
        val instrument = cluster.update(17_003, link.clusterRoute(17_003), replacement)
        assertEquals(1L, instrument.round)
        assertEquals(298L, instrument.cue?.distanceMeters)
    }

    @Test fun anOldNotificationCannotStopTheRouteReportedByTheReboundLink() {
        val link = AmapLinkNavigationState()
        val initial = link.begin()
        val store = NavigationCueStore()
        val notice = NavigationCue("旧路线", 1, 80)
        assertTrue(link.accept(initial, 4, info(), 1_000))
        store.put(store.generation(), "old", notice, 1_100, postTime = 11_100)
        link.clear(initial)
        val rebound = link.begin()
        assertTrue(link.accept(rebound, 4, info(turn = 2), 10_000))
        store.put(store.generation(), "old", notice.copy(state = 2), 10_500, postTime = 20_500)
        val oldEnd = store.clusterRoute(10_500)
        link.observeNotification(10_500, oldEnd)
        assertEquals(1, link.hudSnapshot(10_500, oldEnd)?.state)
        assertEquals(0, link.hudSnapshot(25_001, oldEnd)?.state)
    }

    // Shape and non-sensitive numeric fields from the real 598 callback; road text is synthetic.
    private fun info(turn: Int = 3, distance: Long = 1) = JSONObject()
        .put("curManeuverID", turn).put("segmentRemainDist", distance)
        .put("routeRemainDist", 31715).put("nextRouteName", "测试路")

    @Test fun partialTurnsNeverExtendAFullGuidanceSample() {
        val state = AmapLinkNavigationState()
        val token = state.begin()
        assertTrue(state.accept(token, 4, info().put("routeRemainTime", 420), 1_000))
        val firstEta = state.snapshot(1_000)?.estimatedArrivalEpochSeconds
        assertNotNull(firstEta)
        assertTrue(state.accept(token, 5, JSONObject().put("maneuverID", 3), 15_999))
        assertEquals(firstEta, state.snapshot(16_000)?.estimatedArrivalEpochSeconds)
        assertTrue(state.accept(token, 5, JSONObject().put("maneuverID", 3), 16_001))
        assertEquals(0, state.snapshot(16_001)?.state)
        assertNull(state.snapshot(16_001)?.estimatedArrivalEpochSeconds)
        assertNull(state.snapshot(16_001)?.remainingDistanceMeters)
        assertNull(state.clusterRoute(16_001).cue)
    }

    @Test fun hudEtaUsesUnixSecondsFromTheRealLinkRouteDuration() {
        var wallClock = 1_700_000_000_000L
        val state = AmapLinkNavigationState { wallClock }
        val token = state.begin()
        val session = RouteGuidanceSession {}
        session.handle(Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, 42)))
        assertTrue(state.accept(token, 4, info().put("routeRemainTime", 375), 1000))
        wallClock += 45_000
        val route = session.updates(2000, true, state.snapshot(2000)).last()
        val eta = Iap2Csm.parseParams(route.body).single { it.id == 0x05 }.value
        assertEquals(8, eta.size)
        assertEquals(1_700_000_375L, java.nio.ByteBuffer.wrap(eta).long)
        assertTrue(state.accept(token, 4, info().put("routeRemainTime", 0), 3000))
        val withoutEta = session.updates(3000, true, state.snapshot(3000)).last()
        assertFalse(Iap2Csm.parseParams(withoutEta.body).any { it.id == 0x05 })
    }

    @Test fun realType4UsesSegmentDistanceAndMapsAMapDirectionInsteadOfCarLifeEnum() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        assertTrue(state.accept(token, 4, info(), 0))
        val cue = state.snapshot(1)!!
        assertEquals(2, cue.maneuver) // AMap3=right; CarLife3 would mean a different enum.
        assertEquals(1L, cue.distanceMeters)
        assertEquals("测试路", cue.nextRoad)
        state.accept(token, 4, info(8, 36), 2)
        assertEquals(4, state.snapshot(2)!!.maneuver)
    }
    @Test fun newDirectionNeverReusesThePreviousRoadAndDistance() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        state.accept(token, 4, info(), 0)
        state.accept(token, 5, JSONObject().put("maneuverID", 2), 1)
        val cue = state.snapshot(1)!!
        assertEquals(1, cue.maneuver); assertNull(cue.distanceMeters); assertEquals("", cue.nextRoad)
    }
    @Test fun stopAndArrivalAreAuthoritativeAndOldBindingCannotReviveANewSession() {
        val state = AmapLinkNavigationState(); val old = state.begin()
        state.accept(old, 4, info(), 0); state.accept(old, 100, null, 1)
        assertEquals(0, state.snapshot(2)!!.state)
        val current = state.begin()
        state.clear(old)
        assertFalse(state.accept(old, 4, info(), 3))
        assertTrue(state.accept(current, 4, info(), 4))
        state.accept(current, 200, JSONObject().put("isArrived", true), 5)
        assertEquals(2, state.snapshot(6)!!.state)
        state.clear(); assertNull(state.snapshot(7))
    }

    @Test fun anExpiredHudSampleDoesNotEndTheObservedRoute() {
        val state = AmapLinkNavigationState()
        val token = state.begin()
        assertNull(state.routeInProgress())
        assertTrue(state.accept(token, 4, info(), 0))
        assertEquals(0, state.snapshot(16_000)?.state)
        assertEquals(true, state.routeInProgress())
        assertEquals(0L, state.explicitRouteEnd())
        assertTrue(state.accept(token, 100, null, 16_001))
        assertEquals(false, state.routeInProgress())
        assertEquals(16_001L, state.explicitRouteEnd())
        val rebound = state.begin()
        assertTrue(state.accept(rebound, 100, null, 16_002))
        assertEquals(16_001L, state.explicitRouteEnd())
    }

    @Test fun anExpiredLinkSampleLetsTheInstrumentUseFreshNotificationGuidance() {
        val state = AmapLinkNavigationState()
        val token = state.begin()
        assertTrue(state.accept(token, 4, info(), 1_000))
        val route = ClusterNavigationRoute()
        val noNotification = ClusterRouteObservation(null, null, null)
        assertEquals(0L, route.update(1_000, state.clusterRoute(1_000), noNotification).round)
        val cue = NavigationCue("左转", 1, 80)
        val fallback = ClusterRouteObservation(cue, 16_020, null)
        assertEquals(0, state.snapshot(16_100)?.state)
        assertEquals(ClusterNavigationRoute.Status(0, true, cue),
            route.update(16_100, state.clusterRoute(16_100), fallback))
        val terminal = ClusterRouteObservation(null, null, 17_000)
        assertEquals(ClusterNavigationRoute.Status(1, true, cue), route.update(18_700,
            state.clusterRoute(18_700), terminal.copy(cue = cue, startedAt = 18_700)))
    }

    @Test fun aNewLinkBindingUsesItsOwnFirstValidGuidanceForTheHandoffWindow() {
        val state = AmapLinkNavigationState()
        val initial = state.begin()
        val route = ClusterNavigationRoute()
        val notification = ClusterRouteObservation(NavigationCue("左转", 1, 80), 1_000, null)
        assertTrue(state.accept(initial, 4, info(), 1_000))
        assertEquals(0L, route.update(1_000, state.clusterRoute(1_000), notification).round)
        assertEquals(0L, route.update(16_100, state.clusterRoute(16_100), notification).round)
        state.clear(initial)
        val terminal = ClusterRouteObservation(null, null, 18_000)
        assertFalse(route.update(20_000, state.clusterRoute(20_000), terminal).active)
        val rebound = state.begin()
        assertFalse(state.accept(rebound, 4, null, 23_000))
        assertTrue(state.accept(rebound, 4, info(), 25_000))
        val observation = state.clusterRoute(25_000)
        assertEquals(25_000L, observation.startedAt)
        assertEquals(1L, route.update(25_000, observation, terminal).round)
        // HUD still recognizes an explicit terminal after a brief rebind with no fresh arrow.
        val last = state.begin()
        assertTrue(state.accept(last, 100, null, 25_010))
        assertEquals(25_010L, state.explicitRouteEnd())
    }

    @Test fun aNotificationCanOwnTheNextRouteAfterLinkUnbinds() {
        val state = AmapLinkNavigationState()
        val token = state.begin()
        val route = ClusterNavigationRoute()
        val notification = ClusterRouteObservation(NavigationCue("左转", 1, 80), 1_000, null)
        assertTrue(state.accept(token, 4, info(), 1_000))
        route.update(1_000, state.clusterRoute(1_000), notification)
        state.clear(token)
        assertEquals(ClusterNavigationRoute.Status(0, true, notification.cue),
            route.update(2_500, state.clusterRoute(2_500), notification))
        val ended = ClusterRouteObservation(null, null, 3_500)
        route.update(3_500, state.clusterRoute(3_500), ended)
        val next = notification.cue!!.copy(instruction = "新路线")
        assertEquals(ClusterNavigationRoute.Status(1, true, next), route.update(4_000,
            state.clusterRoute(4_000), ClusterRouteObservation(next, 4_000, 3_500)))
    }

    @Test fun aLinkBindingWithoutUsableGuidanceKeepsTheNotificationVisible() {
        val state = AmapLinkNavigationState()
        val token = state.begin()
        val route = ClusterNavigationRoute()
        val notification = ClusterRouteObservation(NavigationCue("左转", 1, 80), 1_000, null)
        route.update(1_000, state.clusterRoute(1_000), notification)
        assertTrue(state.accept(token, 4, info(turn = 300).put("nextRouteName", ""), 1_100))
        assertEquals(ClusterNavigationRoute.Status(0, true, notification.cue),
            route.update(1_100, state.clusterRoute(1_100), notification))
        assertTrue(state.accept(token, 4, info(), 1_200))
        assertEquals(1_200L, state.clusterRoute(1_200).startedAt)
        assertEquals(2, route.update(1_200, state.clusterRoute(1_200), notification).cue?.maneuver)
    }

    @Test fun aLinkTerminalCanConfirmTheEndOfANotificationOnlyInstrumentRoute() {
        val state = AmapLinkNavigationState()
        val route = ClusterNavigationRoute()
        val token = state.begin()
        val notification = ClusterRouteObservation(NavigationCue("左转", 1, 80), 1_000, null)
        assertTrue(state.accept(token, 4, info(turn = 300), 1_100))
        assertEquals(ClusterNavigationRoute.Status(0, true, notification.cue),
            route.update(1_100, state.clusterRoute(1_100), notification))
        assertTrue(state.accept(token, 100, null, 3_000))
        assertEquals(ClusterNavigationRoute.Status(1, false, null),
            route.update(3_000, state.clusterRoute(3_000), notification.copy(cue = null)))
        assertTrue(state.accept(token, 4, info(), 5_000))
        assertEquals(1L, route.update(5_000, state.clusterRoute(5_000), notification.copy(cue = null)).round)
    }

    @Test fun continuingLinkGuidancePreventsAnOldNotificationEndFromRearmingOnRebind() {
        val state = AmapLinkNavigationState()
        val route = ClusterNavigationRoute()
        val token = state.begin()
        val notification = ClusterRouteObservation(NavigationCue("左转", 1, 80), 1_000, null)
        assertTrue(state.accept(token, 4, info(), 1_000))
        route.update(1_000, state.clusterRoute(1_000), notification)
        val terminal = ClusterRouteObservation(null, null, 2_000)
        assertTrue(state.accept(token, 4, info(), 3_500))
        assertEquals(0L, route.update(3_500, state.clusterRoute(3_500), terminal).round)
        state.clear(token)
        val rebound = state.begin()
        assertTrue(state.accept(rebound, 4, info(), 5_000))
        assertEquals(0L, route.update(5_000, state.clusterRoute(5_000), terminal).round)
    }

    @Test fun anExpiredLinkResumingOnTheSameBindingUsesTheResumedGuidanceTime() {
        val state = AmapLinkNavigationState()
        val token = state.begin()
        val route = ClusterNavigationRoute()
        val notification = ClusterRouteObservation(NavigationCue("左转", 1, 80), 1_000, null)
        assertTrue(state.accept(token, 4, info(), 1_000))
        route.update(1_000, state.clusterRoute(1_000), notification)
        route.update(16_100, state.clusterRoute(16_100), notification)
        val ended = ClusterRouteObservation(null, null, 18_000)
        assertFalse(route.update(20_000, state.clusterRoute(20_000), ended).active)
        // An incomplete sample does not start the new run of usable guidance.
        assertTrue(state.accept(token, 4, info(turn = 300).put("nextRouteName", ""), 20_100))
        assertTrue(state.accept(token, 4, info(), 25_000))
        val resumed = state.clusterRoute(25_000)
        assertEquals(25_000L, resumed.startedAt)
        assertEquals(1L, route.update(25_000, resumed, ended).round)
    }
    @Test fun manualExitClearsRouteOnceAndStopsHeartbeats() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        val hud = RouteGuidanceSession {}
        hud.handle(Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, 77)))
        state.accept(token, 4, info(), 0)
        assertEquals(listOf(0x5202, 0x5201), hud.updates(0, true, state.snapshot(0)).map { it.messageId })
        val end = JSONObject().put("isArrived", false).put("action", 0)
        state.accept(token, 200, end, 1000)
        assertEquals(0, state.snapshot(1000)!!.state)
        val clear = hud.updates(1000, true, state.snapshot(1000)).single()
        assertEquals(0x5201, clear.messageId)
        val params = Iap2Csm.parseParams(clear.body)
        assertArrayEquals(byteArrayOf(0, 77), params.single { it.id == 0 }.value)
        assertArrayEquals(byteArrayOf(0), params.single { it.id == 1 }.value)
        state.accept(token, 100, null, 2000)
        state.accept(token, 200, end, 2001)
        assertTrue(hud.updates(12_000, true, state.snapshot(12_000)).isEmpty())
    }

    @Test fun confirmedArrivalSendsStateTwoEvenIfTheStopEventFollowsBeforeSending() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        val hud = RouteGuidanceSession {}
        hud.handle(Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, 77)))
        state.accept(token, 4, info(), 0)
        hud.updates(0, true, state.snapshot(0))
        state.accept(token, 200, JSONObject().put("isArrived", true), 1000)
        state.accept(token, 100, null, 1001)
        // Link delivered a residual type 4 before the Bluetooth pump could send arrival.
        assertFalse(state.accept(token, 4, info(), 1050))
        val arrival = hud.updates(1150, true, state.snapshot(1150)).single()
        assertEquals(0x5201, arrival.messageId)
        assertArrayEquals(byteArrayOf(2), Iap2Csm.parseParams(arrival.body).single { it.id == 1 }.value)
        assertTrue(hud.updates(12_000, true, state.snapshot(12_000)).isEmpty())
        assertTrue(state.accept(token, 4, info(), 12_100))
        assertEquals(1, state.snapshot(12_100)!!.state)
    }

    @Test fun type200WithoutAnArrivalFlagClearsInsteadOfClaimingArrival() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        state.accept(token, 4, info(), 0)
        state.accept(token, 200, JSONObject().put("action", 0), 1)
        assertEquals(0, state.snapshot(1)!!.state)
    }
    @Test fun naviModeSnapshotAfterGuidanceDoesNotClearTheCurrentArrow() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        state.accept(token, 4, info(), 0)
        // The real SDK key-info refresh emits type 7 after type 4, not as a stop event.
        assertFalse(state.accept(token, 7, JSONObject().put("naviMode", 0).put("naviType", 2), 1))
        assertEquals(2, state.snapshot(1)!!.maneuver)
    }
    @Test fun mapCardKeepsTripFactsAndRecommendedLanesSeparateFromHudManeuver() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        state.accept(token, 4, info().put("curRouteName", "当前测试路").put("routeRemainTime", 420), 100)
        val lanes = JSONObject().put("laneCount", 4)
            .put("backLane", org.json.JSONArray(listOf(0, 3, 3, 3)))
            .put("frontLane", org.json.JSONArray(listOf(255, 3, 3, 3)))
        assertTrue(state.accept(token, 14, lanes, 101))
        val cue = state.snapshot(102)!!
        assertEquals("当前测试路", cue.currentRoad)
        assertEquals(31715L, cue.remainingDistanceMeters)
        assertEquals(420L, cue.remainingTimeSeconds)
        assertEquals(1L, cue.distanceMeters) // Never use total remaining as turn distance.
        assertEquals(listOf(false, true, true, true), cue.lanes.map { it.recommended })
        state.accept(token, 4, info(), 103)
        assertEquals(4, state.snapshot(103)!!.lanes.size)
        state.accept(token, 5, JSONObject().put("maneuverID", 2), 104)
        assertTrue(state.snapshot(104)!!.lanes.isEmpty())
    }
    @Test fun lanesExpireIndependentlyAndDoNotFollowTheSameTurnOntoAnotherRoad() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        val lanes = JSONObject().put("laneCount", 1)
            .put("backLane", org.json.JSONArray(listOf(3))).put("frontLane", org.json.JSONArray(listOf(3)))
        state.accept(token, 4, info().put("nextRouteName", "first"), 0)
        state.accept(token, 14, lanes, 1)
        state.accept(token, 4, info().put("nextRouteName", "first"), 15_001)
        assertEquals(1, state.snapshot(15_001)!!.lanes.size)
        assertTrue(state.snapshot(15_002)!!.lanes.isEmpty())
        state.accept(token, 14, lanes, 15_003)
        state.accept(token, 4, info().put("nextRouteName", "second"), 15_004)
        assertTrue(state.snapshot(15_004)!!.lanes.isEmpty())
    }

    @Test fun sameRoadTurnsDoNotShareLanesAfterReachingTheFirstJunction() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        fun guidance(distance: Int) = info().put("curRouteName", "Main").put("nextRouteName", "Main")
            .put("segmentRemainDist", distance)
        val lanes = JSONObject().put("laneCount", 1)
            .put("backLane", org.json.JSONArray(listOf(3))).put("frontLane", org.json.JSONArray(listOf(3)))
        state.accept(token, 4, guidance(5), 0)
        state.accept(token, 14, lanes, 1)
        state.accept(token, 4, guidance(6), 2)
        assertEquals(1, state.snapshot(2)!!.lanes.size)
        state.accept(token, 4, guidance(40), 1_000)
        assertTrue(state.snapshot(1_000)!!.lanes.isEmpty())
        state.accept(token, 4, guidance(5).put("routeRemainDist", 120), 2_000)
        state.accept(token, 14, lanes, 2_001)
        state.accept(token, 4, guidance(12).put("routeRemainDist", 114), 2_002)
        assertTrue(state.snapshot(2_002)!!.lanes.isEmpty())
    }

    @Test fun malformedLaneDataDoesNotInventDirectionsAndHideClearsOldLanes() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        state.accept(token, 4, info(), 0)
        assertFalse(state.accept(token, 14, JSONObject().put("laneCount", 3)
            .put("backLane", org.json.JSONArray(listOf(0))).put("frontLane", org.json.JSONArray(listOf(0))), 1))
        assertTrue(state.snapshot(1)!!.lanes.isEmpty())
        assertTrue(state.accept(token, 14, JSONObject().put("laneCount", 0), 2))
        assertEquals(0, state.snapshot(15001)!!.state) // Lane updates cannot keep stale maneuver alive.
        state.accept(token, 100, null, 15002)
        assertFalse(state.accept(token, 14, JSONObject().put("laneCount", 0), 15003))
    }
    @Test fun laneCallbackMayPrecedeFirstGuidanceButCannotCrossBindingGeneration() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        val lanes = JSONObject().put("laneCount", 1)
            .put("backLane", org.json.JSONArray(listOf(3))).put("frontLane", org.json.JSONArray(listOf(3)))
        assertTrue(state.accept(token, 14, lanes, 0)); assertNull(state.snapshot(1))
        state.accept(token, 4, info(), 2)
        assertEquals(1, state.snapshot(2)!!.lanes.size)
        val fresh = state.begin()
        assertFalse(state.accept(token, 14, lanes, 3))
        state.accept(fresh, 4, info(), 4)
        assertTrue(state.snapshot(4)!!.lanes.isEmpty())
    }
    @Test fun missingUpdatesMalformedNumbersAndUnknownManeuversNeverInventAnArrow() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        assertFalse(state.accept(token, 5, JSONObject().put("maneuverID", 3), 0))
        assertFalse(state.accept(token, 4, info(distance = -1), 1))
        assertFalse(state.accept(token, 4, info().put("curManeuverID", 3.5), 1))
        state.accept(token, 4, info(999), 2)
        assertNull(state.snapshot(3)!!.maneuver)
        assertEquals(0, state.snapshot(15003)!!.state)
        state.accept(token, 4, info(), 15004)
        assertEquals(1, state.snapshot(15004)!!.state)
    }
}
