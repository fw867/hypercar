package io.github.hypercar.spike

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
 * SPIKE-P0：把 spike 放进前台服务跑。
 *
 * 为什么需要：手机一息屏/被别的 App 顶到后台，普通 Activity 所在进程会被 HyperOS 冻结，
 * spike 协程停住；而且测试占用主屏。前台服务让 spike 在后台跑完并落盘报告。
 *
 * 触发（shell 显式启动前台服务，不受后台启动限制）：
 * ```
 * adb shell am start-foreground-service -n io.github.hypercar/.spike.SpikeRunService --es what shell
 * adb shell am start-foreground-service -n io.github.hypercar/.spike.SpikeRunService --es what s1
 * ```
 */
class SpikeRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        runCatching {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "HyperCar P0 Spike", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val what = intent?.getStringExtra("what") ?: "shell"
        val seconds = intent?.getIntExtra("seconds", 120) ?: 120
        // 虚拟屏只在手机屏幕亮着时合成；FGS 里必须自己持有亮屏锁，
        // 否则锁屏/息屏时 D2 的窗口不会被 layout，focus 为 null，注入全丢。
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "hypercar:p0-spike",
                )
                ?.apply { acquire(15 * 60 * 1000L) }
        }
        startForeground(NOTIF_ID, notification(what))
        SpikeReport.add("FGS", "启动 what=$what（SDK_INT=${Build.VERSION.SDK_INT}）")
        scope.launch {
            val result = runCatching {
                when (what) {
                    "shell" -> SpikeRunner.shellTest(this@SpikeRunService)
                    "s1" -> SpikeRunner.s1(this@SpikeRunService)
                    "s2" -> SpikeRunner.s2(this@SpikeRunService)
                    "s3" -> SpikeRunner.s3(this@SpikeRunService, seconds)
                    else -> "未知 what=$what"
                }
            }.getOrElse { "异常: ${it.javaClass.simpleName}: ${it.message}" }
            SpikeReport.add("FGS", result)
            SpikeReport.write(this@SpikeRunService, "fgs-$what")
            HyperCarRepo.log("SPIKE: [FGS] 结束 what=$what：$result")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(what: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("HyperCar P0 Spike")
            .setContentText("running: $what")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "hypercar-p0-spike"
        const val NOTIF_ID = 0x5348
    }
}
