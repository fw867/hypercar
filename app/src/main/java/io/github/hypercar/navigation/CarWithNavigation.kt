package io.github.hypercar.navigation

import android.os.SystemClock
import io.github.hypercar.session.HyperCarRepo
import org.json.JSONObject

/** Each nativeStart gets a fresh token, including retries within one Android connection attempt. */
internal object CarWithNavigation {
    private val state = CarWithNavigationState()
    private var nextLogAt = 0L

    fun prepareConfig(json: String): String {
        return JSONObject(json).put("carwith_navigation_token", HudSourceWrites.publish(state::begin))
            .put("carwith_navigation_enabled", HyperCarRepo.carWithHudNavigationAllowed).toString()
    }

    fun clear() { HudSourceWrites.publish(state::clear) }

    fun receive(sourceToken: Long, json: String) {
        if (!state.accepts(sourceToken)) return
        if (json == "{\"reset\":true}") {
            HudSourceWrites.publish { state.reset(sourceToken) }
            return
        }
        val update = if (HyperCarRepo.carWithHudNavigationAllowed && json != "null") runCatching {
            require(json.length <= 2048)
            val n = JSONObject(json)
            val action = n.getInt("action")
            action to CarWithCueMapper.map(action,
                if (n.isNull("turn")) null else n.getInt("turn"),
                if (n.isNull("distance")) null else n.getLong("distance"), n.getString("road"))
        }.getOrNull() else null
        val loggedCue = HudSourceWrites.publish {
            if (!HyperCarRepo.carWithHudNavigationAllowed) {
                state.reset(sourceToken)
                return@publish null
            }
            val cue = update?.second
            val now = SystemClock.elapsedRealtime()
            if (!state.update(sourceToken, cue, now, startsRoute = update?.first == 1) ||
                cue == null || now < nextLogAt) return@publish null
            nextLogAt = now + 10_000
            cue
        }
        loggedCue?.let { cue ->
            HyperCarRepo.log("HUD: CarWith结构化导航 state=${cue.state} maneuver=${cue.maneuver} distance=${cue.distanceMeters}m（不记录道路文本）")
        }
    }

    fun snapshot(): NavigationCue? = if (HyperCarRepo.carWithHudNavigationAllowed)
        state.snapshot(SystemClock.elapsedRealtime()) else null

    fun ownedRouteGeneration(now: Long): Long? = state.ownedRouteGeneration(now)
}

internal object CarWithCueMapper {
    // CarLife TurnKind and CP maneuver types are DIFFERENT enums. Unknowns have no arrow.
    private val turns = mapOf(
        1 to (3 to "直行"), 2 to (50 to "向右前方行驶"), 3 to (2 to "右转"),
        4 to (48 to "向右后方转弯"), 5 to (4 to "掉头"), 6 to (47 to "向左后方转弯"),
        7 to (1 to "左转"), 8 to (49 to "向左前方行驶"),
        9 to (6 to "进入环岛"), 10 to (7 to "驶出环岛"),
        11 to (13 to "靠左行驶"), 12 to (14 to "靠右行驶"),
        23 to (11 to "开始导航"), 24 to (12 to "前往目的地"),
    )

    fun map(action: Int, turn: Int?, distance: Long?, road: String): NavigationCue? {
        if (action !in 1..3 || turn != null && turn !in 0..255 ||
            distance != null && distance !in 0..Int.MAX_VALUE.toLong() || road.encodeToByteArray().size > 512) return null
        // Action 3 means stop/hide, which may be cancellation rather than arrival.
        if (action == 3) return NavigationCue("", null, null, state = 0, sourceName = "CarWith")
        if (turn == null) return null
        val mapped = turns[turn]
        val nextRoad = road.replace('\u0000', ' ').trim().take(100)
        val instruction = listOfNotNull(mapped?.second, nextRoad.takeIf(String::isNotEmpty)).joinToString(" ")
        return NavigationCue(instruction, mapped?.first, distance,
            nextRoad = nextRoad, sourceName = "CarWith")
    }
}

internal object NavigationSources {
    fun snapshot(): NavigationCue? {
        // Local map guidance must survive when a receiver has no HUD subscription/support.
        if (!HyperCarRepo.hudNavigationAllowed) return null
        if (HyperCarRepo.carWithHudNavigationAllowed) return CarWithNavigation.snapshot()
        val now = SystemClock.elapsedRealtime()
        return AmapLinkNavigation.hudSnapshot(now, AmapNavigation.clusterRoute(now))
    }

    fun ownedRouteGeneration(): Long? {
        if (!HyperCarRepo.hudNavigationAllowed) return null
        val now = SystemClock.elapsedRealtime()
        if (HyperCarRepo.carWithHudNavigationAllowed) return CarWithNavigation.ownedRouteGeneration(now)
        return AmapLinkNavigation.ownedRouteGeneration(now, AmapNavigation.clusterRoute(now))
    }

    fun clear() { AmapNavigation.clear(); AmapLinkNavigation.clear(); CarWithNavigation.clear(); AmapLinkProbe.stop() }
}
