package io.github.hypercar.carui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.launch
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * 音乐卡片的数据源：NotificationListenerService 权限 + MediaSessionManager.getActiveSessions。
 *
 * 为什么两条都要：
 *  - `getActiveSessions` 需要「通知使用权」的 listener 组件授权，否则 SecurityException；
 *  - 第三方播放器（QQ音乐/汽水/网易云）通过 MediaSession 暴露 metadata 与 transport 控制，
 *    这是标准接口，不需要各家私有 SDK。
 *
 * 歌词不做：标准 MediaSession 没有歌词字段（P1 结论，见 docs）。
 */
data class NowPlaying(
    val available: Boolean = false,
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val artist: String = "",
    val albumArt: Bitmap? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val reason: String = "",
    val liked: Boolean = false,
    val canLike: Boolean = false,
    val repeatMode: Int = PlaybackStateCompat.REPEAT_MODE_INVALID,
    val shuffleMode: Int = PlaybackStateCompat.SHUFFLE_MODE_INVALID,
    val canRepeat: Boolean = false,
    val canShuffle: Boolean = false,
    val queue: List<MusicQueueEntry> = emptyList(),
    val activeQueueId: Long = -1L,
    val canSelectQueue: Boolean = false,
    val dedicatedMode: MusicPlaybackMode? = null,
    val queueLoading: Boolean = false,
    val queueError: String = "",
    val modeError: String = "",
    val queueHasMore: Boolean = false,
)

data class MusicQueueEntry(val id: Long, val title: String, val artist: String, val sourceId: String? = null)

class MusicNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        if (shouldListen()) MusicBridge.attach(this)
        else pauseListening()
    }

    override fun onNotificationPosted(notification: android.service.notification.StatusBarNotification?) {
        notification?.let { MusicBridge.onNotificationChanged(it.packageName) }
    }

    override fun onNotificationRemoved(notification: android.service.notification.StatusBarNotification?) {
        notification?.let { MusicBridge.onNotificationChanged(it.packageName) }
    }

    override fun onListenerDisconnected() {
        if (connected === this) {
            connected = null
            MusicBridge.detach()
        }
        super.onListenerDisconnected()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        // Owned by the main thread; requestUnbind releases the SYSTEM binding, not permission.
        private var connected: MusicNotificationListener? = null

        private fun shouldListen() = io.github.hypercar.settings.SettingsStore.autoConnect ||
            HyperCarRepo.hasCurrentSession

        private fun pauseListening() {
            val listener = connected ?: return
            connected = null
            MusicBridge.detach("连接监听已暂停")
            runCatching { listener.requestUnbind() }
                .onFailure { HyperCarRepo.log("MUSIC: 暂停系统绑定失败 ${it.message}") }
            HyperCarRepo.log("MUSIC: 自动连接关闭且无会话，已暂停音乐监听")
        }

        /** Re-evaluate on delivery so rapid stop/start or switch changes cannot apply stale state. */
        fun sync(context: Context) {
            val app = context.applicationContext
            main.post {
                val component = ComponentName(app, MusicNotificationListener::class.java)
                // Migrate the local component-gating trial; use on-demand binding instead.
                runCatching {
                    if (app.packageManager.getComponentEnabledSetting(component) ==
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        app.packageManager.setComponentEnabledSetting(component,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            android.content.pm.PackageManager.DONT_KILL_APP)
                    }
                }.onFailure { HyperCarRepo.log("MUSIC: 恢复监听组件失败 ${it.message}") }
                if (!shouldListen()) {
                    pauseListening()
                } else if (connected == null && MusicBridge.hasAccess(app)) {
                    runCatching { requestRebind(ComponentName(app, MusicNotificationListener::class.java)) }
                        .onFailure { HyperCarRepo.log("MUSIC: 恢复系统绑定失败 ${it.message}") }
                }
            }
        }
    }
}

object MusicBridge {

    private const val TAG = "HyperCar-Music"
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(NowPlaying())
    /** 歌词缓存（2026-10-31）：只有 QQ 音乐通过第三方 API 能拿到 LRC。 */
    val state: StateFlow<NowPlaying> = _state

