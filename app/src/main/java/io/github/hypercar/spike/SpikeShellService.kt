package io.github.hypercar.spike

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method

/**
 * SPIKE-P0：跑在 **Shizuku UserService** 进程里的最小 shell 端。
 *
 * 为什么需要它：Android 14+ 起，普通 App 建「非 OWN_CONTENT_ONLY」虚拟屏会被当成屏幕共享，
 * 直接 `SecurityException: Requires CAPTURE_VIDEO_OUTPUT`；而要让副屏能接受触摸/拥有焦点，
 * 还需要 `SUPPORTS_TOUCH | OWN_FOCUS | TRUSTED` 这些隐藏 flag。shell UID 具备
 * `CAPTURE_VIDEO_OUTPUT` / `ADD_MIRROR_DISPLAY`，且非 App UID 不受 hidden-API 限制——
 * 所以把「建屏」这一步搬到 shell 进程（与 headless-android / ShadowAuto 同款做法）。
 *
 * 进程模型：Shizuku 用 app_process 以 shell 身份加载本类并实例化；它不是 Android Service，
 * 没有生命周期回调，VirtualDisplay 的生命周期由本对象的静态字段 + binder 调用维持。
 */
class SpikeShellService : ISpikeShell.Stub {

    constructor() : super() {
        bindContext(null)
    }

    constructor(context: Context) : super() {
        bindContext(context)
    }

