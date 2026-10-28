package io.github.hypercar.carui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.github.hypercar.session.HyperCarRepo

/**
 * P2：全屏地图右侧中间的「返回主页」浮动按钮。
 *
 * 参照主屏风格：深紫玻璃底 + 青色描边。点全屏画面后浮出，4 秒后自动收起；
 * 点击按钮回 D1 首页。实现为地图副屏的 TYPE_APPLICATION_OVERLAY 小窗，
 * 只占按钮区域，其他触摸照常穿透给高德。
 */
object MapHomeOverlay {

    private const val TAG = "HyperCar-Overlay"
    private const val BUTTON_SIZE = 104
    private const val RIGHT_MARGIN = 36

    /** HID hit box 跟随右侧按钮，避开导航列表/工具条。 */
    fun hitTest(x: Int, y: Int, displayWidth: Int, displayHeight: Int): Boolean {
        val top = (displayHeight - BUTTON_SIZE) / 2
        val left = displayWidth - BUTTON_SIZE - RIGHT_MARGIN
        return x in left..(left + BUTTON_SIZE) && y in top..(top + BUTTON_SIZE)
    }

    private val main = Handler(Looper.getMainLooper())
    private var attempts = 0

    private var appContext: Context? = null
    private var displayId = -1
    private var onHome: (() -> Unit)? = null
    private var view: View? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var hideTask: Runnable? = null

    fun attach(context: Context, displayId: Int, onHome: () -> Unit) {
        appContext = context.applicationContext
        this.displayId = displayId
        this.onHome = onHome
        attempts = 0
        main.post { ensureView() }
    }

    /** 点全屏画面：把按钮显示出来并提到最上层，重新计时 4 秒。 */
    fun peek() {
        main.post {
            ensureView()
            val v = view ?: return@post
            // 自动收起后当前窗口可能被高德盖住；重新挂一次确保回到最高层。
            if (v.visibility != View.VISIBLE) {
                val manager = wm
                val layout = params
                if (manager != null && layout != null) {
                    runCatching {
                        manager.removeViewImmediate(v)
                        manager.addView(v, layout)
                    }.onFailure {
                        view = null
                        wm = null
                        params = null
                    }
                }
            }
            view?.visibility = View.VISIBLE
            view?.bringToFront()
            Log.i(TAG, "peek visible=${view?.visibility} display=$displayId")
            scheduleHide()
        }
    }

    fun detach() {
        main.post { removeView() }
        appContext = null
        displayId = -1
        onHome = null
    }

    private fun ensureView() {
        if (view != null) return
        val ctx = appContext ?: return
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(displayId)
        if (display == null) {
            HyperCarRepo.log("CARUI-OVERLAY: display=$displayId 不存在")
            if (attempts++ < 8) main.postDelayed({ ensureView() }, 250)
            return
        }
        runCatching {
            val displayContext = ctx.createDisplayContext(display)
            val manager = displayContext.getSystemService(WindowManager::class.java) ?: return
            val button = TextView(displayContext).apply {
                text = "⌂"
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                alpha = 0.88f
                contentDescription = "返回主页"
                elevation = 8f
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xE62A2350.toInt(), 0xE6120F24.toInt()),
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(3, 0xCC22D3EE.toInt())
                }
                setOnClickListener {
                    HyperCarRepo.log("CARUI-OVERLAY: 点击主页按钮")
                    onHome?.invoke()
                }
            }
            val layout = WindowManager.LayoutParams(
                BUTTON_SIZE,
                BUTTON_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                x = RIGHT_MARGIN
                y = 0
            }
            manager.addView(button, layout)
            view = button
            wm = manager
            params = layout
            scheduleHide()
            HyperCarRepo.log("CARUI-OVERLAY: attached display=$displayId overlay=true")
            Log.i(TAG, "attached display=$displayId overlay=true")
        }.onFailure {
            HyperCarRepo.log("CARUI-OVERLAY: 添加失败 ${it.javaClass.simpleName}: ${it.message}")
            Log.w(TAG, "attach failed: ${it.javaClass.simpleName}: ${it.message}")
            if (attempts++ < 5) main.postDelayed({ ensureView() }, 300)
        }
    }

    private fun scheduleHide() {
        hideTask?.let { main.removeCallbacks(it) }
        val task = Runnable { view?.visibility = View.INVISIBLE }
        hideTask = task
        main.postDelayed(task, 4_000)
    }

    private fun removeView() {
        hideTask?.let { main.removeCallbacks(it) }
        hideTask = null
        val v = view
        val manager = wm
        view = null
        wm = null
        params = null
        if (v != null && manager != null) {
            runCatching { manager.removeViewImmediate(v) }
        }
    }
}
