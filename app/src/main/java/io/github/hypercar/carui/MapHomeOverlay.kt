package io.github.hypercar.carui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.github.hypercar.core.CodecSurfaceBridge
import io.github.hypercar.spike.SpikeCaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Buttons are part of the encoded map frame. Android can hide application overlay windows. */
internal class MapHomeOverlay(
    private val caster: SpikeCaster, output: Surface, private val width: Int, private val height: Int,
    private val onFailure: (Exception) -> Unit,
) : AutoCloseable {
    private val bridge = CodecSurfaceBridge(output, width, height, width, height, onFailure)
    val inputSurface get() = bridge.input
    @Volatile var input: MapOverlayInput? = null
        private set
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private val hide = Runnable { if (!closed) bridge.setOverlayVisible(false) }
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var placement: MapButtonPlacement
    private var previouslyShown = false
    @Volatile private var focused: MapControl? = null

    @Synchronized fun attach(withReset: Boolean) {
        placement = MapButtonPlacement(CarDisplayLayout.contentBounds(width, height), withReset,
            CarUiSettings.mapButtonPosition())
        install(placement.layout)
        scope.launch {
            CarDisplayLayout.state.collect { display ->
                // Renegotiation clears the declaration before the receiver sends its next one.
                val bounds = display?.contentBounds(width, height) ?: return@collect
                synchronized(this@MapHomeOverlay) {
                    if (!closed && placement.resize(bounds)) {
                        try { install(placement.layout) } catch (error: Exception) { onFailure(error) }
                    }
                }
            }
        }
        peek()
    }

    private fun install(layout: MapControlBar) {
        val first = layout.buttons.values.first()
        val last = layout.buttons.values.last()
        val bitmap = Bitmap.createBitmap(first.width, last.bottom - first.y, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for ((control, bounds) in layout.buttons) {
                canvas.save()
                canvas.translate(0f, (bounds.y - first.y).toFloat())
                val size = bounds.width.toFloat()
                paint.color = 0xCF171B24.toInt()
                canvas.drawRoundRect(0f, 0f, size, size, size * .25f, size * .25f, paint)
                if (focused == control) {
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.WHITE
                    paint.strokeWidth = 4f
                    canvas.drawRoundRect(3f, 3f, size - 3f, size - 3f, size * .23f, size * .23f, paint)
                    paint.style = Paint.Style.FILL
                }
                paint.color = Color.WHITE
                if (control == MapControl.RESET) {
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = size * .28f
                    val middle = size * .5f - (paint.ascent() + paint.descent()) / 2
                    canvas.drawText("界面", size * .5f, middle - size * .16f, paint)
                    canvas.drawText("复位", size * .5f, middle + size * .16f, paint)
                } else {
                    canvas.scale(size, size)
                    val house = Path().apply {
                        moveTo(.18f, .47f); lineTo(.5f, .19f); lineTo(.82f, .47f)
                        lineTo(.76f, .47f); lineTo(.76f, .79f); lineTo(.55f, .79f)
                        lineTo(.55f, .59f); lineTo(.45f, .59f); lineTo(.45f, .79f)
                        lineTo(.24f, .79f); lineTo(.24f, .47f); close()
                    }
                    canvas.drawPath(house, paint)
                }
                canvas.restore()
            }
            synchronized(bridge) {
                input = MapOverlayInput(caster, layout)
                bridge.installOverlay(bitmap, first.x, first.y) { shown, left, top ->
                    input = MapOverlayInput(caster, layout.at(left, top), if (shown) layout.buttons.keys else emptySet())
                    if (shown != previouslyShown) caster.requestSyncFrame()
                    previouslyShown = shown
                }
            }
        } finally { bitmap.recycle() }
    }

    @Synchronized fun giveFocus(request: ReceiverFocus): Boolean {
        if (closed || !::placement.isInitialized) return false
        focused = request.target(placement.layout.buttons, width, height) ?: return false
        install(placement.layout)
        peek(hold = true)
        return true
    }

    @Synchronized fun key(key: CarConsoleKey, activate: (MapControl) -> Unit): Boolean {
        val selected = focused ?: return false
        if (closed) return false
        val buttons = placement.layout.buttons.keys.toList()
        when (key) {
            CarConsoleKey.SELECT -> if (selected in input?.drawn.orEmpty()) activate(selected)
            CarConsoleKey.NEXT, CarConsoleKey.DOWN, CarConsoleKey.PREVIOUS, CarConsoleKey.UP -> {
                val step = if (key == CarConsoleKey.NEXT || key == CarConsoleKey.DOWN) 1 else -1
                focused = buttons[(buttons.indexOf(selected) + step).coerceIn(buttons.indices)]
                install(placement.layout)
                peek(hold = true)
            }
            else -> {
                clearFocus()
                return false
            }
        }
        return true
    }

    @Synchronized fun clearFocus() {
        if (focused == null || closed) return
        focused = null
        install(placement.layout)
        peek()
    }

    @Synchronized fun move(dx: Int, dy: Int, save: Boolean = false) {
        if (closed) return
        placement.dragBy(dx, dy)
        if (save) placement.endDrag(true)?.let(CarUiSettings::saveMapButtonPosition)
        bridge.moveOverlay(placement.layout.left, placement.layout.top)
    }

    @Synchronized fun cancelDrag() {
        if (closed) return
        placement.endDrag(false)
        bridge.moveOverlay(placement.layout.left, placement.layout.top)
    }

    fun peek(hold: Boolean = false) {
        main.post {
            if (!closed) {
                bridge.setOverlayVisible(true)
                main.removeCallbacks(hide)
                if (!hold) main.postDelayed(hide, 4000)
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        scope.cancel()
        main.removeCallbacks(hide)
        bridge.close()
        input = null
    }
}
