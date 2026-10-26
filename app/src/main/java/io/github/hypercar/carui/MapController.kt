package io.github.hypercar.carui

import android.content.Context
import android.os.SystemClock
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.MapCompat
import io.github.hypercar.spike.SpikeCaster
import io.github.hypercar.spike.SpikeDisplay
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P2：D2 高德地图 + D1/D2 切源。
 *
 * 结构：
 *  - D1 首页（[CarUiCaster] 的 caster）常驻，`pushing=true`；
 *  - 进地图时：MapCompat 横屏 → shell 建 D2（1920×720，独立显示组/免锁屏）→
 *    `am start --display D2` 启高德 → 新 caster 编码 D2；
 *  - 两个编码器都在跑，但只有 active 的 `pushing=true`，切源只切推送 + 关键帧，不重建显示；
 *  - 触摸：高德界面用 Shizuku 按 displayId 注入；顶部下滑手势退出地图；
 *  - 退出：切回 D1、释放 D2/地图 caster、复原 MapCompat。
 */
object MapController {

    private const val TAG = "CARUI-MAP"
    private const val MAP_PKG = "com.autonavi.minimap"
    private const val MAP_COMPONENT =
        "com.autonavi.minimap/com.autonavi.map.activity.SplashActivity"
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
    private var touchLogs = 0
    private val touchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "carui-map-touch").apply { isDaemon = true }
    }
    private var lastMoveInjectAt = 0L

    fun init(context: Context, home: SpikeCaster) {
        appContext = context.applicationContext
        homeCaster = home
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

        val compat = MapCompat.enable(MAP_PKG)
        HyperCarRepo.log("CARUI-MAP: $compat")
        runCatching { ShizukuTools.exec("am force-stop $MAP_PKG", 5_000) }

        val c = SpikeCaster(ctx, width = W, height = H, tag = "CARUI-MAP")
        mapCaster = c
        c.setPushing(false)
        val enc = runCatching { c.startEncoder() }.getOrElse { t ->
            cleanupFailed("地图编码器启动失败: ${t.javaClass.simpleName}: ${t.message}")
            return "编码器失败: ${t.message}"
        }
        val surface = c.encoderSurface ?: run {
            cleanupFailed("地图编码器没有 InputSurface")
            return "没有 InputSurface"
        }
        val created = SpikeShell.createDisplay(surface, W, H, DPI, 0, "hypercar-d2")
        val id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (id <= 0) {
            cleanupFailed("shell 建 D2 失败: $created")
            return "建屏失败: $created"
        }
        mapDisplayId = id
        val launch = SpikeDisplay.amStartComponentOnDisplay(id, MAP_COMPONENT, clearTask = true)
        HyperCarRepo.log("CARUI-MAP: 高德上 D2（$id）: $launch")
        if (!launch.contains("exit=0")) {
            cleanupFailed("高德启动失败: $launch")
            return "高德启动失败: $launch"
        }

        // 高德冷启动先出 splash/白屏；等它画出首帧再切源，避免车机看到 1.8s 空白
        delay(1_800)
        c.setPushing(true)
        c.requestSyncFrame()
        home.setSuspended(true)
        home.setPushing(false)
        mapActive = true
        MapHomeOverlay.attach(ctx, id) { requestExit() }
        HyperCarRepo.log("CARUI-MAP: 已切到 D2（$enc）")
        return "ok display=$id"
    }

    private suspend fun exit(): String {
        if (!mapActive) return "not active"
        val home = homeCaster
        mapCaster?.setPushing(false)
        home?.setSuspended(false)
        home?.setPushing(true)
        home?.requestSyncFrame()
        runCatching { ShizukuTools.exec("am force-stop $MAP_PKG", 5_000) }
        MapHomeOverlay.detach()
        val id = mapDisplayId
        if (id > 0) SpikeShell.releaseDisplay(id)
        mapDisplayId = -1
        runCatching { mapCaster?.stop() }
        mapCaster = null
        val compat = MapCompat.disable(MAP_PKG)
        mapActive = false
        HyperCarRepo.log("CARUI-MAP: $compat")
        return "ok"
    }

    private fun cleanupFailed(reason: String) {
        HyperCarRepo.log("CARUI-MAP: $reason")
        runCatching { mapCaster?.stop() }
        mapCaster = null
        val id = mapDisplayId
        mapDisplayId = -1
        MapHomeOverlay.detach()
        if (id > 0) SpikeShell.releaseDisplay(id)
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(true)
        scope.launch { runCatching { MapCompat.disable(MAP_PKG) } }
    }

    /**
     * 高德界面实时跟手触摸：DOWN/MOVE/UP 立即异步注入 D2（mode=ASYNC），不再等松手重放。
     * 顶部下滑（y<80 起手，下滑>120）退出地图；左下角 Home 圆钮区域直接回首页。
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
                if (x < 220 && y > 500) return true
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
                if (x < 220 && y > 500 && !moved) {
                    MapHomeOverlay.detach()
                    requestExit()
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
        val wasActive = mapActive || mapCaster != null || mapDisplayId > 0
        mapActive = false
        mapCaster?.setPushing(false)
        runCatching { mapCaster?.stop() }
        mapCaster = null
        val id = mapDisplayId
        mapDisplayId = -1
        MapHomeOverlay.detach()
        if (id > 0) SpikeShell.releaseDisplay(id)
        homeCaster?.setSuspended(false)
        homeCaster?.setPushing(true)
        // 没进过地图就不要碰手机上的高德/横屏覆盖
        if (wasActive) {
            scope.launch {
                runCatching { ShizukuTools.exec("am force-stop $MAP_PKG", 4_000) }
                runCatching { MapCompat.disable(MAP_PKG) }
            }
        }
    }
}
