package io.github.hypercar.carui

import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
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

/** One persistent map task. Projection controls never change the source density. */
object MapPreview {
    private var appContext: android.content.Context? = null
    private var activeProvider: MapProvider? = null
    fun init(context: android.content.Context) { appContext = context.applicationContext }
    private var compatProvider: MapProvider? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow("正在打开地图…")
    val status: StateFlow<String> = _status
    private var displayId = -1
    private var cardSurface: Surface? = null
    private var width = 0
    private var height = 0
    private class CardTarget(val texture: SurfaceTexture)
    @Volatile private var visibleTarget: CardTarget? = null
    @Volatile private var target: CardTarget? = null
    private var fullscreen = false
    private var stopped = true
    private var carMode = false
    private var presentation: MapPresentation? = null
    private val cardInput = MapCardInputState()
    @Volatile private var clusterBinding: MapCardInputState.Binding? = null

    /** Encoder thread. A resized/detached card invalidates an in-flight read immediately. */
    internal fun clusterMap(w: Int, h: Int): android.graphics.Bitmap? {
        val card = visibleTarget ?: return null
        val binding = clusterBinding ?: return null
        fun active() = card === visibleTarget && card === target && binding.source === card.texture &&
            clusterBinding === binding && cardInput.accepts(binding)
        if (!active()) return null
        val image = SpikeShell.captureInstrumentMap(binding.displayId, binding.width, binding.height, w, h) ?: return null
        if (active()) return image
        image.recycle()
        return null
    }
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
                        if (CarReceiverUi.state.value.limits.blockMapApp) {
                            cancelInjectedTouch()
                            return@withLock
                        }
                        val action = packet.event.actionMasked
                        if (action == MotionEvent.ACTION_DOWN) cancelInjectedTouch()
                        else if (injectedTouch?.binding !== packet.binding) return@withLock
                        val result = SpikeShell.touchEvent(packet.binding.displayId, packet.event)
                        if (result.startsWith("OK")) {
                            injectedTouch?.event?.recycle()
                            val remaining = when (action) {
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> null
                                MotionEvent.ACTION_POINTER_UP -> {
                                    val (width, height) = packet.binding.width to packet.binding.height
                                    CarTouchFrame.fromView(packet.event, width, height, width, height)
                                        ?.cancelAfter()?.obtain(packet.event.downTime, packet.event.eventTime)
                                }
                                else -> MotionEvent.obtainNoHistory(packet.event)
                            }
                            injectedTouch = remaining?.let { CardTouch(packet.binding, it) }
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

    internal fun cancelCardGesture() {
        scope.launch { lock.withLock { cancelInjectedTouch() } }
    }

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
        clusterBinding = null
        cardInput.clear()
        runCatching { cancelInjectedTouch() }
            .onFailure { HyperCarRepo.log("MAP-CARD-TOUCH: 取消手势失败 ${it.message}") }
    }

