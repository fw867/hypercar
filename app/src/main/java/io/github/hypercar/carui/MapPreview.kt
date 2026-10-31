package io.github.hypercar.carui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.MapCompat
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One persistent map task, responsive display bounds, and constant density across card/fullscreen. */
object MapPreview {
    private var appContext: android.content.Context? = null
    private var activeProvider: MapProvider? = null
    fun init(context: android.content.Context) { appContext = context.applicationContext }
    private val MAP_DPI: Int get() = CarUiCaster.DPI
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow("正在打开地图…")
    val status: StateFlow<String> = _status
    private var displayId = -1
    private var cardSurface: Surface? = null
    private var target: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var fullscreen = false
    private var stopped = false
    private var carMode = false
    private var layoutMode = 2
    fun attach(texture: SurfaceTexture, w: Int, h: Int) {
        scope.launch {
            lock.withLock {
                val previous = cardSurface
                target = texture
                width = w
                height = h
                texture.setDefaultBufferSize(w, h)
                cardSurface = Surface(texture)
                if (!fullscreen && !stopped) {
                    runCatching { route(cardSurface!!, w, h, 2) }.onFailure { failed(it) }
                }
                previous?.release()
            }
        }
    }

    fun detach(texture: SurfaceTexture) {
        scope.launch {
            lock.withLock {
                if (target === texture) {
                    if (!fullscreen && displayId > 0) {
                        // Keep the task/navigation alive while the applications page hides the card.
                        SpikeShell.updateDisplaySurface(displayId, null, 0, 0, 0)
                    }
                    target = null
                    cardSurface?.release()
                    cardSurface = null
                }
                texture.release()
            }
        }
    }

    suspend fun showFullscreen(surface: Surface, w: Int, h: Int, dpi: Int): Int = lock.withLock {
        check(!stopped) { "地图会话已停止" }
        fullscreen = true
        val id = route(surface, w, h, 1)
        id
    }

