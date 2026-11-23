@file:Suppress("DEPRECATION")   // allNetworks/WifiInfo/getDefaultAdapter 等平台 API 已废弃但无替代
package io.github.hypercar.session

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import io.github.hypercar.net.SessionSocketLease
import io.github.hypercar.headunit.HeadUnitActivationPolicy
import io.github.hypercar.headunit.HeadUnitProfile
import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.headunit.RemoteProfiles
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.hypercar.autowake.AutoStart
import io.github.hypercar.bluetooth.CarProfileGuard
import io.github.hypercar.bluetooth.ReconnectDiscoverability
import io.github.hypercar.bluetooth.knownBondedCar
import io.github.hypercar.bluetooth.shouldDiscoverForCarAcl
import io.github.hypercar.bluetooth.DiscoverabilityHandoff
import io.github.hypercar.bluetooth.SystemDiscoverabilityPrompt
import io.github.hypercar.autowake.HyperCarService
import io.github.hypercar.carui.CarUiCaster
import io.github.hypercar.carui.DexCaster
import io.github.hypercar.carui.MusicBridge
import io.github.hypercar.core.AudioStats
import io.github.hypercar.core.LinkState
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.core.VideoStats
import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2Coordinator
import io.github.hypercar.iap2.Iap2WifiCredentialHistory
import io.github.hypercar.net.BluetoothMac
import io.github.hypercar.net.AirplayServiceProbe
import io.github.hypercar.net.CarPlayCtrlAdvertiser
import io.github.hypercar.net.CarWifiRequestIdentity
import io.github.hypercar.net.CtrlInvitation
import io.github.hypercar.net.CtrlDiscoveryTrace
import io.github.hypercar.net.SpecifierPermissions
import io.github.hypercar.net.WifiJoiner
import io.github.hypercar.net.BluetoothHotspot
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.hypercar.iap2.BmwCaffProbe

/**
 * 会话总管：上车秒连的完整链路。
 *
 * 时序（与 docs/AUTO-CONNECT.md 的 cardeck 链路同源，协商已下沉 Rust）：
 *  车机蓝牙 ACL 连上 → （清单静态接收器冷启动进程）→ HyperCarService FGS
 *    → [AutoStart.handler] → [connect]
 *    → iAP2 协商（0x5703 热点凭据 / 0x4301 AirPlay 端点）
 *    → HyperCar 专属局域网请求 + bindProcessToNetwork
 *    → 连 AirPlay 控制端口 → detachFd 移交 Rust → Rust 内完成全部 AirPlay 协商
 *    → 本机自绘界面自采自编，经 nativePushVideoFrame / nativePushAudioPcm 投喂 Rust
 */
object HyperCarRepo {
    private val proactiveActivation = AtomicReference<ProactiveActivation?>(null)
    private val connectionDiagnostics = ConnectionDiagnostics(SystemClock::elapsedRealtime)
    @Volatile private var connectionAttemptId = 0L
    private val carWithEntries = CarWithEntryGate { RustCoreBridge.nativeStopCarWith(it) }

    private fun finishDiagnostic(outcome: String, reason: String) {
        connectionDiagnostics.finish(connectionAttemptId, outcome, reason)
        mediaSummaryWrites.trySend(Unit)
        connectionDiagnostics.report().lastOrNull()?.let { log(it) }
    }

    private val _snapshots = MutableStateFlow(SessionSnapshot())
    val snapshots: StateFlow<SessionSnapshot> = _snapshots.asStateFlow()
    private val _oldDeviceReadiness = MutableStateFlow<OldDeviceReadiness?>(null)
    internal val oldDeviceReadiness = _oldDeviceReadiness.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaLog = MediaDiagnosticLog { log(it) }
    private val mediaSummaryWrites = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var mediaSummaryStore: MediaSummaryStore? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var iap2: Iap2Coordinator? = null

    @Volatile private var wifiJoiner: WifiJoiner? = null
    private val hotspotHandoff = CarHotspotReconnect.Handoff<WifiJoiner, Network>()
    private val wifiRecoveryLease = io.github.hypercar.net.WifiRecoveryLease<WifiJoiner> { request ->
        if (request.release() == null && wifiJoiner === request) {
            hotspotHandoff.clear(request)
            wifiJoiner = null
            joinedNetwork = null
            joinedSsid = null
        }
    }

    private fun releaseRetainedWifi() {
        wifiRecoveryLease.close()
    }

    /** Caller holds autoConnectChanges; adoption never withdraws the physical association. */
    private fun adoptRetainedWifi(network: Network, peer: String?, confirmedBluetoothHotspot: Boolean = false): Boolean {
        val request = wifiJoiner ?: return true
        if (!wifiRecoveryLease.isRetained(request)) return true
        if (!request.ownsLiveRequest(network) || peer == null ||
            !wifiRecoveryLease.adopt(request, peer, confirmedBluetoothHotspot)) {
            return false
        }
        log("WIFI-LEASE: 本轮采用已保留的同车请求，不重新关联热点")
        return true
    }

    /**
     * `_carplay-ctrl._tcp` 广播器（真车邀请驱动流程的核心：车机在 WiFi 上发现它才下发
     * 0x4301）。生命周期与本会话一致：拿到 0x5703 → 绑网 → 注册；拆除时注销。
     */
    @Volatile private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    private class ManualCaffAction(val attempt: Long, val dial: () -> String)
    private val manualCaffAction = AtomicReference<ManualCaffAction?>(null)
    private val caffExperiment = BmwCaffExperiment()
    internal val armedBmwCaffExperiment = caffExperiment.armedTarget

    /** 本轮已经静默加入过的车机热点（避免「先拿凭据入网」与「端点到了再入网」重复连接）。 */
    @Volatile
    private var joinedSsid: String? = null

    @Volatile
    private var joinedNetwork: Network? = null

    /** AirPlay 会话是否已建立（冗余端点去重，cardeck「推流中忽略 0x5703/0x4301」教训）。 */
    @Volatile
    private var streaming = false

    private val videoCapabilityGeneration = java.util.concurrent.atomic.AtomicLong()
    private val engineCallbacks = EngineCallbackGate()
    internal fun requestPlaybackAudio(owner: Long) = engineCallbacks.requestPlayback(owner) {
        sessionLive.get() && RustCoreBridge.nativeRequestAudioOwnership()
    }

    private suspend fun startNativeForSession(
        fd: Int, sockets: SessionSocketLease<Network>, attempt: Long, activation: ProactiveActivation,
        advertiser: CarPlayCtrlAdvertiser, target: AirplayTarget,
        checkEndpoint: (AirplayTarget) -> AirplayTarget, rememberEndpoint: () -> Unit, configure: () -> String,
    ): Boolean {
        var confirmedTarget = target
        return handOffControlSocket(autoConnectChanges, iap2EndpointChanges,
            closeUnclaimed = { runCatching { ParcelFileDescriptor.adoptFd(fd).close() } },
            sourceGeneration = { confirmedTarget.sourceGeneration },
            checkCurrent = {
                if (!sessionLive.get() || connectionAttemptId != attempt || sessionSockets !== sockets ||
                    boundNetwork != sockets.network || !cachedHotspotMatchesTarget())
                    throw CancellationException("旧核心启动结果已失效")
                confirmedTarget = checkEndpoint(confirmedTarget)
            },
            prepare = {
                val config = org.json.JSONObject(configure()).put("carwith_source_owner", attempt)
                val stationMac = wifiStationSnapshot?.takeIf { it.first == attempt && it.second == sockets.network }?.third
                if (stationMac != null) config.put("wifi_mac_address", stationMac)
                log("SESSION-IDENTITY: wifi_station_mac=${stationMac != null} device_identity_preserved=true")
                io.github.hypercar.navigation.CarWithNavigation.prepareConfig(config.toString())
            },
            start = { json, transfer ->
                val callbackOwner = engineCallbacks.begin()
                io.github.hypercar.carui.CarReceiverUi.reset()
                log("CORE-OWNER: start owner=$callbackOwner attempt=$attempt network=${sockets.network}")
                val videoGeneration = videoCapabilityGeneration.incrementAndGet()
                SettingsStore.videoCapabilities.begin(videoGeneration)
                connectionDiagnostics.engineStarted(attempt, callbackOwner, sessionReceiverMac, SystemClock.uptimeMillis(), System.currentTimeMillis())
                RustCoreBridge.setListener(EngineEventListener(engineCallbacks, callbackOwner, object : RustCoreBridge.EventListener by rustListener {
                    override fun onLog(line: String) {
                        if (line.startsWith("MEDIA-TIMELINE: ")) {
                            if (connectionDiagnostics.mediaEvent(attempt, callbackOwner, line)) {
                                mediaSummaryWrites.trySend(Unit)
                            }
                            mediaLog.offer(line)
                            return
                        }
                        if (line.startsWith("VIDEO-FEEDBACK: ") || line.startsWith("VIDEO-QUEUE: ") ||
                            line.startsWith("VIDEO-QOS: ") || line.startsWith("VIDEO: reset source ") ||
                            line.startsWith("VIDEO: keepAlive written ") || line.startsWith("CLUSTER: keepAlive written ")) {
                            mediaLog.offer(line)
                            return
                        }
                        if (!line.startsWith("AUDIO-UDP-DROP: ")) {
                            rustListener.onLog(line)
                            return
                        }
                        val receivedBootNs = SystemClock.elapsedRealtimeNanos()
                        scope.launch {
                            fun current() = sessionLive.get() && connectionAttemptId == attempt &&
                                sessionSockets === sockets && boundNetwork == sockets.network &&
                                engineCallbacks.isCurrent(callbackOwner)
                            val sampleStartBootNs = SystemClock.elapsedRealtimeNanos()
                            val wifi = if (current()) runCatching { audioWifiSnapshot(sockets.network) }
                                .getOrElse { "wifiSample=error error=${it.javaClass.simpleName}" }
                            else "wifiSample=session_changed"
                            val sampleEndBootNs = SystemClock.elapsedRealtimeNanos()
                            log("$line attempt=$attempt engine=$callbackOwner network=${sockets.network} " +
                                "receivedBootNs=$receivedBootNs sampleBootNs=$sampleStartBootNs,$sampleEndBootNs " +
                                if (current()) wifi else "wifiSample=session_changed")
                        }
                    }
                    override fun onEventChannel(rtsp: Long, uptime: Long, cause: String, videoTcp: String) {
                        connectionDiagnostics.eventChannel(attempt, callbackOwner, rtsp, uptime, cause, videoTcp)
                        if (cause != "open") mediaSummaryWrites.trySend(Unit)
                    }
                    override fun onReceiverAudioTake(taken: Boolean) {
                        val ticket = engineCallbacks.receiverAudioTake(callbackOwner, taken) ?: return
                        appContext()?.let { context ->
                            MusicBridge.onReceiverAudioTake(context, ticket) {
                                sessionLive.get() && connectionAttemptId == attempt &&
                                    sessionSockets === sockets && engineCallbacks.isCurrent(callbackOwner)
                            }
                        }
                    }
                    override fun onVideoCapabilities(hevcAllowed: Boolean) {
                        if (!sessionLive.get() || connectionAttemptId != attempt || sessionSockets !== sockets ||
                            videoCapabilityGeneration.get() != videoGeneration) return
                        if (SettingsStore.videoCapabilities.accept(videoGeneration, hevcAllowed))
                            log("VIDEO: receiver HEVC allowed=$hevcAllowed preference=${SettingsStore.videoCodec}")
                    }
                }))
                connectionDiagnostics.mark(attempt, ConnectionDiagnostics.Stage.AIRPLAY_START)
                log("CORE: 准备移交控制 socket（$airplayHost:$airplayPort fd=$fd）")
                val started = try {
                    // Match the advertiser → iAP2 lock order used when pinning a Wi-Fi request.
                    synchronized(advertiser) {
                        transfer {
                            proactiveActivation.set(activation)
                            RustCoreBridge.nativeStart(json, fd, socketProvider(sockets))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    engineCallbacks.retire()
                    SettingsStore.videoCapabilities.clear()
                    throw cancelled
                }
                if (started) rememberEndpoint()
                started
            },
        )
    }

    private fun audioWifiSnapshot(network: Network): String {
        val cm = appContext()?.getSystemService(ConnectivityManager::class.java)
            ?: return "wifiSample=no_connectivity_manager"
        val caps = cm.getNetworkCapabilities(network) ?: return "wifiSample=network_unavailable"
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val info = (caps.transportInfo as? android.net.wifi.WifiInfo)?.takeIf { isWifi }
        val properties = cm.getLinkProperties(network)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val bound = cm.boundNetworkForProcess == network
        return "wifiSample=available wifi=$isWifi processBound=$bound validated=$validated " +
            "if=${properties?.interfaceName ?: "NA"} rssiDbm=${info?.rssi ?: "NA"} " +
            "frequencyMHz=${info?.frequency ?: "NA"} txLinkMbps=${info?.txLinkSpeedMbps ?: "NA"} " +
            "rxLinkMbps=${info?.rxLinkSpeedMbps ?: "NA"} supplicant=${info?.supplicantState ?: "NA"}"
    }

    /** 本轮连接的计时基准（毫秒）；日志里统一打成 `+Nms`，便于定位慢在哪一步。 */
    @Volatile
    private var sessionStageStart: Long = 0L

    /** 本轮已确定的 AirPlay 控制端点；不用于失败后的旧端点重拨。 */
    @Volatile
    private var airplayHost: String? = null
    /** Keep route provenance so a late candidate can be rechecked against the dialing network. */
    private val lateAirplayEndpoint = AtomicReference<CarPlayEndpoint?>(null)
    private val retiredSsidAirplayAddresses = AtomicReference<Set<String>>(emptySet())

    @Volatile
    private var airplayPort: Int = 0

    @Volatile private var fallbackToRemember: io.github.hypercar.net.PhoneIdentitySnapshot.Identity? = null

    /** 当前车机档案（设置里可切换；每次连接重新读取）。 */
    @Volatile private var sessionProfile: HeadUnitProfile? = null
    private val headUnitProfile: HeadUnitProfile
        get() = sessionProfile ?: HeadUnitProfiles.current()

    /** 相对 [sessionStageStart] 的毫秒数，用于阶段日志前缀。 */
    private fun stageMs(): Long =
        if (sessionStageStart == 0L) 0L else System.currentTimeMillis() - sessionStageStart

    @Volatile
    private var connecting = false

    /** 在途连接协程：下车/停止时必须取消，否则它会一直等 iAP2 端点（`connecting` 卡 true）。 */
    @Volatile
    private var connectJob: Job? = null

    /**
     * 本轮 ACL 连上的车机地址。传给 iAP2 作出站目标/入站白名单：
     * 只拨这台车、只收它的连入，避免把时间花在 HI-BOX 等旧 dongle 上。
     */
    @Volatile
    private var aclCarMac: String? = null
    private class AclDiscoverabilityEdge(val device: BluetoothDevice, val ticket: AclDiscoverabilityTicket)
    private val aclDiscoverabilityEdge = AtomicReference<AclDiscoverabilityEdge?>(null)
    private val cachedHotspotAcl = CarHotspotReconnect.AclTrigger()
    internal fun onCarAclDisconnected(address: String, profileDrop: Boolean): Boolean =
        cachedHotspotAcl.onDisconnect(address, profileDrop)
    // Only a manual retry may keep an already-open shell window across attempt teardown.
    @Volatile private var discoveryHandoff: DiscoverabilityHandoff? = null

    /** Identity pinned to a known Wi-Fi resume; never reuse another receiver's Bluetooth identity. */
    @Volatile private var wifiReceiverMac: String? = null
    @Volatile private var sessionReceiverMac: String? = null
    @Volatile private var sessionWifiSsid: String? = null
    private val announcedHotspot = AtomicReference<Pair<Long, BluetoothHotspot>?>(null)
    private class PendingCarHotspot(
        val attempt: Long,
        val receiver: String,
        val network: Network,
        val credentials: CarHotspotReconnect.Credentials,
    )
    private val pendingCarHotspot = AtomicReference<PendingCarHotspot?>(null)
    @Volatile private var wifiStationSnapshot: Triple<Long, Network, String>? = null
    private var wifiReconnectMonitor: ConnectivityManager.NetworkCallback? = null
    private var wifiReconnectCheck: Job? = null
    private var sessionNetworkMonitor: ConnectivityManager.NetworkCallback? = null

    // ---- 掉线自动重连（方案 A-3）----
    // 车机侧链路断开（ACL 掉、AirPlay 会话结束、车机重启）后自动重新建链，
    // 不需要用户去点「连接」。用户主动断开时置 userDisconnectRequested，不再重连。
    private var reconnectJob: Job? = null
    private var sessionUiJob: Job? = null
    /** 本轮会话是否走小米 CarWith（决定用哪个入口去拉起手机端 CarLife）。 */
    @Volatile private var sessionCarWithMode = false
    /** 连接开始时冻结的 DeX 源标记：会话中改设置不影响本条链路。 */
    @Volatile private var sessionSourceIsDex = false
    /** 本轮会话是否走手机自带的系统 CarLife（三星车联 / 荣耀车联等）。 */
    @Volatile private var sessionSystemCarLife = false
    /** 本轮会话冻结的来源字符串，用于判断设置是否已切源。 */
    @Volatile private var sessionCarSource = "car_ui"
    /** 任一由本机 CarLife 引擎提供画面的来源。 */
    internal val sessionCarLifeMode: Boolean get() = sessionCarWithMode || sessionSystemCarLife
    @Volatile private var sessionIdentity: PairingIdentityFiles.Location? = null
    private var automaticResumeJob: Job? = null
    private val automaticModeRequest = AtomicLong()

    val automaticPauseRemaining: Long get() = appContext()?.let {
        io.github.hypercar.autowake.AutomaticConnectionResume.remaining(it)
    } ?: 0L
    val automaticConnectionAllowed: Boolean get() = SettingsStore.autoConnect && automaticPauseRemaining == 0L

    private fun scheduleAutomaticResume(context: Context) {
        automaticResumeJob?.cancel()
        automaticResumeJob = null
        val remaining = automaticPauseRemaining
        runCatching { io.github.hypercar.autowake.AutomaticConnectionResume.schedule(context, remaining) }
            .onFailure { log("AUTO: 暂停恢复闹钟未登记：${it.message}；保留进程内定时器") }
        if (!SettingsStore.autoConnect || remaining <= 0) return
        automaticResumeJob = scope.launch {
            delay(remaining)
            startAutomaticListening(context) // recheck the persisted deadline before restoring listeners
        }
    }

    /** 连续失败次数（成功进 connected 时清零），用来做退避与上限。 */
    private val reconnectBudget = ReconnectBudget()
    @Volatile private var lastConnectInviteNudgeAt = 0L
    @Volatile private var lastHandoffInviteNudgeAt = 0L
    /** True while bound and waiting for ctrl invite / inbound iAP2 (not while streaming). */
    @Volatile private var awaitingCarInvitation = false

    /**
     * [Iap2Bluetooth.sessionStartCount] when this connect round started.
     * The process counter is not cleared on standby, so "this round" is the delta.
     */
    @Volatile private var roundSessionBaseline = 0

    @Volatile
    private var userDisconnectRequested = false

    /** 本次会话是否真正建立过链路（没建过就不要无限重连：那不是"掉线"）。 */
    @Volatile
    private var sessionHadLink = false

    /**
     * 会话资源是否还活着（蓝牙协商/WiFi 绑定/Rust 引擎）。拆除用它做幂等闸门：
     * 重复的 ACL 断开广播、service 二次转交都只会真正拆一次。
     */
    private val sessionLive = AtomicBoolean(false)

    /** 持有移交 Rust 的网络句柄（推流期间不能释放）。 */
    private val ctrlNetworkLock = Any()
    /** VW may advertise on the current Wi-Fi before identifying it as the car's network. */
    @Volatile private var unverifiedInvitationNetwork: Network? = null
    @Volatile
    private var boundNetwork: Network? = null
        set(value) = synchronized(ctrlNetworkLock) {
            if (field != value) {
                unverifiedInvitationNetwork = null
                val advertiser = ctrlAdvertiser
                if (advertiser == null) field = value else synchronized(advertiser) {
                    field = value
                    advertiser.selectNetwork(value)
                }
            }
        }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            appCtx = app
            val summaries = MediaSummaryStore(java.io.File(app.filesDir, "hypercar-media-summaries.txt"))
            mediaSummaryStore = summaries
            scope.launch { mediaLog.run() }
            scope.launch {
                for (signal in mediaSummaryWrites) {
                    runCatching { summaries.save(connectionDiagnostics.mediaSummaries()) }
                        .onFailure { android.util.Log.w("HyperCar", "Media summary write failed", it) }
                }
            }
            // 恢复日志记录开关（诊断页切换过则沿用）
            logRecording = SettingsStore.debugLogEnabled
            _snapshots.value = _snapshots.value.copy(logRecording = logRecording)
            // 落盘日志 + 崩溃兜底：环形缓冲只在内存里，进程一死（原生崩溃 / 被 OOM 杀）
            // 现场就全没了——2026-10-23 20:30 那次崩溃就是这么丢的。这里把日志逐行
            // 追加到 filesDir/hypercar-session.log（每行 flush），重启后先把上一轮的尾部
            // 带回环形缓冲（放在启动标记之前，顺序才读得懂），崩溃前现场仍可导出。
            restoreDiskLogTail()
            installCrashHandler()
            appendDiskLog("===== HyperCar 启动 =====")
            // 环境自述：设备/系统/SDK/App 版本一次写进日志（导出时也会再写一份到文件头）。
            // 排查第一件事就是「哪台机、什么系统、哪个版本」，缺了它只能靠猜。
            log("ENV: ${DeviceInfo.fullSummary(app)}")
            scope.launch { captureCrashArtifacts() }
            // ACL 广播唤醒入口（receiver → handler）：优先经 FGS 拿前台豁免与持锁，
            // 服务起来后再触发真正会话（handler 幂等，service 二次进入走直连分支）
            AutoStart.handler = handler@{ ctx, device, connected, forwarded, observedAt ->
                val app = ctx.applicationContext
                if (!forwarded && hasCurrentSession) recordAclObservation(connectionAttemptId, connected, observedAt)
                if (!connected) {
                    val cleanup = runCatching { device.address }.getOrNull()?.let { address ->
                        aclDiscoverabilityEdge.updateAndGet { edge ->
                            edge?.takeUnless { it.ticket.peer.equals(address, ignoreCase = true) }
                        }
                        iap2?.captureBluetoothLost(address)
                    }
                    if (cleanup != null) scope.launch { cleanup() }
                }
                if (!SettingsStore.autoConnect && (connected || !hasCurrentSession)) return@handler
                if (connected && !automaticConnectionAllowed) {
                    startAutomaticListening(app)
                    return@handler
                }
                if (!connected && !hasCurrentSession && !automaticConnectionAllowed) return@handler
                if (connected) {
                    if (!StartupRequirements.read(app).automaticReady) {
                        startAutomaticListening(app)
                        return@handler
                    }
                    // 记住刚连上 ACL 的车机：本轮 iAP2 只拨/只收这台，别再去碰
                    // HI-BOX 之类已配对的旧 dongle（每次 RFCOMM 失败都白占栈、添噪音，
                    // 2026-10-25 现场日志里 10 分钟里一半是它们的连接失败）
                    runCatching { device.address }.getOrNull()?.let { address ->
                        synchronized(targetCarChanges) {
                            if (!forwarded && address.equals(SettingsStore.targetCarMac, ignoreCase = true)) {
                                cachedHotspotAcl.connected(address, observedAt)
                            }
                        }
                        if (address != aclCarMac) {
                            aclCarMac = address
                            log("AUTO: 本轮 iAP2 目标锁定为 ACL 车机 $address（${bluetoothName(device) ?: "?"}）")
                        }
                        // 音频 profile 干预受车机档案控制；是否为本次失败根因仍需实车验证。
                        CarProfileGuard.noteCar(address)
                        updateCarProfileGuard(app)
                        CarProfileGuard.onCarConnected(app, device, address)
                    }
                    val edge = captureAclDiscoverability(device, forwarded)
                    if (hasCurrentSession) {
                        // A genuine ACL edge may queue discoverability; it never starts AirPlay here.
                        val attempt = connectionAttemptId
                        if (sessionLive.get() && attempt == connectionAttemptId) edge?.ticket?.assign(attempt)
                        scope.launch {
                            nudgeInvitationDiscovery(app, "ACL 连上", expectedAttempt = attempt,
                                discoverableArmed = edge?.ticket?.pendingFor(attempt) == true)
                        }
                    } else if (!HyperCarService.isRunning) {
                        runCatching {
                            app.startForegroundService(HyperCarService.startIntent(app, device, observedAt))
                        }.onFailure {
                            // FGS 被系统拦（未授悬浮窗等）：降级直接连接（进程可能被回收）
                            log("AUTO: FGS 启动被拦（${it.message}）——降级直接拉起会话")
                            connectAutomatically(app, aclEdge = edge, aclObservedAt = observedAt)
                        }
                    } else {
                        connectAutomatically(app, aclEdge = edge, aclObservedAt = observedAt)
                    }
                } else {
                    // An ACL broadcast has no connection generation. The RFCOMM reader,
                    // the bound network monitor and RTSP each own their transport's end.
                    val attempt = connectionAttemptId
                    val address = runCatching { device.address }.getOrNull()
                    if (sessionLive.get() && RustCoreBridge.nativeCanKeepWifiSession()) {
                        iap2?.pauseOutboundForWifi()
                        log("HANDOFF: ACL 退场，保留 RFCOMM 收发与当前 Wi-Fi CarPlay 会话")
                    } else if (hasCurrentSession && !streaming && !hasAirPlayTransport) {
                        scope.launch {
                            val announced = currentBluetoothHotspot(attempt)
                            if (address != null && announced != null && !announced.peer.equals(address, true)) return@launch
                            val bound = boundNetwork != null
                            val present = bound || announced?.let { hotspotAssociation(app, it).associated } == true ||
                                knownCarWifiAssociated(app)
                            if (connectionAttemptId != attempt || !AclHandoffKeep.shouldRefreshInvitation(
                                    sessionLive.get(), streaming || hasAirPlayTransport, present)) return@launch
                            nudgeInvitationDiscovery(app, "ACL 退场交 Wi-Fi", handoffToWifi = true, expectedAttempt = attempt)
                        }
                    }
                }
                logAclEvent(device, connected)
            }
            // Rust 回调 → 快照（Rust 工作线程回调，直接改 StateFlow 是安全的）
            RustCoreBridge.setListener(rustListener)
        }
            // 首启时快照还没读过系统态：这里先算一次（否则 Shizuku 已授权也显示降级模式）
            push()
    }

    /**
     * ACL 事件日志：同一次事件会被 receiver 与 service 各转交一次，去重后只记一行；
     * 车机若在极短时间内反复重建 ACL，也不会把日志刷满（见 [log] 的限流兜底）。
     */
    private fun logAclEvent(device: BluetoothDevice, connected: Boolean) {
        val name = bluetoothName(device) ?: "未知设备"
        val address = runCatching { device.address }.getOrNull() ?: "?"
        val key = "$address/$connected"
        val now = SystemClock.elapsedRealtime()
        if (key == lastAclEventKey && now - lastAclEventAt < ACL_EVENT_LOG_DEDUPE_MILLIS) {
            lastAclEventAt = now
            return
        }
        ctrlDiscoveryTrace.record("event=bluetooth-acl connected=$connected attempt=$connectionAttemptId elapsedMsAtAcl=$now")
        lastAclEventKey = key
        lastAclEventAt = now
        log("AUTO: 车机蓝牙 ${if (connected) "连上" else "断开"}（$name）")
    }

    private fun recordAclObservation(attempt: Long, connected: Boolean, at: Long) {
        connectionDiagnostics.observe(attempt,
            if (connected) ConnectionDiagnostics.Observation.ACL_UP else ConnectionDiagnostics.Observation.ACL_DOWN, at = at)
    }

    /** handler 只在主线程（receiver/service 都是主线程）调用，无需加锁。 */
    private var lastAclEventKey: String? = null
    private var lastAclEventAt = 0L

    // ---------------------------------------------------------------- 对 UI

    val hasCurrentSession: Boolean get() = sessionLive.get() || connecting || streaming
    internal val hasAirPlayTransport: Boolean get() = sessionLive.get() && sessionSockets != null

    /** Source eligibility. Each iAP2 link checks its peer's declaration and subscriptions. */
    @Volatile private var hudSessionEnabled = false
    internal val hudNavigationAllowed: Boolean get() = hudSessionEnabled && hasCurrentSession &&
        sessionCarSource == SettingsStore.carSource &&
        (sessionCarLifeMode || io.github.hypercar.carui.CarUiSettings.defaultMap == io.github.hypercar.carui.MapProvider.AMAP)
    internal val carWithHudNavigationAllowed: Boolean get() = hudNavigationAllowed && sessionCarLifeMode
    internal val mapNavigationAllowed: Boolean get() = hasCurrentSession && !sessionCarLifeMode &&
        !SettingsStore.carLifeMode && io.github.hypercar.carui.CarUiSettings.defaultMap == io.github.hypercar.carui.MapProvider.AMAP

    internal fun connect(context: Context, systemPrompt: SystemDiscoverabilityPrompt? = null) =
        requestConnect(context, manual = true, source = "manual", systemPrompt = systemPrompt,
            suppressManualWindow = ReconnectDiscoverability.windowOpenAtManualClick())

    internal enum class ExperimentMarker(val observation: ConnectionDiagnostics.Observation) {
        CARD_UNLOCKED(ConnectionDiagnostics.Observation.CARD_UNLOCKED),
        ABOUT_TO_SELECT_CARPLAY(ConnectionDiagnostics.Observation.ABOUT_TO_SELECT_CARPLAY),
        OLD_CARPLAY_ERROR(ConnectionDiagnostics.Observation.OLD_CARPLAY_ERROR),
        NEW_LIST_PHONE_SELECTED(ConnectionDiagnostics.Observation.NEW_LIST_PHONE_SELECTED),
    }

    private val ctrlDiscoveryTrace = CtrlDiscoveryTrace(SystemClock::elapsedRealtime, System::currentTimeMillis, scope, ::log)

    internal fun markConnectionExperiment(marker: ExperimentMarker): String {
        val attempt = connectionAttemptId
        val recorded = connectionDiagnostics.observe(attempt, marker.observation)
        val recording = logRecording
        val clocks = "epochMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()}"
        val markLine = "DEV-MARK: attempt=${if (recorded) attempt.toString() else "none"} " +
            "${marker.observation.name} ${marker.observation.label} $clocks"
        if (marker != ExperimentMarker.CARD_UNLOCKED) {
            val readiness = _oldDeviceReadiness.value?.takeIf { it.attempt == attempt }
            val advertiser = ctrlAdvertiser
            val network = boundNetwork
            val port = advertiser?.withListeningPortFor(attempt) { it }
            val currentSrv = network?.let { advertiser?.currentSrv(it) }
            val status = readiness?.takeIf { it.network == network?.toString() }
                ?.copy(srv = currentSrv?.let { OldDeviceReadiness.Srv(it.host, it.port) })
            val readinessLine = "OLD-DEVICE-READY: at-click " +
                "${status?.diagnosticFields() ?: "attempt=$attempt state=unavailable"} $clocks"
            ctrlDiscoveryTrace.mark(listOf(markLine, readinessLine),
                "event=click-state action=${marker.name} attempt=$attempt network=$network currentPort=${port ?: "none"} tcpSyn=external-capture-required")
        } else ctrlDiscoveryTrace.enqueue(markLine)
        return when {
            recording -> "已打点：${marker.observation.label}（已写入诊断日志）"
            recorded -> "已打点：${marker.observation.label}（仅本轮快照，诊断日志已关闭）"
            else -> "诊断日志已关闭，且没有进行中的连接；请先开启日志记录"
        }
    }

    internal suspend fun latestConnectionSnapshot(): String? = withContext(Dispatchers.IO) {
        val attempt = connectionAttemptId
        val advertiser = ctrlAdvertiser
        if (advertiser == null) connectionDiagnostics.connectionSnapshot(attempt, null)
        else advertiser.withListeningPortFor(attempt) { port -> connectionDiagnostics.connectionSnapshot(attempt, port) }
    }

    private val developerConnectionAction = kotlinx.coroutines.sync.Mutex()

    private inline fun runDeveloperConnectionAction(action: () -> String): String {
        if (!developerConnectionAction.tryLock()) return "另一项连接实验仍在进行，请稍后再试"
        return try { action() } finally { developerConnectionAction.unlock() }
    }