    private fun bindCardInput(nextPresentation: MapPresentation, w: Int, h: Int) {
        val texture = target?.texture
        if (nextPresentation == MapPresentation.CARD && texture != null && !stopped && !fullscreen) {
            cardInput.bind(texture, displayId, w, h)
            clusterBinding = cardInput.current.takeIf { carMode && activeProvider == MapProvider.AMAP }
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
        HyperCarRepo.log("CARUI-PREVIEW: 重连复用检查 $result presentation=$presentation")
    }

    fun attach(texture: SurfaceTexture, w: Int, h: Int) {
        val card = CardTarget(texture)
        visibleTarget = card
        scope.launch {
            lock.withLock {
                if (visibleTarget !== card) return@withLock
                val previous = cardSurface
                clearCardInput()
                target = card
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
        if (visibleTarget?.texture === texture) visibleTarget = null
        scope.launch {
            lock.withLock {
                if (target?.texture === texture) {
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

    internal suspend fun showFullscreen(surface: Surface, w: Int, h: Int): Int = lock.withLock {
        check(!stopped) { "地图会话已停止" }
        clearCardInput()
        fullscreen = true
        route(surface, w, h, MapPresentation.FULLSCREEN)
    }

    internal suspend fun supportsLayoutReset(id: Int) = lock.withLock { canResetLayout(id) }

    private fun canResetLayout(id: Int) = id > 0 && displayId == id && !stopped && fullscreen &&
        carMode && activeProvider == MapProvider.AMAP

    private suspend fun applyAmapLayout(id: Int, layout: MapPresentation, timeoutMs: Long = 4000): Boolean {
        val result = ShizukuTools.exec(MapProvider.AMAP.carLaunchCommand(id, layout, false), timeoutMs)
        return result.ok && !result.err.contains("Error") && !result.out.contains("Error")
    }

    internal suspend fun resetFullscreenLayout(id: Int): String = lock.withLock {
        if (!canResetLayout(id)) return@withLock "已跳过：地图输出已切换"
        val snapshot = ShizukuTools.exec(
            "dumpsys activity activities | grep -E '^(Display #|[[:space:]]*(topResumedActivity=|mResumedActivity:))'", 4000)
        if (!snapshot.ok || !hasResumedUcarActivity(snapshot.out, id)) return@withLock "已跳过：未确认当前高德车联窗口"

        // UCar only notifies layout listeners when its mode changes. Keep the encoder, display
        // metrics and map task throughout. Restore full mode even if preview delivery was ambiguous.
        val previewApplied = applyAmapLayout(id, MapPresentation.CARD)
        val restored = applyAmapLayout(id, MapPresentation.FULLSCREEN) || applyAmapLayout(id, MapPresentation.FULLSCREEN)
        presentation = if (restored) MapPresentation.FULLSCREEN else null
        "高德原生布局 preview=$previewApplied fullscreen=$restored"
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

    private suspend fun route(output: Surface, w: Int, h: Int, nextPresentation: MapPresentation, mapDpi: Int = CarUiCaster.DPI): Int {
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
                    check(applyAmapLayout(displayId, nextPresentation, 12000)) { "地图布局切换失败" }
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
fun MapPreviewView(modifier: Modifier = Modifier, interactive: Boolean = true) {
    AndroidView(modifier = modifier, factory = { context ->
        MapPreview.init(context)
        TextureView(context).apply {
            isOpaque = false
            val config = ViewConfiguration.get(context)
            val tap = MapCardTap(config.scaledTouchSlop, ViewConfiguration.getLongPressTimeout())
            var gesture: MapCardInputState.Binding? = null
            var firstDown: MotionEvent? = null
            var pendingDown: MotionEvent? = null
            var pendingUp: MotionEvent? = null
            var pendingBinding: MapCardInputState.Binding? = null
            var doubleTap = false
            fun clearPending() {
                pendingDown?.recycle()
                pendingUp?.recycle()
                pendingDown = null
                pendingUp = null
                pendingBinding = null
            }
            setOnClickListener { if (!CarReceiverUi.state.value.limits.blockMapApp) MapController.requestEnter() }
            contentDescription = "全屏地图"
            val confirmTap = Runnable {
                val binding = pendingBinding
                clearPending()
                if (binding != null && surfaceTexture?.let(MapPreview::acquireCardTouch) === binding) performClick()
            }
            setOnTouchListener { view, event ->
                val action = event.actionMasked
                if (action == MotionEvent.ACTION_DOWN) {
                    gesture = surfaceTexture?.let(MapPreview::acquireCardTouch)
                    tap.down(event.x, event.y)
                    firstDown?.recycle()
                    firstDown = MotionEvent.obtainNoHistory(event)
                    val up = pendingUp
                    val down = pendingDown
                    val binding = gesture
                    val dx = event.x - (down?.x ?: event.x)
                    val dy = event.y - (down?.y ?: event.y)
                    val radius = config.scaledDoubleTapSlop.toFloat()
                    doubleTap = binding != null && pendingBinding === binding && up != null &&
                        event.eventTime - up.eventTime in 0..ViewConfiguration.getDoubleTapTimeout().toLong() &&
                        dx * dx + dy * dy <= radius * radius
                    if (doubleTap && binding != null && down != null && up != null) {
                        // Replay the withheld first tap with its original times before the second DOWN.
                        removeCallbacks(confirmTap)
                        MapPreview.forwardCardTouch(binding, down, width, height)
                        MapPreview.forwardCardTouch(binding, up, width, height)
                        clearPending()
                    } else if (up != null) {
                        removeCallbacks(confirmTap)
                        confirmTap.run()
                    }
                }
                val binding = gesture ?: return@setOnTouchListener false
                if (action == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                tap.observe(event.x, event.y, event.pointerCount)
                if (action == MotionEvent.ACTION_UP && !doubleTap && tap.isTap(event.eventTime - event.downTime)) {
                    // The native view saw DOWN for responsive drag/long press. Never select a POI on a card click.
                    val cancel = MotionEvent.obtainNoHistory(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    MapPreview.forwardCardTouch(binding, cancel, width, height)
                    cancel.recycle()
                    pendingDown = firstDown
                    firstDown = null
                    pendingUp = MotionEvent.obtainNoHistory(event)
                    pendingBinding = binding
                    postDelayed(confirmTap, ViewConfiguration.getDoubleTapTimeout().toLong())
                } else {
                    MapPreview.forwardCardTouch(binding, event, width, height)
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    firstDown?.recycle()
                    firstDown = null
                    gesture = null
                    view.parent?.requestDisallowInterceptTouchEvent(false)
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
                    removeCallbacks(confirmTap)
                    clearPending()
                    firstDown?.recycle()
                    firstDown = null
                    gesture = null
                    MapPreview.detach(texture)
                    return false
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    }, update = { it.isEnabled = interactive })
}
