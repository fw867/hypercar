package io.github.hypercar.yadea

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.min

@Composable
internal fun YadeaPhonePreview(modifier: Modifier = Modifier) {
    val frame by YadeaProjectionService.previewFrames.collectAsStateWithLifecycle()
    val ratio = frame?.let { (it.width.toFloat() / it.height).coerceIn(1f, 2.5f) } ?: 16f / 9f
    AndroidView(
        factory = ::YadeaPreview,
        modifier = modifier.fillMaxWidth().aspectRatio(ratio),
        update = { it.show(frame) },
    )
}

private class YadeaPreview(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val content = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var lastMoveAt = 0L

    init { contentDescription = "导航预览，点按或拖动以操作导航" }

    fun show(frame: Bitmap?) {
        if (frame === bitmap) return
        if (frame == null || frame.width != bitmap?.width || frame.height != bitmap?.height) cancelGesture()
        bitmap = frame
        updateContent()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        updateContent()
    }

    private fun updateContent() {
        val frame = bitmap ?: return content.setEmpty()
        val scale = min(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val w = frame.width * scale
        val h = frame.height * scale
        content.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(android.graphics.Color.BLACK)
        bitmap?.let { canvas.drawBitmap(it, null, content, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            if (!content.contains(event.x, event.y) || !send(action, event.x, event.y)) return false
            pointer = event.getPointerId(0)
            lastMoveAt = event.eventTime
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (pointer == MotionEvent.INVALID_POINTER_ID) return false
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelGesture()
            return true
        }
        val index = event.findPointerIndex(pointer)
        if (index < 0) {
            cancelGesture()
            return true
        }
        if (action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) {
            send(MotionEvent.ACTION_UP, event.getX(index), event.getY(index))
            endGesture()
            performClick()
        } else if (action == MotionEvent.ACTION_MOVE && event.eventTime - lastMoveAt >= 16) {
            send(action, event.getX(index), event.getY(index))
            lastMoveAt = event.eventTime
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    private fun send(action: Int, x: Float, y: Float) =
        !content.isEmpty && YadeaProjectionService.touch(action,
            (x - content.left) / content.width(), (y - content.top) / content.height())

    private fun cancelGesture() {
        if (pointer != MotionEvent.INVALID_POINTER_ID) {
            YadeaProjectionService.touch(MotionEvent.ACTION_CANCEL, 0f, 0f)
            endGesture()
        }
    }

    private fun endGesture() {
        pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
