package io.github.hypercar.yadea

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import io.github.hypercar.R
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.CarWithEntryGate
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.session.StartupRequirements
import io.github.hypercar.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class YadeaSource(val title: String) {
    CarWith("CarWith"), SystemCarLife("CarLife+");

    fun installed(context: Context): Boolean = when (this) {
        CarWith -> StartupRequirements.carWithInstalled(context)
        SystemCarLife -> StartupRequirements.systemCarLifePackage(context) != null
    }
}

internal data class YadeaSessionState(
    val active: Boolean = false,
    val status: String = "手机接入仪表网络后，输入仪表 IP。",
    val display: YadeaDisplay? = null,
    val requestedSize: Pair<Int, Int>? = null,
    val sourceSize: Pair<Int, Int>? = null,
    val preview: Bitmap? = null,
    val route: String = "等待音频",
    val routeId: Int? = null,
)

internal data class YadeaAudioOutput(val id: Int, val label: String)

internal fun yadeaAudioOutputs(context: Context): List<YadeaAudioOutput> = runCatching {
    val audio = context.getSystemService(AudioManager::class.java)
    audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { device ->
        val label = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> device.productName.toString().ifBlank { "蓝牙媒体设备" }
            else -> null
        }
        label?.let { YadeaAudioOutput(device.id, it) }
    }
}.getOrDefault(emptyList())

class YadeaProjectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Session? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            session?.requestStop() ?: stopSelf()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        val host = intent?.getStringExtra(EXTRA_HOST)
        val source = YadeaSource.entries.firstOrNull { it.name == intent?.getStringExtra(EXTRA_SOURCE) }
        if (host == null || source == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val route = intent.getIntExtra(EXTRA_ROUTE, -1).takeIf { it >= 0 }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (error: RuntimeException) {
            state.value = YadeaSessionState(status = "无法启动雅迪前台服务：${error.localizedMessage}")
            stopSelf()
            return START_NOT_STICKY
        }
        val next = Session(host, source, route)
        session = next
        current.set(next)
        next.update { YadeaSessionState(active = true, status = "正在连接雅迪仪表…", routeId = route) }
        scope.launch {
            val outcome = next.run()
            withContext(Dispatchers.Main.immediate) {
                if (session === next) {
                    session = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                if (current.compareAndSet(next, null)) {
                    state.value = YadeaSessionState(status = outcome)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val running = session
        if (running == null) scope.cancel() else running.requestStop(immediate = true)
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "雅迪仪表投屏", NotificationManager.IMPORTANCE_LOW)
        )
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, YadeaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), pendingFlags)
        val stop = PendingIntent.getService(this, NOTIFICATION_ID, Intent(this, javaClass)
            .setAction(ACTION_STOP), pendingFlags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("雅迪仪表投屏")
            .setContentText("点此返回手机预览")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止投屏", stop).build())
            .build()
    }

    private inner class Session(private val host: String, private val source: YadeaSource, route: Int?) {
        private val stopping = AtomicBoolean(false)
        private val connected = AtomicBoolean(false)
        private val jpeg = AtomicReference<ByteArray?>()
        private val lastFrame = AtomicLong()
        private val failure = AtomicReference<Throwable?>()
        private val transport = YadeaTransport()
        private val entry = CarWithEntryGate(RustCoreBridge::nativeStopCarWith)
        private val requestedRoute = AtomicReference(route)
        private val sourceSize = AtomicReference<Pair<Int, Int>?>()
        private val previewReady = AtomicBoolean(false)
        private val launchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        @Volatile private var owner = 0L
        @Volatile private var audio: YadeaAudio? = null

        fun update(transform: (YadeaSessionState) -> YadeaSessionState) {
            state.update { if (current.get() === this) transform(it) else it }
        }

        fun requestStop(immediate: Boolean = false) {
            stopping.set(true)
            update { it.copy(status = "正在停止投屏…") }
            if (immediate || !connected.get()) transport.close()
        }

        fun setRoute(deviceId: Int?) {
            requestedRoute.set(deviceId)
            audio?.setPreferredDevice(deviceId)
            update { it.copy(routeId = deviceId) }
        }

        fun touch(action: Int, x: Float, y: Float): Boolean {
            val (width, height) = sourceSize.get() ?: return false
            if (stopping.get() || !previewReady.get()) return false
            val accepted = RustCoreBridge.nativeYadeaTouch(owner, action,
                (x.coerceIn(0f, 1f) * width).toInt().coerceAtMost(width - 1),
                (y.coerceIn(0f, 1f) * height).toInt().coerceAtMost(height - 1))
            if (accepted && action != MotionEvent.ACTION_MOVE) {
                android.util.Log.i("YadeaTouch", "owner=$owner ${MotionEvent.actionToString(action)} queued")
            }
            return accepted
        }

        suspend fun run(): String {
            var video: YadeaVideo? = null
            var wakeLock: PowerManager.WakeLock? = null
            var outcome = "投屏已停止"
            try {
                if (!source.installed(this@YadeaProjectionService)) error("本机未安装或未启用 ${source.title}")
                owner = HyperCarRepo.claimYadeaSource()
                if (owner == 0L) error("请先结束当前 CarPlay 会话")
                if (stopping.get()) return outcome
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperCar:YadeaProjection")
                    .apply { setReferenceCounted(false); acquire() }
                update { it.copy(status = "等待仪表协商尺寸和帧率…") }
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val network = instrumentNetwork(connectivity, host)
                    ?: error("找不到到达仪表 $host 的 Wi-Fi 网络，请先连接仪表 Wi-Fi")
                val display = transport.connect(host, network = network)
                connected.set(true)
                if (stopping.get()) return outcome
                // HyperCar's source canvas limit is independent of the instrument JPEG dimensions.
                val scale = minOf(1.0, 2560.0 / maxOf(display.width, display.height))
                val sourceWidth = ((display.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
                val sourceHeight = ((display.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
                val waiting = if (source == YadeaSource.SystemCarLife)
                    "请在手机车联中打开 CarLife+ 无线连接，然后返回此页。" else "正在启动 CarWith…"
                update { it.copy(status = waiting, display = display, requestedSize = sourceWidth to sourceHeight) }
                HyperCarRepo.log("YADEA: instrument=${display.width}x${display.height}@${display.fps} " +
                    "confirmed=${display.negotiated} format=${display.imageFormat} capabilities=${display.capabilities} " +
                    "request=${sourceWidth}x${sourceHeight} source=${source.title}")
                val player = YadeaAudio(this@YadeaProjectionService) { route -> update { it.copy(route = route) } }
                audio = player
                player.setPreferredDevice(requestedRoute.get())
                val decoder = YadeaVideo(display.width, display.height, display.fps,
                    onPreview = { bitmap ->
                        if (!stopping.get()) {
                            previewReady.set(true)
                            update { it.copy(preview = bitmap) }
                        }
                    },
                    onJpeg = { data -> if (!stopping.get()) jpeg.set(data) },
                    onError = { error -> failure.compareAndSet(null, error) },
                    requestKeyFrame = { if (!stopping.get()) RustCoreBridge.nativeYadeaRequestKeyFrame(owner) },
                )
                video = decoder
                entry.begin(owner)
                val deviceId = SettingsStore.manualBtMac ?: SettingsStore.stableDeviceId
                val config = JSONObject()
                    .put("device_id", deviceId)
                    .put("car_ip", "127.0.0.1").put("airplay_port", 0).put("identity_path", "")
                    .put("width", sourceWidth).put("height", sourceHeight).put("fps", display.fps)
                    .put("carlife_source", true).put("system_carlife_source", source == YadeaSource.SystemCarLife)
                    .put("hu_bt_address", deviceId).put("hu_bt_name", "HyperCar")
                    .put("hu_manufacturer", "HyperCar").put("hu_model", "HyperCar HU")
                    .put("carwith_cached_canvas", display.negotiated)
                    .put("carwith_cached_max_fps", display.fps)
                val listener = object : RustCoreBridge.YadeaSourceListener {
                    override fun onVideo(data: ByteArray, ptsUs: Long, codec: Int) {
                        if (!stopping.get()) decoder.offerVideo(data, ptsUs, codec)
                    }
                    override fun onSourceSize(width: Int, height: Int) {
                        if (stopping.get() || width < 2 || height < 2) return
                        val size = width to height
                        val previousSize = sourceSize.getAndSet(size)
                        if (previousSize != null && previousSize != size) {
                            previewReady.set(false)
                            jpeg.set(null)
                            update { it.copy(preview = null) }
                        }
                        decoder.setSourceSize(width, height)
                        update { it.copy(sourceSize = size) }
                        HyperCarRepo.log("YADEA: actual source=${width}x${height}")
                    }
                    override fun onSourceReset() {
                        sourceSize.set(null)
                        previewReady.set(false)
                        decoder.reset()
                        player.stop(false)
                        player.stop(true)
                        jpeg.set(null)
                        update { it.copy(status = waiting, preview = null, sourceSize = null) }
                    }
                    override fun onVideoDisconnected() {
                        previewReady.set(false)
                        decoder.reset()
                        sourceSize.get()?.let { (width, height) -> decoder.setSourceSize(width, height) }
                        jpeg.set(null)
                        update { it.copy(status = "导航画面已断开，正在等待恢复…", preview = null) }
                        if (!stopping.get()) RustCoreBridge.nativeYadeaRequestKeyFrame(owner)
                    }
                    override fun onAudioFormat(tts: Boolean, rate: Int, channels: Int) = player.configure(tts, rate, channels)
                    override fun onAudioStop(tts: Boolean) = player.stop(tts)
                    override fun onAudio(tts: Boolean, rate: Int, channels: Int, pcm: ByteArray) = player.offer(tts, rate, channels, pcm)
                    override fun onReopen(startup: Boolean) {
                        if (source == YadeaSource.CarWith && entry.claimReopen(owner, startup)) launchCarWith()
                    }
                    override fun onSourceClosed() {
                        if (!stopping.get()) failure.compareAndSet(null, IOException("导航来源已关闭"))
                    }
                }
                if (stopping.get()) return outcome
                if (!RustCoreBridge.nativeStartYadeaSource(config.toString(), owner, sourceWidth, sourceHeight,
                        display.fps, display.negotiated, listener)) error("无法启动 ${source.title} 导航来源")
                if (source == YadeaSource.CarWith) launchCarWith()
                val started = SystemClock.elapsedRealtime()
                val timeoutMs = if (source == YadeaSource.SystemCarLife) 120_000L else 60_000L
                val intervalNs = 1_000_000_000L / display.fps
                var nextFrameAt = 0L
                var nextNetworkCheck = 0L
                var streaming = false
                while (!stopping.get()) {
                    failure.get()?.let { throw it }
                    transport.checkConnection()
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    if (nowNs >= nextNetworkCheck) {
                        check(connectivity.getNetworkCapabilities(network) != null) { "仪表网络已断开" }
                        nextNetworkCheck = nowNs + 1_000_000_000L
                    }
                    if (nowNs >= nextFrameAt) {
                        jpeg.getAndSet(null)?.let { frame ->
                            transport.sendJpeg(frame)
                            nextFrameAt = nowNs + intervalNs
                            lastFrame.set(SystemClock.elapsedRealtime())
                            if (!streaming || state.value.status != "投屏中") update { it.copy(status = "投屏中") }
                            streaming = true
                        }
                    }
                    val since = SystemClock.elapsedRealtime() - lastFrame.get().takeIf { it > 0 }.let { it ?: started }
                    if (since > timeoutMs) error("等待 ${source.title} 画面超时，请检查手机车联后重试")
                    val untilFrame = nextFrameAt - SystemClock.elapsedRealtimeNanos()
                    delay(if (untilFrame > 0) (untilFrame / 1_000_000L).coerceIn(1L, 20L) else 10L)
                }
            } catch (error: Exception) {
                if (!stopping.get()) {
                    outcome = "投屏中断：${error.localizedMessage ?: error.javaClass.simpleName}"
                    HyperCarRepo.log("YADEA: $outcome")
                }
            } finally {
                stopping.set(true)
                launchScope.cancel()
                if (owner > 0) runCatching { entry.retire(owner) }
                runCatching { video?.close() }
                runCatching { audio?.close() }
                runCatching { if (connected.get()) transport.finish() }
                runCatching { transport.close() }
                if (wakeLock?.isHeld == true) wakeLock.release()
                if (owner > 0) HyperCarRepo.releaseYadeaSource(owner)
            }
            return outcome
        }

        private fun launchCarWith() {
            launchScope.launch {
                if (stopping.get()) return@launch
                val detail = HyperCarRepo.launchCarWith(this@YadeaProjectionService, owner, entry)
                HyperCarRepo.log("YADEA: CarWith 启动 $detail")
            }
        }
    }

    private fun instrumentNetwork(connectivity: ConnectivityManager, host: String): Network? {
        val target = InetAddresses.parseNumericAddress(host)
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            val prefix = connectivity.getLinkProperties(network)?.routes.orEmpty()
                .filter { !it.hasGateway() && it.destination.contains(target) }
                .maxOfOrNull { it.destination.prefixLength } ?: return@mapNotNull null
            network to prefix
        }.maxByOrNull { it.second }?.first
    }

    companion object {
        private const val CHANNEL = "yadea-projection"
        private const val NOTIFICATION_ID = 8888
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_ROUTE = "route"
        private const val ACTION_STOP = "io.github.hypercar.yadea.STOP"
        private val current = AtomicReference<YadeaProjectionService.Session?>()
        private val state = MutableStateFlow(YadeaSessionState())
        internal val sessionState = state.asStateFlow()

        internal fun start(context: Context, host: String, source: YadeaSource, route: Int?) {
            val ip = host.trim()
            if (runCatching { InetAddresses.parseNumericAddress(ip) }.isFailure) {
                if (!state.value.active) state.value = YadeaSessionState(status = "请输入有效的仪表 IP 地址")
                return
            }
            if (state.value.active) return
            state.value = YadeaSessionState(active = true, status = "正在启动投屏…", routeId = route)
            val intent = Intent(context, YadeaProjectionService::class.java)
                .putExtra(EXTRA_HOST, ip).putExtra(EXTRA_SOURCE, source.name).putExtra(EXTRA_ROUTE, route ?: -1)
            runCatching { context.startForegroundService(intent) }.onFailure {
                state.value = YadeaSessionState(status = "无法启动投屏：${it.localizedMessage}")
            }
        }

        internal fun stop() = current.get()?.requestStop()
        internal fun setRoute(route: Int?) = current.get()?.setRoute(route)
        internal fun touch(action: Int, x: Float, y: Float): Boolean = runCatching {
            current.get()?.touch(action, x, y) == true
        }.getOrDefault(false)
    }
}
