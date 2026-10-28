package io.github.hypercar.carui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
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
)

class MusicNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MusicBridge.attach(this)
    }

    override fun onListenerDisconnected() {
        MusicBridge.detach()
        super.onListenerDisconnected()
    }
}

object MusicBridge {

    private const val TAG = "HyperCar-Music"
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private var service: NotificationListenerService? = null
    private var controller: MediaController? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var permissionDenied = false
    private var resumePackage: String? = null
    private var carConnected = false
    private var carAudioReady = false
    private var playbackPrefs: android.content.SharedPreferences? = null
    private var manualPauseRequested = false
    /** 用户在音乐卡片里选定的 App；有它的会话时优先显示它，而不是按“正在播放”抢焦点。 */
    private var preferredPackage: String? = null

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
    }

    fun onCarAudioReady() = onMusicThread(wait = false) {
        if (carConnected) {
            carAudioReady = true
            resumeAfterReconnect()
        }
    }

    fun onCarDisconnected() = onMusicThread {
        if (!carConnected) return@onMusicThread
        carConnected = false
        carAudioReady = false
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
            playbackPrefs?.edit()?.remove("resume_package")?.apply()
            HyperCarRepo.log("MUSIC: 车机音频已就绪，恢复 $pkg")
        }.onFailure { HyperCarRepo.log("MUSIC: 恢复播放失败 ${it.message}") }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == PlaybackState.STATE_PAUSED || state?.state == PlaybackState.STATE_STOPPED) {
                manualPauseRequested = false
            }
            publish()
        }
        override fun onSessionDestroyed() {
            controller = null
            choose()
        }
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
        // 切换前先停掉/关掉上一个 App（即使它已暂停，也一并清掉，避免后台残留）。
        val previous = controller?.packageName
        val previousPlaying = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (!previous.isNullOrBlank() && previous != packageName) {
            if (previousPlaying) {
                runCatching { controller?.transportControls?.pause() }
                HyperCarRepo.log("MUSIC: 停止上一个播放器 $previous")
            }
            closePackage(context, previous)
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
            Thread({
                runBlocking { runCatching { ShizukuTools.exec(cmd, 4_000) } }
            }, "music-launch").apply { isDaemon = true }.start()
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

    /** 关闭上一个音乐 App：优先 shell force-stop（与 MIUI 后台限制无关）。 */
    private fun closePackage(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        if (ShizukuTools.isReady) {
            Thread({
                // 给 pause 一点时间落到 MediaSession，再杀进程，避免它又被通知栏拉起来。
                Thread.sleep(250)
                runBlocking { runCatching { ShizukuTools.exec("am force-stop $packageName", 4_000) } }
            }, "music-close").apply { isDaemon = true }.start()
        } else {
            runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                    .killBackgroundProcesses(packageName)
            }
        }
    }

    fun attach(service: NotificationListenerService) {
        this.service = service
        playbackPrefs = service.getSharedPreferences("car_playback", Context.MODE_PRIVATE)
        resumePackage = playbackPrefs?.getString("resume_package", null)
        preferredPackage = playbackPrefs?.getString("preferred_package", null)
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

    fun detach() {
        main.removeCallbacks(ticker)
        runCatching { controller?.unregisterCallback(callback) }
        controller = null
        service = null
        _state.value = NowPlaying(reason = "通知使用权已断开")
        HyperCarRepo.log("MUSIC: listener disconnected")
    }

    private fun choose(provided: List<MediaController>? = null) {
        val s = service ?: return
        val msm = s.getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(s, MusicNotificationListener::class.java)
        val sessions = provided ?: runCatching { msm.getActiveSessions(component) }.getOrElse {
            permissionDenied = it is SecurityException
            HyperCarRepo.log("MUSIC: getActiveSessions 失败: ${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
        // 优先正在播放的；其次有标题的；再退回第一个（避免把纯语音/系统会话顶上来）
        val playing = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null
        }
        val withTitle = sessions.firstOrNull {
            !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        }
        val target = playing ?: withTitle ?: sessions.firstOrNull()
        if (target !== controller) {
            runCatching { controller?.unregisterCallback(callback) }
            controller = target
            runCatching { target?.registerCallback(callback, main) }
            HyperCarRepo.log("MUSIC: 选中 ${target?.packageName ?: "-"}（共 ${sessions.size} 个会话）")
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = NowPlaying(
                reason = when {
                    permissionDenied -> "请在系统设置里授予「通知使用权」"
                    else -> "未在播放（打开 QQ音乐 / 汽水音乐 任意播放一首）"
                },
            )
            return
        }
        val md = c.metadata
        val ps = c.playbackState
        val pkg = c.packageName
        val ctx = service
        val label = runCatching {
            val ai = ctx!!.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        _state.value = NowPlaying(
            available = true,
            packageName = pkg,
            appLabel = label,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            albumArt = art,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            positionMs = positionOf(ps),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
    }

    private fun positionOf(ps: PlaybackState?): Long {
        if (ps == null) return 0L
        var pos = ps.position
        if (ps.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
            if (elapsed in 0..10_000) pos += elapsed
        }
        return pos.coerceAtLeast(0L)
    }

    fun playPause() {
        // Explicit user playback controls supersede the saved automatic-resume request.
        resumePackage = null
        playbackPrefs?.edit()?.remove("resume_package")?.apply()
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
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

    fun refresh() = choose()
}
