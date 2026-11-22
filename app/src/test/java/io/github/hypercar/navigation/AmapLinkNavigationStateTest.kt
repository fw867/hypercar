package io.github.hypercar.navigation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.iap2.Iap2Csm
import io.github.hypercar.iap2.RouteGuidanceSession

class AmapLinkNavigationStateTest {
    // Shape and non-sensitive numeric fields from the real 598 callback; road text is synthetic.
    private fun info(turn: Int = 3, distance: Long = 1) = JSONObject()
        .put("curManeuverID", turn).put("segmentRemainDist", distance)
        .put("routeRemainDist", 31715).put("nextRouteName", "测试路")

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
