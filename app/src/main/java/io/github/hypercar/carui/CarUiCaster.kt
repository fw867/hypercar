package io.github.hypercar.carui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    private var width = 1920
    private var height = 720
    @Volatile var plan = io.github.hypercar.settings.VideoEncodingPlan.fit(2560, 744)
        private set
    val DPI: Int get() = plan.densityDpi

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var caster: SpikeCaster? = null
    private var displayId = -1
    private var cpuLock: PowerManager.WakeLock? = null

    @Volatile
    var running: Boolean = false
        private set

    val currentDisplayId: Int get() = displayId

    private val lifecycle = SessionResourceLifecycle()

    suspend fun start(context: Context, current: () -> Boolean = { true }): String {
        val ticket = lifecycle.ticket()
        return lifecycle.start(ticket, current, ::stopLocked) {
            startLocked(context.applicationContext) { lifecycle.isCurrent(ticket) && current() }
        } ?: "会话已停止，取消界面启动"
    }

    private suspend fun startLocked(context: Context, current: () -> Boolean): String {
        if (running) {
            // A new native engine needs the touch callback and source dimensions again.
            installTouchListener()
            if (SettingsStore.enableAudio) CarAudioCapture.start() else CarAudioCapture.stop()
            // 引擎重连会新建一个 encoder_size（初始值来自设置，可能是 2560x744）；
            // 画面尺寸必须重新上报，否则 Rust 触摸坐标按 2560x744 缩放，D1/D2 上点不动。
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
            if (MapController.mapActive) MapController.refreshVideoSize()
            scope.launch {
                kotlinx.coroutines.delay(900)
                if (!current()) return@launch
                runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
                if (MapController.mapActive) MapController.refreshVideoSize()
            }
            return "已经在跑（display=$displayId，已重新上报 ${width}x$height）"
        }
        plan = SettingsStore.encodingPlan
        width = plan.width
        height = plan.height
        HyperCarRepo.log("CARUI: viewport=${SettingsStore.receiverViewport ?: (SettingsStore.videoWidth to SettingsStore.videoHeight)} stream=${width}x${height}@${plan.fps} dpi=$DPI")
        val c = SpikeCaster(width = width, height = height, fps = plan.fps, bitrate = plan.bitrate, tag = TAG)
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
        // Old APK services can retain displays whose encoder surface has died. Only clean
        // processes before binding; killing an already bound service leaves a stale Binder.
        if (!SpikeShell.isConnected() && !SpikeShell.isBinding && ShizukuTools.isReady) {
            runCatching { ShizukuTools.exec("pkill -f 'io.github.hypercar:spike'", 2_000) }
            kotlinx.coroutines.delay(150)
        }
        val bind = SpikeShell.connect(context)
        // 清掉上一进程/上次会话残留的副屏（shell UserService 独立存活，不随 App 进程退出）
        runCatching { SpikeShell.releaseDisplay() }
        val created = SpikeShell.createDisplay(surface, width, height, DPI, 0, "hypercar-d1")
        val id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (id <= 0) {
            c.stop()
            caster = null
            return "shell 建屏失败: $created（bind=$bind）"
        }
        displayId = id
        val component = "io.github.hypercar/" + CarHomeActivity::class.java.name
        val launch = SpikeDisplay.amStartComponentOnDisplay(id, component, clearTask = false)
        installTouchListener()
        MapController.init(context, c)

        // 会话期 CPU 常醒 + 虚拟屏独立显示组：锁屏/息屏后车机 UI 仍保持 60fps，
        // 这是防冻结的关键，不能再删。
        runCatching {
            cpuLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hypercar:carui-cpu")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
        }

        running = true
        if (SettingsStore.enableAudio) CarAudioCapture.start()
        // 引擎刚起时 nativeSetVideoSize 可能落在 ENGINE 句柄安装之前；延迟补报一次
        scope.launch {
            kotlinx.coroutines.delay(900)
            if (!current()) return@launch
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
            if (MapController.mapActive) MapController.refreshVideoSize()
        }
        val msg = "启动 ok display=$id | ${width}x${height} | $encoder | bind=$bind | create=$created | launch=$launch"
        HyperCarRepo.log("CARUI: $msg")
        return msg
    }

    fun requestKeyFrame() {
        main.post {
            if (MapController.mapActive) MapController.requestKeyFrame()
            else caster?.requestSyncFrame()
        }
    }

    /**
     * Install the input callback for the currently owned display. Lifecycle changes
     * serialize listener installation with old-display teardown.
     */
    private fun installTouchListener() {
        RustCoreBridge.setTouchListener(object : RustCoreBridge.TouchListener {
            override fun onTouch(action: Int, x: Int, y: Int) {
                if (MapController.onTouch(action, x, y)) return
                main.post {
                    val act = CarHomeActivity.instance
                    if (act == null) {
                        HyperCarRepo.log("CARUI: touch drop（home instance=null）($x,$y)")
                    } else {
                        act.dispatchCarTouch(action, x.toFloat(), y.toFloat())
                    }
                }
            }
        })
    }

    /** Retire synchronously; all cleanup stays off the UI thread. */
    fun stopAsync() {
        val retired = lifecycle.retire()
        scope.launch { lifecycle.stop(retired, ::stopLocked) }
    }

    private inline fun release(resource: String, block: () -> Unit) {
        runCatching(block).onFailure { HyperCarRepo.log("CARUI: 释放 $resource 失败 ${it.message}") }
    }

    private fun stopLocked() {
        release("audio") { CarAudioCapture.stop() }
        if (!running && displayId < 0 && caster == null) return
        // 先收地图（释放 D2/复原横屏覆盖），再收 D1
        release("map") { MapController.stop() }
        release("map state") { MapController.reset() }
        release("touch") { RustCoreBridge.setTouchListener(null) }
        if (displayId > 0) release("display") { SpikeShell.releaseDisplay() }
        displayId = -1
        release("encoder") { caster?.stop() }
        caster = null
        running = false

        release("wake lock") { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        HyperCarRepo.log("CARUI: 已停止（显示/编码器已释放）")
    }
}
