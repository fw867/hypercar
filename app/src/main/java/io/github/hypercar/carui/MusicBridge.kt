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
        MusicBridge.attach(this)
    }

    override fun onNotificationPosted(notification: android.service.notification.StatusBarNotification?) {
        notification?.let { MusicBridge.onNotificationChanged(it.packageName) }
    }

    override fun onNotificationRemoved(notification: android.service.notification.StatusBarNotification?) {
        notification?.let { MusicBridge.onNotificationChanged(it.packageName) }
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
    /** 歌词缓存（2026-10-31）：只有 QQ 音乐通过第三方 API 能拿到 LRC。 */
    val state: StateFlow<NowPlaying> = _state

    private var service: NotificationListenerService? = null
    private var controller: MediaController? = null
    private var compatController: MediaControllerCompat? = null
    private val compatCallback = object : MediaControllerCompat.Callback() {
        override fun onSessionReady() = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()
        override fun onShuffleModeChanged(shuffleMode: Int) = publish()
    }
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    /** 上一次「选中」日志的 key，避免断连后 session 抖动导致刷屏（2026-10-31）。 */
    private var lastChoiceKey = ""
    private var permissionDenied = false
    private var resumePackage: String? = null
    private var carConnected = false
    private var carAudioReady = false
    private var playbackPrefs: android.content.SharedPreferences? = null
    private var manualPauseRequested = false
    /** 用户在音乐卡片里选定的 App；有它的会话时优先显示它，而不是按“正在播放”抢焦点。 */
    private var preferredPackage: String? = null
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
        if (carConnected) {
            carAudioReady = true
            if (autoPlayPending && CarUiSettings.autoPlayOnConnect && resumePackage == null) {
                val pkg = CarUiSettings.defaultMusicPackage.takeIf { it.isNotBlank() }
                    ?: preferredPackage ?: controller?.packageName
                if (pkg != null) {
                    resumePackage = pkg
                    autoPlayDeadline = SystemClock.elapsedRealtime() + 15_000
                    val ctx = service
                    if (ctx != null && controller?.packageName != pkg) selectPackage(ctx, pkg)
                    HyperCarRepo.log("MUSIC: 音频就绪，准备自动播放 $pkg")
                }
            }
            autoPlayPending = false
            resumeAfterReconnect()
        }
    }

    fun onCarDisconnected() = onMusicThread {
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

    private val callback = object : MediaController.Callback() {
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == PlaybackState.STATE_PAUSED || state?.state == PlaybackState.STATE_STOPPED) {
                manualPauseRequested = false
            }
            publish()
        }
        override fun onSessionDestroyed() {
            runCatching { compatController?.unregisterCallback(compatCallback) }
            compatController = null
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

    /** 设置里改默认音乐 App：只改偏好，不立即播放。 */
    fun setDefaultPackage(packageName: String) {
        preferredPackage = packageName.takeIf { it.isNotBlank() }
        playbackPrefs?.edit()?.putString("preferred_package", packageName)?.apply()
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

    fun detach() {
        QqMusicAdapter.close()
        MusicArtwork.clear()
        main.removeCallbacks(ticker)
        runCatching { controller?.unregisterCallback(callback) }
        runCatching { compatController?.unregisterCallback(compatCallback) }
        compatController = null
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
        val target = sessions.firstOrNull { it.packageName == preferredPackage }
            ?: playing ?: withTitle ?: sessions.firstOrNull()
        if (target !== controller) {
            runCatching { controller?.unregisterCallback(callback) }
            runCatching { compatController?.unregisterCallback(compatCallback) }
            compatController = null
            controller = target
            if (target != null) {
                compatController = runCatching {
                    MediaControllerCompat(s, MediaSessionCompat.Token.fromToken(target.sessionToken)!!)
                        .also { it.registerCallback(compatCallback, main) }
                }.getOrNull()
            }
            runCatching { target?.registerCallback(callback, main) }
            // 只在「车机界面在跑」且「选择结果变了」时记录：断连后 session 会反复变化，
            // 之前每变一次就打一行，后台日志被刷满（2026-10-31）。
            val key = "${target?.packageName ?: "-"}|${sessions.size}"
            if (CarUiCaster.running && key != lastChoiceKey) {
                lastChoiceKey = key
                HyperCarRepo.log("MUSIC: 选中 ${target?.packageName ?: "-"}（共 ${sessions.size} 个会话）")
            }
        }
        publish()
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
        val md = c.metadata
        val ps = c.playbackState
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
                c.ratingType in listOf(Rating.RATING_HEART, Rating.RATING_THUMB_UP_DOWN)),
            repeatMode = compatController?.repeatMode ?: PlaybackStateCompat.REPEAT_MODE_INVALID,
            shuffleMode = compatController?.shuffleMode ?: PlaybackStateCompat.SHUFFLE_MODE_INVALID,
            canRepeat = actions and PlaybackStateCompat.ACTION_SET_REPEAT_MODE != 0L,
            canShuffle = actions and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE != 0L,
            queue = if (qq != null && qq.songs.isNotEmpty()) qq.songs.mapIndexed { index, song ->
                MusicQueueEntry(index.toLong(), song.title, song.artist, song.id)
            } else c.queue.orEmpty().map { MusicQueueEntry(it.queueId,
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
            if (elapsed in 0..10_000) pos += elapsed
        }
        return pos.coerceAtLeast(0L)
    }

    fun playPause() {
        autoPlayPending = false
        autoPlayDeadline = 0L
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

    private fun favoriteAction(ps: PlaybackState?): PlaybackState.CustomAction? =
        ps?.customActions?.firstOrNull {
            FavoriteActionLabels.supported(it.name.toString())
        }

    fun toggleFavorite() = onMusicThread {
        val c = controller ?: return@onMusicThread
        if (!_state.value.canLike) return@onMusicThread
        runCatching {
            val custom = favoriteAction(c.playbackState)
            if (custom != null) c.transportControls.sendCustomAction(custom.action, custom.extras)
            else when (c.ratingType) {
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
        runCatching {
            if (state.canShuffle) c.transportControls.setShuffleMode(
                if (mode == MusicPlaybackMode.SHUFFLE) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
            if (state.canRepeat) c.transportControls.setRepeatMode(when (mode) {
                MusicPlaybackMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
                MusicPlaybackMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        }.onFailure { HyperCarRepo.log("MUSIC: 播放模式设置失败 ${it.message}") }
    }

    fun playQueueItem(packageName: String, id: Long, sourceId: String? = null) = onMusicThread {
        val c = controller ?: return@onMusicThread
        if (c.packageName == packageName && packageName == QqMusicAdapter.PACKAGE && sourceId != null) {
            QqMusicAdapter.playSong(sourceId); return@onMusicThread
        }
        if (c.packageName != packageName || (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM == 0L) return@onMusicThread
        if (c.queue.orEmpty().none { it.queueId == id }) return@onMusicThread
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
