package io.github.hypercar.carui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.spike.SpikeCaster
import io.github.hypercar.spike.SpikeDisplay
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DeX 画面源：经 Shizuku 建一块虚拟屏，把三星 DeX 桌面（SecondaryLauncher）放上去，
 * 屏的输出直接写进 H.264 编码器，与自绘 UI 共用同一条推流链路。
 *
 * 与自绘 UI 的差异：
 *  - 画面由 DeX 自己渲染，本模块不绘制任何 UI；
 *  - 触摸走系统注入（[SpikeShell.touchEvent]），不再依赖 CarHomeActivity 的 View 树。
 */
object DexCaster {

    private const val TAG = "HyperCar-Dex"

    /** DeX 桌面在 1080p 级画布上用 240dpi，排布接近真实外接显示器。 */
    private const val DPI = 240
    /** VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS：决定该屏能否被判为可进桌面模式。 */
    private const val FLAG_SYSTEM_DECORATIONS = 1 shl 9

    private const val COMPONENT =
        "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 与 CarUiCaster 一致：启动/停止串行化，并用会话代次挡住旧任务清理新会话。 */
    private val lifecycle = SessionResourceLifecycle()

    private var caster: SpikeCaster? = null
    @Volatile private var displayId = -1
    private var width = 1920
    private var height = 1080
    /** DeX 实际渲染尺寸（2K 超采样）：比画布大，镜像时缩回画布再编码，文字更锐利。 */
    private var renderWidth = 1920
    private var renderHeight = 1080
    /** 渲染屏的密度：随超采样倍数放大，保证 dp 布局与 1080p 时一致。 */
    private var renderDpi = DPI
    private var cpuLock: PowerManager.WakeLock? = null

    @Volatile
    var running: Boolean = false
        private set

    val currentDisplayId: Int get() = displayId


