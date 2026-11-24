package io.github.hypercar.carui

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.spike.SpikeDisplay
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        try { SpikeShell.touchEvent(id, event).startsWith("OK") } finally { event.recycle() }
    }
    private val desired get() = input.desired
    private enum class CloseReason { UserDismissed, ViewDisposed }
    @Volatile private var closeReason: CloseReason? = null
    private val closed get() = closeReason != null
    private val endProjectedTask get() = closeReason == CloseReason.UserDismissed && stabilizeExpandedWindow
    @Volatile private var attachment: Attachment? = null
    private var displayId = -1
    private var tasksBeforeLaunch: List<MediaTaskWindow.Task>? = null
    private var needsFullscreenReset = false
    private var shortVideoWindow: MediaTaskWindow.ExpandedShortVideo? = null

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
                    if (displayId > 0 && needsFullscreenReset && !request.expanded && !resetWindowMode()) {
                        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId rebuilding for home card")
                        releaseDisplay(restoreWindow = false)
                    }
                    if (displayId > 0 && shortVideoWindow != null && !request.expanded && !resetOrientation()) {
                        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId rebuilding for home orientation")
                        releaseDisplay(restoreWindow = false)
                    }
                    if (!isCurrent(request)) return@withLock
                    if (displayId > 0) {
                        if (stabilizeExpandedWindow && request.expanded && shortVideoWindow == null) prepareExpandedShortVideo()
                        if (!isCurrent(request)) return@withLock
                        val result = SpikeShell.updateDisplaySurface(
                            displayId, surface, viewport.width, viewport.height, viewport.dpi,
                        )
                        if (result.startsWith("OK") && applyWindowMode(request)) {
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
        val viewport = request.size
        val component = runCatching { ProjectionApps.launcherComponent(app, packageName) }.getOrNull()
        if (component == null) {
            if (isCurrent(request)) error.value = "应用当前无法启动"
            return false
        }
        SpikeShell.connect(app)
        if (!isCurrent(request)) return false
        val previous = runCatching { MediaTaskWindow.snapshot() }.getOrNull()
        if (!isCurrent(request)) return false
        // Leave Android's task migration enabled if the display owner dies or cleanup cannot run.
        val created = SpikeShell.createDisplay(
            surface, viewport.width, viewport.height, viewport.dpi, name = "hypercar-media-card",
        )
        val id = DISPLAY_ID.find(created)?.groupValues?.get(1)?.toIntOrNull()
        if (id == null || id <= 0) {
            if (isCurrent(request)) error.value = "无法创建应用画面，请检查 Shizuku"
            HyperCarRepo.log("VIDEO-CARD: display failed package=$packageName result=${created.take(120)}")
            return false
        }
        var launched = false
        try {
            if (!isCurrent(request)) return false
            displayId = id
            tasksBeforeLaunch = previous
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
            if (!launched) {
                if (displayId == id) releaseDisplay() else releaseProjectionDisplay(id, previous)
            }
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
                SpikeShell.updateDisplaySurface(old.displayId, null, 0, 0, 0)
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
        shortVideoWindow = prepared
        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId shortVideoOrientationLocked=${prepared.ready}")
    }

    private suspend fun resetOrientation(): Boolean {
        val original = shortVideoWindow?.restoreIgnoreOrientation
        if (original != null) {
            val restored = MediaTaskWindow.setIgnoreOrientationRequest(displayId, original)
            HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId orientationRestored=$restored")
            if (!restored) return false
        }
        shortVideoWindow = null
        return true
    }

    private suspend fun applyWindowMode(request: Request): Boolean {
        if (!isCurrent(request) || !request.expanded) return true
        if (stabilizeExpandedWindow && shortVideoWindow?.ready != true) return true
        val viewport = request.size
        needsFullscreenReset = true
        val expanded = runCatching {
            MediaTaskWindow.expand(displayId, packageName, viewport.width, viewport.height)
        }.getOrDefault(false)
        val usable = expanded || resetWindowMode()
        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId expandedWindow=$expanded")
        return usable || !stabilizeExpandedWindow
    }

    private suspend fun resetWindowMode(): Boolean {
        val restored = MediaTaskWindow.fullscreen(displayId)
        if (restored) needsFullscreenReset = false
        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId fullscreenRestored=$restored")
        return restored
    }

    private suspend fun releaseDisplay(restoreWindow: Boolean = true) {
        if (needsFullscreenReset && restoreWindow) resetWindowMode()
        if (restoreWindow && !endProjectedTask) resetOrientation()
        if (displayId > 0) releaseProjectionDisplay(displayId, tasksBeforeLaunch)
        displayId = -1
        tasksBeforeLaunch = null
        needsFullscreenReset = false
        shortVideoWindow = null
        attachment?.surface?.release()
        attachment = null
    }

    private suspend fun releaseProjectionDisplay(id: Int, previous: List<MediaTaskWindow.Task>?) {
        try {
            runCatching {
                MediaTaskWindow.releaseTasks(id, packageName, previous, closeProjectedTasks = endProjectedTask)
            }.onFailure {
                HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$id task cleanup failed: ${it.javaClass.simpleName}")
            }
        } finally {
            SpikeShell.releaseDisplay(id)
        }
    }

    private companion object {
        val DISPLAY_ID = Regex("id=(\\d+)")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val changes = Mutex()
    }
}
