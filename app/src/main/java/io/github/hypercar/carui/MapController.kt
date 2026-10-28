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

/** Fullscreen encoding and touch routing for Amap's persistent display, shared with the home card. */
object MapController {

    private const val TAG = "CARUI-MAP"
    private const val MAP_PKG = "com.autonavi.minimap"
    private const val W = 1920
    private const val H = 720
    private const val DPI = 240

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var homeCaster: SpikeCaster? = null
    private var mapCaster: SpikeCaster? = null
    private var mapDisplayId = -1
    private var entering = false

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
    private var homeGesture = false
    private var touchLogs = 0
    private val touchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "carui-map-touch").apply { isDaemon = true }
    }
    private var lastMoveInjectAt = 0L

    fun init(context: Context, home: SpikeCaster) {
        appContext = context.applicationContext
        homeCaster = home
        scope.launch { AmapPreview.resumePreview() }
    }

    fun reset() {
        appContext = null
        homeCaster = null
    }

    /** 引擎重连后重新上报编码尺寸（触摸目标），否则坐标按旧画布缩放。 */
    fun refreshVideoSize() {
        if (mapActive) runCatching { io.github.hypercar.core.RustCoreBridge.nativeSetVideoSize(W, H) }
    }

    fun requestEnter() {
        if (mapActive || entering) return
        entering = true
        scope.launch {
            try {
                HyperCarRepo.log("CARUI-MAP: ${enter()}")
            } finally {
                entering = false
            }
        }
    }

    fun requestExit() {
        if (!mapActive) return
        scope.launch {
            HyperCarRepo.log("CARUI-MAP: ${exit()}")
        }
    }

    private fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(MAP_PKG, 0)
        true
    }.getOrDefault(false)

    private suspend fun enter(): String {
        if (mapActive) return "already active"
        val ctx = appContext ?: return "未初始化"
        val home = homeCaster ?: return "home caster 不在"
        if (!installed(ctx)) return "高德未安装（$MAP_PKG）"

        val c = SpikeCaster(ctx, width = W, height = H, tag = "CARUI-MAP")
        mapCaster = c
        c.setPushing(false)
        try {
            c.startEncoder()
            val surface = c.encoderSurface ?: error("地图编码器没有 InputSurface")
            // Retarget the same display at the same density, preserving its navigation engine.
            val id = AmapPreview.showFullscreen(surface, W, H, DPI)
            mapDisplayId = id
            delay(350)
            c.setPushing(true)
            c.requestSyncFrame()
            home.setSuspended(true)
            home.setPushing(false)
            mapActive = true
            MapHomeOverlay.attach(ctx, id) { requestExit() }
            return "ok display=$id（保留高德任务）"
        } catch (e: Exception) {
            cleanupFailed("全屏切换失败: ${e.message}")
            return "切换失败: ${e.message}"
        }
    }

    private suspend fun exit(): String {
        if (!mapActive) return "not active"
        // Attach the card surface before releasing the encoder surface, without restarting the app.
        AmapPreview.resumePreview()
        MapHomeOverlay.detach()
        mapActive = false
        mapDisplayId = -1
        mapCaster?.setPushing(false)
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(true)
        homeCaster?.requestSyncFrame()
        runCatching { mapCaster?.stop() }
        mapCaster = null
        return "ok（导航继续显示在首页卡片）"
    }

    private suspend fun cleanupFailed(reason: String) {
        HyperCarRepo.log("CARUI-MAP: $reason")
        AmapPreview.resumePreview()
        MapHomeOverlay.detach()
        mapActive = false
        mapDisplayId = -1
        runCatching { mapCaster?.stop() }
        mapCaster = null
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(true)
    }

    /**
     * 高德界面实时跟手触摸：DOWN/MOVE/UP 立即异步注入 D2（mode=ASYNC），不再等松手重放。
     * 顶部下滑（y<80 起手，下滑>120）退出地图；左侧中间 Home 圆钮返回首页。
     */
    fun onTouch(action: Int, x: Int, y: Int): Boolean {
        if (!mapActive) return false
        val dId = mapDisplayId
        if (dId <= 0) return true
        when (action) {
            0 -> {
                MapHomeOverlay.peek()
                downX = x
                downY = y
                lastX = x
                lastY = y
                downAt = SystemClock.uptimeMillis()
                moved = false
                exitGesture = y < 80
                homeGesture = MapHomeOverlay.hitTest(x, y, H)
                if (homeGesture) return true
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
                if (homeGesture) return true
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
                if (homeGesture) {
                    homeGesture = false
                    if (!moved && MapHomeOverlay.hitTest(x, y, H)) requestExit()
                    return true
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
                        }
                    }
                    return true
                }
                val dt = downAt
                touchExecutor.execute { inject(dId, 1, x, y, dt) }
            }
            3 -> {
                if (homeGesture) {
                    homeGesture = false
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
        val result = SpikeShell.motion(displayId, action, x, y, downTimeMs)
        if (!result.startsWith("OK")) {
            if (touchLogs++ < 12) {
                HyperCarRepo.log("CARUI-MAP: motion($action $x,$y) display=$displayId 失败: $result")
            }
        } else if (touchLogs++ < 6) {
            HyperCarRepo.log("CARUI-MAP: motion($action $x,$y) display=$displayId OK")
        }
    }

    /** 首页 caster 停止时的同步收尾（会话断开）：释放 D2、复原横屏覆盖。 */
    fun stop() {
        AmapPreview.stop()
        mapActive = false
        mapCaster?.setPushing(false)
        runCatching { mapCaster?.stop() }
        mapCaster = null
        mapDisplayId = -1
        MapHomeOverlay.detach()
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(true)
    }
}
