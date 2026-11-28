package io.github.hypercar.navigation

import org.junit.Assert.*
import org.junit.Test

class CarWithNavigationTest {
    @Test fun identicalGuidanceAfterStopCannotReuseThePreviousRoute() {
        val state = CarWithNavigationState()
        val token = state.begin()
        val arrow = CarWithCueMapper.map(2, 7, 35, "测试路")!!
        state.update(token, arrow, 1_000)
        val before = state.snapshot(1_000)
        state.update(token, CarWithCueMapper.map(3, null, null, ""), 1_001)
        state.update(token, arrow, 1_002)
        val after = state.snapshot(1_002)
        assertNotEquals(before, after)
        state.update(token, arrow, 1_003)
        assertEquals(after, state.snapshot(1_003))
    }

    @Test fun lossOfOneGuidanceSampleHidesTheArrowWithoutEndingTheRoute() {
        val state = CarWithNavigationState()
        val token = state.begin()
        val arrow = CarWithCueMapper.map(2, 7, 35, "测试路")!!
        assertTrue(state.update(token, arrow, 1_000))
        val route = state.snapshot(1_000)?.routeGeneration
        assertNotNull(route)
        assertTrue(state.update(token, null, 1_001))
        assertNull(state.snapshot(1_001))
        assertEquals(route, state.ownedRouteGeneration(1_002))
        assertTrue(state.update(token, arrow, 1_002))
        assertEquals(route, state.snapshot(1_002)?.routeGeneration)
    }

    @Test fun repeatedMissingGuidanceDoesNotExtendOwnershipAndAResetEndsItImmediately() {
        val state = CarWithNavigationState()
        val token = state.begin()
        val arrow = CarWithCueMapper.map(2, 7, 35, "测试路")!!
        state.update(token, arrow, 1_000)
        val route = state.ownedRouteGeneration(1_000)
        state.update(token, null, 2_000)
        state.update(token, null, 46_999)
        assertEquals(route, state.ownedRouteGeneration(47_000))
        assertNull(state.ownedRouteGeneration(47_001))
        state.update(token, arrow, 48_000)
        assertNotEquals(route, state.ownedRouteGeneration(48_000))
        state.update(token, null, 48_001)
        state.reset(token)
        assertNull(state.ownedRouteGeneration(48_002))
    }

    @Test fun carLifeTurnIsTranslatedAndRoadAndMetersArePreserved() {
        val cue = CarWithCueMapper.map(2, 5, 35, "测试路")!!
        assertEquals(4, cue.maneuver) // CarLife 5 is U-turn; CP 5 is follow-road.
        assertEquals(35L, cue.distanceMeters)
        assertEquals("测试路", cue.nextRoad)
        assertEquals("CarWith", cue.sourceName) // protocol doesn't identify which map app
        assertEquals(1, CarWithCueMapper.map(1, 7, 100, "")!!.maneuver)
        assertEquals(2, CarWithCueMapper.map(2, 3, 100, "")!!.maneuver)
    }
    @Test fun cancellationIsNotArrivalAndUnknownTurnsDoNotInventArrows() {
        val stop = CarWithCueMapper.map(3, null, null, "")!!
        assertEquals(0, stop.state); assertNull(stop.maneuver)
        assertNull(CarWithCueMapper.map(2, 99, 50, "路名")!!.maneuver)
        assertNull(CarWithCueMapper.map(2, null, 50, "路名"))
    }
    @Test fun rejectsBadActionsDistancesAndOversizedRoad() {
        assertNull(CarWithCueMapper.map(4, 7, 5, ""))
        assertNull(CarWithCueMapper.map(2, 7, -1, ""))
        assertNull(CarWithCueMapper.map(2, 7, Long.MAX_VALUE, ""))
        assertNull(CarWithCueMapper.map(2, 999, 5, ""))
        assertNull(CarWithCueMapper.map(2, 7, 5, "路".repeat(200)))
    }
    @Test fun nativeRetriesRejectLateCallbacksAndResetClearsTheArrow() {
        val state = CarWithNavigationState()
        val cue = CarWithCueMapper.map(2, 5, 35, "测试路")!!
        val old = state.begin(); assertTrue(state.update(old, cue, 1000))
        val fresh = state.begin(); assertNull(state.snapshot(1000))
        assertFalse(state.update(old, cue, 1001))
        assertTrue(state.update(fresh, cue, 1001))
        assertEquals(cue, state.snapshot(1001)?.copy(routeGeneration = null))
        assertTrue(state.update(fresh, null, 1002)); assertNull(state.snapshot(1002))
        assertTrue(state.update(fresh, cue, 1003))
        state.clear(); assertFalse(state.update(fresh, cue, 1004))
    }
    @Test fun unchangedStructuredCueSurvivesAStoplightAndExplicitStopClearsIt() {
        val s = CarWithNavigationState(); val token = s.begin()
        val cue = CarWithCueMapper.map(2, 5, 35, "测试路")!!
        s.update(token, cue, 1000); assertEquals(cue, s.snapshot(601_000)?.copy(routeGeneration = null))
        s.update(token, cue, 100_000)
        s.update(token, CarWithCueMapper.map(3, null, null, ""), 100_001)
        assertNull(s.snapshot(100_001))
    }
}
