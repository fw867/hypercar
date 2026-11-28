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

    @Test fun onlyAnExplicitEndAdvancesTheRouteAfterNotificationLoss() {
        val store = NavigationCueStore()
        val generation = store.generation()
        store.put(generation, "route", cue, 1_000)
        store.remove("route")
        assertEquals(0L, store.explicitRouteEnd())
        store.put(generation, "arrival", cue.copy(state = 2), 2_000)
        store.put(generation, "arrival", cue.copy(state = 2), 3_000)
        assertEquals(2_000L, store.explicitRouteEnd())
    }

    @Test fun aLateActivePostCannotReplaceArrival() {
        val store = NavigationCueStore()
        val generation = store.generation()
        store.put(generation, "route", cue, 1_000)
        store.put(generation, "route", cue.copy(state = 2), 2_000)
        store.put(generation, "route", cue, 1_900)
        assertEquals(2, store.snapshot(2_100)?.state)
        assertEquals(2_000L, store.explicitRouteEnd())
    }

    @Test fun olderAndroidPostAndRemovalCannotReplaceNewerSameKeyGuidance() {
        val store = NavigationCueStore()
        val generation = store.generation()
        val next = cue.copy(distanceMeters = 50)
        store.put(generation, "route", cue, 1_000, postTime = 10_000)
        store.put(generation, "route", next, 2_000, postTime = 11_000)
        store.put(generation, "route", cue, 2_001, postTime = 10_000)
        store.remove("route", postTime = 10_000)
        assertEquals(next, store.snapshot(2_002))
    }

    @Test fun aSupersededKeyCannotEndTheCurrentRouteWithALaterAndroidPost() {
        val store = NavigationCueStore()
        val generation = store.generation()
        val next = cue.copy(instruction = "新路线")
        store.put(generation, "old", cue, 1_000, postTime = 11_000)
        store.put(generation, "next", next, 1_100, postTime = 11_100)
        store.put(generation, "old", cue.copy(state = 2), 1_200, postTime = 11_200)
        assertEquals(next, store.snapshot(1_200))
        assertEquals(0L, store.explicitRouteEnd())
    }

    @Test fun aSupersededKeyCannotTakeBackGuidanceWithALaterAndroidPost() {
        val store = NavigationCueStore()
        val generation = store.generation()
        val next = cue.copy(instruction = "新路线")
        store.put(generation, "old", cue, 1_000, postTime = 11_000)
        store.put(generation, "next", next, 1_100, postTime = 11_100)
        store.put(generation, "old", cue, 1_300, postTime = 11_300)
        assertEquals(next, store.snapshot(1_300))
        store.put(generation, "next", next.copy(distanceMeters = 50), 1_350, postTime = 11_350)
        assertEquals(50L, store.snapshot(1_350)?.distanceMeters)
    }

    @Test fun aRemovedKeyCanStartAfterSessionClearWithoutReplayingItsOldPost() {
        val store = NavigationCueStore()
        val oldGeneration = store.generation()
        store.put(oldGeneration, "first", cue, 1_000, postTime = 11_000)
        store.put(oldGeneration, "second", cue, 2_000, postTime = 12_000)
        store.remove("first", postTime = 11_000, at = 2_500)
        store.clear()
        store.put(store.generation(), "first", cue, 3_000, postTime = 11_000)
        assertNull(store.snapshot(3_000))
        val next = cue.copy(instruction = "新路线")
        store.put(store.generation(), "first", next, 5_000, postTime = 15_000)
        assertEquals(next, store.snapshot(5_000))
    }

    @Test fun aNewRouteCanReuseTheNotificationKeyAfterArrivalWithoutRemoval() {
        val store = NavigationCueStore()
        val generation = store.generation()
        val next = NavigationCue("左转进入新路", 1, 300)
        store.put(generation, "route", cue, 1_000, postTime = 11_000)
        val before = store.clusterRoute(1_000).routeGeneration
        store.put(generation, "route", cue.copy(state = 2), 2_000, postTime = 12_000)
        assertFalse(store.put(generation, "route", cue.copy(distanceMeters = 50), 2_050, postTime = 12_050))
        assertTrue(store.put(generation, "route", next, 5_000, postTime = 15_000))
        store.remove("route", postTime = 12_000, at = 5_001)
        assertFalse(store.put(generation, "route", cue, 5_002, postTime = 15_002))
        val current = store.clusterRoute(5_002)
        assertEquals(next, current.cue)
        assertEquals(5_000L, current.confirmedStartedAt)
        assertNotEquals(before, current.routeGeneration)
    }

    @Test fun theSameStillActiveSystemPostCanBeSeenAfterListenerReconnect() {
        val store = NavigationCueStore(maxAgeMillis = 1_000)
        store.put(store.generation(), "route", cue, 1_000, postTime = 11_000)
        val before = store.clusterRoute(1_000).routeGeneration
        store.disconnectListener()
        val generation = store.generation()
        assertNull(store.snapshot(1_500))
        assertFalse(store.put(generation, "route", cue, 1_500, postTime = 11_000))
        assertTrue(store.restoreActiveNotification(generation, "route", cue, 11_000, 1_500))
        assertEquals(cue, store.snapshot(1_500))
        assertEquals(before, store.clusterRoute(1_500).routeGeneration)
        assertEquals(1_000L, store.timedSnapshot(1_500)?.updatedAt)
        assertNull(store.snapshot(2_001))
    }

    @Test fun reconnectCannotRestoreARemovedOrExpiredNotification() {
        val store = NavigationCueStore(maxAgeMillis = 1_000)
        store.put(store.generation(), "route", cue, 1_000, postTime = 11_000)
        store.disconnectListener()
        assertFalse(store.restoreActiveNotification(store.generation(), "route", cue, 11_000, 2_001))
        store.finishActiveSnapshot(store.generation())
        assertFalse(store.restoreActiveNotification(store.generation(), "route", cue, 11_000, 1_500))

        store.put(store.generation(), "route", cue, 3_000, postTime = 13_000)
        store.disconnectListener()
        store.remove("route", postTime = 13_000, at = 3_100)
        assertFalse(store.restoreActiveNotification(store.generation(), "route", cue, 13_000, 3_101))
        assertNull(store.snapshot(3_101))
    }

    @Test fun aGreaterDistanceForTheSameTurnCanConfirmARestartAfterArrival() {
        val store = NavigationCueStore()
        val generation = store.generation()
        store.put(generation, "route", cue, 1_000, postTime = 11_000)
        store.put(generation, "route", cue.copy(distanceMeters = 10), 1_900, postTime = 11_900)
        store.put(generation, "route", cue.copy(state = 2), 2_000, postTime = 12_000)
        assertFalse(store.put(generation, "route", cue, 2_050, postTime = 12_050))
        val next = cue.copy(distanceMeters = 400)
        assertTrue(store.put(generation, "route", next, 2_100, postTime = 12_100))
        assertFalse(store.put(generation, "route", cue, 2_101, postTime = 12_101))
        assertEquals(next, store.snapshot(2_100))
    }
}