    private var service: NotificationListenerService? = null
    private var controller: MediaController? = null
    private var compatController: MediaControllerCompat? = null
    // Main-thread snapshots. Rendering and the ticker never query a remote player.
    private data class SessionSnapshot(
        val controller: MediaController?,
        val metadata: MediaMetadata?,
        val playback: PlaybackState?,
        val queue: List<MediaSession.QueueItem>,
        val ratingType: Int,
        val count: Int,
        val denied: Boolean = false,
    )
    private var metadata: MediaMetadata? = null
    private var playback: PlaybackState? = null
    private var queue: List<MediaSession.QueueItem> = emptyList()
    private var ratingType = Rating.RATING_NONE
    private var repeatMode = PlaybackStateCompat.REPEAT_MODE_INVALID
    private var shuffleMode = PlaybackStateCompat.SHUFFLE_MODE_INVALID
    private var repeatRevision = 0L
    private var shuffleRevision = 0L
    private var sessionGeneration = 0L
    private var callback: MediaController.Callback? = null
    private val sessionQueries = musicQuery<SessionSnapshot>("music-session-query")
    // Compat mode getters call the PLAYER synchronously, unlike framework session controls.
    // Keep them separate so a hung player cannot block discovery of another player's session.
    private val modeQueries = musicQuery<Unit>("music-mode-query")
    private val modeCommands = musicQuery<Unit>("music-mode-command")
    // Only touched by modeQueries' single worker, including unregister/register.
    private var modeRegistration: Pair<MediaControllerCompat, MediaControllerCompat.Callback>? = null

    private fun <T> musicQuery(name: String) = LatestMusicQuery<T>(
        name, { action -> main.post { action() }; Unit },
        { HyperCarRepo.log("MUSIC: $name 失败 ${it.javaClass.simpleName}: ${it.message}") },
    )
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    /** 上一次「选中」日志的 key，避免断连后 session 抖动导致刷屏（2026-10-31）。 */
    private var lastChoiceKey = ""
    /** 拉起音乐 App 用（Shizuku exec 挂起，需脱离音乐线程）。 */
    private val launchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var permissionDenied = false
    private var resumePackage: String? = null
    private var carConnected = false
    private var carAudioReady = false
    private var playbackPrefs: android.content.SharedPreferences? = null
    private var manualPauseRequested = false
    /** 用户在音乐卡片里选定的 App；有它的会话时优先显示它，而不是按“正在播放”抢焦点。 */
    private var preferredPackage: String? = null
    private val packageSwitchRevision = java.util.concurrent.atomic.AtomicLong()
    private var autoPlayPending = false
    private var autoPlayDeadline = 0L

    /** Serialize MediaSession commands with metadata callbacks, before restoring the phone audio route. */
    private fun onMusicThread(wait: Boolean = true, action: () -> Unit) {
        if (Looper.myLooper() == main.looper) {
            action()
        } else {
            if (!wait) {
                main.post(action)
                return
            }
            val completed = java.util.concurrent.CountDownLatch(1)
            main.post { try { action() } finally { completed.countDown() } }
            if (!completed.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                HyperCarRepo.log("MUSIC: 等待连接播放状态处理超时")
            }
        }
    }

    fun onCarConnected() = onMusicThread {
        carConnected = true
        carAudioReady = false
        autoPlayPending = CarUiSettings.autoPlayOnConnect
        autoPlayDeadline = 0L
    }

    fun onCarAudioReady() = onMusicThread(wait = false) {
        // 诊断：把参与判断的几个开关都打出来，便于定位"自动播放没触发"（2026-10-31）
        HyperCarRepo.log("MUSIC: 音频就绪检查 carConnected=$carConnected pending=$autoPlayPending autoPlayCfg=${CarUiSettings.autoPlayOnConnect} defaultPkg=${CarUiSettings.defaultMusicPackage} resume=$resumePackage")
        if (carConnected) {
            carAudioReady = true
            // **去掉 `resumePackage == null` 这个门槛**（2026-10-31 实测）：
            // 上次断连时会把当时的播放器写进 resume_package 并在 attach() 时读回，
            // 于是冷启动时 resumePackage 一开始就非空 → 自动播放整段被跳过，
            // 而 resumeAfterReconnect() 又因为没有会话直接 return，结果永远不拉。
            // 现在只要「开了自动播放」就执行；resumePackage 只作为 pkg 的兜底来源。
            if (autoPlayPending && CarUiSettings.autoPlayOnConnect) {
                val pkg = CarUiSettings.defaultMusicPackage.takeIf { it.isNotBlank() }
                    ?: preferredPackage ?: resumePackage ?: controller?.packageName
                if (pkg != null) {
                    resumePackage = pkg
                    autoPlayDeadline = SystemClock.elapsedRealtime() + 15_000
                    val ctx = service
                    if (ctx != null && controller?.packageName != pkg) selectPackage(ctx, pkg)
                    // **冷启动时把音乐 App 真的拉起来**（2026-10-31）：
                    // 之前只调 selectPackage()，它只是"选中偏好"，App 没在跑就没有 MediaSession，
                    // 于是 resumeAfterReconnect() 找不到会话、音乐卡片一直停在"连接你的音乐"。
                    // 这里主动启动默认音乐 App，再等它的会话出现（15s 超时）。
                    if (ctx != null && controller?.packageName != pkg) {
                        // 异步拉起（Shizuku exec 是挂起函数，不能占住音乐线程）
                        launchScope.launch {
                            val r = runCatching { launchMusicApp(ctx, pkg) }.getOrElse { "err" }
                            HyperCarRepo.log("MUSIC: 自动播放 → 拉起 $pkg（$r）")
                        }
                    }
                    HyperCarRepo.log("MUSIC: 音频就绪，准备自动播放 $pkg")
                }
            }
            autoPlayPending = false
            resumeAfterReconnect()
        }
    }

