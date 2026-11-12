package io.github.hypercar.navigation

/** Actual Link type 4 fields, verified against the installed app and a real navigation callback.
 * Maneuver IDs follow AMap's ManeuverIconID, not CarLife TurnKind or CarPlay maneuver enums. */
internal class AmapLinkNavigationState {
    private var counter = 0L
    private var token = 0L
    private var cue: NavigationCue? = null
    private var rawManeuver: Int? = null
    private var updated = 0L
    private var initialLanes: Pair<Long, List<NavigationLane>>? = null
    @Synchronized fun begin(): Long { token = ++counter; cue = null; rawManeuver = null; initialLanes = null; return token }
    @Synchronized fun clear(source: Long? = null) {
        if (source == null || source != 0L && source == token) { token = 0; cue = null; rawManeuver = null; initialLanes = null }
    }
    @Synchronized fun accept(source: Long, type: Int, data: org.json.JSONObject?, now: Long): Boolean {
        if (source == 0L || source != token) return false
        val next = when (type) {
            4 -> {
                val info = data ?: return false
                val maneuver = integer(info.opt("curManeuverID"), 65535)?.toInt() ?: return false
                // segmentRemainDist is metres to the current segment end. routeRemainDist is NOT this distance.
                val distance = integer(info.opt("segmentRemainDist"), 0xffffffffL) ?: return false
                val road = info.optString("nextRouteName").replace('\u0000', ' ').trim().take(100)
                val action = maneuvers[maneuver]
                val sameManeuver = rawManeuver == maneuver
                rawManeuver = maneuver
                NavigationCue(listOfNotNull(action?.second, road.takeIf { it.isNotEmpty() }).joinToString(" "),
                    action?.first, distance, nextRoad = road,
                    currentRoad = info.optString("curRouteName").replace('\u0000', ' ').trim().take(100),
                    remainingDistanceMeters = integer(info.opt("routeRemainDist"), 0xffffffffL),
                    remainingTimeSeconds = integer(info.opt("routeRemainTime"), 0xffffffffL),
                    lanes = if (cue == null) initialLanes?.takeIf { now - it.first in 0..15_000 }?.second.orEmpty()
                        else cue?.lanes.orEmpty().takeIf { sameManeuver }.orEmpty()).also { initialLanes = null }
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
                if (current == null) initialLanes = now to lanes else cue = current.copy(lanes = lanes)
                // A lane event is not proof that the old distance/maneuver is still current.
                return true
            }
            5 -> {
                val current = cue?.takeIf { it.state == 1 } ?: return false
                val maneuver = integer(data?.opt("maneuverID"), 65535)?.toInt() ?: return false
                val action = maneuvers[maneuver]
                val unchanged = rawManeuver == maneuver
                rawManeuver = maneuver
                if (unchanged) current else
                    // A new direction can arrive before its segment info. Never reuse the old road/distance.
                    NavigationCue(action?.second.orEmpty(), action?.first, null)
            }
            100 -> { rawManeuver = null; initialLanes = null; NavigationCue("", null, null, state = 0) }
            200 -> { rawManeuver = null; initialLanes = null; NavigationCue("已到达目的地", null, null, state = 2) }
            else -> return false
        }
        cue = next; updated = now
        return true
    }
    @Synchronized fun snapshot(now: Long): NavigationCue? {
        if (token == 0L) return null
        val current = cue ?: return null
        // An explicit stop remains authoritative over an old notification. Lost live updates clear the arrow.
        return if (current.state == 1 && now - updated !in 0..15_000) NavigationCue("", null, null, state = 0) else current
    }
    private fun integer(value: Any?, maximum: Long): Long? {
        if (value !is Number) return null
        val d = value.toDouble()
        return value.toLong().takeIf { d.isFinite() && d == it.toDouble() && it in 0..maximum }
    }
    private companion object {
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
    fun begin() = state.begin()
    fun clear(token: Long) = state.clear(token)
    fun clear() = state.clear()
    fun accept(token: Long, type: Int, data: org.json.JSONObject?, now: Long) = state.accept(token, type, data, now)
    fun snapshot(): NavigationCue? = state.snapshot(android.os.SystemClock.elapsedRealtime())
}
