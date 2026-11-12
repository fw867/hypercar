package io.github.hypercar.carui

import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.view.MotionEvent
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One persistent map task, native preview/full layouts and constant density across reconnects. */
object MapPreview {
    private var appContext: android.content.Context? = null
    private var activeProvider: MapProvider? = null
    fun init(context: android.content.Context) { appContext = context.applicationContext }
    private var mapDpi = 240
    private var compatProvider: MapProvider? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow("正在打开地图…")
    val status: StateFlow<String> = _status
    private var displayId = -1
    private var cardSurface: Surface? = null
    private var width = 0
    private var height = 0
    private var target: SurfaceTexture? = null
    private var fullscreen = false
    private var stopped = true
    private var carMode = false
    private var presentation: MapPresentation? = null
    private val cardInput = MapCardInputState()
    private data class CardTouch(val binding: MapCardInputState.Binding, val event: MotionEvent)
    private val cardTouches = Channel<CardTouch>(Channel.UNLIMITED)
    private val queuedTouches = java.util.concurrent.atomic.AtomicInteger()
    private var injectedTouch: CardTouch? = null
    private var touchLogs = 0

    init {
        // One reader preserves DOWN/MOVE/UP ordering; the same mutex serializes input with
        // display resize, fullscreen handoff and disconnect, all off the UI thread.
        scope.launch {
            for (packet in cardTouches) {
                try {
                    lock.withLock {
                        if (!cardInput.accepts(packet.binding)) return@withLock
                        val action = packet.event.actionMasked
                        if (action == MotionEvent.ACTION_DOWN) cancelInjectedTouch()
                        else if (injectedTouch?.binding !== packet.binding) return@withLock
                        val result = SpikeShell.touchEvent(packet.binding.displayId, packet.event)
                        if (result.startsWith("OK")) {
                            injectedTouch?.event?.recycle()
                            injectedTouch = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                                null else CardTouch(packet.binding, MotionEvent.obtainNoHistory(packet.event))
                            if (action == MotionEvent.ACTION_DOWN && touchLogs++ < 6) {
                                HyperCarRepo.log("MAP-CARD-TOUCH: DOWN display=${packet.binding.displayId} local=(${packet.event.x.toInt()},${packet.event.y.toInt()}) canvas=${packet.binding.width}x${packet.binding.height}")
                            }
                        } else {
                            cancelInjectedTouch()
                            if (touchLogs++ < 12) HyperCarRepo.log("MAP-CARD-TOUCH: 注入失败 $result")
                        }
                    }
                } catch (error: Exception) {
                    HyperCarRepo.log("MAP-CARD-TOUCH: ${error.message}")
                } finally {
                    packet.event.recycle()
                    queuedTouches.decrementAndGet()
                }
            }
        }
    }

    internal fun acquireCardTouch(texture: SurfaceTexture): MapCardInputState.Binding? = cardInput.acquire(texture)

    internal fun forwardCardTouch(binding: MapCardInputState.Binding, event: MotionEvent, w: Int, h: Int) {
        if (!cardInput.accepts(binding) || w <= 0 || h <= 0) return
        // Skip excess intermediate moves if the shell is briefly slow; never drop gesture edges.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && queuedTouches.get() >= 32) return
        val copy = MotionEvent.obtainNoHistory(event)
        val (sx, sy) = binding.scaleFrom(w, h)
        copy.transform(Matrix().apply { setScale(sx, sy) })
        queuedTouches.incrementAndGet()
        if (!cardTouches.trySend(CardTouch(binding, copy)).isSuccess) {
            queuedTouches.decrementAndGet()
            copy.recycle()
        }
    }

    /** Called under lock before the target changes, retaining CANCEL rather than converting it to UP. */
    private fun cancelInjectedTouch() {
        val active = injectedTouch ?: return
        injectedTouch = null
        try {
            active.event.action = MotionEvent.ACTION_CANCEL
            SpikeShell.touchEvent(active.binding.displayId, active.event)
        } finally { active.event.recycle() }
    }

    private fun clearCardInput() {
        cardInput.clear()
        runCatching { cancelInjectedTouch() }
            .onFailure { HyperCarRepo.log("MAP-CARD-TOUCH: 取消手势失败 ${it.message}") }
    }