    /**
     * 启动指定音乐 App（2026-10-31）。
     *
     * 车机界面在虚拟屏上，App 本身多半处于后台，直接 startActivity 会被
     * Android 的后台启动限制拦掉；所以优先走 Shizuku 的 `am start`，
     * 失败再退回普通 startActivity（有前台可见性时仍可用）。
     */
    /**
     * 启动指定音乐 App（2026-10-31）。**优先把它放到一块不可见的虚显上**，
     * 和地图（D2）一样在后台跑，不再把音乐 App 拉到手机前台。
     *
     * 做法：用 ImageReader 造一个 1080×1920 的 Surface（只给虚显当输出，不读帧、不编码），
     * 交给 shell 建一块 PUBLIC 虚显，然后 `am start --display N`。
     * 虚显常驻复用，切 App 时只换 display 上的 Activity。
     */
    /**
     * **把音乐 App 拉到手机屏幕**（2026-10-31）。
     *
     * 自动播放时我们把它放在不可见虚显上（不抢前台），但用户想改播放列表/音效时必须能在手机上操作。
     * 由于 App 的 task 已经在虚拟屏上，单纯 `am start --display 0` 不一定能把 task 迁过去，
     * 所以这里做"强制重启到默认屏"：force-stop（清掉虚显上的实例）→ 普通 am start（落主屏）。
     * 下一次连接/自动播放会重新把它放回虚显。
     */
    fun openOnPhone(pkg: String) {
        if (pkg.isBlank()) return
        launchScope.launch {
            val ctx = service
            val intent = ctx?.packageManager?.getLaunchIntentForPackage(pkg)
            val comp = intent?.component
            if (io.github.hypercar.shizuku.ShizukuTools.isReady && comp != null) {
                runCatching {
                    io.github.hypercar.shizuku.ShizukuTools.exec("am force-stop $pkg", 6_000)
                }
                val r = runCatching {
                    io.github.hypercar.shizuku.ShizukuTools.exec(
                        "am start -n ${comp.flattenToShortString()}", 8_000)
                }.getOrNull()
                HyperCarRepo.log("MUSIC: 拉到手机屏幕 $pkg（${if (r?.ok == true) "ok" else r?.err?.take(60)}）")
            } else {
                val i = intent
                if (i != null) {
                    runCatching {
                        checkNotNull(ctx).startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        HyperCarRepo.log("MUSIC: 拉到手机屏幕 $pkg（startActivity）")
                    }
                }
            }
        }
    }

    private suspend fun launchMusicApp(ctx: android.content.Context, pkg: String): String {
        val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: return "no-launch-intent"
        val comp = intent.component
        if (comp != null && io.github.hypercar.shizuku.ShizukuTools.isReady) {
            // ① 复用/新建一块不可见虚显（1080×1920：窗口越小播放器解码的封面越小、显得模糊）
            val dId = ensureIdleDisplay()
            if (dId > 0) {
                val r = runCatching {
                    io.github.hypercar.shizuku.ShizukuTools.exec(
                        "am start -n ${comp.flattenToShortString()} --display $dId", 8_000)
                }.getOrNull()
                if (r != null && r.ok) return "display-$dId"
                HyperCarRepo.log("MUSIC: 虚显 $dId 启动失败（${r?.err?.take(80)}），回退普通启动")
            }
            // ② 虚显不可用 → 退回普通 am start（会短暂跳到前台）
            val r = runCatching {
                io.github.hypercar.shizuku.ShizukuTools.exec(
                    "am start -n ${comp.flattenToShortString()}", 8_000)
            }.getOrNull()
            if (r != null && r.ok) return "am-start-ok(前台)"
        }
        return runCatching {
            ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            "activity-ok(前台)"
        }.getOrElse { "fail:${it.javaClass.simpleName}" }
    }

    /** 音乐 App 用的不可见虚显（8×8）；建好后整场复用。 */
    private var idleDisplayId = -1
    /** 卡片里那块 TextureView 的 Surface（关闭时要释放）。 */
    private var viewSurface: android.view.Surface? = null
    /** 卡片里那块视图的实际像素尺寸（触摸换算要用它，和虚显一致）。 */
    @Volatile var viewW = 1080; private set
    @Volatile var viewH = 1920; private set
    private var baseW = 1080; private var baseH = 1920
    private var viewTexture: android.graphics.SurfaceTexture? = null
    private var idleReader: android.media.ImageReader? = null