    suspend fun resumePreview() = lock.withLock {
        stopped = false
        fullscreen = false
        val output = cardSurface
        if (output != null && output.isValid) {
            runCatching { route(output, width, height, 2) }.onFailure { failed(it) }
        } else if (displayId > 0) {
            SpikeShell.updateDisplaySurface(displayId, null, 0, 0, 0)
        }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                stopped = true
                fullscreen = false
                releaseMapTask()
                // The TextureView owns its SurfaceTexture until its destruction callback.
                cardSurface?.release()
                cardSurface = null
                target = null
            }
        }
    }

    private suspend fun route(output: Surface, w: Int, h: Int, mode: Int): Int {
        val context = appContext ?: error("地图未初始化")
        val provider = MapApps.selected(context) ?: error("请在手机安装高德地图或百度地图")
        if (activeProvider != null && activeProvider != provider) releaseMapTask()
        activeProvider = provider
        if (displayId > 0) {
            // The installed Amap handles screen-size changes but not CONFIG_DENSITY.
            // Changing 160/240dpi recreates its activity; keep 240dpi when resizing the bounds.
            val update = SpikeShell.updateDisplaySurface(displayId, output, w, h, MAP_DPI)
            check(update.startsWith("OK")) { update }
            if (carMode && provider.supportsLayoutIntent && mode != layoutMode) {
                val layout = launchCarMap(mode, splash = false)
                check(layout.ok && !layout.err.contains("Error")) { "车联布局切换失败: ${layout.err}" }
            }
            layoutMode = mode
            _status.value = ""
            HyperCarRepo.log("CARUI-PREVIEW: 保留地图任务 $update carMode=$carMode layout=$mode")
            return displayId
        }
        _status.value = "正在打开${provider.label}…"
        val compat = MapCompat.enable(provider.packageName)
        HyperCarRepo.log("CARUI-PREVIEW: $compat")
        if (!SpikeShell.isConnected()) {
            val binding = SpikeShell.connect(context)
            check(SpikeShell.isConnected()) { binding }
        }
        // Auto-cruise belongs to the map app's own projection settings.
        val mapW = CarUiCaster.plan.width
        val mapH = CarUiCaster.plan.height
        val result = SpikeShell.createDisplay(output, mapW, mapH, MAP_DPI, 0, "hypercar-map")
        displayId = Regex("id=(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        check(displayId > 0) { "无法创建地图窗口: $result" }
        val carLaunch = launchCarMap(mode, splash = true)
        carMode = carLaunch.ok && !carLaunch.err.contains("Error") && !carLaunch.out.contains("Error")
        layoutMode = mode
        if (!carMode) {
            check(provider == MapProvider.AMAP) { "${provider.label}车联入口不可用，请检查地图版本或首次授权：${carLaunch.err}" }
            HyperCarRepo.log("CARUI-PREVIEW: 车联入口不可用，回退手机地图 ${carLaunch.err}")
            val launch = ShizukuTools.exec("am start --activity-clear-task " +
                "-n ${provider.packageName}/com.autonavi.map.activity.SplashActivity --display $displayId", 12000)
            check(launch.ok && !launch.err.contains("Error")) { "${provider.label}启动失败: ${launch.err}" }
        }
        // 第一次以编码画布创建后也要立即适配卡片边界，避免首帧上下留边。
        val update = SpikeShell.updateDisplaySurface(displayId, output, w, h, MAP_DPI)
        check(update.startsWith("OK")) { update }
        HyperCarRepo.log("CARUI-PREVIEW: ${provider.label}初次启动 display=$displayId carMode=$carMode layout=$mode")
        HyperCarRepo.log("CARUI-PREVIEW: 自动巡航跟随${provider.label}原生设置")
        _status.value = ""
        return displayId
    }

    /**
     * 更新地图显示面；**display 已失效时自愈**（2026-10-31）。
     *
     * 冷启动/全屏切换的竞态里，shell 侧可能已经释放了这块 display，
     * 此时 updateDisplaySurface 返回 "ERR display=N missing"，
     * 之前会直接把"地图暂不可用"抛给用户。现在把它当成"需要重建"：
     * 清掉本地句柄（displayId = -1），下一次 resumePreview 就会重新建屏。
     */
    private suspend fun updateSurfaceOrHeal(id: Int, output: android.view.Surface, w: Int, h: Int): String {
        val r = SpikeShell.updateDisplaySurface(id, output, w, h, MAP_DPI)
        if (r.startsWith("OK")) {
            // 成功后必须清掉旧错误文案：否则一次失败会把"地图暂不可用：ERR …"永久留在卡片上，
            // 而地图其实已经正常出图了（2026-10-31 现场）。
            if (_status.value.isNotEmpty()) _status.value = ""
            return r
        }
        if (r.contains("missing")) {
            HyperCarRepo.log("CARUI-PREVIEW: display $id 已失效（$r）→ 清句柄，下一轮重建")
            runCatching { SpikeShell.releaseDisplay(id) }
            displayId = -1
            carMode = false
        }
        return r
    }
    /** UcarMapActivity is singleTask; layout-only intents retain the current navigation. */
    private suspend fun launchCarMap(mode: Int, splash: Boolean): io.github.hypercar.shizuku.ExecResult {
        val provider = activeProvider ?: error("地图任务不存在")
        return ShizukuTools.exec(provider.carLaunchCommand(displayId, mode, splash), 12000)
    }

    private suspend fun releaseMapTask() {
        if (displayId > 0) SpikeShell.releaseDisplay(displayId)
        displayId = -1
        carMode = false
        activeProvider?.let { provider ->
            ShizukuTools.exec("am force-stop ${provider.packageName}", 5000)
            MapCompat.disable(provider.packageName)
        }
        activeProvider = null
    }

    private suspend fun failed(error: Throwable) {
        runCatching { releaseMapTask() }
        // **display 失效不算"地图不可用"**（2026-10-31）：调整尺寸/切全屏时旧 display 被释放
        // 是正常现象，下一轮 resumePreview 会自动重建。这类错误只记日志、不在卡片上打扰用户，
        // 否则会出现"地图明明已经出图，中间却盖着 ERR display=133 missing"。
        val msg = error.message.orEmpty()
        if (msg.contains("missing")) {
            HyperCarRepo.log("CARUI-PREVIEW: 忽略 display 失效（自愈中）：$msg")
            return
        }
        _status.value = "地图暂不可用：${msg.take(100)}"
        HyperCarRepo.log("CARUI-PREVIEW: ${error.message}")
    }
}

@Composable
fun MapPreviewView(modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        MapPreview.init(context)
        TextureView(context).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    MapPreview.attach(texture, width, height)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    MapPreview.attach(texture, width, height)
                }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    MapPreview.detach(texture)
                    return false
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    })
}
