package io.github.hypercar.navigation

import io.github.hypercar.session.HyperCarRepo

/** Actual Link type 4 fields, verified against the installed app and a real navigation callback.
 * Maneuver IDs follow AMap's ManeuverIconID, not CarLife TurnKind or CarPlay maneuver enums. */
internal class AmapLinkNavigationState(private val wallClockMillis: () -> Long = System::currentTimeMillis) {
    private data class NotificationTakeover(
        val displacedGuidance: NavigationCue?,
        var notification: ClusterRouteObservation,
        var joined: Boolean = false,
    )

    private var counter = 0L
    private var token = 0L
    private var cue: NavigationCue? = null
    private var rawManeuver: Int? = null
    private var updated = 0L
    private var lastExplicitRouteEnd = 0L
    private var lastRouteEnd = Long.MIN_VALUE
    private var endedRouteStartedAt: Long? = null
    private var routeStartedAt: Long? = null
    private var routeGeneration: Long? = null
    private var endedRouteGeneration: Long? = null
    private var bindingGuidanceAt: Long? = null
    private var lastUsableGuidanceAt: Long? = null
    private var lastUsableGuidance: NavigationCue? = null
    private var notificationTakeover: NotificationTakeover? = null
    private var routeHadUsableGuidance = false
    private var notificationGuidanceFloor: Long? = null
    private var stoppedByNotification = false
    private var fullGuidanceAt = 0L
    private var replanning: TimedNavigationCue? = null
    private var lanesAt: Long? = null
    private var initialLanes = emptyList<NavigationLane>()
    @Synchronized fun begin(): Long {
        token = ++counter
        resetBindingGuidance()
        cue = null; rawManeuver = null; clearLanes(); replanning = null
        return token
    }
    @Synchronized fun clear(source: Long? = null) {
        if (source == null || source != 0L && source == token) {
            resetBindingGuidance()
            token = 0; cue = null; rawManeuver = null; clearLanes(); replanning = null
            if (source == null) {
                routeStartedAt = null
                routeGeneration = null
                routeHadUsableGuidance = false
                notificationGuidanceFloor = null
                fullGuidanceAt = 0
                lastUsableGuidance = null
                notificationTakeover = null
            }
        }
    }
    @Synchronized fun accept(source: Long, type: Int, data: org.json.JSONObject?, now: Long): Boolean {
        if (source == 0L || source != token) return false
        val next = when (type) {
            4 -> {
                // Link may deliver a final guidance sample after the end event. Keep the
                // terminal cue long enough for the iAP2 worker to publish it.
                val previousState = cue?.state
                if (stoppedByNotification && now - lastRouteEnd in 0 until END_SETTLE_MS ||
                    (previousState == 0 || previousState == 2) && now - updated in 0 until END_SETTLE_MS) return false
                val info = data ?: return false
                val maneuver = integer(info.opt("curManeuverID"), 65535)?.toInt() ?: return false
                // segmentRemainDist is metres to the current segment end. routeRemainDist is NOT this distance.
                val distance = integer(info.opt("segmentRemainDist"), 0xffffffffL) ?: return false
                val routeDistance = integer(info.opt("routeRemainDist"), 0xffffffffL)
                val routeRemainingSeconds = integer(info.opt("routeRemainTime"), 0xffffffffL)
                val estimatedArrival = routeRemainingSeconds?.takeIf { it > 0 }?.let { remaining ->
                    // Anchor the reported route duration to when Link supplied it.
                    val observedMillis = wallClockMillis()
                    if (observedMillis > 0) observedMillis / 1000 + remaining else null
                }
                val road = info.optString("nextRouteName").replace('\u0000', ' ').trim().take(100)
                val currentRoad = info.optString("curRouteName").replace('\u0000', ' ').trim().take(100)
                val action = maneuvers[maneuver]
                val guidance = NavigationCue(listOfNotNull(action?.second, road.takeIf { it.isNotEmpty() }).joinToString(" "),
                    action?.first, distance, nextRoad = road, currentRoad = currentRoad,
                    remainingDistanceMeters = routeDistance, remainingTimeSeconds = routeRemainingSeconds,
                    estimatedArrivalEpochSeconds = estimatedArrival)
                if (!acceptsTakeoverGuidance(guidance, now)) return false
                val previous = cue
                val sameJunction = previous?.state == 1 && rawManeuver == maneuver &&
                    (previous.nextRoad.isEmpty() || road.isEmpty() || previous.nextRoad == road) &&
                    (previous.currentRoad.isEmpty() || currentRoad.isEmpty() || previous.currentRoad == currentRoad) &&
                    previous.distanceMeters?.let {
                        val increase = distance - it
                        // A short same-road turn can follow immediately after we reach this junction.
                        val previousTotal = previous.remainingDistanceMeters
                        val progressing = routeDistance != null && previousTotal != null && routeDistance < previousTotal
                        increase < 50 && (it > 15 || increase < if (progressing) 3 else 10)
                    } != false
                if ((previous != null && !sameJunction) || !lanesFresh(now)) clearLanes()
                val lanes = if (previous == null) initialLanes else previous.lanes.takeIf { sameJunction && lanesFresh(now) }.orEmpty()
                initialLanes = emptyList()
                rawManeuver = maneuver
                guidance.copy(lanes = lanes)
            }
            14 -> {
                val current = cue
                if (current != null && current.state != 1) return false
                val info = data ?: return false
                val count = integer(info.opt("laneCount"), 16)?.toInt() ?: return false
                val back = info.optJSONArray("backLane")
                val front = info.optJSONArray("frontLane")
                if (count > 0 && (back == null || front == null || back.length() != count || front.length() != count)) return false
                val lanes = (0 until count).map { index ->
                    val direction = integer(back!!.opt(index), 255)?.toInt() ?: return false
                    val selected = integer(front!!.opt(index), 255)?.toInt() ?: return false
                    NavigationLane(direction, selected in 0..12, selected.takeIf { it in 0..12 })
                }
                lanesAt = now.takeIf { lanes.isNotEmpty() }
                if (current == null) initialLanes = lanes else cue = current.copy(lanes = lanes)
                // A lane event is not proof that the old distance/maneuver is still current.
                return true
            }
            5 -> {
                val current = cue?.takeIf { it.state == 1 } ?: return false
                val maneuver = integer(data?.opt("maneuverID"), 65535)?.toInt() ?: return false
                val action = maneuvers[maneuver]
                val unchanged = rawManeuver == maneuver
                rawManeuver = maneuver
                if (unchanged) current else {
                    // A new direction can arrive before its segment info. Never reuse the old road/distance.
                    clearLanes()
                    NavigationCue(action?.second.orEmpty(), action?.first, null)
                }
            }
            100, 200 -> {
                if (routeStartedAt == null && notificationTakeover?.joined == false) return false
                rawManeuver = null
                clearLanes()
                val stopped = NavigationCue("", null, null, state = 0)
                when (type) {
                    100 -> cue?.takeIf { it.state == 2 } ?: stopped
                    // Link also sends type 200 on manual exit (action=0, isArrived=false).
                    else -> if (data?.optBoolean("isArrived", false) == true)
                        NavigationCue("已到达目的地", null, null, state = 2) else stopped
                }
            }
            else -> return false
        }
        val usableFullGuidance = type == 4 && next.isUsableInstrumentGuidance()
        if (next.state == 1 && (routeStartedAt == null || usableFullGuidance && !linkLeaseActive(now))) {
            routeStartedAt = now
            val joining = notificationTakeover?.takeIf { !it.joined && it.notification.guidanceWithinLease(now) }
            routeGeneration = joining?.notification?.routeGeneration ?: NavigationRouteGenerations.next()
        }
        if (usableFullGuidance) {
            // After a rebind's first usable sample, a previously cached notification
            // cannot attest to the route this binding just reported.
            if (bindingGuidanceAt == null && routeHadUsableGuidance) notificationGuidanceFloor = now
            if (lastUsableGuidanceAt?.let { now - it in 0..GUIDANCE_MAX_AGE_MS } != true) {
                bindingGuidanceAt = now
            }
            lastUsableGuidanceAt = now
            lastUsableGuidance = next
            routeHadUsableGuidance = true
            notificationTakeover?.joined = true
        }
        if ((type == 100 || type == 200) && routeStartedAt != null) {
            if (now > lastExplicitRouteEnd) {
                lastExplicitRouteEnd = now
                endedRouteStartedAt = routeStartedAt
            }
            retireRoute(now)
        }
        if (type == 4) {
            fullGuidanceAt = now
            replanning = replanning?.takeIf { it.updatedAt > now }
        } else if (next.state != 1) {
            replanning = null
        }
        stoppedByNotification = stoppedByNotification && next.state != 1
        cue = next; updated = now
        return true
    }
    @Synchronized fun routeInProgress(): Boolean? = cue?.let { it.state == 1 }
    @Synchronized fun explicitRouteEnd(): Long = lastExplicitRouteEnd

