package io.github.hypercar.carui

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeDisplay
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One application task can move between a full-sized pane and the home card. */
internal class MediaCardProjection(context: Context, private val packageName: String, private val dialer: String?) {
    private val app = context.applicationContext
    val size = PhoneProjectionSize.read(context)
    val error = MutableStateFlow<String?>(null)
    val expandedWindowReady = MutableStateFlow(false)
    val stabilizeExpandedWindow = ShortVideoApps.isSupported(packageName)

    private val input = ProjectionSurfaceInput<Request> { id, frame, downTime ->
        val event = frame.obtain(downTime, SystemClock.uptimeMillis())
        try {
            val target = display?.takeIf { it.id == id }
            runCatching { target?.owner?.touchEvent(id, event)?.startsWith("OK") == true }.getOrDefault(false)
        } finally { event.recycle() }
    }
    private val desired get() = input.desired
    private enum class CloseReason { UserDismissed, ViewDisposed }
    @Volatile private var closeReason: CloseReason? = null
    private val closed get() = closeReason != null
    private val endProjectedTask get() = closeReason == CloseReason.UserDismissed && stabilizeExpandedWindow
    @Volatile private var attachment: Attachment? = null
    @Volatile private var display: SpikeShell.MediaCardDisplay? = null
    private val displayId get() = display?.id ?: -1
    private val changes = Mutex()
    private var displayLease: MediaCardHandoffs.Lease? = null
    private var tasksBeforeLaunch: List<MediaTaskWindow.Task>? = null
    private var window = MediaCardWindowState()

    private data class Request(
        val texture: SurfaceTexture, val size: PhoneProjectionSize, val expanded: Boolean,
        val viewWidth: Int, val viewHeight: Int,
    )
    private data class Attachment(val request: Request, val surface: Surface, val displayId: Int)

    fun show(texture: SurfaceTexture, viewWidth: Int, viewHeight: Int, expanded: Boolean) {
        if (closed || viewWidth <= 0 || viewHeight <= 0) return
        val viewport = if (expanded) size.expanded(viewWidth, viewHeight) else size
        val request = Request(texture, viewport, expanded && viewport.width > viewport.height, viewWidth, viewHeight)
        if (!input.replace(request)) return
        expandedWindowReady.value = false
        texture.setDefaultBufferSize(viewport.width, viewport.height)
        error.value = null
        scope.launch {
            changes.withLock {
                if (!isCurrent(request)) return@withLock
                val surface = runCatching { Surface(texture) }.getOrNull()
                if (surface == null) {
                    if (isCurrent(request)) error.value = "无法创建应用画面"
                    return@withLock
                }
                var attached = false
                try {
                    if (display?.owner?.asBinder()?.isBinderAlive == false) releaseDisplay()
                    if (displayId > 0 && !request.expanded && !window.collapse(displayId)) {
                        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId rebuilding for home card")
                        releaseDisplay()
                    }
                    if (!isCurrent(request)) return@withLock
                    val target = display
                    if (target != null) {
                        if (stabilizeExpandedWindow && request.expanded && window.shortVideo == null) prepareExpandedShortVideo()
                        if (!isCurrent(request)) return@withLock
                        val updated = runCatching { target.owner.updateDisplaySurface(
                            target.id, surface, viewport.width, viewport.height, viewport.dpi,
                        ).startsWith("OK") }.getOrDefault(false)
                        if (updated && applyWindowMode(request)) {
                            attachment?.surface?.release()
                            attachment = Attachment(request, surface, displayId)
                            attached = true
                            markWindowReady(request)
                            HyperCarRepo.log("VIDEO-CARD: package=$packageName surface reattached display=$displayId size=${viewport.width}x${viewport.height} dpi=${viewport.dpi}")
                            return@withLock
                        }
                        releaseDisplay()
                    }
                    if (isCurrent(request)) attached = open(request, surface)
                } finally {
                    if (!attached) surface.release()
                }
            }
        }
    }

    private suspend fun open(request: Request, surface: Surface): Boolean {
        while (isCurrent(request)) {
            val lease = handoffs.tryAcquire(packageName)
            if (lease != null) {
                try {
                    return open(request, surface, lease)
                } finally {
                    // Failures before display creation have no owner or cleanup to wait for.
                    if (lease.ownerAlive == null) handoffs.finish(packageName, lease)
                }
            }
            delay(250)
        }
        return false
    }

