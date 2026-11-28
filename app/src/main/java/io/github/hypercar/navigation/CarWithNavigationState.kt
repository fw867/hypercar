package io.github.hypercar.navigation

/** Native engine token and cue lifetime are updated under one lock. */
internal class CarWithNavigationState {
    // Structured guidance is event driven: unchanged turns stay valid until cancel/reset/close.
    private val store = NavigationCueStore(maxAgeMillis = Long.MAX_VALUE)
    private var counter = 0L
    private var token = 0L
    private var routeGeneration: Long? = null
    private var guidanceMissingAt: Long? = null
    @Synchronized fun begin(): Long { token = ++counter; clearRoute(); return token }
    @Synchronized fun accepts(sourceToken: Long): Boolean = sourceToken != 0L && sourceToken == token
    @Synchronized fun update(sourceToken: Long, cue: NavigationCue?, now: Long, startsRoute: Boolean = false): Boolean {
        if (!accepts(sourceToken)) return false
        when {
            cue == null -> {
                // Invalid guidance can hide the arrow without ending the source route.
                if (routeGeneration != null && guidanceMissingAt == null) {
                    guidanceMissingAt = now
                    store.clear()
                }
            }
            cue.state == 0 || cue.state == 2 -> clearRoute()
            else -> {
                if (startsRoute || ownedRouteGeneration(now) == null) routeGeneration = NavigationRouteGenerations.next()
                guidanceMissingAt = null
                store.put(store.generation(), "carwith", cue.copy(routeGeneration = routeGeneration), now)
            }
        }
        return true
    }
    @Synchronized fun reset(sourceToken: Long) { if (accepts(sourceToken)) clearRoute() }
    @Synchronized fun clear() { token = 0; clearRoute() }
    private fun clearRoute() { routeGeneration = null; guidanceMissingAt = null; store.clear() }
    @Synchronized fun ownedRouteGeneration(now: Long): Long? =
        routeGeneration.takeUnless { guidanceMissingAt?.let { now - it !in 0..ROUTE_OWNERSHIP_GRACE_MS } == true }

    fun snapshot(now: Long): NavigationCue? = store.snapshot(now)

    private companion object { const val ROUTE_OWNERSHIP_GRACE_MS = 45_000L }
}