    @Synchronized fun clusterRoute(now: Long): ClusterRouteObservation {
        return ClusterRouteObservation(if (stoppedByNotification || guidanceExpired(now)) null else snapshot(now), bindingGuidanceAt,
            lastExplicitRouteEnd.takeIf { it != 0L }, endedRouteStartedAt = endedRouteStartedAt,
            guidanceAt = lastUsableGuidanceAt, routeGeneration = routeGeneration,
            notificationGuidanceFloor = notificationGuidanceFloor)
    }

    @Synchronized fun observeNotification(now: Long, notification: ClusterRouteObservation) {
        val terminalAt = notification.endedAt?.takeIf { notificationEndedLinkRoute(notification) }
        val boundary = terminalAt ?: replacementAfterLostLink(now, notification)
        if (boundary == null) {
            notificationTakeover?.takeIf { !it.joined }?.let { takeover ->
                val start = takeover.notification.confirmedStartedAt ?: return@let
                val incomingStart = notification.activeStartAfter(lastRouteEnd)
                if (incomingStart != null && incomingStart >= start && usableNotificationRoute(notification)) {
                    takeover.notification = notification
                } else if (notification.endedRouteStartedAt == start && notification.endedAt != null ||
                    notification.confirmedStartedAt == start && notification.removedAt?.let { now - it >= NOTIFICATION_HANDOFF_MS } == true) {
                    takeover.notification = notification.copy(guidanceAt = null)
                }
            }
            return
        }
        val displaced = lastUsableGuidance
        retireRoute(boundary)
        if (terminalAt == null) notificationTakeover = NotificationTakeover(displaced, notification)
        rawManeuver = null
        clearLanes()
        replanning = null
        cue = if (terminalAt != null) {
            notification.cue?.takeIf { it.state == 0 || it.state == 2 } ?: NavigationCue("", null, null, state = 0)
        } else null
        updated = boundary
        stoppedByNotification = terminalAt != null
    }

