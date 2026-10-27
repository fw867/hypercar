package io.github.hypercar.carlife

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 验证用的"投屏内容"Activity：显示大字 + 实时时钟。
 *
 * 它会被 [CastDisplay] 启动到 **CarLife 提供的虚显（CastConfig.surface）** 上，
 * 用来验证"宿主往画布里画 → CarLife 编码 → 8240 → 车机"这条链路是否闭环。
 * 验证通过后，把这个 Activity 换成真正的车机界面即可。
 */
class CastProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private lateinit var clock: TextView
    private lateinit var info: TextView

    private val tick = object : Runnable {
        override fun run() {
            clock.text = fmt.format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "HyperCar CAST OK"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 64f
            gravity = Gravity.CENTER
        }
        clock = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            gravity = Gravity.CENTER
        }
        info = TextView(this).apply {
            text = "display=${display?.displayId}  ${display?.width}x${display?.height} @${resources.displayMetrics.densityDpi}dpi"
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(title)
        root.addView(clock)
        root.addView(info)
        setContentView(root)
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
