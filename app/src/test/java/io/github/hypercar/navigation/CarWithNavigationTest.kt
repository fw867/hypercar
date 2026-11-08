package io.github.hypercar.navigation

import org.junit.Assert.*
import org.junit.Test

class CarWithNavigationTest {
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
        assertEquals(cue, state.snapshot(1001))
        assertTrue(state.update(fresh, null, 1002)); assertNull(state.snapshot(1002))
        assertTrue(state.update(fresh, cue, 1003))
        state.clear(); assertFalse(state.update(fresh, cue, 1004))
    }
    @Test fun unchangedStructuredCueSurvivesAStoplightAndExplicitStopClearsIt() {
        val s = CarWithNavigationState(); val token = s.begin()
        val cue = CarWithCueMapper.map(2, 5, 35, "测试路")!!
        s.update(token, cue, 1000); assertEquals(cue, s.snapshot(601_000))
        s.update(token, cue, 100_000)
        s.update(token, CarWithCueMapper.map(3, null, null, ""), 100_001)
        assertNull(s.snapshot(100_001))
    }
}
