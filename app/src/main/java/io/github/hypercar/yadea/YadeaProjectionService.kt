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
import io.github.hypercar.core.LinkState
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.CarWithEntryGate
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.session.StartupRequirements
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.ui.MainActivity
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class YadeaSource(val title: String) {
    CarWith("CarWith"), SystemCarLife("CarLife+");

    fun installed(context: Context): Boolean = when (this) {
        CarWith -> StartupRequirements.carWithInstalled(context)
        SystemCarLife -> StartupRequirements.systemCarLifePackage(context) != null
    }

    companion object {
        fun fromSetting(source: String): YadeaSource? = when (source) {
            "carwith" -> CarWith
            "system_carlife" -> SystemCarLife
            else -> null
        }
    }
}

internal data class YadeaSessionState(
    val link: LinkState = LinkState.Idle,
    val display: YadeaDisplay? = null,
    val requestedSize: Pair<Int, Int>? = null,
    val sourceSize: Pair<Int, Int>? = null,
    val route: String = "等待音频",
    val awaitingSystemCarLife: Boolean = false,
) {
    val active: Boolean get() = link is LinkState.Negotiating || link is LinkState.Connected || link is LinkState.Stopping
}

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
        if (session != null || intent == null) return START_NOT_STICKY
        val host = intent.getStringExtra(EXTRA_HOST)
        val peer = intent.getStringExtra(EXTRA_PEER_ADDRESS)?.let { address ->
            YadeaP2pPeer(intent.getStringExtra(EXTRA_PEER_NAME).orEmpty(), address)
        }
        val source = YadeaSource.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SOURCE) }
        val scanner = pendingP2p.getAndSet(null)
        if ((host == null && peer == null) || source == null) {
            scanner?.close()
            stopSelf()
            return START_NOT_STICKY
        }
        val route = intent.getIntExtra(EXTRA_ROUTE, -1).takeIf { it >= 0 }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (error: RuntimeException) {
            scanner?.close()
            state.value = failureState("无法启动雅迪前台服务：${error.localizedMessage}")
            stopSelf()
            return START_NOT_STICKY
        }
        val next = Session(host, peer, scanner, source, route)
        session = next
        current.set(next)
        if (state.value.link == LinkState.Stopping) next.requestStop()
        else next.update { YadeaSessionState(link = LinkState.Negotiating("正在连接雅迪仪表…")) }
        scope.launch {
            val outcome = next.run()
            withContext(Dispatchers.Main.immediate) {
                if (session === next) {
                    session = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                if (current.compareAndSet(next, null)) {
                    preview.value = null
                    state.value = outcome?.let(::failureState) ?: YadeaSessionState()
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
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_PROJECTION)
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

    private inner class Session(
        private val manualHost: String?,
        private val peer: YadeaP2pPeer?,
        private val scannedLink: YadeaP2p?,
        private val source: YadeaSource,
        route: Int?,
    ) {
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
        private val p2pConnectJob = AtomicReference<kotlinx.coroutines.Job?>()

        fun update(transform: (YadeaSessionState) -> YadeaSessionState) {
            state.update {
                if (current.get() !== this) it
                else if (stopping.get()) it.copy(link = LinkState.Stopping, awaitingSystemCarLife = false)
                else transform(it)
            }
        }

        fun requestStop(immediate: Boolean = false) {
            stopping.set(true)
            update { it.copy(link = LinkState.Stopping, awaitingSystemCarLife = false) }
            p2pConnectJob.get()?.cancel()
            if (immediate || !connected.get()) transport.close()
        }

        fun setRoute(deviceId: Int?) {
            requestedRoute.set(deviceId)
            audio?.setPreferredDevice(deviceId)
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

        suspend fun run(): String? {
            var video: YadeaVideo? = null
            var wakeLock: PowerManager.WakeLock? = null
            val p2p = scannedLink
            var outcome: String? = null
            try {
                if (stopping.get()) return null
                if (!source.installed(this@YadeaProjectionService)) error("本机未安装或未启用 ${source.title}")
                owner = HyperCarRepo.claimYadeaSource()
                if (owner == 0L) error("请先结束当前 CarPlay 会话")
                if (stopping.get()) return outcome
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperCar:YadeaProjection")
                    .apply { setReferenceCounted(false); acquire() }
                update { it.copy(link = LinkState.Negotiating("等待仪表协商尺寸和帧率…")) }
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val host: String
                val network: Network?
                val localAddress: Inet4Address?
                if (peer != null) {
                    update { it.copy(link = LinkState.Negotiating("正在连接 ${peer.name} 的 Wi-Fi Direct…")) }
                    val link = p2p ?: error("扫描会话已结束，请重新扫描仪表")
                    host = coroutineScope {
                        val connection = async { link.connect(peer) }
                        p2pConnectJob.set(connection)
                        if (stopping.get()) connection.cancel()
                        try {
                            connection.await()
                        } finally {
                            p2pConnectJob.compareAndSet(connection, null)
                        }
                    }
                    network = null
                    val interfaceName = link.interfaceName ?: error("未获取到 Wi-Fi Direct 接口")
                    localAddress = awaitP2pAddress(link, interfaceName, host)
                    update { it.copy(link = LinkState.Negotiating("已连接 Wi-Fi Direct，正在协商仪表画面…")) }
                } else {
                    host = checkNotNull(manualHost)
                    localAddress = null
                    network = instrumentNetwork(connectivity, host)
                        ?: error("找不到到达仪表 $host 的 Wi-Fi 网络，请先连接仪表 Wi-Fi")
                }
                val display = transport.connect(host, network = network, localAddress = localAddress)
                connected.set(true)
                if (stopping.get()) return outcome
                // HyperCar's source canvas limit is independent of the instrument JPEG dimensions.
                val scale = minOf(1.0, 2560.0 / maxOf(display.width, display.height))
                val sourceWidth = ((display.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
                val sourceHeight = ((display.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
                val waiting = if (source == YadeaSource.SystemCarLife)
                    "请在手机车联中打开 CarLife+ 无线连接，然后返回此页。" else "正在启动 CarWith…"
                update { it.copy(link = LinkState.Negotiating(waiting), display = display,
                    requestedSize = sourceWidth to sourceHeight) }
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
                            preview.value = bitmap
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
                            preview.value = null
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
                        preview.value = null
                        update { it.copy(link = LinkState.Negotiating(waiting), sourceSize = null,
                            awaitingSystemCarLife = source == YadeaSource.SystemCarLife) }
                    }
                    override fun onVideoDisconnected() {
                        previewReady.set(false)
                        decoder.reset()
                        sourceSize.get()?.let { (width, height) -> decoder.setSourceSize(width, height) }
                        jpeg.set(null)
                        preview.value = null
                        update { it.copy(link = LinkState.Negotiating("导航画面已断开，正在等待恢复…"),
                            awaitingSystemCarLife = source == YadeaSource.SystemCarLife) }
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
                else update { it.copy(awaitingSystemCarLife = true) }
                val started = SystemClock.elapsedRealtime()
                val timeoutMs = if (source == YadeaSource.SystemCarLife) 120_000L else 60_000L
                val intervalNs = 1_000_000_000L / display.fps
                var nextFrameAt = 0L
                var nextNetworkCheck = 0L
                while (!stopping.get()) {
                    failure.get()?.let { throw it }
                    transport.checkConnection()
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    if (nowNs >= nextNetworkCheck) {
                        val networkConnected = if (network != null) connectivity.getNetworkCapabilities(network) != null
                            else p2p?.connected?.value == true
                        check(networkConnected) { "仪表网络已断开" }
                        nextNetworkCheck = nowNs + 1_000_000_000L
                    }
                    if (nowNs >= nextFrameAt) {
                        jpeg.getAndSet(null)?.let { frame ->
                            transport.sendJpeg(frame)
                            nextFrameAt = nowNs + intervalNs
                            lastFrame.set(SystemClock.elapsedRealtime())
                            if (state.value.link is LinkState.Negotiating) update {
                                if (it.link is LinkState.Negotiating) {
                                    it.copy(link = LinkState.Connected("雅迪仪表", System.currentTimeMillis()),
                                        awaitingSystemCarLife = false)
                                } else it
                            }
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
                runCatching { p2p?.close() }
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

        private suspend fun awaitP2pAddress(link: YadeaP2p, interfaceName: String, host: String): Inet4Address {
            val remote = InetAddresses.parseNumericAddress(host)
            return withTimeoutOrNull(5_000) {
                var local: Inet4Address? = null
                while (local == null && link.connected.value && !stopping.get()) {
                    // The P2P group can be reported before DHCP assigns a local address.
                    local = NetworkInterface.getByName(interfaceName)?.inetAddresses?.toList()
                        ?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull { it != remote && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    if (local == null) delay(150)
                }
                local
            } ?: error("未获取到 Wi-Fi Direct 本机地址")
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
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_PEER_ADDRESS = "peer_address"
        private const val EXTRA_ROUTE = "route"
        private const val ACTION_STOP = "io.github.hypercar.yadea.STOP"
        private val current = AtomicReference<YadeaProjectionService.Session?>()
        private val pendingP2p = AtomicReference<YadeaP2p?>()
        private val state = MutableStateFlow(YadeaSessionState())
        private val preview = MutableStateFlow<Bitmap?>(null)
        private val selectedRoute = MutableStateFlow<Int?>(null)
        internal val sessionState = state.asStateFlow()
        internal val previewFrames = preview.asStateFlow()
        internal val audioRoute = selectedRoute.asStateFlow()

        internal fun isValidInstrumentIp(host: String): Boolean =
            runCatching { InetAddresses.parseNumericAddress(host.trim()) }.isSuccess

        private fun failureState(reason: String) = YadeaSessionState(link = LinkState.Failed(reason, recoverable = false))

        internal fun start(context: Context, source: YadeaSource, host: String) {
            begin(context, source, host, null, null)
        }

        internal fun startP2p(context: Context, source: YadeaSource, peer: YadeaP2pPeer, scanner: YadeaP2p) {
            begin(context, source, null, peer, scanner)
        }

        private fun begin(context: Context, source: YadeaSource, host: String?, peer: YadeaP2pPeer?, scanner: YadeaP2p?) {
            val ip = host?.trim().orEmpty()
            if (state.value.active) {
                scanner?.close()
                return
            }
            if (peer == null && !isValidInstrumentIp(ip)) {
                state.value = failureState("请输入有效的仪表 IP 地址")
                return
            }
            val route = selectedRoute.value?.takeIf { id -> yadeaAudioOutputs(context).any { it.id == id } }
            selectedRoute.value = route
            state.value = YadeaSessionState(link = LinkState.Negotiating("正在启动投屏…"))
            val intent = Intent(context, YadeaProjectionService::class.java)
                .putExtra(EXTRA_SOURCE, source.name).putExtra(EXTRA_ROUTE, route ?: -1)
            if (peer == null) intent.putExtra(EXTRA_HOST, ip)
            else intent.putExtra(EXTRA_PEER_NAME, peer.name).putExtra(EXTRA_PEER_ADDRESS, peer.address)
            pendingP2p.getAndSet(scanner)?.close()
            runCatching { context.startForegroundService(intent) }.onFailure {
                pendingP2p.getAndSet(null)?.close()
                state.value = failureState("无法启动投屏：${it.localizedMessage}")
            }
        }

        internal fun stop() {
            current.get()?.let { it.requestStop(); return }
            state.update { if (it.active) it.copy(link = LinkState.Stopping) else it }
        }

        internal fun setRoute(route: Int?) {
            selectedRoute.value = route
            current.get()?.setRoute(route)
        }
        internal fun touch(action: Int, x: Float, y: Float): Boolean = runCatching {
            current.get()?.touch(action, x, y) == true
        }.getOrDefault(false)
    }
}
