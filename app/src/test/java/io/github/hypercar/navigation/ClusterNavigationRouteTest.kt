package io.github.hypercar.navigation

import org.junit.Assert.*
import org.junit.Test

class ClusterNavigationRouteTest {
    private val cue = NavigationCue("右转", 2, 100)
    private val absent = ClusterRouteObservation(null, null, null)

    @Test fun historicalFallbackEndDoesNotStartARoundWhenLinkRebinds() {
        val route = ClusterNavigationRoute()
        val link = ClusterRouteObservation(cue, 1_000, null)
        val fallback = ClusterRouteObservation(null, null, 2_000)
        assertEquals(0L, route.update(2_000, link, fallback).round)
        assertEquals(ClusterNavigationRoute.Status(0, true, null), route.update(2_100, link.copy(cue = null), fallback))
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(2_200, link, fallback))
    }

    @Test fun firstPollAfterLinkStopRemembersTheEndWhenTheBindingDisappears() {
        val route = ClusterNavigationRoute()
        val link = AmapLinkNavigationState()
        val token = link.begin()
        val old = ClusterRouteObservation(cue, 1_100, null)
        link.accept(token, 4, org.json.JSONObject().put("curManeuverID", 3).put("segmentRemainDist", 100), 1_000)
        link.accept(token, 100, null, 2_000)
        assertFalse(route.update(2_001, link.clusterRoute(2_001), old).active)
        link.clear(token)
        assertFalse(route.update(2_002, link.clusterRoute(2_002), old).active)
        val next = ClusterRouteObservation(cue.copy(maneuver = 1), 2_100, null)
        assertEquals(next.cue, route.update(2_100, link.clusterRoute(2_100), next).cue)
    }

    @Test fun realEndRearmsOnlyAfterANewRouteAndCannotReuseOldFallbackGuidance() {
        val route = ClusterNavigationRoute()
        val link = ClusterRouteObservation(cue, 1_000, null)
        val fallback = ClusterRouteObservation(cue, 2_000, null)
        route.update(2_000, link, fallback)
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(2_100, link.copy(cue = null), fallback))
        val stopped = ClusterRouteObservation(null, null, 3_000)
        assertEquals(ClusterNavigationRoute.Status(1, false, null), route.update(3_000, stopped, fallback))
        assertEquals(ClusterNavigationRoute.Status(1, true, cue), route.update(4_000, stopped, fallback.copy(startedAt = 4_000)))
        assertEquals(ClusterNavigationRoute.Status(1, false, null), route.update(6_500, stopped,
            ClusterRouteObservation(null, null, 5_000)))
        assertEquals(1L, route.update(6_600, absent, ClusterRouteObservation(null, null, 5_000)).round)
        assertEquals(ClusterNavigationRoute.Status(2, true, cue), route.update(6_700, absent,
            ClusterRouteObservation(cue, 6_700, 5_000)))
    }

    @Test fun fallbackNotificationCannotEndANewerRouteUsingAReplayedTerminal() {
        val store = NavigationCueStore()
        val route = ClusterNavigationRoute()
        val generation = store.generation()
        store.put(generation, "route", cue, 4_000)
        assertTrue(route.update(4_000, absent, store.clusterRoute(4_000)).active)
        store.put(generation, "old-arrival", cue.copy(state = 2), 3_000)
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(5_000, absent, store.clusterRoute(5_000)))
        store.put(generation, "arrival", cue.copy(state = 2), 6_000)
        assertEquals(ClusterNavigationRoute.Status(0, true, null), route.update(6_000, absent, store.clusterRoute(6_000)))
        assertEquals(ClusterNavigationRoute.Status(0, false, null), route.update(7_500, absent, store.clusterRoute(7_500)))
    }

    @Test fun anIgnoredFallbackCueCannotOwnTheFirstVisibleLinkRoute() {
        val route = ClusterNavigationRoute()
        val link = ClusterRouteObservation(cue, 1_000, null)
        val fallback = ClusterRouteObservation(cue, 2_000, null)
        assertEquals(ClusterNavigationRoute.Status(0, false, null), route.update(2_000, link.copy(cue = cue.copy(state = 0)), fallback))
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(2_100, link, fallback))
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(3_000, link,
            ClusterRouteObservation(null, null, 3_000)))
    }

    @Test fun overlappingLinkTakesOverWithoutLosingGuidanceWhenTheNotificationEnds() {
        val route = ClusterNavigationRoute()
        val notification = ClusterRouteObservation(cue, 1_000, null)
        val link = ClusterRouteObservation(cue, 2_100, null)
        val terminal = ClusterRouteObservation(null, null, 2_200)
        assertEquals(0L, route.update(1_000, absent, notification).round)
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(2_100, link, notification))
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(4_000, link, terminal))
        assertEquals(ClusterNavigationRoute.Status(1, false, null), route.update(5_000,
            ClusterRouteObservation(null, null, 5_000), terminal))
    }

    @Test fun linkJoiningShortlyAfterTheNotificationEndsKeepsTheSameRound() {
        val route = ClusterNavigationRoute()
        val terminal = ClusterRouteObservation(null, null, 2_200)
        assertEquals(0L, route.update(1_000, absent, ClusterRouteObservation(cue, 1_000, null)).round)
        assertEquals(ClusterNavigationRoute.Status(0, true, null), route.update(2_200, absent, terminal))
        assertEquals(ClusterNavigationRoute.Status(0, false, null), route.update(3_700, absent, terminal))
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(4_000,
            ClusterRouteObservation(cue, 3_699, null), terminal))
    }

    @Test fun aLinkRouteStartingOutsideTheHandoffWindowGetsANewRound() {
        val route = ClusterNavigationRoute()
        val terminal = ClusterRouteObservation(null, null, 2_200)
        route.update(1_000, absent, ClusterRouteObservation(cue, 1_000, null))
        route.update(3_700, absent, terminal)
        assertEquals(ClusterNavigationRoute.Status(1, true, cue), route.update(4_000,
            ClusterRouteObservation(cue, 3_700, null), terminal))
    }

    @Test fun anExpiredLinkLetsTheNotificationAdvanceAConfirmedNewRoute() {
        val route = ClusterNavigationRoute()
        val link = ClusterRouteObservation(cue, 1_000, null)
        route.update(1_000, link, ClusterRouteObservation(cue, 1_000, null))
        val next = cue.copy(instruction = "下一条路线")
        assertEquals(ClusterNavigationRoute.Status(1, true, next), route.update(16_100,
            link.copy(cue = null), ClusterRouteObservation(next, 15_000, 14_500)))
    }

    @Test fun aNewNotificationRouteAfterAnExplicitEndDoesNotWaitForTheHandoffWindow() {
        val route = ClusterNavigationRoute()
        assertEquals(0L, route.update(1_000, absent, ClusterRouteObservation(cue, 1_000, null)).round)
        assertEquals(ClusterNavigationRoute.Status(1, true, cue), route.update(2_100, absent,
            ClusterRouteObservation(cue, 2_100, 2_000)))
    }

    @Test fun notificationEndAtLinkUnbindUsesTheFirstReboundGuidanceForTheHandoff() {
        for ((start, expectedRound) in listOf(3_999L to 0L, 4_000L to 1L)) {
            val route = ClusterNavigationRoute()
            val active = ClusterRouteObservation(cue, 1_000, null)
            val arrival = ClusterRouteObservation(cue.copy(state = 2), null, 2_500)
            route.update(1_000, active, active)
            assertNull(route.update(2_500, absent, arrival).cue)
            assertFalse(route.update(4_000, absent, arrival).active)
            val rebound = active.copy(startedAt = start)
            assertEquals(ClusterNavigationRoute.Status(expectedRound, true, cue), route.update(4_100, rebound, arrival))
            assertEquals(expectedRound, route.update(4_200, rebound, arrival).round)
            assertTrue(route.update(4_300, absent, arrival).active)
            assertEquals(expectedRound, route.update(6_000, rebound.copy(startedAt = 6_000), arrival).round)
        }
    }

    @Test fun aRebindBetweenPollsStillUsesTheConfirmedNotificationEnd() {
        val route = ClusterNavigationRoute()
        val active = ClusterRouteObservation(cue, 1_000, null)
        route.update(1_000, active, active)
        val arrival = ClusterRouteObservation(null, null, 2_500)
        val rebound = active.copy(startedAt = 4_000)
        assertEquals(ClusterNavigationRoute.Status(1, true, cue), route.update(4_100, rebound, arrival))
    }

    @Test fun unusableLinkGuidanceLetsTheNotificationAdvanceAConfirmedNewRoute() {
        val route = ClusterNavigationRoute()
        val active = ClusterRouteObservation(cue, 1_000, null)
        route.update(1_000, active, active)
        val unusable = active.copy(cue = cue.copy(maneuver = null))
        assertEquals(cue, route.update(2_000, unusable, active).cue)
        val next = cue.copy(instruction = "新路线")
        assertEquals(ClusterNavigationRoute.Status(1, true, next), route.update(5_000, unusable,
            ClusterRouteObservation(next, 5_000, 3_000)))
    }

    @Test fun anExplicitLinkEndStillEndsTheRouteWhileNotificationsHaveTakenOver() {
        val route = ClusterNavigationRoute()
        val active = ClusterRouteObservation(cue, 1_000, null)
        route.update(1_000, active, active)
        route.update(16_100, active.copy(cue = null), active)
        val stopped = ClusterRouteObservation(cue.copy(state = 0), null, 17_000)
        assertEquals(ClusterNavigationRoute.Status(1, false, null), route.update(17_000, stopped, absent))
        val next = ClusterRouteObservation(cue, 19_001, 17_000)
        assertEquals(ClusterNavigationRoute.Status(1, true, cue), route.update(19_001, next, absent))
    }

    @Test fun aFreshNotificationCanTakeOverAfterAnExplicitLinkEndAndNotificationRemoval() {
        val route = ClusterNavigationRoute()
        val store = NavigationCueStore()
        val generation = store.generation()
        store.put(generation, "first", cue, 1_000)
        route.update(1_000, ClusterRouteObservation(cue, 1_000, null), store.clusterRoute(1_000))
        val stopped = ClusterRouteObservation(cue.copy(state = 0), null, 3_000)
        assertEquals(ClusterNavigationRoute.Status(1, false, null), route.update(3_000, stopped, store.clusterRoute(3_000)))
        store.remove("first")
        store.put(generation, "first", cue, 1_000) // Android can replay the previous post on reconnect.
        assertEquals(ClusterNavigationRoute.Status(1, false, null), route.update(4_000, stopped.copy(cue = null), store.clusterRoute(4_000)))
        store.put(generation, "first", cue, 4_500) // The old route may still refresh after reconnect.
        assertEquals(ClusterNavigationRoute.Status(1, false, null), route.update(4_500, stopped, store.clusterRoute(4_500)))
        val next = cue.copy(instruction = "新路线")
        store.put(generation, "second", next, 5_000)
        assertEquals(ClusterNavigationRoute.Status(1, true, next), route.update(5_000, stopped, store.clusterRoute(5_000)))
        assertEquals(0L, store.explicitRouteEnd())
    }

    @Test fun replacementNotificationCanArriveBeforeTheOldRemovalCallback() {
        val route = ClusterNavigationRoute()
        val store = NavigationCueStore()
        val generation = store.generation()
        store.put(generation, "first", cue, 1_000)
        route.update(1_000, ClusterRouteObservation(cue, 1_000, null), store.clusterRoute(1_000))
        val stopped = ClusterRouteObservation(cue.copy(state = 0), null, 3_000)
        route.update(3_000, stopped, store.clusterRoute(3_000))
        store.put(generation, "first", cue, 3_500)
        assertNull(route.update(3_500, stopped, store.clusterRoute(3_500)).cue)
        val next = cue.copy(instruction = "新路线")
        store.put(generation, "second", next, 5_000)
        store.remove("first")
        assertEquals(ClusterNavigationRoute.Status(1, true, next), route.update(5_000, stopped, store.clusterRoute(5_000)))
    }

    @Test fun aNewNotificationRouteAfterRemovalAdvancesTheInstrumentRoundOnce() {
        val route = ClusterNavigationRoute()
        val store = NavigationCueStore()
        val generation = store.generation()
        store.put(generation, "first", cue, 1_000, postTime = 11_000)
        assertEquals(0L, route.update(1_000, absent, store.clusterRoute(1_000)).round)
        store.remove("first", postTime = 11_000)
        assertNull(route.update(1_500, absent, store.clusterRoute(1_500)).cue)
        val next = cue.copy(instruction = "新路线")
        store.put(generation, "second", next, 6_000, postTime = 16_000)
        assertEquals(ClusterNavigationRoute.Status(1, true, next), route.update(6_000, absent, store.clusterRoute(6_000)))
        assertEquals(1L, route.update(6_001, absent, store.clusterRoute(6_001)).round)
    }

    @Test fun confirmedNotificationReplacementAdvancesTheRoundAfterLinkHasGoneAway() {
        val route = ClusterNavigationRoute()
        val store = NavigationCueStore()
        val generation = store.generation()
        val link = ClusterRouteObservation(cue, 1_000, null, guidanceAt = 1_000)
        store.put(generation, "first", cue, 1_010, postTime = 11_010)
        route.update(1_010, link, store.clusterRoute(1_010))
        store.remove("first", postTime = 11_010, at = 1_500)
        assertTrue(route.update(2_999, absent, store.clusterRoute(2_999)).active)
        assertFalse(route.update(3_000, absent, store.clusterRoute(3_000)).active)
        val next = cue.copy(instruction = "新路线")
        store.put(generation, "second", next, 4_000, postTime = 14_000)
        assertEquals(ClusterNavigationRoute.Status(1, true, next), route.update(4_000, absent, store.clusterRoute(4_000)))
    }

    @Test fun transientNotificationRemovalDoesNotAdvanceTheInstrumentRound() {
        val route = ClusterNavigationRoute()
        val store = NavigationCueStore()
        val generation = store.generation()
        store.put(generation, "first", cue, 1_000, postTime = 11_000)
        route.update(1_000, absent, store.clusterRoute(1_000))
        store.remove("first", postTime = 11_000, at = 1_500)
        assertTrue(route.update(2_000, absent, store.clusterRoute(2_000)).active)
        store.put(generation, "second", cue, 2_999, postTime = 12_999)
        assertEquals(ClusterNavigationRoute.Status(0, true, cue), route.update(2_999, absent, store.clusterRoute(2_999)))
    }
}
