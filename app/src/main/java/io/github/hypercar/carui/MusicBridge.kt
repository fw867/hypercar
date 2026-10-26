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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            choose()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (controller != null) publish()
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

    fun attach(service: NotificationListenerService) {
        this.service = service
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
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
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
