package io.github.hypercar.carui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeCaster
import io.github.hypercar.spike.SpikeDisplay
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * P1：把自绘首页（[CarHomeActivity]）放到一块 1920×720 的副屏上，并用编码器采集推给车机。
 *
 * 与 S3 的差异：
 *  - 画面不是 `Presentation`，而是 **shell 启动的 Activity**（Compose 生命周期正常，
 *    和 P2 的 D2 托管同一套路径）；
 *  - 触摸不再走注入：Rust HID → [RustCoreBridge.TouchListener] → 直接 dispatch 给 D1 的 View 树。
 *
 * 依赖 P0 已提交的 spike 基础件（SpikeCaster 的编码器 / SpikeShell 建屏 / SpikeDisplay 启动）。
 */
object CarUiCaster {

    private const val TAG = "HyperCar-CarUI"
    const val WIDTH = 1920
    const val HEIGHT = 720
    const val DPI = 240

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var caster: SpikeCaster? = null
    private var displayId = -1
    private var cpuLock: PowerManager.WakeLock? = null
    private var savedScreenOffTimeout = -1
    private var appContext: Context? = null

    @Volatile
    var running: Boolean = false
        private set

    val currentDisplayId: Int get() = displayId

    suspend fun start(context: Context): String {
        if (running) return "已经在跑（display=$displayId）"
        val c = SpikeCaster(context, width = WIDTH, height = HEIGHT, tag = TAG)
        caster = c
        val encoder = runCatching { c.startEncoder() }.getOrElse { t ->
            caster = null
            return "编码器启动失败: ${t.javaClass.simpleName}: ${t.message}"
        }
        val surface = c.encoderSurface ?: run {
            c.stop()
            caster = null
            return "编码器没有 InputSurface"
        }
        val bind = SpikeShell.connect(context)
        val created = SpikeShell.createDisplay(surface, WIDTH, HEIGHT, DPI, 0, "hypercar-d1")
        val id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (id <= 0) {
            c.stop()
            caster = null
            return "shell 建屏失败: $created（bind=$bind）"
        }
        displayId = id
        val component = "io.github.hypercar/" + CarHomeActivity::class.java.name
        val launch = SpikeDisplay.amStartComponentOnDisplay(id, component, clearTask = true)
        RustCoreBridge.setTouchListener(object : RustCoreBridge.TouchListener {
            override fun onTouch(action: Int, x: Int, y: Int) {
                main.post {
                    CarHomeActivity.instance?.dispatchCarTouch(action, x.toFloat(), y.toFloat())
                }
            }
        })
        appContext = context.applicationContext

        // 会话期 CPU 常醒：面板断电后系统不能进 Doze
        runCatching {
            cpuLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hypercar:carui-cpu")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
        }

        // 仅当用户显式开启「真息屏」时才断面板/拉长超时；默认保持正常亮屏与触摸唤醒。
        val screenOff = if (SettingsStore.phoneScreenOff) {
            savedScreenOffTimeout = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
            }.getOrDefault(60_000)
            runCatching { ShizukuTools.exec("settings put system screen_off_timeout 2147483647") }
            SpikeShell.setPhysicalScreenPower(false)
        } else {
            false
        }

        running = true
        val msg = "启动 ok display=$id | $encoder | bind=$bind | create=$created | " +
            "launch=$launch | screenOff=$screenOff"
        HyperCarRepo.log("CARUI: $msg")
        return msg
    }

    fun stop() {
        if (!running && displayId < 0) return
        // 先点亮物理面板（人可能就在手机前），再释放显示/编码器
        runCatching { SpikeShell.setPhysicalScreenPower(true) }
        RustCoreBridge.setTouchListener(null)
        if (displayId > 0) SpikeShell.releaseDisplay()
        displayId = -1
        runCatching { caster?.stop() }
        caster = null
        running = false

        val ctx = appContext
        if (savedScreenOffTimeout > 0 && ctx != null) {
            val restored = runCatching {
                Settings.System.putInt(
                    ctx.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    savedScreenOffTimeout,
                )
            }.getOrDefault(false)
            if (!restored) {
                val timeout = savedScreenOffTimeout
                scope.launch {
                    runCatching {
                        ShizukuTools.exec("settings put system screen_off_timeout $timeout")
                    }
                }
            }
        }
        savedScreenOffTimeout = -1
        runCatching { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        HyperCarRepo.log("CARUI: 已停止（显示/编码器已释放，物理屏已恢复）")
    }
}
