package io.github.hypercar.carlife

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宿主侧的「投屏画布」：把 CarLife 递过来的 `CastConfig.surface` 变成虚拟屏，再往上挂内容。
 *
 * 依据（CarWith 的实现，见 docs/NEGOTIATION-GAPS.md §26）：
 * ```java
 * // com/miui/carlink/castfwk/CarlinkStateMachine.java:1166
 * public VirtualDisplay N0(Surface surface, int w, int h, int dpi) {
 *     return displayConnected(surface, w, h, 0, dpi, 30, 2);   // → VirtualDisplay(surface)
 * }
 * ```
 * 即：**CarLife（小米版）只当编码器，像素由宿主往这个 surface 上画**。
 *
 * 实现要点（2026-10-27 实测）：
 * - `VIRTUAL_DISPLAY_FLAG_PUBLIC` 被系统拒绝（需要 ADD_MIRROR_DISPLAY 等签名权限），
 *   普通应用只能用 `OWN_CONTENT_ONLY`（display 变 FLAG_PRIVATE）；
 * - 私有虚显上**不能再 `startActivity(launchDisplayId=...)`**（SecurityException），
 *   但**可以用 `Presentation` 直接挂窗口** —— 这是普通应用能用的正路。
 */
object CastDisplay {
    private const val TAG = "HyperCar"

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var presentation: Presentation? = null

    @Volatile
    private var heldSurface: Surface? = null

    @Volatile
    var attachedDisplayId: Int = -1
        private set

    fun attach(context: Context, width: Int, height: Int, dpi: Int, surface: Surface?) {
        // Binder 回调线程没有 Looper，Presentation 必须在主线程创建
        Handler(Looper.getMainLooper()).post {
            attachOnMain(context.applicationContext, width, height, dpi, surface)
        }
    }

    private fun attachOnMain(context: Context, width: Int, height: Int, dpi: Int, surface: Surface?) {
        if (surface == null) {
            Log.w(TAG, "CAST-DISPLAY: surface 为空，跳过（CarLife 没给编码面）")
            return
        }
        release()
        heldSurface = surface
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val d = dpi.coerceAtLeast(160)
        val dm = context.getSystemService(DisplayManager::class.java)
        val vd = runCatching {
            dm.createVirtualDisplay(
                "hypercar-cast", w, h, d, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, null, null,
            )
        }.getOrNull()
        if (vd == null) {
            Log.e(TAG, "CAST-DISPLAY: createVirtualDisplay 返回 null（${w}x$h@$d）")
            return
        }
        virtualDisplay = vd
        attachedDisplayId = vd.display.displayId
        Log.i(TAG, "CAST-DISPLAY: 已建虚显 id=$attachedDisplayId ${w}x$h@${d}dpi")

        // 用 Presentation 直接挂到这块屏上（私有虚显不允许 startActivity）
        runCatching {
            val p = Presentation(context, vd.display, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            p.setContentView(buildProbeView(context, vd.display))
            p.show()
            presentation = p
            Log.i(TAG, "CAST-DISPLAY: 验证页已挂到虚显 $attachedDisplayId（HyperCar CAST OK）")
        }.onFailure { Log.e(TAG, "CAST-DISPLAY: Presentation 失败 $it") }
    }

    fun release() {
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        heldSurface = null
        attachedDisplayId = -1
    }

    /** 第一版验证内容：大字 + 实时时钟（证明"画的东西"能到车机）。 */
    private fun buildProbeView(context: Context, display: Display): LinearLayout {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(context).apply {
            text = "HyperCar CAST OK"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 64f
            gravity = Gravity.CENTER
        }
        val clock = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            gravity = Gravity.CENTER
        }
        val info = TextView(context).apply {
            text = "display=${display.displayId}  ${display.width}x${display.height}"
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(title)
        root.addView(clock)
        root.addView(info)

        val handler = Handler(Looper.getMainLooper())
        val colors = intArrayOf(
            Color.parseColor("#101820"), Color.parseColor("#102A18"),
            Color.parseColor("#1A1030"), Color.parseColor("#301010"),
        )
        var i = 0
        val tick = object : Runnable {
            override fun run() {
                clock.text = fmt.format(Date())
                // 换底色：肉眼明显、编码帧大小也明显变化（便于判断"画面真的传过去了"）
                root.setBackgroundColor(colors[i % colors.size])
                i++
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
        return root
    }
}