    /** 把虚拟屏切换成 freeform，并回报 shell 的真实输出，便于在车机上确认是否生效。 */
    private suspend fun applyFreeform(id: Int): String = runCatching {
        val r = ShizukuTools.exec("cmd window set-display-windowing-mode -d $id 5", 4_000)
        "exit=${r.code} out=${r.out.trim()} err=${r.err.trim()}"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    suspend fun start(context: Context, current: () -> Boolean = { true }): String {
        if (running) {
            installTouchListener()
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
            return "已经在跑（display=$displayId，${width}x$height）"
        }
        val ticket = lifecycle.ticket()
        return lifecycle.start(ticket, current, ::stopLocked) {
            startLocked(context.applicationContext) { lifecycle.isCurrent(ticket) && current() }
        } ?: "会话已停止，取消 DeX 启动"
    }

    private suspend fun startLocked(context: Context, current: () -> Boolean): String {
        val plan = SettingsStore.encodingPlan.encoderCandidates().firstOrNull()
            ?: return "没有可用的编码方案"
        width = plan.width
        height = plan.height
        // 超采样：DeX 按「设置 → 分辨率与帧率 → DeX 渲染分辨率」的目标宽度渲染，
        // 镜像进画布时降采样，文字/图标边缘会明显更干净。dpi 同比放大，dp 尺寸不变。
        val renderTarget = SettingsStore.dexRenderWidth
        val superScale = if (renderTarget > width) renderTarget.toFloat() / width else 1f
        renderWidth = ((width * superScale).toInt()).let { if (it % 2 == 0) it else it - 1 }
        renderHeight = ((height * superScale).toInt()).let { if (it % 2 == 0) it else it - 1 }
        renderDpi = Math.round(DPI * superScale)
        HyperCarRepo.log("DEX: 渲染 $renderWidth" + "x$renderHeight" + " dpi=$renderDpi → 画布 " + "${width}x$height")
        val next = SpikeCaster(
            width = width,
            height = height,
            fps = plan.fps,
            bitrate = plan.bitrate,
            tag = TAG,
            preferredCodec = SettingsStore.preferredVideoCodec,
        )
        val encoder = try {
            next.startEncoder()
        } catch (e: Exception) {
            next.stop()
            return "编码器启动失败: ${e.message}"
        }
        val surface = next.encoderSurface ?: run {
            next.stop()
            return "编码器没有 InputSurface"
        }
        caster = next
        val bind = SpikeShell.connect(context)
        if (SpikeShell.userServiceBinder == null || !current()) {
            next.stop()
            caster = null
            return "shell 建屏取消（bind=$bind）"
        }
        // 三星隐藏开发者开关：让副屏进入真正的桌面模式（应用以自由窗口打开、有壁纸和任务栏）。
        // 改完要重启一次才彻底生效，这里每次会话都写一遍保证它不会丢。
        runCatching {
            ShizukuTools.exec("settings put global enable_desktop_mode_on_secondary_display 1", 4_000)
        }
        // 三星 DeX 只把「系统叠加视图」（开发者选项里的模拟辅助显示设备，type=OVERLAY）
        // 当成桌面显示器：在那块屏上点图标就能开自由窗口。shell 自建的 VIRTUAL 屏做不到，
        // 所以这里先让系统建一块叠加视图，再把它的画面镜像进编码器。
        runCatching { ShizukuTools.exec("settings put global enable_freeform_support 1", 4_000) }
        runCatching { ShizukuTools.exec("settings put global force_resizable_activities 1", 4_000) }
        // 路线 A：普通虚拟屏 + SHOULD_SHOW_SYSTEM_DECORATIONS(1<<9)。
        // 桌面模式的判据链是 DesktopTasksController → WindowManager.isEligibleForDesktopMode →
        // DisplayContent.isSystemDecorationsSupported()（要求 isTrusted + 系统装饰开关）。
        // 这条如果通了，就完全不需要系统叠加视图，手机主屏也不会再冒出预览小窗。
        // 上一次会话如果异常结束，镜像屏会留着（vm 不会随进程回收），这里先兜底释放。
        if (mirrorDisplayId > 0) {
            runCatching { SpikeShell.releaseDisplay(mirrorDisplayId) }
            mirrorDisplayId = -1
        }
        val plain = SpikeShell.createDisplay(
            surface, width, height, DPI, FLAG_SYSTEM_DECORATIONS, "hypercar-dex",
        )
        val plainId = Regex("id=(\\d+)").find(plain)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        // Dextop 的关键一步：给这块屏「开放系统装饰」。WMS 的 DisplayWindowSettings 里没有该屏
        // 条目时 shouldShowSystemDecorsLocked() 恒为 false，这正是我们之前 eligible=false 的卡点。
        var plainEligible = false
        if (plainId > 0) {
            val decors = runCatching { SpikeShell.setSystemDecors(plainId, true) }.getOrDefault(false)
            HyperCarRepo.log("DEX: 系统装饰 display=$plainId -> ok=$decors")
            runCatching { ShizukuTools.exec("wm set-display-engagement-mode -d $plainId 3", 4_000) }
            runCatching { ShizukuTools.exec("cmd window set-display-windowing-mode -d $plainId 5", 4_000) }
            plainEligible = runCatching { SpikeShell.isDesktopEligible(plainId) }.getOrDefault(false)
            if (!plainEligible) {
                kotlinx.coroutines.delay(400)
                plainEligible = runCatching { SpikeShell.isDesktopEligible(plainId) }.getOrDefault(false)
            }
        }
        HyperCarRepo.log("DEX: 裸屏 $plain eligible=$plainEligible")
        var overlayId = -1
        var created = plain
        var id = plainId
        if (plainId > 0 && plainEligible) {
            // 裸屏就是画布尺寸，没有超采样空间。
            renderWidth = width
            renderHeight = height
            renderDpi = DPI
            overlayMode = false
        } else {
            // 路线 B：系统叠加视图（type=OVERLAY）+ 镜像进编码器。
            if (plainId > 0) SpikeShell.releaseDisplay(plainId)
            overlayId = ensureOverlayDisplay(renderWidth, renderHeight, renderDpi)
            created = if (overlayId > 0) {
                SpikeShell.createMirrorDisplay(surface, width, height, DPI, overlayId)
            } else {
                SpikeShell.createDisplay(
                    surface, width, height, DPI, FLAG_SYSTEM_DECORATIONS, "hypercar-dex",
                )
            }
            // 记录镜像屏 id：它和叠加视图是两块屏，stop 时必须显式释放，否则每轮会话漏一块虚拟屏。
            mirrorDisplayId = Regex("mirror id=(\\d+)")
                .find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (overlayId > 0 && !created.startsWith("OK")) {
                next.stop()
                caster = null
                releaseOverlayDisplayNow()
                return "叠加视图镜像失败: $created（overlay=$overlayId）"
            }
            id = if (overlayId > 0) {
                overlayId
            } else {
                Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }
        }
        if (id <= 0) {
            next.stop()
            caster = null
            return "shell 建屏失败: $created（bind=$bind）"
        }
        displayId = id
        // 虚拟屏默认是 fullscreen 窗口模式，应用会整屏铺满、也没有标题栏；
        // 切成 freeform 后每个应用是带标题栏的浮动窗口（mode_id=5 即 WINDOWING_MODE_FREEFORM）。
        HyperCarRepo.log("DEX: freeform display=$id -> " + applyFreeform(id))
        val launch = SpikeDisplay.amStartComponentOnDisplay(id, COMPONENT, clearTask = false)
        if (!current()) {
            stopLocked()
            return "会话已停止，取消 DeX 启动"
        }
        installTouchListener()
        holdCpuAwake(context)
        running = true
        // 壁纸已由系统按屏自适应（实测桌面上墙纸正常），不再需要 shell 侧 setDimensionHints。
        CarAudioCapture.start(context)
        runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
        scope.launch {
            kotlinx.coroutines.delay(900)
            if (!current()) return@launch
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
            HyperCarRepo.log("DEX: freeform retry display=$id -> " + applyFreeform(id))
        }
        val msg = "启动 ok display=$id | ${width}x${height}@${plan.fps} dpi=$DPI | $encoder | bind=$bind | launch=$launch"
        HyperCarRepo.log("DEX: $msg")
        // 取证镜像只在 debug 包里开，正式包不多建一块屏。
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            dumpDebugFrames(context, width, height, id)
        }
        return msg
    }

