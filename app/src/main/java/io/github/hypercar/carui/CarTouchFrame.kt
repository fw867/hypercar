package io.github.hypercar.carui

import android.view.InputDevice
import android.view.MotionEvent

/** The ordered touch snapshot for a single Android action and its changed pointer. */
internal class CarTouchFrame private constructor(val action: Int, private val points: IntArray) {
    val masked: Int get() = action and MotionEvent.ACTION_MASK
    val pointerCount: Int get() = points.size / 3
    private val changed: Int get() = action ushr MotionEvent.ACTION_POINTER_INDEX_SHIFT
    val x: Int get() = points[changed * 3 + 1]
    val y: Int get() = points[changed * 3 + 2]

    fun cancelAfter(): CarTouchFrame? = when (masked) {
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> null
        MotionEvent.ACTION_POINTER_UP -> CarTouchFrame(
            MotionEvent.ACTION_CANCEL,
            IntArray(points.size - 3).also { remaining ->
                val start = changed * 3
                points.copyInto(remaining, endIndex = start)
                points.copyInto(remaining, destinationOffset = start, startIndex = start + 3)
            },
        )
        else -> CarTouchFrame(MotionEvent.ACTION_CANCEL, points)
    }

    fun obtain(downTime: Long, eventTime: Long): MotionEvent {
        val count = pointerCount
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = points[i * 3]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i * 3 + 1].toFloat()
                y = points[i * 3 + 2].toFloat()
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, count, properties, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    companion object {
        fun fromView(event: MotionEvent, viewWidth: Int, viewHeight: Int, width: Int, height: Int): CarTouchFrame? {
            if (viewWidth <= 0 || viewHeight <= 0 || width <= 0 || height <= 0 || event.pointerCount > 16) return null
            val points = IntArray(event.pointerCount * 3)
            val sx = width.toFloat() / viewWidth
            val sy = height.toFloat() / viewHeight
            for (i in 0 until event.pointerCount) {
                points[i * 3] = event.getPointerId(i)
                points[i * 3 + 1] = (event.getX(i) * sx).toInt().coerceIn(0, width - 1)
                points[i * 3 + 2] = (event.getY(i) * sy).toInt().coerceIn(0, height - 1)
            }
            return fromNative(event.action, points)
        }

        fun fromNative(action: Int, points: IntArray): CarTouchFrame? {
            if (action < 0 || points.size !in 3..48 || points.size % 3 != 0) return null
            val count = points.size / 3
            val masked = action and MotionEvent.ACTION_MASK
            val changed = action ushr MotionEvent.ACTION_POINTER_INDEX_SHIFT
            when (masked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> if (count != 1 || changed != 0) return null
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> if (changed != 0) return null
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                    if (count < 2 || changed >= count) return null
                else -> return null
            }
            var ids = 0
            for (i in points.indices step 3) {
                val id = points[i]
                if (id !in 0..31 || points[i + 1] < 0 || points[i + 2] < 0) return null
                val mask = 1 shl id
                if (ids and mask != 0) return null
                ids = ids or mask
            }
            return CarTouchFrame(action, points)
        }
    }
}

/** Keeps a gesture with the page that received its initial DOWN. Called on the UI thread. */
internal class CarTouchGesture {
    data class Grant(val engine: Long, val screen: Long)

    class Target(
        val isCurrent: () -> Boolean,
        val send: (CarTouchFrame) -> Unit,
        val cancel: (CarTouchFrame) -> Unit = send,
    )

    private var target: Target? = null
    private var cancelFrame: CarTouchFrame? = null
    private var activeGrant: Grant? = null

    fun dispatch(frame: CarTouchFrame, grant: Grant, current: Boolean, targetForDown: () -> Target?) {
        if (!current || frame.masked == MotionEvent.ACTION_CANCEL) {
            if (activeGrant == grant) cancel()
            return
        }
        if (activeGrant != grant) cancel()
        dispatch(frame, targetForDown)
        activeGrant = if (target == null) null else grant
    }

    fun dispatch(frame: CarTouchFrame, targetForDown: () -> Target?) {
        if (frame.masked == MotionEvent.ACTION_DOWN) {
            cancel()
            target = targetForDown()
        }
        val origin = target ?: return
        if (!origin.isCurrent()) {
            cancel()
            return
        }
        cancelFrame = frame.cancelAfter()
        if (cancelFrame == null) target = null
        origin.send(frame)
    }

    fun cancel() {
        val origin = target
        val frame = cancelFrame
        target = null
        cancelFrame = null
        activeGrant = null
        if (origin != null && frame != null) origin.cancel(frame)
    }
}

/** Page changes and the final map injection share one ordering boundary. */
internal class CarTouchPage {
    data class Stamp(val map: Boolean, val revision: Long)

    @Volatile private var current = Stamp(false, 0)

    fun snapshot() = current

    @Synchronized fun showMap(map: Boolean) {
        if (map != current.map) current = Stamp(map, current.revision + 1)
    }

    @Synchronized fun dispatch(stamp: Stamp, action: () -> Unit) {
        if (current == stamp) action()
    }
}
