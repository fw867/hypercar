package io.github.hypercar.spike

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 受控的注入目标（SPIKE-P0 / S2）。
 *
 * 为什么先打自己：要证明「注入到 D2 的事件确实进了 D2 上那个窗口」，
 * 用高德只能靠截图猜；用自己的全屏 Activity 可以精确数到 DOWN/MOVE/UP 条数与时刻，
 * 还能把「注入开始 → App 收到」的延迟算到微秒（同一进程同一 elapsedRealtime 时基）。
 */
object SpikeProbe {
    data class Ev(
        val atNs: Long,
        val action: Int,
        val x: Float,
        val y: Float,
        val displayId: Int,
    )

    private val lock = Any()
    private val events = ArrayList<Ev>()

    fun record(ev: Ev) {
        synchronized(lock) {
            events.add(ev)
            if (events.size > 2000) events.removeAt(0)
        }
    }

    fun clear() = synchronized(lock) { events.clear() }

    fun count(): Int = synchronized(lock) { events.size }

    fun last(): Ev? = synchronized(lock) { events.lastOrNull() }

    /** 指定时刻之后到达的**第一条**事件（DOWN）——用它量「注入→App 收到」的真实延迟。 */
    fun firstSince(atNs: Long): Ev? = synchronized(lock) { events.firstOrNull { it.atNs >= atNs } }

    fun snapshot(): List<Ev> = synchronized(lock) { events.toList() }

    fun moveCount(): Int = synchronized(lock) { events.count { it.action == MotionEvent.ACTION_MOVE } }
}

class SpikeProbeActivity : Activity() {

    private lateinit var probeView: ProbeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        probeView = ProbeView(this)
        setContentView(probeView)
        SpikeReport.add(
            "PROBE",
            "onCreate displayId=${display?.displayId} " +
                "mode=${display?.mode?.physicalWidth}x${display?.mode?.physicalHeight}",
        )
    }

    override fun onResume() {
        super.onResume()
        SpikeReport.add("PROBE", "onResume displayId=${display?.displayId} view=${probeView.width}x${probeView.height}")
    }

    private class ProbeView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            isFakeBoldText = true
        }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(64, 220, 255) }
        private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 230, 140) }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            SpikeProbe.record(
                SpikeProbe.Ev(
                    atNs = SystemClock.elapsedRealtimeNanos(),
                    action = event.actionMasked,
                    x = event.x,
                    y = event.y,
                    displayId = display?.displayId ?: -1,
                ),
            )
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(8, 12, 20))
            paint.textSize = 64f
            canvas.drawText("D2 PROBE", 60f, 100f, accent)

            paint.textSize = 180f
            canvas.drawText(SpikeProbe.count().toString(), 60f, 320f, paint)

            paint.textSize = 48f
            val dispId = display?.displayId
            canvas.drawText("display=$dispId  view=${width}x${height}", 60f, 420f, paint)
            val last = SpikeProbe.last()
            canvas.drawText(
                last?.let {
                    "last action=${it.action} x=${it.x.toInt()} y=${it.y.toInt()} " +
                        "d=${it.displayId}"
                } ?: "waiting for touch…",
                60f,
                500f,
                paint,
            )
            canvas.drawText("moves=${SpikeProbe.moveCount()}", 60f, 580f, paint)

            // 动起来的色条：截图两次不同 = 屏幕在持续渲染（S1 也用它做对照）
            val t = SystemClock.elapsedRealtime() % 2000L
            val x = 60f + (t / 2000f) * (width - 160).coerceAtLeast(1)
            canvas.drawRect(x, 620f, x + 100f, 700f, green)
            postInvalidateOnAnimation()
        }
    }
}
