package io.github.hypercar.navigation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

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
        state.accept(current, 200, null, 5)
        assertEquals(2, state.snapshot(6)!!.state)
        state.clear(); assertNull(state.snapshot(7))
    }
    @Test fun naviModeSnapshotAfterGuidanceDoesNotClearTheCurrentArrow() {
        val state = AmapLinkNavigationState(); val token = state.begin()
        state.accept(token, 4, info(), 0)
        // The real SDK key-info refresh emits type 7 after type 4, not as a stop event.
        assertFalse(state.accept(token, 7, JSONObject().put("naviMode", 0).put("naviType", 2), 1))
        assertEquals(2, state.snapshot(1)!!.maneuver)
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
