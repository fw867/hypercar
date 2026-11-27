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
import io.github.hypercar.core.PipelineDiagnostics
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
import java.util.concurrent.atomic.AtomicLong

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
    private var touchListener: CastTouchListener? = null

    private var caster: SpikeCaster? = null
    private var encoderTargetFps = 0
    @Volatile private var displayId = -1
    @Volatile private var displayService: IBinder? = null
    private var cpuLock: PowerManager.WakeLock? = null

    @Volatile
    var running: Boolean = false
        private set

    val currentDisplayId: Int get() = displayId

    private val lifecycle = SessionResourceLifecycle()
    private enum class RecoveryResult { SKIPPED, FAILED, REBUILT }

    suspend fun start(context: Context, current: () -> Boolean = { true }): String {
        val ticket = lifecycle.ticket()
        return lifecycle.start(ticket, current, { stopLocked() }) {
            startLocked(context.applicationContext, ticket) { lifecycle.isCurrent(ticket) && current() }
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
        var recovery: Deferred<RecoveryResult>? = null
        var frameRecovery = false
        val watchdog = CarUiFrameWatchdog()
        var lastSnapshot: PipelineDiagnostics? = null
        val snapshotAt = AtomicLong()
        fun videoOnline(pipeline: PipelineDiagnostics, now: Long): Boolean =
            pipeline.active && pipeline.screenOwned == true && pipeline.videoChannelReady &&
                pipeline.videoStream == "READY" && pipeline.tcpState == 1 &&
                (pipeline.tcpSampleAgeMs ?: Long.MAX_VALUE) <= 3_000 &&
                now - snapshotAt.get() <= 3_000
        fun log(message: String) = HyperCarRepo.log(
            "CARUI-LIFECYCLE: attempt=$attempt engine=$engine elapsedMs=${SystemClock.elapsedRealtime()} $message")
        try {
            while (active()) {
                recovery?.takeIf { it.isCompleted }?.let {
                    val result = it.await()
                    val now = SystemClock.elapsedRealtime()
                    pending = !frameRecovery && result != RecoveryResult.REBUILT
                    retryAt = now + 5_000
                    if (frameRecovery && result != RecoveryResult.SKIPPED) {
                        watchdog.restartFinished(now, rebuilt = result == RecoveryResult.REBUILT)
                    } else if (result == RecoveryResult.REBUILT) {
                        watchdog.restarted(now)
                    }
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
                val now = SystemClock.elapsedRealtime()
                if (pipeline !== lastSnapshot) {
                    if (lastSnapshot?.let { it.generation != pipeline.generation || it.rebuilds != pipeline.rebuilds } == true) {
                        watchdog.observe(now, false, pipeline.sourceUnits, pipeline.writtenFrames)
                    }
                    lastSnapshot = pipeline
                    snapshotAt.set(now)
                }
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
                val online = videoOnline(pipeline, now)
                val stalled = watchdog.observe(now, running && recovery == null && online,
                    pipeline.sourceUnits, pipeline.writtenFrames)
                val retryFrame = !running && online && watchdog.shouldRetry(now)
                if ((pending || stalled || retryFrame) && recovery == null && mainAlive && now >= retryAt) {
                    val stalledSource = !pending
                    frameRecovery = stalledSource
                    recovery = async {
                        var frameRestartStarted = false
                        try {
                            if (!active()) return@async RecoveryResult.SKIPPED
                            if (!ShizukuTools.isReady) {
                                return@async if (retryFrame) RecoveryResult.FAILED else RecoveryResult.SKIPPED
                            }
                            val bind = SpikeShell.connect(app)
                            if (!active()) return@async RecoveryResult.SKIPPED
                            if (SpikeShell.userServiceBinder == null) {
                                log("recovery waiting for user service bind=$bind")
                                return@async if (retryFrame) RecoveryResult.FAILED else RecoveryResult.SKIPPED
                            }
                            val result = if (stalledSource) {
                                lifecycle.start(ticket, ::active, { stopLocked(keepAudio = true) }) {
                                    val latest = HyperCarRepo.snapshots.value.pipeline
                                    val checkedAt = SystemClock.elapsedRealtime()
                                    if (running == retryFrame || displayId != id || !videoOnline(latest, checkedAt) ||
                                        latest.generation != pipeline.generation ||
                                        latest.rebuilds != pipeline.rebuilds ||
                                        latest.sourceUnits != pipeline.sourceUnits) return@start null
                                    frameRestartStarted = true
                                    log("recovery rebuilding source reason=frame_stall retry=$retryFrame oldDisplayId=$id sourceUnits=${latest.sourceUnits} writtenFrames=${latest.writtenFrames}")
                                    stopLocked(keepAudio = true)
                                    startLocked(app, ticket, keepAudio = true, current = ::active)
                                }
                            } else {
                                log("recovery rebuilding source oldDisplayId=$id")
                                lifecycle.restart(ticket, ::active, { stopLocked() }) {
                                    startLocked(app, ticket, current = ::active)
                                }
                            }
                            val rebuilt = result != null && running
                            if (active()) log("recovery rebuilt=$rebuilt displayId=$displayId result=$result")
                            when {
                                rebuilt -> RecoveryResult.REBUILT
                                frameRestartStarted || !stalledSource -> RecoveryResult.FAILED
                                else -> RecoveryResult.SKIPPED
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (active()) log("recovery failed: ${error.message}")
                            if (frameRestartStarted || retryFrame || !stalledSource) RecoveryResult.FAILED else RecoveryResult.SKIPPED
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

    private suspend fun startLocked(context: Context, ticket: Long, keepAudio: Boolean = false, current: () -> Boolean): String {
        val desiredCodec = SettingsStore.preferredVideoCodec
        val requested = SettingsStore.encodingPlan
        val limited = SettingsStore.receiverMaxFps != null
        if (running && (caster?.preferredCodec != desiredCodec || encoderTargetFps != requested.fps ||
                caster?.receiverRateLimited != limited)) {
            HyperCarRepo.log("CARUI: rebuilding home/map encoders codec=$desiredCodec target_fps=${requested.fps}")
            stopLocked(keepAudio)
        }
        if (running) {
            // A new native engine needs the touch callback and source dimensions again.
            installTouchListener()
            if (!keepAudio) CarAudioCapture.start(context)
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
        var lastError: Exception? = null
        var encoder = ""
        for (candidate in requested.encoderCandidates()) {
            if (!current()) return "会话已停止，取消编码启动"
            val next = SpikeCaster(width = candidate.width, height = candidate.height, fps = candidate.fps,
                bitrate = candidate.bitrate, tag = TAG, preferredCodec = desiredCodec, receiverRateLimited = limited)
            try {
                encoder = next.startEncoder()
            } catch (e: Exception) {
                next.stop()
                lastError = e
                HyperCarRepo.log("CARUI: encoder rejected ${candidate.width}x${candidate.height}@${candidate.fps} codec=${next.actualCodec} error=${e.message}")
                continue
            }
            caster = next
            encoderTargetFps = requested.fps
            plan = candidate
            width = candidate.width
            height = candidate.height
            break
        }
        val c = caster ?: return "编码器启动失败: ${lastError?.message}"
        HyperCarRepo.log("CARUI: requested=${requested.width}x${requested.height}@${requested.fps} stream=${width}x${height}@${plan.fps} requested_fps=${SettingsStore.videoFps} declared_max_fps=${SettingsStore.receiverMaxFps} effective_fps=${requested.fps} encoder_fps=${plan.fps} dpi=$DPI $encoder")
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
        MapPreview.prepareProjection(ticket)
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
            stopLocked(keepAudio)
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
        if (!keepAudio) CarAudioCapture.start(context)
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
            if (MapController.overlayKey(key) || CarReceiverUi.state.value.limits.blockMapApp) return
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
        touchListener?.retire()
        val next = CastTouchListener()
        touchListener = next
        RustCoreBridge.setTouchListener(next)
    }

    private class CastTouchListener : RustCoreBridge.TouchListener {
        @Volatile private var retired = false
        private val gesture = CarTouchGesture()

        override fun onTouchFrame(engineGeneration: Long, screenGrant: Long, action: Int, pointers: IntArray) {
            val frame = CarTouchFrame.fromNative(action, pointers) ?: return
            val page = MapController.touchPageStamp()
            val grant = CarTouchGesture.Grant(engineGeneration, screenGrant)
            fun grantCurrent() = !retired && RustCoreBridge.nativeTouchGrantCurrent(engineGeneration, screenGrant)
            main.post {
                if (retired) return@post
                gesture.dispatch(frame, grant, grantCurrent() && MapController.touchPageStamp() == page) {
                    targetForDown(page, ::grantCurrent)
                }
            }
        }

        fun retire() {
            retired = true
            main.post { gesture.cancel() }
        }

        private fun targetForDown(page: CarTouchPage.Stamp, grantCurrent: () -> Boolean): CarTouchGesture.Target? {
            if (CarReceiverUi.state.value.screenOwned == false) return null
            fun samePage() = MapController.touchPageStamp() == page
            if (page.map) {
                if (!MapController.fullscreenInputActive) return null
                val mapDisplay = MapController.fullscreenDisplayId.takeIf { it > 0 } ?: return null
                fun sameMap() = samePage() && MapController.fullscreenInputActive &&
                    mapDisplay == MapController.fullscreenDisplayId
                return CarTouchGesture.Target(
                    isCurrent = { sameMap() && CarReceiverUi.state.value.screenOwned != false },
                    send = { MapController.onTouch(it, page, grantCurrent) },
                    cancel = { frame ->
                        if (sameMap()) MapController.onTouch(frame, page, grantCurrent)
                        else MapController.cancelFullscreenTouch(page)
                    },
                )
            }
            val activity = CarHomeActivity.instance?.takeIf { it.display?.displayId == displayId } ?: return null
            return CarTouchGesture.Target(
                isCurrent = {
                    activity === CarHomeActivity.instance && samePage() &&
                        activity.display?.displayId == displayId && CarReceiverUi.state.value.screenOwned != false
                },
                send = activity::dispatchCarTouch,
            )
        }
    }

    /** Retire synchronously; all cleanup stays off the UI thread. */
    fun stopAsync(routeInProgress: (() -> Boolean?)? = null) {
        val retired = lifecycle.retire()
        val idleDisplay = if (running && routeInProgress != null)
            MapPreview.idleDisplayAtDisconnect(retired - 1, routeInProgress) else null
        scope.launch { lifecycle.stop(retired) { stopLocked(idleDisplay = idleDisplay) } }
    }

    private inline fun release(resource: String, block: () -> Unit) {
        runCatching(block).onFailure { HyperCarRepo.log("CARUI: 释放 $resource 失败 ${it.message}") }
    }

    private suspend fun stopLocked(keepAudio: Boolean = false, idleDisplay: MapPreview.ProjectionDisplay? = null) {
        if (!keepAudio) release("audio") { CarAudioCapture.stop() }
        if (!running && displayId < 0 && caster == null) return
        // Await surface detachment before destroying encoders; keep the map/navigation alive.
        release("map") { MapController.stop() }
        release("touch") {
            touchListener?.retire()
            touchListener = null
            RustCoreBridge.setTouchListener(null)
        }
        if (displayId > 0) release("display") { SpikeShell.releaseDisplay(displayId) }
        if (idleDisplay != null && displayService != null && displayService == SpikeShell.userServiceBinder)
            release("parked displays") {
                MapPreview.parkAfterProjection(idleDisplay) { lifecycle.isCurrent(idleDisplay.ticket + 1) }
            }
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
