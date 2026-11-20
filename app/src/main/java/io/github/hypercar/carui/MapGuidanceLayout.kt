package io.github.hypercar.carui

/** Sizes the maneuver panel from its map card, not the whole car display. All inputs are in dp. */
internal class MapGuidanceLayout private constructor(
    val compact: Boolean,
    val edge: Float,
    val start: Float,
    val maxPanelWidth: Float,
) {
    val showArrow: Boolean get() = maxPanelWidth >= 120f

    companion object {
        fun forCard(width: Float, height: Float): MapGuidanceLayout {
            val compact = width < 520f || height < 300f
            if (!compact) return MapGuidanceLayout(false, edge = 14f, start = 94f, maxPanelWidth = 290f)

            // Preserve the original speed badge clearance when there is room for readable text.
            // On very narrow cards, give text space before reserving the full badge gutter.
            val edge = minOf(8f, width / 2f)
            val start = (width - edge - 156f).coerceIn(edge, 94f)
            val available = (width - start - edge).coerceAtLeast(0f)
            val target = (width * .48f).coerceIn(156f, 220f)
            return MapGuidanceLayout(true, edge, start, minOf(available, target))
        }
    }
}