    // ---- 音乐 App 在卡片里显示（2026-11-01）----
    /** 音乐虚显的逻辑尺寸（触摸换算、建屏都用它）。 */
    val MUSIC_DISPLAY_W = 1080
    val MUSIC_DISPLAY_H = 1920

    /** 音乐 App 的虚显 id（未创建时为 -1）。 */

    /** 把音乐虚显的输出面换成卡片里的 TextureView，画面就"搬进"音乐卡。 */
    /**
     * 把音乐 App 显示在音乐卡里（2026-11-01 第二版）。
     *
     * 关键变化：**按卡片的真实像素尺寸重建虚显**（而不是把 1080×1920 的画面硬塞进横卡），
     * 这样宽高比和卡片一致 —— 既不变形，也不会黑屏（上一次黑屏是因为把竖屏虚显的
     * 输出面直接换到了比例完全不同的卡面上）。
     */
    fun showInView(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        texture.setDefaultBufferSize(w, h)
        viewW = w; viewH = h
        val surface = android.view.Surface(texture)
        viewSurface = surface
        launchScope.launch { createViewDisplay(surface, w, h) }
    }

    private suspend fun createViewDisplay(surface: android.view.Surface, dw: Int, dh: Int) {
        releaseIdleDisplay()
        val created = runCatching {
            io.github.hypercar.spike.SpikeShell.createDisplay(surface, dw, dh, 160, 0, "hypercar-music-view")
        }.getOrNull().orEmpty()
        val id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (id <= 0) {
            HyperCarRepo.log("MUSIC: 建卡片虚显失败：${created.take(100)}")
            return
        }
        idleDisplayId = id
        HyperCarRepo.log("MUSIC: 音乐画面进卡片 display=$id ${dw}x$dh")
        val pkg = controller?.packageName ?: CarUiSettings.defaultMusicPackage.takeIf { it.isNotBlank() }
        if (pkg != null) {
            val comp = runCatching {
                service?.packageManager?.getLaunchIntentForPackage(pkg)?.component
            }.getOrNull()
            if (comp != null) {
                val r = runCatching {
                    io.github.hypercar.shizuku.ShizukuTools.exec(
                        "am start -n ${comp.flattenToShortString()} --display $id", 8_000)
                }.getOrNull()
                HyperCarRepo.log("MUSIC: 卡片内拉起 $pkg（${if (r?.ok == true) "ok" else r?.err?.take(60)}）")
            }
        }
    }

    /** 卡片收起：拆掉卡片虚显，重建后台虚显并让 App 继续在后台跑（不中断播放）。 */
    fun hideFromView() {
        launchScope.launch {
            viewSurface = null
            val pkg = controller?.packageName
            releaseIdleDisplay()
            val id = ensureIdleDisplay()
            HyperCarRepo.log("MUSIC: 音乐 App 回到后台 display=$id")
        }
    }

    fun touch(action: Int, x: Int, y: Int) {
        val id = idleDisplayId
        if (id <= 0) return
        runCatching { io.github.hypercar.spike.SpikeShell.motion(id, action, x, y, SystemClock.uptimeMillis()) }
    }

    private suspend fun ensureIdleDisplay(): Int {
        if (idleDisplayId > 0) return idleDisplayId
        val reader = runCatching {
            android.media.ImageReader.newInstance(MUSIC_DISPLAY_W, MUSIC_DISPLAY_H, android.graphics.PixelFormat.RGBA_8888, 2)
        }.getOrNull() ?: return -1
        val created = runCatching {
            io.github.hypercar.spike.SpikeShell.createDisplay(
                reader.surface, MUSIC_DISPLAY_W, MUSIC_DISPLAY_H, 160, 0, "hypercar-music")
        }.getOrNull().orEmpty()
        val id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (id <= 0) {
            HyperCarRepo.log("MUSIC: 建音乐虚显失败：${created.take(120)}")
            runCatching { reader.close() }
            return -1
        }
        idleReader = reader       // 保持引用，避免 Surface 被回收
        idleDisplayId = id
        HyperCarRepo.log("MUSIC: 音乐虚显就绪 display=$id（${created.take(80)}）")
        return id
    }

    /** 会话结束/切换时释放音乐虚显（由 detach 调用）。 */
    private fun releaseIdleDisplay() {
        if (idleDisplayId > 0) runCatching { io.github.hypercar.spike.SpikeShell.releaseDisplay(idleDisplayId) }
        idleDisplayId = -1
        runCatching { idleReader?.close() }
        idleReader = null
    }