    /**
     * 调试取证：把 DeX 所在的那块屏再镜像一份到 ImageReader，存成 PNG。
     * `screencap -d` 对叠加视图无效，这是唯一能看到「车机到底收到什么画面」的办法。
     */
    private fun dumpDebugFrames(context: Context, width: Int, height: Int, sourceDisplayId: Int) {
        scope.launch {
            val dir = java.io.File(context.getExternalFilesDir(null), "dbg")
            runCatching { dir.mkdirs() }
            runCatching { dir.listFiles()?.forEach { it.delete() } }
            val reader = android.media.ImageReader.newInstance(
                width, height, android.graphics.PixelFormat.RGBA_8888, 3
            )
            val created = runCatching {
                SpikeShell.createMirrorDisplay(reader.surface, width, height, DPI, sourceDisplayId)
            }.getOrNull()
            HyperCarRepo.log("DEX-DBG: 取证镜像 source=$sourceDisplayId -> $created")
            if (created == null) {
                runCatching { reader.close() }
                return@launch
            }
            val mirrorId = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull()
            var saved = 0
            val deadline = System.currentTimeMillis() + 8_000L
            while (saved < 3 && System.currentTimeMillis() < deadline) {
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image == null) {
                    kotlinx.coroutines.delay(400)
                    continue
                }
                runCatching {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val bmp = android.graphics.Bitmap.createBitmap(
                        width + rowPadding / plane.pixelStride, height,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, width, height)
                    val file = java.io.File(dir, "dex-dbg-$saved.png")
                    java.io.FileOutputStream(file).use {
                        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    saved++
                    HyperCarRepo.log("DEX-DBG: 帧已存 ${file.absolutePath}")
                }
                runCatching { image.close() }
                kotlinx.coroutines.delay(700)
            }
            runCatching { reader.close() }
            mirrorId?.let { runCatching { SpikeShell.releaseDisplay(it) } }
            HyperCarRepo.log("DEX-DBG: 取证结束 saved=$saved")
        }
    }

    fun requestKeyFrame() {
        main.post { caster?.requestSyncFrame() }
    }

    /** 车机侧按键：直接注入到 DeX 所在的屏。 */
    internal fun onConsoleKey(key: CarConsoleKey) {
        val id = displayId
        if (!running || id <= 0) return
        val result = SpikeShell.consoleKey(id, key.keyCode, key.metaState)
        if (result != "OK") HyperCarRepo.log("DEX: console input $key display=$id $result")
    }

    /** 触摸注入失败只记前几次，避免刷屏。 */
    private var touchErrLogged = 0
    /** 每次会话前几帧触摸留痕，用来判断车机触摸到底有没有到 Kotlin 侧。 */
    private var touchTraceLogged = 0