    private fun bindCardInput(nextPresentation: MapPresentation, w: Int, h: Int) {
        val texture = target
        if (nextPresentation == MapPresentation.CARD && texture != null && !stopped && !fullscreen) {
            cardInput.bind(texture, displayId, w, h)
        }
    }
    private fun displayName(provider: MapProvider) = "hypercar-map-${provider.id}"

    /** Called under the projection lifecycle lock, before creating the new home display. */
    suspend fun prepareProjection() = lock.withLock {
        clearCardInput()
        val provider = appContext?.let { MapApps.selected(it) }
        if (activeProvider != null && activeProvider != provider) releaseMapTask()
        val result = SpikeShell.prepareProjection(provider?.let(::displayName).orEmpty())
        check(result.startsWith("OK")) { result }
        val retainedId = Regex("id=(-?\\d+)").find(result)?.groupValues?.get(1)?.toInt() ?: -1
        if (retainedId != displayId) {
            presentation = null
            carMode = retainedId > 0
        }
        displayId = retainedId
        activeProvider = provider.takeIf { displayId > 0 }
        mapDpi = Regex("dpi=(\\d+)").find(result)?.groupValues?.get(1)?.toInt() ?: CarUiCaster.DPI
        HyperCarRepo.log("CARUI-PREVIEW: 重连复用检查 $result presentation=$presentation")
    }

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
                    runCatching { route(cardSurface!!, w, h, MapPresentation.CARD) }.onFailure { failed(it) }
                }
                previous?.release()
            }
        }
    }

    fun detach(texture: SurfaceTexture) {
        scope.launch {
            lock.withLock {
                if (target === texture) {
                    clearCardInput()
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

    suspend fun showFullscreen(surface: Surface, w: Int, h: Int): Int = lock.withLock {
        check(!stopped) { "地图会话已停止" }
        clearCardInput()
        fullscreen = true
        val id = route(surface, w, h, MapPresentation.FULLSCREEN)
        id
    }

    suspend fun resumePreview() = lock.withLock {
        stopped = false
        fullscreen = false
        val output = cardSurface
        if (output != null && output.isValid) {
            runCatching { route(output, width, height, MapPresentation.CARD) }.onFailure { failed(it) }
        } else if (displayId > 0) {
            SpikeShell.updateDisplaySurface(displayId, null, 0, 0, 0)
        }
    }

    suspend fun suspendProjection() = lock.withLock {
        clearCardInput()
        stopped = true
        fullscreen = false
        try {
            if (displayId > 0) {
                val result = SpikeShell.updateDisplaySurface(displayId, null, 0, 0, 0)
                HyperCarRepo.log("CARUI-PREVIEW: 断开投屏，保留地图/导航 display=$displayId $result")
            }
        } finally {
            MapDiagnostics.stop()
            io.github.hypercar.navigation.AmapLinkProbe.stop()
            // The TextureView owns its SurfaceTexture until its destruction callback.
            cardSurface?.release()
            cardSurface = null
            target = null
        }
    }

    suspend fun release() = lock.withLock {
        stopped = true
        fullscreen = false
        releaseMapTask()
    }

    private suspend fun route(output: Surface, w: Int, h: Int, nextPresentation: MapPresentation): Int {
        clearCardInput()
        val context = appContext ?: error("地图未初始化")
        val provider = MapApps.selected(context) ?: error("请在手机安装高德地图或百度地图")
        if (activeProvider != null && activeProvider != provider) releaseMapTask()
        activeProvider = provider
        HyperCarRepo.log("MAP-SCENE: request provider=${provider.id} display=$displayId from=$presentation to=$nextPresentation size=${w}x$h carMode=$carMode fullscreen=$fullscreen")
        if (displayId > 0) {
            val update = SpikeShell.updateDisplaySurface(displayId, output, w, h, mapDpi)
            if (update.contains("missing")) {
                HyperCarRepo.log("CARUI-PREVIEW: 地图屏失效，重建输出且不强停地图 $update")
                displayId = -1
                presentation = null
            } else {
                check(update.startsWith("OK")) { update }
                if (carMode && provider == MapProvider.AMAP && presentation != nextPresentation) {
                    val layout = ShizukuTools.exec(provider.carLaunchCommand(displayId, nextPresentation, false), 12000)
                    check(layout.ok && !layout.err.contains("Error") && !layout.out.contains("Error")) {
                        "地图布局切换失败: ${layout.err}"
                    }
                }
                presentation = nextPresentation
                bindCardInput(nextPresentation, w, h)
                _status.value = ""
                MapDiagnostics.start(context, provider, displayId, nextPresentation, carMode)
                runCatching { io.github.hypercar.navigation.AmapLinkProbe.start(context, mapStarted = true) }
                HyperCarRepo.log("CARUI-PREVIEW: 复用地图任务 $update presentation=$presentation")
                return displayId
            }
        }
        _status.value = "正在打开${provider.label}…"
        if (!SpikeShell.isConnected()) {
            val binding = SpikeShell.connect(context)
            check(SpikeShell.isConnected()) { binding }
        }
        // Auto-cruise belongs to the map app's own projection settings.
        val result = SpikeShell.createDisplay(output, w, h, mapDpi, 0, displayName(provider))
        displayId = Regex("id=(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        check(displayId > 0) { "无法创建地图窗口: $result" }
        val carLaunch = ShizukuTools.exec(provider.carLaunchCommand(displayId, nextPresentation, true), 12000)
        carMode = carLaunch.ok && !carLaunch.err.contains("Error") && !carLaunch.out.contains("Error")
        presentation = nextPresentation
        if (!carMode) {
            check(provider == MapProvider.AMAP) { "${provider.label}车联入口不可用，请检查地图版本或首次授权：${carLaunch.err}" }
            HyperCarRepo.log("CARUI-PREVIEW: 车联入口不可用，回退手机地图 ${carLaunch.err}")
            // Native Ucar activities already support landscape. Compat changes kill the app,
            // so only the portrait-locked phone fallback needs them, once before its launch.
            if (compatProvider != provider) {
                compatProvider = provider
                HyperCarRepo.log("CARUI-PREVIEW: ${MapCompat.enable(provider.packageName)}")
            }
            val launch = ShizukuTools.exec("am start --activity-clear-task " +
                "-n ${provider.packageName}/com.autonavi.map.activity.SplashActivity --display $displayId", 12000)
            check(launch.ok && !launch.err.contains("Error")) { "${provider.label}启动失败: ${launch.err}" }
        }
        MapDiagnostics.start(context, provider, displayId, nextPresentation, carMode)
        runCatching { io.github.hypercar.navigation.AmapLinkProbe.start(context, mapStarted = true) }
        bindCardInput(nextPresentation, w, h)
        HyperCarRepo.log("CARUI-PREVIEW: ${provider.label}初次启动 display=$displayId carMode=$carMode ${w}x$h dpi=$mapDpi presentation=$presentation")
        HyperCarRepo.log("CARUI-PREVIEW: 自动巡航跟随${provider.label}原生设置")
        _status.value = ""
        return displayId
    }

    private suspend fun releaseMapTask(terminateProvider: Boolean = true) {
        clearCardInput()
        MapDiagnostics.stop()
        io.github.hypercar.navigation.AmapLinkProbe.stop()
        if (displayId > 0) SpikeShell.releaseDisplay(displayId)
        displayId = -1
        carMode = false
        presentation = null
        if (terminateProvider) {
            activeProvider?.let { provider ->
                ShizukuTools.exec("am force-stop ${provider.packageName}", 5000)
            }
            compatProvider?.let { MapCompat.disable(it.packageName) }
            compatProvider = null
        }
        activeProvider = null
    }

    private suspend fun failed(error: Throwable) {
        if (error is CancellationException) throw error
        // A projection failure does not authorize ending the user's navigation.
        runCatching { releaseMapTask(terminateProvider = false) }
        val msg = error.message.orEmpty()
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
            var gesture: MapCardInputState.Binding? = null
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    gesture = surfaceTexture?.let { MapPreview.acquireCardTouch(it) }
                }
                val binding = gesture ?: return@setOnTouchListener false
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MapPreview.forwardCardTouch(binding, event, view.width, view.height)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    gesture = null
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
                true
            }
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