    private fun retireRoute(at: Long) {
        lastRouteEnd = maxOf(lastRouteEnd, at)
        routeStartedAt = null
        endedRouteGeneration = routeGeneration
        routeGeneration = null
        routeHadUsableGuidance = false
        notificationGuidanceFloor = null
        lastUsableGuidance = null
        notificationTakeover = null
        resetBindingGuidance()
    }

    private fun acceptsTakeoverGuidance(guidance: NavigationCue, now: Long): Boolean {
        val takeover = notificationTakeover ?: return true
        // Link has no route identifier in this callback. Keep the new notification's route
        // until a Link sample agrees with its turn, distance and any explicit next road.
        val displaced = takeover.displacedGuidance
        if (displaced != null && guidance.maneuver == displaced.maneuver &&
            guidance.nextRoad == displaced.nextRoad && guidance.currentRoad == displaced.currentRoad &&
            distancesAgree(guidance.distanceMeters, displaced.distanceMeters) &&
            (guidance.remainingDistanceMeters == null || displaced.remainingDistanceMeters == null ||
                distancesAgree(guidance.remainingDistanceMeters, displaced.remainingDistanceMeters))) return false
        if (takeover.joined || !takeover.notification.guidanceWithinLease(now)) return true
        val notification = takeover.notification.cue ?: return false
        val road = notification.nextRoad.ifBlank { notification.instruction.substringAfter("进入", "").trim() }
        return guidance.maneuver != null && guidance.maneuver == notification.maneuver &&
            distancesAgree(guidance.distanceMeters, notification.distanceMeters) &&
            (road.isEmpty() || guidance.nextRoad == road)
    }

