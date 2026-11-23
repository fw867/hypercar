package io.github.hypercar.carui

import android.content.Context
import android.os.SystemClock
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.spike.SpikeCaster
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fullscreen encoding and touch routing for Amap's persistent display, shared with the home card. */
object MapController {

    private const val TAG = "CARUI-MAP"
    private var W = 1920
    private var H = 720

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitions = Mutex()

    private var appContext: Context? = null
    @Volatile private var homeCaster: SpikeCaster? = null
    private var mapCaster: SpikeCaster? = null
    @Volatile private var mapOverlay: MapHomeOverlay? = null
    @Volatile private var imeDiagnostics: MapImeDiagnostics? = null
    private var mapDisplayId = -1
    internal val fullscreenDisplayId: Int get() = if (mapActive) mapDisplayId else -1
    @Volatile private var fullscreenRequested = false
    internal val fullscreenInputActive get() = mapActive && fullscreenRequested

    @Volatile
    var mapActive: Boolean = false
        private set

    private var downX = 0
    private var downY = 0
    private var lastX = 0
    private var lastY = 0
    private var downAt = 0L
    private var moved = false
    private var exitGesture = false
    private var wakingControl = false
    private var pressedOverlay: MapHomeOverlay? = null
    private var pressedControl: MapControl? = null
    private var touchLogs = 0
    private val touchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "carui-map-touch").apply { isDaemon = true }
    }
    private var lastMoveInjectAt = 0L
    // Accessed only by touchExecutor, including cancellation on a receiver limit change.
    private var injectedDownAt: Long? = null
    private var injectedDisplay = -1

    suspend fun init(context: Context, home: SpikeCaster) = transitions.withLock {
        appContext = context.applicationContext
        MapPreview.init(context)
        homeCaster = home
        W = CarUiCaster.plan.width
        H = CarUiCaster.plan.height
        MapPreview.resumePreview()
    }

    /** 引擎重连后重新上报编码尺寸（触摸目标），否则坐标按旧画布缩放。 */
    fun refreshVideoSize() {
        if (mapActive) runCatching { io.github.hypercar.core.RustCoreBridge.nativeSetVideoSize(W, H) }
    }

    fun requestEnter() {
        val expectedHome = homeCaster ?: return
        fullscreenRequested = true
        scope.launch {
            transitions.withLock {
                if (fullscreenRequested && !mapActive && homeCaster === expectedHome)
                    HyperCarRepo.log("CARUI-MAP: ${enter()}")
            }
        }
    }

    fun requestExit() {
        val expectedHome = homeCaster ?: return
        fullscreenRequested = false
        scope.launch {
            transitions.withLock {
                if (!fullscreenRequested && mapActive && homeCaster === expectedHome)
                    HyperCarRepo.log("CARUI-MAP: ${exit()}")
            }
        }
    }

    private suspend fun enter(): String {
        if (mapActive) return "already active"
        val ctx = appContext ?: return "未初始化"
        val home = homeCaster ?: return "home caster 不在"
        val provider = MapApps.selected(ctx) ?: return "请在手机安装高德地图或百度地图"

        val c = SpikeCaster(width = W, height = H, fps = CarUiCaster.plan.fps, bitrate = CarUiCaster.plan.bitrate, tag = "CARUI-MAP", preferredCodec = home.actualCodec, receiverRateLimited = home.receiverRateLimited)
        mapCaster = c
        c.setPushing(false)
        try {
            c.startEncoder()
            val surface = c.encoderSurface ?: error("地图编码器没有 InputSurface")
            val overlay = MapHomeOverlay(c, surface, W, H) { error ->
                HyperCarRepo.log("CARUI-MAP: 地图画面合成失败 ${error.message}")
                scope.launch { transitions.withLock { if (mapCaster === c && mapActive) exit() } }
            }
            mapOverlay = overlay
            val id = MapPreview.showFullscreen(overlay.inputSurface, W, H)
            mapDisplayId = id
            delay(350)
            home.setPushing(false)
            home.setSuspended(true)
            c.setPushing(true)
            c.requestSyncFrame()
            pressedControl = null
            wakingControl = false
            pressedOverlay = null
            overlay.attach(MapPreview.supportsLayoutReset(id))
            if (provider == MapProvider.AMAP) imeDiagnostics = MapImeDiagnostics(id)
            mapActive = true
            return "ok display=$id（保留${provider.label}任务）"
        } catch (e: Exception) {
            cleanupFailed("全屏切换失败: ${e.message}")
            return "切换失败: ${e.message}"
        }
    }

    private suspend fun exit(): String {
        if (!mapActive) return "not active"
        stopImeDiagnostics()
        // Attach the card surface before releasing the encoder surface, without restarting the app.
        MapPreview.resumePreview()
        mapActive = false
        mapDisplayId = -1
        mapCaster?.setPushing(false)
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(true)
        homeCaster?.requestSyncFrame()
        // 回首页后触摸目标必须切回 D1 画布；否则地图尺寸残留会让音乐卡点不中。
        runCatching {
            io.github.hypercar.core.RustCoreBridge.nativeSetVideoSize(
                CarUiCaster.plan.width,
                CarUiCaster.plan.height,
            )
        }
        mapOverlay?.close()
        mapOverlay = null
        runCatching { mapCaster?.stop() }
        mapCaster = null
        return "ok（导航继续显示在首页卡片）"
    }

    private suspend fun cleanupFailed(reason: String) {
        stopImeDiagnostics()
        HyperCarRepo.log("CARUI-MAP: $reason")
        MapPreview.resumePreview()
        mapActive = false
        mapDisplayId = -1
        mapOverlay?.close()
        mapOverlay = null
        runCatching { mapCaster?.stop() }
        mapCaster = null
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(true)
    }

    fun requestKeyFrame() {
        mapCaster?.requestSyncFrame()
    }

    internal fun transferFocus(request: ReceiverFocus): Boolean = mapOverlay?.giveFocus(request) == true

    internal fun cancelMapGesture() {
        MapPreview.cancelCardGesture()
        touchExecutor.execute {
            injectedDownAt?.let { inject(injectedDisplay, 3, 0, 0, it) }
        }
    }

    internal fun overlayKey(key: CarConsoleKey): Boolean {
        val caster = mapCaster ?: return false
        return mapOverlay?.key(key) { control -> requestControl(caster, control) } == true
    }

    private fun requestControl(caster: SpikeCaster, action: MapControl) {
        scope.launch {
            transitions.withLock {
                if (!mapActive || mapCaster !== caster) return@withLock
                if (action == MapControl.HOME) {
                    HyperCarRepo.log("CARUI-MAP: ${exit()}")
                } else {
                    val result = MapPreview.resetFullscreenLayout(mapDisplayId)
                    caster.requestSyncFrame()
                    HyperCarRepo.log("CARUI-MAP: 界面复位 display=$mapDisplayId canvas=${W}x$H $result")
                }
            }
        }
    }

    /**
     * 高德界面实时跟手触摸：DOWN/MOVE/UP 立即异步注入 D2（mode=ASYNC），不再等松手重放。
     * 顶部下滑退出；右侧工具栏的手势由本层消费，避免同时点到地图。
     */
    fun onTouch(action: Int, x: Int, y: Int): Boolean {
        if (!mapActive) return false
        val caster = mapCaster ?: return true
        val dId = mapDisplayId
        if (dId <= 0) return true
        when (action) {
            0 -> {
                downX = x
                downY = y
                lastX = x
                lastY = y
                downAt = SystemClock.uptimeMillis()
                moved = false
                exitGesture = y < 80
                pressedOverlay?.cancelDrag()
                val overlay = mapOverlay
                val input = overlay?.input
                overlay?.clearFocus()
                pressedControl = input?.visible(caster, x, y)
                wakingControl = pressedControl == null && input?.slot(caster, x, y) != null
                pressedOverlay = overlay.takeIf { pressedControl != null }
                overlay?.peek(hold = pressedControl != null)
                if (wakingControl || pressedControl != null) return true
                if (!exitGesture) {
                    val dt = downAt
                    touchExecutor.execute { inject(dId, 0, x, y, dt) }
                }
            }
            2 -> {
                lastX = x
                lastY = y
                if (kotlin.math.abs(x - downX) > 10 || kotlin.math.abs(y - downY) > 10) {
                    moved = true
                }
                if (wakingControl) return true
                if (pressedControl != null) {
                    if (moved) pressedOverlay?.move(x - downX, y - downY)
                    return true
                }
                if (exitGesture) {
                    if (y - downY > 120) {
                        exitGesture = false
                        requestExit()
                    }
                    return true
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastMoveInjectAt >= 8L) {
                    lastMoveInjectAt = now
                    val dt = downAt
                    touchExecutor.execute { inject(dId, 2, x, y, dt) }
                }
            }
            1 -> {
                mapOverlay?.peek()
                if (wakingControl) {
                    wakingControl = false
                    return true
                }
                val control = pressedControl
                if (control != null) {
                    pressedControl = null
                    val overlay = pressedOverlay
                    pressedOverlay = null
                    if (moved || kotlin.math.abs(x - downX) > 10 || kotlin.math.abs(y - downY) > 10) {
                        overlay?.move(x - downX, y - downY, save = true)
                    } else if (overlay?.input?.visible(caster, x, y) == control) {
                        requestControl(caster, control)
                    }
                    return true
                }
                val tapDiagnostics = imeDiagnostics.takeIf {
                    !moved && kotlin.math.abs(x - downX) <= 10 && kotlin.math.abs(y - downY) <= 10
                }
                if (exitGesture) {
                    exitGesture = false
                    if (y - downY > 120) {
                        requestExit()
                    } else {
                        val dt = downAt
                        touchExecutor.execute {
                            inject(dId, 0, x, y, dt)
                            inject(dId, 1, x, y, dt)
                            tapDiagnostics?.tap()
                        }
                    }
                    return true
                }
                val dt = downAt
                touchExecutor.execute {
                    inject(dId, 1, x, y, dt)
                    tapDiagnostics?.tap()
                }
            }
            3 -> {
                if (wakingControl || pressedControl != null) {
                    pressedOverlay?.cancelDrag()
                    pressedOverlay = null
                    mapOverlay?.peek()
                    wakingControl = false
                    pressedControl = null
                    return true
                }
                exitGesture = false
                moved = false
                val dt = downAt
                touchExecutor.execute { inject(dId, 3, x, y, dt) }
            }
        }
        return true
    }

    private fun inject(displayId: Int, action: Int, x: Int, y: Int, downTimeMs: Long) {
        if (CarReceiverUi.state.value.limits.blockMapApp && action != 3) return
        if (action != 0 && (injectedDisplay != displayId || injectedDownAt != downTimeMs)) return
        val result = SpikeShell.motion(displayId, action, x, y, downTimeMs)
        if (action == 0 && result.startsWith("OK")) {
            injectedDisplay = displayId
            injectedDownAt = downTimeMs
        } else if (action == 1 || action == 3) {
            injectedDownAt = null
        }
        if (!result.startsWith("OK")) {
            if (touchLogs++ < 12) {
                HyperCarRepo.log("CARUI-MAP: motion($action $x,$y) display=$displayId 失败: $result")
            }
        } else if (touchLogs++ < 6) {
            HyperCarRepo.log("CARUI-MAP: motion($action $x,$y) display=$displayId OK")
        }
    }

    /** Stop projection only; retain the map display, task, route and native camera. */
    suspend fun stop() = transitions.withLock {
        fullscreenRequested = false
        stopImeDiagnostics()
        runCatching { MapPreview.suspendProjection() }
            .onFailure { HyperCarRepo.log("CARUI-MAP: 分离地图输出失败 ${it.message}") }
        mapActive = false
        mapCaster?.setPushing(false)
        mapOverlay?.close()
        mapOverlay = null
        runCatching { mapCaster?.stop() }
        mapCaster = null
        mapDisplayId = -1
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(false)
        appContext = null
        homeCaster = null
    }

    private fun stopImeDiagnostics() {
        imeDiagnostics?.close()
        imeDiagnostics = null
    }
}
