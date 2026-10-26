package io.github.hypercar.carui

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.ui.theme.HyperCarTheme

/**
 * D1 车机首页（自绘）。由 `CarUiCaster` 通过 Shizuku shell 启动到 1920×720 的副屏上，
 * 它的窗口内容就是编码器采集的画面。
 *
 * 触摸不走系统注入：Rust 收到车机 HID 后用 [dispatchCarTouch] 直接派发给本 Activity 的
 * View 树（D1 是我们自己的内容，零权限）。D2 的高德才需要 Shizuku 注入。
 */
class CarHomeActivity : ComponentActivity() {

    private var downTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            HyperCarTheme {
                CarHomeScreen()
            }
        }
        instance = this
        HyperCarRepo.log(
            "CARUI: CarHomeActivity onCreate display=${display?.displayId} " +
                "mode=${display?.mode?.physicalWidth}x${display?.mode?.physicalHeight}",
        )
    }

    override fun onResume() {
        super.onResume()
        HyperCarRepo.log("CARUI: onResume display=${display?.displayId}")
        MusicBridge.refresh()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        HyperCarRepo.log("CARUI: CarHomeActivity onDestroy")
        super.onDestroy()
    }

    /** Rust HID → UI：action 0=DOWN / 1=UP / 2=MOVE，坐标已是 D1 画布像素。 */
    fun dispatchCarTouch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val a = when (action) {
            0 -> MotionEvent.ACTION_DOWN
            1 -> MotionEvent.ACTION_UP
            2 -> MotionEvent.ACTION_MOVE
            else -> return
        }
        if (a == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(
            if (a == MotionEvent.ACTION_DOWN) now else downTime,
            now,
            a,
            x,
            y,
            0,
        )
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        runCatching { window.decorView.dispatchTouchEvent(ev) }
        ev.recycle()
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) downTime = 0L
    }

    /**
     * 把当前 D1 界面离屏画到 Bitmap 并存 PNG（调试/验收用）。
     *
     * 为什么不用 `screencap -d`：D1 的输出面是编码器 InputSurface，shell 的 screencap
     * 抓不了；而且设备锁屏时虚拟屏暂停合成。把 View 直接 draw 到 Bitmap 不依赖屏幕状态。
     * 必须在主线程调用（内部会切）。
     */
    fun captureToFile(): String? {
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        return runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            view.draw(android.graphics.Canvas(bmp))
            val path = io.github.hypercar.spike.SpikeReport.writePng(this, "carui-home", bmp)
            bmp.recycle()
            path
        }.getOrNull()
    }

    companion object {
        @Volatile
        var instance: CarHomeActivity? = null
            private set
    }
}
