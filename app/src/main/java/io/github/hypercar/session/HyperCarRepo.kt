@file:Suppress("DEPRECATION")   // allNetworks/WifiInfo/getDefaultAdapter 等平台 API 已废弃但无替代
package io.github.hypercar.session

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import io.github.hypercar.net.SessionSocketLease
import io.github.hypercar.headunit.HeadUnitActivationPolicy
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.hypercar.autowake.AutoStart
import io.github.hypercar.bluetooth.CarProfileGuard
import io.github.hypercar.bluetooth.ReconnectDiscoverability
import io.github.hypercar.autowake.HyperCarService
import io.github.hypercar.carui.CarUiCaster
import io.github.hypercar.carui.MusicBridge
import io.github.hypercar.core.AudioStats
import io.github.hypercar.core.LinkState
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.core.VideoStats
import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2Coordinator
import io.github.hypercar.net.BluetoothMac
import io.github.hypercar.net.AirplayServiceProbe
import io.github.hypercar.net.CarPlayCtrlAdvertiser
import io.github.hypercar.net.SpecifierPermissions
import io.github.hypercar.net.WifiJoiner
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 会话总管：上车秒连的完整链路。
 *
 * 时序（与 docs/AUTO-CONNECT.md 的 cardeck 链路同源，协商已下沉 Rust）：
 *  车机蓝牙 ACL 连上 → （清单静态接收器冷启动进程）→ HyperCarService FGS
 *    → [AutoStart.handler] → [connect]
 *    → iAP2 协商（0x5703 热点凭据 / 0x4301 AirPlay 端点）
 *    → Shizuku 静默连车机热点 + bindProcessToNetwork
 *    → 连 AirPlay 控制端口 → detachFd 移交 Rust → Rust 内完成全部 AirPlay 协商
 *    → 本机自绘界面自采自编，经 nativePushVideoFrame / nativePushAudioPcm 投喂 Rust
 */
object HyperCarRepo {
    private val proactiveActivation = AtomicReference<ProactiveActivation?>(null)
    private val connectionDiagnostics = ConnectionDiagnostics(SystemClock::elapsedRealtime)
    @Volatile private var connectionAttemptId = 0L

    private fun finishDiagnostic(outcome: String, reason: String) {
        connectionDiagnostics.finish(connectionAttemptId, outcome, reason)
        connectionDiagnostics.report().lastOrNull()?.let { log(it) }
    }

    private val _snapshots = MutableStateFlow(SessionSnapshot())
    val snapshots: StateFlow<SessionSnapshot> = _snapshots.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var iap2: Iap2Coordinator? = null

    private var wifiJoiner: WifiJoiner? = null

    /**
     * `_carplay-ctrl._tcp` 广播器（真车邀请驱动流程的核心：车机在 WiFi 上发现它才下发
     * 0x4301）。生命周期与本会话一致：拿到 0x5703 → 绑网 → 注册；拆除时注销。
     */
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /** 本轮已经静默加入过的车机热点（避免「先拿凭据入网」与「端点到了再入网」重复连接）。 */
    @Volatile
    private var joinedSsid: String? = null

    @Volatile
    private var joinedNetwork: Network? = null

    /** AirPlay 会话是否已建立（冗余端点去重，cardeck「推流中忽略 0x5703/0x4301」教训）。 */
    @Volatile
    private var streaming = false

