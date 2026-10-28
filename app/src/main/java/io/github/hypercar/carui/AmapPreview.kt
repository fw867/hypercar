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

/** One persistent Amap task, responsive display bounds, and a constant density across card/fullscreen. */
object AmapPreview {
    private const val PKG = "com.autonavi.minimap"
    private const val MAP_WIDTH = 1920
    private const val MAP_HEIGHT = 720
    private const val MAP_DPI = 240
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow("正在打开高德地图…")
    val status: StateFlow<String> = _status
    private var displayId = -1
    private var cardSurface: Surface? = null
    private var target: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var fullscreen = false
    private var stopped = false

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
                    runCatching { route(cardSurface!!, w, h, 160) }.onFailure { failed(it) }
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
        val id = route(surface, w, h, dpi)
        fullscreen = true
        id
    }

    suspend fun resumePreview() = lock.withLock {
        stopped = false
        fullscreen = false
        val output = cardSurface
        if (output != null && output.isValid) {
            runCatching { route(output, width, height, 160) }.onFailure { failed(it) }
        } else if (displayId > 0) {
            SpikeShell.updateDisplaySurface(displayId, null, 0, 0, 0)
        }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                stopped = true
                fullscreen = false
                val hadMap = displayId > 0
                if (hadMap) SpikeShell.releaseDisplay(displayId)
                displayId = -1
                // The TextureView owns its SurfaceTexture until its destruction callback.
                cardSurface?.release()
                cardSurface = null
                target = null
                if (hadMap) {
                    ShizukuTools.exec("am force-stop $PKG", 5000)
                    MapCompat.disable(PKG)
                }
            }
        }
    }

    private suspend fun route(output: Surface, w: Int, h: Int, dpi: Int): Int {
        if (displayId > 0) {
            // The installed Amap handles screen-size changes but not CONFIG_DENSITY.
            // Changing 160/240dpi recreates its activity; keep 240dpi when resizing the bounds.
            val update = SpikeShell.updateDisplaySurface(displayId, output, w, h, MAP_DPI)
            check(update.startsWith("OK")) { update }
            _status.value = ""
            HyperCarRepo.log("CARUI-PREVIEW: 保留地图任务 $update")
            return displayId
        }
        _status.value = "正在打开高德地图…"
        val compat = MapCompat.enable(PKG)
        HyperCarRepo.log("CARUI-PREVIEW: $compat")
        // Only the first launch of the driving session opens cruise. Surface switches never relaunch Amap.
        val result = SpikeShell.createDisplay(output, MAP_WIDTH, MAP_HEIGHT, MAP_DPI, 0, "hypercar-map")
        displayId = Regex("id=(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        check(displayId > 0) { "无法创建地图窗口: $result" }
        val uri = "amapuri://drive/CarCruisePage?sourceApplication=io.github.hypercar&clearStack=1"
        val launch = ShizukuTools.exec("am start --activity-clear-task " +
            "-n $PKG/com.autonavi.map.activity.SplashActivity --display $displayId", 12000)
        if (!launch.ok || launch.err.contains("Error")) {
            SpikeShell.releaseDisplay(displayId)
            displayId = -1
            error("高德启动失败: ${launch.err}")
        }
        // Keep the activity's launch intent as the normal launcher. Putting cruise in that
        // intent causes configuration recreation to replay cruise over an active navigation.
        delay(1800)
        val cruise = ShizukuTools.exec("am start -a android.intent.action.VIEW -d '$uri' " +
            "-p $PKG --display $displayId", 12000)
        check(cruise.ok) { "无法打开巡航: ${cruise.err}" }
        HyperCarRepo.log("CARUI-PREVIEW: 初次巡航 display=$displayId ${MAP_WIDTH}x$MAP_HEIGHT ${launch.out.take(300)}")
        _status.value = ""
        return displayId
    }

    private fun failed(error: Throwable) {
        _status.value = "地图暂不可用，点击重试全屏"
        HyperCarRepo.log("CARUI-PREVIEW: ${error.message}")
    }
}

@Composable
fun AmapPreviewView(modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        TextureView(context).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    AmapPreview.attach(texture, width, height)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    AmapPreview.attach(texture, width, height)
                }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    AmapPreview.detach(texture)
                    return false
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    })
}
