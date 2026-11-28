package io.github.hypercar.navigation

internal fun NavigationCue.isUsableInstrumentGuidance(): Boolean = state == 1 && maneuver != null && instruction.isNotBlank()
internal const val NOTIFICATION_HANDOFF_MS = 1_500L

internal data class ClusterRouteObservation(
    val cue: NavigationCue?,
    val startedAt: Long?,
    val endedAt: Long?,
    val endedRouteStartedAt: Long? = null,
    val guidanceAt: Long? = null,
    val confirmedStartedAt: Long? = startedAt,
    val routeGeneration: Long? = null,
    val endedRouteGuidanceAt: Long? = null,
    val notificationGuidanceFloor: Long? = null,
    val removedAt: Long? = null,
    val previousRouteRemovedAt: Long? = null,
) {
    fun activeStartAfter(ended: Long): Long? {
        if (cue?.state != 1 && cue?.state != 5) return null
        return startedAt?.takeIf { it > ended } ?: confirmedStartedAt?.takeIf { it > ended }
    }

    fun linkStartAfter(ended: Long): Long? = startedAt?.takeIf { it > ended && cue?.isUsableInstrumentGuidance() == true }

    fun hasGuidanceSince(floor: Long?, at: Long? = guidanceAt ?: confirmedStartedAt): Boolean =
        floor == null || at?.let { it >= floor } == true
}

/** Tracks route continuity for the instrument map as guidance moves between sources. */
internal class ClusterNavigationRoute {
    data class Status(val round: Long, val active: Boolean, val cue: NavigationCue?)

    private class Route(val startedAt: Long) {
        var linkStartedAt: Long? = null
        var notificationStartedAt: Long? = null
        var handledNotificationEnd = Long.MIN_VALUE
    }

    private var route: Route? = null
    private var latestEnd = Long.MIN_VALUE
    private var round = 0L

    fun update(now: Long, link: ClusterRouteObservation, notification: ClusterRouteObservation): Status {
        if (route == null) {
            // The producer can see a complete navigation run between two map polls.
            // Remember its boundary even if this instrument has no round to finish.
            link.endedAt?.let { latestEnd = maxOf(latestEnd, it) }
            if (link.linkStartAfter(latestEnd) == null && notification.endedRouteStartedAt != null) {
                notification.endedAt?.let { latestEnd = maxOf(latestEnd, it) }
            }
        }
        route?.let { current ->
            val linkEnd = link.endedAt?.takeIf { end ->
                val start = current.linkStartedAt ?: link.endedRouteStartedAt
                end > latestEnd && end >= current.startedAt && start?.let { it > latestEnd && end >= it } == true
            }
            if (linkEnd != null) {
                finish(linkEnd)
            } else {
                val linkStart = link.linkStartAfter(latestEnd)
                val notificationEnd = notification.endedAt?.takeIf { end ->
                    val start = notification.endedRouteStartedAt ?: current.notificationStartedAt
                    end > latestEnd && end > current.handledNotificationEnd && end >= current.startedAt &&
                        (link.notificationGuidanceFloor?.let { end < it } == true ||
                            notification.hasGuidanceSince(link.notificationGuidanceFloor, notification.endedRouteGuidanceAt ?: end)) &&
                        start?.let { it > latestEnd && end >= it } == true
                }
                if (notificationEnd != null) {
                    val continuousLink = linkStart != null && linkStart == current.linkStartedAt
                    when {
                        // New guidance on the same Link run can outlast an unrelated notification ending.
                        continuousLink -> if (link.guidanceAt?.let { it - notificationEnd >= NOTIFICATION_HANDOFF_MS } == true) {
                            current.handledNotificationEnd = notificationEnd
                        }
                        notification.activeStartAfter(notificationEnd) != null ||
                            linkStart?.let { it - notificationEnd >= NOTIFICATION_HANDOFF_MS } == true -> finish(notificationEnd)
                        linkStart != null -> current.handledNotificationEnd = notificationEnd
                        // Keep the terminal while waiting for the next source's actual start.
                        else -> return Status(round, now - notificationEnd < NOTIFICATION_HANDOFF_MS, null)
                    }
                } else {
                    val removed = notification.previousRouteRemovedAt
                    val joinedNotification = link.routeGeneration != null && link.routeGeneration == notification.routeGeneration
                    val priorStart = current.notificationStartedAt ?: current.startedAt
                    if (removed != null && removed > latestEnd && priorStart <= removed &&
                        (joinedNotification || linkStart == null && link.guidanceAt?.let { it > removed } != true) &&
                        notification.activeStartAfter(removed) != null) {
                        finish(removed)
                    } else if (linkStart == null && notification.removedAt?.let { at ->
                            now - at >= NOTIFICATION_HANDOFF_MS && link.guidanceAt?.let { it > at } != true
                        } == true) {
                        return Status(round, false, null)
                    }
                }
            }
        }

        val linkStart = link.linkStartAfter(latestEnd)
        val notificationFloor = when (link.cue?.state) {
            0, 2 -> link.endedAt?.let { maxOf(it, latestEnd) } ?: Long.MAX_VALUE
            else -> latestEnd
        }
        val notificationStart = notification.activeStartAfter(notificationFloor)
            ?.takeIf { notification.hasGuidanceSince(link.notificationGuidanceFloor) }
        if (route == null) (linkStart ?: notificationStart)?.let { route = Route(it) }
        route?.let { current ->
            if (linkStart != null) current.linkStartedAt = linkStart
            if (current.notificationStartedAt == null) current.notificationStartedAt = notificationStart
        }
        val cue = if (linkStart != null) link.cue else notification.cue.takeIf { notificationStart != null }
        return Status(round, route != null, cue)
    }

    private fun finish(ended: Long) {
        latestEnd = ended
        round++
        route = null
    }
}