    /** Manual single-variable diagnostic: reuse only the current Wi-Fi listener and service identity. */
    internal suspend fun resendCtrlMdnsAnnouncement(): String = withContext(Dispatchers.IO) {
        val requestedBootNs = SystemClock.elapsedRealtimeNanos()
        val attempt = connectionAttemptId
        val advertiser = ctrlAdvertiser
        val network = boundNetwork
        fun finish(reason: String, message: String): String {
            log("MDNS-RESEND: manual attempt=$attempt network=$network requestBootNs=$requestedBootNs " +
                "resultBootNs=${SystemClock.elapsedRealtimeNanos()} result=$reason message=$message")
            return message
        }
        if (!SettingsStore.developerMenuVisible) return@withContext finish("menu_hidden", "请先显示开发者菜单")
        if (streaming) return@withContext finish("streaming", "CarPlay 已连接，无需重发")
        if (hasAirPlayTransport) return@withContext finish("transport_in_progress", "本轮正在处理连接，暂不重发")
        if (!sessionLive.get() || advertiser == null || network == null) {
            val reason = when {
                !sessionLive.get() -> "no_active_attempt"
                network == null -> "wifi_not_bound"
                else -> "no_advertiser"
            }
            return@withContext finish(reason, "请先连接车机 Wi-Fi，并让 HyperCar 等待车机邀请")
        }
        if (!developerConnectionAction.tryLock())
            return@withContext finish("action_busy", "另一项连接实验仍在进行，请稍后再试")
        val result = try {
            advertiser.resendAnnouncement(network) {
                connectionAttemptId == attempt && sessionLive.get() && !userDisconnectRequested &&
                    ctrlAdvertiser === advertiser && boundNetwork == network && !streaming && !hasAirPlayTransport
            }
        } finally {
            developerConnectionAction.unlock()
        }
        finish(result.status, result.message)
    }

    private fun bmwCaffExperimentTarget(): String? = SettingsStore.targetCarMac?.takeIf {
        headUnitProfile.id == "bmw_id85" && !headUnitProfile.allowOutboundIap2 && !SettingsStore.carLifeMode
    }

    internal fun disarmBmwCaffExperiment() = caffExperiment.clear()

    internal fun armBmwCaffExperiment(): String? {
        if (!SettingsStore.developerMenuVisible) return "请先显示开发者菜单"
        val target = bmwCaffExperimentTarget() ?: return "请选择宝马 iD8.5 档案和已配对的目标车机（非 CarWith 模式）"
        caffExperiment.arm(target)
        return null
    }

    internal suspend fun dialBmwCaffManually(): String = withContext(Dispatchers.IO) {
        if (!SettingsStore.developerMenuVisible) return@withContext "请先显示开发者菜单"
        if (headUnitProfile.id != "bmw_id85") return@withContext "仅支持宝马 iD8.5 档案"
        if (!sessionLive.get()) return@withContext "请先准备实验，再到首页手动连接"
        if (streaming) return@withContext "CarPlay 已连接，无需拨号"
        if (hasAirPlayTransport) return@withContext "本轮正在处理连接，暂不拨号"
        val action = manualCaffAction.get()?.takeIf { it.attempt == connectionAttemptId }
            ?: return@withContext "本轮不是已就绪的手动实验；请确认目标已配对，准备实验后从首页发起新一轮连接"
        runDeveloperConnectionAction { action.dial() }
    }

    private fun connectAutomatically(context: Context, source: String = "acl", wakePeer: String? = null,
                                     aclEdge: AclDiscoverabilityEdge? = null, aclObservedAt: Long? = null) =
        requestConnect(context, manual = false, source = source, wakePeer = wakePeer, aclEdge = aclEdge,
            aclObservedAt = aclObservedAt)

    fun connectFromCompanion(context: Context, address: String) {
        if (!io.github.hypercar.autowake.CompanionWakePolicy.accepts(
                SettingsStore.autoConnect, SettingsStore.targetCarMac, address,
            )) return
        if (hasCurrentSession) {
            scope.launch { nudgeInvitationDiscovery(context.applicationContext, "companion") }
            return
        }
        log("COMPANION: 恢复监听并请求自动会话；仍等待本轮车机邀请")
        connectAutomatically(context, "companion", address)
    }

    private fun captureAclDiscoverability(device: BluetoothDevice, forwarded: Boolean): AclDiscoverabilityEdge? {
        val peer = runCatching { device.address }.getOrNull() ?: return null
        // A foreground-service round trip is not a new ACL; preserve the original age and ownership.
        if (forwarded) return aclDiscoverabilityEdge.get()?.takeIf { it.ticket.peer.equals(peer, ignoreCase = true) }
        val coordinator = iap2
        val iap2Started = streaming || hasAirPlayTransport || coordinator?.isSessionActive == true ||
            (sessionLive.get() && (coordinator?.sessionStartCount ?: 0) > roundSessionBaseline)
        val target = SettingsStore.targetCarMac
        if (!shouldDiscoverForCarAcl(SettingsStore.autoConnect, knownAclCar(device, peer, target), iap2Started,
                ReconnectDiscoverability.discoverableWindowOpen())) return null
        val edge = AclDiscoverabilityEdge(device,
            AclDiscoverabilityTicket(peer, SystemClock.elapsedRealtime(), target, headUnitProfile.id, automaticModeRequest.get()))
        aclDiscoverabilityEdge.set(edge)
        return edge
    }