    private fun installTouchListener() {
        touchErrLogged = 0
        touchTraceLogged = 0
        RustCoreBridge.setTouchListener(object : RustCoreBridge.TouchListener {
            override fun onTouchFrame(
                engineGeneration: Long,
                screenGrant: Long,
                action: Int,
                pointers: IntArray,
            ) {
                val id = displayId
                if (id <= 0 || pointers.size < 3) return
                val grantOk = runCatching { RustCoreBridge.nativeTouchGrantCurrent(engineGeneration, screenGrant) }
                    .getOrDefault(false)
                if (touchTraceLogged < 5) {
                    touchTraceLogged++
                    HyperCarRepo.log("DEX-TOUCH: action=$action pointers=${pointers.size / 3} " +
                        "grant=$grantOk display=$id")
                }
                if (!grantOk) return
                // Rust 侧给的是编码画布坐标；DeX 按更大分辨率渲染时要按比例放大，否则触摸点会整体偏移。
                val sx = if (width > 0) renderWidth.toFloat() / width else 1f
                val sy = if (height > 0) renderHeight.toFloat() / height else 1f
                val count = pointers.size / 3
                val properties = Array(count) { i ->
                    MotionEvent.PointerProperties().apply {
                        this.id = pointers[i * 3]
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val coords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = pointers[i * 3 + 1] * sx
                        y = pointers[i * 3 + 2] * sy
                        pressure = 1f
                        size = 1f
                    }
                }
                val now = SystemClock.uptimeMillis()
                if (action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_DOWN) downTime = now
                val ev = MotionEvent.obtain(
                    if (downTime == 0L) now else downTime,
                    now,
                    action,
                    count,
                    properties,
                    coords,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
                try {
                    val r = SpikeShell.touchEvent(id, ev)
                    if (r != "OK" && touchErrLogged < 6) {
                        touchErrLogged++
                        HyperCarRepo.log("DEX: 触摸注入失败 display=$id -> $r")
                    }
                } finally {
                    ev.recycle()
                    if (action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_UP ||
                        action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_CANCEL) {
                        downTime = 0L
                    }
                }
            }
        })
    }

    @Volatile private var downTime = 0L
    /** 本轮是否用了系统叠加视图（DeX 原生窗口化依赖它）。 */
    @Volatile private var overlayMode = false
    /** 镜像我们那块屏的 displayId（不是叠加视图本身）。 */
    @Volatile private var mirrorDisplayId = -1

    /** 让系统建一块叠加视图（模拟辅助显示设备），返回它的 displayId；失败返回 -1。 */
    private suspend fun ensureOverlayDisplay(w: Int, h: Int, dpi: Int): Int {
        // 尺寸/密度必须严格匹配：上一次会话残留的叠加屏会沿用旧分辨率，
        // 所以先清掉再按当前请求重建（否则从 1080p 切 2K 时会静默复用旧屏）。
        if (SpikeShell.findOverlayDisplay() > 0) {
            releaseOverlayDisplayNow()
            kotlinx.coroutines.delay(600)
        }
        val size = "$w" + "x" + "$h"
        // 手机屏上会画一块「叠加视图预览窗」，它同时就是该屏的 surface 提供者，无 root 无法隐藏
        // （OverlayDisplayAdapter/OverlayDisplayWindow 源码）。这里刻意不加 gravity / disable_window_interaction：
        // 让窗口保持可拖动、可双指缩放，位置由用户自己随手摆。
        runCatching { ShizukuTools.exec("settings put global overlay_display_devices \"$size/$dpi\"", 4_000) }
        repeat(12) {
            kotlinx.coroutines.delay(300)
            val id = SpikeShell.findOverlayDisplay()
            if (id > 0) {
                overlayMode = true
                return id
            }
        }
        return -1
    }

    /** 同步清掉叠加屏设置（重建前用，不能走 scope.launch 否则会和后续轮询抢时序）。 */
    private suspend fun releaseOverlayDisplayNow() {
        overlayMode = false
        runCatching {
            ShizukuTools.exec("settings put global overlay_display_devices \"\"", 4_000)
        }
    }


    private fun holdCpuAwake(context: Context) {
        runCatching {
            cpuLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hypercar:dex-cpu")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
        }
    }

    fun stopAsync() {
        val retired = lifecycle.retire()
        scope.launch { lifecycle.stop(retired, ::stopLocked) }
    }

    /**
     * 只清理属于 DeX 会话的资源。普通自绘模式的断开流程也会转到这里，所以：
     *  - 没起过 DeX（没有任何 DeX 资源）就直接返回，不碰共享的音频采集与触摸回调；
     *  - shell 用户服务是共享的（地图卡片、高德投射也在用），不在这里解绑，
     *    否则会把正在保留的地图屏和导航任务一起销毁。
     */
    private suspend fun stopLocked() {
        if (!DexSessionPolicy.ownsResources(running, displayId, mirrorDisplayId, caster != null, overlayMode)) return
        release("audio") { CarAudioCapture.stop() }
        release("touch") { RustCoreBridge.setTouchListener(null) }
        if (mirrorDisplayId > 0) {
            release("mirror") { SpikeShell.releaseDisplay(mirrorDisplayId) }
            mirrorDisplayId = -1
        }
        if (displayId > 0 && !overlayMode) release("display") { SpikeShell.releaseDisplay(displayId) }
        displayId = -1
        // 叠加屏是全局设置：必须在串行区内同步清掉，否则会删掉下一轮刚建的屏。
        if (overlayMode) releaseOverlayDisplayNow()
        release("encoder") { caster?.stop() }
        caster = null
        running = false
        release("wake lock") { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        HyperCarRepo.log("DEX: 投屏已停止（DeX 屏与编码器已释放，共享 shell 服务保留）")
    }

    private inline fun release(resource: String, block: () -> Unit) {
        runCatching(block).onFailure { HyperCarRepo.log("DEX: 释放 $resource 失败 ${it.message}") }
    }
}
