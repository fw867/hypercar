package io.github.hypercar.carui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.hypercar.session.HyperCarRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * P1 自绘首页的启动器（前台服务 + 亮屏锁）。
 *
 * 触发：
 * ```
 * adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action start
 * adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action stop
 * ```
 *
 * 后续接进会话层：AirPlay `RECORD` 完成 → start；断开 → stop（P1 收尾做）。
 */
class CarUiService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "HyperCar 车机界面", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "start"
        if (action == "stop") {
            CarUiCaster.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == "shot") {
            // 调试：把 D1 界面离屏画到 PNG（不依赖屏幕是否休眠）
            startForeground(NOTIF_ID, notification())
            val activity = CarHomeActivity.instance
            if (activity == null) {
                HyperCarRepo.log("CARUI: shot 失败——CarHomeActivity 不在")
            } else {
                activity.runOnUiThread {
                    val path = activity.captureToFile()
                    HyperCarRepo.log("CARUI: shot → $path")
                }
            }
            return START_NOT_STICKY
        }
        if (action == "tap") {
            // 调试：直接给 D1 派发一次点按（不依赖屏幕焦点/系统注入），验证 Compose 交互
            startForeground(NOTIF_ID, notification())
            val x = intent?.getFloatExtra("x", 0f) ?: 0f
            val y = intent?.getFloatExtra("y", 0f) ?: 0f
            val activity = CarHomeActivity.instance
            if (activity == null) {
                HyperCarRepo.log("CARUI: tap ($x,$y) 失败——CarHomeActivity 不在")
            } else {
                activity.runOnUiThread {
                    activity.dispatchCarTouch(0, x, y)
                    activity.dispatchCarTouch(1, x, y)
                    HyperCarRepo.log("CARUI: tap ($x,$y) 已派发")
                }
            }
            return START_NOT_STICKY
        }
        if (action == "rusttap") {
            // 调试：走 Rust 的触摸通道（nativeDebugTouch）→ TouchListener → D1 UI，
            // 验证「车机 HID → Rust → Kotlin → Compose」整条链（不需要真实 HID）。
            startForeground(NOTIF_ID, notification())
            val xi = (intent?.getFloatExtra("x", 0f) ?: 0f).toInt()
            val yi = (intent?.getFloatExtra("y", 0f) ?: 0f).toInt()
            io.github.hypercar.core.RustCoreBridge.nativeDebugTouch(0, xi, yi)
            scope.launch {
                kotlinx.coroutines.delay(60)
                io.github.hypercar.core.RustCoreBridge.nativeDebugTouch(1, xi, yi)
                HyperCarRepo.log("CARUI: rusttap ($xi,$yi) 已注入 Rust 触摸通道")
            }
            return START_NOT_STICKY
        }
        if (action == "playpause") {
            startForeground(NOTIF_ID, notification())
            MusicBridge.playPause()
            HyperCarRepo.log("CARUI: playpause → ${MusicBridge.state.value.playing}")
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification())
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "hypercar:carui",
                )
                ?.apply { acquire(30 * 60 * 1000L) }
        }
        scope.launch {
            val result = CarUiCaster.start(applicationContext)
            HyperCarRepo.log("CARUI: service start → $result")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        CarUiCaster.stop()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("HyperCar 车机界面")
            .setContentText("D1 自绘首页运行中")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "hypercar-carui"
        const val NOTIF_ID = 0x4341
    }
}
