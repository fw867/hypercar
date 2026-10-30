package io.github.hypercar.carui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil
import kotlin.math.floor

/** Coordinates are in receiver display pixels, independently of UI density/encoder alignment. */
data class DisplayRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
    fun intersect(other: DisplayRect): DisplayRect? {
        val left = maxOf(x, other.x); val top = maxOf(y, other.y)
        val right = minOf(right.toLong(), other.right.toLong()).toInt()
        val bottom = minOf(bottom.toLong(), other.bottom.toLong()).toInt()
        return if (right > left && bottom > top) DisplayRect(left, top, right-left, bottom-top) else null
    }
}

data class DisplayLayout(val width: Int, val height: Int, val view: DisplayRect, val safe: DisplayRect, val drawOutside: Boolean) {
    fun contentBounds(canvasWidth: Int, canvasHeight: Int): DisplayRect = scale(safe, canvasWidth, canvasHeight)
    fun backgroundBounds(canvasWidth: Int, canvasHeight: Int): DisplayRect = scale(if (drawOutside) view else safe, canvasWidth, canvasHeight)
    private fun scale(area: DisplayRect, canvasWidth: Int, canvasHeight: Int): DisplayRect {
        val full = DisplayRect(0, 0, canvasWidth, canvasHeight)
        if (width <= 0 || height <= 0) return full
        val display = DisplayRect(0, 0, width, height)
        val visible = view.intersect(display) ?: display
        val clipped = area.intersect(visible) ?: visible
        // Round toward the safe interior, including when H.264 even alignment changes the canvas.
        val left = ceil(clipped.x.toDouble() * canvasWidth / width).toInt()
        val top = ceil(clipped.y.toDouble() * canvasHeight / height).toInt()
        val right = floor(clipped.right.toDouble() * canvasWidth / width).toInt()
        val bottom = floor(clipped.bottom.toDouble() * canvasHeight / height).toInt()
        return DisplayRect(left, top, right-left, bottom-top).intersect(full) ?: full
    }
}

object CarDisplayLayout {
    val state = MutableStateFlow<DisplayLayout?>(null)
    fun contentBounds(width: Int, height: Int): DisplayRect = state.value?.contentBounds(width, height) ?: DisplayRect(0,0,width,height)
    fun homeButtonBounds(width: Int, height: Int): DisplayRect {
        val safe = contentBounds(width,height)
        val size = minOf(104,safe.width,safe.height)
        val margin = minOf(36,(safe.width-size).coerceAtLeast(0))
        return DisplayRect(safe.right-size-margin,safe.y+(safe.height-size)/2,size,size)
    }
}
