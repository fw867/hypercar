package io.github.hypercar.spike

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import io.github.hypercar.shizuku.ShizukuTools
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * S2：把触摸注入到 D2（SPIKE-P0）。
 *
 * 两条路，先易后难：
 *  - **shell**：`ShizukuTools.exec("input -d <id> tap …")`，走 shell 的 input 命令，
 *    一定能用但每次都起一个进程（延迟 ~100ms），适合点击/`swipe`，不适合跟手拖动；
 *  - **binder**：Shizuku 把 `input` 系统服务的 binder 包一层（调用方 uid 变成 shell），
 *    手工组 Parcel 调 `IInputManager.injectInputEvent`。事务码由
 *    [SystemServiceHelper.getTransactionCode] 反射取出，避免把 AIDL 名单抄错。
 */
object SpikeInput {

    private const val TOKEN = "android.hardware.input.IInputManager"
    private const val MODE_WAIT_FOR_FINISH = 2

    data class Result(val ok: Boolean, val elapsedUs: Long, val detail: String = "")

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var transactionCode: Int = -1

    /** 装 binder / 取事务码；返回一行诊断（报告里必留）。 */
    fun prepare(): String {
        binder = runCatching {
            SystemServiceHelper.getSystemService("input")?.let { ShizukuBinderWrapper(it) }
        }.getOrNull()
        transactionCode = runCatching {
            SystemServiceHelper.getTransactionCode(TOKEN, "injectInputEvent")
        }.getOrNull() ?: -1
        return "binder=${binder != null} transactionCode=$transactionCode " +
            "shizukuReady=${ShizukuTools.isReady} shellInput=${runCatching { SpikeShell.inputState() }.getOrNull()}"
    }

    fun isBinderReady(): Boolean = binder != null && transactionCode > 0

    /** 单条事件注入（WAIT_FOR_FINISH：返回时 App 已处理完，便于量延迟）。 */
    fun inject(event: MotionEvent): Result {
        val b = binder
        if (b == null || transactionCode <= 0) {
            return Result(false, 0, "binder 未就绪")
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val t0 = SystemClock.elapsedRealtimeNanos()
        return try {
            data.writeInterfaceToken(TOKEN)
            event.writeToParcel(data, 0)
            data.writeInt(MODE_WAIT_FOR_FINISH)
            b.transact(transactionCode, data, reply, 0)
            reply.readException()
            val ok = reply.readInt() != 0
            Result(ok, (SystemClock.elapsedRealtimeNanos() - t0) / 1000)
        } catch (t: Throwable) {
            SpikeReport.add("S2", "inject 异常 ${t.javaClass.simpleName}: ${t.message}")
            Result(false, (SystemClock.elapsedRealtimeNanos() - t0) / 1000, t.message.orEmpty())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun one(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): MotionEvent =
        multi(displayId, downTime, action, arrayOf(floatArrayOf(x, y)))

    fun tap(displayId: Int, x: Float, y: Float): Result {
        // 应用进程取不到 IInputManager 的事务码（hidden API 限制）时，改用 shell UserService 注入。
        if (!isBinderReady()) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val r = SpikeShell.tap(displayId, x.toInt(), y.toInt())
            return Result(r.startsWith("OK"), (SystemClock.elapsedRealtimeNanos() - t0) / 1000, r)
        }
        val downTime = SystemClock.uptimeMillis()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val down = inject(one(displayId, MotionEvent.ACTION_DOWN, x, y, downTime))
        val up = inject(one(displayId, MotionEvent.ACTION_UP, x, y, downTime))
        return Result(
            down.ok && up.ok,
            (SystemClock.elapsedRealtimeNanos() - t0) / 1000,
            "down=${down.detail} up=${up.detail}",
        )
    }

    fun swipe(
        displayId: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        steps: Int = 12,
        stepMs: Long = 8,
    ): Result {
        if (!isBinderReady()) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val r = SpikeShell.swipe(
                displayId,
                x0.toInt(),
                y0.toInt(),
                x1.toInt(),
                y1.toInt(),
                (steps * stepMs).toInt().coerceAtLeast(120),
            )
            return Result(r.startsWith("OK"), (SystemClock.elapsedRealtimeNanos() - t0) / 1000, r)
        }
        val downTime = SystemClock.uptimeMillis()
        val t0 = SystemClock.elapsedRealtimeNanos()
        var ok = inject(one(displayId, MotionEvent.ACTION_DOWN, x0, y0, downTime)).ok
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            ok = ok && inject(
                one(displayId, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, downTime),
            ).ok
            SystemClock.sleep(stepMs)
        }
        ok = ok && inject(one(displayId, MotionEvent.ACTION_UP, x1, y1, downTime)).ok
        return Result(ok, (SystemClock.elapsedRealtimeNanos() - t0) / 1000)
    }

