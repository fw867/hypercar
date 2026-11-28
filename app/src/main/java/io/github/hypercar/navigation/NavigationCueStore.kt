package io.github.hypercar.navigation

internal data class TimedNavigationCue(val cue: NavigationCue, val updatedAt: Long)

/** A removed notification or a late callback from a previous session cannot revive an old arrow. */
internal class NavigationCueStore(private val maxAgeMillis: Long = 90_000L) {
    private data class Entry(
        val key: String, val sample: TimedNavigationCue, val routeGeneration: Long?, val notificationPostTime: Long?,
    )
    private data class NotificationGuidance(val cue: NavigationCue, val furthestDistance: Long?) {
        fun sameTurn(other: NavigationCue): Boolean =
            cue.maneuver == other.maneuver && cue.instruction == other.instruction && cue.nextRoad == other.nextRoad

        fun startsFarther(other: NavigationCue): Boolean {
            val before = furthestDistance ?: return false
            val after = other.distanceMeters ?: return false
            return after - before >= maxOf(100, before / 2)
        }

        fun coversDistance(other: NavigationCue): Boolean =
            furthestDistance?.let { limit -> other.distanceMeters?.let { it <= limit } } == true
    }
    private data class RetiredNotification(
        val postTime: Long, val removed: Boolean, val endedGuidance: NotificationGuidance? = null,
    )
    private var generation = 0L
    private var entry: Entry? = null
    private var disconnectedEntry: Entry? = null
    private var lastExplicitRouteEnd = 0L
    private var routeStartedAt: Long? = null
    private var routeUpdatedAt = Long.MIN_VALUE
    private var notificationPostTime = Long.MIN_VALUE
    private var guidanceKey: String? = null
    private var confirmedStartedAt: Long? = null
    private var guidanceGeneration: Long? = null
    private var endedRouteStartedAt: Long? = null
    private var endedRouteGuidanceAt: Long? = null
    private var currentRouteObserved = false
    private var removedAt: Long? = null
    private var previousRouteRemovedAt: Long? = null
    private var notificationGuidance: NotificationGuidance? = null
    private var previousNotificationGuidance: NotificationGuidance? = null
    private val retiredNotificationKeys = LinkedHashMap<String, RetiredNotification>()
    @Synchronized fun generation(): Long = generation
    @Synchronized fun put(generation: Long, key: String, cue: NavigationCue, updated: Long, postTime: Long? = null): Boolean {
        // Notifications can replay an earlier post. A stamped cue comes from the ordered
        // CarWith worker and can legitimately follow stop/restart in the same clock tick.
        if (generation != this.generation || updated < routeUpdatedAt ||
            (updated == routeUpdatedAt && cue.routeGeneration == null && postTime == null) ||
            (postTime != null && postTime <= notificationPostTime)) return false
        val retired = retiredNotificationKeys[key]
        if (postTime != null && retired != null) {
            // Android can deliver a newer post for the outgoing key after its replacement.
            // A same-key explicit end can establish a boundary before Android removes the instance.
            val restarting = !currentRouteObserved || removalBoundary(updated) != null
            val ended = retired.endedGuidance
            val differentGuidance = ended?.let { !it.sameTurn(cue) || it.startsFarther(cue) } == true
            if (cue.state != 1 || postTime <= retired.postTime || !restarting ||
                (!retired.removed && !differentGuidance)) return false
        }
        val oldGuidance = notificationGuidance
        if (postTime != null && key == guidanceKey && cue.state == 1) {
            val outgoing = previousNotificationGuidance
            if (outgoing?.sameTurn(cue) == true && oldGuidance != null &&
                (!oldGuidance.sameTurn(cue) || outgoing.coversDistance(cue) && outgoing.startsFarther(oldGuidance.cue))) return false
        }
        if (postTime != null && retired != null) {
            retiredNotificationKeys.remove(key)
        }
        val priorGeneration = guidanceGeneration ?: entry?.routeGeneration
        when (cue.state) {
            1, 5 -> {
                val boundary = removalBoundary(updated)
                if (boundary != null) previousRouteRemovedAt = boundary
                if (routeStartedAt == null || boundary != null) routeStartedAt = updated
                val newGuidance = guidanceKey != key || boundary != null
                if (newGuidance) {
                    if (postTime != null && guidanceKey != key) {
                        guidanceKey?.let { retireNotification(it, notificationPostTime, removedAt != null) }
                    }
                    confirmedStartedAt = updated
                    guidanceGeneration = NavigationRouteGenerations.next()
                    notificationGuidance = null
                    previousNotificationGuidance = retired?.endedGuidance
                }
                if (postTime != null && cue.state == 1) {
                    val continuousTurn = !newGuidance && oldGuidance?.sameTurn(cue) == true
                    if (!newGuidance && oldGuidance != null && !continuousTurn) previousNotificationGuidance = null
                    val furthest = oldGuidance?.furthestDistance?.takeIf { continuousTurn }
                    notificationGuidance = NotificationGuidance(cue,
                        cue.distanceMeters?.let { maxOf(it, furthest ?: it) } ?: furthest)
                }
                guidanceKey = key
                removedAt = null
                currentRouteObserved = true
            }
            0, 2 -> if (routeStartedAt != null) {
                lastExplicitRouteEnd = updated
                endedRouteStartedAt = confirmedStartedAt
                endedRouteGuidanceAt = routeUpdatedAt
                if (postTime != null) {
                    val endedGuidance = notificationGuidance
                    guidanceKey?.let { retireNotification(it, notificationPostTime, removedAt != null && it != key, endedGuidance) }
                    retireNotification(key, postTime, false, endedGuidance.takeIf { key == guidanceKey })
                }
                routeStartedAt = null
                guidanceKey = null
                notificationGuidance = null
                previousNotificationGuidance = null
                confirmedStartedAt = null
                guidanceGeneration = null
                currentRouteObserved = false
                removedAt = null
            }
        }
        routeUpdatedAt = updated
        if (postTime != null) notificationPostTime = postTime
        disconnectedEntry = null
        entry = Entry(key, TimedNavigationCue(cue, updated), cue.routeGeneration ?: guidanceGeneration ?: priorGeneration, postTime)
        return true
    }