    private suspend fun open(request: Request, surface: Surface, lease: MediaCardHandoffs.Lease): Boolean {
        val viewport = request.size
        val component = runCatching { ProjectionApps.launcherComponent(app, packageName) }.getOrNull()
        if (component == null) {
            if (isCurrent(request)) error.value = "应用当前无法启动"
            return false
        }
        SpikeShell.connect(app)
        if (!isCurrent(request)) return false
        val previous = runCatching { MediaTaskWindow.snapshot() }.getOrNull() ?: run {
            if (isCurrent(request)) error.value = "无法读取应用任务，请检查 Shizuku"
            return false
        }
        if (!isCurrent(request)) return false
        // Leave Android's task migration enabled if the display owner dies or cleanup cannot run.
        val created = SpikeShell.createMediaCardDisplay(surface, viewport.width, viewport.height, viewport.dpi).getOrElse {
            if (isCurrent(request)) error.value = "无法创建应用画面，请检查 Shizuku"
            HyperCarRepo.log("VIDEO-CARD: display failed package=$packageName result=${it.message?.take(120)}")
            return false
        }
        val id = created.id
        lease.ownerAlive = { created.owner.asBinder().isBinderAlive }
        lease.retireIfHidden = {
            if (!closed && desired == null && changes.tryLock()) {
                scope.launch {
                    try {
                        if (desired == null && displayLease === lease) releaseDisplay()
                    } finally { changes.unlock() }
                }
            }
        }
        displayLease = lease
        display = created
        tasksBeforeLaunch = previous
        var launched = false
        try {
            if (!isCurrent(request)) return false
            if (stabilizeExpandedWindow && request.expanded) prepareExpandedShortVideo()
            if (!isCurrent(request)) return false
            val result = SpikeDisplay.amStartLauncherOnDisplay(id, component.flattenToShortString())
            var windowUsable = true
            val confirmed = result.ok && MediaTaskWindow.confirmLaunch(id, packageName, stillOpen = { !closed }) {
                windowUsable = applyWindowMode(request)
            }
            launched = confirmed && windowUsable
            if (launched) {
                attachment = Attachment(request, surface, id)
                markWindowReady(request)
            } else if (isCurrent(request)) {
                error.value = if (result.ok) "应用未能在投屏中保持打开" else "应用未能在卡片中打开"
            }
            HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$id size=${viewport.width}x${viewport.height} dpi=${viewport.dpi} ready=$launched result=${result.detail.take(300)}")
            return launched
        } finally {
            if (!launched) releaseDisplay()
        }
    }

    fun hide(texture: SurfaceTexture) {
        if (desired?.texture === texture) {
            input.replace(null)
            expandedWindowReady.value = false
        }
        scope.launch {
            changes.withLock {
                val old = attachment?.takeIf { it.request.texture === texture } ?: return@withLock
                if (desired?.texture === texture) return@withLock
                runCatching { display?.owner?.updateDisplaySurface(old.displayId, null, 0, 0, 0) }
                attachment = null
                old.surface.release()
            }
        }
    }

    fun dismiss() = close(CloseReason.UserDismissed)

    fun dispose() = close(CloseReason.ViewDisposed)

    private fun close(reason: CloseReason) {
        if (closed) return
        closeReason = reason
        input.replace(null)
        expandedWindowReady.value = false
        scope.launch { changes.withLock { releaseDisplay() } }
    }

    fun touch(texture: SurfaceTexture, event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        val display = attachment?.takeIf { isCurrent(it.request) && it.request.texture === texture } ?: return
        if (!allowsInteraction(CarReceiverUi.state.value.limits)) {
            cancelTouch()
            return
        }
        val viewport = display.request.size
        val frame = CarTouchFrame.fromView(event, viewWidth, viewHeight, viewport.width, viewport.height) ?: return
        input.touch(display.request, display.displayId, frame, event.downTime)
    }

    fun allowsInteraction(limits: UiLimits) = limits.allowsProjection(packageName, dialer)

    fun cancelTouch() = input.cancel()