    /** 双指 pinch（外扩/内收）。返回失败时 detail 里有原因。 */
    fun pinch(
        displayId: Int,
        cx: Float,
        cy: Float,
        from: Float,
        to: Float,
        steps: Int = 10,
    ): Result {
        if (!isBinderReady()) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val r = SpikeShell.pinch(displayId, cx.toInt(), cy.toInt(), from.toInt(), to.toInt(), steps)
            return Result(r.startsWith("OK"), (SystemClock.elapsedRealtimeNanos() - t0) / 1000, r)
        }
        val downTime = SystemClock.uptimeMillis()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val p0 = floatArrayOf(cx - from, cy)
        val p1 = floatArrayOf(cx + from, cy)
        var ok = inject(multi(displayId, downTime, MotionEvent.ACTION_DOWN, arrayOf(p0))).ok
        ok = ok && inject(multi(displayId, downTime, pointerDown, arrayOf(p0, p1))).ok
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            val half = from + (to - from) * f
            ok = ok && inject(
                multi(
                    displayId,
                    downTime,
                    MotionEvent.ACTION_MOVE,
                    arrayOf(floatArrayOf(cx - half, cy), floatArrayOf(cx + half, cy)),
                ),
            ).ok
            SystemClock.sleep(10)
        }
        ok = ok && inject(multi(displayId, downTime, pointerUp, arrayOf(p0, p1))).ok
        ok = ok && inject(multi(displayId, downTime, MotionEvent.ACTION_UP, arrayOf(p0))).ok
        return Result(ok, (SystemClock.elapsedRealtimeNanos() - t0) / 1000)
    }

    private fun multi(displayId: Int, downTime: Long, action: Int, points: Array<FloatArray>): MotionEvent {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i][0]
                y = points[i][1]
                pressure = 1f
                size = 1f
            }
        }
        // 用带 displayId 的 obtain 重载（14 参版本没有 displayId，setDisplayId 是 @hide）。
        // 参数顺序：… deviceId, edgeFlags, source, **displayId**, flags, classification
        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            points.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            displayId,
            0,
            0,
        ) ?: error("MotionEvent.obtain(displayId=…) 返回 null")
    }

    // ---------------------------------------------------------------- shell 基线

    suspend fun shellTap(displayId: Int, x: Float, y: Float): Result {
        if (!ShizukuTools.isReady) return Result(false, 0, "Shizuku 未就绪")
        val t0 = SystemClock.elapsedRealtimeNanos()
        val r = ShizukuTools.exec("input -d $displayId tap ${x.toInt()} ${y.toInt()}", 8_000)
        return Result(
            r.code == 0,
            (SystemClock.elapsedRealtimeNanos() - t0) / 1000,
            "exit=${r.code} out=${r.out.take(200)} err=${r.err.take(200)}",
        )
    }

    suspend fun shellSwipe(
        displayId: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        durationMs: Int = 300,
    ): Result {
        if (!ShizukuTools.isReady) return Result(false, 0, "Shizuku 未就绪")
        val t0 = SystemClock.elapsedRealtimeNanos()
        val r = ShizukuTools.exec("input -d $displayId swipe $x0 $y0 $x1 $y1 $durationMs", 10_000)
        return Result(
            r.code == 0,
            (SystemClock.elapsedRealtimeNanos() - t0) / 1000,
            "exit=${r.code} out=${r.out.take(200)} err=${r.err.take(200)}",
        )
    }

    suspend fun inputUsage(): String {
        if (!ShizukuTools.isReady) return "Shizuku 未就绪"
        val r = ShizukuTools.exec("input 2>&1 | head -8", 6_000)
        return r.out.replace("\n", " | ").take(600)
    }
}
