package io.github.hypercar.carui

import kotlin.math.roundToInt

internal enum class MapControl { RESET, HOME }

/** Immutable HID snapshot. Only a button with a committed draw can start an action. */
internal data class MapOverlayInput(val owner: Any, val layout: MapControlBar, val drawn: Set<MapControl> = emptySet()) {
    fun slot(token: Any, x: Int, y: Int): MapControl? = if (owner === token) layout.hit(x, y) else null
    fun visible(token: Any, x: Int, y: Int): MapControl? = slot(token, x, y)?.takeIf { it in drawn }
}

internal data class MapButtonPosition(val x: Float, val y: Float)

/** Keeps the user's relative position independent of rounding and temporary safe insets. */
internal class MapButtonPlacement(
    private var safe: DisplayRect, private val withReset: Boolean, private var saved: MapButtonPosition?,
) {
    var layout = MapControlBar.inside(safe, withReset).restore(saved)
        private set
    private var dragged: MapButtonPosition? = null
    private var anchor = layout
    private var anchorDx = 0
    private var anchorDy = 0
    private var lastDx = 0
    private var lastDy = 0

    fun resize(next: DisplayRect): Boolean {
        if (next == safe) return false
        safe = next
        layout = MapControlBar.inside(next, withReset).restore(dragged ?: saved)
        anchor = layout
        anchorDx = lastDx
        anchorDy = lastDy
        return true
    }

    /** Deltas are measured from DOWN. A resize restarts them from the last pointer sample. */
    fun dragBy(dx: Int, dy: Int) {
        lastDx = dx
        lastDy = dy
        val next = anchor.at(anchor.left + dx - anchorDx, anchor.top + dy - anchorDy)
        val previous = dragged ?: saved ?: layout.position()
        val pos = next.position()
        if (next.left != layout.left || next.top != layout.top) {
            dragged = MapButtonPosition(if (next.left == layout.left) previous.x else pos.x,
                if (next.top == layout.top) previous.y else pos.y)
        }
        layout = next
    }

    fun endDrag(save: Boolean): MapButtonPosition? {
        val commit = dragged.takeIf { save }
        if (commit != null) saved = commit
        layout = MapControlBar.inside(safe, withReset).restore(saved)
        dragged = null
        anchor = layout
        anchorDx = 0
        anchorDy = 0
        lastDx = 0
        lastDy = 0
        return commit
    }
}

internal class MapControlBar private constructor(private val safe: DisplayRect, val buttons: Map<MapControl, DisplayRect>) {
    val left get() = buttons.values.first().x
    val top get() = buttons.values.first().y
    private val travelX get() = safe.width - buttons.values.first().width
    private val travelY get() = safe.height - (buttons.values.last().bottom - top)

    fun at(x: Int, y: Int): MapControlBar {
        val dx = x.coerceIn(safe.x, safe.x + travelX) - left
        val dy = y.coerceIn(safe.y, safe.y + travelY) - top
        return if (dx == 0 && dy == 0) this else MapControlBar(safe, buttons.mapValues { (_, r) ->
            r.copy(x = r.x + dx, y = r.y + dy)
        })
    }

    fun position() = MapButtonPosition(
        (left - safe.x).toFloat() / travelX.coerceAtLeast(1),
        (top - safe.y).toFloat() / travelY.coerceAtLeast(1),
    )

    fun restore(position: MapButtonPosition?): MapControlBar {
        if (position == null || !position.x.isFinite() || !position.y.isFinite()) return this
        return at(safe.x + (travelX * position.x.coerceIn(0f, 1f)).roundToInt(),
            safe.y + (travelY * position.y.coerceIn(0f, 1f)).roundToInt())
    }

    fun hit(x: Int, y: Int): MapControl? = buttons.entries.firstOrNull { (_, r) ->
        x in r.x until r.right && y in r.y until r.bottom
    }?.key

    companion object {
        fun inside(safe: DisplayRect, withReset: Boolean): MapControlBar {
            val controls = if (withReset) MapControl.entries else listOf(MapControl.HOME)
            val gap = if (withReset) minOf(20, safe.height / 12) else 0
            val size = minOf(124, safe.width, (safe.height - gap) / controls.size)
            if (size <= 0) return if (withReset) inside(safe, false) else MapControlBar(safe, emptyMap())
            val left = safe.right - size - minOf(36, safe.width - size)
            val top = safe.y + (safe.height - size * controls.size - gap) / 2
            return MapControlBar(safe, controls.mapIndexed { index, control ->
                control to DisplayRect(left, top + index * (size + gap), size, size)
            }.toMap())
        }
    }
}