    private fun isCurrent(request: Request) = !closed && desired === request

    private suspend fun markWindowReady(request: Request) {
        if (!stabilizeExpandedWindow || !request.expanded) return
        withContext(Dispatchers.Main.immediate) {
            if (isCurrent(request)) expandedWindowReady.value = true
        }
    }

    private suspend fun prepareExpandedShortVideo() {
        val prepared = MediaTaskWindow.prepareExpandedShortVideo(displayId)
        window.shortVideo = prepared
        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId shortVideoOrientationLocked=${prepared.ready}")
    }

    private suspend fun applyWindowMode(request: Request): Boolean {
        if (!isCurrent(request) || !request.expanded) return true
        if (stabilizeExpandedWindow && window.shortVideo?.ready != true) return true
        val viewport = request.size
        window.needsFullscreenReset = true
        val expanded = runCatching {
            MediaTaskWindow.expand(displayId, packageName, viewport.width, viewport.height)
        }.getOrDefault(false)
        val usable = expanded || window.resetFullscreen(displayId)
        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId expandedWindow=$expanded")
        return usable || !stabilizeExpandedWindow
    }

    private suspend fun releaseDisplay() {
        val target = display ?: return
        val lease = checkNotNull(displayLease)
        lease.retireIfHidden = null
        val previous = tasksBeforeLaunch
        val fullscreen = window.needsFullscreenReset
        val closeTasks = endProjectedTask
        val orientation = window.shortVideo?.restoreIgnoreOrientation?.takeUnless { closeTasks }
        val expectedDestinations = mutableMapOf<Int, Int?>()
        val pkg = packageName
        val cleanup = MediaCardRelease(
            ownerAlive = { target.owner.asBinder().isBinderAlive },
            restore = {
                MediaTaskWindow.restoreBeforeRelease(target.id, pkg, previous, fullscreen, orientation, closeTasks, expectedDestinations) { command ->
                    ShizukuTools.exec(command, 2_000) { target.owner.asBinder().isBinderAlive }
                }
            },
            release = { target.owner.releaseDisplayById(target.id).startsWith("OK") },
        )
        display = null
        displayLease = null
        tasksBeforeLaunch = null
        window = MediaCardWindowState()
        val surface = attachment?.surface
        attachment = null
        try {
            if (cleanup.attempt()) {
                handoffs.finish(pkg, lease)
            } else {
                HyperCarRepo.log("VIDEO-CARD: package=$pkg display=${target.id} cleanup pending")
                scope.launch {
                    var retryMs = 500L
                    do {
                        delay(retryMs)
                        retryMs = (retryMs * 2).coerceAtMost(10_000L)
                    } while (!cleanup.attempt())
                    handoffs.finish(pkg, lease)
                    HyperCarRepo.log("VIDEO-CARD: package=$pkg display=${target.id} cleanup finished")
                }
            }
        } finally { surface?.release() }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val handoffs = MediaCardHandoffs()
    }
}

/** A package's next launch may snapshot tasks once the original display is released or its owner dies. */
internal class MediaCardHandoffs {
    class Lease {
        @Volatile var ownerAlive: (() -> Boolean)? = null
        @Volatile var retireIfHidden: (() -> Unit)? = null
    }
    private val leases = mutableMapOf<String, Lease>()

    @Synchronized fun tryAcquire(packageName: String): Lease? {
        val held = leases[packageName]
        if (held != null && held.ownerAlive?.invoke() != false) {
            held.retireIfHidden?.invoke()
            return null
        }
        return Lease().also { leases[packageName] = it }
    }

    @Synchronized fun finish(packageName: String, lease: Lease) {
        if (leases[packageName] === lease) leases.remove(packageName)
    }
}

/** Each retired display keeps its creating service and task snapshot until release is acknowledged. */
internal class MediaCardRelease(
    private val ownerAlive: () -> Boolean,
    private val restore: suspend () -> Boolean,
    private val release: () -> Boolean,
) {
    private var restored = false

    suspend fun attempt(): Boolean {
        if (!ownerAlive()) return true
        if (!restored) restored = runCatching { restore() }.getOrDefault(false)
        return !ownerAlive() || (restored && runCatching { release() }.getOrDefault(false))
    }
}
