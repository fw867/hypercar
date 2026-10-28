package io.github.hypercar.carui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.github.hypercar.session.HyperCarRepo

/**
 * P2：地图全屏应用上的「返回主页」浮动按钮。
 *
 * 点全屏地图后，在左侧中间浮出一个半透明圆钮；点它回 D1 首页。
 * 实现为地图副屏的 TYPE_APPLICATION_OVERLAY 小窗（FLAG_NOT_TOUCH_MODAL，
 * 其他区域触摸照常穿透给高德），显示 4 秒后自动收起。
 */
object MapHomeOverlay {

    private const val BUTTON_SIZE = 104
    private const val LEFT_MARGIN = 36

    /** HID hit box follows the visible button, leaving the navigation toolbar unobstructed. */
    fun hitTest(x: Int, y: Int, displayHeight: Int): Boolean {
        val top = (displayHeight - BUTTON_SIZE) / 2
        return x in LEFT_MARGIN..(LEFT_MARGIN + BUTTON_SIZE) && y in top..(top + BUTTON_SIZE)
    }

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var displayId = -1
    private var onHome: (() -> Unit)? = null
    private var view: View? = null
    private var wm: WindowManager? = null
    private var hideTask: Runnable? = null

    fun attach(context: Context, displayId: Int, onHome: () -> Unit) {
        appContext = context.applicationContext
        this.displayId = displayId
        this.onHome = onHome
        main.post { ensureView() }
    }

    /** 用户点了全屏画面：显示/续期按钮。 */
    fun peek() {
        main.post {
            ensureView()
            val v = view ?: return@post
            v.visibility = View.VISIBLE
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
            return
        }
        runCatching {
            val displayContext = ctx.createDisplayContext(display)
            val manager = displayContext.getSystemService(WindowManager::class.java)
                ?: return
            val button = TextView(displayContext).apply {
                text = "⌂"
                textSize = 30f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                alpha = 0.82f
                contentDescription = "返回主页"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                    setStroke(4, 0xCC00E5FF.toInt())
                }
                setOnClickListener {
                    HyperCarRepo.log("CARUI-OVERLAY: 点击主页按钮")
                    onHome?.invoke()
                }
            }
            val lp = WindowManager.LayoutParams(
                BUTTON_SIZE,
                BUTTON_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                x = LEFT_MARGIN
                y = 0
            }
            manager.addView(button, lp)
            view = button
            wm = manager
            scheduleHide()
            HyperCarRepo.log("CARUI-OVERLAY: attached display=$displayId overlay=true")
        }.onFailure {
            HyperCarRepo.log("CARUI-OVERLAY: 添加失败 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun scheduleHide() {
        hideTask?.let { main.removeCallbacks(it) }
        val task = Runnable {
            view?.visibility = View.INVISIBLE
        }
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
        if (v != null && manager != null) {
            runCatching { manager.removeViewImmediate(v) }
        }
    }
}
