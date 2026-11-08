package io.github.hypercar.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationCueStoreTest {
    private val cue = NavigationCue("右转", 2, 100)
    @Test fun doesNotReplayExpiredOrFutureData() {
        val s = NavigationCueStore(); s.put(s.generation(), "route", cue, 1000)
        assertNull(s.snapshot(999)); assertEquals(cue, s.snapshot(91_000)); assertNull(s.snapshot(91_001))
    }
    @Test fun clearedSessionRejectsLateCallback() {
        val s = NavigationCueStore(); val old = s.generation(); s.clear(); s.put(old, "old", cue, 1000)
        assertNull(s.snapshot(1000))
        s.put(s.generation(), "new", cue, 1000); assertEquals(cue, s.snapshot(1000))
    }
    @Test fun removingOtherNotificationCannotEraseCurrentRoute() {
        val s = NavigationCueStore(); s.put(s.generation(), "route", cue, 1000)
        s.remove("other"); assertEquals(cue, s.snapshot(1000))
        s.remove("route"); assertNull(s.snapshot(1000))
    }
}