    private fun distancesAgree(a: Long?, b: Long?): Boolean =
        a != null && b != null && kotlin.math.abs(a - b) <= maxOf(20, b / 10)

    private fun ClusterRouteObservation.guidanceWithinLease(now: Long): Boolean =
        guidanceAt?.let { now - it in 0..ROUTE_OWNERSHIP_GRACE_MS } == true

    private fun resetBindingGuidance() {
        bindingGuidanceAt = null
        lastUsableGuidanceAt = null
    }

    private fun guidanceExpired(now: Long) = cue?.state == 1 && now - fullGuidanceAt !in 0..GUIDANCE_MAX_AGE_MS

    @Synchronized fun snapshot(now: Long): NavigationCue? {
        if (token == 0L) return null
        val current = cue ?: return null
        // An explicit stop remains authoritative over an old notification. Lost live updates clear the arrow.
        if (guidanceExpired(now)) return NavigationCue("", null, null, state = 0)
        return if (current.lanes.isNotEmpty() && !lanesFresh(now)) current.copy(lanes = emptyList()) else current
    }

    private fun usableNotificationRoute(notification: ClusterRouteObservation): Boolean =
        notification.confirmedStartedAt?.let { it > lastRouteEnd } == true &&
            notification.hasGuidanceSince(notificationGuidanceFloor)

    private fun notificationEndedLinkRoute(notification: ClusterRouteObservation): Boolean {
        val end = notification.endedAt ?: return false
        val start = notification.endedRouteStartedAt ?: return false
        val linkStart = routeStartedAt ?: return false
        return start > lastRouteEnd && notification.hasGuidanceSince(notificationGuidanceFloor, notification.endedRouteGuidanceAt ?: end) &&
            start - fullGuidanceAt <= GUIDANCE_MAX_AGE_MS && end >= linkStart && end >= fullGuidanceAt
    }

    private fun linkLeaseActive(now: Long) = routeStartedAt != null && now - fullGuidanceAt in 0..ROUTE_OWNERSHIP_GRACE_MS

    private fun replacementAfterLostLink(now: Long, notification: ClusterRouteObservation): Long? {
        if (routeStartedAt == null || snapshot(now)?.isUsableInstrumentGuidance() == true ||
            !usableNotificationRoute(notification)) return null
        return notification.previousRouteRemovedAt?.takeIf { removed ->
            removed >= fullGuidanceAt && notification.activeStartAfter(removed) != null
        }
    }

    private fun activeLinkGeneration(now: Long, notification: ClusterRouteObservation): Long? =
        routeGeneration.takeIf {
            linkLeaseActive(now) && !notificationEndedLinkRoute(notification) && replacementAfterLostLink(now, notification) == null
        }

    @Synchronized fun ownedRouteGeneration(now: Long, notification: ClusterRouteObservation): Long? =
        activeLinkGeneration(now, notification) ?: notification.routeGeneration?.takeIf {
            usableNotificationRoute(notification) && notification.guidanceWithinLease(now)
        }

    fun ownsRoute(now: Long, notification: ClusterRouteObservation): Boolean = ownedRouteGeneration(now, notification) != null

