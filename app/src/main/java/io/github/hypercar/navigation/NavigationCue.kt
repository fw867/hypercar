package io.github.hypercar.navigation

/** Actual source facts only, from a map notification or verified CarWith navigation message. */
data class NavigationCue(
    val instruction: String,
    val maneuver: Int?,
    val distanceMeters: Long?,
    val state: Int = 1,
    val nextRoad: String = "",
    val sourceName: String = "高德地图",
    val currentRoad: String = "",
    val remainingDistanceMeters: Long? = null,
    val remainingTimeSeconds: Long? = null,
    val lanes: List<NavigationLane> = emptyList(),
) {
    init {
        require(state in 0..6)
        require('\u0000' !in nextRoad && nextRoad.encodeToByteArray().size <= 512)
        require('\u0000' !in sourceName && sourceName.encodeToByteArray().size <= 64)
        require(maneuver == null || maneuver in 0..53)
        require(distanceMeters == null || distanceMeters in 0..0xffffffffL)
        require('\u0000' !in instruction && instruction.encodeToByteArray().size <= 512)
    }
}

/** AMap LaneAction, not the maneuver enum. Foreground 255 means this lane is not recommended. */
data class NavigationLane(val direction: Int, val recommended: Boolean, val foregroundDirection: Int? = null)

internal object AmapNotificationParser {
    private val distance = Regex("^(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米|km|m)\\s*(?:后|後)?\\s*[,，:：]?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val actions = listOf(
        "向左后方转弯" to 47, "向右后方转弯" to 48,
        "向左前方行驶" to 49, "向右前方行驶" to 50,
        "靠左行驶" to 13, "靠右行驶" to 14,
        "左转" to 1, "右转" to 2, "直行" to 3, "掉头" to 4,
    )

    fun parse(texts: List<String>, ongoing: Boolean): NavigationCue? {
        if (!ongoing) return null
        // A generic “导航中” or trip advertisement is insufficient to infer a maneuver.
        for (raw in texts) {
            val text = raw.replace('\u0000', ' ').trim().take(128)
            if (text == "已到达目的地" || text == "您已到达目的地") return NavigationCue(text, null, null, 2)
            if (text == "正在重新规划路线" || text == "重新规划路线中") return NavigationCue(text, null, null, 5)
            val match = distance.matchEntire(text) ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            val unit = match.groupValues[2].lowercase()
            val meters = value * if (unit in listOf("公里", "千米", "km")) 1000 else 1
            if (!meters.isFinite() || meters !in 0.0..1_000_000.0) continue
            val action = match.groupValues[3]
            val type = actions.firstOrNull { action.startsWith(it.first) }?.second ?: continue
            return NavigationCue(action, type, meters.toLong())
        }
        return null
    }
}
