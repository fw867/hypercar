package io.github.hypercar.carui

import android.content.Context
import android.graphics.SurfaceTexture
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

/** One application task can move between a full-sized pane and the home card. */
internal class MediaCardProjection(context: Context, private val packageName: String) {
    private val app = context.applicationContext
    val size = PhoneProjectionSize.read(context)
    val error = MutableStateFlow<String?>(null)

    @Volatile private var desired: Request? = null
    @Volatile private var closed = false
    @Volatile private var attachment: Attachment? = null
    private var displayId = -1
    private var tasksBeforeLaunch: List<MediaTaskWindow.Task>? = null
    private var needsFullscreenReset = false

    private data class Request(val texture: SurfaceTexture, val size: PhoneProjectionSize, val expanded: Boolean)
    private data class Attachment(val request: Request, val surface: Surface, val displayId: Int)

    fun show(texture: SurfaceTexture, viewWidth: Int, viewHeight: Int, expanded: Boolean) {
        if (closed || viewWidth <= 0 || viewHeight <= 0) return
        val viewport = if (expanded) size.expanded(viewWidth, viewHeight) else size
        texture.setDefaultBufferSize(viewport.width, viewport.height)
        val request = Request(texture, viewport, expanded && viewport.width > viewport.height)
        if (desired == request) return
        desired = request
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
                        releaseDisplay(restoreFullscreen = false)
                    }
                    if (!isCurrent(request)) return@withLock
                    if (displayId > 0) {
                        val result = SpikeShell.updateDisplaySurface(
                            displayId, surface, viewport.width, viewport.height, viewport.dpi,
                        )
                        if (result.startsWith("OK")) {
                            attachment?.surface?.release()
                            attachment = Attachment(request, surface, displayId)
                            attached = true
                            if (isCurrent(request)) applyWindowMode(request)
                            HyperCarRepo.log("VIDEO-CARD: package=$packageName surface reattached display=$displayId size=${viewport.width}x${viewport.height} dpi=${viewport.dpi}")
                            return@withLock
                        }
                        releaseDisplay()
                    }
                    attached = open(request, surface)
                } finally {
                    if (!attached) surface.release()
                }
            }
        }
    }

    private suspend fun open(request: Request, surface: Surface): Boolean {
        val viewport = request.size
        val component = runCatching { app.packageManager.getLaunchIntentForPackage(packageName)?.component }.getOrNull()
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
            val result = SpikeDisplay.amStartComponentOnDisplay(id, component.flattenToShortString())
            launched = !closed && result.contains(" → exit=0 ")
            if (launched) {
                displayId = id
                tasksBeforeLaunch = previous
                attachment = Attachment(request, surface, id)
                if (isCurrent(request)) applyWindowMode(request)
            } else if (isCurrent(request)) {
                error.value = "应用未能在卡片中打开"
            }
            HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$id size=${viewport.width}x${viewport.height} dpi=${viewport.dpi} ready=$launched result=${result.take(160)}")
            return launched
        } finally {
            if (!launched) releaseProjectionDisplay(id, previous)
        }
    }

    fun hide(texture: SurfaceTexture) {
        if (desired?.texture === texture) desired = null
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

    fun close() {
        closed = true
        desired = null
        scope.launch { changes.withLock { releaseDisplay() } }
    }

    fun touch(texture: SurfaceTexture, action: Int, x: Float, y: Float, downTime: Long) {
        val display = attachment?.takeIf { isCurrent(it.request) && it.request.texture === texture } ?: return
        val viewport = display.request.size
        val px = (x * viewport.width).toInt().coerceIn(0, viewport.width - 1)
        val py = (y * viewport.height).toInt().coerceIn(0, viewport.height - 1)
        SpikeShell.motion(display.displayId, action, px, py, downTime)
    }

    private fun isCurrent(request: Request) = !closed && desired === request

    private suspend fun applyWindowMode(request: Request) {
        if (!request.expanded) return
        val viewport = request.size
        needsFullscreenReset = true
        val expanded = runCatching {
            MediaTaskWindow.expand(displayId, packageName, viewport.width, viewport.height)
        }.getOrDefault(false)
        if (!expanded) resetWindowMode()
        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId expandedWindow=$expanded")
    }

    private suspend fun resetWindowMode(): Boolean {
        val restored = MediaTaskWindow.fullscreen(displayId)
        if (restored) needsFullscreenReset = false
        HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId fullscreenRestored=$restored")
        return restored
    }

    private suspend fun releaseDisplay(restoreFullscreen: Boolean = true) {
        if (needsFullscreenReset && restoreFullscreen) resetWindowMode()
        if (displayId > 0) releaseProjectionDisplay(displayId, tasksBeforeLaunch)
        displayId = -1
        tasksBeforeLaunch = null
        needsFullscreenReset = false
        attachment?.surface?.release()
        attachment = null
    }

    private suspend fun releaseProjectionDisplay(id: Int, previous: List<MediaTaskWindow.Task>?) {
        try {
            runCatching { MediaTaskWindow.releaseTasks(id, packageName, previous) }.onFailure {
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
