package io.github.hypercar.carui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.AudioAttributes
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.session.EngineCallbackGate
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

internal class ReceiverPauseTarget<P, K>(val player: P, val token: K, val generation: Long, val switchRevision: Long) {
    fun activeIn(sessions: List<P>, tokenOf: (P) -> K): P? = sessions.firstOrNull { tokenOf(it) == token }
    fun matches(selected: K?, generation: Long, switchRevision: Long) =
        token == selected && this.generation == generation && this.switchRevision == switchRevision
}

private fun MediaController.isPlayingPhoneMusic(context: Context, preferred: String?): Boolean =
    playbackState?.state == PlaybackState.STATE_PLAYING && isLocalMusic(context, preferred)

private fun MediaController.isLocalMusic(context: Context?, preferred: String?): Boolean {
    if (ShortVideoApps.isSupported(packageName)) return false
    val info = playbackInfo ?: return false
    if (info.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) return false
    return packageName == preferred || MusicApps.isKnownPlayer(packageName) ||
        info.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MUSIC ||
        runCatching { context?.packageManager?.getApplicationInfo(packageName, 0)?.category }.getOrNull() == ApplicationInfo.CATEGORY_AUDIO
}

internal fun <T : Any, K> shortVideoPauseTargets(
    active: List<T>, selected: T?, tokenOf: (T) -> K, isActiveMusic: (T) -> Boolean,
): List<T> = (active + listOfNotNull(selected)).distinctBy(tokenOf).filter(isActiveMusic)

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
    val album: String = "",
    val playbackStatus: Int = 0,
    val mediaDetails: MediaPlaybackDetails = MediaPlaybackDetails(),
)

data class MediaPlaybackDetails(
    val itemId: Long? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val genre: String = "",
    val composer: String = "",
    val liked: Boolean? = null,
    val queueIndex: Int? = null,
    val queueCount: Int? = null,
    val speed: Float? = null,
)

class MusicNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        if (shouldListen()) {
            MusicBridge.attach(this)
            replayNavigation()
        }
        else pauseListening()
    }

    override fun onNotificationPosted(notification: android.service.notification.StatusBarNotification?) {
        if (!shouldListen()) return
        ensureAttached()
        notification?.let {
            MusicBridge.onNotificationChanged(it.packageName)
            io.github.hypercar.navigation.AmapNavigation.posted(it)
        }
    }

    override fun onNotificationRemoved(notification: android.service.notification.StatusBarNotification?) {
        if (!shouldListen()) return
        ensureAttached()
        notification?.let {
            MusicBridge.onNotificationChanged(it.packageName)
            io.github.hypercar.navigation.AmapNavigation.removed(it)
        }
    }

    private fun replayNavigation() {
        runCatching { activeNotifications?.forEach { io.github.hypercar.navigation.AmapNavigation.posted(it) } }
    }

    private fun ensureAttached() {
        if (!MusicBridge.isAttachedTo(this)) MusicBridge.attach(this)
    }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        writer.println("listenerConnected=${connected === this} observing=${shouldListen()}")
        writer.println(MusicBridge.diagnostics())
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        if (MusicBridge.isAttachedTo(this)) MusicBridge.detach()
        super.onDestroy()
    }

    override fun onListenerDisconnected() {
        io.github.hypercar.navigation.AmapNavigation.clear()
        if (connected === this) connected = null
        if (MusicBridge.isAttachedTo(this)) MusicBridge.detach()
        super.onListenerDisconnected()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        // Keep the system-granted listener separate from optional media work. requestUnbind
        // during an idle callback can race requestRebind and leave a bound but idle service.
        private var connected: MusicNotificationListener? = null

        private fun shouldListen() = HyperCarRepo.automaticConnectionAllowed ||
            HyperCarRepo.hasCurrentSession

        private fun pauseListening() {
            val listener = connected ?: return
            if (MusicBridge.isAttachedTo(listener)) {
                MusicBridge.detach("连接监听已暂停")
                HyperCarRepo.log("MUSIC: 无活动连接，暂停媒体订阅，保留系统通知绑定供下次恢复")
            }
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
                } else if (connected != null) {
                    connected?.ensureAttached()
                    connected?.replayNavigation()
                } else if (MusicBridge.hasAccess(app)) {
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
    val state: StateFlow<NowPlaying> = _state

    private var service: NotificationListenerService? = null
    private var controller: MediaController? = null
    // Main-thread snapshots. Rendering and the ticker never query a remote player.
    private data class SessionSnapshot(
        val controller: MediaController?,
        val metadata: MediaMetadata?,
        val playback: PlaybackState?,
        val ratingType: Int,
        val count: Int,
        val denied: Boolean = false,
        val queueIds: List<Long>? = null,
    )
    private var metadata: MediaMetadata? = null
    private var playback: PlaybackState? = null
    private var queueIds: List<Long>? = null
    private var ratingType = Rating.RATING_NONE
    @Volatile private var sessionGeneration = 0L
    private val snapshotEpoch = MusicSnapshotEpoch()
    private var lastRefreshAt = 0L
    private var callback: MediaController.Callback? = null
    private var callbackController: MediaController? = null
    private var callbackRegistered = false
    @Volatile internal var audioListenerSnapshot = "listener=false callback=false target=none state=null"
        private set

    private var observedSelectionRevision = -1L
    @Volatile private var observedPlaying: ReceiverPauseTarget<MediaController, android.media.session.MediaSession.Token>? = null

    private fun recordAudioListener(event: String, force: Boolean = false) {
        val playing = controller?.takeIf { service != null && callbackRegistered && !permissionDenied &&
            playback?.state == PlaybackState.STATE_PLAYING }
        val revision = packageSwitchRevision.get()
        val previous = observedPlaying
        if (playing == null || revision != observedSelectionRevision) observedPlaying = null
        else if (previous?.player !== playing || previous.switchRevision != revision) {
            observedPlaying = ReceiverPauseTarget(playing, playing.sessionToken, sessionGeneration, revision)
        }
        val snapshot = "listener=${service != null} callback=$callbackRegistered target=${controller?.packageName ?: "none"} state=${playback?.state} denied=$permissionDenied generation=$sessionGeneration"
        val changed = snapshot != audioListenerSnapshot
        audioListenerSnapshot = snapshot
        if (carConnected && (changed || force)) HyperCarRepo.log("AUDIO-PLAYER: $event $snapshot")
    }
    private val sessionQueries = musicQuery<SessionSnapshot>("music-session-query")
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
    private var playbackEngine = 0L
    private val carConnected get() = playbackEngine != 0L
    private var carAudioReady = false
    private var playbackPrefs: android.content.SharedPreferences? = null
    private var manualPauseRequested = false
    /** 用户在音乐卡片里选定的 App；有它的会话时优先显示它，而不是按“正在播放”抢焦点。 */
    private val _preferredPlayer = MutableStateFlow<String?>(null)
    internal val preferredPlayer: StateFlow<String?> = _preferredPlayer
    private var preferredPackage: String?
        get() = _preferredPlayer.value
        set(value) { _preferredPlayer.value = value }
    private val packageSwitchRevision = java.util.concurrent.atomic.AtomicLong()
    private var autoPlayPending = false
    private var autoPlayDeadline = 0L
    private var receiverAudioTake: (() -> Boolean)? = null

    /** A permanent receiver Take cancels pending connect autoplay, including a late audio-ready callback. */
    internal fun onReceiverAudioTake(context: Context, ticket: EngineCallbackGate.AudioTake, connected: () -> Boolean) {
        // Capture on receipt, before posting or querying Android. Never substitute a later session.
        val locked = observedPlaying
        val current = { ticket.isCurrent() && connected() }
        onMusicThread(wait = false) {
            if (!current()) return@onMusicThread
            receiverAudioTake = current
            autoPlayPending = false
            autoPlayDeadline = 0L
            resumePackage = null
            context.getSharedPreferences("car_playback", Context.MODE_PRIVATE).edit().remove("resume_package").apply()
            if (locked == null || observedPlaying !== locked || locked.switchRevision != packageSwitchRevision.get()) {
                HyperCarRepo.log("AUDIO-PLAYER: receiverTake skipped; no confirmed playing session at Take")
                return@onMusicThread
            }
            val preferred = preferredPackage
            launchScope.launch {
                val result = runCatching {
                    val manager = context.getSystemService(MediaSessionManager::class.java)
                    val sessions = manager.getActiveSessions(ComponentName(context, MusicNotificationListener::class.java))
                    locked.activeIn(sessions) { it.sessionToken }?.isPlayingPhoneMusic(context, preferred) == true
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val outcome = when {
                        result.isFailure -> "sessions unavailable: ${result.exceptionOrNull()?.javaClass?.simpleName}"
                        result.getOrNull() != true -> "locked player inactive"
                        else -> runCatching {
                            val player = locked.player
                            if (!player.isPlayingPhoneMusic(context, preferred)) return@runCatching "target no longer playing"
                            val controls = player.transportControls
                            val sent = ticket.dispatch(valid = {
                                connected() && receiverAudioTake === current && observedPlaying === locked &&
                                    locked.matches(controller?.sessionToken, sessionGeneration, packageSwitchRevision.get())
                            }, pause = { controls.pause() })
                            if (sent) "pause dispatched target=${player.packageName}" else "stale Take or player"
                        }.getOrElse { "pause failed: ${it.javaClass.simpleName}" }
                    }
                    HyperCarRepo.log("AUDIO-PLAYER: receiverTake $outcome")
                }
            }
        }
    }

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

    fun onCarConnected(owner: Long) = onMusicThread {
        if (playbackEngine != owner) {
            playbackEngine = owner
            controller?.let { player ->
                // A retained player token can span two car sessions. Seed the new listener
                // from the player without interpreting its initial state as a play action.
                playback = runCatching { player.playbackState }.getOrNull()
                snapshotEpoch.playbackChanged()
                registerPlayerCallback(player)
            }
        }
        recordAudioListener("connected", force = true)
        mediaDiagnosticCount = 0
        carAudioReady = false
        autoPlayPending = CarUiSettings.autoPlayOnConnect && receiverAudioTake?.invoke() != true
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
     * 自动播放时我们把它放在不可见虚显上（不抢前台），但用户想调整播放器/音效时必须能在手机上操作。
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
        receiverAudioTake = null
        packageSwitchRevision.incrementAndGet()
        if (!carConnected) return@onMusicThread
        playbackEngine = 0L
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
        if (!carConnected || !carAudioReady || receiverAudioTake?.invoke() == true) return
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

    private fun registerPlayerCallback(player: MediaController) {
        unregisterPlayerCallback()
        val context = service ?: return
        val listener = sessionCallback(playbackEngine)
        callback = listener
        // Each registration gets its own Binder callback endpoint even when the token is reused.
        callbackRegistered = runCatching {
            MediaController(context, player.sessionToken).also {
                callbackController = it
                it.registerCallback(listener, main)
            }
        }.isSuccess
    }

    private fun unregisterPlayerCallback() {
        callback?.let { old -> runCatching { callbackController?.unregisterCallback(old) } }
        callback = null
        callbackController = null
        callbackRegistered = false
    }

    private fun sessionCallback(owner: Long) = object : MediaController.Callback() {
        private val audioResume = PlaybackAudioResume(playback?.state)
        override fun onMetadataChanged(value: MediaMetadata?) {
            if (callback !== this) return
            snapshotEpoch.metadataChanged()
            metadata = value
            publish()
        }
        override fun onPlaybackStateChanged(value: PlaybackState?) {
            if (callback !== this) return
            snapshotEpoch.playbackChanged()
            val resumed = audioResume.onState(value?.state)
            playback = value
            recordAudioListener("stateCallback resumeIntent=$resumed", force = resumed)
            if (resumed) requestPlaybackAudio("playerState", owner)
            if (value?.state == PlaybackState.STATE_PAUSED || value?.state == PlaybackState.STATE_STOPPED) {
                manualPauseRequested = false
            }
            publish()
        }
        override fun onSessionDestroyed() {
            if (callback !== this) return
            clearController()
            recordAudioListener("sessionDestroyed")
            publish()
            choose()
        }
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
            if (callback !== this) return
            snapshotEpoch.queueChanged()
            queueIds = queue?.map { it.queueId }
            publish()
        }
    }

    private fun clearController() {
        observedPlaying = null
        sessionGeneration++
        snapshotEpoch.sessionChanged()
        unregisterPlayerCallback()
        controller = null
        metadata = null
        playback = null
        queueIds = null
        ratingType = Rating.RATING_NONE
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (controller != null) publish()
            if (CarUiCaster.running && SystemClock.elapsedRealtime() - lastRefreshAt >= 5_000) choose()
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
    fun selectPackage(context: Context, packageName: String, launchOnPhone: Boolean = true) {
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
        val intent = if (launchOnPhone) context.packageManager.getLaunchIntentForPackage(packageName) else null
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
        if (this.service === service) return
        if (this.service != null) detach()
        HyperCarRepo.log("MUSIC: 通知监听已接入（attach）")
        this.service = service
        playbackPrefs = service.getSharedPreferences("car_playback", Context.MODE_PRIVATE)
        resumePackage = playbackPrefs?.getString("resume_package", null)
        preferredPackage = CarUiSettings.defaultMusicPackage.takeIf { it.isNotBlank() }
            ?: playbackPrefs?.getString("preferred_package", null)
        permissionDenied = false
        recordAudioListener("attached")
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
        recordAudioListener("detached")
        _state.value = NowPlaying(reason = reason)
        HyperCarRepo.log("MUSIC: listener disconnected")
    }

    private fun choose(provided: List<MediaController>? = null) {
        val s = service ?: return
        lastRefreshAt = SystemClock.elapsedRealtime()
        val epoch = snapshotEpoch.capture()
        val selectionRevision = packageSwitchRevision.get()
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
            val players = sessions.filterNot { ShortVideoApps.isSupported(it.packageName) }
            val target = players.firstOrNull { it.packageName == preferred }
                ?: players.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
                ?: players.firstOrNull { session ->
                    session.metadata?.let { musicTrackText(it::getString).title.isNotBlank() } == true
                }
                ?: players.firstOrNull()
            SessionSnapshot(target, target?.metadata, target?.playbackState,
                target?.ratingType ?: Rating.RATING_NONE, sessions.size, denied,
                runCatching { target?.queue?.map { it.queueId } }.getOrNull())
        }, { snapshot ->
            if (service !== s) return@submit
            observedSelectionRevision = selectionRevision
            permissionDenied = snapshot.denied
            val target = snapshot.controller
            // Framework creates fresh wrappers for the same token; don't replace callbacks
            // or overwrite newer callback values with a delayed discovery snapshot.
            if (target?.sessionToken != controller?.sessionToken) {
                clearController()
                controller = target
                metadata = snapshot.metadata
                playback = snapshot.playback
                queueIds = snapshot.queueIds
                ratingType = snapshot.ratingType
                if (target != null) registerPlayerCallback(target)
                val key = "${target?.packageName ?: "-"}|${snapshot.count}"
                if (CarUiCaster.running && key != lastChoiceKey) {
                    lastChoiceKey = key
                    HyperCarRepo.log("MUSIC: 选中 ${target?.packageName ?: "-"}（共 ${snapshot.count} 个会话）")
                }
            } else if (target != null) {
                // Notifications/refresh can repair a missing player callback, but a delayed
                // discovery snapshot must not replace newer metadata or playback callbacks.
                if (snapshotEpoch.acceptsMetadata(epoch)) metadata = snapshot.metadata
                if (snapshotEpoch.acceptsPlayback(epoch)) playback = snapshot.playback
                if (snapshotEpoch.acceptsQueue(epoch)) queueIds = snapshot.queueIds
                ratingType = snapshot.ratingType
                if (!callbackRegistered) registerPlayerCallback(target)
            }
            recordAudioListener("sessionSnapshot")
            publish()
        })
    }

    fun onNotificationChanged(packageName: String) {
        main.post {
            if (controller?.packageName == packageName || controller == null) {
                MusicArtwork.invalidate()
                choose()
            }
        }
    }

    internal fun isAttachedTo(listener: NotificationListenerService): Boolean = service === listener

    internal fun diagnostics(): String = "musicAttached=${service != null} selected=${controller?.packageName} " +
        "metadata=${metadata != null} playback=${playback?.state} art=${_state.value.albumArt?.let { "${it.width}x${it.height}" }} " +
        "playing=${_state.value.playing} sessionGeneration=$sessionGeneration"

    private fun publish() {
        val c = controller
        if (c == null) {
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
        val label = runCatching {
            val ai = ctx!!.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
        val track = musicTrackText { md?.getString(it) }
        val art = MusicArtwork.resolve(ctx, pkg, md, track) { publish() }
        val rating = md?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.takeIf { it.isRated }
        val likeAction = favoriteAction(ps)
        val actions = ps?.actions ?: 0L
        val itemId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }?.let {
            java.util.UUID.nameUUIDFromBytes("$pkg\u0000$it".encodeToByteArray()).mostSignificantBits
        }
        fun trackValue(key: String) = md?.getLong(key)?.takeIf { it in 1..65535 }?.toInt()
        val liked = when (rating?.ratingStyle) {
            Rating.RATING_HEART -> rating.hasHeart()
            Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
            else -> likeAction?.name?.toString()?.let(FavoriteActionLabels::liked)
        }
        val activeQueueId = ps?.activeQueueItemId?.takeUnless { it == android.media.session.MediaSession.QueueItem.UNKNOWN_ID.toLong() }
        _state.value = NowPlaying(
            available = true,
            packageName = pkg,
            appLabel = label,
            title = track.title,
            artist = track.artist,

            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playbackStatus = when (ps?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> 1
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 2
                else -> 0
            },
            albumArt = art,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            positionMs = positionOf(ps),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            liked = liked ?: false,
            mediaDetails = MediaPlaybackDetails(itemId = itemId,
                trackNumber = trackValue(MediaMetadata.METADATA_KEY_TRACK_NUMBER),
                trackCount = trackValue(MediaMetadata.METADATA_KEY_NUM_TRACKS),
                discNumber = trackValue(MediaMetadata.METADATA_KEY_DISC_NUMBER),
                genre = md?.getString(MediaMetadata.METADATA_KEY_GENRE).orEmpty(),
                composer = md?.getString(MediaMetadata.METADATA_KEY_COMPOSER).orEmpty(),
                liked = liked, speed = ps?.playbackSpeed, queueCount = queueIds?.size,
                queueIndex = activeQueueId?.let { queueIds?.indexOf(it) }?.takeIf { it >= 0 }),
            canLike = likeAction != null || (actions and PlaybackState.ACTION_SET_RATING != 0L &&
                ratingType in listOf(Rating.RATING_HEART, Rating.RATING_THUMB_UP_DOWN)),
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

    private fun cancelAutoPlay() {
        autoPlayPending = false
        autoPlayDeadline = 0L
        // Explicit user playback controls supersede the saved automatic-resume request.
        resumePackage = null
        playbackPrefs?.edit()?.remove("resume_package")?.apply()
    }

    fun pauseForShortVideo() = onMusicThread {
        cancelAutoPlay()
        val s = service
        val sessions = runCatching {
            s?.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(
                ComponentName(s, MusicNotificationListener::class.java),
            )
        }.getOrNull().orEmpty()
        val selected = controller
        val players = shortVideoPauseTargets(sessions, selected, MediaController::getSessionToken) { player ->
            val state = player.playbackState?.state ?: playback?.state.takeIf {
                player.sessionToken == selected?.sessionToken
            }
            (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING) && player.isLocalMusic(s, preferredPackage)
        }
        for (player in players) {
            runCatching { player.transportControls.pause() }
                .onSuccess { HyperCarRepo.log("MUSIC: 打开短视频，已请求暂停 ${player.packageName}") }
                .onFailure {
                    HyperCarRepo.log("MUSIC: 打开短视频时暂停失败 ${it.javaClass.simpleName}")
                }
        }
    }

    fun playPause() {
        cancelAutoPlay()
        val c = controller ?: return
        if (playback?.state == PlaybackState.STATE_PLAYING) {
            manualPauseRequested = true
            c.transportControls.pause()
        } else {
            manualPauseRequested = false
            requestPlaybackAudio("playPause")
            c.transportControls.play()
        }
    }

    private fun requestPlaybackAudio(source: String, owner: Long = playbackEngine) {
        if (!carConnected) {
            HyperCarRepo.log("AUDIO-PLAYER: claim source=$source skipped=disconnected $audioListenerSnapshot")
            return
        }
        runCatching { HyperCarRepo.requestPlaybackAudio(owner) }
            .onSuccess { HyperCarRepo.log("AUDIO-PLAYER: claim source=$source queued=$it $audioListenerSnapshot") }
            .onFailure { HyperCarRepo.log("AUDIO-PLAYER: claim source=$source failed=${it.javaClass.simpleName} $audioListenerSnapshot") }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    private var mediaDiagnosticCount = 0
    private var mediaDiagnosticId = 0L
    fun onCarMediaKey(context: Context, usage: Int, current: () -> Boolean = { true }) = onMusicThread(wait = false) {
        if (!current()) return@onMusicThread
        val diagnosticId = ++mediaDiagnosticId
        val trace = mediaDiagnosticCount++ < 120
        if (trace) HyperCarRepo.log("MEDIA-KEY: rx=$diagnosticId usage=0x${usage.toString(16)} connected=$carConnected audioReady=$carAudioReady listener=${service != null} denied=$permissionDenied target=${controller?.packageName ?: "none"} state=${playback?.state} actions=${playback?.actions}")
        if (!carConnected || CarMediaKeys.keyCode(usage) == 0) {
            if (trace) HyperCarRepo.log("MEDIA-KEY: drop=$diagnosticId reason=${if (!carConnected) "not-connected" else "unsupported-usage"}")
            return@onMusicThread
        }
        // Explicit steering-wheel playback supersedes saved automatic resume requests.
        autoPlayPending = false
        resumePackage = null
        playbackPrefs?.edit()?.remove("resume_package")?.apply()
        if (CarMediaKeys.requestsPlayback(usage, playback?.state)) requestPlaybackAudio("hid-0x${usage.toString(16)}")
        runCatching {
            val target = controller
            val beforeMetadata = metadata
            val beforeState = playback?.state
            val generation = sessionGeneration
            val now = SystemClock.uptimeMillis()
            fun event(code: Int, action: Int) = android.view.KeyEvent(now, now, action, code, 0)
            val route = CarMediaKeys.dispatch(usage, session = { code ->
                if (target == null) false else {
                    val down = target.dispatchMediaButtonEvent(event(code, android.view.KeyEvent.ACTION_DOWN))
                    val up = target.dispatchMediaButtonEvent(event(code, android.view.KeyEvent.ACTION_UP))
                    if (trace) HyperCarRepo.log("MEDIA-KEY: dispatch=$diagnosticId downAccepted=$down upAccepted=$up")
                    down || up
                }
            }, system = { code ->
                val audio = context.getSystemService(android.media.AudioManager::class.java) ?: error("AudioManager unavailable")
                audio.dispatchMediaKeyEvent(event(code, android.view.KeyEvent.ACTION_DOWN))
                audio.dispatchMediaKeyEvent(event(code, android.view.KeyEvent.ACTION_UP))
            })
            if (trace) main.postDelayed({
                if (carConnected && mediaDiagnosticId == diagnosticId) {
                    HyperCarRepo.log("MEDIA-KEY: observe=$diagnosticId after=1000ms sameSession=${sessionGeneration == generation && controller === target} metadataChanged=${metadata !== beforeMetadata} stateBefore=$beforeState stateNow=${playback?.state}; cached callbacks only, not execution confirmation")
                }
            }, 1000)
            HyperCarRepo.log("MEDIA-KEY: id=$diagnosticId usage=0x${usage.toString(16)} key=${CarMediaKeys.keyCode(usage)} route=$route target=${target?.packageName ?: "system"}（派发不代表播放器已执行）")
        }.onFailure { HyperCarRepo.log("MEDIA-KEY: 派发失败 usage=0x${usage.toString(16)}: ${it.message}") }
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

    fun refresh() = choose()
}
