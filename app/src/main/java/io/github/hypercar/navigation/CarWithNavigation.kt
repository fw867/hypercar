package io.github.hypercar.navigation

import android.os.SystemClock
import io.github.hypercar.session.HyperCarRepo
import org.json.JSONObject

/** Each nativeStart gets a fresh token, including retries within one Android connection attempt. */
internal object CarWithNavigation {
    private val state = CarWithNavigationState()
    private var nextLogAt = 0L

    @Synchronized fun prepareConfig(json: String): String {
        return JSONObject(json).put("carwith_navigation_token", state.begin())
            .put("carwith_navigation_enabled", HyperCarRepo.carWithHudNavigationAllowed).toString()
    }

    @Synchronized fun clear() { state.clear() }

    @Synchronized fun receive(sourceToken: Long, json: String) {
        if (!state.accepts(sourceToken)) return
        if (!HyperCarRepo.carWithHudNavigationAllowed || json == "null") {
            state.update(sourceToken, null, SystemClock.elapsedRealtime()); return
        }
        val cue = runCatching {
            require(json.length <= 2048)
            val n = JSONObject(json)
            CarWithCueMapper.map(n.getInt("action"),
                if (n.isNull("turn")) null else n.getInt("turn"),
                if (n.isNull("distance")) null else n.getLong("distance"), n.getString("road"))
        }.getOrNull()
        if (cue == null) { state.update(sourceToken, null, SystemClock.elapsedRealtime()); return }
        val now = SystemClock.elapsedRealtime()
        if (!state.update(sourceToken, cue, now)) return
        if (now >= nextLogAt) {
            nextLogAt = now + 10_000
            HyperCarRepo.log("HUD: CarWith结构化导航 state=${cue.state} maneuver=${cue.maneuver} distance=${cue.distanceMeters}m（不记录道路文本）")
        }
    }

    fun snapshot(): NavigationCue? = if (HyperCarRepo.carWithHudNavigationAllowed)
        state.snapshot(SystemClock.elapsedRealtime()) else null
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
        if (!HyperCarRepo.hudNavigationAllowed) { clear(); return null }
        return if (HyperCarRepo.carWithHudNavigationAllowed) CarWithNavigation.snapshot() else AmapNavigation.snapshot()
    }
    fun clear() { AmapNavigation.clear(); CarWithNavigation.clear(); AmapLinkProbe.stop() }
}
