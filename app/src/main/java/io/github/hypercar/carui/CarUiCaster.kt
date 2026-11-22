package io.github.hypercar.carui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.core.content.ContextCompat
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeCaster
import io.github.hypercar.spike.SpikeDisplay
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

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
    @Volatile private var displayId = -1
    @Volatile private var displayService: IBinder? = null
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

    /** Observe and recover the source owned by this engine. The native screen gate still controls video. */
    suspend fun monitor(context: Context, attempt: Long, engine: Long, current: () -> Boolean) = coroutineScope {
        val app = context.applicationContext
        val ticket = lifecycle.ticket()
        fun active() = lifecycle.isCurrent(ticket) && current()
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val displays = app.getSystemService(DisplayManager::class.java)
        val events = Channel<Pair<String, Long>>(Channel.BUFFERED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                events.trySend(intent.action.orEmpty() to SystemClock.elapsedRealtime())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { HyperCarRepo.log("CARUI-LIFECYCLE: attempt=$attempt screen receiver unavailable: ${it.message}") }.isSuccess
        var event: Pair<String, Long>? = "monitor_start" to SystemClock.elapsedRealtime()
        var previous = ""
        var pending = false
        var retryAt = 0L
        var recovery: Deferred<Boolean>? = null
        fun log(message: String) = HyperCarRepo.log(
            "CARUI-LIFECYCLE: attempt=$attempt engine=$engine elapsedMs=${SystemClock.elapsedRealtime()} $message")
        try {
            while (active()) {
                recovery?.takeIf { it.isCompleted }?.let {
                    pending = !it.await()
                    retryAt = SystemClock.elapsedRealtime() + 5_000
                    recovery = null
                }
                val mainAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val userService = SpikeShell.userServiceBinder
                val id = displayId
                val display = displays?.getDisplay(id)
                val displayState = when (display?.state) {
                    Display.STATE_ON -> "ON"
                    Display.STATE_OFF -> "OFF"
                    Display.STATE_DOZE -> "DOZE"
                    Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
                    null -> if (id > 0) "REMOVED" else "NONE"
                    else -> display.state.toString()
                }
                val pipeline = HyperCarRepo.snapshots.value.pipeline
                val screenOwner = when (pipeline.screenOwned) {
                    true -> "phone"
                    false -> "receiver"
                    null -> "unknown"
                }
                val state = "interactive=${power?.isInteractive} locked=${keyguard?.isDeviceLocked} " +
                    "keyguard=${keyguard?.isKeyguardLocked} shizukuMain=$mainAlive shizukuUser=${userService != null} " +
                    "displayId=$id displayState=$displayState screenOwner=$screenOwner video=${pipeline.videoStream}"
                if (event != null || state != previous) {
                    log("event=${event?.first ?: "state_changed"} eventElapsedMs=${event?.second ?: SystemClock.elapsedRealtime()} $state")
                    previous = state
                }
                val originalService = displayService
                if (recovery == null && originalService != null &&
                    (originalService !== userService || (displays != null && id > 0 && display == null))) {
                    pending = true
                }
                if (pending && recovery == null && mainAlive && SystemClock.elapsedRealtime() >= retryAt) {
                    recovery = async {
                        try {
                            if (!active() || !ShizukuTools.isReady) return@async false
                            val bind = SpikeShell.connect(app)
                            if (!active()) return@async false
                            if (SpikeShell.userServiceBinder == null) {
                                log("recovery waiting for user service bind=$bind")
                                return@async false
                            }
                            log("recovery rebuilding source oldDisplayId=$id")
                            val result = lifecycle.restart(ticket, ::active, ::stopLocked) {
                                startLocked(app, ::active)
                            }
                            val rebuilt = result != null && running
                            if (active()) log("recovery rebuilt=$rebuilt displayId=$displayId result=$result")
                            rebuilt
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (active()) log("recovery failed: ${error.message}")
                            false
                        }
                    }
                }
                event = withTimeoutOrNull(500) { events.receive() }
            }
        } finally {
            recovery?.cancel()
            if (registered) app.unregisterReceiver(receiver)
        }
    }

    private suspend fun startLocked(context: Context, current: () -> Boolean): String {
        val desiredCodec = SettingsStore.preferredVideoCodec
        if (running && caster?.preferredCodec != desiredCodec) {
            HyperCarRepo.log("CARUI: video codec preference changed; rebuilding home/map encoders")
            stopLocked()
        }
        if (running) {
            // A new native engine needs the touch callback and source dimensions again.
            installTouchListener()
            CarAudioCapture.start(context)
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
        val requested = SettingsStore.encodingPlan
        var lastError: Exception? = null
        var encoder = ""
        for (candidate in requested.encoderCandidates()) {
            if (!current()) return "会话已停止，取消编码启动"
            val next = SpikeCaster(width = candidate.width, height = candidate.height, fps = candidate.fps,
                bitrate = candidate.bitrate, tag = TAG, preferredCodec = desiredCodec)
            try {
                encoder = next.startEncoder()
            } catch (e: Exception) {
                next.stop()
                lastError = e
                HyperCarRepo.log("CARUI: encoder rejected ${candidate.width}x${candidate.height}@${candidate.fps} codec=${next.actualCodec} error=${e.message}")
                continue
            }
            caster = next
            plan = candidate
            width = candidate.width
            height = candidate.height
            break
        }
        val c = caster ?: return "编码器启动失败: ${lastError?.message}"
        HyperCarRepo.log("CARUI: requested=${requested.width}x${requested.height}@${requested.fps} stream=${width}x${height}@${plan.fps} dpi=$DPI $encoder")
        val surface = c.encoderSurface ?: run {
            c.stop()
            caster = null
            return "编码器没有 InputSurface"
        }
        val bind = SpikeShell.connect(context)
        val boundService = SpikeShell.userServiceBinder
        if (!current() || boundService == null) {
            c.stop()
            caster = null
            return "shell 建屏取消（bind=$bind）"
        }
        // Reclaim dead encoder displays without destroying the retained navigation task.
        MapPreview.init(context)
        MapPreview.prepareProjection()
        val created = SpikeShell.createDisplay(surface, width, height, DPI, 0, "hypercar-d1")
        val id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (id <= 0) {
            c.stop()
            caster = null
            return "shell 建屏失败: $created（bind=$bind）"
        }
        displayId = id
        displayService = boundService
        val component = "io.github.hypercar/" + CarHomeActivity::class.java.name
        val launch = SpikeDisplay.amStartComponentOnDisplay(id, component, clearTask = false)
        if (!launch.contains(" → exit=0 ")) {
            stopLocked()
            return "首页启动失败：$launch"
        }
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
        CarAudioCapture.start(context)
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

    internal fun onConsoleKey(key: CarConsoleKey) {
        if (!running) return
        val target = if (MapController.mapActive) {
            if (key == CarConsoleKey.BACK) {
                MapController.requestExit()
                return
            }
            MapController.fullscreenDisplayId
        } else displayId
        if (target <= 0) return
        val result = SpikeShell.consoleKey(target, key.keyCode, key.metaState)
        if (result != "OK") HyperCarRepo.log("CARUI: console input $key display=$target $result")
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

    private suspend fun stopLocked() {
        release("audio") { CarAudioCapture.stop() }
        if (!running && displayId < 0 && caster == null) return
        // Await surface detachment before destroying encoders; keep the map/navigation alive.
        release("map") { MapController.stop() }
        release("touch") { RustCoreBridge.setTouchListener(null) }
        if (displayId > 0) release("display") { SpikeShell.releaseDisplay(displayId) }
        displayId = -1
        displayService = null
        release("encoder") { caster?.stop() }
        caster = null
        running = false

        release("wake lock") { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        HyperCarRepo.log("CARUI: 投屏已停止（地图任务与导航保留，首页显示/编码器已释放）")
    }

    /** Source changes explicitly relinquish the self-drawn map instead of retaining two navigators. */
    suspend fun releaseForCarWith() {
        val retired = lifecycle.retire()
        lifecycle.stop(retired) {
            stopLocked()
            MapPreview.release()
        }
    }
}