    private fun knownAclCar(device: BluetoothDevice, peer: String, target: String?): Boolean = knownBondedCar(
        try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT may be revoked between receiving the ACL and inspecting it.
            false
        } catch (_: RuntimeException) {
            false
        },
        peer, target, SettingsStore.isCarPaired(peer), wifiReceiverMac,
    )

    private fun aclDiscoverabilityStillValid(edge: AclDiscoverabilityEdge, attempt: Long): Boolean {
        val target = SettingsStore.targetCarMac
        return aclDiscoverabilityEdge.get() === edge && edge.ticket.peer.equals(aclCarMac, ignoreCase = true) &&
            sessionLive.get() && connectionAttemptId == attempt && !userDisconnectRequested &&
            automaticConnectionAllowed && !streaming && !hasAirPlayTransport &&
            edge.ticket.isFresh(SystemClock.elapsedRealtime(), target, headUnitProfile.id, automaticModeRequest.get()) &&
            knownAclCar(edge.device, edge.ticket.peer, target)
    }

    private suspend fun nudgeInvitationDiscovery(
        context: Context,
        reason: String,
        handoffToWifi: Boolean = false,
        expectedAttempt: Long = connectionAttemptId,
        discoverableArmed: Boolean = false,
    ) {
        val advertiser = ctrlAdvertiser
        val now = SystemClock.elapsedRealtime()
        val keepWifi = runCatching { RustCoreBridge.nativeCanKeepWifiSession() }.getOrDefault(false)
        val anchor = InviteWakeNudge.cooldownAnchor(
            handoffToWifi, lastConnectInviteNudgeAt, lastHandoffInviteNudgeAt)
        if (!InviteWakeNudge.shouldNudge(sessionLive.get(), streaming, keepWifi, advertiser != null,
                now, anchor, handoffToWifi)) return
        val ssid = sessionWifiSsid ?: currentWifiSsid(context)
        if (expectedAttempt != connectionAttemptId || advertiser !== ctrlAdvertiser || !sessionLive.get() ||
            streaming || hasAirPlayTransport) return
        val network = boundNetwork
        val ensured = advertiser?.nudgeWhileWaiting(reason, ssid, network) == true
        val stamped = InviteWakeNudge.rememberNudge(ensured, now, anchor)
        if (stamped != anchor) {
            if (handoffToWifi) lastHandoffInviteNudgeAt = stamped else lastConnectInviteNudgeAt = stamped
        }
        val armed = when {
            handoffToWifi -> false
            reason == "ACL 连上" -> discoverableArmed
            else -> InviteWakeNudge.armDiscoverable(handoffToWifi) &&
                ReconnectDiscoverability.armForWake()
        }
        log("INVITE-WAKE: $reason；ctrl监听=${if (ensured) "保持/修复" else "未变"} 可发现再开=${if (armed) "是" else "否"}；" +
            "继续等车机 Wi-Fi ctrl 邀请（宝马二次上车蓝牙短窗口后常退场，不触发主动建链）")
        val waitingForCarIap2 = KnownWifiWaitCopy.awaitingHeadUnitIap2(
            wifiReceiverMac != null && boundNetwork != null,
            iap2?.sessionStartCount ?: 0,
            roundSessionBaseline,
            ReconnectDiscoverability.firstWindowOver(),
        )
        push(link = LinkState.Negotiating(
            when {
                handoffToWifi && waitingForCarIap2 ->
                    KnownWifiWaitCopy.boundNegotiatingStep(headUnitProfile.id, true)
                        ?: KnownWifiWaitCopy.HERO
                handoffToWifi -> "蓝牙已交棒，等待车机 Wi-Fi 邀请"
                else -> "车机已回连，等待 CarPlay 邀请"
            }))
    }

    private fun requestConnect(context: Context, manual: Boolean, source: String, wakePeer: String? = null,
                               systemPrompt: SystemDiscoverabilityPrompt? = null,
                               suppressManualWindow: Boolean = false, aclEdge: AclDiscoverabilityEdge? = null, aclObservedAt: Long? = null) {
        init(context)
        scope.launch {
            autoConnectChanges.lock()
            try {
                if (!AutomaticConnectionPolicy.mayStart(manual, SettingsStore.autoConnect, automaticPauseRemaining > 0)) {
                    scheduleAutomaticResume(context.applicationContext)
                    return@launch
                }
                if (!hasCurrentSession) {
                    val readiness = StartupRequirements.read(context)
                    val issues = if (manual) readiness.manualIssues else readiness.automaticIssues
                    if (issues.isNotEmpty()) {
                        log("STARTUP: 阻止连接：${issues.joinToString("；")}")
                        push(link = LinkState.Failed("请先完成使用引导：${issues.joinToString("；")}"))
                        return@launch
                    }
                }
                if (manual) {
                    // A deliberate start supersedes a previous manual-stop cooldown. Otherwise
                    // a failed manual retry falls back into the old ten-minute identity-only state.
                    clearAutomaticPause(context.applicationContext, "手动启动连接")
                }
                if (WaitingInvitationRetryPolicy.shouldSoftRestart(manual, awaitingCarInvitation,
                        hasCurrentSession, streaming)) {
                    val attempt = connectionAttemptId
                    val previous = discoveryHandoff
                    val origin = previous?.takeIf { it.successor == attempt }?.origin ?: attempt
                    val moving = DiscoverabilityHandoff(origin).takeIf { ReconnectDiscoverability.windowOpenAtManualClick() }
                    discoveryHandoff = moving
                    try {
                        softRestartInvitationWait(context, systemPrompt, suppressManualWindow)
                    } finally {
                        if (discoveryHandoff === moving) discoveryHandoff = null
                    }
                    return@launch
                }
                if (AutomaticConnectionPolicy.mayStart(manual, SettingsStore.autoConnect, automaticPauseRemaining > 0)) {
                    beginConnect(context, source, manual, wakePeer, systemPrompt, suppressManualWindow, aclEdge, aclObservedAt)
                }
            } finally { autoConnectChanges.unlock() }
        }
    }

    /**
     * Invite-wait rounds keep [sessionLive] true, so a normal CONNECT was ignored and the car
     * tap/ACL path could not open a fresh attempt. Manual retry ends the wait without the
     * ten-minute pause, then starts a new attempt with listening restored.
     */
    private suspend fun softRestartInvitationWait(context: Context, systemPrompt: SystemDiscoverabilityPrompt?,
                                                  suppressManualWindow: Boolean) {
        val attempt = connectionAttemptId
        log("CONNECT: 等待邀请中手动重试——结束 attempt=$attempt 后重新开始，不创建10分钟暂停")
        carWithEntries.retire(attempt)
        reconnectBudget.clear()
        userDisconnectRequested = false
        awaitingCarInvitation = false
        reconnectJob?.cancel()
        reconnectJob = null
        sessionSockets?.invalidate()
        val pending = connectJob
        connectJob = null
        pending?.cancel()
        withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { pending?.join() }
        // keepListening restores cafe accept without the ten-minute STOP_AND_PAUSE path.
        runDisconnect("手动重试等待中的邀请轮次", keepListening = true, graceful = false)
        reconnectJob?.cancel()
        reconnectJob = null
        connecting = false
        if (AutomaticConnectionPolicy.mayStart(true, SettingsStore.autoConnect, automaticPauseRemaining > 0)) {
            beginConnect(context, "manual-retry", manual = true, systemPrompt = systemPrompt,
                suppressManualWindow = suppressManualWindow)
        }
    }

    private fun beginConnect(context: Context, source: String, manual: Boolean, wakePeer: String? = null,
                             systemPrompt: SystemDiscoverabilityPrompt?,
                             suppressManualWindow: Boolean = false, aclEdge: AclDiscoverabilityEdge? = null, aclObservedAt: Long? = null) {
        init(context)
        if (sessionLive.get() || connecting || streaming) {
            if (sessionLive.get()) {
                aclEdge?.ticket?.assign(connectionAttemptId)
                aclObservedAt?.let { recordAclObservation(connectionAttemptId, true, it) }
            }
            log("CONNECT: 已在连接/已连接，忽略重复触发")
            return
        }
        val selection = connectionProfile() ?: return
        val profile = checkNotNull(selection.profile)
        sessionProfile = profile
        // 手动/自动发起连接都算"用户想要连"，清掉断开标记与重连倒计时
        userDisconnectRequested = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectBudget.start(retryReceiver(), manual = source == "manual")
        sessionHadLink = false
        roundSessionBaseline = iap2?.sessionStartCount ?: 0
        sessionCarSource = SettingsStore.carSource
        sessionCarWithMode = SettingsStore.carWithMode
        sessionSourceIsDex = SettingsStore.dexMode
        sessionSystemCarLife = SettingsStore.systemCarLifeMode
        hudSessionEnabled = true
        io.github.hypercar.navigation.NavigationSources.clear()
        sessionIdentity = null
        sessionReceiverMac = null
        lateAirplayEndpoint.set(null)
        retiredSsidAirplayAddresses.set(emptySet())
        airplayHost = null
        airplayPort = 0
        connecting = true
        lastConnectInviteNudgeAt = 0L
        lastHandoffInviteNudgeAt = 0L
        io.github.hypercar.carui.MusicNotificationListener.sync(context)
        connectionAttemptId = connectionDiagnostics.begin(source,
            "${profile.id} remote=${selection.remote} " +
                "activation=${profile.requireAirplayActivation} outbound=${profile.allowOutboundIap2} " +
                "profileGuard=${profile.dropProfilesBeforeIap2}", SystemClock.uptimeMillis())
        aclObservedAt?.let { recordAclObservation(connectionAttemptId, true, it) }
        val moving = discoveryHandoff?.takeIf { source == "manual-retry" && it.successor == null }
        aclEdge?.ticket?.assign(connectionAttemptId)
        if (sessionCarLifeMode) carWithEntries.begin(connectionAttemptId)
        sessionLive.set(true)
        // Readers that observe the published successor must also see its live flag and ID.
        discoveryHandoff = moving?.let { DiscoverabilityHandoff(it.origin, connectionAttemptId) }
        standbyEndpoint.get()?.takeIf { it.sourceIsCurrent }?.bluetoothHotspot?.let {
            announcedHotspot.compareAndSet(null, connectionAttemptId to it)
        }
        log(connectionDiagnostics.report().last())
        updateCarProfileGuard(context)
        runCatching { context.startForegroundService(HyperCarService.sessionIntent(context)) }
            .onFailure { log("SERVICE: 会话保活启动失败：${it.message}") }
        // 阶段计时基准：之后每条 CONNECT 日志都带 +Nms，一眼看出卡在哪一步
        sessionStageStart = System.currentTimeMillis()
        push(link = LinkState.Negotiating("检查 Shizuku"))
        connectJob?.cancel()
        val attempt = connectionAttemptId
        // Consume only an explicit opt-in on a newly admitted manual round. Menu visibility
        // is presentation only, and retries/wakeups keep the production probe policy.
        val experimentTarget = caffExperiment.takeForRound(manual, if (manual) bmwCaffExperimentTarget() else null)
        connectJob = scope.launch {
            runConnect(context.applicationContext, attempt, wakePeer, manual, systemPrompt, experimentTarget, suppressManualWindow)
        }
    }

    /**
     * 重置配对身份（设置页「重置配对身份」按钮）。
     *
     * 连接进行中不允许重置：身份文件在引擎启动时已被读进 Rust，删了也不会立刻生效，
     * 反而会让"这次连接用的旧身份、下次用新身份"造成两边状态错位。
     *
     * @return 给用户看的摘要（成功或拒绝原因）。
     */
    suspend fun resetPairingIdentity(mac: String): String = resetPairingData(mac)

    suspend fun resetAllPairingIdentities(): String = resetPairingData(null)

    private suspend fun resetPairingData(mac: String?): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val ctx = appContext() ?: return@withContext "应用上下文未就绪，稍后再试"
        autoConnectChanges.lock()
        try {
            if (hasCurrentSession) return@withContext "请先断开连接，再重置配对身份"
            if (mac != null && PairingEvidence.receiverKey(mac) == null) {
                return@withContext "无效的车机地址，未清除配对信息"
            }
            stopStandbyListening() // RFCOMM callbacks must not recreate credentials during reset.
            runCatching {
                if (mac == null) {
                    val summary = PairingReset.runAll(ctx, ::log)
                    io.github.hypercar.autowake.CarCompanionWake.remove(ctx)
                    io.github.hypercar.iap2.Iap2Peer.clear()
                    updateTargetCar(null, null)
                    return@runCatching summary
                }
                val summary = PairingReset.run(ctx, mac, ::log)
                io.github.hypercar.settings.SettingsRevision.bump()
                io.github.hypercar.autowake.CarCompanionWake.removeFor(ctx, mac)
                if (mac.equals(io.github.hypercar.iap2.Iap2Peer.address, ignoreCase = true)) {
                    io.github.hypercar.iap2.Iap2Peer.clear()
                }
                val cleared = synchronized(targetCarChanges) {
                    if (SettingsStore.targetCarMac.equals(mac, ignoreCase = true)) {
                        updateTargetCar(null, null)
                        true
                    } else false
                }
                if (cleared) "$summary，目标车机已设为未指定"
                else "$summary，当前目标车机保持不变"
            }.getOrElse {
                log("PAIR-RESET: 清理失败，保留目标选择：${it.message}")
                "重置失败，请重试：${it.message}"
            }
        } finally {
            autoConnectChanges.unlock()
            startAutomaticListening(ctx)
        }
    }

    private val targetCarChanges = Any()

    internal suspend fun selectHeadUnitProfile(profileId: String, expectedTargetMac: String?, keepTarget: Boolean): String? =
        autoConnectChanges.withLock {
            synchronized(targetCarChanges) {
                if (hasCurrentSession) return@synchronized "连接中，请断开后再切换档案"
                val selection = HeadUnitProfiles.catalog().select(profileId)
                selection.blockReason?.let { return@synchronized it }
                if (SettingsStore.headUnitProfileId == profileId) return@synchronized null
                val target = SettingsStore.targetCarMac
                if (!target.orEmpty().equals(expectedTargetMac.orEmpty(), ignoreCase = true)) {
                    return@synchronized "目标车机已变化，请重新确认"
                }
                val targetName = SettingsStore.targetCarName
                SettingsStore.headUnitProfileId = profileId
                updateTargetCar(target.takeIf { keepTarget }, targetName.takeIf { keepTarget })
                null
            }
        }

    private fun cachedHotspotMatchesTarget(): Boolean =
        hotspotHandoff.matchesTarget(SettingsStore.targetCarMac, established = sessionHadLink)

    /** 切换目标立即更新入站过滤，并退出旧目标尚未激活的缓存连接。 */
    fun updateTargetCar(mac: String?, name: String?) = synchronized(targetCarChanges) {
        val target = mac?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(java.util.Locale.ROOT)
        val changed = !target.orEmpty().equals(SettingsStore.targetCarMac.orEmpty(), ignoreCase = true)
        if (changed) {
            SettingsStore.lastAirplayEndpoint = "" // Legacy endpoint had no receiver association.
            cachedHotspotAcl.disconnected()
        }
        CarProfileGuard.stop()
        SettingsStore.setTargetCar(target, name?.takeIf { target != null && it.isNotBlank() })
        CarProfileGuard.noteCar(null)
        aclCarMac = null
        announcedHotspot.set(null)
        standbyEndpoint.set(null)
        iap2?.onTargetChanged()
        appContext()?.let { context ->
            if (!changed) startAutomaticListening(context)
            else {
                val attempt = connectionAttemptId
                scope.launch {
                    autoConnectChanges.withLock {
                        if (connectionAttemptId == attempt && !cachedHotspotMatchesTarget()) {
                            sessionSockets?.invalidate()
                            val pending = connectJob
                            connectJob = null
                            pending?.cancel()
                            withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { pending?.join() }
                            runDisconnect("目标已切换，撤销尚未建立会话的缓存热点")
                        }
                    }
                    startAutomaticListening(context)
                }
            }
        }
        io.github.hypercar.settings.SettingsRevision.bump()
        log("TARGET: 用户目标=${target ?: "自动"}，已清除旧 ACL 候选；入站过滤立即生效，已建立会话保持")
    }

    fun disconnect(expectedAttempt: Long) {
        // Invalidate on the tap, before the asynchronous stop can wait on the previous connect job.
        when (ConnectionStopPolicy.decide(true, expectedAttempt, connectionAttemptId, hasCurrentSession,
                _snapshots.value.link, SettingsStore.autoConnect)) {
            ConnectionStopPolicy.Decision.STOP, ConnectionStopPolicy.Decision.STOP_AND_PAUSE -> {
                iap2?.retireCafeHandoff(expectedAttempt, "pause")
                carWithEntries.retire(expectedAttempt)
            }
            else -> Unit
        }
        disconnect(userInitiated = true, expectedAttempt = expectedAttempt, reason = "用户断开当前会话")
    }

    private fun clearAutomaticPause(context: Context, reason: String) {
        val hadPause = SettingsStore.automaticPause.isNotEmpty()
        automaticResumeJob?.cancel()
        automaticResumeJob = null
        runCatching { io.github.hypercar.autowake.AutomaticConnectionResume.clear(context) }
            .onFailure { log("AUTO: 清除暂停恢复闹钟失败 ${it.message}") }
        if (hadPause) {
            io.github.hypercar.settings.SettingsRevision.bump()
            log("AUTO: $reason，清除旧自动连接暂停记录")
        }
        if (SettingsStore.autoConnect) startWifiReconnectMonitor(context)
    }

    private fun disconnect(
        userInitiated: Boolean,
        expectedAttempt: Long = connectionAttemptId,
        expectedEngine: Long? = null,
        reason: String,
        stillCurrent: () -> Boolean = { true },
    ) {
        val attempt = expectedAttempt
        scope.launch {
            autoConnectChanges.lock()
            try {
                if (expectedEngine != null && !engineCallbacks.isCurrent(expectedEngine)) return@launch
                val decision = ConnectionStopPolicy.decide(userInitiated, attempt, connectionAttemptId,
                    hasCurrentSession, _snapshots.value.link, SettingsStore.autoConnect)
                if (decision == ConnectionStopPolicy.Decision.IGNORE_STALE ||
                    decision == ConnectionStopPolicy.Decision.IGNORE_INACTIVE) {
                    log("SESSION-CONTROL: 忽略停止请求 decision=$decision user=$userInitiated expected=$attempt current=$connectionAttemptId state=${_snapshots.value.link.javaClass.simpleName}")
                    return@launch
                }
                if (!stillCurrent()) return@launch
                if (userInitiated) {
                    aclDiscoverabilityEdge.set(null)
                    cachedHotspotAcl.disconnected()
                }
                carWithEntries.retire(attempt)
                if (decision == ConnectionStopPolicy.Decision.STOP_AND_PAUSE) {
                    reconnectBudget.clear()
                    appContext()?.let { io.github.hypercar.autowake.AutomaticConnectionResume.pause(it) }
                    io.github.hypercar.settings.SettingsRevision.bump()
                    // releaseSessionResources closes the old RFCOMM after the AirPlay exit.
                    iap2?.pauseSessions(closeCurrent = false)
                    log("AUTO: 手动停止 attempt=$attempt，暂停自动建链 10 分钟；保留 iPhone SDP，手动启动将解除暂停")
                }
                userDisconnectRequested = !automaticConnectionAllowed
                reconnectJob?.cancel()
                reconnectJob = null
                sessionSockets?.invalidate()
                val pending = connectJob
                connectJob = null
                pending?.cancel()
                withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { pending?.join() }
                val keepListening = AutomaticConnectionPolicy.keepListeningAfterStop(SettingsStore.autoConnect, automaticPauseRemaining > 0)
                runDisconnect(reason, keepListening,
                    graceful = userInitiated)
                if (keepListening) startStandbyListening()
                else if (keepIdleIdentity) startIdentityOnly()
                else stopStandbyListening()
                appContext()?.let { scheduleAutomaticResume(it) }
            } finally { autoConnectChanges.unlock() }
        }
    }

    fun setAutoConnect(context: Context, enabled: Boolean) {
        val request = automaticModeRequest.incrementAndGet()
        if (!enabled) cachedHotspotAcl.disconnected()
        val app = context.applicationContext
        scope.launch {
            autoConnectChanges.lock()
            try {
                if (request != automaticModeRequest.get()) return@launch
                if (enabled) {
                    val issues = StartupRequirements.read(app).automaticIssues
                    if (issues.isNotEmpty()) {
                        log("STARTUP: 无法开启自动上车连接：${issues.joinToString("；")}")
                        return@launch
                    }
                }
                SettingsStore.autoConnect = enabled
                if (enabled) clearAutomaticPause(app, "用户开启自动连接")
                else {
                    reconnectBudget.clear()
                    stopWifiReconnectMonitor()
                    reconnectJob?.cancel()
                    reconnectJob = null
                }
                io.github.hypercar.settings.SettingsRevision.bump()
            } finally { autoConnectChanges.unlock() }
            startAutomaticListening(app)
        }
    }

    /** 只处理待机监听；当前手动/自动会话不随开关切换被拆除。 */
    fun startAutomaticListening(context: Context): Job {
        init(context)
        return scope.launch {
            autoConnectChanges.lock()
            try {
                scheduleAutomaticResume(context)
                if (SettingsStore.autoConnect && automaticPauseRemaining > 0) {
                    if (!hasCurrentSession) startIdentityOnly()
                    return@launch
                }
                if (SettingsStore.autoConnect) {
                    val issues = StartupRequirements.read(context).automaticStandbyIssues
                    if (issues.isNotEmpty()) {
                        stopStandbyListening(standbyReady = false)
                        log("STARTUP: 自动上车连接等待启动条件：${issues.joinToString("；")}")
                        return@launch
                    }
                }
                if (SettingsStore.autoConnect) startWifiReconnectMonitor(context)
                else stopWifiReconnectMonitor()
                io.github.hypercar.autowake.CarCompanionWake.sync(context)
                io.github.hypercar.carui.MusicNotificationListener.sync(context)
                when (AutomaticConnectionPolicy.modeChanged(SettingsStore.autoConnect, hasCurrentSession)) {
                    AutomaticConnectionPolicy.IdleAction.KEEP_SESSION -> {
                        updateCarProfileGuard(context)
                        log("AUTO: 自动模式=${SettingsStore.autoConnect}；保留当前会话")
                    }
                    AutomaticConnectionPolicy.IdleAction.START_LISTENING -> startStandbyListening()
                    AutomaticConnectionPolicy.IdleAction.KEEP_IDENTITY -> startIdentityOnly()
                    AutomaticConnectionPolicy.IdleAction.STOP_LISTENING -> stopStandbyListening()
                }
            } finally { autoConnectChanges.unlock() }
        }
    }

    private val autoConnectChanges = kotlinx.coroutines.sync.Mutex()
    private val carWifiRequests = kotlinx.coroutines.sync.Mutex()
    private val iap2EndpointChanges = Any()
    private val activeEndpointInbox = AtomicReference<Pair<Long, CarHotspotReconnect.EndpointInbox>?>(null)
    private val standbyEndpoint = AtomicReference<CarPlayEndpoint?>(null)
    @Volatile private var iap2WifiHistory = Iap2WifiCredentialHistory()

    private var coordinatorProfile: HeadUnitProfile? = null

    private fun connectionProfile(): RemoteProfiles.Selection? {
        val selection = HeadUnitProfiles.selection()
        val reason = selection.blockReason ?: return selection
        stopStandbyListening(standbyReady = false)
        log("PROFILE: 阻止新连接：$reason")
        push(link = LinkState.Failed(reason))
        return null
    }

    private fun coordinatorFor(context: Context, profile: HeadUnitProfile): Iap2Coordinator {
        val existing = iap2
        if (existing != null && coordinatorProfile == profile) return existing
        existing?.stop()
        if (!sessionLive.get()) iap2WifiHistory = Iap2WifiCredentialHistory()
        standbyEndpoint.set(null)
        coordinatorProfile = profile
        return Iap2Coordinator(
            context,
            targetMac = { SettingsStore.targetCarMac },
            preferredMac = { aclCarMac },
            deviceMacProvider = BluetoothMac::resolve,
            profile = profile,
            canStartSession = {
                val activeProfile = sessionProfile?.takeIf { sessionLive.get() }
                (activeProfile ?: HeadUnitProfiles.selection().profile) == profile
            },
            onInboundSession = { attempt, peer, at -> connectionDiagnostics.rfcommAccepted(attempt, peer, at) },
            diagnosticAttempt = { connectionAttemptId.takeIf { sessionLive.get() } ?: 0L },
            wifiCredentialHistory = { iap2WifiHistory },
            log = ::log,
        ).also { iap2 = it }
    }

    private fun startStandbyListening() {
        if (!automaticConnectionAllowed || hasCurrentSession) return
        val profile = connectionProfile()?.profile ?: return
        appContext()?.let { io.github.hypercar.carui.MusicNotificationListener.sync(it) }
        val context = appContext() ?: return
        if (StartupRequirements.read(context).automaticStandbyIssues.isNotEmpty()) {
            startAutomaticListening(context)
            return
        }
        startWifiReconnectMonitor(context)
        val coordinator = coordinatorFor(context, profile)
        coordinator.start { propose ->
            val endpoint = synchronized(iap2EndpointChanges) {
                if (iap2 !== coordinator) return@start false
                val update = propose() ?: return@start false
                val endpoint = update.endpoint
                if (!endpoint.whileSourceCurrent {
                    update.commit()
                    rememberHotspot(connectionAttemptId, endpoint)
                    standbyEndpoint.updateAndGet { mergeEndpoint(it, endpoint) }
                }) return@start false
                endpoint
            }
            if (endpoint.sourceIsCurrent && SettingsStore.autoConnect) connectAutomatically(context, "standby-iap2")
            true
        }
        if (coordinator.isRunning) {
            runCatching { context.startForegroundService(HyperCarService.listenIntent(context)) }
                .onFailure { log("SERVICE: 后台监听保活启动失败：${it.message}") }
            log("AUTO: 待机监听已启用，等待车机；尚未发起 AirPlay 会话")
            updateCarProfileGuard(context)
        }
    }

    private val keepIdleIdentity: Boolean get() = AutomaticConnectionPolicy.keepIdentityAfterStop(
        SettingsStore.autoConnect, automaticPauseRemaining > 0)

    /** Keep the same SDP/SCN, but never run a handshake or outbound probe during cooldown. */
    private fun startIdentityOnly() {
        if (!keepIdleIdentity || hasCurrentSession) return
        val profile = connectionProfile()?.profile ?: return
        val context = appContext() ?: return
        stopWifiReconnectMonitor()
        reconnectJob?.cancel()
        reconnectJob = null
        CarProfileGuard.stop()
        iap2WifiHistory = Iap2WifiCredentialHistory()
        standbyEndpoint.set(null)
        val coordinator = coordinatorFor(context, profile)
        coordinator.keepIdentityOnly()
        io.github.hypercar.carui.MusicNotificationListener.sync(context)
        if (coordinator.isRunning) {
            runCatching { context.startForegroundService(HyperCarService.identityIntent(context)) }
                .onFailure { log("SERVICE: 身份声明保活启动失败 ${it.message}") }
            log("AUTO: 休眠中仅保留 iPhone/CarPlay 服务声明，不扫描、不拨号、不进行 iAP2 握手")
        }
    }

    private fun updateCarProfileGuard(context: Context) {
        if (io.github.hypercar.bluetooth.CarProfilePolicy.shouldGuard(
                headUnitProfile.dropProfilesBeforeIap2, automaticConnectionAllowed, hasCurrentSession,
            )) CarProfileGuard.start(context, ::log)
        else CarProfileGuard.stop()
    }

    private fun stopStandbyListening(standbyReady: Boolean = true) {
        if (standbyReady || hasCurrentSession) appContext()?.let { updateCarProfileGuard(it) }
        else CarProfileGuard.stop()
        stopWifiReconnectMonitor()
        if (hasCurrentSession) return
        reconnectJob?.cancel()
        reconnectJob = null
        iap2WifiHistory = Iap2WifiCredentialHistory()
        iap2?.stop()
        iap2 = null
        standbyEndpoint.set(null)
        stopForegroundService()
        appContext()?.let { io.github.hypercar.carui.MusicNotificationListener.sync(it) }
        log("AUTO: 待机监听与自动重连已停止；手动连接仍可用")
    }

    private fun receiverForEngine(): String? {
        val active = iap2?.activePeerAddress
        val desktop = io.github.hypercar.headunit.ReceiverCompatibility.isDesktopReceiver(
            headUnitProfile.id,
            io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(active),
        )
        val selected = PairingEvidence.receiverMac(active, sessionReceiverMac, wifiReceiverMac, desktop)
        if (wifiReceiverMac != null && !wifiReceiverMac.equals(selected, true)) {
            log("RECEIVER: Wi-Fi 历史候选=$wifiReceiverMac；当前蓝牙对端=${active ?: "无"}，凭证身份=${selected ?: "由接收端 /info 判定"}；不以共享网络历史覆盖实际接收端")
        }
        sessionReceiverMac = selected
        return selected
    }

    /** Same trust as Wi-Fi resume: paired car, credential marker, and a live network for this SSID. */
    private suspend fun knownCarWifiAssociated(context: Context): Boolean {
        val ssid = currentWifiSsid(context) ?: return false
        if (knownWifiReceiver(ssid) == null) return false
        return knownWifiNetwork(context, ssid) != null
    }

    private fun knownWifiReceiver(ssid: String?): String? {
        val records = SettingsStore.knownCarNetworks.toMutableSet()
        // Upgrade existing installs only when the selected car already has its own pairing credential.
        val legacySsid = SettingsStore.lastAirplayEndpoint.substringAfter('|', "")
        val target = SettingsStore.targetCarMac
        if (legacySsid.isNotBlank() && target != null) records += "$legacySsid|$target"
        val mac = KnownCarNetworkPolicy.receiver(ssid, records, target) ?: return null
        val context = appContext() ?: return null
        return mac.takeIf { SettingsStore.isCarPaired(mac) &&
            PairingEvidence.read(context.filesDir, mac).mayDropProfiles }
    }

    private fun knownWifiNetwork(context: Context, ssid: String): Network? {
        val networks = wifiNetworks(context)
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val knownReceiver = knownWifiReceiver(ssid) != null
        val usable = networks.filter {
            io.github.hypercar.net.CarWifiPolicy.canReuse(cm.getNetworkCapabilities(it),
                wifiJoiner?.ownsLiveRequest(it) == true || knownReceiver)
        }
        val exact = usable.filter { network ->
            val info = cm.getNetworkCapabilities(network)?.transportInfo as? android.net.wifi.WifiInfo
            info?.ssid?.trim('"') == ssid
        }
        // Some ROMs redact WifiInfo; one Wi-Fi handle is unambiguous. Never choose an arbitrary
        // network when concurrent station connections are present and SSID information is hidden.
        return exact.singleOrNull() ?: networks.singleOrNull()?.takeIf {
            val actual = (cm.getNetworkCapabilities(it)?.transportInfo as? android.net.wifi.WifiInfo)?.ssid?.trim('"')
            it in usable && (actual.isNullOrBlank() || actual.contains("unknown", true))
        }
    }

    /** VW may withhold 0x5703 until initial AirPlay pairing; advertise only for an explicitly selected peer. */
    private suspend fun prepareInvitationNetwork(context: Context, advertiser: CarPlayCtrlAdvertiser) {
        val attempt = connectionAttemptId
        val profile = headUnitProfile
        if (!profile.advertiseOnCurrentWifi) { resumeKnownCarNetwork(context, advertiser); return }
        val target = SettingsStore.targetCarMac
        if (!HeadUnitActivationPolicy.canAdvertiseCurrentWifi(profile, target, iap2?.activePeerAddress)) return
        val discovery = hotspotHandoff.discoveryToken()
        val ssid = if (discovery != null) currentWifiSsid(context) else null
        val network = ssid?.let { knownWifiNetwork(context, it) }
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!sessionLive.get() || connectionAttemptId != attempt || ctrlAdvertiser !== advertiser) return
            if (!target.equals(SettingsStore.targetCarMac, ignoreCase = true)) return
            if (!canApplyWifiDiscovery(advertiser, discovery)) return
            val verified = boundNetwork?.takeUnless { it == unverifiedInvitationNetwork }
            if (verified != null) {
                joinedSsid?.let { advertiser.ensureForNetwork(it, network = verified) }
                return
            }
            if (ssid == null || network == null) return
            val changed = boundNetwork != network || joinedSsid != ssid || wifiReceiverMac != target
            if (changed) {
                val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
                if (!adoptRetainedWifi(network, PairingEvidence.receiverKey(target))) return
                if (!cm.bindProcessToNetwork(network)) {
                    log("WIFI-INVITE: 当前 Wi-Fi 绑定失败，继续等车机热点/邀请")
                    return
                }
                wifiReceiverMac = target
                sessionWifiSsid = ssid
                boundNetwork = network
                unverifiedInvitationNetwork = network.takeUnless { knownWifiReceiver(ssid).equals(target, ignoreCase = true) }
                joinedNetwork = network
                joinedSsid = ssid
                watchSessionNetwork(context, network)
                log("WIFI-INVITE: profile=${profile.id} target=$target network=#$network；先广播等邀请，不推断已配对或主动直连")
            }
            advertiser.ensureForNetwork(ssid, network = network)
        } finally { autoConnectChanges.unlock() }
    }

    private class SelectedEndpoint(val endpoint: CarPlayEndpoint, val invitation: CtrlInvitation<Network>? = null)
    private class WifiCredentialsChanged : CancellationException()
    private class CtrlInvitationExpired : CancellationException()

    private fun usableCtrlInvitation(
        advertiser: CarPlayCtrlAdvertiser,
        selected: CtrlInvitation<Network>? = null,
    ): CtrlInvitation<Network>? {
        val invite = selected ?: advertiser.lastInvitation ?: return null
        val now = SystemClock.elapsedRealtime()
        if (invite.isOn(boundNetwork) && cachedHotspotMatchesTarget() &&
            HeadUnitActivationPolicy.acceptsInvitation(headUnitProfile, invite.receivedAtMillis, now)) return invite
        if (advertiser.discardInvitation(invite)) {
            log("CTRL: 忽略过期/跨网络/旧目标邀请 profile=${headUnitProfile.id} age=${now - invite.receivedAtMillis}ms inviteNetwork=${invite.network} currentNetwork=$boundNetwork")
        }
        return null
    }

    /** Called under autoConnectChanges by both known-network discovery paths. */
    private fun canApplyWifiDiscovery(advertiser: CarPlayCtrlAdvertiser, token: Any?): Boolean {
        val provisional = hotspotHandoff.provisionalNetwork
        if (provisional != null && boundNetwork == provisional && cachedHotspotMatchesTarget()) {
            joinedSsid?.let { advertiser.ensureForNetwork(it, network = provisional) }
        }
        return hotspotHandoff.canCommitDiscovery(token)
    }

    private suspend fun resumeKnownCarNetwork(context: Context, advertiser: CarPlayCtrlAdvertiser) {
        val attempt = connectionAttemptId
        val discovery = hotspotHandoff.discoveryToken()
        val retainedRequest = wifiJoiner
        val retainedNetwork = retainedRequest?.let { request ->
            request.currentNetwork?.takeIf { request.ownsLiveRequest(it) && request.isReady(it) }
        }
        val retainedSsid = joinedSsid?.takeIf {
            boundNetwork == null && retainedNetwork != null && joinedNetwork == retainedNetwork &&
                retainedRequest?.requiresDifferentHotspot(it) == false
        }
        val ssid = if (discovery != null) retainedSsid ?: currentWifiSsid(context) else null
        val mac = ssid?.let(::knownWifiReceiver)
        val network = if (ssid != null && mac != null)
            retainedNetwork?.takeIf { retainedSsid != null } ?: knownWifiNetwork(context, ssid) else null
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!sessionLive.get() || connectionAttemptId != attempt || ctrlAdvertiser !== advertiser) return
            if (!canApplyWifiDiscovery(advertiser, discovery) || ssid == null || mac == null || network == null) return
            if (retainedSsid != null && (retainedRequest == null || wifiJoiner !== retainedRequest ||
                    !wifiRecoveryLease.isRetained(retainedRequest) || !retainedRequest.ownsLiveRequest(network) ||
                    !retainedRequest.isReady(network) ||
                    knownWifiReceiver(ssid) != mac)) return
            val changed = boundNetwork != network || joinedSsid != ssid || wifiReceiverMac != mac
            if (changed) {
                val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
                if (!adoptRetainedWifi(network, PairingEvidence.receiverKey(mac))) return
                if (!cm.bindProcessToNetwork(network)) {
                    log("WIFI-RESUME: 已知车机网络绑定失败，等待下次检查")
                    return
                }
                wifiReceiverMac = mac
                CarProfileGuard.noteCar(mac)
                sessionWifiSsid = ssid
                boundNetwork = network
                watchSessionNetwork(context, network)
                joinedNetwork = network
                joinedSsid = ssid
                log("WIFI-RESUME: 绑定已知车机 Wi-Fi 并恢复 ctrl 广播，使用本车凭证")
            }
            unverifiedInvitationNetwork = null
            // 同一网络不会反复重注册；上次注册失败则允许重试。
            advertiser.ensureForNetwork(ssid, network = network)
        } finally { autoConnectChanges.unlock() }
    }

    /** Both Android loss callbacks are serialized here. A cached association is optional until activation. */
    private fun releaseLostProvisionalHotspot(
        context: Context, network: Network, expectedRequest: WifiJoiner? = null,
    ): Boolean {
        val request = hotspotHandoff.provisionalRequest(network) ?: return false
        if (boundNetwork != network || wifiJoiner !== request ||
            (expectedRequest != null && request !== expectedRequest)) return false
        wifiJoiner = null
        clearSessionWifiBinding(context)
        request.release(keepConnected = false)
        hotspotHandoff.clear(request)
        log("CAR-WIFI-RESUME: 缓存热点已断开，保留本轮蓝牙监听，等待新热点参数或车机邀请")
        return true
    }

    /** Called with autoConnectChanges held, before replacing a Wi-Fi association. */
    private fun clearSessionWifiBinding(context: Context) {
        val network = boundNetwork
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val monitor = sessionNetworkMonitor
        sessionNetworkMonitor = null
        monitor?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        boundNetwork = null // Also retires the ctrl listener and any invitation tied to this network.
        joinedNetwork = null
        joinedSsid = null
        wifiReceiverMac = null
        sessionWifiSsid = null
        wifiStationSnapshot = null
        if (network != null && cm?.boundNetworkForProcess == network) runCatching { cm.bindProcessToNetwork(null) }
    }

    private fun canStopAfterWifiLoss(credentials: CarWifiRequestIdentity?, ownerIsCurrent: () -> Boolean): Boolean {
        val inbox = activeEndpointInbox.get()?.takeIf { it.first == connectionAttemptId }?.second
        val activation = proactiveActivation.get()
        return inbox?.canStopAfterWifiLoss(credentials, !streaming && activation == null, activation, ownerIsCurrent)
            ?: ownerIsCurrent()
    }

    private fun recoverActivationWifiLoss(credentials: CarWifiRequestIdentity?, ownerIsCurrent: () -> Boolean): Boolean =
        synchronized(iap2EndpointChanges) {
            val inbox = activeEndpointInbox.get()?.takeIf { it.first == connectionAttemptId }?.second
            if (!sessionLive.get() || inbox == null || !ownerIsCurrent()) {
                return@synchronized false
            }
            val activation = proactiveActivation.get() ?: return@synchronized false
            if (!activation.interceptWifiLoss(credentials, SystemClock.elapsedRealtime())) return@synchronized false
            activation.wifiLoss?.let { loss ->
                val peer = sessionReceiverMac ?: iap2?.activePeerAddress ?: currentBluetoothHotspot(connectionAttemptId)?.peer ?: wifiReceiverMac
                val lost = loss.credentials
                if (peer != null && lost != null) iap2WifiHistory.forReceiver(peer).retireRecoveryCredential(lost.ssid, lost.passphrase)
                inbox.beginWifiRecovery(lost, loss.observedAt + WIFI_LOSS_CREDENTIAL_WAIT_MILLIS)
            }
            true
        }

    /** Decide and finish admitting work to the old connect job under the iAP2 producer's lock. */
    private fun commitWifiLoss(credentials: CarWifiRequestIdentity?, ownerIsCurrent: () -> Boolean): Boolean =
        synchronized(iap2EndpointChanges) {
            if (recoverActivationWifiLoss(credentials, ownerIsCurrent)) return@synchronized false
            if (!canStopAfterWifiLoss(credentials, ownerIsCurrent)) return@synchronized false
            connectJob?.cancel()
            true
        }

    private fun watchSessionNetwork(context: Context, expected: Network,
        credentials: CarWifiRequestIdentity? = wifiJoiner?.hotspotIdentity) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        sessionNetworkMonitor?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastCapabilities: String? = null
            private var lastLink: String? = null
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network != expected || sessionNetworkMonitor !== this) return
                val state = "wifi=${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}"
                if (state != lastCapabilities) { lastCapabilities = state; log("NET-WATCH: network=#$network $state") }
            }
            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
                if (network != expected || sessionNetworkMonitor !== this) return
                ctrlAdvertiser?.let { advertiser ->
                    synchronized(advertiser) {
                        if (sessionNetworkMonitor === this && boundNetwork == network && ctrlAdvertiser === advertiser)
                            advertiser.networkAddresses(network, properties.linkAddresses.map { it.address }.toSet())
                    }
                }
                val state = "if=${properties.interfaceName} addresses=${properties.linkAddresses} routes=${properties.routes}"
                if (state != lastLink) { lastLink = state; log("NET-WATCH: network=#$network $state") }
            }
            override fun onLost(network: Network) {
                if (network != expected || sessionNetworkMonitor !== this) return
                val owner = this
                if (recoverActivationWifiLoss(credentials) { sessionNetworkMonitor === owner && boundNetwork == network }) return
                scope.launch {
                    autoConnectChanges.lock()
                    try {
                        if (sessionNetworkMonitor === owner && boundNetwork == network) {
                            if (releaseLostProvisionalHotspot(context, network)) return@launch
                            if (!commitWifiLoss(credentials) { sessionNetworkMonitor === owner && boundNetwork == network }) {
                                log("WIFI: 旧网络丢失，保留本轮现场凭据更新")
                                return@launch
                            }
                            log("NET-WATCH: network=#$network lost；终止本轮网络，不切到其他网络")
                            sessionSockets?.takeIf { it.network == expected }?.invalidate()
                            val pending = connectJob
                            withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { pending?.join() }
                            runDisconnect("已知车机 Wi-Fi 已断开")
                        }
                    } finally { autoConnectChanges.unlock() }
                }
            }
        }
        sessionNetworkMonitor = callback
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback) }
            .onFailure { sessionNetworkMonitor = null; log("WIFI-RESUME: 会话网络监听失败：${it.message}") }
    }

    @Synchronized private fun startWifiReconnectMonitor(context: Context) {
        if (wifiReconnectMonitor != null || !automaticConnectionAllowed) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = queueWifiReconnectCheck()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = queueWifiReconnectCheck()
            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = queueWifiReconnectCheck()
        }
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            wifiReconnectMonitor = callback
            queueWifiReconnectCheck()
        }.onFailure { log("WIFI-RESUME: 网络监听未启动：${it.message}") }
    }

    @Synchronized private fun queueWifiReconnectCheck() {
        if (!automaticConnectionAllowed || hasCurrentSession || wifiReconnectCheck?.isActive == true) return
        wifiReconnectCheck = scope.launch {
            delay(750) // Wi-Fi capabilities/SSID often arrive after onAvailable.
            val context = appContext() ?: return@launch
            if (!automaticConnectionAllowed || hasCurrentSession || wifiNetworks(context).isEmpty()) return@launch
            val ssid = currentWifiSsid(context)
            val wakePeer = knownWifiReceiver(ssid) ?: return@launch
            if (ssid == null || knownWifiNetwork(context, ssid) == null) return@launch
            val receiver = PairingEvidence.receiverKey(wakePeer)?.let { "${headUnitProfile.id}:$it" }
            if (reconnectJob?.isActive == true || !reconnectBudget.mayWake(receiver)) return@launch
            log("WIFI-RESUME: 已接入已配对车机网络，恢复 ctrl 广播与监听，按车机激活状态建立 AirPlay")
            connectAutomatically(context, "known-wifi", wakePeer)
        }
    }

    @Synchronized private fun stopWifiReconnectMonitor() {
        wifiReconnectCheck?.cancel()
        wifiReconnectCheck = null
        val cm = appContext()?.getSystemService(ConnectivityManager::class.java)
        wifiReconnectMonitor?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        wifiReconnectMonitor = null
    }

    fun clearLogs() {
        _snapshots.value = _snapshots.value.copy(recentLogs = emptyList())
        // **清空后立刻补回环境自述**：否则用户点一下"清空"，后面的日志就再也没有
        // 设备/系统/SDK/App 版本了（反馈出去的日志无法定位版本，2026-10-25 实测踩到）。
        // 不走 log()：它在「记录已暂停」时会直接丢弃，而这一行恰恰是最该保留的。
        val ctx = appCtx ?: return
        val stamp = logTimeFormat.get()?.format(java.util.Date()) ?: ""
        val env = "$stamp  ENV: ${DeviceInfo.fullSummary(ctx)}"
        appendDiskLog(env)
        _snapshots.update { it.copy(recentLogs = it.recentLogs + env) }
    }

    // ---------------------------------------------------------------- 连接主流程

    private suspend fun runConnect(context: Context, diagnosticId: Long, wakePeer: String?, manual: Boolean,
                                   systemPrompt: SystemDiscoverabilityPrompt?, experimentTarget: String?,
                                   suppressManualWindow: Boolean) {
        if (!sessionLive.get() || connectionAttemptId != diagnosticId) return
        fun fail(reason: String) = HyperCarRepo.fail(reason, diagnosticId)
        val startupIssues = StartupRequirements.read(context).manualIssues
        if (startupIssues.isNotEmpty()) {
            fail("启动条件已变化：${startupIssues.joinToString("；")}")
            return
        }
        val bluetoothPermission = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val dependencies = ConnectionPreflight.State(
            bluetoothEnabled = runCatching { context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull(),
            bluetoothPermission = bluetoothPermission,
            wifiEnabled = runCatching { context.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled }.getOrNull(),
            shizukuReady = ShizukuTools.isReady,
        )
        val findings = ConnectionPreflight.findings(dependencies)
        val preflight = "bluetooth=${dependencies.bluetoothEnabled} permission=$bluetoothPermission " +
            "wifi=${dependencies.wifiEnabled} shizuku=${dependencies.shizukuReady}"
        connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.DEPENDENCIES_CHECKED, preflight)
        log("PREFLIGHT: $preflight；${findings.joinToString("；").ifBlank { "依赖检查通过" }}")
        var discoveryJob: Job? = null
        var readinessJob: Job? = null
        val caffSince = SystemClock.uptimeMillis()
        val caffBaseline = roundSessionBaseline
        val caffProbe = BmwCaffProbe(diagnosticId)
        var caffAction: ManualCaffAction? = null
        var caffJob: Job? = null
        var connectionEndpoints: Pair<Long, CarHotspotReconnect.EndpointInbox>? = null
        val invited = java.util.concurrent.atomic.AtomicBoolean(false)
        val caffTarget = SettingsStore.targetCarMac
        val caffDevice = if (headUnitProfile.id == "bmw_id85" && !headUnitProfile.allowOutboundIap2) runCatching {
            val devices = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.bondedDevices
            val bonded = devices.map { it.address }.toSet()
            val knownBmw = devices.filter {
                io.github.hypercar.bluetooth.CarTargetPolicy.acceptsAcl(
                    null, it.address, it.name.orEmpty(), knownCar = false, hasIap2 = false)
            }.map { it.address }.toSet()
            val mac = BmwCaffProbe.target("bmw_id85", caffTarget, wakePeer ?: aclCarMac, bonded, knownBmw)
            devices.firstOrNull { it.address == mac }
        }.getOrNull() else null
        try {
            if (!precheckDegradedMode(context)) return
            // Shizuku 从「必须」降级为「可选增强」：没有它照样能连，只是两条路径降级——
            //  WiFi 走 WifiNetworkSpecifier（热点首连弹一次系统确认框）+ deviceID 用稳定伪 MAC。
            if (!ShizukuTools.isReady) {
                // 降级模式预检：权限缺失/定位服务关闭时提前失败并给出可操作原因，
                // 不要等 requestNetwork 静默 onUnavailable 才让用户一头雾水。
                val first = !SettingsStore.degradedFirstConnectDone
                log(
                    "SHIZUKU: 未就绪——降级模式" +
                        if (first) "（热点首连需在系统确认框点「连接」，deviceID 用稳定伪 MAC）"
                        else "（热点已批准过，应静默；deviceID 用稳定伪 MAC）"
                )
            }
            push(link = LinkState.Negotiating("解析本机蓝牙身份"))
            // deviceID 不是「随便一个唯一值」：按 deviceID 过滤的接收端会逐字符比对。
            // WinPlay 就是拿它和「目标蓝牙设备」（Windows 里配对的经典蓝牙地址）比，
            // 不一致直接对会话级 SETUP 回 403（cpStack.ts 的 deviceFilter）。
            // 所以这里明确区分「真实 MAC」和「伪 MAC」，伪 MAC 时把后果写进日志。
            val coordinator = coordinatorFor(context, headUnitProfile)
            val phoneIdentity = coordinator.identityForSession()
            val deviceMac = phoneIdentity.address
            val usesFallbackPhoneIdentity = deviceMac == SettingsStore.stableDeviceId
            fallbackToRemember = phoneIdentity.takeIf { SettingsStore.manualBtMac == null && usesFallbackPhoneIdentity }
            if (sessionCarLifeMode && !prepareCarWithSource(context, diagnosticId, deviceMac, wakePeer)) {
                if (carWithEntries.current(diagnosticId)) fail("CarWith 源端守候服务准备失败")
                return
            }
            log(when {
                SettingsStore.manualBtMac.equals(deviceMac, ignoreCase = true) -> "BT: 使用高级设置已保存的手机标识；iAP2、mDNS 与 AirPlay 共用"
                deviceMac == SettingsStore.stableDeviceId -> "BT: 使用已有稳定手机标识；真实蓝牙地址未取得，严格接收端仍可能拒绝"
                else -> "BT: 使用已解析手机标识；iAP2、mDNS 与 AirPlay 共用"
            })

            // ---- iAP2 协商（蓝牙侧）----
            push(link = LinkState.Negotiating("等待车机蓝牙 iAP2 协商"))
            // 档案自述：现场日志第一眼就能看出「用的是哪份车机档案、几个关键开关是什么」，
            // 省得再靠日志反推（2026-10-25 图标回归就是被档案里三个布尔左右的）。
            val p = headUnitProfile
            log(
                "PROFILE: ${p.id}（${p.label}）0x4300=${p.sendCarPlayAvailability} " +
                    "EA=${p.openEaSessions} 0x4E0E=${p.sendTransportIdentifier} " +
                    "断HFP/A2DP=${p.dropProfilesBeforeIap2} 主动拨号=${p.allowOutboundIap2} 档案A退场=${p.abortWhenCarPlayWindowClosed} " +
                    "当前WiFi广播=${p.advertiseOnCurrentWifi} 新鲜邀请=${p.requireFreshCtrlInvite}"
            )
            // 等「可用」端点（有 IP），而不是等第一个回调：iAP2 两条消息分工不同——
            // 0x5703 只带热点凭据（无 IP/端口），0x4301 才带 AirPlay 端口与车机地址，
            // 谁先到由车机决定。WinPlay 实测 0x5703 先到、0x4301 晚 143ms：
            // 旧实现取首个回调就继续，于是在 19:46:57.019 立刻判「端点无可用 IP」失败，
            // 而真正可用的 ips=[192.168.137.1] 在下一条消息里被当成「冗余端点」丢掉。
            //
            // **真车死锁的解药**（2026-10-24 对照 cardeck 定谳）：0x5703 一到就立刻
            // 绑 WiFi + 广播 `_carplay-ctrl._tcp`——车机是在 WiFi 上发现该服务后
            // `GET /ctrl-int/1/connect` 邀请手机，**邀请成功才下发 0x4301**。
            // 若等 0x4301 才去入网，双方互等（旧实现就是这样，真车上永远拿不到端点）。
            val controlServiceName = io.github.hypercar.net.ControlServiceName.forProfile(
                p.protocolProfileId, io.github.hypercar.bluetooth.LocalDeviceName.snapshot())
            val advertiser = CarPlayCtrlAdvertiser(context, { deviceMac }, ::log, onDiagnostic = { event, port ->
                if (event == CarPlayCtrlAdvertiser.Event.INVITED && invited.compareAndSet(false, true)) {
                    caffProbe.cancel()
                    if (caffProbe.attempted) log("BT-CAFF#$diagnosticId: ctrl invitation received")
                }
                connectionDiagnostics.mark(diagnosticId, when (event) {
                    CarPlayCtrlAdvertiser.Event.REGISTERED -> ConnectionDiagnostics.Stage.CTRL_REGISTERED
                    CarPlayCtrlAdvertiser.Event.TCP_ACCEPTED -> ConnectionDiagnostics.Stage.CTRL_TCP_ACCEPTED
                    CarPlayCtrlAdvertiser.Event.INVITE_HEAD -> ConnectionDiagnostics.Stage.CTRL_INVITE_HEAD
                    CarPlayCtrlAdvertiser.Event.INVITE_ACK -> ConnectionDiagnostics.Stage.CTRL_INVITE_ACK
                    CarPlayCtrlAdvertiser.Event.INVITED -> ConnectionDiagnostics.Stage.CTRL_INVITED
                }, detail = if (event == CarPlayCtrlAdvertiser.Event.REGISTERED) port.toString() else "")
            }, serviceNameProvider = { controlServiceName }, attemptId = diagnosticId,
                traceDiscovery = ctrlDiscoveryTrace::record,
                onCtrlEpochChanged = { connectionDiagnostics.newCtrlEpoch(diagnosticId) }, onMdnsObservation = { family, port ->
                connectionDiagnostics.observe(diagnosticId,
                    if (port != null) ConnectionDiagnostics.Observation.MDNS_LOCAL_ANNOUNCEMENT
                    else ConnectionDiagnostics.Observation.MDNS_PEER_QUERY,
                    if (port != null) "IPv$family port=$port" else "IPv$family")
            }, onNetworkInvitation = { source, network, stillCurrent ->
                if (p.id == "bmw_id85" && sessionLive.get() && connectionAttemptId == diagnosticId &&
                    ctrlAdvertiser === source && iap2 === coordinator && network == boundNetwork) {
                    coordinator.retireCafeHandoff(diagnosticId, "ctrl-invite", stillCurrent)
                }
            })
            synchronized(ctrlNetworkLock) {
                if (!sessionLive.get() || connectionAttemptId != diagnosticId) return
                ctrlAdvertiser?.stop()
                advertiser.selectNetwork(boundNetwork)
                ctrlAdvertiser = advertiser
            }
            readinessJob = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                watchOldDeviceReadiness(context, diagnosticId, advertiser)
            }
            if (caffDevice != null) {
                fun waiting() = connectionAttemptId == diagnosticId && sessionLive.get() && !userDisconnectRequested &&
                    headUnitProfile.id == "bmw_id85" && SettingsStore.targetCarMac == caffTarget &&
                    iap2 === coordinator && coordinator.sessionStartCount == caffBaseline &&
                    !invited.get() && !hasAirPlayTransport
                if (experimentTarget != null && experimentTarget.equals(caffTarget, ignoreCase = true) && !sessionCarLifeMode) {
                    val requesting = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun manualWaiting() = waiting() && ctrlAdvertiser === advertiser && !advertiser.hasUnresolvedOrPublishedInvite
                    val action = ManualCaffAction(diagnosticId) dial@{
                        if (!requesting.compareAndSet(false, true)) return@dial "已有 …caff 探测正在进行"
                        try {
                            if (caffProbe.attempted) return@dial "本轮已尝试过 …caff，不重复拨号"
                            if (!manualWaiting() || caffProbe.finished)
                                return@dial "本轮不再等待车机，未拨号"
                            log("BT-CAFF#$diagnosticId: manual probe requested")
                            coordinator.pollBmwProbe(caffProbe, caffDevice, caffSince, ctrlReady = false,
                                manual = true, valid = ::manualWaiting)
                            when {
                                caffProbe.attempted -> "已执行一次 …caff 探测，请查看日志中的 SDP / RFCOMM 结果"
                                !manualWaiting() || caffProbe.finished -> "本轮已收到车机响应或不再等待，未拨号"
                                else -> "未拨号：请确认 App 正在监听，且目标车机的经典蓝牙已连接"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            if (caffProbe.attempted) caffProbe.cancel()
                            log("BT-CAFF#$diagnosticId: manual probe failed=${e.message}")
                            "…caff 探测出错，请查看诊断日志"
                        } finally {
                            requesting.set(false)
                        }
                    }
                    caffAction = action
                    manualCaffAction.updateAndGet { current ->
                        if (connectionAttemptId == diagnosticId && sessionLive.get()) action else current
                    }
                    log("BT-CAFF#$diagnosticId: developer experiment; automatic probe disabled for this round")
                } else {
                    caffJob = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                        try {
                            while (waiting() && !caffProbe.finished && !caffProbe.attempted) {
                                if (caffDevice.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) break
                                val network = boundNetwork
                                val ctrlReady = network != null && ctrlAdvertiser === advertiser &&
                                    caffDevice.address.equals(wifiReceiverMac, ignoreCase = true) && advertiser.listeningOn(network)
                                coordinator.pollBmwProbe(caffProbe, caffDevice, caffSince, ctrlReady, valid = ::waiting)
                                delay(300)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (e: Exception) {
                            caffProbe.cancel()
                            log("BT-CAFF#$diagnosticId: probe failed=${e.message}")
                        }
                    }
                }
            }
            val lastSeen = AtomicReference<CarPlayEndpoint?>(null)
            val baselineSessions = coordinator.sessionStartCount
            roundSessionBaseline = baselineSessions
            val discoveryAttempt = connectionAttemptId
            discoveryJob = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                var pickedAcl: AclDiscoverabilityEdge? = null
                fun aclListenerReady() = iap2 === coordinator && coordinator.isListening &&
                    !coordinator.isSessionActive && coordinator.sessionStartCount <= baselineSessions &&
                    !invited.get() && advertiser.lastInvitePeerIp == null
                try { ReconnectDiscoverability.watch(context,
                    networkKey = {
                        val mac = wifiReceiverMac
                        val network = boundNetwork
                        val ssid = sessionWifiSsid
                        val target = SettingsStore.targetCarMac
                        if (network != null && mac != null && ssid != null &&
                            (target.isNullOrBlank() || target.equals(mac, ignoreCase = true))) "$network|$ssid|$mac" else null
                    },
                    servicesReady = { coordinator.isListening && advertiser.isAdvertising },
                    sessionStarted = { !sessionLive.get() || connectionAttemptId != discoveryAttempt || userDisconnectRequested ||
                        coordinator.isSessionActive || coordinator.sessionStartCount > baselineSessions ||
                        invited.get() || advertiser.lastInvitePeerIp != null || streaming },
                    immediatePreparation = manual,
                    systemPrompt = systemPrompt,
                    suppressManualWindow = suppressManualWindow,
                    takeAclPreparationKey = take@{
                        val edge = aclDiscoverabilityEdge.get() ?: return@take null
                        if (!edge.ticket.pendingFor(discoveryAttempt) || !aclListenerReady() ||
                            !aclDiscoverabilityStillValid(edge, discoveryAttempt) || !edge.ticket.take(discoveryAttempt)) return@take null
                        pickedAcl = edge
                        edge.ticket.peer
                    },
                    aclPreparationStillValid = { peer ->
                        val edge = pickedAcl
                        edge != null && edge.ticket.peer == peer && edge.ticket.takenBy(discoveryAttempt) && aclListenerReady() &&
                            aclDiscoverabilityStillValid(edge, discoveryAttempt)
                    },
                    discoverableStillUseful = {
                        var handoff: DiscoverabilityHandoff?
                        var live: Boolean
                        var liveAttempt: Long
                        do {
                            handoff = discoveryHandoff
                            live = sessionLive.get()
                            liveAttempt = connectionAttemptId
                            // If teardown or successor publication changed the handoff during
                            // these reads, don't use the mixed snapshot to close the window.
                        } while (handoff !== discoveryHandoff)
                        val sameRound = live && liveAttempt == discoveryAttempt
                        val manualHandoff = handoff?.keepsWindow(discoveryAttempt, liveAttempt, live) == true
                        !streaming && !hasAirPlayTransport && !userDisconnectRequested && when {
                            sameRound -> !coordinator.isSessionActive && coordinator.sessionStartCount <= baselineSessions &&
                                !invited.get() && advertiser.lastInvitePeerIp == null
                            manualHandoff -> iap2?.isSessionActive != true &&
                                (iap2?.sessionStartCount ?: 0) <= roundSessionBaseline && ctrlAdvertiser?.lastInvitePeerIp == null
                            else -> false
                        }
                    },
                    preparationKey = preparation@{
                        if (!manual && p.protocolProfileId != "bmw_id85") return@preparation null
                        val target = SettingsStore.targetCarMac
                        val allowed = sessionLive.get() && connectionAttemptId == discoveryAttempt && !userDisconnectRequested
                        val listening = coordinator.isListening
                        if (!allowed || !listening || (!manual && target.isNullOrBlank() && boundNetwork != null)) return@preparation null
                        runCatching {
                            val devices = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
                            ReconnectDiscoverability.preparationKey(p.protocolProfileId, manual, target, listening, allowed, SettingsStore.pairedCars) { mac ->
                                devices.firstOrNull { it.address.equals(mac, ignoreCase = true) }?.let { device ->
                                    runCatching { device.name }.getOrNull().orEmpty()
                                }
                            }
                        }.getOrNull()
                    },
                    log = ::log,
                ) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Exception) {
                    log("BT-DISCOVERY: 可发现兜底失败：${t.message}，保留原连接流程")
                }
            }
            // **等端点超时也不拆 iAP2**（2026-10-24 iPhone HCI 对照 / 2026-10-25 现场）：
            // 真 iPhone 在拿到 0x5703 后 ≥34s 不发任何 iAP2 报文、也从不主动拆链；
            // 而「拆除会让车机收起 CarPlay 图标」——一旦车机把本机从 CarPlay 设备里摘掉，
            // 它连 iAP2 都不再发起（10-25 日志：10 分钟里 0 次连入）。所以这里改成
            // 「超时只提示、不拆链，继续下一轮等」，直到用户断开 / ACL 断开 / 拿到端点。
            val joinHints = AtomicReference<CarPlayEndpoint?>(null)
            val lastRequestedCredentials = AtomicReference<CarWifiRequestIdentity?>(null)
            val wifiHistory = iap2WifiHistory
            val endpoints = CarHotspotReconnect.EndpointInbox(
                iap2EndpointChanges, standbyEndpoint, SystemClock::elapsedRealtime,
                isRetired = { endpoint ->
                    endpoint.bluetoothHotspot?.let { hotspot ->
                        val history = wifiHistory.forReceiver(hotspot.peer)
                        endpoint.passphrase?.let { history.isRetiredRecoveryCredential(hotspot.ssid, it) } == true ||
                            endpoint.replayedWifiConfiguration?.let { history.isRetiredRecoveryCredential(it.ssid, it.passphrase) } == true
                    } == true
                },
                hasProposedCredentials = { endpoint ->
                    endpoint.bluetoothHotspot?.let { hotspot ->
                        endpoint.canRequestHotspot(hotspot) &&
                            hotspot.applies(diagnosticId, connectionAttemptId, coordinator.activePeerAddress, SettingsStore.targetCarMac) &&
                            WifiJoiner.canRequestHotspot(endpoint.ssid, endpoint.passphrase)
                    } == true
                },
            ) {
                hasFreshWifiCredentials(it, diagnosticId)
            }
            fun publishEndpointHint(endpoint: CarPlayEndpoint) {
                endpoint.whileSourceCurrent {
                    rememberHotspot(diagnosticId, endpoint)
                    val transition = endpoints.classifyHintTransition(joinHints.get(), endpoint)
                    if (transition.retiredSsidAddresses.isNotEmpty()) {
                        retiredSsidAirplayAddresses.updateAndGet { it + transition.retiredSsidAddresses }
                    }
                    if (transition.invalidateLateAddress) lateAirplayEndpoint.set(null)
                    joinHints.set(mergeEndpoint(transition.mergeFrom, endpoint))
                }
            }
            connectionEndpoints = diagnosticId to endpoints
            activeEndpointInbox.set(connectionEndpoints)
            coordinator.start { propose ->
                synchronized(iap2EndpointChanges) {
                    if (connectionAttemptId != diagnosticId || !sessionLive.get() || iap2 !== coordinator) return@start false
                    val update = propose() ?: return@start false
                    // Check deferral before looking at the currently bound WLAN.
                    val endpoint = update.endpoint
                    fun publish(endpoint: CarPlayEndpoint) {
                        if (!endpoint.sourceIsCurrent) return
                        connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.IAP2_PARAMETERS,
                            "wifiCredentials=${!endpoint.ssid.isNullOrBlank()} endpoint=${endpoint.ipAddresses.isNotEmpty()}")
                        recordLateAirplayEndpoint(context, coordinator, diagnosticId, endpoint)
                    }
                    val accepted = endpoints.offer(
                        endpoint,
                        onDeferredAccept = { released ->
                            update.commitDeferred(released)
                            publishEndpointHint(released)
                        },
                        onDeferredAddress = { released ->
                            publishEndpointHint(released)
                            publish(released)
                        },
                        onDeferredPublish = ::publish,
                        onAccept = {
                            update.commit()
                            publishEndpointHint(endpoint)
                        },
                    )
                    if (accepted) publish(endpoint)
                    accepted
                }
            }
            var invitationOnly = false
            val cacheAttempt = CarHotspotReconnect.RestoreAttempt(if (manual) null else cachedHotspotAcl)
            var lastCacheSkip: String? = null
            fun cachedHotspotTrigger(): CarHotspotReconnect.RestoreAttempt.Trigger? {
                val receiver = SettingsStore.targetCarMac
                val allowed = manual || automaticConnectionAllowed
                val trigger = if (allowed && receiver != null) cacheAttempt.candidate(receiver, SystemClock.elapsedRealtime()) else null
                val skip = when {
                    receiver == null -> "target_not_selected"
                    !allowed -> "automatic_disabled_or_paused"
                    trigger == null -> if (manual) return null else "no_recent_target_acl"
                    else -> carHotspotCacheBlockReason(context, p.id, receiver)
                }
                if (skip != null) {
                    if (skip != lastCacheSkip) {
                        lastCacheSkip = skip
                        log("CAR-WIFI-RESUME: attempt=$diagnosticId trigger=${if (manual) "manual" else "acl"} skipped=$skip")
                    }
                    return null
                }
                return trigger
            }
            suspend fun waitForInvitation(
                reason: String,
            ) {
                invitationOnly = true
                awaitingCarInvitation = true
                sessionSockets?.invalidate() // Retire only the failed engine's socket factory, not Wi-Fi.
                lateAirplayEndpoint.set(null)
                lastSeen.set(null)
                airplayHost = null
                airplayPort = 0
                connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.WAITING_INVITATION, reason)
                connectionDiagnostics.report().lastOrNull()?.let { log(it) }
                log("ACTIVATION: $reason；保留身份、Wi-Fi 与广播，等待车机新邀请或 iAP2 端点")
                push(link = LinkState.Negotiating("等待车机开启 CarPlay"))
            }
            activation@ while (sessionLive.get()) {
                var candidate: SelectedEndpoint? = null
                var waitRounds = 0
                while (candidate == null) {
                    candidate =
                        awaitUsableEndpoint(
                            context, coordinator, lastSeen, advertiser, invitationOnly, usesFallbackPhoneIdentity,
                            manual, endpoints, joinHints, lastRequestedCredentials, ::cachedHotspotTrigger,
                            { trigger, request -> cacheAttempt.submit(trigger, SystemClock.elapsedRealtime(), request) },
                        )
                            ?: if (invitationOnly) null else gatewayFallbackEndpoint(context, lastSeen.get())?.let { SelectedEndpoint(it) }
                    if (candidate == null) {
                        waitRounds++
                        val seen = lastSeen.get()
                        val reason =
                            if (seen == null) {
                                if (coordinator.sessionStartCount == 0) {
                                    "车机未发起 iAP2（ACL 已连但收不到 …cafe 连入）——" +
                                        "请在车机确认 Apple CarPlay 已启用；继续保留广播与监听"
                                } else {
                                    "车机连入了 iAP2 但没给 0x5703/0x4301——请在车机上点一次 Apple CarPlay"
                                }
                            } else {
                                "车机未给出可用 AirPlay 地址（ssid=${seen.ssid ?: "-"} ips=${seen.ipAddresses}）"
                            }
                        log(
                            "SESSION: 第 ${waitRounds} 轮等待结束（${reason}）——" +
                                "**保持 iAP2 链路不拆**，继续等（拆链会让车机收起 CarPlay 图标）",
                        )
                        push(link = LinkState.Negotiating("等车机 CarPlay（保持 iAP2，不拆链）"))
                    }
                }
                var endpoint = candidate.endpoint
                val selectedInvite = candidate.invitation
                if (!endpoint.sourceIsCurrent) continue@activation
                if (endpoints.credentialsChanged { it.ssid == endpoint.ssid && it.passphrase == endpoint.passphrase }) {
                    log("CAR-WIFI-RESUME: 端点选定后收到现场热点凭据，优先处理凭据")
                    continue@activation
                }

                // ---- 静默入车机热点 + 绑网 ----
                push(link = LinkState.Negotiating("静默加入车机热点"))
                val proposedHost = pickHost(endpoint)
                if (proposedHost == null) {
                    // awaitUsableEndpoint 已保证有 IP，这里只是防御
                    fail("端点无可用 IP（0x5703 有凭据、0x4301 未给出地址）")
                    return
                }
                // 端点定谳：把「凭什么地址」摊开写清楚（真车排查全靠这一行）
                log(
                    "CONNECT +${stageMs()}ms 端点确定：$proposedHost:${endpoint.airPlayPort}" +
                        " ssid=${endpoint.ssid ?: "-"}" +
                        " 有密码=${!endpoint.passphrase.isNullOrBlank()}" +
                        " ips=${endpoint.ipAddresses}" +
                        " gateway=${endpoint.gateway ?: "-"}" +
                        " devId=${endpoint.deviceIdentifier ?: "-"}" +
                        " srcvers=${endpoint.sourceVersion ?: "-"}",
                )
                val network = joinAndBind(context, endpoint, selectedInvite, endpoints)
                if (!endpoint.sourceIsCurrent) continue@activation
                if (network != null) connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.WIFI_BOUND)
                if (network == null) {
                    if (endpoints.credentialsChanged { it.ssid == endpoint.ssid && it.passphrase == endpoint.passphrase }) {
                        log("CAR-WIFI-RESUME: 入网期间收到现场热点凭据，优先处理凭据")
                        continue@activation
                    }
                    if (selectedInvite != null) {
                        advertiser.discardInvitation(selectedInvite)
                        waitForInvitation("所选邀请的入网复核未通过")
                        continue@activation
                    }
                    fail("车机热点入网失败：${wifiJoiner?.lastFailureReason ?: "没有匹配本车且可绑定的有效网络"}")
                    return
                }
                val verified = verifyBoundAirplayEndpoint(context, endpoint, network)
                val host = verified?.let(::pickHost)
                if (verified == null || host == null) {
                    log("ENDPOINT: 仅含地址的 0x4301 不属于当前绑定网络，继续等待车机端点或邀请")
                    continue@activation
                }
                endpoint = verified
                val sockets = SessionSocketLease(network)
                sessionSockets?.invalidate()
                sessionSockets = sockets
                watchSessionNetwork(context, network, endpoint.hotspotIdentity() ?: wifiJoiner?.hotspotIdentity)
                log("NET: 本轮 socket 固定使用网络 #$network；${describeNetwork(context, network)}")
                // **不再预探测端口**：老代码在这里对 7000/7001/7100/5000/62078 各 connect 一次再 close
                // （只为打日志），但很多接收端（DiPlay/xcertplay 这类单控制器实现、以及部分车机）
                // 会把「连上又立刻断开」当成控制器掉线，随即关掉 AirPlay 服务——于是我们真正去连时
                // 就只剩 ECONNREFUSED（2026-10-26 12:47 桌面联调现场：54.3s 探测 7000=开，
                // 57.3s 真连 7000 全 refused）。连接尝试本身就会把「开/关」打进日志，不需要额外探。

                // ---- 定端口：mDNS 自报优先，拿不到再试 7000/7001/7100 ----
                push(link = LinkState.Negotiating("查找车机 AirPlay 服务"))
                // 车机档案决定端口策略：宝马走 mDNS（车机确实广播 _airplay._tcp），
                // WinPlay 是固定 7000 的普通接收端——先问 mDNS 只会白等 3 秒。
                val headUnit = headUnitProfile
                // 端点来自车机 ctrl 邀请时**跳过 mDNS**：地址与端口都是车机亲口给的
                // （2026-10-25 车友日志：邀请后还去 browse 3s，结果 PTR/SRV/A 全 0——
                //  这台车根本不广播 `_airplay._tcp`，而车机 20s 后就把 RFCOMM 拆了）。
                // 同理 **0x4301 已经给出地址+端口时也跳过**：那同样是车机亲口说的
                // （2026-10-26 12:47 桌面联调：0x4301 在 54.17s 就给了 192.168.1.208:7000，
                //  我们还去等 3s mDNS，结果 57.3s 才去连、接收端已经把 7000 关了）。
                val haveEndpoint = endpoint.ipAddresses.isNotEmpty() && endpoint.airPlayPort > 0
                val desktop = io.github.hypercar.headunit.ReceiverCompatibility.isDesktopReceiver(headUnit.id,
                    io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(iap2?.activePeerAddress))
                val mdnsEndpoint = if (!endpoint.fromInvite && (desktop || (headUnit.preferMdnsAirplay && !haveEndpoint))) {
                    AirplayServiceProbe(context, ::log).browse(boundNetwork, accept = ::matchesAirplayService)
                } else {
                    when {
                        endpoint.fromInvite ->
                            log("MDNS: 端点来自 ctrl 邀请（${endpoint.ipAddresses}）——跳过 mDNS 查询")
                        haveEndpoint ->
                            log("MDNS: 已有${if (endpoint.proactive) "主动发现/缓存" else "iAP2"}端点 ${endpoint.ipAddresses}:${endpoint.airPlayPort}——跳过 mDNS 查询")
                    }
                    null
                }
                val apHost = mdnsEndpoint?.host ?: host
                val candidatePorts = if (mdnsEndpoint != null) {
                    listOf(mdnsEndpoint.port)
                } else if (haveEndpoint) {
                    listOf(endpoint.airPlayPort)
                } else {
                    headUnit.fallbackAirplayPorts.distinct().ifEmpty { listOf(7000) }
                }
                log(
                    "CONNECT +${stageMs()}ms AirPlay 目标：" +
                        if (mdnsEndpoint != null) {
                            "$apHost:${mdnsEndpoint.port}（mDNS _airplay._tcp，实例=${mdnsEndpoint.instance}" +
                                (mdnsEndpoint.model?.let { "，model=$it" } ?: "") + "）"
                        } else {
                            "$apHost 候选端口=$candidatePorts（mDNS 未拿到，逐个试连）"
                        },
                )
                airplayHost = apHost

                if ((selectedInvite != null || headUnitProfile.requireFreshCtrlInvite) &&
                    usableCtrlInvitation(advertiser, selectedInvite)?.host?.let { sameControlAddress(it, apHost) } != true) {
                    waitForInvitation("车机邀请已过期或端点已改变，等待新邀请")
                    continue@activation
                }
                // ---- 连 AirPlay 控制端口并移交 FD ----
                if (endpoint.proactive && !endpoint.fromInvite &&
                    usableCtrlInvitation(advertiser)?.host?.let { sameControlAddress(it, apHost) } != true) {
                    waitForInvitation("发现的端点缺少本轮车机邀请")
                    continue@activation
                }
                push(link = LinkState.Negotiating("连接车机 AirPlay 端口"))
                val carNetworkSsid = currentWifiSsid(context)
                val requiresInvitation = selectedInvite != null || headUnitProfile.requireFreshCtrlInvite || endpoint.proactive
                val candidateHosts = mdnsEndpoint?.addresses ?: endpoint.ipAddresses.takeIf { it.isNotEmpty() }
                    ?: listOf(apHost)
                fun checkBeforeDial() {
                    if (endpoints.credentialsChanged { fresh ->
                        wifiJoiner?.ownsHotspot(network, fresh.ssid, fresh.passphrase) == true ||
                            (!endpoint.fromInvite && endpoint.ssid == fresh.ssid && endpoint.passphrase == fresh.passphrase)
                    }) throw WifiCredentialsChanged()
                    if (requiresInvitation && usableCtrlInvitation(advertiser, selectedInvite) == null) {
                        throw CtrlInvitationExpired()
                    }
                }
                val connected = try {
                    checkBeforeDial()
                    connectAirplayEndpoint(context, sockets,
                        (listOf(apHost) + candidateHosts).distinct().flatMap { address ->
                            val verifiedNetwork = endpoint.verifiedAirplayNetworkHandle.takeIf { mdnsEndpoint == null }
                            candidatePorts.map { AirplayTarget(address, it, verifiedNetwork, endpoint.sourceGeneration) }
                        }, advertiser, selectedInvite, ::checkBeforeDial)
                } catch (_: WifiCredentialsChanged) {
                    sockets.invalidate()
                    log("CAR-WIFI-RESUME: 拨号阶段收到现场热点凭据，返回入网")
                    continue@activation
                } catch (_: CtrlInvitationExpired) {
                    waitForInvitation("拨号阶段所选邀请失效")
                    continue@activation
                } catch (_: AirplayEndpointChanged) {
                    log("ENDPOINT: 拨号前 iAP2 链路已重新协商，重新选择端点")
                    continue@activation
                }
                if (connected == null) {
                    if (!endpoint.sourceIsCurrent) continue@activation
                    if (selectedInvite != null || endpoint.proactive) {
                        selectedInvite?.let(advertiser::discardInvitation)
                        waitForInvitation("主动控制端口不可用；候选地址已失效")
                        continue@activation
                    }
                    fail("AirPlay 端点暂不可用；已失效旧缓存并重新发现，等待车机下一轮邀请")
                    return
                }
                val apHostFinal = connected.target.host
                val port = connected.target.port
                val controlFd = connected.socket
                val activationInvite = usableCtrlInvitation(advertiser, selectedInvite)
                if ((requiresInvitation || activationInvite != null) &&
                    activationInvite?.host?.let { sameControlAddress(it, apHostFinal) } != true) {
                    runCatching { ParcelFileDescriptor.adoptFd(controlFd).close() }
                    if (selectedInvite == null && activationInvite != null) {
                        log("ENDPOINT: 移交前收到新 ctrl 邀请 ${activationInvite.host}，重新选择端点")
                        continue@activation
                    }
                    waitForInvitation("连接期间车机邀请失效，已关闭候选 socket，等待新邀请")
                    continue@activation
                }
                val activation = ProactiveActivation()
                val started = try {
                    startNativeForSession(controlFd, sockets, diagnosticId, activation, advertiser, connected.target, { current ->
                        checkBeforeDial()
                        val currentInvite = usableCtrlInvitation(advertiser, selectedInvite)
                        if (requiresInvitation && currentInvite == null) throw CtrlInvitationExpired()
                        if (currentInvite != null && !sameControlAddress(currentInvite.host, apHostFinal)) throw AirplayEndpointChanged()
                        if (!current.sourceIsCurrent || !current.allowsNetwork(sockets.network.networkHandle))
                            throw AirplayEndpointChanged()
                        val newest = lateAirplayTarget(context, sockets.network, lateAirplayEndpoint.get(), currentInvite?.host)
                        if (newest != null && !current.sameRoute(newest)) throw AirplayEndpointChanged()
                        newest?.let(current::withRouteEvidence) ?: current
                    },
                        rememberEndpoint = { rememberAirplayEndpoint(carNetworkSsid, connected.target) }) {
                        airplayHost = apHostFinal
                        airplayPort = port
                        push(link = LinkState.Negotiating("Rust 核心层协商 AirPlay 会话"))
                        sessionWifiSsid = carNetworkSsid
                        val receiver = receiverForEngine()
                        val identity = PairingIdentityFiles.location(context.filesDir, receiver)
                        ensurePairingIdentity(identity)
                        sessionIdentity = identity
                        val identityPath = identity.file.absolutePath
                        val json = SettingsStore.engineConfigJson(apHostFinal, port, deviceMac, identityPath,
                            receiverBluetoothMac = receiver, carWithSource = sessionCarWithMode,
                            receiverModel = coordinator.activePeerAddress?.takeIf { it.equals(receiver, ignoreCase = true) }
                                ?.let { io.github.hypercar.iap2.Iap2Peer.modelFor(it) } ?: mdnsEndpoint?.model,
                            headUnitProfile = headUnitProfile,
                            systemCarLifeSource = sessionSystemCarLife)
                        log("SESSION-ORIGIN: ctrlInvite=${advertiser.lastInvitation != null} endpointFromInvite=${endpoint.fromInvite} " +
                            "bluetoothSession=${coordinator.isSessionActive} knownWifi=${wifiReceiverMac != null} network=$boundNetwork")
                        log("CORE +${stageMs()}ms 引擎配置：$json")
                        json
                    }
                } catch (_: WifiCredentialsChanged) {
                    sockets.invalidate()
                    log("CAR-WIFI-RESUME: 移交控制 socket 前收到现场热点凭据，返回入网")
                    continue@activation
                } catch (_: CtrlInvitationExpired) {
                    waitForInvitation("移交控制 socket 前所选邀请失效")
                    continue@activation
                } catch (_: AirplayEndpointChanged) {
                    log("ENDPOINT: 移交控制 socket 前收到新邀请或 iAP2 端点，重新选择端点")
                    continue@activation
                }
                log("CORE: nativeStart 返回 $started")
                if (!started) {
                    activation.interceptTerminal("核心层启动未完成；保留链路等待新邀请（查看旧引擎清理日志）")
                }
                // Teardown owns nativeStop. A stale startup continuation must never stop a newer engine.
                autoConnectChanges.lock()
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!sessionLive.get() || connectionAttemptId != diagnosticId || sessionSockets !== sockets) return
                    streaming = true
                } finally { autoConnectChanges.unlock() }
                val rejected = activation.result.await()
                if (rejected == null) {
                    proactiveActivation.compareAndSet(activation, null)
                    endpoints.finishRecovery()
                    break@activation
                }
                val wifiLoss = autoConnectChanges.withLock {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!sessionLive.get() || connectionAttemptId != diagnosticId || sessionSockets !== sockets) return
                    engineCallbacks.retire()
                    SettingsStore.receiverMaxFps = null
                    io.github.hypercar.carui.CarDisplayLayout.viewAreaChoices.value = null
                    io.github.hypercar.carui.CarReceiverUi.reset()
                    // Stop only the failed media engine. Keep the same identity, Wi-Fi lease,
                    // RFCOMM session and ctrl listener/port alive for the receiver's next invite.
                    streaming = false
                    // Keep the source service and launch alive across media activations.
                    if (!sessionCarLifeMode) {
                        sessionUiJob?.cancel()
                        sessionUiJob = null
                    }
                    CarUiCaster.stopAsync()
                    DexCaster.stopAsync()
                    MusicBridge.onCarDisconnected()
                    // Every failed activation consumes its invitation. CAS preserves a newer
                    // invitation arriving during negotiation, even from the same address.
                    if (activation.requiresFreshInvitation && activationInvite != null)
                        advertiser.discardInvitation(activationInvite)
                    waitForInvitation(rejected)
                    RustCoreBridge.nativeStop()
                    val lost = synchronized(iap2EndpointChanges) {
                        proactiveActivation.compareAndSet(activation, null)
                        activation.wifiLoss
                    }
                    if (lost != null && boundNetwork == sockets.network) {
                        val request = wifiJoiner
                        wifiJoiner = null
                        clearSessionWifiBinding(context)
                        if (request != null) {
                            hotspotHandoff.clear(request)
                            request.release(keepConnected = false)
                            if (wifiRecoveryLease.isRetained(request)) releaseRetainedWifi()
                        }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!sessionLive.get() || connectionAttemptId != diagnosticId) return
                    lost
                }
                if (wifiLoss != null) {
                    val deadline = wifiLoss.observedAt + WIFI_LOSS_CREDENTIAL_WAIT_MILLIS
                    val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    log("CAR-WIFI-RESUME: attempt=$diagnosticId 旧 Wi-Fi 已断开，等待车机更新凭据 remainingMs=$remaining")
                    push(link = LinkState.Negotiating("等待车机更新 Wi-Fi 凭据"))
                    val replacement = endpoints.awaitReplacementCredentials(
                        wifiLoss.credentials, deadline, ::publishEndpointHint,
                    ) { selected ->
                        val hotspot = checkNotNull(selected.bluetoothHotspot)
                        wifiHistory.forReceiver(hotspot.peer).restoreSelected(
                            hotspot.ssid, checkNotNull(selected.passphrase), wifiLoss.credentials,
                        )
                    }
                    if (replacement == null) {
                        fail("旧 Wi-Fi 断开后未收到新的车机热点凭据")
                        return
                    }
                    log("CAR-WIFI-RESUME: attempt=$diagnosticId 已收到新凭据，继续本轮入网")
                    val ssid = checkNotNull(replacement.ssid)
                    val password = checkNotNull(replacement.passphrase)
                    lastRequestedCredentials.set(CarWifiRequestIdentity(ssid, password))
                    val network = joinAndBind(context, replacement, replaceAssociation = true, lockedRecoveryAttempt = diagnosticId)
                    if (network != null && replacement.sourceIsCurrent) {
                        lastSeen.set(replacement)
                        connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.WIFI_BOUND)
                        advertiser.ensureForNetwork(ssid, network = network)
                    }
                    var previousCredentials: CarPlayEndpoint = replacement
                    endpoints.finishRecoveryJoin { deferred ->
                        val hotspot = deferred.bluetoothHotspot ?: return@finishRecoveryJoin false
                        val deferredPassword = deferred.passphrase ?: return@finishRecoveryJoin false
                        if (!sessionLive.get() || connectionAttemptId != diagnosticId || iap2 !== coordinator ||
                            !hotspot.applies(diagnosticId, diagnosticId, coordinator.activePeerAddress, SettingsStore.targetCarMac)) {
                            return@finishRecoveryJoin false
                        }
                        val receiverHistory = wifiHistory.forReceiver(hotspot.peer)
                        if (receiverHistory.isRetiredRecoveryCredential(hotspot.ssid, deferredPassword)) return@finishRecoveryJoin false
                        if (!deferred.addressOnlyAirplayReport &&
                            (previousCredentials.ssid != deferred.ssid || previousCredentials.passphrase != deferred.passphrase)) {
                            val previousHotspot = checkNotNull(previousCredentials.bluetoothHotspot)
                            wifiHistory.forReceiver(previousHotspot.peer).retireRecoveryCredential(
                                previousHotspot.ssid, checkNotNull(previousCredentials.passphrase),
                            )
                            previousCredentials = deferred
                        }
                        true
                    }
                    if (network == null || !replacement.sourceIsCurrent) {
                        if (endpoints.shouldRetryJoin(replacement)) {
                            log("CAR-WIFI-RESUME: 已选凭据入网未完成，等待本轮有效热点凭据")
                            continue@activation
                        }
                        fail("车机热点重新入网失败：${wifiJoiner?.lastFailureReason ?: "没有匹配本车的有效网络"}")
                        return
                    }
                }

            }

        } catch (cancelled: CancellationException) {
            // disconnect() 掐掉在途连接：正常取消，不算失败（也不要把 Failed 写进快照）
            throw cancelled
        } catch (t: Throwable) {
            fail("会话异常: ${t.message}")
        } finally {
            connectionEndpoints?.let {
                activeEndpointInbox.compareAndSet(it, null)
                it.second.close()
            }
            caffProbe.cancel()
            caffAction?.let { manualCaffAction.compareAndSet(it, null) }
            caffJob?.cancel()
            if (caffProbe.attempted && !invited.get()) log("BT-CAFF#$diagnosticId: wait ended without ctrl invitation")
            discoveryJob?.cancel()
            readinessJob?.cancel()
            if (connectionAttemptId == diagnosticId) connecting = false
            appContext()?.let { io.github.hypercar.carui.MusicNotificationListener.sync(it) }
        }
    }

    private suspend fun watchOldDeviceReadiness(context: Context, attempt: Long, advertiser: CarPlayCtrlAdvertiser) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        fun current() = sessionLive.get() && connectionAttemptId == attempt && ctrlAdvertiser === advertiser &&
            !userDisconnectRequested
        fun publish(next: OldDeviceReadiness?) {
            synchronized(ctrlNetworkLock) {
                val previous = _oldDeviceReadiness.value
                if (previous == next || (next != null && !current()) ||
                    (next == null && previous?.attempt != attempt)) return
                _oldDeviceReadiness.value = next
            }
            ctrlDiscoveryTrace.enqueue("OLD-DEVICE-READY: ${next?.diagnosticFields() ?: "attempt=$attempt ready=false state=inactive"} " +
                "epochMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()}")
        }
        try {
            while (current()) {
                if (streaming || hasAirPlayTransport) {
                    publish(null)
                } else {
                    val network = boundNetwork?.takeIf {
                        cm.boundNetworkForProcess == it &&
                            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                    val scanMode = if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        runCatching { adapter?.scanMode }.getOrNull()
                    } else null
                    val discoverable = scanMode?.takeUnless { it == BluetoothAdapter.ERROR }
                        ?.let { it == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }
                    val srv = network?.let(advertiser::currentSrv)?.let { OldDeviceReadiness.Srv(it.host, it.port) }
                    if (network == null || network == boundNetwork) {
                        publish(OldDeviceReadiness(attempt, network?.toString(), srv, discoverable))
                    }
                }
                delay(300)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("OLD-DEVICE-READY: attempt=$attempt state=unavailable error=${error.javaClass.simpleName}")
        } finally {
            publish(null)
        }
    }

    /**
     * 会话拆除（幂等）：只有真正还活着的会话才拆一次。
     *
     * 幂等闸门不可省：ACL 断开广播 + service 的 ACTION_STOP 转交会让本方法被重复调用，
     * 每调一次都 nativeStop + 重建快照 + 记一行日志，历史版本正是在这里配合
     * ACTION_STOP 回灌，把「断开」刷成了每秒几百条。
     */
    private suspend fun runDisconnect(
        reason: String,
        keepListening: Boolean = AutomaticConnectionPolicy.keepListeningAfterStop(SettingsStore.autoConnect, automaticPauseRemaining > 0),
        graceful: Boolean = false,
    ) {
        carWithEntries.retire(connectionAttemptId)
        videoCapabilityGeneration.incrementAndGet()
        SettingsStore.videoCapabilities.clear()
        // 幂等：重复的断开事件（ACL 断开广播 + 引擎 ended + service 转交）只真正拆一次。
        // 但**状态必须回 Idle**——曾经这里直接 return，导致链路已断、界面还显示"已连接"。
        if (!engineCallbacks.endConnection { sessionLive.compareAndSet(true, false) }) {
            _snapshots.update { s ->
                if (s.link is LinkState.Connected) {
                    s.copy(link = LinkState.Idle, pipeline = s.pipeline.copy(active = false))
                } else {
                    s
                }
            }
            awaitingCarInvitation = false
            return
        }
        awaitingCarInvitation = false
        push(link = LinkState.Stopping)
        closedPipelineGeneration.accumulateAndGet(_snapshots.value.pipeline.generation, ::maxOf)
        finishDiagnostic("STOPPED", reason)
        val hadLink = sessionHadLink
        val receiver = retryReceiver()
        releaseSessionResources(keepListening, graceful)
        log("SESSION: 已断开（$reason）")
        _snapshots.update { s ->
            s.copy(
                link = LinkState.Idle,
                pipeline = s.pipeline.copy(active = false),
                video = VideoStats(),
                audio = AudioStats(),
                shizukuReady = ShizukuTools.isReady,
                logRecording = logRecording,
            )
        }
        scheduleAutoReconnect(reason, hadLink, receiver)
        if (SettingsStore.autoConnect) scheduleStandbyAfterCleanup()
    }

    private fun wifiReceiverKey(): String? = PairingEvidence.receiverKey(
        sessionReceiverMac ?: iap2?.activePeerAddress ?: wifiReceiverMac ?: aclCarMac ?: SettingsStore.targetCarMac)

    private fun retryReceiver(): String? = wifiReceiverKey()?.let { "${headUnitProfile.id}:$it" }

    /** Bounded recovery continues through failed retries, replenished only by stable video. */
    private fun scheduleAutoReconnect(reason: String, hadLink: Boolean, receiver: String?) {
        if (!automaticConnectionAllowed || userDisconnectRequested) return
        val ctx = appContext() ?: return
        val retry = reconnectBudget.failed(receiver, hadLink, SystemClock.elapsedRealtime())
        if (retry == null) {
            log("RECONNECT: 无可用恢复预算（未建立过链路或已耗尽）——保持待机，等待车机新连接")
            return
        }
        val owner = connectionAttemptId
        val retryDelay = retry.delayMs
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            log("RECONNECT: $reason——${retryDelay}ms 后重试（${retry.number}/${ReconnectBudget.MAX_RETRIES}）")
            delay(retryDelay)
            if (connectionAttemptId != owner || userDisconnectRequested || !automaticConnectionAllowed || hasCurrentSession) return@launch
            connectAutomatically(ctx, "retry")
        }
    }

    /** 直接停止服务，避免 ACTION_STOP 再回灌 ACL 断开事件。 */
    private fun stopForegroundService() {
        val context = appContext() ?: return
        runCatching { context.stopService(Intent(context, HyperCarService::class.java)) }
            .onFailure { log("SERVICE: 停止前台保活失败：${it.message}") }
    }

    /** 释放保活服务、iAP2、Rust 和 WiFi 绑定；拆除与失败路径共用。 */
    private fun releaseSessionResources(keepListening: Boolean = false, graceful: Boolean = false) {
        iap2WifiHistory = Iap2WifiCredentialHistory()
        engineCallbacks.retire()
        pendingCarHotspot.set(null)
        SettingsStore.receiverMaxFps = null
        io.github.hypercar.carui.CarDisplayLayout.viewAreaChoices.value = null
        io.github.hypercar.carui.CarReceiverUi.reset()
        val advertised = announcedHotspot.getAndSet(null)
        val requestPeer = advertised?.second?.takeIf {
            it.applies(advertised.first, connectionAttemptId, iap2?.activePeerAddress, SettingsStore.targetCarMac) &&
                wifiJoiner?.requiresDifferentHotspot(it.ssid) == false
        }
        val requestReceiver = PairingEvidence.receiverKey(requestPeer?.peer) ?: wifiReceiverKey()
        hudSessionEnabled = false
        io.github.hypercar.carui.CarSpeechValidation.cancel()
        io.github.hypercar.navigation.NavigationSources.clear()
        sessionSockets?.invalidate() // 阻止旧引擎继续创建/移交 socket，先于 nativeStop。
        sessionSockets = null
        CarProfileGuard.stop() // 清除旧会话的延迟检查；重新待机时再启动。
        if (!keepListening) stopForegroundService()
        MusicBridge.onCarDisconnected()
        streaming = false
        // P1：先停自绘界面（释放 D1 显示/编码器），再停引擎。
        // 清理涉及 shell binder/编码器释放，可能卡住；异步执行，断开流程不等它。
        sessionUiJob?.cancel()
        sessionUiJob = null
        CarUiCaster.stopAsync()
        DexCaster.stopAsync()
        // 在解绑网络、结束蓝牙旧会话之前完成有界业务退出；断网/EOF 路径只强制清理。
        runCatching {
            if (graceful) log("SESSION-CLOSE: graceful completed=${RustCoreBridge.nativeStopGracefully()}")
            else RustCoreBridge.nativeStop()
        }
        runCatching { RustCoreBridge.nativeStopCarWith(connectionAttemptId) }
        proactiveActivation.set(null)
        if (keepListening) {
            standbyEndpoint.set(null)
            runCatching { iap2?.endCurrentSession() }
        } else if (keepIdleIdentity) {
            iap2?.pauseSessions()
            standbyEndpoint.set(null)
        } else {
            runCatching { iap2?.stop() }
            iap2 = null
            standbyEndpoint.set(null)
        }
        synchronized(ctrlNetworkLock) {
            runCatching { ctrlAdvertiser?.stop() }
            ctrlAdvertiser = null
        }
        val cm = appContext()?.getSystemService(ConnectivityManager::class.java)
        sessionNetworkMonitor?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        sessionNetworkMonitor = null
        val request = wifiJoiner
        val provisional = boundNetwork?.let(hotspotHandoff::provisionalRequest)
        val retainNetwork = request?.release(keepConnected = request !== provisional && cachedHotspotMatchesTarget())
        if (request != null && retainNetwork != null) {
            request.unbindProcess()
            wifiRecoveryLease.retain(request, requestReceiver.orEmpty())
            joinedNetwork = retainNetwork
            log("WIFI-LEASE: CarPlay 暂停/结束，保留已连接车机热点；仅解绑进程，不撤销热点请求")
        } else {
            releaseRetainedWifi()
            request?.let(hotspotHandoff::clear)
            wifiJoiner = null
            joinedSsid = null
            joinedNetwork = null
        }
        val previousNetwork = boundNetwork
        if (previousNetwork != null && cm?.boundNetworkForProcess == previousNetwork)
            runCatching { cm.bindProcessToNetwork(null) }
        fallbackToRemember = null
        iap2?.invalidatePhoneIdentity()
        boundNetwork = null
        wifiReceiverMac = null
        sessionReceiverMac = null
        sessionIdentity = null
        sessionWifiSsid = null
        wifiStationSnapshot = null
        sessionProfile = null
        appContext()?.let { io.github.hypercar.carui.MusicNotificationListener.sync(it) }
    }

    // ---------------------------------------------------------------- WiFi

    private suspend fun wifiStationMac(context: Context, network: Network): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = caps.transportInfo as? android.net.wifi.WifiInfo
        io.github.hypercar.net.WifiStationIdentity.normalize(info?.macAddress)?.let { return it }
        if (!ShizukuTools.isReady) return null
        val addresses = cm.getLinkProperties(network)?.linkAddresses?.mapNotNull { it.address.hostAddress }?.toSet().orEmpty()
        if (addresses.isEmpty()) return null
        // exec drains stdout before its process wait, so bound the child itself as well.
        val result = ShizukuTools.exec("timeout 2 cmd wifi status", 3_000)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
            cm.getLinkProperties(network)?.linkAddresses?.mapNotNull { it.address.hostAddress }?.toSet() != addresses) return null
        return io.github.hypercar.net.WifiStationIdentity.fromStatus(result.out, addresses)
    }

    /** Match this paired Bluetooth peer's fresh AP information before asking Wi-Fi to act. */
    private suspend fun matchingPairedCarNetwork(context: Context, endpoint: CarPlayEndpoint): Network? {
        val coordinator = iap2 ?: return null
        val peer = coordinator.activePeerAddress ?: return null
        val paired = coordinator.isSessionActive && SettingsStore.isCarPaired(peer) &&
            (SettingsStore.targetCarMac.isNullOrBlank() || peer.equals(SettingsStore.targetCarMac, true))
        if (!paired || endpoint.proactive || endpoint.ssid.isNullOrBlank()) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = wifiNetworks(context)
        val fallbackSsid = if (networks.size == 1) currentWifiSsid(context) else null
        return networks.filter { network ->
            val info = cm.getNetworkCapabilities(network)?.transportInfo as? android.net.wifi.WifiInfo
            val actual = info?.ssid?.trim('"')?.takeUnless { it.contains("unknown", true) } ?: fallbackSsid
            io.github.hypercar.net.CarWifiPolicy.samePairedHotspot(paired, !endpoint.proactive, endpoint.ssid, actual)
        }.singleOrNull()
    }

    private fun currentBluetoothHotspot(attempt: Long): BluetoothHotspot? {
        val (owner, hotspot) = announcedHotspot.get() ?: return null
        return hotspot.takeIf { sessionLive.get() && connectionAttemptId == attempt &&
            it.applies(owner, attempt, iap2?.activePeerAddress, SettingsStore.targetCarMac) }
    }

    private fun hasFreshWifiCredentials(endpoint: CarPlayEndpoint?, attempt: Long): Boolean =
        endpoint != null && endpoint.canRequestHotspot(currentBluetoothHotspot(attempt)) &&
            WifiJoiner.canRequestHotspot(endpoint.ssid, endpoint.passphrase)

    private fun rememberHotspot(attempt: Long, endpoint: CarPlayEndpoint) {
        announcedHotspot.updateAndGet { previous ->
            when {
                !sessionLive.get() || connectionAttemptId != attempt -> previous
                endpoint.bluetoothHotspot != null -> attempt to endpoint.bluetoothHotspot
                endpoint.ssid != null -> null
                else -> previous
            }
        }
    }

    private suspend fun hotspotAssociation(context: Context, hotspot: BluetoothHotspot): BluetoothHotspot.Association<Network> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val candidates = wifiNetworks(context).mapNotNull { network ->
            val caps = cm?.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val info = caps.transportInfo as? android.net.wifi.WifiInfo
            val name = info?.ssid?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == android.net.wifi.WifiManager.UNKNOWN_SSID }
            val owned = wifiJoiner?.let { it.ownsLiveRequest(network) && !it.requiresDifferentHotspot(hotspot.ssid) } == true
            network to (name ?: hotspot.ssid.takeIf { owned })
        }.toMap()
        if (hotspot.ssid in candidates.values) return hotspot.association(candidates, null)
        val station = runCatching { context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo }.getOrNull()
        val systemSsid = if (station?.supplicantState == android.net.wifi.SupplicantState.COMPLETED) {
            station.ssid?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == android.net.wifi.WifiManager.UNKNOWN_SSID }
                ?: currentWifiSsid(context)
        } else null
        return hotspot.association(candidates, systemSsid)
    }

    private suspend fun readyBluetoothHotspotNetwork(context: Context, endpoint: CarPlayEndpoint, attempt: Long): Network? {
        if (endpoint.proactive) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        var waiting = false
        while (true) {
            val hotspot = currentBluetoothHotspot(attempt)?.takeIf { it.ssid == endpoint.ssid } ?: return null
            val match = hotspotAssociation(context, hotspot)
            if (!match.associated) return null
            val network = match.network
            if (network != null && cm.getLinkProperties(network)?.linkAddresses?.any {
                    !it.address.isLoopbackAddress && !it.address.isAnyLocalAddress && !it.address.isMulticastAddress &&
                        (it.address is java.net.Inet6Address || !it.address.isLinkLocalAddress)
                } == true) {
                log("WIFI-REUSE: attempt=$attempt 本轮蓝牙给出的 SSID 与已连接热点一致，使用网络 #$network")
                return network
            }
            if (!waiting) log("WIFI-REUSE: attempt=$attempt 已关联本轮蓝牙给出的热点，等待唯一 Wi-Fi 句柄和地址")
            waiting = true
            delay(250)
        }
    }

    /** Recheck the live OS link; a remembered handle or a shared SSID alone is insufficient. */
    private suspend fun reusableCarNetwork(context: Context, endpoint: CarPlayEndpoint, explain: Boolean = false,
        matchedAssociation: Network? = null): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val pairedAssociation = matchedAssociation ?: matchingPairedCarNetwork(context, endpoint)
        if (pairedAssociation != null) {
            val hasAddress = cm.getLinkProperties(pairedAssociation)?.linkAddresses?.any {
                !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress && !it.address.isMulticastAddress &&
                    (it.address is java.net.Inet6Address || !it.address.isLinkLocalAddress)
            } == true
            if (hasAddress) {
                if (explain) log("WIFI-VERIFY: 本轮已配对蓝牙车机热点信息与当前连接一致，直接复用")
                return pairedAssociation
            }
            return null
        }
        val networks = wifiNetworks(context)
        // Our live request already selected this exact SSID/password. Do not require redacted
        // WifiInfo SSID, or confuse the receiver's deviceIdentifier with its AP BSSID.
        val owned = networks.filter { network ->
            val ownedRequest = wifiJoiner?.ownsHotspot(network, endpoint.ssid, endpoint.passphrase) == true
            val hasAddress = cm.getLinkProperties(network)?.linkAddresses?.any {
                !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress && !it.address.isMulticastAddress &&
                    (it.address is java.net.Inet6Address || !it.address.isLinkLocalAddress)
            } == true
            ownedRequest && io.github.hypercar.net.CarWifiPolicy.canReuse(cm.getNetworkCapabilities(network), verifiedRequest = true) &&
                io.github.hypercar.net.CarNetworkReuse.accepts(endpoint.ssid, null, endpoint.fromInvite,
                    io.github.hypercar.net.CarNetworkReuse.Evidence(null, null, hasAddress, false, false, ownedRequest))
        }
        if (owned.size == 1) {
            if (explain) log("WIFI-VERIFY: live_request=true credentials_match=true；复用本轮已取得的局域网")
            return owned.single()
        }
        val fallbackSsid = if (networks.size == 1) currentWifiSsid(context) else null
        val fallbackInfo = if (networks.size == 1) runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo
        }.getOrNull() else null
        var fallbackBssid = fallbackInfo?.bssid
        if (networks.size == 1 &&
            io.github.hypercar.net.CarNetworkReuse.mac(fallbackBssid) == null && ShizukuTools.isReady) {
            fallbackBssid = io.github.hypercar.net.CarNetworkReuse.readBssid {
                ShizukuTools.exec("cmd wifi status", 3_000).out
            }
            if (fallbackBssid == null && explain) log("WIFI-REUSE: 未取得可靠 BSSID，继续正常入网，不中断 iAP2")
        }
        val verifiedRoute = if (!endpoint.requiresBoundAirplayRoute || endpoint.verifiedAirplayNetworkHandle != null)
            verifyBoundAirplayEndpoint(context, endpoint, boundNetwork) else null
        val peers = (verifiedRoute?.ipAddresses.orEmpty() + listOfNotNull(verifiedRoute?.gateway)).mapNotNull {
            runCatching { android.net.InetAddresses.parseNumericAddress(it.substringBefore('%')) }.getOrNull()
        }
        val actualPeerIdentity = iap2?.activePeerAddress?.let { io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(it) }
            .orEmpty().lowercase()
        val desktopPeer = actualPeerIdentity.contains("winplay") || actualPeerIdentity.contains("diplay")
        val eligible = networks.filter { network ->
            val caps = cm.getNetworkCapabilities(network)
            val lp = cm.getLinkProperties(network)
            val info = caps?.transportInfo as? android.net.wifi.WifiInfo
            val ssid = info?.ssid?.trim('"')?.takeUnless { it.contains("unknown", true) } ?: fallbackSsid
            val hasAddress = lp?.linkAddresses?.any {
                !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress && !it.address.isMulticastAddress &&
                    (it.address is java.net.Inet6Address || !it.address.isLinkLocalAddress)
            } == true
            val route = lp?.routes?.any { r -> r.destination.prefixLength > 0 && peers.any { r.destination.contains(it) } } == true
            val pinnedSame = network == joinedNetwork && !joinedSsid.isNullOrBlank() && joinedSsid == endpoint.ssid
            val safe = io.github.hypercar.net.CarWifiPolicy.canReuse(caps, verifiedRequest = pinnedSame)
            val identity = io.github.hypercar.net.CarWifiPolicy.canReuseDesktop(desktopPeer, endpoint.ssid, ssid, hasAddress,
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) ||
                io.github.hypercar.net.CarNetworkReuse.accepts(endpoint.ssid, null, endpoint.fromInvite,
                    io.github.hypercar.net.CarNetworkReuse.Evidence(ssid, io.github.hypercar.net.CarNetworkReuse.mac(info?.bssid) ?: fallbackBssid,
                        hasAddress, route, network == boundNetwork || network == joinedNetwork))
            if (explain) {
                val actual = io.github.hypercar.net.CarNetworkReuse.mac(info?.bssid) ?: io.github.hypercar.net.CarNetworkReuse.mac(fallbackBssid)
                log("WIFI-VERIFY: safe=$safe identity=$identity ssid_match=${!endpoint.ssid.isNullOrBlank() && endpoint.ssid == ssid} " +
                    "receiver_id_is_ap=false bssid_known=${actual != null} " +
                    "address=$hasAddress route_to_peer=$route ipv6=${lp?.linkAddresses?.any { it.address is java.net.Inet6Address } == true} " +
                    "internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true} " +
                    "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
            }
            safe && identity
        }
        if (explain) log("WIFI-VERIFY: wifi_candidates=${networks.size} verified_candidates=${eligible.size}; association alone is not a usable network")
        return eligible.firstOrNull { it == boundNetwork } ?: eligible.singleOrNull()
    }

    private suspend fun joinAndBind(context: Context, endpoint: CarPlayEndpoint, invitation: CtrlInvitation<Network>? = null,
        endpoints: CarHotspotReconnect.EndpointInbox? = null,
        replaceAssociation: Boolean = false,
        lockedRecoveryAttempt: Long? = null,
        latestDeviceIdentifier: () -> String? = { endpoint.deviceIdentifier }): Network? {
        val attempt = connectionAttemptId
        carWifiRequests.lock()
        val network = try {
            joinAndBindLocked(context, endpoint, invitation, endpoints, replaceAssociation, latestDeviceIdentifier, attempt, lockedRecoveryAttempt)
        }
        finally { carWifiRequests.unlock() }
        // Resolve before opening the control TCP socket; optional shell metadata must not
        // delay the first SETUP on an already accepted receiver connection.
        if (network != null && headUnitProfile.protocolProfileId == "bmw_id85" &&
            wifiStationSnapshot?.let { it.first == attempt && it.second == network } != true) {
            val mac = try { wifiStationMac(context, network) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                log("SESSION-IDENTITY: Wi-Fi 地址未取得，沿用设备身份：${error.javaClass.simpleName}")
                null
            }
            autoConnectChanges.lock()
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!sessionLive.get() || connectionAttemptId != attempt || boundNetwork != network)
                    throw CancellationException("旧网络身份结果已失效")
                wifiStationSnapshot = mac?.let { Triple(attempt, network, it) }
            } finally { autoConnectChanges.unlock() }
        }
        return network
    }

    /** A ctrl invitation already has a network. Revalidate that binding without starting a join. */
    private fun reuseInvitationNetwork(cm: ConnectivityManager, endpoint: CarPlayEndpoint, selected: CtrlInvitation<Network>,
        endpoints: CarHotspotReconnect.EndpointInbox): Network? {
        val invite = ctrlAdvertiser?.let { usableCtrlInvitation(it, selected) } ?: return null
        val network = boundNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val link = cm.getLinkProperties(network) ?: return null
        val peer = runCatching { android.net.InetAddresses.parseNumericAddress(invite.host.substringBefore('%')) }.getOrNull() ?: return null
        val boundSsid = joinedSsid.takeIf { joinedNetwork == network }
        val info = caps.transportInfo as? android.net.wifi.WifiInfo
        val ssid = info?.ssid?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == android.net.wifi.WifiManager.UNKNOWN_SSID }
        val matches = io.github.hypercar.net.CarWifiPolicy.canReuseInvitedCar(
            knownCar = boundSsid != null || (wifiReceiverMac != null && knownWifiReceiver(ssid).equals(wifiReceiverMac, true)),
            sameNetwork = invite.isOn(network), peerMatches = invite.host in endpoint.ipAddresses,
            announcedSsid = endpoint.ssid, currentSsid = ssid, boundSsid = boundSsid,
            hasAddress = link.linkAddresses.any { !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress && !it.address.isMulticastAddress },
            routeToPeer = link.routes.any { it.destination.prefixLength > 0 && it.destination.contains(peer) })
        if (!matches || (cm.boundNetworkForProcess != network && !cm.bindProcessToNetwork(network))) return null
        endpoints.whenCredentialsDrained {
            hotspotHandoff.confirm(network)
            unverifiedInvitationNetwork = null
            network
        } ?: return null
        log("WIFI-REUSE: 已核实邀请来源，沿用车机网络 #$network")
        return network
    }

    private suspend fun joinAndBindLocked(context: Context, endpoint: CarPlayEndpoint,
        invitation: CtrlInvitation<Network>?, endpoints: CarHotspotReconnect.EndpointInbox?,
        replaceAssociation: Boolean, latestDeviceIdentifier: () -> String?, attempt: Long, lockedRecoveryAttempt: Long?): Network? {
        if (!endpoint.sourceIsCurrent) return null
        fun ensureCurrent() {
            if (!sessionLive.get() || connectionAttemptId != attempt || !cachedHotspotMatchesTarget())
                throw CancellationException("旧车机入网结果已失效")
        }
        fun requestHotspot(): BluetoothHotspot? {
            if (lockedRecoveryAttempt == null) return currentBluetoothHotspot(attempt)
            // The SSID was verified when this report entered the inbox. Later reports
            // cannot replace it for this request, but the connection owner must still match.
            return endpoint.bluetoothHotspot?.takeIf {
                sessionLive.get() && connectionAttemptId == attempt &&
                    it.applies(lockedRecoveryAttempt, attempt, iap2?.activePeerAddress, SettingsStore.targetCarMac)
            }
        }
        fun credentialsSuperseded(): Boolean = if (!endpoint.sourceIsCurrent) true
            else if (lockedRecoveryAttempt != null) !endpoint.canRequestHotspot(requestHotspot())
            else endpoints?.credentialsChanged { it.ssid == endpoint.ssid && it.passphrase == endpoint.passphrase } == true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        if (endpoint.fromInvite) {
            if (invitation == null || endpoints == null) return null
            autoConnectChanges.lock()
            try {
                ensureCurrent()
                return reuseInvitationNetwork(cm, endpoint, invitation, endpoints)
            } finally { autoConnectChanges.unlock() }
        }
        val matchingAssociation = if (lockedRecoveryAttempt == null)
            readyBluetoothHotspotNetwork(context, endpoint, attempt) ?: matchingPairedCarNetwork(context, endpoint) else null
        var reuse = if (lockedRecoveryAttempt == null)
            reusableCarNetwork(context, endpoint, matchedAssociation = matchingAssociation) else null
        var protectedSameAssociation = matchingAssociation != null
        val replacingCredentials: Boolean
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (credentialsSuperseded()) return null
            val freshCredentials = endpoint.canRequestHotspot(requestHotspot()) &&
                WifiJoiner.canRequestHotspot(endpoint.ssid, endpoint.passphrase)
            val liveRequest = wifiJoiner
            replacingCredentials = replaceAssociation ||
                (freshCredentials && liveRequest?.requiresCredentialUpdate(endpoint.ssid, endpoint.passphrase) == true)
            if (replacingCredentials && (!freshCredentials ||
                    SpecifierPermissions.degradeBlockReason(context) != null)) return null
            liveRequest?.let { request ->
                val association = request.currentNetwork
                if (association != null && request.ownsLiveRequest(association)) {
                    val confirmedSame = association == matchingAssociation
                    if (!replacingCredentials && (confirmedSame || !request.requiresDifferentHotspot(endpoint.ssid))) {
                        protectedSameAssociation = true
                        // All live requests are protected, not only those parked after a stop.
                        // Missing credentials/capabilities are not evidence of a different AP.
                        if (!request.isReady(association)) return null
                        val peer = PairingEvidence.receiverKey(currentBluetoothHotspot(attempt)?.peer) ?: wifiReceiverKey()
                        if (!adoptRetainedWifi(association, peer, confirmedSame)) return null
                        reuse = association
                    } else {
                        // Do not dismantle a working association when a new join cannot start.
                        if (!endpoint.canRequestHotspot(requestHotspot()) ||
                            !WifiJoiner.canRequestHotspot(endpoint.ssid, endpoint.passphrase) ||
                            SpecifierPermissions.degradeBlockReason(context) != null) return null
                        if (replacingCredentials) {
                            sessionNetworkMonitor?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                            sessionNetworkMonitor = null
                        }
                        request.release(keepConnected = false)
                        releaseRetainedWifi()
                        if (wifiJoiner === request) wifiJoiner = null
                        hotspotHandoff.clear(request)
                    }
                }
            }
            if (replacingCredentials) {
                reuse = null
                protectedSameAssociation = false
                log("WIFI: 现场热点凭据变化，停用旧请求并重新入网")
            }
            if (reuse != null && cm.getNetworkCapabilities(reuse)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(reuse)?.linkAddresses?.isNotEmpty() == true) {
                // Keep a specifier request owning this network alive; retire callbacks for other links.
                if (wifiJoiner?.ownsBinding(reuse) == false && wifiJoiner?.ownsLiveRequest(reuse) == false) {
                    if (wifiJoiner?.release() != null) return null
                    wifiJoiner?.let(hotspotHandoff::clear)
                    wifiJoiner = null
                }
                if (cm.boundNetworkForProcess != reuse && !cm.bindProcessToNetwork(reuse)) return null
                boundNetwork = reuse
                unverifiedInvitationNetwork = null
                joinedNetwork = reuse
                joinedSsid = endpoint.ssid?.takeIf { it.isNotBlank() } ?: joinedSsid
                watchSessionNetwork(context, reuse, endpoint.hotspotIdentity() ?: wifiJoiner?.hotspotIdentity)
                log("WIFI-REUSE: attempt=$attempt 已核实网络 #$reuse，复用现有车机 Wi-Fi，不重新入网")
                return reuse
            }
            if (protectedSameAssociation) {
                log("WIFI: 同热点网络属性暂未就绪，保留原请求，等待下一次核验")
                return null
            }
            if (!endpoint.canRequestHotspot(requestHotspot())) return null
            if (wifiJoiner?.release() != null) return null
            // A local-only migration deliberately disconnects the old system AP. Its old loss
            // callback must not cancel the new request as if the user had left the car.
            sessionNetworkMonitor?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            sessionNetworkMonitor = null
            val previousNetwork = boundNetwork
            wifiJoiner?.let(hotspotHandoff::clear)
            wifiJoiner = null
            if (previousNetwork != null && cm.boundNetworkForProcess == previousNetwork)
                runCatching { cm.bindProcessToNetwork(null) }
            boundNetwork = null
            joinedNetwork = null
            joinedSsid = null
        } finally { autoConnectChanges.unlock() }
        val credentials = endpoint.hotspotIdentity()
        val joiner = createWifiJoiner(context, credentials) {
            wifiJoiner === it && endpoint.canRequestHotspot(requestHotspot()) && !credentialsSuperseded()
        }
        autoConnectChanges.lock()
        try { ensureCurrent(); wifiJoiner = joiner } finally { autoConnectChanges.unlock() }
        val ssid = endpoint.ssid
        val psk = endpoint.passphrase
        var matchingAfterScan: Network? = null
        val network = if (!ssid.isNullOrBlank() && psk != null) {
            push(link = LinkState.Negotiating("连接车机局域网，首次请确认系统提示"))
            log("WIFI: 加入热点 $ssid，方式=HyperCar 专用局域网（最多60s）；其它 App 保留系统互联网")
            when (val result = joiner.joinCarHotspotSilently(ssid, psk,
                alreadyAssociated = {
                    ensureCurrent()
                    if (replacingCredentials) null
                    else readyBluetoothHotspotNetwork(context, endpoint, attempt).also { matchingAfterScan = it }
                },
                verifiedFallback = { ensureCurrent(); if (replacingCredentials) null else reusableCarNetwork(context, endpoint) },
                credentialsCurrent = { !credentialsSuperseded() }) {
                ensureCurrent()
                latestDeviceIdentifier()
            }) {
                is WifiJoiner.JoinResult.Joined -> { markDegradedFirstConnectIfNeeded(); result.network }
                else -> { log("WIFI: 入网未完成（${joiner.lastFailureReason ?: "不支持或连接失败"}），仅尝试已核实的当前网络"); null }
            }
        } else null
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        ensureCurrent()
        val effective = network ?: if (replacingCredentials || credentialsSuperseded()) null
            else reusableCarNetwork(context, endpoint, explain = true)
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (wifiJoiner !== joiner) throw CancellationException("网络租约已更换")
            if (credentialsSuperseded()) {
                wifiJoiner = null
                joiner.release(keepConnected = false)
                return null
            }
            val capabilities = effective?.let(cm::getNetworkCapabilities)
            if (effective == null || capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                !joiner.bindProcess(effective, verifiedSystemHotspot = effective == matchingAfterScan)) {
                val association = joiner.release()
                if (association != null) {
                    joinedNetwork = association
                    joinedSsid = ssid
                    log("WIFI: 本轮业务入网未完成，保留系统已建立的车机热点连接")
                } else wifiJoiner = null
                log("WIFI: 没有匹配本车且可绑定的有效网络，停止本轮入网")
                return null
            }
            boundNetwork = effective
            joinedSsid = ssid
            joinedNetwork = effective
            watchSessionNetwork(context, effective, credentials)
            val hotspot = requestHotspot()
            if (ssid != null && psk != null && hotspot != null && CarHotspotReconnect.canRemember(
                    psk, freshHotspot = endpoint.canRequestHotspot(hotspot),
                    ownedRequest = joiner.ownsHotspot(effective, ssid, psk),
                    localOnly = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    desktop = isDesktopHotspotPeer(headUnitProfile.id, hotspot.peer),
                )) {
                pendingCarHotspot.set(PendingCarHotspot(attempt, hotspot.peer, effective, CarHotspotReconnect.Credentials(ssid, psk)))
            }
            log("WIFI +${stageMs()}ms ✓ 已绑定车机网络：${describeNetwork(context, effective)}")
            return effective
        } finally { autoConnectChanges.unlock() }
    }

    private fun CarPlayEndpoint.hotspotIdentity(): CarWifiRequestIdentity? {
        val name = ssid?.takeIf { it.isNotBlank() } ?: return null
        val password = passphrase ?: return null
        return CarWifiRequestIdentity(name, password)
    }

    private fun createWifiJoiner(context: Context, credentials: CarWifiRequestIdentity?,
        maySubmitRequest: (WifiJoiner) -> Boolean): WifiJoiner {
        lateinit var joiner: WifiJoiner
        fun mayStopSession() = canStopAfterWifiLoss(credentials) { wifiJoiner === joiner }
        joiner = WifiJoiner(context, diagnostic = ::log, maySubmitRequest = { maySubmitRequest(joiner) }) lost@{
            if (recoverActivationWifiLoss(credentials) { wifiJoiner === joiner }) return@lost
            scope.launch {
                autoConnectChanges.lock()
                try {
                    if (wifiJoiner !== joiner) return@launch
                    val provisional = hotspotHandoff.provisionalNetwork
                    if (provisional != null && releaseLostProvisionalHotspot(context, provisional, joiner)) return@launch
                    if (!mayStopSession()) {
                        log("WIFI: 旧入网请求丢失，保留本轮现场凭据更新")
                        return@launch
                    }
                    if (joiner.release() != null) return@launch
                    if (wifiRecoveryLease.isRetained(joiner)) {
                        releaseRetainedWifi()
                        log("WIFI-LEASE: 待机中的局域网请求已丢失，释放旧租约")
                    } else if (sessionLive.get()) {
                        log("WIFI: 本轮局域网请求已丢失；回调本身不判定热点关闭或断链发起方")
                        disconnect(userInitiated = false, expectedAttempt = connectionAttemptId,
                            reason = "车机局域网请求丢失",
                            stillCurrent = { commitWifiLoss(credentials) { wifiJoiner === joiner } })
                    }
                } finally { autoConnectChanges.unlock() }
            }
        }
        return joiner
    }

    private suspend fun restoreCarHotspot(
        context: Context,
        receiver: String,
        credentials: CarHotspotReconnect.Credentials,
        advertiser: CarPlayCtrlAdvertiser,
        restoreBlockReason: () -> String?,
        reportSkip: (String) -> Unit,
        submitRequest: (() -> Unit) -> Boolean,
    ) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) { reportSkip("connectivity_manager_unavailable"); return }
        val identity = CarWifiRequestIdentity(credentials.ssid, credentials.passphrase)
        val joiner = createWifiJoiner(context, identity) { hotspotHandoff.isJoining(it) && restoreBlockReason() == null }
        val (history, cacheBinding) = autoConnectChanges.withLock {
            synchronized(ctrlNetworkLock) {
                synchronized(advertiser) {
                    val history = iap2WifiHistory.forReceiver(receiver)
                    val binding = synchronized(iap2EndpointChanges) {
                        val block = restoreBlockReason()
                        if (block != null) { reportSkip(block); return }
                        if (!hotspotHandoff.begin(joiner, receiver)) { reportSkip("cache_request_in_progress"); return }
                        history.beginCacheBinding(credentials.ssid, credentials.passphrase)
                    }
                    try {
                        if (boundNetwork != null) {
                            clearSessionWifiBinding(context)
                            log("CAR-WIFI-RESUME: 结束普通 Wi-Fi 上的临时广播，尝试已配对热点")
                        }
                    } catch (failure: Throwable) {
                        history.finishCacheBinding(binding, bound = false)
                        hotspotHandoff.finishJoining(joiner)
                        throw failure
                    }
                    history to binding
                }
            }
        }
        try {
            log("CAR-WIFI-RESUME: 尝试重新接入已配对车机的专用 Wi-Fi")
            val result = withTimeoutOrNull(12_000) {
                joiner.joinCarHotspotSilently(credentials.ssid, credentials.passphrase,
                    submitRequest = { request -> restoreBlockReason() == null && submitRequest(request) })
            }
            val network = (result as? WifiJoiner.JoinResult.Joined)?.network
            if (network == null) {
                reportSkip(restoreBlockReason() ?: if (result == null) "join_timeout" else "join_failed")
                return
            }
            autoConnectChanges.lock()
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val block = restoreBlockReason() ?: when {
                    !joiner.ownsHotspot(network, credentials.ssid, credentials.passphrase) -> "request_ownership_lost"
                    cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != false ->
                        "not_local_only"
                    !joiner.bindProcess(network) -> "bind_failed"
                    else -> null
                }
                if (block != null) { reportSkip(block); return }
                history.finishCacheBinding(cacheBinding, bound = true)
                hotspotHandoff.bind(joiner, network)
                wifiJoiner = joiner
                wifiReceiverMac = receiver
                CarProfileGuard.noteCar(receiver)
                sessionWifiSsid = credentials.ssid
                boundNetwork = network
                joinedSsid = credentials.ssid
                joinedNetwork = network
                watchSessionNetwork(context, network, identity)
                advertiser.ensureForNetwork(credentials.ssid, network = network)
                log("CAR-WIFI-RESUME: 已接入专用 Wi-Fi 并开始等待车机邀请")
            } finally { autoConnectChanges.unlock() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("CAR-WIFI-RESUME: 入网异常（${error.javaClass.simpleName}），继续等待车机")
        } finally {
            history.finishCacheBinding(cacheBinding, bound = false)
            if (hotspotHandoff.isJoining(joiner)) {
                try {
                    joiner.release(keepConnected = false)
                } finally {
                    hotspotHandoff.finishJoining(joiner)
                }
            }
        }
    }

    /** Live network diagnostics; no saved credentials. */
    private fun describeNetwork(context: Context, network: Network): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.getLinkProperties(network)
        val caps = cm?.getNetworkCapabilities(network)
        val v4 = lp?.linkAddresses?.filter { it.address is java.net.Inet4Address }
            ?.joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" }
        val v6 = lp?.linkAddresses?.count { it.address is java.net.Inet6Address } ?: 0
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(",")
        "if=${lp?.interfaceName ?: "?"} ipv4=[${v4 ?: "-"}] ipv6=${v6} gw=${gw ?: "-"} dns=[${dns ?: "-"}]" +
            " ssid=${(caps?.transportInfo as? android.net.wifi.WifiInfo)?.ssid ?: "?"}" +
            " validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false}" +
            " internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false}"
    }.getOrElse { "网络属性读取失败（${it.message}）" }

    /** 兜底：拿当前默认网络（连上车机热点但静默路径失败时，Android 可能仍选蜂窝）。 */

    /**
     * 「网关兜底」：iAP2 没给出可连的 AirPlay 地址时，拿当前 Wi-Fi 的默认网关 + 7000 端口当端点。
     *
     * 两种场景都要覆盖：
     *  - **已拿到 0x5703、也已入网**，但车机没下发 0x4301（真车等 ctrl 邀请超时/邀请失败）——
     *    车机的 AirPlay 接收端通常就在热点网内以 7000 端口提供 RTSP 控制面；
     *  - **一个凭据都没拿到**，但用户手动连了车机热点。
     * 失败不比现状更差：照旧报错退出（只多花几次 2.5s 超时）。端口 7000 是 AirPlay 惯例值。
     */
    private fun gatewayFallbackEndpoint(
        context: Context,
        seen: CarPlayEndpoint?,
    ): CarPlayEndpoint? {
        if (seen?.sourceIsCurrent == false || headUnitProfile.requireAirplayActivation || hotspotHandoff.discoveryToken() == null) return null
        val gateway = wifiGateway(context) ?: return null
        log(
            "SESSION: 车机未给可用 AirPlay 地址" +
                (if (seen?.ssid.isNullOrEmpty()) "" else "（已拿到热点凭据 ssid=${seen?.ssid}）") +
                "——尝试网关兜底 $gateway:7000",
        )
        return CarPlayEndpoint(
            ssid = seen?.ssid,
            passphrase = seen?.passphrase,
            airPlayPort = 7000,
            deviceIdentifier = seen?.deviceIdentifier,
            publicKeyHex = seen?.publicKeyHex,
            sourceVersion = seen?.sourceVersion,
            ipAddresses = listOf(gateway),
            gateway = gateway,
            sourceGeneration = seen?.sourceGeneration,
        )
    }

    /** 当前所有 Wi-Fi 网络里第一个「有默认路由」的网关地址（IPv4 优先）。 */
    private fun wifiGateway(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            cm.allNetworks
                .asSequence()
                .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .mapNotNull { network -> cm.getLinkProperties(network)?.routes?.firstOrNull { it.isDefaultRoute }?.gateway }
                .mapNotNull { it.hostAddress }
                .firstOrNull { it.contains('.') && !it.contains(':') }
        }.getOrNull()
    }

    /**
     * 当前连接的 Wi-Fi SSID。
     *
     * 先读 `WifiManager.connectionInfo.ssid`（零成本，5s 一次的轮询不能每次都起 shell 进程）；
     * 缺定位权限时它会返回 `<unknown ssid>`（HyperOS 常见），此时退回 Shizuku 的
     * `cmd wifi status`（与 WifiJoiner 同源的解析，一直可读）。
     */
    private fun wifiSsidFromApi(context: Context): String? =
            runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                    ?.connectionInfo
                    ?.ssid
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
            }.getOrNull()

    private suspend fun currentWifiSsid(context: Context): String? {
        wifiSsidFromApi(context)?.let { return it }
        if (!ShizukuTools.isReady) return null
        val out = runCatching { ShizukuTools.exec("cmd wifi status", 3_000) }.getOrNull()?.out
        val match = out?.let { Regex("connected\\s+to\\s+\"([^\"]*)\"").find(it) }
        return match?.groupValues?.get(1)?.ifBlank { null }
    }

    /**
     * **静默网关试探**：把当前 Wi-Fi 的默认网关当成车机热点，试一次 `网关:7000`（AirPlay 控制面）。
     *
     * 为什么需要（用户 2026-10-24 要求；cardeck PR #19 在凯迪拉克 CT5 上的同款兜底）：
     * 新协议车机（GM/Cinemo、BMW iD9 同族）声明会发 0x4301，但可能晚发或等前置条件，
     * 车主手动把手机连上「全新CT5」后其实就能直连——cardeck 那边是弹窗确认，本仓按
     * 「直接静默试一次」处理：成功即当端点继续，失败就继续等 0x4301（不影响保活）。
     *
     * 只在「等待期间出现新 SSID」或「该 SSID 正是 iAP2 给的车机热点」时各试一次：
     * 手机上原本就有的 WiFi（家里/公司，可能有 Apple TV 占着 7000）不会被误当车机。
     *
     * @return 试探成功时的合成端点（`airPlayPort=7000`、ip=网关）；失败返回 null
     */
    /** Bounded discovery on the bound receiver network; invitations always take priority. */
    private suspend fun discoverAirplayOnLocalSubnet(context: Context, ssid: String): CarPlayEndpoint? {
        val network = boundNetwork ?: return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        val local = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address &&
            !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress } ?: return null
        val address = local.address.hostAddress ?: return null
        if (local.prefixLength !in 16..30) {
            log("DISCOVERY: prefix=/${local.prefixLength} 不在有界 IPv4 扫描范围内，继续等邀请")
            return null
        }
        val plan = AirplaySubnetPlan(address, local.prefixLength)
        val neighbors = runCatching {
            plan.arpPeers(java.io.File("/proc/net/arp").readLines(), lp.interfaceName)
        }.getOrElse { error ->
            log("DISCOVERY: ARP 邻居表不可用 ${error.javaClass.simpleName}，继续使用有界地址枚举")
            emptyList()
        }
        val common = plan.commonHosts(neighbors)
        val ports = headUnitProfile.fallbackAirplayPorts.filter { it in 1..65535 }.distinct().ifEmpty { listOf(7000) }
        val started = SystemClock.elapsedRealtime()
        log("DISCOVERY: network=#$network interface=${lp.interfaceName} self=$address/${local.prefixLength} " +
            "neighbors=${neighbors.size} common=${common.size} ports=$ports limit=$TOTAL_SWEEP_MAX")
        probeHostsForAirplay(network, common, ports)?.let { hit ->
            log("DISCOVERY: source=common target=${hit.first}:${hit.second} elapsed=${SystemClock.elapsedRealtime() - started}ms；仅端口可达，尚未授权")
            return endpointFor(ssid, hit.first, hit.second)
        }
        if (!sessionLive.get() || boundNetwork != network || ctrlAdvertiser?.lastInvitePeerIp != null ||
            lateAirplayEndpoint.get()?.sourceIsCurrent == true) return null
        val full = plan.remainingHosts(common.toSet(), TOTAL_SWEEP_MAX)
        log("DISCOVERY: source=prefix remaining=${full.size} ports=$ports；逐地址保留全部候选端口，正常邀请优先")
        probeHostsForAirplay(network, full, ports)?.let { hit ->
            log("DISCOVERY: source=prefix target=${hit.first}:${hit.second} elapsed=${SystemClock.elapsedRealtime() - started}ms；仅端口可达，尚未授权")
            return endpointFor(ssid, hit.first, hit.second)
        }
        log("DISCOVERY: 未得到可用候选或已被邀请/换网中止，elapsed=${SystemClock.elapsedRealtime() - started}ms")
        return null
    }

    /** 扫描命中后合成端点（后续 pair-verify 会确认它到底是不是 AirPlay 接收端）。 */
    private fun endpointFor(ssid: String, host: String, port: Int) = CarPlayEndpoint(
        ssid = ssid,
        passphrase = null,
        airPlayPort = port,
        deviceIdentifier = null,
        publicKeyHex = null,
        sourceVersion = null,
        ipAddresses = listOf(host),
        gateway = host,
    )

    /** Every socket is pinned to this network; stop between probes on invite, cancellation or network change. */
    private suspend fun probeHostsForAirplay(network: Network, hosts: List<String>, ports: List<Int>): Pair<String, Int>? {
        val attempt = connectionAttemptId
        val tried = java.util.concurrent.atomic.AtomicLong()
        val bindFailures = java.util.concurrent.atomic.AtomicLong()
        fun stop() = !sessionLive.get() || connectionAttemptId != attempt || boundNetwork != network ||
            ctrlAdvertiser?.lastInvitePeerIp != null || lateAirplayEndpoint.get()?.sourceIsCurrent == true
        try {
            return AirplaySubnetProbe.find(hosts, ports, PROBE_CHUNK_SIZE,
                shouldStop = ::stop,
                probe = { host, port ->
                    tried.incrementAndGet()
                    var bound = false
                    runCatching { Socket().use { socket ->
                        network.bindSocket(socket)
                        bound = true
                        socket.connect(InetSocketAddress(host, port), FULL_SWEEP_TIMEOUT_MILLIS.toInt())
                    } }.onFailure { if (!bound) bindFailures.incrementAndGet() }.isSuccess
                },
            )
        } finally {
            log("DISCOVERY-PROBE: attempt=$attempt network=#$network hosts=${hosts.size} ports=$ports " +
                "socket_attempts=${tried.get()} bind_failures=${bindFailures.get()} interrupted=${stop()}")
        }
    }

    private fun probeGatewayAsCarHotspot(
        context: Context,
        ssid: String,
    ): CarPlayEndpoint? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val previous = boundNetwork
        val knownAssociation = knownWifiNetwork(context, ssid)
        for (network in boundNetwork?.let { listOf(it) } ?: wifiNetworks(context)) {
            if (!io.github.hypercar.net.CarWifiPolicy.canReuse(cm.getNetworkCapabilities(network),
                    wifiJoiner?.ownsLiveRequest(network) == true || network == knownAssociation)) continue
            val gateway =
                cm.getLinkProperties(network)
                    ?.routes
                    ?.firstOrNull { it.isDefaultRoute }
                    ?.gateway
                    ?.hostAddress
                    ?: continue
            if (!gateway.contains('.') || gateway.contains(':') || gateway == "0.0.0.0") continue
            val reachable =
                runCatching {
                    cm.bindProcessToNetwork(network)
                    java.net.Socket().use { socket ->
                        socket.connect(
                            java.net.InetSocketAddress(gateway, GATEWAY_FALLBACK_PORT),
                            GATEWAY_PROBE_TIMEOUT_MILLIS.toInt(),
                        )
                    }
                }.isSuccess
            if (reachable) {
                boundNetwork = network
                log("WIFI: ✓ \"$ssid\" 的网关 $gateway:$GATEWAY_FALLBACK_PORT 可连——直接当车机热点继续（不等 0x4301）")
                return CarPlayEndpoint(
                    ssid = ssid,
                    passphrase = null,
                    airPlayPort = GATEWAY_FALLBACK_PORT,
                    deviceIdentifier = null,
                    publicKeyHex = null,
                    sourceVersion = null,
                    ipAddresses = listOf(gateway),
                    gateway = gateway,
                )
            }
            log("WIFI: \"$ssid\" 的网关 $gateway:$GATEWAY_FALLBACK_PORT 无响应——继续等车机 0x4301")
        }
        // 全部失败：把进程网络恢复原状（别一直绑在一块无外网的 WiFi 上）
        runCatching { cm.bindProcessToNetwork(previous) }
        return null
    }

    private fun verifyBoundAirplayEndpoint(context: Context, endpoint: CarPlayEndpoint, network: Network?): CarPlayEndpoint? {
        if (!endpoint.sourceIsCurrent) return null
        if (!endpoint.requiresBoundAirplayRoute) return endpoint
        if (network == null || boundNetwork != network) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val link = cm.getLinkProperties(network) ?: return null
        val ssid = joinedSsid.takeIf { joinedNetwork == network }
        val request = wifiJoiner
        val ownsCredentials = request?.ownsHotspot(network, endpoint.ssid, endpoint.passphrase) == true
        if (endpoint.passphrase != null && request?.ownsLiveRequest(network) == true && !ownsCredentials) return null
        return endpoint.verifiedAirplayEndpoint(ssid, network.networkHandle, retiredSsidAirplayAddresses.get(), ownsCredentials) { host ->
            val address = runCatching { android.net.InetAddresses.parseNumericAddress(host.substringBefore('%')) }.getOrNull()
            address != null && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress &&
                link.linkAddresses.none { it.address == address } && link.routes.any {
                    it.destination.prefixLength > 0 && !it.hasGateway() && it.destination.contains(address)
                }
        }
    }

    private fun recordLateAirplayEndpoint(context: Context, coordinator: Iap2Coordinator, attempt: Long, endpoint: CarPlayEndpoint) {
        if (attempt != connectionAttemptId || !sessionLive.get() || iap2 !== coordinator) return
        val verified = verifyBoundAirplayEndpoint(context, endpoint, boundNetwork) ?: return
        if (verified.airPlayPort in 1..65535 && pickHost(verified) != null) {
            verified.whileSourceCurrent { lateAirplayEndpoint.set(verified) }
        }
    }

    private fun invalidateRememberedEndpoint(ssid: String?, target: AirplayTarget) {
        val saved = SettingsStore.lastAirplayEndpoint
        val remembered = RememberedAirplayEndpoint.parse(saved, ssid)
        if (remembered == (target.host to target.port) && SettingsStore.lastAirplayEndpoint == saved) {
            SettingsStore.lastAirplayEndpoint = ""
            log("ENDPOINT: 连接失败，失效当前车机缓存 $target；配对身份保留")
        }
    }

    private fun rememberAirplayEndpoint(ssid: String?, target: AirplayTarget) {
        RememberedAirplayEndpoint.format(target, ssid)?.let { SettingsStore.lastAirplayEndpoint = it }
    }

    /** Fresh discovery is bound to this exact session's Network; never falls back to cellular. */
    private fun matchesAirplayService(endpoint: AirplayServiceProbe.Endpoint): Boolean =
        io.github.hypercar.headunit.ReceiverCompatibility.matchesAirplayService(headUnitProfile.id,
            io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(iap2?.activePeerAddress), endpoint.instance, endpoint.model)

    private fun lateAirplayTarget(context: Context, network: Network, endpoint: CarPlayEndpoint?, invitedHost: String?): AirplayTarget? {
        val verified = endpoint?.let { verifyBoundAirplayEndpoint(context, it, network) } ?: return null
        val host = if (invitedHost == null) pickHost(verified) else invitedHost.takeIf {
            verified.ipAddresses.any { address -> io.github.hypercar.net.EndpointScope.sameAddressOnNetwork(address, it) }
        }
        return host?.let { AirplayTarget(it, verified.airPlayPort, verified.verifiedAirplayNetworkHandle, verified.sourceGeneration) }
            ?.takeIf { it.allowsNetwork(network.networkHandle) }
    }

    private fun sameControlAddress(first: String, second: String): Boolean =
        first == second || io.github.hypercar.net.EndpointScope.sameAddressOnNetwork(first, second)

    private suspend fun connectAirplayEndpoint(
        context: Context,
        sockets: SessionSocketLease<Network>,
        initial: List<AirplayTarget>,
        advertiser: CarPlayCtrlAdvertiser,
        invitation: CtrlInvitation<Network>?,
        checkBeforeDial: () -> Unit,
    ): AirplayEndpointConnector.Connected<Int>? {
        val attempt = connectionAttemptId
        val ssid = currentWifiSsid(context)
        val coordinator = iap2
        val profile = headUnitProfile.id
        val peer = coordinator?.activePeerAddress
        val first = initial.firstOrNull()
        val hints = AirplayEndpointHints(first?.takeIf { it.verifiedNetworkHandle == null && it.sourceGeneration == null }?.port ?: 7000)
        if (first?.verifiedNetworkHandle != null || first?.sourceGeneration != null) {
            initial.distinctBy { it.host }.forEach(hints::advertised)
        }
        var tries = 0
        return AirplayEndpointConnector(
            current = {
                val current = connectionAttemptId == attempt && sessionLive.get() &&
                    sessionSockets === sockets && boundNetwork == sockets.network && ctrlAdvertiser === advertiser &&
                    iap2 === coordinator && headUnitProfile.id == profile &&
                    (peer == null || coordinator?.activePeerAddress?.equals(peer, ignoreCase = true) != false)
                if (current) checkBeforeDial()
                current
            },
            now = { SystemClock.elapsedRealtime() },
            open = { target: AirplayTarget ->
                tries++
                openControlFd(context, sockets, target.host, target.port,
                    if (tries == 1) CONTROL_CONNECT_TIMEOUT_MILLIS.toInt() else AIRPLAY_CONNECT_RETRY_TIMEOUT_MILLIS.toInt()) {
                    checkBeforeDial()
                    if (!target.sourceIsCurrent) throw AirplayEndpointChanged()
                }
            },
            close = { fd: Int -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() }; Unit },
            invalidate = { target -> invalidateRememberedEndpoint(ssid, target) },
            updates = {
                val next = usableCtrlInvitation(advertiser, invitation)
                val late = lateAirplayTarget(context, sockets.network, lateAirplayEndpoint.getAndSet(null), next?.host)
                val newInvite = if (invitation == null) next?.let { hints.invitation(it, it.host) } else null
                when {
                    late != null -> { log("ENDPOINT: 本轮 iAP2 更新 → $late"); listOf(hints.advertised(late)) }
                    newInvite != null -> {
                        val target = newInvite
                        log("ENDPOINT: 新 ctrl 邀请 → $target（同地址保留明确端口）")
                        listOf(target)
                    }
                    else -> emptyList()
                }
            },
            discover = { remaining ->
                val timeout = minOf(1_500L, remaining)
                val invitedHost = usableCtrlInvitation(advertiser, invitation)?.host
                val identity = io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(coordinator?.activePeerAddress ?: peer)
                log("ENDPOINT: 旧目标暂不可用，在网络 #${sockets.network} 重新查询 _airplay._tcp（最多 ${timeout}ms）")
                val fresh = AirplayServiceProbe(context, ::log).browse(sockets.network, timeout, strictNetwork = true) { endpoint ->
                    (invitedHost == null || endpoint.addresses.any { address ->
                        io.github.hypercar.net.EndpointScope.sameAddressOnNetwork(address, invitedHost)
                    }) &&
                        io.github.hypercar.headunit.ReceiverCompatibility.matchesAirplayService(profile, identity, endpoint.instance, endpoint.model)
                }
                if (fresh != null) {
                    log("ENDPOINT: 重新发现 → ${fresh.host}:${fresh.port}（${fresh.instance}）")
                    val addresses = if (invitedHost == null) fresh.addresses else listOf(invitedHost)
                    addresses.map { hints.advertised(AirplayTarget(it, fresh.port, sockets.network.networkHandle)) }
                } else {
                    log("ENDPOINT: 无新服务，保留 ctrl 广播与 iAP2，限时等待新端点/服务启动")
                    emptyList()
                }
            },
            pause = { delay(400) },
            networkHandle = sockets.network.networkHandle,
        ).connect(initial, AIRPLAY_CONNECT_WINDOW_MILLIS)
    }

    /** 本轮控制连接与后续媒体连接共用固定 Network，不轮询其他 Wi-Fi/默认网络。 */
    private suspend fun openControlFd(
        context: Context,
        sockets: SessionSocketLease<Network>,
        host: String,
        port: Int,
        timeoutMillis: Int = CONTROL_CONNECT_TIMEOUT_MILLIS.toInt(),
        beforeConnect: () -> Unit = {},
    ): Int? {
        var acquiredFd: Int? = null
        try {
            return withContext(Dispatchers.IO) {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val fd = openTcpFd(sockets, host, port, timeoutMillis, beforeConnect)
                    acquiredFd = fd
                    log("NET +${stageMs()}ms ✓ $host:$port 已连通（固定网络 #${sockets.network}，耗时 ${SystemClock.elapsedRealtime()-startedAt}ms，fd=$fd）")
                    fd
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log("NET: 控制 socket 失败 network=#${sockets.network} $host:$port ${error.javaClass.simpleName}: ${error.message}；${describeNetwork(context, sockets.network)}")
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext may discard a detached fd when cancellation races its return.
            acquiredFd?.let { runCatching { ParcelFileDescriptor.adoptFd(it).close() } }
            throw cancelled
        }
    }

    /** 手机当前挂着的 Wi-Fi 网络（很可能就是用户手动连上的车机热点）。 */
    private fun wifiNetworks(context: Context): List<Network> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
            cm.allNetworks.filter { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- socket 供给（移交 Rust）


    /** 按蓝牙 MAC 核对系统配对，缺 BLUETOOTH_CONNECT 时返回 null。 */
    @android.annotation.SuppressLint("MissingPermission")
    private fun bondedDeviceOf(mac: String): BluetoothDevice? = runCatching {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            ?.bondedDevices
            ?.firstOrNull { it.address.equals(mac, ignoreCase = true) }
    }.getOrNull()

    @android.annotation.SuppressLint("MissingPermission")
    private fun bondedNameOf(mac: String): String? = runCatching { bondedDeviceOf(mac)?.name }.getOrNull()

    private fun isDesktopHotspotPeer(profileId: String, mac: String): Boolean {
        val peer = io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(mac) ?: bondedNameOf(mac)
        return io.github.hypercar.headunit.ReceiverCompatibility.isDesktopReceiver(profileId, peer)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun carHotspotCacheBlockReason(context: Context, profileId: String, receiver: String): String? {
        val paired = SettingsStore.isCarPaired(receiver) && PairingEvidence.read(context.filesDir, receiver).mayDropProfiles
        return CarHotspotReconnect.credentialBlockReason(target = receiver, bonded = bondedDeviceOf(receiver)?.address,
            paired = paired, desktop = isDesktopHotspotPeer(profileId, receiver))
    }
    /**
     * 配对身份自愈：**文件是主、SharedPreferences 是备份**。
     *
     * 车机记住的是这条配对的 pairingId + 公钥；身份文件一旦丢失就会重新生成密钥，
     * 车机认为「新设备」→ 第二次上车连不上、只有两边删配对才能恢复（现场 2026-10-25）。
     * 这里在每次下发引擎配置前对齐两处：
     *  - 文件缺失 + 有备份 → 写回文件（**不会**重新生成，保住车机那边的配对）
     *  - 文件存在 → 更新备份（升级/换机后仍可恢复）
     */
    private fun ensurePairingIdentity(identity: PairingIdentityFiles.Location) {
        val path = identity.file
        val key = identity.backupKey
        val backup = if (key == null) SettingsStore.pairingIdentityBackup
            else SettingsStore.receiverPairingIdentityBackup(key)
        runCatching {
            if (!path.exists()) {
                if (backup.isNotBlank()) {
                    path.writeText(backup)
                    log("PAIR: 身份文件缺失——已用备份恢复（车机侧配对保持有效）")
                }
            }
            if (path.exists()) {
                val text = path.readText().trim()
                if (text.isNotBlank() && text != backup) {
                    if (key == null) SettingsStore.pairingIdentityBackup = text
                    else SettingsStore.saveReceiverPairingIdentityBackup(key, text)
                    log("PAIR: 已备份配对身份到设置（换机/清缓存后可恢复）")
                }
                val pairingId = text.substringBefore(' ')
                if (pairingId.isNotBlank()) log("PAIR: 当前身份 pairingId=$pairingId")
            }
        }.onFailure { log("PAIR: 身份自愈失败 - ${it.message}") }
    }


    /**
     * 解析 AirPlay 端点地址：**链路本地 IPv6（fe80::）必须带 scope** 才能连。
     *
     * 不带 scope 时 Android 直接回 `EINVAL`——连接包根本发不出去（2026-10-25 23:58 现场：
     * DiPlay 的 0x4301 只给 fe80::db93:…，控制端口每个尝试都是 2ms 内 EINVAL）。
     * 补 `%wlan0` 后控制通道能通，但**事件通道/数据通道**同样走这里，所以必须集中在一处。
     * 依次尝试 %wlan0 / %wlan1 / %ap0 / %wlan2，再枚举本机所有非回环网卡名。
     */
    private fun resolveEndpoint(host: String, port: Int, network: Network? = null): java.net.InetSocketAddress {
        val raw = host.trim()
        val linkLocal = io.github.hypercar.net.EndpointScope.isLinkLocal(raw)
        if (network != null) {
            val cm = appContext()?.getSystemService(ConnectivityManager::class.java)
            val scoped = io.github.hypercar.net.EndpointScope.onNetwork(raw,
                if (linkLocal && !raw.contains('%')) cm?.getLinkProperties(network)?.interfaceName else null)
            return java.net.InetSocketAddress(network.getAllByName(scoped).first(), port)
        }
        if (!linkLocal || raw.contains('%')) {
            return java.net.InetSocketAddress(raw, port)
        }
        val names = buildList {
            add("wlan0"); add("wlan1"); add("ap0"); add("wlan2")
            runCatching {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.filter { it.isUp && !it.isLoopback }
                    ?.forEach { add(it.name) }
            }
        }.distinct()
        for (name in names) {
            val addr = runCatching { java.net.InetAddress.getByName("$raw%$name") }.getOrNull()
            if (addr != null) return java.net.InetSocketAddress(addr, port)
        }
        return java.net.InetSocketAddress(raw, port)
    }

    @Volatile private var sessionSockets: SessionSocketLease<Network>? = null

    private fun openTcpFd(sockets: SessionSocketLease<Network>, host: String, port: Int, timeout: Int,
        beforeConnect: () -> Unit = {}): Int =
        sockets.open(
            create = { Socket() },
            bind = { network, socket -> network.bindSocket(socket) },
            connect = {
                it.tcpNoDelay = true
                val target = resolveEndpoint(host, port, sockets.network)
                beforeConnect()
                it.connect(target, timeout.coerceAtLeast(1_000))
            },
            handoff = { socket ->
                val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()
                fd
            },
        )

    private fun socketProvider(sockets: SessionSocketLease<Network>) = object : RustCoreBridge.SocketProvider {
        override fun bindTimingFd(fd: Int): Boolean = runCatching {
            sockets.open(
                create = { ParcelFileDescriptor.fromFd(fd) },
                bind = { network, duplicate -> network.bindSocket(duplicate.fileDescriptor) },
                connect = {},
                handoff = { true },
            )
        }.onFailure { log("TIMING: bind to network=#${sockets.network} failed: ${it.message}") }.getOrDefault(false)

        override fun openTcpFd(host: String, port: Int, timeoutMs: Int): Int = runCatching {
            openTcpFd(sockets, host, port, timeoutMs)
        }.onFailure { socketFailure(sockets, "TCP", host, port, it) }.getOrElse { -1 }

        override fun openUdpFd(host: String, port: Int): Int = runCatching {
            sockets.open(
                create = { DatagramSocket(null as java.net.SocketAddress?) },
                bind = { network, socket -> network.bindSocket(socket) },
                connect = {
                    if (port == 0) it.bind(java.net.InetSocketAddress(0))
                    else it.connect(resolveEndpoint(host, port, sockets.network))
                },
                handoff = { udp ->
                    val fd = ParcelFileDescriptor.fromDatagramSocket(udp).detachFd()
                    fd
                },
            )
        }.onFailure { socketFailure(sockets, "UDP", host, port, it) }.getOrElse { -1 }
    }

    private fun socketFailure(sockets: SessionSocketLease<Network>, type: String, host: String, port: Int, error: Throwable) {
        log("NET: $type socket 失败 network=#${sockets.network} $host:$port ${error.javaClass.simpleName}: ${error.message}")
    }

    // ---------------------------------------------------------------- Rust 回调

    private var lastAudioPlaying = false
    private val closedPipelineGeneration = java.util.concurrent.atomic.AtomicLong(0)

    private val rustListener = object : RustCoreBridge.EventListener {
        private var activationGeneration = 0L
        private var activationKey = ""
        private var activationReports = 0
        @Synchronized override fun onPipeline(json: String) {
            val next = io.github.hypercar.core.PipelineDiagnostics.parse(json) ?: return
            if (!sessionLive.get() || proactiveActivation.get()?.rejected == true) return
            if (next.writtenFrames > 0 && next.videoChannelReady && next.recordAccepted &&
                next.videoStream == "READY" && next.generation > closedPipelineGeneration.get() &&
                next.generation >= _snapshots.value.pipeline.generation) {
                reconnectBudget.videoReady(retryReceiver(), SystemClock.elapsedRealtime())
                connectionDiagnostics.mark(connectionAttemptId, ConnectionDiagnostics.Stage.FIRST_VIDEO_FRAME)
                fallbackToRemember?.let { identity ->
                    fallbackToRemember = null
                    if (SettingsStore.savedPhoneMac.remember(identity.settingsRevision, identity.address))
                        log("BT: 已将成功推流的稳定手机标识保存到高级设置")
                }
            }
            _snapshots.update { s ->
                if ((s.link !is LinkState.Negotiating && s.link !is LinkState.Connected) ||
                    next.generation <= closedPipelineGeneration.get() || !next.canReplace(s.pipeline)) s
                else s.copy(pipeline = next)
            }
            if (_snapshots.value.pipeline == next) {
                next.videoTcpEvidence?.let { connectionDiagnostics.videoSample(connectionAttemptId, engineCallbacks.current(), it) }
                // Bounded observation only: no retry, ownership claim or success inference.
                val key = "${next.activationLabel}/${next.wifiIap2}/${next.audioReady}"
                if (activationGeneration != next.generation) {
                    activationGeneration = next.generation; activationKey = ""; activationReports = 0
                }
                if (key != activationKey && activationReports < 16) {
                    activationKey = key; activationReports++
                    log("ACTIVATION-GAP: attempt=$connectionAttemptId core=${next.generation} " +
                        "stage=${next.activationLabel} wifi_iap2=${next.wifiIap2} " +
                        "audio_setup=${next.audioReady} requestUI=${next.uiRequests}; " +
                        "Wi-Fi iAP2 observed independently; receiver presentation unconfirmed")
                    connectionDiagnostics.report().lastOrNull()?.let { log(it) }
                }
            }
        }
        override fun onState(state: String, detail: String) {
            val callbackOwner = engineCallbacks.current()
            log("CORE: [$state] $detail")
            if (!sessionLive.get() && state in setOf("activation", "negotiating", "connected", "failed", "ended")) return
            val activation = proactiveActivation.get()
            if (state in setOf("failed", "ended", "error") && activation?.interceptTerminal(detail) == true) {
                closedPipelineGeneration.accumulateAndGet(_snapshots.value.pipeline.generation, ::maxOf)
                _snapshots.update { s -> s.copy(pipeline = s.pipeline.copy(active = false, activationFailure = s.pipeline.activationFailure ?: detail)) }
                return
            }
            if (activation?.rejected == true && state != "stopping") return
            if (state in setOf("ended", "failed", "error", "stopping")) {
                closedPipelineGeneration.accumulateAndGet(_snapshots.value.pipeline.generation, ::maxOf)
                _snapshots.update { s -> s.copy(pipeline = s.pipeline.copy(active = false)) }
            }
            if (state == "ended" || state == "error") {
                val network = sessionSockets?.network
                appContext()?.let { context ->
                    if (network != null) log("NET-END: network=#$network reason=$detail；${describeNetwork(context, network)}")
                }
            }
            when (state) {
                "activation" -> {
                    val stage = runCatching { ConnectionDiagnostics.Stage.valueOf(detail) }.getOrNull() ?: return
                    connectionDiagnostics.mark(connectionAttemptId, stage)
                    log("ACTIVATION: attempt=$connectionAttemptId core=${_snapshots.value.pipeline.generation} stage=$stage")
                    if (stage == ConnectionDiagnostics.Stage.VIDEO_CHANNEL_READY) activation?.videoReady()
                }
                "negotiating" -> {
                    SettingsStore.receiverViewport = null
                    SettingsStore.receiverMaxFps = null
                    io.github.hypercar.carui.CarDisplayLayout.state.value = null
                    io.github.hypercar.carui.CarDisplayLayout.viewAreaChoices.value = null
                    push(link = LinkState.Negotiating(detail))
                }
                "connected" -> {
                    synchronized(targetCarChanges) {
                        if (!cachedHotspotMatchesTarget()) return
                        sessionHadLink = true
                    }
                    sessionIdentity?.let { ensurePairingIdentity(it) }
                    val diagnosticId = connectionAttemptId
                    connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.CONTROL_READY)
                    connectionDiagnostics.report().lastOrNull()?.let { log(it) }
                    MusicBridge.onCarConnected(callbackOwner)
                    streaming = true
                    // 配对成功过 → 记一笔（设置页「目标车机」列表给它打「已配对」标记）。
                    // 蓝牙已配对 ≠ CarPlay 配对过：只有会话真正激活（RECORD 完成）才算。
                    runCatching {
                        // Prefer the identity used by this engine; SSID history can refer to another receiver.
                        val mac = sessionReceiverMac ?: iap2?.activePeerAddress ?: aclCarMac ?: SettingsStore.targetCarMac
                        if (mac.isNullOrBlank()) {
                            log(
                                "PAIR: ⚠ 会话成功但拿不到车机 MAC（ACL / iAP2 对端 / 目标车机都为空）" +
                                    "——本次不写「已配对」标记",
                            )
                        } else {
                            val carName = connectedReceiverName(
                                headUnitProfile.label, mac,
                                SettingsStore.targetCarMac, SettingsStore.targetCarName,
                                io.github.hypercar.iap2.Iap2Peer.address, io.github.hypercar.iap2.Iap2Peer.name,
                                bondedNameOf(mac),
                            )
                            SettingsStore.markCarPaired(mac, carName)
                            val candidate = pendingCarHotspot.getAndSet(null)
                            val context = appContext()
                            if (context != null && candidate != null && candidate.attempt == diagnosticId &&
                                candidate.network == sessionSockets?.network && candidate.receiver.equals(mac, true)) {
                                scope.launch {
                                    autoConnectChanges.lock()
                                    try {
                                        if (connectionAttemptId == candidate.attempt && SettingsStore.isCarPaired(mac) &&
                                            PairingEvidence.read(context.filesDir, mac).mayDropProfiles) {
                                            runCatching { CarHotspotReconnect.save(context, mac, candidate.credentials) }
                                                .onFailure { log("CAR-WIFI-RESUME: 缓存保存失败（${it.javaClass.simpleName}）") }
                                        }
                                    } finally { autoConnectChanges.unlock() }
                                }
                            }
                            val rememberedSsid = sessionWifiSsid ?: joinedSsid
                            if (!rememberedSsid.isNullOrBlank()) SettingsStore.rememberCarNetwork(rememberedSsid, mac)
                            val from = when {
                                mac.equals(iap2?.activePeerAddress, true) -> "当前 iAP2 对端"
                                mac.equals(sessionReceiverMac, true) -> "本轮凭证身份"
                                mac.equals(aclCarMac, true) -> "ACL 广播"
                                else -> "目标车机设置"
                            }
                            log(
                                "PAIR: 已记录配对车机 $carName（$mac，来源=$from）" +
                                    "——「目标车机」列表会标「已配对」",
                            )
                        }
                    }
                    push(
                        link = LinkState.Connected(
                            carName = (sessionReceiverMac ?: iap2?.activePeerAddress ?: aclCarMac ?: SettingsStore.targetCarMac).let { mac ->
                                connectedReceiverName(
                                    profileLabel = headUnitProfile.label,
                                    receiverMac = mac,
                                    targetMac = SettingsStore.targetCarMac, targetName = SettingsStore.targetCarName,
                                    peerMac = io.github.hypercar.iap2.Iap2Peer.address,
                                    peerName = io.github.hypercar.iap2.Iap2Peer.name,
                                    bondedName = mac?.let(::bondedNameOf),
                                )
                            },
                            sinceMillis = System.currentTimeMillis(),
                        ),
                    )
                    // The source service already receives and caches independently.
                    val ctx = appContext()
                    if (ctx != null && !sessionCarLifeMode) {
                        sessionUiJob?.cancel()
                        sessionUiJob = scope.launch {
                            fun current() = sessionLive.get() && streaming && connectionAttemptId == diagnosticId &&
                                engineCallbacks.current() == callbackOwner
                            if (!current()) return@launch
                            val dex = SettingsStore.dexMode
                            val result = runCatching {
                                if (dex) DexCaster.start(ctx, ::current) else CarUiCaster.start(ctx, ::current)
                            }.getOrElse { "启动失败: ${it.javaClass.simpleName}: ${it.message}" }
                            log(if (dex) "DEX: $result" else "CARUI: $result")
                            if (dex) {
                                if (DexCaster.running) {
                                    connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.UI_STARTED)
                                }
                            } else if (CarUiCaster.running) {
                                connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.UI_STARTED)
                                io.github.hypercar.navigation.AmapLinkProbe.start(ctx)
                                CarUiCaster.monitor(ctx, diagnosticId, callbackOwner, ::current)
                            }
                        }
                    }
                }
                "failed" -> {
                    MusicBridge.onCarDisconnected()
                    // Negotiation failures are intercepted above. A failure after video
                    // became ready must not rebuild the native session on the old endpoint.
                    log("SESSION: 媒体会话失败，不重拨旧 RTSP 端点；等待车机重新邀请：$detail")
                    fail(detail, allowReconnect = false, expectedEngine = callbackOwner)
                }
                "ended" -> {
                    MusicBridge.onCarDisconnected()
                    disconnect(userInitiated = false, expectedEngine = callbackOwner, reason = detail)
                }
                "stopping" -> { /* runDisconnect 内处理 */ }
            }
        }

        override fun onLog(line: String) {
            if (line.startsWith("CARWITH-MEDIA:") || line.startsWith("AUDIO-RESOURCE:")) {
                log("$line ${MusicBridge.audioListenerSnapshot}")
            } else {
                log(line)
            }
        }

        override fun onCarWithReopen(attempt: Long, startup: Boolean) {
            if (!carWithEntries.claimReopen(attempt, startup)) return
            scope.launch {
                val ctx = appContext() ?: return@launch
                if (!sessionLive.get() || connectionAttemptId != attempt || !sessionSystemCarLife) return@launch
                val reason = if (startup) "源端 CMD 持续拒绝连接" else "车机画布迟到"
                log("CARWITH-START: $reason，重新启动本机车联入口 attempt=$attempt")
                log("CARWITH-START: attempt=$attempt " + if (sessionSystemCarLife) launchSystemCarLife(ctx, attempt) else launchCarWith(ctx, attempt))
            }
        }

        override fun onForceKeyFrame() {
            if (SettingsStore.dexMode) io.github.hypercar.carui.DexCaster.requestKeyFrame()
            else io.github.hypercar.carui.CarUiCaster.requestKeyFrame()
        }

        override fun onDisplaySize(width: Int, height: Int, maxFps: Int) {
            SettingsStore.receiverMaxFps = maxFps.takeIf { it > 0 }
            SettingsStore.receiverViewport = (width to height).takeIf { width >= 320 && height >= 240 }
            if (sessionLive.get() && width in 320..8192 && height in 240..8192) {
                // receiverForEngine froze the actual peer for this AirPlay session.
                val key = PairingEvidence.canvasKey(headUnitProfile.protocolProfileId, sessionReceiverMac)
                if (key != null) SettingsStore.rememberCarWithCanvas(key, width, height)
                log("CARWITH-SIZE: receiver=${width}x$height canvas_cache=${if (key != null) "saved" else "no_session_identity"}")
            }
        }

        override fun onViewAreaChoices(revision: Long, uuid: String, targets: IntArray) {
            io.github.hypercar.carui.CarDisplayLayout.viewAreaChoices.value =
                if (sessionLive.get() && !sessionCarWithMode && targets.isNotEmpty())
                    io.github.hypercar.carui.CarDisplayLayout.ViewAreaChoices(revision, uuid, targets.toList())
                else null
        }

        override fun onDisplayLayout(width: Int, height: Int, viewX: Int, viewY: Int, viewWidth: Int, viewHeight: Int,
                                     safeX: Int, safeY: Int, safeWidth: Int, safeHeight: Int, drawOutside: Boolean,
                                     statusBarEdge: Int, rightHandDrive: Boolean, allowsFocus: Boolean) {
            if (!sessionCarWithMode) io.github.hypercar.carui.CarReceiverUi.setFocusTransferAllowed(allowsFocus)
            io.github.hypercar.carui.CarDisplayLayout.state.value = io.github.hypercar.carui.DisplayLayout(
                width, height,
                io.github.hypercar.carui.DisplayRect(viewX, viewY, viewWidth, viewHeight),
                io.github.hypercar.carui.DisplayRect(safeX, safeY, safeWidth, safeHeight), drawOutside,
                io.github.hypercar.carui.CarDockEdge.fromReceiver(statusBarEdge, rightHandDrive),
            )
        }

        override fun onNightMode(night: Boolean) {
            io.github.hypercar.carui.CarUiSettings.onCarNight(night)
            log("CARUI: 车机日夜模式 night=$night")
        }

        override fun onReceiverUi(kind: String, payload: String) {
            if (!sessionCarWithMode) io.github.hypercar.carui.CarReceiverUi.handle(kind, payload)
        }

        override fun onMediaKey(usage: Int) {
            if (!sessionLive.get()) return
            // 车机每上报一个按键都留痕：用来确认 home/recents 到底有没有送到，以及送的是什么 usage。
            log("CARKEY: usage=0x" + usage.toString(16))
            val consoleKey = io.github.hypercar.carui.CarConsoleKey.fromInput(usage)
            if (consoleKey != null) {
                if (!sessionCarLifeMode) {
                    if (sessionSourceIsDex) io.github.hypercar.carui.DexCaster.onConsoleKey(consoleKey)
                    else io.github.hypercar.carui.CarUiCaster.onConsoleKey(consoleKey)
                }
                return
            }
            appContext()?.let { MusicBridge.onCarMediaKey(it, usage) }
        }

        override fun onMicrophonePcm(owner: Long, token: Long, pcm: ByteArray, rate: Int, end: Boolean) {
            val attempt = connectionAttemptId
            appContext()?.let { context ->
                io.github.hypercar.carui.CarSpeechValidation.receive(context, token, pcm, rate, end) {
                    sessionLive.get() && connectionAttemptId == attempt && RustCoreBridge.nativeVoiceKeyToken() == owner
                }
            }
        }

        override fun onVoiceAssistant() {
            val attempt = connectionAttemptId
            if (sessionLive.get()) io.github.hypercar.carui.CarVoiceAssistant.wake {
                sessionLive.get() && connectionAttemptId == attempt
            }
        }

        override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {
            if (audioPlaying != lastAudioPlaying) {
                lastAudioPlaying = audioPlaying
                log("AUDIO: playing=$audioPlaying ducking=$ducking")
            }
            // 用 update（CAS）而不是读-改-写：每秒一次，与 log() 的读-改-写并发会吞掉日志行。
            _snapshots.update { s ->
                s.copy(
                    video = s.video.copy(fps = fps, frames = frames, kbps = kbps),
                    audio = s.audio.copy(playing = audioPlaying, ducking = ducking),
                )
            }
        }
    }

    @Volatile
    private var appCtx: android.content.Context? = null

    private val mediaKeyGateCounts = linkedMapOf<String, Long>()
    private var mediaKeyGateLastLog = 0L
    private var mediaKeyGateDetails = 0

    /** Fixed reason labels only: no report bytes, identity or media metadata. */
    @Synchronized
    private fun mediaKeyGateDiagnostic(reason: String) {
        mediaKeyGateCounts[reason] = (mediaKeyGateCounts[reason] ?: 0L) + 1
        val now = System.nanoTime() / 1_000_000L
        if (now - mediaKeyGateLastLog >= 10_000) {
            mediaKeyGateLastLog = now
            mediaKeyGateDetails = 0
            log("MEDIA-KEY-GATE: totals=$mediaKeyGateCounts")
        }
        if (mediaKeyGateDetails++ < 8) log("MEDIA-KEY-GATE: result=$reason")
    }

    /** CSM workers may outlive a UI stop; validate again on MusicBridge's dispatch thread. */
    fun onIap2MediaKey(usage: Int, current: () -> Boolean) {
        val attempt = connectionAttemptId
        if (!sessionLive.get()) { mediaKeyGateDiagnostic("session-not-live"); return }
        if (!current()) { mediaKeyGateDiagnostic("transport-not-current"); return }
        if (usage == 0xcf || usage == 0x100cf) {
            val token = RustCoreBridge.nativeVoiceKeyToken()
            if (token != 0L && sessionLive.get() && connectionAttemptId == attempt && current()) {
                mediaKeyGateDiagnostic("voice-forward")
                RustCoreBridge.nativeSendVoiceKey(token, if (usage == 0xcf) 2 else 3)
            } else mediaKeyGateDiagnostic(if (token == 0L) "voice-no-token" else "voice-session-changed")
            return
        }
        val context = appContext()
        if (context == null) { mediaKeyGateDiagnostic("no-context"); return }
        mediaKeyGateDiagnostic("music-queued")
        MusicBridge.onCarMediaKey(context, usage) {
            val allowed = sessionLive.get() && connectionAttemptId == attempt && current()
            mediaKeyGateDiagnostic(if (allowed) "music-thread-accepted" else "music-thread-session-changed")
            allowed
        }
    }

    private fun appContext(): android.content.Context? = appCtx

    private fun bluetoothName(device: BluetoothDevice): String? =
        try { device.name } catch (_: SecurityException) { null }

    private fun pickHost(endpoint: CarPlayEndpoint): String? {
        // IPv4 优先 → 其余地址；全空再试网关（cardeck EndpointHosts 同序）
        val ips = endpoint.ipAddresses.filter { it.isNotBlank() }
        return ips.firstOrNull { it.contains('.') && !it.contains(':') }
            ?: ips.firstOrNull()
            ?: endpoint.gateway?.takeIf { it.isNotBlank() }
    }

    private suspend fun probeRememberedEndpoint(
        context: Context,
        coordinator: Iap2Coordinator,
    ): CarPlayEndpoint? {
        val currentSsid = currentWifiSsid(context)
        val saved = SettingsStore.lastAirplayEndpoint
        val desktop = io.github.hypercar.headunit.ReceiverCompatibility.isDesktopReceiver(headUnitProfile.id,
            io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(coordinator.activePeerAddress))
        val remembered = RememberedAirplayEndpoint.parse(saved, currentSsid, desktop) ?: return null
        val (host, port) = remembered
        // Use the real control connection once, without a connect/disconnect preflight which
        // can make a single-controller receiver close its service before nativeStart.
        log("SESSION: 使用本车网络缓存 $host:$port 作为候选；实际连接失败后失效并重新发现")
        return CarPlayEndpoint(
            ssid = currentSsid, passphrase = null, airPlayPort = port,
            deviceIdentifier = null, publicKeyHex = null, sourceVersion = null,
            ipAddresses = listOf(host), gateway = host,
        )
    }

    /**
     * 等一个真正可用的端点（有 IP），期间把 0x5703（热点凭据）与 0x4301（AirPlay 端口/地址）
     * 两次回调合并成一份，并每 [ENDPOINT_PROGRESS_MILLIS] 报一次进度。
     *
     * 为什么不能取第一个回调：两条消息分工不同，0x5703 只有 ssid/psk（没有 IP/端口），
     * 0x4301 才带端口与车机地址，先后顺序由车机决定。WinPlay 实测 0x5703 先到、
     * 0x4301 晚 143ms（真车同理）。取首个回调 = 拿凭据那一份去要 IP，必然立刻失败。
     *
     * **拿到 0x5703 就立刻入网 + 广播 `_carplay-ctrl._tcp`**（不等 0x4301）：
     * 真车（Audi MIB3 / BMW）是「在 WiFi 上发现手机 ctrl 服务 → `GET /ctrl-int/1/connect`
     * 邀请 → 邀请成功才下发 0x4301」。若等 0x4301 才入网，两边互等，永远拿不到端点
     * （cardeck 在奥迪上实测的死锁；hypercar 旧流程同样只有 0x4301 一条来源）。
     *
     * 逐条消费 0x5703/0x4301；定时等待只消费唤醒信号。提交缓存网络邀请前检查排队中的现场凭据。
     */
    private suspend fun awaitUsableEndpoint(
        context: Context,
        coordinator: Iap2Coordinator,
        lastSeen: AtomicReference<CarPlayEndpoint?>,
        advertiser: CarPlayCtrlAdvertiser,
        invitationOnly: Boolean,
        usesFallbackPhoneIdentity: Boolean,
        manual: Boolean,
        endpoints: CarHotspotReconnect.EndpointInbox,
        joinHints: AtomicReference<CarPlayEndpoint?>,
        lastRequestedCredentials: AtomicReference<CarWifiRequestIdentity?>,
        cachedHotspotTrigger: () -> CarHotspotReconnect.RestoreAttempt.Trigger?,
        submitCachedHotspot: (CarHotspotReconnect.RestoreAttempt.Trigger, () -> Unit) -> Boolean,
    ): SelectedEndpoint? = withTimeoutOrNull(ENDPOINT_TIMEOUT_MILLIS) {
        val diagnosticId = connectionAttemptId
        // A provisional hotspot can carry a new invitation but cannot activate AirPlay on its own.
        val profileRequiresActivation = headUnitProfile.requireAirplayActivation
        prepareInvitationNetwork(context, advertiser)
        val requireActivation = invitationOnly || profileRequiresActivation
        if (requireActivation) {
            awaitingCarInvitation = true
            if (KnownWifiWaitCopy.waitsForHeadUnitEndpoint(headUnitProfile.id)) {
                log(KnownWifiWaitCopy.endpointOpening())
                push(link = LinkState.Negotiating(KnownWifiWaitCopy.ENDPOINT_STEP))
            } else {
                log("SESSION: 等待本轮 ctrl 邀请或 iAP2 端点；保持蓝牙监听与 Wi-Fi 广播")
                push(link = LinkState.Negotiating("等待车机开启 CarPlay"))
            }
        }
        if (!requireActivation && !endpoints.hasPendingCredentials() && hotspotHandoff.discoveryToken() != null) {
            val remembered = probeRememberedEndpoint(context, coordinator)
            if (remembered != null && advertiser.lastInvitePeerIp == null && !endpoints.hasPendingCredentials()) {
                awaitingCarInvitation = false
                return@withTimeoutOrNull SelectedEndpoint(remembered)
            }
        }

        var densoProbe: Job? = null
        fun hasFreshCredentials(endpoint: CarPlayEndpoint?): Boolean = hasFreshWifiCredentials(endpoint, diagnosticId)
        var cachedWifiReceiver: String? = null
        var cachedWifiJob: Job? = null
        var cacheAcl: CarHotspotReconnect.AclTrigger.Edge? = null
        var lastCacheSkip: String? = null
        fun logCacheSkip(reason: String) {
            if (lastCacheSkip == reason) return
            lastCacheSkip = reason
            log("CAR-WIFI-RESUME: attempt=$diagnosticId trigger=${if (manual) "manual" else "acl"} skipped=$reason")
        }

        fun endpointWifiReady(freshCredentials: Boolean): Boolean = endpoints.canUseEndpoint(
            hasCache = cachedWifiReceiver != null && cachedWifiReceiver.equals(SettingsStore.targetCarMac, ignoreCase = true),
            carNetworkReady = boundNetwork?.let { it != unverifiedInvitationNetwork } == true,
            freshCredentials = freshCredentials,
        )

        suspend fun acceptIap2Endpoint(endpoint: CarPlayEndpoint): CarPlayEndpoint? {
            if (headUnitProfile.requireFreshCtrlInvite) return null
            return autoConnectChanges.withLock {
                synchronized(iap2EndpointChanges) {
                    if (!cachedHotspotMatchesTarget() || !endpoints.accepts(endpoint) ||
                        !endpointWifiReady(freshCredentials = false)) return@withLock null
                    val verified = verifyBoundAirplayEndpoint(context, endpoint, boundNetwork) ?: return@withLock null
                    if (pickHost(verified) == null) return@withLock null
                    val automaticProvisionalWifi = !manual && hotspotHandoff.provisionalNetwork != null
                    if (!CarHotspotReconnect.canUseIap2Activation(
                            automaticProvisionalWifi, automaticProvisionalWifi && hasFreshCredentials(endpoint),
                        )) return@withLock null
                    boundNetwork?.let(hotspotHandoff::confirm)
                    verified
                }
            }
        }

        suspend fun scheduleCachedWifi(next: CarPlayEndpoint? = null) {
            val running = cachedWifiJob?.takeIf { it.isActive }
            if (running != null) {
                val acl = cacheAcl ?: return
                if (cachedHotspotAcl.isCurrent(acl)) return
                running.cancelAndJoin()
                logCacheSkip("acl_no_longer_current")
            }
            val trigger = cachedHotspotTrigger() ?: return
            val receiver = trigger.receiver
            val acl = trigger.acl
            if (hasFreshCredentials(next) || hasFreshCredentials(joinHints.get()) || hasFreshCredentials(standbyEndpoint.get())) {
                logCacheSkip("fresh_credentials")
                return
            }
            if (boundNetwork != null && boundNetwork != unverifiedInvitationNetwork) {
                logCacheSkip("existing_wifi_binding")
                return
            }
            if (wifiJoiner != null) {
                logCacheSkip("existing_wifi_request")
                return
            }
            val saved = runCatching { CarHotspotReconnect.load(context, receiver) }.getOrElse {
                logCacheSkip("cache_read_failed error=${it.javaClass.simpleName}")
                return
            }
            if (saved == null) { logCacheSkip("cache_missing"); return }
            cachedWifiReceiver = receiver
            cacheAcl = acl
            val profileId = headUnitProfile.id
            cachedWifiJob = launch {
                // Start the same grace period at the ACL edge for automatic connections.
                val graceStart = acl?.observedAt ?: SystemClock.elapsedRealtime()
                delay((graceStart + 2_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                fun restoreBlockReason(): String? = when {
                    !sessionLive.get() || connectionAttemptId != diagnosticId || userDisconnectRequested ||
                        ctrlAdvertiser !== advertiser -> "attempt_changed"
                    headUnitProfile.id != profileId || !SettingsStore.targetCarMac.equals(receiver, true) -> "target_or_profile_changed"
                    acl != null && !automaticConnectionAllowed -> "automatic_disabled_or_paused"
                    acl != null && !cachedHotspotAcl.isCurrent(acl) -> "acl_no_longer_current"
                    coordinator.activePeerAddress?.equals(receiver, true) == false -> "different_active_peer"
                    hasFreshCredentials(joinHints.get()) || hasFreshCredentials(standbyEndpoint.get()) -> "fresh_credentials"
                    advertiser.hasUnresolvedOrPublishedInvite -> "ctrl_invitation_pending"
                    !CarHotspotReconnect.canReplaceBinding(boundNetwork, unverifiedInvitationNetwork, joinedSsid, saved.ssid) ->
                        "existing_wifi_binding"
                    wifiJoiner != null -> "existing_wifi_request"
                    else -> carHotspotCacheBlockReason(context, profileId, receiver)
                }
                restoreCarHotspot(context, receiver, saved, advertiser, ::restoreBlockReason, ::logCacheSkip) { request ->
                    submitCachedHotspot(trigger, request)
                }
            }
        }
        try {
            scheduleCachedWifi()
            var usable: CarPlayEndpoint? = null
            var awaitingWifi: CarPlayEndpoint? = null
            var ticks = 0
            var partialKey: String? = null
            var advertisedSsid: String? = null
            var selectedInvite: CtrlInvitation<Network>? = null
            // 静默网关试探：只在「等待期间出现新 WiFi」或「当前 WiFi 正是 iAP2 给的车机热点」
            // 时各试一次（每个 SSID 一次），避免对家里已有的 WiFi（可能有 Apple TV 占着 7000）误连。
            var observedSsid: String? = null
            val probedSsid = HashSet<String>()
            // 网段主动扫描也只在「当前 SSID 就是车机热点」且还没扫过时做一次
            val localScanned = HashSet<String>()
            while (usable == null) {
                if (coordinator.isListening) connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.BLUETOOTH_LISTENING)
                if (coordinator.isSessionActive) connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.IAP2_STARTED)
                if (boundNetwork != null) connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.WIFI_BOUND)
                val next = endpoints.receive(ENDPOINT_TICK_MILLIS)
                scheduleCachedWifi(next)
                if (next != null) {
                    connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.IAP2_PARAMETERS,
                        "wifiCredentials=${!next.ssid.isNullOrBlank()} endpoint=${next.ipAddresses.isNotEmpty()}")
                }
                if (next == null) {
                    ticks++
                    // 手动连接可以早于车机热点出现。等待期间也检查已配对网络，
                    // 不等本轮 300s 超时；复用已连网络并保持邀请监听。
                    prepareInvitationNetwork(context, advertiser)
                    // ① 车机已经来邀请过（GET /ctrl-int/1/connect）→ 直接用邀请方 IP 当端点。
                    //    BMW MGU-55 实测：清单里声明「0x4301 不发」，等它是死等；而邀请一旦
                    //    到达，TCP 对端地址就是车机在热点网段上的地址，AirPlay 控制面在 7000。
                    val invite = autoConnectChanges.withLock {
                        val invite = usableCtrlInvitation(advertiser) ?: return@withLock null
                        endpoints.whenCredentialsDrained { invite }
                    }
                    if (invite != null) {
                        selectedInvite = invite
                        val inviteIp = invite.host
                        val base = lastSeen.get()?.takeIf { it.sourceIsCurrent }
                        log("SESSION: ✓ 车机已邀请（$inviteIp）——用它当 AirPlay 端点 :7000")
                        usable = CarPlayEndpoint(
                            ssid = joinedSsid ?: base?.ssid,
                            passphrase = null,
                            airPlayPort = headUnitProfile.fallbackAirplayPorts.firstOrNull() ?: 7000,
                            deviceIdentifier = base?.deviceIdentifier,
                            publicKeyHex = base?.publicKeyHex,
                            sourceVersion = base?.sourceVersion,
                            ipAddresses = listOf(inviteIp),
                            gateway = inviteIp,
                            fromInvite = true,
                        )
                        continue
                    }
                    val deferred = awaitingWifi?.let { acceptIap2Endpoint(it) }
                    if (deferred != null) {
                        lastSeen.set(deferred)
                        usable = deferred
                        continue
                    }
                    if (endpoints.hasPendingCredentials()) continue
                    // ① 静默网关试探：手机刚连上（或本来就该连）车机热点时，不用等 0x4301——
                    //    车机的 AirPlay 控制面通常就在热点网关的 7000 端口。
                    val ssid = if (!requireActivation && cachedWifiJob?.isActive != true &&
                        hotspotHandoff.discoveryToken() != null) currentWifiSsid(context) else null
                    if (!ssid.isNullOrBlank() && ssid !in probedSsid) {
                        val changed = observedSsid != null && observedSsid != ssid
                        val isCarHotspot = ssid == lastSeen.get()?.ssid || knownWifiReceiver(ssid) != null
                        if (changed || isCarHotspot) {
                            probedSsid.add(ssid)
                            log("WIFI: 检测到 Wi-Fi \"$ssid\"——静默试一次「把网关当车机热点」（:7000）")
                            val probed = probeGatewayAsCarHotspot(context, ssid)
                            if (probed != null) {
                                usable = probed.copy(proactive = profileRequiresActivation)
                                continue
                            }
                            // 邀请可能在 cmd wifi / 网关探测期间到达，避免错过后再花十余秒扫 /20。
                            if (advertiser.lastInvitePeerIp != null) continue
                            // 网关拿不到（宝马 DIRECT 热点 gw=0.0.0.0）时，改在网段里主动找 —— 这是唯一出路
                            if (isCarHotspot && localScanned.add(ssid)) {
                                // 先试 mDNS（车机若广播 _airplay._tcp，这一步就能定端点，比扫网段快）
                                val mdnsHere = runCatching {
                                    AirplayServiceProbe(context, ::log).browse(boundNetwork, accept = ::matchesAirplayService)
                                }.getOrNull()
                                if (advertiser.lastInvitePeerIp != null) continue
                                if (mdnsHere != null) {
                                    log("MDNS: ✓ 等待期间直接查到 _airplay._tcp → ${mdnsHere.host}:${mdnsHere.port}")
                                    usable = CarPlayEndpoint(
                                        ssid = ssid,
                                        passphrase = null,
                                        airPlayPort = mdnsHere.port,
                                        deviceIdentifier = null,
                                        publicKeyHex = null,
                                        sourceVersion = null,
                                        ipAddresses = mdnsHere.addresses,
                                        gateway = mdnsHere.host,
                                        proactive = profileRequiresActivation,
                                    )
                                    continue
                                }
                                val found = discoverAirplayOnLocalSubnet(context, ssid)
                                if (advertiser.lastInvitePeerIp != null) continue
                                if (found != null) {
                                    usable = found.copy(proactive = profileRequiresActivation)
                                    continue
                                }
                            }
                        }
                    }
                    if (ssid != null) observedSsid = ssid
                    // ② 每 15s 报一次进度（tick 是 5s，探测要跟得上换网速度）
                    if (ticks % TICKS_PER_PROGRESS == 0) {
                        log("BT-HEALTH: attempt=$diagnosticId ${coordinator.diagnosticSummary()}")
                        log("CTRL-DIAG: attempt=$diagnosticId ${advertiser.diagnosticSummary()}")
                        connectionDiagnostics.report().lastOrNull()?.let { log(it) }
                        val seen = lastSeen.get()
                        val waited = ticks * ENDPOINT_TICK_MILLIS / 1000
                        // 区分「车机根本没连进来」与「连进来了但没给凭据」——前者是车机
                        // 侧的问题（iDrive 里没把本机当 CarPlay 手机），后者才是协议问题。
                        if (requireActivation) {
                            val wifiBound = wifiReceiverMac != null && boundNetwork != null
                            val sessionsThisRound = KnownWifiWaitCopy.sessionsThisRound(
                                coordinator.sessionStartCount, roundSessionBaseline)
                            val waitingForCarIap2 = KnownWifiWaitCopy.awaitingHeadUnitIap2(
                                wifiBound, coordinator.sessionStartCount, roundSessionBaseline,
                                ReconnectDiscoverability.firstWindowOver())
                            val network = boundNetwork
                            val previousLink = _snapshots.value.link
                            val identityHint = LinkState.Negotiating("未收到车机邀请，请在高级设置填写本机蓝牙 MAC 后重连")
                            val showIdentityHint = headUnitProfile.id == "bmw_id85" && usesFallbackPhoneIdentity &&
                                sessionsThisRound > 0 && network != null && advertiser.listeningOn(network) &&
                                connectionDiagnostics.isWaitingForCtrlTcp(diagnosticId)
                            if (showIdentityHint && previousLink != identityHint) {
                                push(link = identityHint)
                                log("SESSION: 已入车机 Wi-Fi，ctrl 未收到 TCP；本轮使用备用蓝牙标识，可填写本机真实蓝牙 MAC 后重试")
                            } else if (!showIdentityHint && previousLink == identityHint) {
                                push(link = LinkState.Negotiating("等待车机开启 CarPlay"))
                            }
                            if (wifiBound) {
                                val step = KnownWifiWaitCopy.boundNegotiatingStep(
                                    headUnitProfile.id, waitingForCarIap2)
                                val current = _snapshots.value.link
                                if (step != null) {
                                    if (current !is LinkState.Negotiating || current.step != step) {
                                        push(link = LinkState.Negotiating(step))
                                    }
                                } else if (current is LinkState.Negotiating &&
                                    current.step == KnownWifiWaitCopy.HERO) {
                                    push(link = LinkState.Negotiating("等待车机开启 CarPlay"))
                                }
                                log(KnownWifiWaitCopy.boundProgress(
                                    headUnitProfile.id, waited, sessionsThisRound, waitingForCarIap2))
                            } else {
                                val current = _snapshots.value.link
                                if (current is LinkState.Negotiating && current.step == KnownWifiWaitCopy.HERO) {
                                    push(link = LinkState.Negotiating("等待车机开启 CarPlay"))
                                }
                                log(
                                    "SESSION: 等待本轮车机邀请/0x4301，已等 ${waited}s；" +
                                        "蓝牙会话次数=${coordinator.sessionStartCount} 已收Wi-Fi凭据=${seen?.ssid != null}，保持广播与监听",
                                )
                            }
                        } else if (seen == null && coordinator.sessionStartCount == 0 && wifiReceiverMac == null) {
                            log(
                                "SESSION: ${waited}s 内车机一次都没发起 iAP2（ACL 已连但收不到 …cafe 连入）——" +
                                    "请确认「Apple CarPlay」已为该设备启用，继续保持监听",
                            )
                        } else {
                            log(
                                if (seen == null) {
                                    "SESSION: 仍在等车机端点（还没收到 0x5703/0x4301，已等 ${waited}s）" +
                                        "——链路保持中，请在车机上点一次「Apple CarPlay/手机」"
                                } else {
                                    "SESSION: 仍在等车机地址（ssid=${seen.ssid ?: "-"} port=${seen.airPlayPort} " +
                                        "ips=${seen.ipAddresses}，已等 ${waited}s）"
                                },
                            )
                        }
                    }
                    continue
                }
                val previous = lastSeen.get()
                val combined = mergeEndpoint(previous, next)
                val newest = endpoints.newestCredentials(combined)
                val merged = if (newest === combined || combined.ssid != null && combined.ssid != newest.ssid) newest
                    else mergeEndpoint(combined, newest)
                if (!merged.sourceIsCurrent) continue
                lastSeen.set(merged)
                val freshCredentials = hasFreshCredentials(merged)
                val pendingCache = cachedWifiJob?.takeIf { it.isActive }
                if (freshCredentials && pendingCache != null) {
                    logCacheSkip("fresh_credentials")
                    pendingCache.cancelAndJoin()
                }
                if (!endpointWifiReady(freshCredentials)) {
                    if (awaitingWifi == null && pickHost(merged) != null) {
                        log("CAR-WIFI-RESUME: 已收到 AirPlay 地址，等待车机 Wi-Fi 或本轮热点凭据")
                    }
                    awaitingWifi = merged
                    continue
                }
                awaitingWifi = null
                // 0x5703 只给 ssid/psk：先入网 + 广播，车机才可能邀请并下发 0x4301
                val replaceAssociation = freshCredentials &&
                    lastRequestedCredentials.get()?.requiresCredentialUpdate(merged.ssid, merged.passphrase) == true
                val changedCredentials = replaceAssociation || freshCredentials &&
                    wifiJoiner?.requiresCredentialUpdate(merged.ssid, merged.passphrase) == true
                if (!merged.ssid.isNullOrBlank() && (merged.ssid != advertisedSsid || changedCredentials)) {
                    // The last request may have been cancelled before Android withdrew its association.
                    if (freshCredentials && merged.passphrase != null) {
                        lastRequestedCredentials.set(CarWifiRequestIdentity(merged.ssid, merged.passphrase))
                    }
                    log("SESSION: 已收到车机热点参数——先核实入网，再广播 _carplay-ctrl")
                    val network = joinAndBind(context, merged, endpoints = endpoints, replaceAssociation = replaceAssociation) {
                        // Never borrow a hint from another SSID/credentials or an older attempt.
                        joinHints.get()?.takeIf { it.ssid == merged.ssid && it.passphrase == merged.passphrase }
                            ?.deviceIdentifier ?: merged.deviceIdentifier
                    }
                    if (network == null || !merged.sourceIsCurrent) {
                        if (endpoints.shouldRetryJoin(merged)) {
                            log("CAR-WIFI-RESUME: 入网期间热点参数失效，等待本轮有效热点凭据")
                            continue
                        }
                        throw IllegalStateException("车机热点入网失败：${wifiJoiner?.lastFailureReason ?: "没有匹配本车的有效网络"}")
                    }
                    advertisedSsid = merged.ssid
                    connectionDiagnostics.mark(connectionAttemptId, ConnectionDiagnostics.Stage.WIFI_BOUND)
                    advertiser.ensureForNetwork(merged.ssid, network = network)
                    val hotspot = merged.bluetoothHotspot
                    if (densoProbe == null && !invitationOnly && merged.densoOutbackWifi &&
                        hotspot != null && merged.canRequestHotspot(currentBluetoothHotspot(diagnosticId)) &&
                        !advertiser.hasUnresolvedOrPublishedInvite) {
                        densoProbe = launch {
                            // Give the freshly advertised ctrl listener an invitation window first.
                            fun mayProbe() = sessionLive.get() && connectionAttemptId == diagnosticId &&
                                iap2 === coordinator && boundNetwork == network && ctrlAdvertiser === advertiser &&
                                currentBluetoothHotspot(diagnosticId) == hotspot && !advertiser.hasUnresolvedOrPublishedInvite
                            try {
                                delay(2_000)
                                val result = io.github.hypercar.net.DensoOutbackProbe.probe(::mayProbe, {
                                    val manager = context.getSystemService(ConnectivityManager::class.java)
                                    AirplayServiceProbe(context, ::log).browse(network, strictNetwork = true) { candidate ->
                                        val address = runCatching {
                                            android.net.InetAddresses.parseNumericAddress(candidate.host.substringBefore('%'))
                                        }.getOrNull()
                                        val link = manager?.getLinkProperties(network)
                                        mayProbe() && address != null && !address.isAnyLocalAddress &&
                                            !address.isLoopbackAddress && !address.isMulticastAddress && link != null &&
                                            link.linkAddresses.none { it.address == address } &&
                                            link.routes.any { it.destination.prefixLength > 0 &&
                                                !it.hasGateway() && it.destination.contains(address) }
                                    }
                                }) { endpoint ->
                                    io.github.hypercar.net.DensoOutbackProbe.requestInfo(
                                        endpoint, { network.socketFactory.createSocket() }, ::mayProbe)
                                }
                                log("DENSO-OUTBACK: attempt=$diagnosticId network=#$network GET /info $result")
                            } catch (cancelled: CancellationException) {
                                log("DENSO-OUTBACK: attempt=$diagnosticId network=#$network probe cancelled; invitation or wait ended")
                                throw cancelled
                            } catch (error: Exception) {
                                log("DENSO-OUTBACK: attempt=$diagnosticId network=#$network probe failed ${error.javaClass.simpleName}: ${error.message}")
                            }
                        }
                    }
                }
                // Only fresh coordinator callbacks reach here; cached/scanned endpoints
                // are gated separately during fallback. A fresh 0x4301 is also a receiver invitation.
                val resolved = acceptIap2Endpoint(merged)
                if (resolved != null) {
                    lastSeen.set(resolved)
                    usable = resolved
                    continue
                }
                if (merged.requiresBoundAirplayRoute && merged.ipAddresses.isNotEmpty()) awaitingWifi = merged
                val key = "${merged.ssid}/${merged.airPlayPort}"
                if (key != partialKey) {
                    partialKey = key
                    log(
                        "SESSION: 端点缺 IP（0x5703 只给热点凭据 ssid=${merged.ssid ?: "-"}）" +
                            "——继续等 0x4301 的 AirPlay 地址",
                    )
                }
            }
            log("SESSION: 端点就绪 ips=${usable?.ipAddresses} port=${usable?.airPlayPort}")
            // 上面 while (usable == null) 循环退出时 usable 已被智能转换为非空，
            // 原来的 if (usable != null) 恒真，直接置位即可。
            awaitingCarInvitation = false
            SelectedEndpoint(checkNotNull(usable), selectedInvite)
        } finally {
            cachedWifiJob?.cancel()
            densoProbe?.cancel()
        }
    }

    /** 合并两次端点回调：0x5703 给热点凭据、0x4301 给端口与地址，字段各自补齐。 */
    private fun mergeEndpoint(previous: CarPlayEndpoint?, incoming: CarPlayEndpoint): CarPlayEndpoint {
        // Keep Wi-Fi credentials and route evidence tied to their source when combining reports.
        // A report from a newer logical link never inherits fields queued by its predecessor.
        val old = previous?.takeIf { it.sourceIsCurrent }
        if (old == null || old.ssid != null && incoming.ssid != null && old.ssid != incoming.ssid) return incoming
        if (old.passphrase != null && incoming.passphrase != null && old.passphrase != incoming.passphrase) return incoming
        val updatesRoute = incoming.ipAddresses.isNotEmpty() || incoming.airPlayPort > 0 || incoming.gateway != null
        val combineRoute = !updatesRoute || (!incoming.requiresBoundAirplayRoute && !old.requiresBoundAirplayRoute)
        return incoming.copy(
            bluetoothHotspot = incoming.bluetoothHotspot ?: old.bluetoothHotspot?.takeIf { incoming.ssid == null },
            ssid = incoming.ssid ?: old.ssid,
            passphrase = incoming.passphrase ?: old.passphrase,
            airPlayPort = if (combineRoute && incoming.airPlayPort <= 0) old.airPlayPort else incoming.airPlayPort,
            deviceIdentifier = incoming.deviceIdentifier ?: old.deviceIdentifier,
            publicKeyHex = incoming.publicKeyHex ?: old.publicKeyHex,
            sourceVersion = incoming.sourceVersion ?: old.sourceVersion,
            ipAddresses = if (combineRoute) incoming.ipAddresses.ifEmpty { old.ipAddresses } else incoming.ipAddresses,
            gateway = incoming.gateway ?: old.gateway.takeIf { combineRoute },
            requiresBoundAirplayRoute = if (updatesRoute) incoming.requiresBoundAirplayRoute else old.requiresBoundAirplayRoute,
            requiresMatchingAirplayCredentials = if (updatesRoute) incoming.requiresMatchingAirplayCredentials
                else old.requiresMatchingAirplayCredentials,
            airplayRouteBinding = if (updatesRoute) incoming.airplayRouteBinding else old.airplayRouteBinding,
            replayedWifiConfiguration = if (updatesRoute) incoming.replayedWifiConfiguration else old.replayedWifiConfiguration,
            sourceGeneration = incoming.sourceGeneration ?: old.sourceGeneration,
        )
    }

    private fun fail(reason: String, expectedAttempt: Long = connectionAttemptId, allowReconnect: Boolean = true, expectedEngine: Long? = null) {
        scope.launch {
            autoConnectChanges.lock()
            try {
                if (expectedEngine != null && !engineCallbacks.isCurrent(expectedEngine)) return@launch
                if (expectedAttempt != connectionAttemptId ||
                    !engineCallbacks.endConnection { sessionLive.compareAndSet(true, false) }) return@launch
                carWithEntries.retire(expectedAttempt)
                val receiver = retryReceiver()
                val hadLink = sessionHadLink
                sessionSockets?.invalidate()
                val pending = connectJob
                connectJob = null
                pending?.cancel()
                withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { pending?.join() }
                closedPipelineGeneration.accumulateAndGet(_snapshots.value.pipeline.generation, ::maxOf)
                _snapshots.update { it.copy(pipeline = it.pipeline.copy(active = false)) }
                finishDiagnostic("FAILED", reason)
                log("SESSION 失败: $reason")
                releaseSessionResources(AutomaticConnectionPolicy.keepListeningAfterStop(SettingsStore.autoConnect, automaticPauseRemaining > 0))
                _snapshots.update { it.copy(link = LinkState.Failed(reason)) }
                if (allowReconnect) scheduleAutoReconnect("失败: $reason", hadLink, receiver)
                if (SettingsStore.autoConnect) scheduleStandbyAfterCleanup()
            } finally { autoConnectChanges.unlock() }
        }
    }

    private fun scheduleStandbyAfterCleanup() {
        val previous = connectJob
        scope.launch {
            withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { previous?.join() }
            autoConnectChanges.lock()
            try { if (keepIdleIdentity) startIdentityOnly() else startStandbyListening() }
            finally { autoConnectChanges.unlock() }
        }
    }

    private fun push(link: LinkState? = null) {
        _snapshots.update { s ->
            s.copy(
                connectionAttemptId = connectionAttemptId,
                link = link ?: s.link,
                shizukuReady = ShizukuTools.isReady,
                degradedPermissionGranted = degradedPermissionGranted(),
                degradedLocationOn = degradedLocationOn(),
                degradedFirstConnectDone = SettingsStore.degradedFirstConnectDone,
            )
        }
    }

    // ---------------------------------------------------------------- 降级模式引导

    /**
     * 系统侧状态变化后刷新快照（Shizuku 授权成功、从系统设置返回等）。
     * 与连接流程无关，只重算 shizukuReady / 降级预检等派生字段。
     */
    fun refreshSystemState() {
        push()
    }

    /** 专属局域网请求所需的权限；有 Shizuku 时也需要。 */
    private fun degradedPermissionGranted(): Boolean {
        val ctx = appCtx ?: return true
        return runCatching { SpecifierPermissions.isGranted(ctx) }.getOrDefault(false)
    }

    /** 系统定位服务是否开启（仅 12/12L 相关；13+ 用 NEARBY_WIFI_DEVICES，恒 true）。 */
    private fun degradedLocationOn(): Boolean {
        val ctx = appCtx ?: return true
        return runCatching { SpecifierPermissions.isLocationEnabled(ctx) }.getOrDefault(true)
    }

    /**
     * 由 UI 调：发起降级模式所需的运行时权限申请。
     * 走标准 ActivityResult 由 Activity 侧发起（这里只决定申请哪些权限）。
     */
    fun degradedPermissionsToRequest(): Array<String> = SpecifierPermissions.requiredPermissions()

    /** 权限申请结果回调（UI 侧 ActivityResult 后调用），刷新快照让引导卡消失。 */
    fun onDegradedPermissionResult() {
        log("PERM: 权限申请结果 refreshed（granted=${degradedPermissionGranted()}）")
        push()
    }

    /**
     * 降级模式连接前预检：权限缺失/定位服务关闭时**提前失败**并给出可操作原因，
     * 而不是等 WifiNetworkSpecifier 的 requestNetwork 静默 onUnavailable 才懵。
     */
    private fun precheckDegradedMode(context: Context): Boolean {
        val reason = SpecifierPermissions.degradeBlockReason(context)
        if (reason != null) {
            fail(reason)
            return false
        }
        return true
    }

    /** 降级模式成功连上车机热点后：标记首连已完成（系统确认框已批准，后续静默）。 */
    private fun markDegradedFirstConnectIfNeeded() {
        if (!SettingsStore.degradedFirstConnectDone) {
            SettingsStore.degradedFirstConnectDone = true
            log("PERM: ✓ 车机局域网首连完成；系统可复用本次授权，授权失效时仍需确认")
            push()
        }
    }

    /**
     * 环形日志（[LOG_KEEP_LINES] 行）+ 快照更新。任意线程可调。行前缀带 HH:mm:ss.SSS 时间戳（上车对表用）。
     *
     * **刷屏兜底**：同一行文本在 [LOG_RATE_WINDOW_MILLIS] 内超过 [LOG_RATE_MAX] 次就不再落盘
     * （logcat 也一起省掉），等下一次有非重复行进来时补一条「已折叠 N 条」。事件回环的历史
     * 故障就是 0.6s 灌了 300 行；有了限流，环形缓冲能把真正的上下文留下来。
     */
    /** 调试：不连车机直接起 DeX 画面源，验证「虚拟屏 → 编码器」这条路。 */
    fun debugStartDex() {
        scope.launch {
            val ctx = appContext()
            if (ctx == null) {
                log("DEX-DBG: 上下文为空")
                return@launch
            }
            val msg = runCatching { DexCaster.start(ctx) }
                .getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }
            log("DEX-DBG: $msg")
        }
    }

    /** 调试：不连车机也经 Shizuku 建一块虚拟屏，用于验证外部画面源（DeX 等）。 */
    fun debugCreateDisplay() {
        scope.launch {
            val ctx = appContext()
            if (ctx == null) {
                log("CARUI-DBG: 上下文为空，跳过建屏")
                return@launch
            }
            val msg = runCatching { CarUiCaster.debugCreateDisplay(ctx) }
                .getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }
            log("CARUI-DBG: $msg")
        }
    }

    fun log(line: String) {
        if (line.startsWith("BT-ACCEPT: ")) ctrlDiscoveryTrace.record("event=rfcomm-inbound " + line.removePrefix("BT-ACCEPT: "))
        val now = SystemClock.elapsedRealtime()
        var folded = 0
        var overBudget = false
        synchronized(logRateLock) {
            val state = logRates.getOrPut(line) { LogRate(now, 0) }
            if (now - state.windowStart >= LOG_RATE_WINDOW_MILLIS) {
                state.windowStart = now
                state.count = 0
            }
            if (state.count >= LOG_RATE_MAX) {
                suppressedLogs++
                overBudget = true
            } else {
                state.count++
                if (logRates.size > LOG_RATE_MAX_KEYS) logRates.clear() // 防长生日志键膨胀
                folded = suppressedLogs
                suppressedLogs = 0
            }
        }
        if (overBudget) return
        android.util.Log.i("HyperCar", line)
        if (!logRecording) return // 记录开关关闭：丢弃（logcat 仍写，adb 可查）
        val stamp = logTimeFormat.get()?.format(java.util.Date()) ?: ""
        appendDiskLog("$stamp  $line")
        _snapshots.update { s ->
            val extra =
                if (folded > 0) {
                    listOf("$stamp  LOG: ⚠ 已折叠 $folded 条重复日志（同一行 ${LOG_RATE_WINDOW_MILLIS / 1000}s 内超过 $LOG_RATE_MAX 次）")
                } else {
                    emptyList()
                }
            s.copy(recentLogs = (s.recentLogs + extra + "$stamp  $line").takeLast(LOG_KEEP_LINES))
        }
    }

    // ---------------------------------------------------------------- 落盘日志（崩溃后仍可查）

    /**
     * 磁盘日志写入器（懒打开）。只在 [diskLogLock] 内访问。
     *
     * 为什么要落盘：`recentLogs` 是纯内存环形缓冲，进程一死就清零；2026-10-23 20:30
     * 的崩溃现场（连上 AirPlay 端口之后）就是这么丢的——用户「崩溃前快速点了导出」，
     * 导出的也只是崩溃前的旧内容。落盘后重启会把上一轮的尾部带回环形缓冲。
     */
    private var diskLogWriter: java.io.BufferedWriter? = null
    private val diskLogLock = Any()

    /** 打开（必要时先把上一份转成 .1）内部日志文件；失败返回 null，不影响界面日志。 */
    private fun diskLog(): java.io.BufferedWriter? {
        synchronized(diskLogLock) {
            diskLogWriter?.let { return it }
            val dir = appCtx?.filesDir ?: return null
            val writer = runCatching {
                val file = java.io.File(dir, DISK_LOG_NAME)
                if (file.length() > DISK_LOG_MAX_BYTES) {
                    val old = java.io.File(dir, "$DISK_LOG_NAME.1")
                    old.delete()
                    file.renameTo(old)
                }
                java.io.FileOutputStream(file, true).bufferedWriter()
            }.getOrNull() ?: return null
            diskLogWriter = writer
            return writer
        }
    }

    /** 追加一行到磁盘日志并立即 flush：原生崩溃（SIGSEGV/SIGABRT）时最后几行也不会丢。 */
    private fun appendDiskLog(line: String) {
        val writer = diskLog() ?: return
        synchronized(diskLogLock) {
            runCatching {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
        }
    }

    /** 把上一次运行（崩溃/被系统杀）的最后若干行带回环形缓冲，供崩溃后导出。 */
    private fun restoreDiskLogTail() {
        val dir = appCtx?.filesDir ?: return
        val file = java.io.File(dir, DISK_LOG_NAME)
        if (!file.exists() || file.length() == 0L) return
        val tail = runCatching { file.readLines().takeLast(DISK_LOG_TAIL_LINES) }.getOrDefault(emptyList())
        if (tail.isEmpty()) return
        _snapshots.update { s ->
            val header = "===== 上次运行（进程重启/崩溃前）最后 ${tail.size} 行 ====="
            s.copy(recentLogs = (s.recentLogs + header + tail).takeLast(LOG_KEEP_LINES))
        }
    }

    /** Java 层未捕获异常写盘（原生崩溃靠 [appendDiskLog] 的逐行 flush 保命）。 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                appendDiskLog("!!! CRASH（线程 ${thread.name}）：${error.javaClass.name}: ${error.message}")
                error.stackTraceToString().lineSequence().take(80).forEach { appendDiskLog("    $it") }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * 抓上一次运行的崩溃线索（只有 Shizuku 就绪时能读 logcat）。
     *
     * Rust 侧 panic 会经 `nativeInitLogger` 装的 panic hook 打到 logcat（tag HyperCar-Core），
     * 系统崩溃记录在 crash 缓冲区，原生 abort 的证据（`Fatal signal`/`Abort message`）在
     * DEBUG/libc 里。抓回来写进日志后，不用 adb 也能看到「谁崩的、崩在哪」。
     *
     * 注意别用 `logcat -d -t 600` 这种「全量尾巴」：这台机器 logcat 每秒上千行，
     * 600 行只覆盖半秒，崩溃那一瞬很容易被冲掉（2026-10-23 20:44 就是这样漏掉的）。
     * 改成按 tag 精确抓、窗口放大，并只留真正像崩溃的行。指纹文件去重。
     */
    private suspend fun captureCrashArtifacts() {
        if (!ShizukuTools.isReady) return
        val dump = runCatching {
            ShizukuTools.exec(
                "logcat -d -s HyperCar-Core:V -t 600; " +
                    "logcat -b crash -d -t 150; " +
                    "logcat -d -t 5000 -s DEBUG:V libc:V",
                8_000,
            )
        }.getOrNull() ?: return
        val interesting = listOf(
            "PANIC",
            "panicked at",
            "Fatal signal",
            "Abort message",
            "libhypercar_core",
            "tombstone",
            "Process: io.github.hypercar",
            "ANR in io.github.hypercar",
        )
        val lines = (dump.out + "\n" + dump.err)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && interesting.any { key -> it.contains(key) } }
            .distinct()
            .toList()
            .takeLast(40)
        if (lines.isEmpty()) return
        val dir = appCtx?.filesDir ?: return
        val marker = java.io.File(dir, "crash-capture.txt")
        val fingerprint = lines.joinToString("\n").hashCode().toString()
        if (runCatching { marker.readText() }.getOrNull() == fingerprint) return
        runCatching { marker.writeText(fingerprint) }
        log("CRASH: 上一次运行的崩溃线索（${lines.size} 行）")
        lines.forEach { log("CRASH| $it") }
    }

    /** 单行日志的限流状态（仅 [logRateLock] 内访问）。 */
    private class LogRate(var windowStart: Long, var count: Int)

    private val logRateLock = Any()
    private val logRates = HashMap<String, LogRate>()
    private var suppressedLogs = 0

    private val logTimeFormat = object : ThreadLocal<java.text.SimpleDateFormat>() {
        override fun initialValue(): java.text.SimpleDateFormat =
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    }

    /** 调试日志记录开关（持久化到 SettingsStore；关闭不丢既有内容）。 */
    @Volatile
    var logRecording: Boolean = true
        private set

    fun setLogRecording(enabled: Boolean) {
        logRecording = enabled
        SettingsStore.debugLogEnabled = enabled
        _snapshots.value = _snapshots.value.copy(logRecording = enabled)
        log("LOG: 日志记录已${if (enabled) "开启" else "暂停"}")
    }

    /**
     * 导出日志到 `下载/HyperCar/`（MediaStore，免存储权限，文件管理器/微信可见）。
     * 完成后路径写进快照 [SessionSnapshot.lastExportPath] 并落一条日志（诊断页最上方可见）。
     * 必须在后台线程调用（内部已切 IO）。
     */
    fun exportLogs() {
        scope.launch {
            mediaLog.flush()
            val logs = withContext(Dispatchers.IO) {
                synchronized(diskLogLock) {
                    val history = runCatching {
                        diskLogWriter?.flush()
                        val dir = appCtx?.filesDir ?: return@runCatching emptyList<String>()
                        listOf("$DISK_LOG_NAME.1", DISK_LOG_NAME).flatMap { name ->
                            val file = java.io.File(dir, name)
                            if (file.exists()) file.readLines() else emptyList()
                        }
                    }.getOrElse {
                        log("LOG: 读取保留历史失败 ${it.message}，回退当前环形日志")
                        emptyList()
                    }
                    history.ifEmpty { _snapshots.value.recentLogs }
                }
            }
            val (report, liveMedia) = connectionDiagnostics.exportSnapshot()
            val previousMedia = withContext(Dispatchers.IO) {
                runCatching { mediaSummaryStore?.previous(liveMedia).orEmpty() }
                    .getOrElse {
                        log("LOG: 读取已保存媒体摘要失败 ${it.message}")
                        emptyList()
                    }
            }
            if (logs.isEmpty() && report.isEmpty() && previousMedia.isEmpty()) {
                log("LOG: 无日志可导出")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { writeDownloadsFile(logs, report, previousMedia) }
            result.onSuccess { path ->
                _snapshots.value = _snapshots.value.copy(lastExportPath = path)
                log("LOG: ✓ 日志已导出 $path（${logs.size} 行）")
            }.onFailure { e ->
                log("LOG: 导出失败 - ${e.message}")
            }
        }
    }

    /** 写 `Download/HyperCar/hypercar-log-<时间戳>.txt`；Android 10+ 走 MediaStore 免权限。 */
    private fun writeDownloadsFile(logs: List<String>, report: List<String>, previousMedia: List<String>): Result<String> = runCatching {
        val ctx = requireNotNull(appCtx) { "Repo 未初始化" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "hypercar-log-$stamp.txt"
        val text = buildString {
            appendLine("# HyperCar 调试日志  导出于 $stamp")
            appendLine("# 应用 ${DeviceInfo.appVersion(ctx)}")
            appendLine("# 设备 ${DeviceInfo.systemSummary()}")
            appendLine("# 说明：以下时间戳为本机时间；保留的落盘日志（含上一份轮转，读取失败时回退当前日志）共 ${logs.size} 行")
            appendLine("# 连接摘要：CONTROL_READY=控制就绪；VIDEO_WRITING=已有视频写出；均不代表车机已进入桌面或实际出声")
            appendLine("# ACTIVATION-GAP/PIPELINE.activation_gaps 分别核对邀请、iAP2、资源、主屏及媒体；Wi-Fi iAP2 未就绪是独立观察，不强制套用所有接收端")
            report.forEach { appendLine("# $it") }
            previousMedia.forEach { appendLine("# SAVED_MEDIA $it") }
            appendLine()
            logs.forEach { appendLine(it) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/HyperCar",
                )
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = requireNotNull(
                resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
            ) { "MediaStore 插入失败" }
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("输出流打开失败")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "/storage/emulated/0/Download/HyperCar/$name"
        } else {
            // Android 9 及以下：需存储权限路径（manifest 未声明时此分支仅在旧设备出现）
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                ),
                "HyperCar",
            )
            dir.mkdirs()
            val file = java.io.File(dir, name)
            file.writeText(text, Charsets.UTF_8)
            file.absolutePath
        }
    }

    // ---------------------------------------------------------------- 常量
    // 注意：本类是 standalone object，**不能**用 companion object（Kotlin 不允许
    // 「companion 声明在 object 里」），常量直接作为 object 成员即可。

    /**
     * 等车机 iAP2 端点的上限。
     *
     * **5 分钟**（cardeck 0.1.14 / PR #19 定谳）：0x4301 是「活事件」，新协议车机
     * （GM/Cinemo、BMW iD9 同族）声明会发但可能**晚发或等前置条件**——
     * 奥迪 MMI 9310 实测晚 2 分 35 秒、凯迪拉克 CT5 更久；旧实现 8s 拆链时这两类车
     * 永远等不到（拆链还会把车机侧的等待计时反复清零）。窗口内链路保持、每 15s 报进度。
     */
    private const val ENDPOINT_TIMEOUT_MILLIS = 300_000L
    private const val WIFI_LOSS_CREDENTIAL_WAIT_MILLIS = 12_000L

    /** 同一次 ACL 事件会被 receiver 与 service 各转交一次：这段时间内只记一行。 */
    private const val ACL_EVENT_LOG_DEDUPE_MILLIS = 1_500L

    /** 等端点期间的进度日志间隔（车机不开 iAP2 窗口时不至于静默 5 分钟）。 */
    private const val ENDPOINT_PROGRESS_MILLIS = 15_000L

    /**
     * 等端点时的轮询粒度。**1s**：车机 ctrl 邀请到「车机拆 RFCOMM」只有 ~20s 窗口
     * （2026-10-25 车友日志：邀请 10:52:55 → 链路 10:53:16 关），而邀请之前是 5s 一跳，
     * 实测白等了 3.1s 才把邀请变成端点。1s 一跳最坏损失 1s；SSID 读取是零成本 API
     * （不查 shell），进度日志仍按 [TICKS_PER_PROGRESS] × 1s = 15s 打一条。
     */
    private const val ENDPOINT_TICK_MILLIS = 1_000L
    private const val TICKS_PER_PROGRESS = (ENDPOINT_PROGRESS_MILLIS / ENDPOINT_TICK_MILLIS).toInt()

    /** 网关兜底端口（AirPlay 控制面惯例值）与静默试探的单次连接超时。 */
    private const val GATEWAY_FALLBACK_PORT = 7000
    private const val GATEWAY_PROBE_TIMEOUT_MILLIS = 1_200L
    /** 全段扫描的并发块大小：一次最多同时建这么多 socket（太多会被 fd 上限/AP 客户端表打回）。 */
    private const val PROBE_CHUNK_SIZE = 128
    /** 全段扫描单个地址的超时：没人应答的地址多数会立刻 EHOSTUNREACH，超时只是兜底。 */
    private const val FULL_SWEEP_TIMEOUT_MILLIS = 150L
    /** 全段扫描地址总数上限（/20 = 4094，留点余量；更宽的网段也不会扫爆）。 */
    private const val TOTAL_SWEEP_MAX = 8192

    /** 停止时等在途连接协程退出的上限（超过就先拆，连接侧另有 sessionLive 兜底）。 */
    private const val JOIN_TIMEOUT_MILLIS = 5_000L

    /** 掉线自动重连：退避序列（ms）与最大连续尝试次数。 */

    /** 单个候选网络上连 AirPlay 控制端口的超时（多网络轮试，单次不能给太久）。 */
    private const val CONTROL_CONNECT_TIMEOUT_MILLIS = 2_500L

    /** 连 AirPlay 控制端口的重试窗口（接收端常在 invite 之后才开端口，一轮失败不能当死）。 */
    private const val AIRPLAY_CONNECT_WINDOW_MILLIS = 8_000L

    /** 重试轮里的单次连接超时（比首轮短，避免窗口被几轮超时吃光）。 */
    private const val AIRPLAY_CONNECT_RETRY_TIMEOUT_MILLIS = 1_200L

    /** 环形日志保留行数（诊断页展示 + 导出）。 */
    private const val LOG_KEEP_LINES = 1600

    /** 单行日志限流：窗口内最多落盘次数。 */
    private const val LOG_RATE_MAX = 4
    private const val LOG_RATE_WINDOW_MILLIS = 10_000L

    /** 限流表容量上限（超过就清空重建，避免长跑后无限增长）。 */
    private const val LOG_RATE_MAX_KEYS = 256

    /** 落盘日志文件名（filesDir 下，不需要任何存储权限）。 */
    private const val DISK_LOG_NAME = "hypercar-session.log"

    /** 落盘日志超过这个大小就转存成 `.1`（只留一份上一代）。 */
    private const val DISK_LOG_MAX_BYTES = 1_500_000L

    private fun prepareCarWithSource(context: Context, diagnosticId: Long, deviceMac: String, wakePeer: String?): Boolean {
        fun current() = sessionLive.get() && carWithEntries.current(diagnosticId)
        if (!current()) return false
        // Known-Wi-Fi wakes run before resumeKnownCarNetwork populates wifiReceiverMac.
        val profile = headUnitProfile.protocolProfileId
        val key = PairingEvidence.canvasKey(profile, iap2?.activePeerAddress,
            wakePeer, wifiReceiverMac, aclCarMac, SettingsStore.targetCarMac)
            ?: if (profile in setOf("bmw_id85", "bmw_id9")) {
                PairingEvidence.canvasKey(profile, knownWifiReceiver(wifiSsidFromApi(context)))
            } else null
        val cached = PairingEvidence.cachedCanvas(key, SettingsStore::carWithCanvas)
        val canvas = cached ?: (1920 to 1080)
        val config = org.json.JSONObject(SettingsStore.engineConfigJson("127.0.0.1", 0, deviceMac, "",
            carWithSource = true, headUnitProfile = headUnitProfile,
            systemCarLifeSource = sessionSystemCarLife))
            .put("width", canvas.first).put("height", canvas.second)
            .put("carwith_cached_canvas", cached != null)
            .put("carwith_navigation_enabled", carWithHudNavigationAllowed).toString()
        log("CARWITH: 源端预热画布 ${canvas.first}x${canvas.second} canvas_cache=" +
            when { cached != null -> "hit"; key != null -> "miss"; else -> "no_identity" })
        if (!RustCoreBridge.nativePrepareCarWith(config, diagnosticId)) return false
        if (!current()) {
            RustCoreBridge.nativeStopCarWith(diagnosticId)
            return false
        }
        sessionUiJob?.cancel()
        sessionUiJob = scope.launch {
            try {
                CarUiCaster.releaseForCarWith()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!current()) return@launch
                log("CARWITH: CarLife 通道守候与接收服务就绪；启动源端 attempt=$diagnosticId；独立保持至本轮连接结束")
                val launchTime = System.currentTimeMillis()
                val result = if (sessionSystemCarLife) launchSystemCarLife(context, diagnosticId)
                    else launchCarWith(context, diagnosticId)
                if (!current()) return@launch
                val version = runCatching {
                    context.packageManager.getPackageInfo("com.miui.carlink", 0).let { "${it.versionName}/${it.longVersionCode}" }
                }.getOrDefault("unknown")
                log("CARWITH-START: attempt=$diagnosticId version=$version $result")
                kotlinx.coroutines.delay(6_000)
                if (current()) CarWithStartupDiagnostics.collect(launchTime) { evidence ->
                    if (current()) log("CARWITH-START: attempt=$diagnosticId $evidence")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) log("CARWITH: 源端准备失败：${error.message}")
            } finally {
                if (!current()) RustCoreBridge.nativeStopCarWith(diagnosticId)
            }
        }
        return true
    }

    /** 重启时从落盘日志带回环形缓冲的行数（够覆盖崩溃前的一段现场）。 */
    private const val DISK_LOG_TAIL_LINES = 400

    /**
     * CarWith 模式入口（2026-11-03）。
     *
     * 用**普通 intent** 打开 CarWith 已导出的 `CarlifeAdbActivity`（名字里有 Adb，但普通应用
     * 就能启动，不需要 adb 客户端，也不需要系统签名/root）。CarWith 会自己建显示与编码器，
     * 并在本机开 CarLife 端口（7240/8240/9240/9340）；Rust 引擎随即主动拨号接管。
     *
     * 前提：装了 CarWith（com.miui.carlink）；MIUI「后台弹出界面」需允许，
     * 否则入口会被静默丢弃（7240 一直 ECONNREFUSED）。不再检查/冻结任何包。
     */

    /**
     * 系统自带 CarLife 入口（三星车联 / 荣耀车联等）。
     *
     * 这些 ROM 的 CarLife 引擎在用户点「开始连接」后进入等待态并监听 7240 等端口，但**再次拉起
     * 它的界面会把等待态清掉**——2026-11-23 在 SM-S9380 实测：端口已 LISTEN 时重新启动
     * carlife.samsung，七个监听口全消失。所以这里不主动拉起任何界面，只给提示；Rust 侧负责
     * 每秒拨一次 127.0.0.1:7240，端口一开就接管。
     */
    private suspend fun launchSystemCarLife(ctx: android.content.Context, attempt: Long): String {
        val where = io.github.hypercar.session.StartupRequirements.systemCarLifePackage(ctx) ?: "系统车联"
        val message = "请在$where 里点「百度 CarLife+」→「WLAN 连接」→「开始连接」，再切回本应用；正在等待 7240"
        log("SYSTEM-CARLIFE: attempt=$attempt $message")
        return message
    }

    private suspend fun launchCarWith(ctx: android.content.Context, attempt: Long): String {
        // Do not probe 7240 with a disposable client: it consumes a real CarLife connection.
        val comp = "com.miui.carlink/com.miui.carlink.castfwk.usb.CarlifeAdbActivity"
        var shellStatus = "Shizuku 未就绪"
        val launched = carWithEntries.launch(attempt, shell = {
            if (!ShizukuTools.isReady) return@launch false
            val result = runCatching { ShizukuTools.exec("am start -n $comp", 8_000) { carWithEntries.current(attempt) } }.getOrNull()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            shellStatus = result?.let { "am start exit=${it.code} out=${it.out.take(320)} err=${it.err.take(240)}" }
                ?: "am start 未取得结果"
            result?.ok == true
        }, fallback = {
            shellStatus += runCatching {
                ctx.startActivity(android.content.Intent().setComponent(
                    android.content.ComponentName("com.miui.carlink", "com.miui.carlink.castfwk.usb.CarlifeAdbActivity"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                "；startActivity($comp) 已调用"
            }.getOrElse { "；startActivity($comp) 失败 ${it.javaClass.simpleName}" }
        })
        return if (launched) shellStatus else "已跳过过期 CarWith 启动 attempt=$attempt"
    }
}
