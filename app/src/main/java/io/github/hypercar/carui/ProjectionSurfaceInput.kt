package io.github.hypercar.carui

import android.view.MotionEvent

/** TextureView lifecycle and input callbacks are ordered on the main thread. */
internal class ProjectionSurfaceInput<T : Any>(
    private val send: (displayId: Int, frame: CarTouchFrame, downTime: Long) -> Boolean,
) {
    @Volatile var desired: T? = null
        private set
    private var gesture: T? = null
    private var displayId = -1
    private var downAt = 0L
    private var cancelFrame: CarTouchFrame? = null

    fun replace(request: T?): Boolean {
        if (desired == request) return false
        cancel()
        desired = request
        return true
    }

    fun touch(request: T, id: Int, frame: CarTouchFrame, downTime: Long) {
        if (desired !== request) return
        if (frame.masked == MotionEvent.ACTION_DOWN) {
            cancel()
            gesture = request
            displayId = id
            downAt = downTime
        } else if (gesture !== request || displayId != id || downAt != downTime) return
        if (!send(id, frame, downTime)) {
            if (frame.masked == MotionEvent.ACTION_CANCEL) {
                gesture = null
                cancelFrame = null
            } else {
                cancel()
            }
            return
        }
        cancelFrame = frame.cancelAfter()
        if (cancelFrame == null) gesture = null
    }

    fun cancel() {
        if (gesture == null) return
        gesture = null
        cancelFrame?.let { send(displayId, it, downAt) }
        cancelFrame = null
    }
}