    @Synchronized fun remove(key: String, postTime: Long? = null, at: Long? = null) {
        if (key == guidanceKey && postTime != null && postTime >= notificationPostTime) {
            removedAt = removedAt ?: at ?: routeUpdatedAt
        }
        val retired = retiredNotificationKeys[key]
        if (postTime != null && retired != null && postTime >= retired.postTime) {
            retiredNotificationKeys[key] = retired.copy(postTime = postTime, removed = true)
        }
        hideSample(key, postTime)
    }

    @Synchronized fun hide(generation: Long, key: String, postTime: Long) {
        if (generation == this.generation) hideSample(key, postTime)
    }

    private fun hideSample(key: String, postTime: Long?) {
        if (key == guidanceKey && postTime != null) notificationPostTime = maxOf(notificationPostTime, postTime)
        val disconnected = disconnectedEntry
        if (disconnected?.key == key && (postTime == null || disconnected.notificationPostTime?.let { postTime >= it } == true)) {
            disconnectedEntry = null
        }
        val sample = entry?.takeIf { it.key == key } ?: return
        if (postTime != null && sample.notificationPostTime?.let { it > postTime } == true) return
        if (postTime != null) notificationPostTime = maxOf(notificationPostTime, postTime)
        entry = null
    }

    private fun removalBoundary(updated: Long): Long? = removedAt?.takeIf { updated - it >= NOTIFICATION_HANDOFF_MS }

    private fun retireNotification(key: String, postTime: Long, removed: Boolean, endedGuidance: NotificationGuidance? = null) {
        retiredNotificationKeys.remove(key)
        retiredNotificationKeys[key] = RetiredNotification(postTime, removed, endedGuidance)
        if (retiredNotificationKeys.size > 16) retiredNotificationKeys.remove(retiredNotificationKeys.keys.first())
    }
    @Synchronized fun clear() {
        generation++
        entry = null
        disconnectedEntry = null
        currentRouteObserved = false
        removedAt = null
    }

    @Synchronized fun disconnectListener() {
        val active = entry?.takeIf { it.notificationPostTime != null && (it.sample.cue.state == 1 || it.sample.cue.state == 5) }
            ?: disconnectedEntry
        clear()
        disconnectedEntry = active
    }

    @Synchronized fun restoreActiveNotification(generation: Long, key: String, cue: NavigationCue, postTime: Long, now: Long): Boolean {
        val active = disconnectedEntry ?: return false
        if (generation != this.generation || active.key != key || active.notificationPostTime != postTime ||
            postTime != notificationPostTime || cue != active.sample.cue || now - active.sample.updatedAt !in 0..maxAgeMillis) return false
        entry = active
        disconnectedEntry = null
        currentRouteObserved = true
        return true
    }

    @Synchronized fun finishActiveSnapshot(generation: Long) {
        if (generation == this.generation) disconnectedEntry = null
    }
    @Synchronized fun explicitRouteEnd(): Long = lastExplicitRouteEnd
    @Synchronized fun clusterRoute(now: Long): ClusterRouteObservation {
        val sample = timedSnapshot(now)
        return ClusterRouteObservation(sample?.cue, routeStartedAt, lastExplicitRouteEnd.takeIf { it != 0L },
            endedRouteStartedAt = endedRouteStartedAt,
            guidanceAt = routeUpdatedAt.takeIf { sample != null || currentRouteObserved },
            confirmedStartedAt = confirmedStartedAt.takeIf { currentRouteObserved },
            routeGeneration = entry?.routeGeneration ?: guidanceGeneration.takeIf { currentRouteObserved },
            endedRouteGuidanceAt = endedRouteGuidanceAt, removedAt = removedAt,
            previousRouteRemovedAt = previousRouteRemovedAt)
    }
    @Synchronized fun timedSnapshot(now: Long): TimedNavigationCue? =
        entry?.sample?.takeIf { now - it.updatedAt in 0..maxAgeMillis }

    fun snapshot(now: Long): NavigationCue? = timedSnapshot(now)?.cue
}