    @Synchronized fun hudSnapshot(now: Long, notification: ClusterRouteObservation): NavigationCue? {
        val fallback = notification.cue?.takeIf {
            when (it.state) {
                1, 5 -> usableNotificationRoute(notification)
                0, 2 -> notification.endedAt?.let { end ->
                    end >= lastRouteEnd && notification.hasGuidanceSince(notificationGuidanceFloor, notification.endedRouteGuidanceAt ?: end) &&
                        routeStartedAt?.let { end >= it && end >= fullGuidanceAt } != false
                } == true
                else -> false
            }
        }?.let {
            val notificationGeneration = notification.routeGeneration ?: it.routeGeneration
            val linkGeneration = if (notificationGeneration != null && (it.state == 1 || it.state == 5)) {
                activeLinkGeneration(now, notification)
            } else null
            it.copy(routeGeneration = linkGeneration ?: notificationGeneration)
        }
        val link = snapshot(now) ?: return fallback
        if (guidanceExpired(now)) return fallback ?: link.copy(routeGeneration = routeGeneration)
        if (link.state != 1) {
            return fallback?.takeIf { it.state == 1 || it.state == 5 }
                ?: link.copy(routeGeneration = endedRouteGeneration)
        }
        if (notificationEndedLinkRoute(notification)) {
            return fallback ?: NavigationCue("", null, null, state = 0, routeGeneration = routeGeneration)
        }
        // A new notification can supersede the last complete route. A partial turn or
        // lane hint cannot end replanning, even if the notification has been removed.
        val latest = replanning?.updatedAt ?: fullGuidanceAt
        val fallbackAt = notification.guidanceAt
        if (fallback?.state == 5 && fallbackAt != null && fallbackAt > latest) {
            // A replanning hint for the live Link route does not start a second route.
            replanning = TimedNavigationCue(fallback.copy(routeGeneration = routeGeneration ?: fallback.routeGeneration), fallbackAt)
        } else if (fallback?.state == 1 && notification.confirmedStartedAt?.let { it > latest } == true) {
            replanning = null
        }
        val currentLink = link.copy(routeGeneration = routeGeneration)
        return replanning?.cue ?: if (link.isUsableInstrumentGuidance()) currentLink else fallback ?: currentLink
    }
    private fun lanesFresh(now: Long): Boolean = lanesAt?.let { now - it in 0..15_000 } == true

    private fun clearLanes() {
        lanesAt = null
        initialLanes = emptyList()
    }

    private fun integer(value: Any?, maximum: Long): Long? {
        if (value !is Number) return null
        val d = value.toDouble()
        return value.toLong().takeIf { d.isFinite() && d == it.toDouble() && it in 0..maximum }
    }
    private companion object {
        const val GUIDANCE_MAX_AGE_MS = 15_000L
        const val ROUTE_OWNERSHIP_GRACE_MS = 45_000L
        const val END_SETTLE_MS = 2_000L

        // AMap AutoSDK ManeuverIconID -> CP maneuver type. Unknowns intentionally have no arrow.
        val maneuvers = mapOf(
            2 to (1 to "左转"), 3 to (2 to "右转"), 4 to (49 to "向左前方行驶"),
            5 to (50 to "向右前方行驶"), 6 to (47 to "向左后方转弯"), 7 to (48 to "向右后方转弯"),
            8 to (4 to "掉头"), 9 to (3 to "直行"), 11 to (6 to "进入环岛"), 12 to (7 to "驶出环岛"),
            19 to (4 to "掉头"), 20 to (3 to "顺行"),
        )
    }
}

internal object AmapLinkNavigation {
    private val state = AmapLinkNavigationState()
    fun begin() = HudSourceWrites.publish { state.begin() }
    fun clear(token: Long) = HudSourceWrites.publish { state.clear(token) }
    fun clear() = HudSourceWrites.publish { state.clear() }
    fun accept(token: Long, type: Int, data: org.json.JSONObject?, now: Long) =
        HudSourceWrites.publish {
            if ((type == 4 || type == 100 || type == 200) && HyperCarRepo.hudNavigationAllowed) {
                state.observeNotification(now, AmapNavigation.clusterRoute(now))
            }
            state.accept(token, type, data, now)
        }
    // The notification producer calls this while publishing the accepted notification.
    fun observeNotification(now: Long, notification: ClusterRouteObservation) = state.observeNotification(now, notification)
    fun routeInProgress(): Boolean? = state.routeInProgress()
    fun clusterRoute(now: Long) = state.clusterRoute(now)
    fun snapshot(): NavigationCue? = state.snapshot(android.os.SystemClock.elapsedRealtime())
    fun ownedRouteGeneration(now: Long, notification: ClusterRouteObservation) = state.ownedRouteGeneration(now, notification)
    fun ownsRoute(now: Long, notification: ClusterRouteObservation) = state.ownsRoute(now, notification)
    fun hudSnapshot(now: Long, notification: ClusterRouteObservation) = state.hudSnapshot(now, notification)
}