    fun onCarDisconnected() = onMusicThread {
        packageSwitchRevision.incrementAndGet()
        if (!carConnected) return@onMusicThread
        carConnected = false
        carAudioReady = false
        autoPlayPending = false
        autoPlayDeadline = 0L
        // 不要只信 controller：切 App 后它可能还指向旧的会话。断连时把所有
        // 正在播放的 MediaSession 都暂停，避免手机外放/后台继续响。
        val s = service
        val sessions = runCatching {
            s?.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(
                ComponentName(s, MusicNotificationListener::class.java),
            )
        }.getOrNull().orEmpty()
        val playingStates = listOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
        )
        val playing = buildList {
            addAll(sessions.filter { it.playbackState?.state in playingStates })
            val c = controller
            if (c != null && c.playbackState?.state in playingStates && none { it.packageName == c.packageName }) {
                add(c)
            }
        }
        val target = playing.firstOrNull { it.packageName == controller?.packageName }
            ?: playing.firstOrNull()
        if (!manualPauseRequested && playing.isNotEmpty()) {
            resumePackage = target?.packageName
            playbackPrefs?.edit()?.putString("resume_package", resumePackage)?.apply()
            playing.forEach { session ->
                runCatching { session.transportControls.pause() }
                    .onFailure { HyperCarRepo.log("MUSIC: 断连暂停失败 ${it.message}") }
            }
            HyperCarRepo.log("MUSIC: 车机断开，已暂停 ${playing.joinToString { it.packageName }}")
        } else {
            resumePackage = null
            playbackPrefs?.edit()?.remove("resume_package")?.apply()
        }
    }

    private fun resumeAfterReconnect() {
        if (!carConnected || !carAudioReady) return
        if (autoPlayDeadline > 0 && SystemClock.elapsedRealtime() > autoPlayDeadline) {
            resumePackage = null
            autoPlayDeadline = 0L
            HyperCarRepo.log("MUSIC: 自动播放等待媒体会话超时")
        }
        val pkg = resumePackage ?: return
        val s = service ?: return
        val sessions = runCatching {
            s.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(
                ComponentName(s, MusicNotificationListener::class.java),
            )
        }.getOrNull().orEmpty()
        val target = sessions.firstOrNull { it.packageName == pkg } ?: return
        runCatching { target.transportControls.play() }.onSuccess {
            resumePackage = null
            autoPlayDeadline = 0L
            playbackPrefs?.edit()?.remove("resume_package")?.apply()
            HyperCarRepo.log("MUSIC: 车机音频已就绪，恢复 $pkg")
        }.onFailure { HyperCarRepo.log("MUSIC: 恢复播放失败 ${it.message}") }
    }

    private fun sessionCallback(generation: Long) = object : MediaController.Callback() {
        override fun onQueueChanged(value: MutableList<MediaSession.QueueItem>?) {
            if (generation != sessionGeneration) return
            queue = value.orEmpty().toList()
            publish()
        }
        override fun onMetadataChanged(value: MediaMetadata?) {
            if (generation != sessionGeneration) return
            metadata = value
            publish()
        }
        override fun onPlaybackStateChanged(value: PlaybackState?) {
            if (generation != sessionGeneration) return
            playback = value
            if (value?.state == PlaybackState.STATE_PAUSED || value?.state == PlaybackState.STATE_STOPPED) {
                manualPauseRequested = false
            }
            publish()
        }
        override fun onSessionDestroyed() {
            if (generation != sessionGeneration) return
            clearController()
            publish()
            choose()
        }
    }

    private fun clearController() {
        sessionGeneration++
        modeQueries.invalidate()
        modeCommands.invalidate()
        callback?.let { old -> runCatching { controller?.unregisterCallback(old) } }
        callback = null
        controller = null
        compatController = null
        metadata = null
        playback = null
        queue = emptyList()
        ratingType = Rating.RATING_NONE
        repeatMode = PlaybackStateCompat.REPEAT_MODE_INVALID
        shuffleMode = PlaybackStateCompat.SHUFFLE_MODE_INVALID
        // A blocked Binder cannot be cancelled. There is at most one running query and
        // one replacement, so detach/switch never allocates more blocked threads.
        modeQueries.submit({ clearModeRegistration() }, {})
    }

    private fun clearModeRegistration() {
        modeRegistration?.let { (remote, listener) -> runCatching { remote.unregisterCallback(listener) } }
        modeRegistration = null
    }

    private fun bindModes(s: NotificationListenerService, target: MediaController, generation: Long) {
        modeQueries.submit({
            clearModeRegistration()
            val remote = MediaControllerCompat(s, MediaSessionCompat.Token.fromToken(target.sessionToken)!!)
            val listener = object : MediaControllerCompat.Callback() {
                override fun onSessionReady() {
                    if (generation == sessionGeneration) refreshModes(remote, generation)
                }
                override fun onRepeatModeChanged(value: Int) {
                    if (generation != sessionGeneration) return
                    repeatRevision++
                    repeatMode = value
                    publish()
                }
                override fun onShuffleModeChanged(value: Int) {
                    if (generation != sessionGeneration) return
                    shuffleRevision++
                    shuffleMode = value
                    publish()
                }
            }
            modeRegistration = remote to listener
            remote.registerCallback(listener, main)
            main.post {
                if (generation == sessionGeneration) {
                    compatController = remote
                    refreshModes(remote, generation)
                }
            }
        }, {})
    }

    private fun refreshModes(remote: MediaControllerCompat, generation: Long) {
        val rr = repeatRevision
        val sr = shuffleRevision
        modeQueries.submit({
            // Deliver independently: a blocked shuffle query must not hide repeat's result.
            val repeat = remote.repeatMode
            main.post {
                if (generation == sessionGeneration && repeatRevision == rr) {
                    repeatMode = repeat
                    publish()
                }
            }
            val shuffle = remote.shuffleMode
            main.post {
                if (generation == sessionGeneration && shuffleRevision == sr) {
                    shuffleMode = shuffle
                    publish()
                }
            }
        }, {})
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (controller != null) publish()
            resumeAfterReconnect()
            main.postDelayed(this, 1_000)
        }
    }

    /** 当前是否有通知使用权（设置页/诊断页用它引导用户）。 */
    fun hasAccess(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    /** 跳系统「通知使用权」设置页（手机主屏，不是车机屏）。 */
    fun openAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 音乐卡片左上角切换 App：记住选择并把目标 App 拉到手机前台（车机只显示媒体卡片）。 */
    fun selectPackage(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val switchRevision = packageSwitchRevision.incrementAndGet()
        // 切换前先停掉/关掉上一个 App（即使它已暂停，也一并清掉，避免后台残留）。
        val previous = controller?.packageName
        val previousPlaying = playback?.state == PlaybackState.STATE_PLAYING
        if (!previous.isNullOrBlank() && previous != packageName) {
            if (previousPlaying) {
                runCatching { controller?.transportControls?.pause() }
                HyperCarRepo.log("MUSIC: 停止上一个播放器 $previous")
            }
            closePackage(previous, switchRevision)
        }
        preferredPackage = packageName
        val prefs = playbackPrefs
            ?: context.getSharedPreferences("car_playback", Context.MODE_PRIVATE)
        prefs.edit().putString("preferred_package", packageName).apply()
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        val component = intent?.component
        if (component != null && ShizukuTools.isReady) {
            // 走 shell 拉起：MIUI 对后台 startActivity 会弹「是否允许启动」确认页，
            // 车机上点一下切歌不该把手机前台让给系统确认框。
            val cmd = "am start -n ${component.flattenToShortString()} --display 0"
            launchScope.launch {
                if (packageSwitchRevision.get() == switchRevision) {
                    runCatching { ShizukuTools.exec(cmd, 4_000) }
                }
            }
        } else if (intent != null) {
            runCatching {
                val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0)
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
            }.onFailure {
                HyperCarRepo.log("MUSIC: 切换 ${packageName} 失败 ${it.message}")
            }
        }
        HyperCarRepo.log("MUSIC: 切换音乐 App -> $packageName")
        main.post { choose() }
    }

    /** 设置里改默认音乐 App：只改偏好，不立即播放。 */
    fun setDefaultPackage(packageName: String) {
        packageSwitchRevision.incrementAndGet()
        preferredPackage = packageName.takeIf { it.isNotBlank() }
        playbackPrefs?.edit()?.putString("preferred_package", packageName)?.apply()
        main.post { choose() }
    }

    /** Delayed cleanup belongs to one switch; A→B→A must not force-stop the new A. */
    private fun closePackage(packageName: String, revision: Long) {
        if (packageName.isBlank() || !ShizukuTools.isReady) return
        launchScope.launch {
            kotlinx.coroutines.delay(250)
            if (packageSwitchRevision.get() != revision) return@launch
            runCatching { ShizukuTools.exec("am force-stop $packageName", 4_000) }
        }
    }

    fun attach(service: NotificationListenerService) {
        HyperCarRepo.log("MUSIC: 通知监听已接入（attach）")
        this.service = service
        playbackPrefs = service.getSharedPreferences("car_playback", Context.MODE_PRIVATE)
        resumePackage = playbackPrefs?.getString("resume_package", null)
        preferredPackage = CarUiSettings.defaultMusicPackage.takeIf { it.isNotBlank() }
            ?: playbackPrefs?.getString("preferred_package", null)
        permissionDenied = false
        val msm = service.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(service, MusicNotificationListener::class.java)
        if (sessionsListener == null && msm != null) {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
                choose(list)
            }
            sessionsListener = listener
            runCatching {
                msm.addOnActiveSessionsChangedListener(listener, component, main)
            }.onFailure {
                HyperCarRepo.log("MUSIC: addOnActiveSessionsChangedListener 失败: ${it.message}")
            }
        }
        choose()
        main.removeCallbacks(ticker)
        main.post(ticker)
        HyperCarRepo.log("MUSIC: listener connected（access=${hasAccess(service)}）")
    }

    fun detach(reason: String = "通知使用权已断开") {
        packageSwitchRevision.incrementAndGet()
        QqMusicAdapter.close()
        MusicArtwork.clear()
        main.removeCallbacks(ticker)
        sessionQueries.invalidate()
        clearController()
        val oldService = service
        sessionsListener?.let { listener ->
            runCatching { oldService?.getSystemService(MediaSessionManager::class.java)
                ?.removeOnActiveSessionsChangedListener(listener) }
        }
        sessionsListener = null
        service = null
        _state.value = NowPlaying(reason = reason)
        HyperCarRepo.log("MUSIC: listener disconnected")
    }

    private fun choose(provided: List<MediaController>? = null) {
        val s = service ?: return
        val preferred = preferredPackage
        sessionQueries.submit({
            val msm = s.getSystemService(MediaSessionManager::class.java)
            var denied = false
            val sessions = provided ?: runCatching {
                msm?.getActiveSessions(ComponentName(s, MusicNotificationListener::class.java))
            }.getOrElse {
                denied = it is SecurityException
                HyperCarRepo.log("MUSIC: getActiveSessions 失败: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }.orEmpty()
            val target = sessions.firstOrNull { it.packageName == preferred }
                ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
                ?: sessions.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
                ?: sessions.firstOrNull()
            SessionSnapshot(target, target?.metadata, target?.playbackState,
                target?.queue.orEmpty().toList(), target?.ratingType ?: Rating.RATING_NONE, sessions.size, denied)
        }, { snapshot ->
            if (service !== s) return@submit
            permissionDenied = snapshot.denied
            val target = snapshot.controller
            // Framework creates fresh wrappers for the same token; don't replace callbacks
            // or overwrite newer callback values with a delayed discovery snapshot.
            if (target?.sessionToken != controller?.sessionToken) {
                clearController()
                controller = target
                metadata = snapshot.metadata
                playback = snapshot.playback
                queue = snapshot.queue
                ratingType = snapshot.ratingType
                if (target != null) {
                    val generation = sessionGeneration
                    callback = sessionCallback(generation).also {
                        runCatching { target.registerCallback(it, main) }
                    }
                    bindModes(s, target, generation)
                }
                val key = "${target?.packageName ?: "-"}|${snapshot.count}"
                if (CarUiCaster.running && key != lastChoiceKey) {
                    lastChoiceKey = key
                    HyperCarRepo.log("MUSIC: 选中 ${target?.packageName ?: "-"}（共 ${snapshot.count} 个会话）")
                }
            }
            publish()
        })
    }

    fun onNotificationChanged(packageName: String) {
        main.post {
            if (controller?.packageName == packageName) { MusicArtwork.invalidate(); publish() }
        }
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            QqMusicAdapter.close()
            MusicArtwork.clear()
            _state.value = NowPlaying(
                reason = when {
                    permissionDenied -> "请在系统设置里授予「通知使用权」"
                    else -> "未在播放（打开 QQ音乐 / 汽水音乐 任意播放一首）"
                },
            )
            return
        }
        val md = metadata
        val ps = playback
        val pkg = c.packageName
        val ctx = service
        QqMusicAdapter.select(ctx, pkg == QqMusicAdapter.PACKAGE) { publish() }
        val qq = QqMusicAdapter.state.takeIf { pkg == QqMusicAdapter.PACKAGE }
        val label = runCatching {
            val ai = ctx!!.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
        val art = MusicArtwork.resolve(ctx, pkg, md) { publish() }
        val rating = md?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.takeIf { it.isRated }
        val likeAction = favoriteAction(ps)
        val actions = ps?.actions ?: 0L
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        _state.value = NowPlaying(
            available = true,
            packageName = pkg,
            appLabel = label,
            title = title,
            artist = artist,

            albumArt = art,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            positionMs = positionOf(ps),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            liked = when (rating?.ratingStyle) {
                Rating.RATING_HEART -> rating.hasHeart()
                Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
                else -> likeAction?.name?.toString()?.let(FavoriteActionLabels::liked) ?: false
            },
            canLike = likeAction != null || (actions and PlaybackState.ACTION_SET_RATING != 0L &&
                ratingType in listOf(Rating.RATING_HEART, Rating.RATING_THUMB_UP_DOWN)),
            repeatMode = repeatMode,
            shuffleMode = shuffleMode,
            canRepeat = actions and PlaybackStateCompat.ACTION_SET_REPEAT_MODE != 0L,
            canShuffle = actions and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE != 0L,
            queue = if (qq != null && qq.songs.isNotEmpty()) qq.songs.mapIndexed { index, song ->
                MusicQueueEntry(index.toLong(), song.title, song.artist, song.id)
            } else queue.map { MusicQueueEntry(it.queueId,
                it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty()) },
            activeQueueId = ps?.activeQueueItemId ?: -1L,
            canSelectQueue = qq?.songs?.isNotEmpty() == true || actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L,
            dedicatedMode = qq?.mode,
            queueLoading = qq?.loading ?: false,
            queueError = qq?.queueError.orEmpty(),
            modeError = qq?.modeError.orEmpty(),
            queueHasMore = qq?.hasMore ?: false,
        )
    }

    private fun positionOf(ps: PlaybackState?): Long {
        if (ps == null) return 0L
        var pos = ps.position
        if (ps.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
            if (elapsed >= 0) pos += (elapsed * ps.playbackSpeed).toLong()
        }
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return pos.coerceAtLeast(0L).let { if (duration > 0) it.coerceAtMost(duration) else it }
    }

    fun playPause() {
        autoPlayPending = false
        autoPlayDeadline = 0L
        // Explicit user playback controls supersede the saved automatic-resume request.
        resumePackage = null
        playbackPrefs?.edit()?.remove("resume_package")?.apply()
        val c = controller ?: return
        if (playback?.state == PlaybackState.STATE_PLAYING) {
            manualPauseRequested = true
            c.transportControls.pause()
        } else {
            manualPauseRequested = false
            c.transportControls.play()
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    private fun favoriteAction(ps: PlaybackState?): PlaybackState.CustomAction? =
        ps?.customActions?.firstOrNull {
            FavoriteActionLabels.supported(it.name.toString())
        }

    fun toggleFavorite() = onMusicThread {
        val c = controller ?: return@onMusicThread
        if (!_state.value.canLike) return@onMusicThread
        runCatching {
            val custom = favoriteAction(playback)
            if (custom != null) c.transportControls.sendCustomAction(custom.action, custom.extras)
            else when (ratingType) {
                Rating.RATING_HEART -> c.transportControls.setRating(Rating.newHeartRating(!_state.value.liked))
                Rating.RATING_THUMB_UP_DOWN -> c.transportControls.setRating(Rating.newThumbRating(!_state.value.liked))
            }
        }.onFailure { HyperCarRepo.log("MUSIC: 喜欢操作失败 ${it.message}") }
    }

    fun setPlaybackMode(mode: MusicPlaybackMode) = onMusicThread {
        if (controller?.packageName == QqMusicAdapter.PACKAGE && QqMusicAdapter.state.mode != null) {
            QqMusicAdapter.setMode(mode); return@onMusicThread
        }
        val c = compatController ?: return@onMusicThread
        val state = _state.value
        if (!MusicPlaybackModes.available(state.canRepeat, state.canShuffle, state.repeatMode, state.shuffleMode).contains(mode)) return@onMusicThread
        modeCommands.submit({
            if (state.canShuffle) c.transportControls.setShuffleMode(
                if (mode == MusicPlaybackMode.SHUFFLE) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
            if (state.canRepeat) c.transportControls.setRepeatMode(when (mode) {
                MusicPlaybackMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
                MusicPlaybackMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        }, {})
    }

    fun playQueueItem(packageName: String, id: Long, sourceId: String? = null) = onMusicThread {
        val c = controller ?: return@onMusicThread
        if (c.packageName == packageName && packageName == QqMusicAdapter.PACKAGE && sourceId != null) {
            QqMusicAdapter.playSong(sourceId); return@onMusicThread
        }
        if (c.packageName != packageName || (playback?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM == 0L) return@onMusicThread
        if (queue.none { it.queueId == id }) return@onMusicThread
        runCatching { c.transportControls.skipToQueueItem(id) }
            .onFailure { HyperCarRepo.log("MUSIC: 切换列表歌曲失败 ${it.message}") }
    }

    fun loadMusicQueue(more: Boolean = false) = onMusicThread {
        if (controller?.packageName == QqMusicAdapter.PACKAGE) QqMusicAdapter.loadQueue(more)
    }

    fun refreshPlaybackMode() = onMusicThread {
        if (controller?.packageName == QqMusicAdapter.PACKAGE) QqMusicAdapter.refreshMode()
    }

    fun refresh() = choose()
}
