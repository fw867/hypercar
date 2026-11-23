package io.github.hypercar.carui

import android.view.MotionEvent

/** TextureView lifecycle and input callbacks are ordered on the main thread. */
internal class ProjectionSurfaceInput<T : Any>(
    private val send: (displayId: Int, action: Int, x: Int, y: Int, downTime: Long) -> Unit,
) {
    @Volatile var desired: T? = null
        private set
    private var gesture: T? = null
    private var displayId = -1
    private var downAt = 0L
    private var lastX = 0
    private var lastY = 0

    fun replace(request: T?): Boolean {
        if (desired == request) return false
        cancel()
        desired = request
        return true
    }

    fun touch(request: T, id: Int, action: Int, x: Int, y: Int, downTime: Long) {
        if (desired !== request) return
        if (action == MotionEvent.ACTION_DOWN) {
            cancel()
            gesture = request
            displayId = id
            downAt = downTime
        } else if (gesture !== request || displayId != id || downAt != downTime) return
        lastX = x
        lastY = y
        send(id, action, x, y, downTime)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gesture = null
    }

    fun cancel() {
        if (gesture == null) return
        gesture = null
        send(displayId, MotionEvent.ACTION_CANCEL, lastX, lastY, downAt)
    }
}