    private fun bindContext(context: Context?) {
        appContext = context ?: runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentApplication").invoke(null)
            current as? Context
        }.getOrNull()
        Log.i(TAG, "service init context=${appContext != null}")
    }

    override fun createDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        name: String,
    ): String {
        val ctx = appContext ?: return "ERR 没有 Context"
        if (surface == null) return "ERR surface=null"
        // 关键：Android 17 的 DisplayManagerService 会校验 packageName 与调用 uid 的包列表。
        // UserService 的 uid 是 shell(2000)，Context 却是宿主 App 的（io.github.hypercar）→
        //   SecurityException: packageName must match the owner uid
        // 传 null 也会被拒（已实测）。这里把包名覆盖成 shell uid 自己登记的包名再试。
        synchronized(lock) {
            releaseLocked()
            val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            // 隐藏 flag（AOSP DisplayManager.java）：SUPPORTS_TOUCH=1<<6、
            // TRUSTED=1<<10、OWN_FOCUS=1<<14。OWN_FOCUS 要求 display 同时 TRUSTED。
            val touch = 1 shl 6
            val trusted = 1 shl 10
            val ownFocus = 1 shl 14
            val candidates = intArrayOf(
                base or flags or touch or ownFocus or trusted,
                base or flags or touch or ownFocus,
                base or flags or touch,
                base or flags,
            )

            var lastError: Throwable? = null
            var lastLabel = ""
            for (pkg in listOf("com.android.shell", "android")) {
                val dm = runCatching {
                    val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                    ctor.isAccessible = true
                    ctor.newInstance(PackageOverrideContext(ctx, pkg)) as DisplayManager
                }.getOrElse {
                    SpikeReport.add("SHELL", "构造 DisplayManager(pkg=$pkg) 失败: ${it.message}")
                    null
                } ?: continue
                SpikeReport.add("SHELL", "尝试 package=$pkg（真实 uid=${android.os.Process.myUid()}）")
                for (candidate in candidates) {
                    try {
                        val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, candidate)
                        if (vd != null) {
                            display = vd
                            flagsUsed = candidate
                            val msg = "OK id=${vd.display.displayId} flags=0x${candidate.toString(16)} package=$pkg"
                            SpikeReport.add("SHELL", "createDisplay $msg")
                            return msg
                        }
                        Log.w(TAG, "createDisplay(pkg=$pkg) 返回 null，flags=0x${candidate.toString(16)}")
                    } catch (t: Throwable) {
                        lastError = t
                        lastLabel = "pkg=$pkg flags=0x${candidate.toString(16)}"
                        Log.w(TAG, "createDisplay $lastLabel 失败: $t")
                    }
                }
            }
            val stack = lastError?.stackTraceToString()?.take(1200).orEmpty()
            return "ERR 全部失败（$lastLabel）: ${lastError?.javaClass?.name}: ${lastError?.message}\n$stack"
        }
    }

    override fun releaseDisplay(): String = synchronized(lock) {
        val had = display != null
        releaseLocked()
        "OK released=$had"
    }

    override fun status(): String = synchronized(lock) {
        val vd = display ?: return "OK no display"
        val displayId = vd.display.displayId
        val state = runCatching { vd.display.state }.getOrDefault(-1)
        "OK id=$displayId state=$state flags=0x${flagsUsed.toString(16)}"
    }

    // ------------------------------------------------------------ 触摸注入（shell 身份）

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private var inputError: String = "未初始化"

    override fun inputState(): String {
        val ok = ensureInput()
        return if (ok) "OK inject=${injectMethod != null} displayId=${setDisplayIdMethod != null}"
        else "ERR $inputError"
    }

    override fun tap(displayId: Int, x: Int, y: Int): String {
        if (!ensureInput()) return "ERR $inputError"
        val t0 = SystemClock.elapsedRealtimeNanos()
        val downTime = SystemClock.uptimeMillis()
        val ok = send(displayId, single(displayId, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), downTime)) &&
            run { SystemClock.sleep(40); true } &&
            send(displayId, single(displayId, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), downTime))
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        return if (ok) "OK $us" else "ERR inject 返回 false（$inputError）"
    }

    override fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        if (!ensureInput()) return "ERR $inputError"
        val t0 = SystemClock.elapsedRealtimeNanos()
        val downTime = SystemClock.uptimeMillis()
        val duration = durationMs.coerceAtLeast(120)
        val steps = (duration / 20).coerceAtLeast(6)
        var ok = send(displayId, single(displayId, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), downTime))
        for (i in 1 until steps) {
            val f = i.toFloat() / steps
            ok = send(
                displayId,
                single(
                    displayId,
                    MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * f,
                    y1 + (y2 - y1) * f,
                    downTime,
                ),
            ) && ok
            SystemClock.sleep(8)
        }
        ok = send(displayId, single(displayId, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), downTime)) && ok
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        return if (ok) "OK $us" else "ERR inject 返回 false（$inputError）"
    }

    override fun pinch(
        displayId: Int,
        cx: Int,
        cy: Int,
        fromRadius: Int,
        toRadius: Int,
        steps: Int,
    ): String {
        if (!ensureInput()) return "ERR $inputError"
        val t0 = SystemClock.elapsedRealtimeNanos()
        val downTime = SystemClock.uptimeMillis()
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        var ok = send(displayId, multi(displayId, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(cx - fromRadius.toFloat()), floatArrayOf(cy.toFloat())))
        ok = send(
            displayId,
            multi(
                displayId,
                downTime,
                pointerDown,
                floatArrayOf((cx - fromRadius).toFloat(), (cx + fromRadius).toFloat()),
                floatArrayOf(cy.toFloat(), cy.toFloat()),
            ),
        ) && ok
        val n = steps.coerceAtLeast(4)
        for (i in 1..n) {
            val f = i.toFloat() / n
            val half = fromRadius + (toRadius - fromRadius) * f
            ok = send(
                displayId,
                multi(
                    displayId,
                    downTime,
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(cx - half, cx + half),
                    floatArrayOf(cy.toFloat(), cy.toFloat()),
                ),
            ) && ok
            SystemClock.sleep(10)
        }
        ok = send(
            displayId,
            multi(
                displayId,
                downTime,
                pointerUp,
                floatArrayOf((cx - toRadius).toFloat(), (cx + toRadius).toFloat()),
                floatArrayOf(cy.toFloat(), cy.toFloat()),
            ),
        ) && ok
        ok = send(
            displayId,
            multi(displayId, downTime, MotionEvent.ACTION_UP, floatArrayOf((cx - toRadius).toFloat()), floatArrayOf(cy.toFloat())),
        ) && ok
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        return if (ok) "OK $us" else "ERR inject 返回 false（$inputError）"
    }

    private fun ensureInput(): Boolean {
        if (inputManager != null && injectMethod != null && setDisplayIdMethod != null) return true
        val ctx = appContext ?: run { inputError = "没有 Context"; return false }
        val im = ctx.getSystemService(Context.INPUT_SERVICE) ?: run {
            inputError = "INPUT_SERVICE 不可用"
            return false
        }
        inputManager = im
        injectMethod = runCatching {
            im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.getOrElse {
            inputError = "injectInputEvent 反射失败: ${it.message}"
            null
        }
        setDisplayIdMethod = runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }.getOrElse {
            inputError = "setDisplayId 反射失败: ${it.message}"
            null
        }
        return injectMethod != null && setDisplayIdMethod != null
    }

    private fun single(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            if (action == MotionEvent.ACTION_DOWN) now else downTime,
            now,
            action,
            x,
            y,
            1f,
            1f,
            0,
            1f,
            1f,
            0,
            0,
        )
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return ev
    }

    private fun multi(
        displayId: Int,
        downTime: Long,
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
    ): MotionEvent {
        val props = Array(xs.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(xs.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            xs.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun send(displayId: Int, event: MotionEvent): Boolean {
        if (!ensureInput()) return false
        return try {
            setDisplayIdMethod!!.invoke(event, displayId)
            val result = injectMethod!!.invoke(inputManager, event, 2)
            result !is Boolean || result
        } catch (t: Throwable) {
            inputError = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "inject 失败: $t")
            false
        } finally {
            event.recycle()
        }
    }

    private fun releaseLocked() {
        runCatching { display?.release() }
        display = null
        flagsUsed = 0
    }

    private companion object {
        private const val TAG = "HyperCar-SpikeShell"
        private val lock = Any()
        private var display: VirtualDisplay? = null
        private var flagsUsed: Int = 0
        private var appContext: Context? = null
    }
}