    /**
     * AirPlay 协商失败时的「原地重试」：**不拆 iAP2 链路**，重开控制 socket 再谈一次。
     *
     * 依据（2026-10-24 真车 + 版本对照）：车机的 CarPlay 图标与窗口挂在 iAP2 会话上。
     * 旧行为是 AirPlay 一失败就 [fail] → 拆 BT/Wi-Fi，车机立刻把图标收回去；
     * 用户看到的现象正是「装了新版本后车机上没有 CarPlay 图标了」。
     * 现在保留链路、按 2.5s 间隔重试 [AIRPLAY_MAX_ATTEMPTS] 次，仍不行才真失败。
     */
    private suspend fun startNativeForSession(
        fd: Int, sockets: SessionSocketLease<Network>, attempt: Long, configure: () -> String,
    ): Boolean {
        var transferred = false
        try {
            autoConnectChanges.lock()
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!sessionLive.get() || connectionAttemptId != attempt || sessionSockets !== sockets)
                    throw CancellationException("旧核心启动结果已失效")
                val json = io.github.hypercar.navigation.CarWithNavigation.prepareConfig(configure())
                transferred = true
                return RustCoreBridge.nativeStart(json, fd, socketProvider(sockets))
            } finally { autoConnectChanges.unlock() }
        } finally {
            if (!transferred) runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }

    private fun retryAirplayOrFail(detail: String) {
        // 车机主动关掉控制通道时**不要重试同一个端点**：它紧接着会把 7000 的监听也关掉，
        // 之后每次重试都是 ECONNREFUSED（2026-10-25 22:32 现场：6 轮 × 2.5s 全是拒连，
        // 既没意义，还占着「等车机重新发起 iAP2」的时间窗）。
        // 这种情况直接结束本轮：清掉记忆端点，等车机下一轮 iAP2 会话（它会自己重连蓝牙再邀请）。
        val peerClosed = detail.contains("控制通道已关闭") ||
            detail.contains("连接被对端关闭") ||
            detail.contains("Broken pipe") ||
            detail.contains("Connection reset")
        if (peerClosed) {
            log("SESSION: 车机主动关闭控制通道（$detail）——不再重试旧端口，等下一轮 iAP2 会话")
            runCatching { SettingsStore.lastAirplayEndpoint = "" }
            fail(detail)
            return
        }

        val host = airplayHost
        val port = airplayPort
        val canRetry = host != null && port > 0 && airplayAttempts < AIRPLAY_MAX_ATTEMPTS && sessionLive.get()
        if (!canRetry) {
            fail(detail)
            return
        }
        airplayAttempts++
        val attempt = airplayAttempts
        log(
            "SESSION: AirPlay 协商失败（$detail）——保留 iAP2 链路（车机继续显示 CarPlay），" +
                "第 $attempt/$AIRPLAY_MAX_ATTEMPTS 次原地重试",
        )
        push(link = LinkState.Negotiating("AirPlay 重试 $attempt/$AIRPLAY_MAX_ATTEMPTS"))
        val sockets = sessionSockets ?: return
        val owner = connectionAttemptId
        scope.launch {
            autoConnectChanges.lock()
            try {
                if (connectionAttemptId != owner || sessionSockets !== sockets || !sessionLive.get()) return@launch
                io.github.hypercar.navigation.CarWithNavigation.clear()
                runCatching { RustCoreBridge.nativeStop() }
            } finally { autoConnectChanges.unlock() }
            delay(AIRPLAY_RETRY_DELAY_MILLIS)
            if (sessionSockets !== sockets || !sessionLive.get()) return@launch
            val context = appContext() ?: return@launch
            val carNetworkSsid = currentWifiSsid(context)
            val connected = connectAirplayEndpoint(context, sockets, listOf(AirplayTarget(host!!, port)))
            if (connected == null) {
                if (connectionAttemptId == owner && sessionSockets === sockets && sessionLive.get())
                    retryAirplayOrFail("控制端口重连失败 $host:$port（已重新发现）")
                return@launch
            }
            val target = connected.target
            val fd = connected.socket
            val started = startNativeForSession(fd, sockets, owner) {
                airplayHost = target.host
                airplayPort = target.port
                rememberAirplayEndpoint(carNetworkSsid, target)
                val receiver = receiverForEngine()
                val identity = PairingIdentityFiles.location(context.filesDir, receiver)
                ensurePairingIdentity(identity)
                sessionIdentity = identity
                val identityPath = identity.file.absolutePath
                val json = SettingsStore.engineConfigJson(target.host, target.port, sessionDeviceMac, identityPath,
                    receiverBluetoothMac = receiver, carWithSource = sessionCarWithMode)
                log("CORE: 重试 nativeStart（$target fd=$fd，第 $attempt 次）")
                json
            }
            log("CORE: 重试 nativeStart 返回 $started")
            if (!started) retryAirplayOrFail("核心层重启失败")
        }
    }

    /** 本轮连接的计时基准（毫秒）；日志里统一打成 `+Nms`，便于定位慢在哪一步。 */
    @Volatile
    private var sessionStageStart: Long = 0L

    /**
     * AirPlay 侧已确定的端点与重试计数（失败重试用）。
     *
     * 为什么要重试而不是直接失败：车机的 CarPlay 图标/窗口与 **iAP2 链路的存活**绑定。
     * 2026-10-24 实测：拿到 ctrl 邀请后 AirPlay 若在 ~5s 内失败，我们会立刻拆 iAP2、
     * 车机随即把 CarPlay 图标收回去——表现为「车机上根本看不到我们的 CarPlay」。
     * 保留链路、原地重试，车机才会一直把窗口开着，也才有机会把 /info 走通。
     */
    @Volatile
    private var airplayHost: String? = null
    /** Atomic host+port, scoped to the connection attempt by recordLateAirplayEndpoint. */
    private val lateAirplayEndpoint = AtomicReference<AirplayTarget?>(null)

    @Volatile
    private var airplayPort: Int = 0

    @Volatile
    private var airplayAttempts: Int = 0

    /** 本机蓝牙 MAC（AirPlay 重试时重建引擎配置要用）。 */
    @Volatile
    private var sessionDeviceMac: String = ""

    /** 当前车机档案（设置里可切换；每次连接重新读取）。 */
    private val headUnitProfile: io.github.hypercar.headunit.HeadUnitProfile
        get() = io.github.hypercar.headunit.HeadUnitProfiles.current()

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

    /** Identity pinned to a known Wi-Fi resume; never reuse another receiver's Bluetooth identity. */
    @Volatile private var wifiReceiverMac: String? = null
    @Volatile private var sessionReceiverMac: String? = null
    @Volatile private var sessionWifiSsid: String? = null
    private var wifiReconnectMonitor: ConnectivityManager.NetworkCallback? = null
    private var wifiReconnectCheck: Job? = null
    private var sessionNetworkMonitor: ConnectivityManager.NetworkCallback? = null

    // ---- 掉线自动重连（方案 A-3）----
    // 车机侧链路断开（ACL 掉、AirPlay 会话结束、车机重启）后自动重新建链，
    // 不需要用户去点「连接」。用户主动断开时置 userDisconnectRequested，不再重连。
    private var reconnectJob: Job? = null
    private var sessionUiJob: Job? = null
    @Volatile private var sessionCarWithMode = false
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
    private val activationWakeEvidence = CarActivationWakeEvidence()
    @Volatile private var lastConnectInviteNudgeAt = 0L
    @Volatile private var lastHandoffInviteNudgeAt = 0L
    /** True while bound and waiting for ctrl invite / inbound iAP2 (not while streaming). */
    @Volatile private var awaitingCarInvitation = false

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
    @Volatile
    private var boundNetwork: Network? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            appCtx = app
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
            AutoStart.handler = handler@{ ctx, device, connected ->
                val app = ctx.applicationContext
                if (!connected) {
                    val cleanup = runCatching { device.address }.getOrNull()
                        ?.let { iap2?.captureBluetoothLost(it) }
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
                        if (address != aclCarMac) {
                            aclCarMac = address
                            log("AUTO: 本轮 iAP2 目标锁定为 ACL 车机 $address（${bluetoothName(device) ?: "?"}）")
                        }
                        // 音频 profile 干预受车机档案控制；是否为本次失败根因仍需实车验证。
                        CarProfileGuard.noteCar(address)
                        updateCarProfileGuard(app)
                        CarProfileGuard.onCarConnected(app, device, address)
                    }
                    if (hasCurrentSession) {
                        // Already waiting for invite/iAP2: refresh discovery on ACL, do not start AirPlay.
                        scope.launch { nudgeInvitationDiscovery(app, "ACL 连上") }
                    } else if (!HyperCarService.isRunning) {
                        runCatching {
                            app.startForegroundService(HyperCarService.startIntent(app, device))
                        }.onFailure {
                            // FGS 被系统拦（未授悬浮窗等）：降级直接连接（进程可能被回收）
                            log("AUTO: FGS 启动被拦（${it.message}）——降级直接拉起会话")
                            connectAutomatically(app)
                        }
                    } else {
                        connectAutomatically(app)
                    }
                } else {
                    // BMW retires Bluetooth after Wi-Fi takes over. Only keep a live, authenticated
                    // Wi-Fi iAP2 session with a recent successful RTSP feedback response.
                    if (sessionLive.get() && RustCoreBridge.nativeCanKeepWifiSession()) {
                        iap2?.retireForWifi()
                        log("HANDOFF: 蓝牙 ACL 已退场，Wi-Fi iAP2 就绪且控制保活正常，继续 CarPlay")
                        logAclEvent(device, connected)
                        return@handler
                    }
                    val processBound = hasCurrentSession && wifiReceiverMac != null && boundNetwork != null
                    if (processBound) {
                        // BMW secondary: phone is often already on car Wi-Fi; ACL closes after a
                        // short window. Refresh ctrl on that handoff so the car can invite on Wi-Fi.
                        log("WIFI-RESUME: 保留已知车机网络及监听；ACL 短窗口退场属二次上车常见交接，刷新 ctrl 等 Wi-Fi 邀请")
                        scope.launch { nudgeInvitationDiscovery(app, "ACL 退场交 Wi-Fi", handoffToWifi = true) }
                        logAclEvent(device, connected)
                        return@handler
                    }
                    if (hasCurrentSession && !streaming) {
                        // Bind often finishes after this short ACL. Confirm the known car
                        // network off the main thread before deciding to tear the wait down.
                        val attempt = connectionAttemptId
                        val address = runCatching { device.address }.getOrNull()
                        scope.launch {
                            val present = knownCarWifiAssociated(app)
                            if (connectionAttemptId != attempt || !sessionLive.get()) return@launch
                            val boundNow = wifiReceiverMac != null && boundNetwork != null
                            if (!AclHandoffKeep.keepInviteWait(hasCurrentSession, streaming, boundNow, present)) {
                                userDisconnectRequested = false
                                if (address != null && address == aclCarMac) aclCarMac = null
                                disconnect(userInitiated = false)
                                return@launch
                            }
                            log(if (boundNow) {
                                "WIFI-RESUME: 保留已知车机网络及监听；ACL 短窗口退场属二次上车常见交接，刷新 ctrl 等 Wi-Fi 邀请"
                            } else {
                                "WIFI-RESUME: ACL 退场时已知车机 Wi-Fi 已连接，进程绑定尚未完成，保留本轮等待"
                            })
                            nudgeInvitationDiscovery(app, "ACL 退场交 Wi-Fi", handoffToWifi = true)
                        }
                        logAclEvent(device, connected)
                        return@handler
                    }
                    // 真正离开车机后允许下一轮 ACL 自动连接；服务停止不回灌 ACL 事件。
                    userDisconnectRequested = false
                    runCatching { device.address }.getOrNull()?.let { address ->
                        if (address == aclCarMac) aclCarMac = null
                    }
                    disconnect(userInitiated = false)
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
        lastAclEventKey = key
        lastAclEventAt = now
        log("AUTO: 车机蓝牙 ${if (connected) "连上" else "断开"}（$name）")
    }

    /** handler 只在主线程（receiver/service 都是主线程）调用，无需加锁。 */
    private var lastAclEventKey: String? = null
    private var lastAclEventAt = 0L

    // ---------------------------------------------------------------- 对 UI

    val hasCurrentSession: Boolean get() = sessionLive.get() || connecting || streaming

    /** Only the filtered classic ACL broadcast calls this, never the foreground-service relay. */
    internal fun noteCarActivationWake(mac: String, connected: Boolean) {
        activationWakeEvidence.observe(mac, connected, SystemClock.elapsedRealtime())
    }

    /** BMW HUD: AMap notifications for self UI or structured CarWith data; other profiles send none. */
    @Volatile private var hudSessionEnabled = false
    internal val hudNavigationAllowed: Boolean get() = hudSessionEnabled && hasCurrentSession &&
        sessionCarWithMode == SettingsStore.carWithMode &&
        headUnitProfile.id == io.github.hypercar.headunit.HeadUnitProfiles.BMW_ID85.id &&
        (sessionCarWithMode || io.github.hypercar.carui.CarUiSettings.defaultMap == io.github.hypercar.carui.MapProvider.AMAP)
    internal val carWithHudNavigationAllowed: Boolean get() = hudNavigationAllowed && sessionCarWithMode

    fun connect(context: Context) = requestConnect(context, manual = true, source = "manual")

    private fun connectAutomatically(context: Context, source: String = "acl") =
        requestConnect(context, manual = false, source = source)

    fun connectFromCompanion(context: Context, address: String) {
        if (!io.github.hypercar.autowake.CompanionWakePolicy.accepts(
                SettingsStore.autoConnect, SettingsStore.targetCarMac, address,
            )) return
        if (hasCurrentSession) {
            scope.launch { nudgeInvitationDiscovery(context.applicationContext, "companion") }
            return
        }
        log("COMPANION: 恢复监听并请求自动会话；仍等待本轮车机邀请")
        connectAutomatically(context, "companion")
    }

    /**
     * Known-Wi-Fi invite wait: ACL is often only a short BMW wake window before Wi-Fi takes
     * over. Refresh `_carplay-ctrl._tcp` so the car can invite on Wi-Fi; do not start the
     * experimental proactive AirPlay switch, and do not require a lasting BT iAP2 session.
     */
    private suspend fun nudgeInvitationDiscovery(
        context: Context,
        reason: String,
        handoffToWifi: Boolean = false,
    ) {
        val advertiser = ctrlAdvertiser
        val now = SystemClock.elapsedRealtime()
        val keepWifi = runCatching { RustCoreBridge.nativeCanKeepWifiSession() }.getOrDefault(false)
        val anchor = InviteWakeNudge.cooldownAnchor(
            handoffToWifi, lastConnectInviteNudgeAt, lastHandoffInviteNudgeAt)
        if (!InviteWakeNudge.shouldNudge(sessionLive.get(), streaming, keepWifi, advertiser != null,
                now, anchor, handoffToWifi)) return
        val ssid = sessionWifiSsid ?: currentWifiSsid(context)
        val network = boundNetwork
        val refreshed = advertiser?.nudgeWhileWaiting(reason, ssid, network) == true
        val stamped = InviteWakeNudge.rememberNudge(refreshed, now, anchor)
        if (stamped != anchor) {
            if (handoffToWifi) lastHandoffInviteNudgeAt = stamped else lastConnectInviteNudgeAt = stamped
        }
        val armed = InviteWakeNudge.armDiscoverable(handoffToWifi) &&
            ReconnectDiscoverability.armForWake()
        log("INVITE-WAKE: $reason；刷新ctrl=${if (refreshed) "是" else "否"} 可发现再开=${if (armed) "是" else "否"}；" +
            "继续等车机 Wi-Fi ctrl 邀请（宝马二次上车蓝牙短窗口后常退场，不触发主动建链）")
        val waitingForCarIap2 = KnownWifiWaitCopy.awaitingHeadUnitIap2(
            wifiReceiverMac != null && boundNetwork != null,
            iap2?.sessionStartCount ?: 0,
            ReconnectDiscoverability.firstWindowOver(),
        )
        push(link = LinkState.Negotiating(
            when {
                handoffToWifi && waitingForCarIap2 -> KnownWifiWaitCopy.HERO
                handoffToWifi -> "蓝牙已交棒，等待车机 Wi-Fi 邀请"
                else -> "车机已回连，等待 CarPlay 邀请"
            }))
    }

    private fun requestConnect(context: Context, manual: Boolean, source: String) {
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
                    softRestartInvitationWait(context)
                    return@launch
                }
                if (AutomaticConnectionPolicy.mayStart(manual, SettingsStore.autoConnect, automaticPauseRemaining > 0)) beginConnect(context, source)
            } finally { autoConnectChanges.unlock() }
        }
    }

    /**
     * Invite-wait rounds keep [sessionLive] true, so a normal CONNECT was ignored and the car
     * tap/ACL path could not open a fresh attempt. Manual retry ends the wait without the
     * ten-minute pause, then starts a new attempt with listening restored.
     */
    private suspend fun softRestartInvitationWait(context: Context) {
        val attempt = connectionAttemptId
        log("CONNECT: 等待邀请中手动重试——结束 attempt=$attempt 后重新开始，不创建10分钟暂停")
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
            beginConnect(context, "manual-retry")
        }
    }

    private fun beginConnect(context: Context, source: String) {
        init(context)
        if (sessionLive.get() || connecting || streaming) {
            log("CONNECT: 已在连接/已连接，忽略重复触发")
            return
        }
        // 手动/自动发起连接都算"用户想要连"，清掉断开标记与重连倒计时
        userDisconnectRequested = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectBudget.start(retryReceiver(), manual = source == "manual")
        sessionHadLink = false
        sessionCarWithMode = SettingsStore.carWithMode
        hudSessionEnabled = true
        io.github.hypercar.navigation.NavigationSources.clear()
        sessionIdentity = null
        sessionReceiverMac = null
        lateAirplayEndpoint.set(null)
        airplayHost = null
        airplayPort = 0
        connecting = true
        sessionLive.set(true)
        lastConnectInviteNudgeAt = 0L
        lastHandoffInviteNudgeAt = 0L
        io.github.hypercar.carui.MusicNotificationListener.sync(context)
        val profile = headUnitProfile
        connectionAttemptId = connectionDiagnostics.begin(source,
            "${profile.id} remote=${io.github.hypercar.headunit.RemoteProfiles.isRemote(profile.id)} " +
                "activation=${profile.requireAirplayActivation} outbound=${profile.allowOutboundIap2} " +
                "profileGuard=${profile.dropProfilesBeforeIap2}")
        log(connectionDiagnostics.report().last())
        updateCarProfileGuard(context)
        runCatching { context.startForegroundService(HyperCarService.sessionIntent(context)) }
            .onFailure { log("SERVICE: 会话保活启动失败：${it.message}") }
        // 阶段计时基准：之后每条 CONNECT 日志都带 +Nms，一眼看出卡在哪一步
        sessionStageStart = System.currentTimeMillis()
        push(link = LinkState.Negotiating("检查 Shizuku"))
        connectJob?.cancel()
        val attempt = connectionAttemptId
        connectJob = scope.launch { runConnect(context.applicationContext, attempt) }
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

    /** 切换目标立即更新后续入站过滤；只取消尚未建立会话的出站拨号。 */
    fun updateTargetCar(mac: String?, name: String?) = synchronized(targetCarChanges) {
        val target = mac?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(java.util.Locale.ROOT)
        if (!target.orEmpty().equals(SettingsStore.targetCarMac.orEmpty(), ignoreCase = true)) {
            SettingsStore.lastAirplayEndpoint = "" // Legacy endpoint had no receiver association.
        }
        CarProfileGuard.stop()
        SettingsStore.setTargetCar(target, name?.takeIf { target != null && it.isNotBlank() })
        CarProfileGuard.noteCar(null)
        aclCarMac = null
        standbyEndpoint.set(null)
        iap2?.onTargetChanged()
        appContext()?.let { startAutomaticListening(it) }
        io.github.hypercar.settings.SettingsRevision.bump()
        log("TARGET: 用户目标=${target ?: "自动"}，已清除旧 ACL 候选；入站过滤立即生效，已建立会话保持")
    }

    fun disconnect(expectedAttempt: Long) = disconnect(userInitiated = true, expectedAttempt = expectedAttempt)

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
        reason: String = if (userInitiated) "用户断开当前会话" else "车机 ACL 断开",
    ) {
        val attempt = expectedAttempt
        scope.launch {
            autoConnectChanges.lock()
            try {
                val decision = ConnectionStopPolicy.decide(userInitiated, attempt, connectionAttemptId,
                    hasCurrentSession, _snapshots.value.link, SettingsStore.autoConnect)
                if (decision == ConnectionStopPolicy.Decision.IGNORE_STALE ||
                    decision == ConnectionStopPolicy.Decision.IGNORE_INACTIVE) {
                    log("SESSION-CONTROL: 忽略停止请求 decision=$decision user=$userInitiated expected=$attempt current=$connectionAttemptId state=${_snapshots.value.link.javaClass.simpleName}")
                    return@launch
                }
                if (decision == ConnectionStopPolicy.Decision.STOP_AND_PAUSE) {
                    reconnectBudget.clear()
                    appContext()?.let { io.github.hypercar.autowake.AutomaticConnectionResume.pause(it) }
                    io.github.hypercar.settings.SettingsRevision.bump()
                    iap2?.pauseSessions()
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

    /**
     * 待机通知开关：关掉后立刻撤掉「正在监听车机」的前台通知（会话占用前台时不动），
     * 打开后若正在待机则补回前台保活。
     */
    fun setStandbyNotification(context: Context, enabled: Boolean) {
        SettingsStore.standbyNotification = enabled
        io.github.hypercar.settings.SettingsRevision.bump()
        if (enabled) {
            if (automaticConnectionAllowed && !hasCurrentSession) {
                runCatching { context.startForegroundService(HyperCarService.listenIntent(context)) }
                    .onFailure { log("SERVICE: 待机通知恢复失败：${it.message}") }
            }
        } else if (!hasCurrentSession) {
            // 没有会话要保活：直接停前台服务，监听本身仍在进程内跑
            runCatching { context.stopService(android.content.Intent(context, HyperCarService::class.java)) }
                .onFailure { log("SERVICE: 待机通知取消失败：${it.message}") }
        }
        log("AUTO: 待机通知${if (enabled) "已开启" else "已关闭"}")
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
                    val issues = StartupRequirements.read(context).automaticIssues
                    if (issues.isNotEmpty()) {
                        SettingsStore.autoConnect = false
                        io.github.hypercar.settings.SettingsRevision.bump()
                        log("STARTUP: 启动条件未满足，已关闭自动上车连接：${issues.joinToString("；")}")
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
    private val standbyEndpoint = AtomicReference<CarPlayEndpoint?>(null)

    private var coordinatorProfile: io.github.hypercar.headunit.HeadUnitProfile? = null

    private fun coordinatorFor(context: Context): Iap2Coordinator {
        val profile = headUnitProfile
        val existing = iap2
        if (existing != null && coordinatorProfile == profile) return existing
        existing?.stop()
        standbyEndpoint.set(null)
        coordinatorProfile = profile
        return Iap2Coordinator(
            context,
            targetMac = { SettingsStore.targetCarMac },
            preferredMac = { aclCarMac },
            deviceMacProvider = { BluetoothMac.resolveOrStable(force = true) },
            dropProfilesBeforeIap2 = profile.dropProfilesBeforeIap2,
            allowOutboundIap2 = profile.allowOutboundIap2,
            log = ::log,
        ).also { iap2 = it }
    }

    private fun startStandbyListening() {
        if (!automaticConnectionAllowed || hasCurrentSession) return
        appContext()?.let { io.github.hypercar.carui.MusicNotificationListener.sync(it) }
        val context = appContext() ?: return
        if (!StartupRequirements.read(context).automaticReady) {
            startAutomaticListening(context)
            return
        }
        startWifiReconnectMonitor(context)
        val coordinator = coordinatorFor(context)
        coordinator.start { endpoint ->
            standbyEndpoint.updateAndGet { mergeEndpoint(it, endpoint) }
            if (SettingsStore.autoConnect) connectAutomatically(context, "standby-iap2")
        }
        if (coordinator.isRunning) {
            // 「待机通知」关掉时不进前台服务：通知栏干净，代价是进程更容易被回收
            // （车机 ACL 广播仍会尝试拉起 App，见清单里的 CarBluetoothReceiver）。
            if (SettingsStore.standbyNotification) {
                runCatching { context.startForegroundService(HyperCarService.listenIntent(context)) }
                    .onFailure { log("SERVICE: 后台监听保活启动失败：${it.message}") }
            } else {
                runCatching { context.startService(HyperCarService.listenIntent(context)) }
                    .onFailure { log("SERVICE: 后台监听启动失败：${it.message}") }
                log("AUTO: 待机通知已关闭——只做进程内监听，不发前台通知")
            }
            log("AUTO: 待机监听已启用，等待车机；尚未发起 AirPlay 会话")
            updateCarProfileGuard(context)
        }
    }

    private val keepIdleIdentity: Boolean get() = AutomaticConnectionPolicy.keepIdentityAfterStop(
        SettingsStore.autoConnect, automaticPauseRemaining > 0)

    /** Keep the same SDP/SCN, but never run a handshake or outbound probe during cooldown. */
    private fun startIdentityOnly() {
        if (!keepIdleIdentity || hasCurrentSession) return
        val context = appContext() ?: return
        stopWifiReconnectMonitor()
        reconnectJob?.cancel()
        reconnectJob = null
        CarProfileGuard.stop()
        standbyEndpoint.set(null)
        val coordinator = coordinatorFor(context)
        coordinator.keepIdentityOnly()
        io.github.hypercar.carui.MusicNotificationListener.sync(context)
        if (coordinator.isRunning) {
            if (SettingsStore.standbyNotification) {
                runCatching { context.startForegroundService(HyperCarService.identityIntent(context)) }
                    .onFailure { log("SERVICE: 身份声明保活启动失败 ${it.message}") }
            } else {
                runCatching { context.startService(HyperCarService.identityIntent(context)) }
                    .onFailure { log("SERVICE: 身份声明保活启动失败 ${it.message}") }
            }
            log("AUTO: 休眠中仅保留 iPhone/CarPlay 服务声明，不扫描、不拨号、不进行 iAP2 握手")
        }
    }

    private fun updateCarProfileGuard(context: Context) {
        if (io.github.hypercar.bluetooth.CarProfilePolicy.shouldGuard(
                headUnitProfile.dropProfilesBeforeIap2, automaticConnectionAllowed, hasCurrentSession,
            )) CarProfileGuard.start(context, ::log)
        else CarProfileGuard.stop()
    }

    fun setBluetoothCarPlayMarker(enabled: Boolean) {
        SettingsStore.bluetoothCarPlayMarker = enabled
        iap2?.refreshCarPlayMarker()
        log("BT-MARKER: 额外 CarPlay 身份广播=${if (enabled) "开" else "关"}，保留主 iAP2 服务")
    }

    private fun stopStandbyListening() {
        appContext()?.let { updateCarProfileGuard(it) }
        stopWifiReconnectMonitor()
        if (hasCurrentSession) return
        reconnectJob?.cancel()
        reconnectJob = null
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
            SettingsStore.headUnitProfileId,
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
        val exact = networks.filter { network ->
            val info = cm.getNetworkCapabilities(network)?.transportInfo as? android.net.wifi.WifiInfo
            info?.ssid?.trim('"') == ssid
        }
        // Some ROMs redact WifiInfo; one Wi-Fi handle is unambiguous. Never choose an arbitrary
        // network when concurrent station connections are present and SSID information is hidden.
        return exact.singleOrNull() ?: networks.singleOrNull()
    }

    /** VW may withhold 0x5703 until initial AirPlay pairing; advertise only for an explicitly selected peer. */
    private suspend fun prepareInvitationNetwork(context: Context, advertiser: CarPlayCtrlAdvertiser) {
        val attempt = connectionAttemptId
        val profile = headUnitProfile
        if (!profile.advertiseOnCurrentWifi) { resumeKnownCarNetwork(context, advertiser); return }
        val target = SettingsStore.targetCarMac
        if (!HeadUnitActivationPolicy.canAdvertiseCurrentWifi(profile, target, iap2?.activePeerAddress)) return
        val ssid = currentWifiSsid(context) ?: return
        val network = knownWifiNetwork(context, ssid) ?: return
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!sessionLive.get() || connectionAttemptId != attempt || ctrlAdvertiser !== advertiser) return
            val changed = boundNetwork != network || joinedSsid != ssid || wifiReceiverMac != target
            if (changed) {
                val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
                if (!cm.bindProcessToNetwork(network)) {
                    log("WIFI-INVITE: 当前 Wi-Fi 绑定失败，继续等车机热点/邀请")
                    return
                }
                wifiReceiverMac = target
                sessionWifiSsid = ssid
                boundNetwork = network
                joinedNetwork = network
                joinedSsid = ssid
                watchSessionNetwork(context, network)
                log("WIFI-INVITE: profile=${profile.id} target=$target network=#$network；先广播等邀请，不推断已配对或主动直连")
            }
            advertiser.ensureForNetwork(ssid, force = changed, network = network)
        } finally { autoConnectChanges.unlock() }
    }

    private fun usableCtrlInvitation(advertiser: CarPlayCtrlAdvertiser): CarPlayCtrlAdvertiser.Invitation? {
        val invite = advertiser.lastInvitation ?: return null
        val now = SystemClock.elapsedRealtime()
        if (HeadUnitActivationPolicy.acceptsInvitation(headUnitProfile, invite.receivedAtMillis, now,
                boundNetwork != null && invite.network == boundNetwork)) return invite
        if (advertiser.discardInvitation(invite)) {
            log("CTRL: 忽略过期/跨网络邀请 profile=${headUnitProfile.id} age=${now - invite.receivedAtMillis}ms inviteNetwork=${invite.network} currentNetwork=$boundNetwork，继续广播等本轮邀请")
        }
        return null
    }

    private suspend fun resumeKnownCarNetwork(context: Context, advertiser: CarPlayCtrlAdvertiser) {
        val attempt = connectionAttemptId
        val ssid = currentWifiSsid(context) ?: return
        val mac = knownWifiReceiver(ssid) ?: return
        val network = knownWifiNetwork(context, ssid) ?: return
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!sessionLive.get() || connectionAttemptId != attempt || ctrlAdvertiser !== advertiser) return
            val changed = boundNetwork != network || joinedSsid != ssid || wifiReceiverMac != mac
            if (changed) {
                val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
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
            // 同一网络不会反复重注册；上次注册失败则允许重试。
            advertiser.ensureForNetwork(ssid, force = changed, network = network)
        } finally { autoConnectChanges.unlock() }
    }

    private fun watchSessionNetwork(context: Context, expected: Network) {
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
                val state = "if=${properties.interfaceName} addresses=${properties.linkAddresses} routes=${properties.routes}"
                if (state != lastLink) { lastLink = state; log("NET-WATCH: network=#$network $state") }
            }
            override fun onLost(network: Network) {
                if (network != expected || sessionNetworkMonitor !== this) return
                log("NET-WATCH: network=#$network lost；终止本轮网络，不切到其他网络")
                val owner = this
                scope.launch {
                    autoConnectChanges.lock()
                    try {
                        if (sessionNetworkMonitor === owner && boundNetwork == network) {
                            sessionSockets?.takeIf { it.network == expected }?.invalidate()
                            val pending = connectJob
                            pending?.cancel()
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
            if (knownWifiReceiver(ssid) == null || ssid == null || knownWifiNetwork(context, ssid) == null) return@launch
            val receiver = knownWifiReceiver(ssid)?.let { PairingEvidence.receiverKey(it) }?.let { "${headUnitProfile.id}:$it" }
            if (reconnectJob?.isActive == true || !reconnectBudget.mayWake(receiver)) return@launch
            log("WIFI-RESUME: 已接入已配对车机网络，恢复 ctrl 广播与监听，按车机激活状态建立 AirPlay")
            connectAutomatically(context, "known-wifi")
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

    private suspend fun runConnect(context: Context, diagnosticId: Long) {
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
        try {
            // Shizuku 从「必须」降级为「可选增强」：没有它照样能连，只是两条路径降级——
            //  WiFi 走 WifiNetworkSpecifier（热点首连弹一次系统确认框）+ deviceID 用稳定伪 MAC。
            if (!ShizukuTools.isReady) {
                // 降级模式预检：权限缺失/定位服务关闭时提前失败并给出可操作原因，
                // 不要等 requestNetwork 静默 onUnavailable 才让用户一头雾水。
                if (!precheckDegradedMode(context)) return
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
            val realMac = BluetoothMac.resolve(force = true)
            val deviceMac = realMac ?: BluetoothMac.resolveOrStable(force = true)
            sessionDeviceMac = deviceMac
            if (realMac == null) {
                log(
                    "BT: ⚠ 未解析到真实蓝牙 MAC，本次用伪 ID $deviceMac——" +
                        "按 deviceID 过滤的接收端（WinPlay / 部分车机）会直接 403；" +
                        "装 Shizuku 或到设置里「手动指定蓝牙 MAC」填真实地址即可",
                )
            } else {
                log("BT: 使用真实蓝牙 MAC $deviceMac")
            }

            // ---- iAP2 协商（蓝牙侧）----
            push(link = LinkState.Negotiating("等待车机蓝牙 iAP2 协商"))
            // 档案自述：现场日志第一眼就能看出「用的是哪份车机档案、几个关键开关是什么」，
            // 省得再靠日志反推（2026-10-25 图标回归就是被档案里三个布尔左右的）。
            val p = headUnitProfile
            log(
                "PROFILE: ${p.id}（${p.label}）0x4300=${p.sendCarPlayAvailability} " +
                    "EA=${p.openEaSessions} 0x4E0E=${p.sendTransportIdentifier} " +
                    "断HFP/A2DP=${p.dropProfilesBeforeIap2} 主动拨号=${p.allowOutboundIap2} 档案A退场=${p.abortWhenCarPlayWindowClosed} " +
                    "主动WiFi=${HeadUnitActivationPolicy.permitsProactive(p)} 当前WiFi广播=${p.advertiseOnCurrentWifi} 新鲜邀请=${p.requireFreshCtrlInvite}"
            )
            val coordinator = coordinatorFor(context)

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
            val advertiser = CarPlayCtrlAdvertiser(context, { deviceMac }, ::log, onDiagnostic = { event ->
                connectionDiagnostics.mark(diagnosticId, when (event) {
                    CarPlayCtrlAdvertiser.Event.REGISTERED -> ConnectionDiagnostics.Stage.CTRL_REGISTERED
                    CarPlayCtrlAdvertiser.Event.INVITED -> ConnectionDiagnostics.Stage.CTRL_INVITED
                })
            })
            ctrlAdvertiser = advertiser
            val lastSeen = AtomicReference<CarPlayEndpoint?>(null)
            val baselineSessions = coordinator.sessionStartCount
            discoveryJob = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                try { io.github.hypercar.bluetooth.ReconnectDiscoverability.watch(context,
                    networkKey = {
                        val mac = wifiReceiverMac
                        val network = boundNetwork
                        val ssid = sessionWifiSsid
                        val target = SettingsStore.targetCarMac
                        if (network != null && mac != null && ssid != null &&
                            (target.isNullOrBlank() || target.equals(mac, ignoreCase = true))) "$network|$ssid|$mac" else null
                    },
                    servicesReady = { coordinator.isListening && advertiser.isAdvertising },
                    sessionStarted = { coordinator.isSessionActive || coordinator.sessionStartCount > baselineSessions ||
                        advertiser.lastInvitePeerIp != null || streaming },
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
            var invitationOnly = false
            val knownWifiRetry = KnownWifiActivationRetry()
            var retryEndpoint: CarPlayEndpoint? = null
            suspend fun waitForInvitation(reason: String, failedEndpoint: CarPlayEndpoint?, activationClosed: Boolean = false) {
                // Snapshot before the suspending network checks, so a later wake is not
                // accidentally consumed as part of the old failure's baseline.
                val failedAt = SystemClock.elapsedRealtime()
                val failedNetwork = boundNetwork?.toString()
                val failedReceiver = wifiReceiverMac
                val failedWake = activationWakeEvidence.snapshot(failedReceiver)?.connectedRevision ?: 0L
                invitationOnly = true
                awaitingCarInvitation = true
                sessionSockets?.invalidate() // Retire only the failed engine's socket factory, not Wi-Fi.
                lateAirplayEndpoint.set(null)
                standbyEndpoint.set(null)
                // Bridge the cleanup -> awaitUsableEndpoint callback handover. A fresh
                // 0x4301 arriving while nativeStop joins must not disappear into a closed channel.
                coordinator.start { endpoint ->
                    if (connectionAttemptId == diagnosticId && sessionLive.get() && iap2 === coordinator)
                        standbyEndpoint.set(endpoint)
                }
                lastSeen.set(null)
                airplayHost = null
                airplayPort = 0
                // A temporary eligibility loss must not replenish the same scope's budget.
                val scheduledDelay = knownWifiRetry.failed(failedNetwork, failedReceiver, failedAt, activationClosed, failedWake)
                val retryEligible = proactiveWifiSessionAllowed(context, SettingsStore.proactiveWifiSession)
                val retryDelay = scheduledDelay.takeIf { retryEligible }
                // Keep only a scoped hint. It remains gated by ready() until a fresh wake
                // actually reserves recovery; no TCP probe or full subnet rescan while blocked.
                retryEndpoint = failedEndpoint.takeIf { scheduledDelay != null || activationClosed }
                connectionDiagnostics.mark(diagnosticId,
                    if (retryDelay != null) ConnectionDiagnostics.Stage.WAITING_PROACTIVE_RETRY
                    else ConnectionDiagnostics.Stage.WAITING_INVITATION, reason)
                connectionDiagnostics.report().lastOrNull()?.let { log(it) }
                if (retryDelay != null) {
                    log("ACTIVATION: 主动建链失败：$reason；保留身份/Wi-Fi，已配对车机网络将在至少 ${retryDelay / 1000}s 后重试，车机邀请仍优先")
                    push(link = LinkState.Negotiating("车机 Wi-Fi 恢复未完成，稍后自动重试"))
                } else if (activationClosed && retryEligible) {
                    log("ACTIVATION: RECORD 后、资源确认前关闭：$reason；等待新邀请或同车新 ACL 状态；距失败至少30s、状态安静10s后最多恢复${KnownWifiActivationRetry.MAX_STATE_RECOVERIES}次，计入${KnownWifiActivationRetry.MAX_RETRIES}次总重试；未收到明确拒绝码")
                    push(link = LinkState.Negotiating("车机尚未激活，等待邀请或状态变化"))
                } else {
                    log("ACTIVATION: 主动建链失败：$reason；当前不满足已配对车机 Wi-Fi 条件，保留身份与广播，等待车机邀请")
                    push(link = LinkState.Negotiating("主动连接未获接受，等待车机邀请"))
                }
            }
            activation@ while (sessionLive.get()) {
                var endpoint: CarPlayEndpoint? = null
                var waitRounds = 0
                while (endpoint == null) {
                    endpoint =
                        awaitUsableEndpoint(context, coordinator, lastSeen, advertiser, invitationOnly, knownWifiRetry, retryEndpoint)
                            ?: if (invitationOnly) null else gatewayFallbackEndpoint(context, lastSeen.get())
                    if (endpoint == null) {
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

                // ---- 静默入车机热点 + 绑网 ----
                push(link = LinkState.Negotiating("静默加入车机热点"))
                val host = pickHost(endpoint)
                if (host == null) {
                    // awaitUsableEndpoint 已保证有 IP，这里只是防御
                    fail("端点无可用 IP（0x5703 有凭据、0x4301 未给出地址）")
                    return
                }
                // 端点定谳：把「凭什么地址」摊开写清楚（真车排查全靠这一行）
                log(
                    "CONNECT +${stageMs()}ms 端点确定：$host:${endpoint.airPlayPort}" +
                        " ssid=${endpoint.ssid ?: "-"}" +
                        " 有密码=${!endpoint.passphrase.isNullOrBlank()}" +
                        " ips=${endpoint.ipAddresses}" +
                        " gateway=${endpoint.gateway ?: "-"}" +
                        " devId=${endpoint.deviceIdentifier ?: "-"}" +
                        " srcvers=${endpoint.sourceVersion ?: "-"}",
                )
                val network = joinAndBind(context, endpoint)
                if (network != null) connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.WIFI_BOUND)
                if (network == null) {
                    fail("车机热点入网失败：${wifiJoiner?.lastFailureReason ?: "没有匹配本车且可绑定的有效网络"}")
                    return
                }
                val sockets = SessionSocketLease(network)
                sessionSockets?.invalidate()
                sessionSockets = sockets
                watchSessionNetwork(context, network)
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
                val mdnsEndpoint = if (desktop || (headUnit.preferMdnsAirplay && !endpoint.fromInvite && !haveEndpoint)) {
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
                airplayAttempts = 0

                if (headUnitProfile.requireFreshCtrlInvite && usableCtrlInvitation(advertiser)?.host != apHost) {
                    waitForInvitation("车机邀请已过期或端点已改变，等待新邀请", null)
                    continue@activation
                }
                // ---- 连 AirPlay 控制端口并移交 FD ----
                push(link = LinkState.Negotiating("连接车机 AirPlay 端口"))
                val carNetworkSsid = currentWifiSsid(context)
                val connected = connectAirplayEndpoint(context, sockets, candidatePorts.map { AirplayTarget(apHost, it) })
                if (connected == null) {
                    if (endpoint.proactive) {
                        waitForInvitation("主动控制端口不可用；候选地址已失效", null)
                        continue@activation
                    }
                    fail("AirPlay 端点暂不可用；已失效旧缓存并重新发现，等待车机下一轮邀请")
                    return
                }
                val apHostFinal = connected.target.host
                val port = connected.target.port
                val controlFd = connected.socket
                val activationInvite = usableCtrlInvitation(advertiser)
                if (headUnitProfile.requireFreshCtrlInvite && activationInvite?.host != apHostFinal) {
                    runCatching { ParcelFileDescriptor.adoptFd(controlFd).close() }
                    waitForInvitation("连接期间车机邀请失效，已关闭候选 socket，等待新邀请", null)
                    continue@activation
                }
                val activation = if (HeadUnitActivationPolicy.needsFallbackGate(headUnitProfile, endpoint.proactive)) ProactiveActivation() else null
                val started = startNativeForSession(controlFd, sockets, diagnosticId) {
                    airplayHost = apHostFinal
                    airplayPort = port
                    rememberAirplayEndpoint(carNetworkSsid, connected.target)
                    // ---- 启动 Rust 引擎（协商在 Rust 内继续）----
                    push(link = LinkState.Negotiating("Rust 核心层协商 AirPlay 会话"))
                    // 画面与音频由本机自绘界面自采自编，经 nativePushVideoFrame /
                    // nativePushAudioPcm 投喂给引擎；这里只负责下发会话参数。
                    sessionWifiSsid = carNetworkSsid
                    val receiver = receiverForEngine()
                    val identity = PairingIdentityFiles.location(context.filesDir, receiver)
                    ensurePairingIdentity(identity)
                    sessionIdentity = identity
                    val identityPath = identity.file.absolutePath
                    val json = SettingsStore.engineConfigJson(apHostFinal, port, deviceMac, identityPath,
                        receiverBluetoothMac = receiver, carWithSource = sessionCarWithMode)
                    // 下发配置原样打一行（不含密钥）：分辨率/帧率一目了然，
                    // 免得再靠猜「这次到底请求了多大尺寸」
                    log("SESSION-ORIGIN: ctrlInvite=${advertiser.lastInvitation != null} endpointFromInvite=${endpoint.fromInvite} " +
                        "bluetoothSession=${coordinator.isSessionActive} knownWifi=${wifiReceiverMac != null} network=$boundNetwork")
                    log("CORE +${stageMs()}ms 引擎配置：$json")
                    // 前后各记一行：原生崩溃（Rust panic 越过 JNI → SIGABRT）会直接把进程带走，
                    // 这两行能区分「崩在 nativeStart 里」还是「崩在引擎跑起来之后」。
                    log("CORE: 调用 nativeStart（$apHostFinal:$port fd=$controlFd）")
                    connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.AIRPLAY_START)
                    proactiveActivation.set(activation)
                    json
                }
                log("CORE: nativeStart 返回 $started")
                if (!started) {
                    fail("核心层启动失败（查看日志）")
                    return
                }
                // 拆除与新连接的竞争兜底：拆除已经把资源收走时（sessionLive=false）别再挂
                // streaming，并把刚起的引擎停掉——否则会留下没人管的僵尸会话。
                if (!sessionLive.get()) {
                    runCatching { RustCoreBridge.nativeStop() }
                    log("SESSION: 连接结果已过期（拆除途中），丢弃本次会话")
                    return
                }
                streaming = true
                if (activation == null) break@activation
                val rejected = activation.result.await()
                if (rejected == null) {
                    proactiveActivation.compareAndSet(activation, null)
                    break@activation
                }
                // Stop only the failed media engine. Keep the same identity, Wi-Fi lease,
                // RFCOMM session and ctrl listener/port alive for the receiver's next invite.
                streaming = false
                sessionUiJob?.cancel()
                sessionUiJob = null
                CarUiCaster.stopAsync()
                MusicBridge.onCarDisconnected()
                // A failed VW activation must wait for another invitation, not reuse the
                // consumed one. CAS preserves an invite that arrived during negotiation.
                if (headUnitProfile.requireFreshCtrlInvite && activationInvite != null)
                    advertiser.discardInvitation(activationInvite)
                waitForInvitation(rejected, endpoint.copy(ipAddresses = listOf(apHostFinal),
                    airPlayPort = port, gateway = apHostFinal),
                    activationClosed = endpoint.proactive && !endpoint.fromInvite && activation.requiresFreshInvitation)
                RustCoreBridge.nativeStop()
                proactiveActivation.compareAndSet(activation, null)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!sessionLive.get() || connectionAttemptId != diagnosticId) return
            }

        } catch (cancelled: CancellationException) {
            // disconnect() 掐掉在途连接：正常取消，不算失败（也不要把 Failed 写进快照）
            throw cancelled
        } catch (t: Throwable) {
            fail("会话异常: ${t.message}")
        } finally {
            discoveryJob?.cancel()
            if (connectionAttemptId == diagnosticId) connecting = false
            appContext()?.let { io.github.hypercar.carui.MusicNotificationListener.sync(it) }
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
        // 幂等：重复的断开事件（ACL 断开广播 + 引擎 ended + service 转交）只真正拆一次。
        // 但**状态必须回 Idle**——曾经这里直接 return，导致链路已断、界面还显示"已连接"。
        if (!sessionLive.compareAndSet(true, false)) {
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

    private fun retryReceiver(): String? =
        (sessionReceiverMac ?: iap2?.activePeerAddress ?: wifiReceiverMac ?: aclCarMac ?: SettingsStore.targetCarMac)
            ?.let { PairingEvidence.receiverKey(it) }?.let { "${headUnitProfile.id}:$it" }

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
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            log("RECONNECT: $reason——${retry.delayMs}ms 后重试（${retry.number}/${ReconnectBudget.MAX_RETRIES}）")
            delay(retry.delayMs)
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
        hudSessionEnabled = false
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
        // 在解绑网络、结束蓝牙旧会话之前完成有界业务退出；断网/EOF 路径只强制清理。
        runCatching {
            if (graceful) log("SESSION-CLOSE: graceful completed=${RustCoreBridge.nativeStopGracefully()}")
            else RustCoreBridge.nativeStop()
        }
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
        runCatching { ctrlAdvertiser?.stop() }
        ctrlAdvertiser = null
        val cm = appContext()?.getSystemService(ConnectivityManager::class.java)
        sessionNetworkMonitor?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        sessionNetworkMonitor = null
        runCatching { wifiJoiner?.release() }
        val previousNetwork = boundNetwork
        if (previousNetwork != null && cm?.boundNetworkForProcess == previousNetwork)
            runCatching { cm.bindProcessToNetwork(null) }
        wifiJoiner = null
        joinedSsid = null
        joinedNetwork = null
        boundNetwork = null
        wifiReceiverMac = null
        sessionReceiverMac = null
        sessionIdentity = null
        sessionWifiSsid = null
        appContext()?.let { io.github.hypercar.carui.MusicNotificationListener.sync(it) }
    }

    // ---------------------------------------------------------------- WiFi

    /** Recheck the live OS link; a remembered handle or a shared SSID alone is insufficient. */
    private suspend fun reusableCarNetwork(context: Context, endpoint: CarPlayEndpoint): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = wifiNetworks(context)
        val fallbackSsid = if (networks.size == 1) currentWifiSsid(context) else null
        val fallbackInfo = if (networks.size == 1) runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo
        }.getOrNull() else null
        var fallbackBssid = fallbackInfo?.bssid
        if (networks.size == 1 && io.github.hypercar.net.CarNetworkReuse.mac(endpoint.deviceIdentifier) != null &&
            io.github.hypercar.net.CarNetworkReuse.mac(fallbackBssid) == null && ShizukuTools.isReady) {
            fallbackBssid = io.github.hypercar.net.CarNetworkReuse.readBssid {
                ShizukuTools.exec("cmd wifi status", 3_000).out
            }
            if (fallbackBssid == null) log("WIFI-REUSE: 未取得可靠 BSSID，继续正常入网，不中断 iAP2")
        }
        val peers = (endpoint.ipAddresses + listOfNotNull(endpoint.gateway)).mapNotNull {
            runCatching { android.net.InetAddresses.parseNumericAddress(it.substringBefore('%')) }.getOrNull()
        }
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
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                io.github.hypercar.net.CarNetworkReuse.accepts(endpoint.ssid, endpoint.deviceIdentifier, endpoint.fromInvite,
                    io.github.hypercar.net.CarNetworkReuse.Evidence(ssid, io.github.hypercar.net.CarNetworkReuse.mac(info?.bssid) ?: fallbackBssid,
                        hasAddress, route, network == boundNetwork || network == joinedNetwork))
        }
        return eligible.firstOrNull { it == boundNetwork } ?: eligible.singleOrNull()
    }

    private suspend fun joinAndBind(context: Context, endpoint: CarPlayEndpoint): Network? {
        val attempt = connectionAttemptId
        fun ensureCurrent() {
            if (!sessionLive.get() || connectionAttemptId != attempt) throw CancellationException("旧车机入网结果已失效")
        }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val reuse = reusableCarNetwork(context, endpoint)
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (reuse != null && cm.getNetworkCapabilities(reuse)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(reuse)?.linkAddresses?.isNotEmpty() == true) {
                // Keep a specifier request owning this network alive; retire callbacks for other links.
                if (wifiJoiner?.ownsBinding(reuse) == false) { wifiJoiner?.release(); wifiJoiner = null }
                if (cm.boundNetworkForProcess != reuse && !cm.bindProcessToNetwork(reuse)) return null
                boundNetwork = reuse
                joinedNetwork = reuse
                joinedSsid = endpoint.ssid
                watchSessionNetwork(context, reuse)
                log("WIFI-REUSE: attempt=$attempt 已核实网络 #$reuse，复用现有车机 Wi-Fi，不重新入网")
                return reuse
            }
            wifiJoiner?.release()
            wifiJoiner = null
        } finally { autoConnectChanges.unlock() }
        lateinit var joiner: WifiJoiner
        joiner = WifiJoiner(context) {
            scope.launch {
                if (connectionAttemptId == attempt && wifiJoiner === joiner && sessionLive.get()) {
                    log("WIFI: 当前车机热点消失（熄火/离开）")
                    disconnect(userInitiated = false, expectedAttempt = attempt, reason = "热点消失")
                }
            }
        }
        autoConnectChanges.lock()
        try { ensureCurrent(); wifiJoiner = joiner } finally { autoConnectChanges.unlock() }
        val ssid = endpoint.ssid
        val psk = endpoint.passphrase
        val network = if (!ssid.isNullOrBlank() && !psk.isNullOrBlank()) {
            val shellJoin = ShizukuTools.isReady
            push(link = LinkState.Negotiating(if (shellJoin) "加入车机热点" else "连接车机 Wi-Fi，请确认系统连接提示"))
            log("WIFI: 加入热点 $ssid，方式=${if (shellJoin) "Shizuku" else "系统确认（最多60s）"}")
            when (val result = joiner.joinCarHotspotSilently(ssid, psk)) {
                is WifiJoiner.JoinResult.Joined -> { markDegradedFirstConnectIfNeeded(); result.network }
                else -> { log("WIFI: 入网未完成（${joiner.lastFailureReason ?: "不支持或连接失败"}），仅尝试已核实的当前网络"); null }
            }
        } else null
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        ensureCurrent()
        val effective = network ?: reusableCarNetwork(context, endpoint)
        autoConnectChanges.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (wifiJoiner !== joiner) throw CancellationException("网络租约已更换")
            if (effective == null || cm.getNetworkCapabilities(effective)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                !joiner.bindProcess(effective)) {
                joiner.release(); wifiJoiner = null
                log("WIFI: 没有匹配本车且可绑定的有效网络，停止本轮入网")
                return null
            }
            boundNetwork = effective
            joinedSsid = ssid
            joinedNetwork = effective
            watchSessionNetwork(context, effective)
            log("WIFI +${stageMs()}ms ✓ 已绑定车机网络：${describeNetwork(context, effective)}")
            return effective
        } finally { autoConnectChanges.unlock() }
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
        if (headUnitProfile.requireAirplayActivation) return null
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
    private suspend fun currentWifiSsid(context: Context): String? {
        val fromApi =
            runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                    ?.connectionInfo
                    ?.ssid
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
            }.getOrNull()
        if (fromApi != null) return fromApi
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
        if (!sessionLive.get() || boundNetwork != network || ctrlAdvertiser?.lastInvitePeerIp != null || lateAirplayEndpoint.get() != null) return null
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
            ctrlAdvertiser?.lastInvitePeerIp != null || lateAirplayEndpoint.get() != null
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
        for (network in boundNetwork?.let { listOf(it) } ?: wifiNetworks(context)) {
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

    private fun recordLateAirplayEndpoint(coordinator: Iap2Coordinator, attempt: Long, endpoint: CarPlayEndpoint) {
        if (attempt != connectionAttemptId || !sessionLive.get() || iap2 !== coordinator) return
        val host = pickHost(endpoint) ?: return
        if (endpoint.airPlayPort in 1..65535) {
            lateAirplayEndpoint.set(AirplayTarget(host, endpoint.airPlayPort))
        }
    }

    private fun invalidateRememberedEndpoint(ssid: String?, target: AirplayTarget) {
        val saved = SettingsStore.lastAirplayEndpoint
        val remembered = ProactiveWifiSessionPolicy.rememberedEndpoint(saved, ssid)
        if (remembered == (target.host to target.port) && SettingsStore.lastAirplayEndpoint == saved) {
            SettingsStore.lastAirplayEndpoint = ""
            log("ENDPOINT: 连接失败，失效当前车机缓存 $target；配对身份保留")
        }
    }

    private fun rememberAirplayEndpoint(ssid: String?, target: AirplayTarget) {
        if (target.host.contains('.') && !target.host.contains(':')) {
            SettingsStore.lastAirplayEndpoint = "$target|${ssid.orEmpty()}"
        }
    }

    /** Fresh discovery is bound to this exact session's Network; never falls back to cellular. */
    private fun matchesAirplayService(endpoint: AirplayServiceProbe.Endpoint): Boolean =
        io.github.hypercar.headunit.ReceiverCompatibility.matchesAirplayService(headUnitProfile.id,
            io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(iap2?.activePeerAddress), endpoint.instance, endpoint.model)

    private suspend fun connectAirplayEndpoint(
        context: Context,
        sockets: SessionSocketLease<Network>,
        initial: List<AirplayTarget>,
    ): AirplayEndpointConnector.Connected<Int>? {
        val attempt = connectionAttemptId
        val ssid = currentWifiSsid(context)
        val advertiser = ctrlAdvertiser
        val hints = AirplayEndpointHints(initial.firstOrNull()?.port ?: 7000)
        var tries = 0
        return AirplayEndpointConnector(
            current = { connectionAttemptId == attempt && sessionLive.get() &&
                sessionSockets === sockets && boundNetwork == sockets.network },
            now = { SystemClock.elapsedRealtime() },
            open = { target: AirplayTarget ->
                tries++
                openControlFd(context, sockets, target.host, target.port,
                    if (tries == 1) CONTROL_CONNECT_TIMEOUT_MILLIS.toInt() else AIRPLAY_CONNECT_RETRY_TIMEOUT_MILLIS.toInt())
            },
            close = { fd: Int -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() }; Unit },
            invalidate = { target -> invalidateRememberedEndpoint(ssid, target) },
            updates = {
                val late = lateAirplayEndpoint.getAndSet(null)
                val next = advertiser?.lastInvitation
                val newInvite = next?.let { hints.invitation(it, it.host) }
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
            discover = {
                log("ENDPOINT: 旧目标暂不可用，在网络 #${sockets.network} 重新查询 _airplay._tcp（最多 1500ms）")
                val fresh = AirplayServiceProbe(context, ::log).browse(sockets.network, 1500, strictNetwork = true, accept = ::matchesAirplayService)
                if (fresh != null) {
                    log("ENDPOINT: 重新发现 → ${fresh.host}:${fresh.port}（${fresh.instance}）")
                    listOf(hints.advertised(AirplayTarget(fresh.host, fresh.port)))
                } else {
                    log("ENDPOINT: 无新服务，保留 ctrl 广播与 iAP2，限时等待新端点/服务启动")
                    emptyList()
                }
            },
            pause = { delay(400) },
        ).connect(initial, AIRPLAY_CONNECT_WINDOW_MILLIS)
    }

    /** 本轮控制连接与后续媒体连接共用固定 Network，不轮询其他 Wi-Fi/默认网络。 */
    private suspend fun openControlFd(
        context: Context,
        sockets: SessionSocketLease<Network>,
        host: String,
        port: Int,
        timeoutMillis: Int = CONTROL_CONNECT_TIMEOUT_MILLIS.toInt(),
    ): Int? {
        var acquiredFd: Int? = null
        try {
            return withContext(Dispatchers.IO) {
                val startedAt = SystemClock.elapsedRealtime()
                runCatching {
                    openTcpFd(sockets, host, port, timeoutMillis).also { acquiredFd = it }
                }.onSuccess { fd ->
                    log("NET +${stageMs()}ms ✓ $host:$port 已连通（固定网络 #${sockets.network}，耗时 ${SystemClock.elapsedRealtime()-startedAt}ms，fd=$fd）")
                }.onFailure { error ->
                    log("NET: 控制 socket 失败 network=#${sockets.network} $host:$port ${error.javaClass.simpleName}: ${error.message}；${describeNetwork(context, sockets.network)}")
                }.getOrNull()
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


    /** 按 MAC 查已配对蓝牙设备的名称（缺 BLUETOOTH_CONNECT 时返回 null）。 */
    @android.annotation.SuppressLint("MissingPermission")
    private fun bondedNameOf(mac: String): String? = runCatching {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            ?.bondedDevices
            ?.firstOrNull { it.address.equals(mac, ignoreCase = true) }
            ?.name
    }.getOrNull()
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

    private fun openTcpFd(sockets: SessionSocketLease<Network>, host: String, port: Int, timeout: Int): Int =
        sockets.open(
            create = { Socket() },
            bind = { network, socket -> network.bindSocket(socket) },
            connect = {
                it.tcpNoDelay = true
                it.connect(resolveEndpoint(host, port, sockets.network), timeout.coerceAtLeast(1_000))
            },
            handoff = { socket ->
                val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()
                fd
            },
        )

    private fun socketProvider(sockets: SessionSocketLease<Network>) = object : RustCoreBridge.SocketProvider {
        override fun openTcpFd(host: String, port: Int, timeoutMs: Int): Int = runCatching {
            openTcpFd(sockets, host, port, timeoutMs)
        }.onFailure { socketFailure(sockets, "TCP", host, port, it) }.getOrElse { -1 }

        override fun openUdpFd(host: String, port: Int): Int = runCatching {
            sockets.open(
                create = { DatagramSocket(null as java.net.SocketAddress?) },
                bind = { network, socket -> network.bindSocket(socket) },
                connect = { it.connect(resolveEndpoint(host, port, sockets.network)) },
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
        override fun onPipeline(json: String) {
            val next = io.github.hypercar.core.PipelineDiagnostics.parse(json) ?: return
            if (!sessionLive.get() || proactiveActivation.get()?.rejected == true) return
            if (next.writtenFrames > 0 && next.videoChannelReady && next.recordAccepted &&
                next.videoStream == "READY" && next.generation > closedPipelineGeneration.get() &&
                next.generation >= _snapshots.value.pipeline.generation) {
                reconnectBudget.videoReady(retryReceiver(), SystemClock.elapsedRealtime())
                connectionDiagnostics.mark(connectionAttemptId, ConnectionDiagnostics.Stage.FIRST_VIDEO_FRAME)
            }
            _snapshots.update { s ->
                if ((s.link !is LinkState.Negotiating && s.link !is LinkState.Connected) ||
                    next.generation <= closedPipelineGeneration.get() || next.generation < s.pipeline.generation) s
                else s.copy(pipeline = next)
            }
        }
        override fun onState(state: String, detail: String) {
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
                    activation?.stage(stage)
                    connectionDiagnostics.mark(connectionAttemptId, stage)
                    log("ACTIVATION: attempt=$connectionAttemptId core=${_snapshots.value.pipeline.generation} stage=$stage")
                    if (stage == ConnectionDiagnostics.Stage.VIDEO_CHANNEL_READY) activation?.videoReady()
                }
                "negotiating" -> {
                    SettingsStore.receiverViewport = null
                    io.github.hypercar.carui.CarDisplayLayout.state.value = null
                    push(link = LinkState.Negotiating(detail))
                }
                "connected" -> {
                    sessionIdentity?.let { ensurePairingIdentity(it) }
                    val diagnosticId = connectionAttemptId
                    connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.CONTROL_READY)
                    connectionDiagnostics.report().lastOrNull()?.let { log(it) }
                    MusicBridge.onCarConnected()
                    streaming = true
                    sessionHadLink = true
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
                            val carName = bondedNameOf(mac)
                                ?: io.github.hypercar.iap2.Iap2Peer.name.takeIf {
                                    mac.equals(io.github.hypercar.iap2.Iap2Peer.address, true)
                                }
                                ?: SettingsStore.targetCarName
                                ?: "车机"
                            SettingsStore.markCarPaired(mac, carName)
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
                            carName = SettingsStore.targetCarName ?: "BMW ID8.5",
                            sinceMillis = System.currentTimeMillis(),
                        ),
                    )
                    // P1：AirPlay RECORD 完成 → 接管屏幕流。
                    // 两种来源（2026-11-03 设置里可切）：
                    //  · car_ui  —— 我们自绘车机首页（默认）
                    //  · carwith —— 拉起小米 CarWith 的 CarLife 入口，由它建显示/编码，
                    //               我们只做 CarLife 通道 → AirPlay 的转发（Rust 引擎会主动拨 7240）
                    val ctx = appContext()
                    if (ctx != null) {
                        sessionUiJob?.cancel()
                        sessionUiJob = scope.launch {
                            fun current() = sessionLive.get() && streaming && connectionAttemptId == diagnosticId
                            if (!current()) return@launch
                            if (sessionCarWithMode) {
                                CarUiCaster.releaseForCarWith()
                                if (!current()) return@launch
                                val r = launchCarWith(ctx)
                                log("CARWITH: $r")
                                return@launch
                            }
                            val result = runCatching { CarUiCaster.start(ctx, ::current) }
                                .getOrElse { "启动失败: ${it.javaClass.simpleName}: ${it.message}" }
                            log("CARUI: $result")
                            if (CarUiCaster.running) {
                                connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.UI_STARTED)
                                io.github.hypercar.navigation.AmapLinkProbe.start(ctx)
                            }
                        }
                    }
                }
                "failed" -> {
                    MusicBridge.onCarDisconnected()
                    retryAirplayOrFail(detail)
                }
                "ended" -> {
                    MusicBridge.onCarDisconnected()
                    disconnect(userInitiated = false, reason = detail)
                }
                "stopping" -> { /* runDisconnect 内处理 */ }
            }
        }

        override fun onLog(line: String) = log(line)

        override fun onForceKeyFrame() {
            io.github.hypercar.carui.CarUiCaster.requestKeyFrame()
        }

        override fun onDisplaySize(width: Int, height: Int) {
            SettingsStore.receiverViewport = (width to height).takeIf { width >= 320 && height >= 240 }
        }

        override fun onDisplayLayout(width: Int, height: Int, viewX: Int, viewY: Int, viewWidth: Int, viewHeight: Int,
                                     safeX: Int, safeY: Int, safeWidth: Int, safeHeight: Int, drawOutside: Boolean) {
            io.github.hypercar.carui.CarDisplayLayout.state.value = io.github.hypercar.carui.DisplayLayout(
                width, height,
                io.github.hypercar.carui.DisplayRect(viewX, viewY, viewWidth, viewHeight),
                io.github.hypercar.carui.DisplayRect(safeX, safeY, safeWidth, safeHeight), drawOutside,
            )
        }

        override fun onNightMode(night: Boolean) {
            io.github.hypercar.carui.CarUiSettings.onCarNight(night)
            log("CARUI: 车机日夜模式 night=$night")
        }

        override fun onMediaKey(usage: Int) {
            if (!sessionLive.get()) return
            appContext()?.let { MusicBridge.onCarMediaKey(it, usage) }
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

    private suspend fun proactiveWifiSessionAllowed(context: Context, enabled: Boolean): Boolean {
        if (!enabled || !HeadUnitActivationPolicy.permitsProactive(headUnitProfile)) return false
        val ssid = currentWifiSsid(context)
        val receiver = knownWifiReceiver(ssid)
        val network = ssid?.let { knownWifiNetwork(context, it) }
        return ProactiveWifiSessionPolicy.allows(enabled, receiver, iap2?.activePeerAddress,
            network != null && network == boundNetwork && receiver == wifiReceiverMac)
    }

    private suspend fun probeRememberedEndpoint(
        context: Context,
        coordinator: Iap2Coordinator,
        strictNetwork: Boolean,
    ): CarPlayEndpoint? {
        val diagnosticId = connectionAttemptId
        val currentSsid = currentWifiSsid(context)
        val saved = SettingsStore.lastAirplayEndpoint
        val desktop = io.github.hypercar.headunit.ReceiverCompatibility.isDesktopReceiver(headUnitProfile.id,
            io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(coordinator.activePeerAddress))
        val remembered = ProactiveWifiSessionPolicy.rememberedEndpoint(saved, currentSsid, desktop) ?: return null
        if (strictNetwork && knownWifiReceiver(currentSsid) == null) return null
        val (host, port) = remembered
        // Use the real control connection once, without a connect/disconnect preflight which
        // can make a single-controller receiver close its service before nativeStart.
        log("SESSION: 使用本车网络缓存 $host:$port 作为候选；实际连接失败后失效并重新发现")
        coordinator.start { endpoint ->
            recordLateAirplayEndpoint(coordinator, diagnosticId, endpoint)
        }
        return CarPlayEndpoint(
            ssid = currentSsid, passphrase = null, airPlayPort = port,
            deviceIdentifier = null, publicKeyHex = null, sourceVersion = null,
            ipAddresses = listOf(host), gateway = host, proactive = strictNetwork,
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
     * 用无界 Channel 收集回调而不是首个回调 resume：0x5703/0x4301 可能连续到达，一条都不能丢；
     * 等待结束（或超时）后 close，晚到的回调 trySend 失败即静默丢弃。
     */
    private suspend fun awaitUsableEndpoint(
        context: Context,
        coordinator: Iap2Coordinator,
        lastSeen: AtomicReference<CarPlayEndpoint?>,
        advertiser: CarPlayCtrlAdvertiser,
        invitationOnly: Boolean = false,
        knownWifiRetry: KnownWifiActivationRetry = KnownWifiActivationRetry(),
        retryEndpoint: CarPlayEndpoint? = null,
    ): CarPlayEndpoint? = withTimeoutOrNull(ENDPOINT_TIMEOUT_MILLIS) {
        val diagnosticId = connectionAttemptId
        // BMW's second-entry box trace still obtains Bluetooth credentials and a fresh ctrl invite.
        // Retain the network/identity shortcut, but never confuse a remembered/open port with activation.
        val profileRequiresActivation = headUnitProfile.requireAirplayActivation
        val proactiveEnabled = SettingsStore.proactiveWifiSession && HeadUnitActivationPolicy.permitsProactive(headUnitProfile)
        val proactiveDelay = ProactiveWifiSessionDelay()
        suspend fun proactiveReady(): Boolean {
            val eligible = proactiveWifiSessionAllowed(context, proactiveEnabled && SettingsStore.proactiveWifiSession)
            if (!sessionLive.get() || connectionAttemptId != diagnosticId || ctrlAdvertiser !== advertiser ||
                iap2 !== coordinator) return false
            val now = SystemClock.elapsedRealtime()
            val network = boundNetwork?.toString()
            val receiver = wifiReceiverMac
            val stable = proactiveDelay.ready(eligible, network, now)
            if (!stable) return false
            if (!invitationOnly) return true // the user's explicit manual start can bypass idle cooldown
            // Do not spend a recovery reservation on an invitation/endpoint already available.
            if (usableCtrlInvitation(advertiser) != null || standbyEndpoint.get() != null) return false
            val recovery = knownWifiRetry.recoverAfterStateChange(eligible, automaticPauseRemaining > 0,
                network, receiver, now, activationWakeEvidence.snapshot(receiver))
            if (recovery != null) {
                log("ACTIVATION-RECOVERY: attempt=$diagnosticId network=$network 新车机 ACL 状态已安静10s，距失败至少30s；恢复${recovery.number}/${KnownWifiActivationRetry.MAX_STATE_RECOVERIES}，总重试${recovery.totalRetries}/${KnownWifiActivationRetry.MAX_RETRIES}；车机邀请仍优先")
                connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.WAITING_PROACTIVE_RETRY,
                    "车机状态变化后有界恢复 ${recovery.number}/${KnownWifiActivationRetry.MAX_STATE_RECOVERIES}")
                push(link = LinkState.Negotiating("车机状态已稳定，恢复尝试 ${recovery.number}/${KnownWifiActivationRetry.MAX_STATE_RECOVERIES}"))
            }
            // A newly bound trusted network needs its own stable grace; never inherit an old
            // endpoint or backoff from another receiver. Unknown networks remain invite-only.
            return knownWifiRetry.allowsRetry(eligible, automaticPauseRemaining > 0, network, receiver, now)
        }
        prepareInvitationNetwork(context, advertiser)
        var requireActivation = (invitationOnly || profileRequiresActivation) && !proactiveReady()
        if (invitationOnly || profileRequiresActivation) awaitingCarInvitation = true
        log("WIFI-ACTIVE: 本轮开关=$proactiveEnabled 延时=${ProactiveWifiSessionPolicy.DELAY_MILLIS}ms 失败后已知网络恢复=$invitationOnly（需重新核实车机/网络及退避）；正常邀请优先，端口可连不代表车机已授权")
        if (requireActivation) {
            log("SESSION: 等待本轮 ctrl 邀请或 iAP2 端点；保持蓝牙监听与 Wi-Fi 广播，不预连记忆/扫描端口")
            push(link = LinkState.Negotiating("等待车机开启 CarPlay"))
        }
        if (!requireActivation) {
            val remembered = probeRememberedEndpoint(context, coordinator, profileRequiresActivation || invitationOnly)
            val candidate = remembered ?: retryEndpoint?.takeIf {
                invitationOnly && knownWifiRetry.ready(boundNetwork?.toString(), wifiReceiverMac,
                    SystemClock.elapsedRealtime())
            }
            if (candidate != null && advertiser.lastInvitePeerIp == null &&
                (!invitationOnly || proactiveReady())) {
                awaitingCarInvitation = false
                return@withTimeoutOrNull candidate
            }
        }

        val callbacks = Channel<CarPlayEndpoint>(Channel.UNLIMITED)
        coordinator.start { endpoint ->
            connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.IAP2_PARAMETERS,
                "wifiCredentials=${!endpoint.ssid.isNullOrBlank()} endpoint=${endpoint.ipAddresses.isNotEmpty()}")
            callbacks.trySend(endpoint)
            // 端口阶段（awaitUsableEndpoint 已返回）再来的 0x4301：记下来供端口循环热切换
            recordLateAirplayEndpoint(coordinator, diagnosticId, endpoint)
        }
        try {
            var usable: CarPlayEndpoint? = null
            var ticks = 0
            var partialKey: String? = null
            var wifiJoined = false
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
                val next = standbyEndpoint.getAndSet(null)
                    ?: withTimeoutOrNull(ENDPOINT_TICK_MILLIS) { callbacks.receive() }
                if (next != null) connectionDiagnostics.mark(diagnosticId, ConnectionDiagnostics.Stage.IAP2_PARAMETERS,
                    "wifiCredentials=${!next.ssid.isNullOrBlank()} endpoint=${next.ipAddresses.isNotEmpty()}")
                if (next == null) {
                    ticks++
                    // 手动连接可以早于车机热点出现。等待期间也检查已配对网络，
                    // 不等本轮 300s 超时；实验模式仅允许已配对且已绑定的车机网络主动尝试。
                    prepareInvitationNetwork(context, advertiser)
                    // ① 车机已经来邀请过（GET /ctrl-int/1/connect）→ 直接用邀请方 IP 当端点。
                    //    BMW MGU-55 实测：清单里声明「0x4301 不发」，等它是死等；而邀请一旦
                    //    到达，TCP 对端地址就是车机在热点网段上的地址，AirPlay 控制面在 7000。
                    val inviteIp = usableCtrlInvitation(advertiser)?.host
                    if (inviteIp != null) {
                        val base = lastSeen.get()
                        log("SESSION: ✓ 车机已邀请（$inviteIp）——用它当 AirPlay 端点 :7000")
                        usable = CarPlayEndpoint(
                            ssid = base?.ssid,
                            passphrase = base?.passphrase,
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
                    val waitBefore = requireActivation
                    requireActivation = (invitationOnly || profileRequiresActivation) && !proactiveReady()
                    if (waitBefore && !requireActivation) {
                        log("WIFI-ACTIVE: 已配对车机 Wi-Fi 稳定满 10s，主动尝试 AirPlay / Wi-Fi iAP2；尚未收到车机邀请")
                        val remembered = probeRememberedEndpoint(context, coordinator, true)
                        if (advertiser.lastInvitePeerIp != null) continue
                        if (proactiveReady()) {
                            usable = remembered ?: retryEndpoint?.takeIf {
                                invitationOnly && knownWifiRetry.ready(boundNetwork?.toString(), wifiReceiverMac,
                                    SystemClock.elapsedRealtime())
                            }?.also {
                                log("WIFI-ACTIVE: 复用同一车机/网络上失败的候选 ${it.ipAddresses}:${it.airPlayPort} 作实际建链重试，不重复全段扫描")
                            }
                        }
                        if (usable != null) continue
                    }
                    // ① 静默网关试探：手机刚连上（或本来就该连）车机热点时，不用等 0x4301——
                    //    车机的 AirPlay 控制面通常就在热点网关的 7000 端口。
                    val ssid = currentWifiSsid(context)
                    if (!requireActivation && !ssid.isNullOrBlank() && ssid !in probedSsid) {
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
                                        ipAddresses = listOf(mdnsHere.host),
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
                        connectionDiagnostics.report().lastOrNull()?.let { log(it) }
                        val seen = lastSeen.get()
                        val waited = ticks * ENDPOINT_TICK_MILLIS / 1000
                        // 区分「车机根本没连进来」与「连进来了但没给凭据」——前者是车机
                        // 侧的问题（iDrive 里没把本机当 CarPlay 手机），后者才是协议问题。
                        if (requireActivation) {
                            val wifiBound = wifiReceiverMac != null && boundNetwork != null
                            val waitingForCarIap2 = KnownWifiWaitCopy.awaitingHeadUnitIap2(
                                wifiBound, coordinator.sessionStartCount, ReconnectDiscoverability.firstWindowOver())
                            if (waitingForCarIap2) {
                                val current = _snapshots.value.link
                                if (current !is LinkState.Negotiating || current.step != KnownWifiWaitCopy.HERO) {
                                    push(link = LinkState.Negotiating(KnownWifiWaitCopy.HERO))
                                }
                                log(KnownWifiWaitCopy.progress(waited, coordinator.sessionStartCount))
                            } else {
                                val current = _snapshots.value.link
                                if (current is LinkState.Negotiating && current.step == KnownWifiWaitCopy.HERO) {
                                    push(link = LinkState.Negotiating("等待车机开启 CarPlay"))
                                }
                                log(
                                    if (wifiBound) {
                                        "SESSION: 等待本轮车机 Wi-Fi ctrl 邀请，已等 ${waited}s；" +
                                            "已绑定已知车机网络，宝马二次上车蓝牙常仅短窗口后交棒，" +
                                            "不以持续 iAP2 会话为成败（本轮蓝牙会话=${coordinator.sessionStartCount}）"
                                    } else {
                                        "SESSION: 等待本轮车机邀请/0x4301，已等 ${waited}s；" +
                                            "蓝牙会话次数=${coordinator.sessionStartCount} 已收Wi-Fi凭据=${seen?.ssid != null}，保持广播与监听"
                                    },
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
                val merged = mergeEndpoint(lastSeen.get(), next)
                lastSeen.set(merged)
                // 0x5703 只给 ssid/psk：先入网 + 广播，车机才可能邀请并下发 0x4301
                if (!wifiJoined && !merged.ssid.isNullOrBlank()) {
                    log("SESSION: 已收到车机热点参数——先核实入网，再广播 _carplay-ctrl")
                    val network = joinAndBind(context, merged)
                        ?: throw IllegalStateException("车机热点入网失败：${wifiJoiner?.lastFailureReason ?: "没有匹配本车的有效网络"}")
                    wifiJoined = true
                    connectionDiagnostics.mark(connectionAttemptId, ConnectionDiagnostics.Stage.WIFI_BOUND)
                    advertiser.ensureForNetwork(merged.ssid, force = true, network = network)
                }
                // Only fresh coordinator callbacks reach here; cached/scanned endpoints
                // are gated separately during fallback. A fresh 0x4301 is also a receiver invitation.
                if (!headUnitProfile.requireFreshCtrlInvite && pickHost(merged) != null) {
                    usable = merged
                    continue
                }
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
            if (usable != null) awaitingCarInvitation = false
            usable
        } finally {
            callbacks.close()
        }
    }

    /** 合并两次端点回调：0x5703 给热点凭据、0x4301 给端口与地址，字段各自补齐。 */
    private fun mergeEndpoint(old: CarPlayEndpoint?, incoming: CarPlayEndpoint): CarPlayEndpoint {
        if (old == null) return incoming
        return incoming.copy(
            ssid = incoming.ssid ?: old.ssid,
            passphrase = incoming.passphrase ?: old.passphrase,
            airPlayPort = if (incoming.airPlayPort > 0) incoming.airPlayPort else old.airPlayPort,
            deviceIdentifier = incoming.deviceIdentifier ?: old.deviceIdentifier,
            publicKeyHex = incoming.publicKeyHex ?: old.publicKeyHex,
            sourceVersion = incoming.sourceVersion ?: old.sourceVersion,
            ipAddresses = incoming.ipAddresses.ifEmpty { old.ipAddresses },
            gateway = incoming.gateway ?: old.gateway,
        )
    }

    private fun fail(reason: String, expectedAttempt: Long = connectionAttemptId) {
        scope.launch {
            autoConnectChanges.lock()
            try {
                if (expectedAttempt != connectionAttemptId || !sessionLive.compareAndSet(true, false)) return@launch
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
                scheduleAutoReconnect("失败: $reason", hadLink, receiver)
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

    /** 降级模式下连热点的运行时权限是否已授予（有 Shizuku 时不需要，恒 true）。 */
    private fun degradedPermissionGranted(): Boolean {        if (ShizukuTools.isReady) return true
        val ctx = appCtx ?: return true
        return runCatching { SpecifierPermissions.isGranted(ctx) }.getOrDefault(false)
    }

    /** 系统定位服务是否开启（仅 12/12L 相关；13+ 用 NEARBY_WIFI_DEVICES，恒 true）。 */
    private fun degradedLocationOn(): Boolean {
        if (ShizukuTools.isReady) return true
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
        if (ShizukuTools.isReady) return true
        val reason = SpecifierPermissions.degradeBlockReason(context)
        if (reason != null) {
            fail(reason)
            return false
        }
        return true
    }

    /** 降级模式成功连上车机热点后：标记首连已完成（系统确认框已批准，后续静默）。 */
    private fun markDegradedFirstConnectIfNeeded() {
        if (ShizukuTools.isReady) return
        if (!SettingsStore.degradedFirstConnectDone) {
            SettingsStore.degradedFirstConnectDone = true
            log("PERM: ✓ 降级模式首连完成——此后上车自动连接将静默（不再弹确认框）")
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
    fun log(line: String) {
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
            if (logs.isEmpty() && connectionDiagnostics.report().isEmpty()) {
                log("LOG: 无日志可导出")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { writeDownloadsFile(logs) }
            result.onSuccess { path ->
                _snapshots.value = _snapshots.value.copy(lastExportPath = path)
                log("LOG: ✓ 日志已导出 $path（${logs.size} 行）")
            }.onFailure { e ->
                log("LOG: 导出失败 - ${e.message}")
            }
        }
    }

    /** 写 `Download/HyperCar/hypercar-log-<时间戳>.txt`；Android 10+ 走 MediaStore 免权限。 */
    private fun writeDownloadsFile(logs: List<String>): Result<String> = runCatching {
        val ctx = requireNotNull(appCtx) { "Repo 未初始化" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "hypercar-log-$stamp.txt"
        val text = buildString {
            appendLine("# HyperCar 调试日志  导出于 $stamp")
            appendLine("# 应用 ${DeviceInfo.appVersion(ctx)}")
            appendLine("# 设备 ${DeviceInfo.systemSummary()}")
            appendLine("# 说明：以下时间戳为本机时间；保留的落盘日志（含上一份轮转，读取失败时回退当前日志）共 ${logs.size} 行")
            appendLine("# 连接摘要：WAITING=尚未完成 RECORD；STREAMING=已完成 RECORD；不代表实际画面/声音验证")
            connectionDiagnostics.report().forEach { appendLine("# $it") }
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

    /** AirPlay 协商失败后原地重试的次数上限（不拆 iAP2 链路，保持车机 CarPlay 窗口）。 */
    private const val AIRPLAY_MAX_ATTEMPTS = 6

    /** 两次 AirPlay 重试之间的间隔。 */
    private const val AIRPLAY_RETRY_DELAY_MILLIS = 2_500L

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

    /** 重启时从落盘日志带回环形缓冲的行数（够覆盖崩溃前的一段现场）。 */
    private const val DISK_LOG_TAIL_LINES = 400
}















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

    private suspend fun launchCarWith(ctx: android.content.Context): String {
        // Do not probe 7240 with a disposable client: it consumes a real CarLife connection.
        val comp = "com.miui.carlink/com.miui.carlink.castfwk.usb.CarlifeAdbActivity"
        if (ShizukuTools.isReady) {
            val result = runCatching { ShizukuTools.exec("am start -n $comp", 8_000) }.getOrNull()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (result?.ok == true) return "入口启动请求已发送；等待七路通道、挑战认证与加密就绪"
        }
        return runCatching {
            ctx.startActivity(android.content.Intent().setComponent(
                android.content.ComponentName("com.miui.carlink", "com.miui.carlink.castfwk.usb.CarlifeAdbActivity"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            "入口启动请求已发送；等待 CarWith 协议握手"
        }.getOrElse { "启动 CarWith 失败: ${it.javaClass.simpleName}" }
    }
