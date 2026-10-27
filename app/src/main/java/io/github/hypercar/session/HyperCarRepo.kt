package io.github.hypercar.session

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.hypercar.autowake.AutoStart
import io.github.hypercar.autowake.HyperCarService
import io.github.hypercar.core.AudioStats
import io.github.hypercar.core.CarLifeState
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
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
 *    → 广播唤醒本机 CarLife 连 127.0.0.1
 */
object HyperCarRepo {

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
    /**
     * 实验（`SettingsStore.carlifeFirst`）：**先**把 CarLife 拉到前台，等它把蓝牙相关的
     * 初始化折腾完，**再**启动 iAP2 监听/出站。
     *
     * 动机：2015 版协议明确"手机端 CarLife 在前台才产视频，不在前台只发 length=0 心跳包"，
     * 这解释了 `Qin:0`；但 CarLife 一起前台就会抢蓝牙顶断 iAP2（2026-10-26 16:31 现场）。
     * 如果它的抢蓝牙只是一次性启动动作，把顺序反过来两者就能共存。
     */
    private suspend fun experimentWakeCarLifeFirst() {
        log("EXPERIMENT: 先拉 CarLife 到前台——等它做完蓝牙初始化，再起 iAP2")
        val r = runCatching {
            ShizukuTools.exec(
                "am start -n com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity",
                8_000,
            )
        }.getOrNull()
        log(
            "EXPERIMENT: am start exit=${r?.code} " +
                r?.out?.lineSequence()?.firstOrNull().orEmpty().take(100),
        )
        val adapter = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        for (i in 1..3) {
            kotlinx.coroutines.delay(1_500)
            val on = runCatching { adapter?.isEnabled }.getOrNull()
            val bonded = runCatching { adapter?.bondedDevices?.size }.getOrNull()
            log("EXPERIMENT: 第 ${i * 1500}ms —— BT enabled=$on 已配对=$bonded")
        }
        log("EXPERIMENT: 开始启动 iAP2（若紧接着出现 BT: 启动… 说明共存尝试开始）")
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
        scope.launch {
            runCatching { RustCoreBridge.nativeStop() }
            delay(AIRPLAY_RETRY_DELAY_MILLIS)
            if (!sessionLive.get()) return@launch
            val context = appContext() ?: return@launch
            val fd = openControlFd(context, host!!, port)
            if (fd == null || fd < 0) {
                retryAirplayOrFail("控制端口重连失败 $host:$port")
                return@launch
            }
            val identityFile = context.filesDir.resolve("controller_identity.txt")
            ensurePairingIdentity(context, identityFile)
            val identityPath = identityFile.absolutePath
            val json = SettingsStore.engineConfigJson(host, port, sessionDeviceMac, identityPath, sessionMdHost)
            log("CORE: 重试 nativeStart（$host:$port fd=$fd，第 $attempt 次）")
            val started = RustCoreBridge.nativeStart(json, fd, socketProvider)
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
    /** 晚到 0x4301 给出的地址/端口（端口循环里会立刻改用，不再陪跑候选端口）。 */
    @Volatile
    private var lateAirplayHost: String? = null
    @Volatile
    private var lateAirplayPort: Int = 0

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

    /** 本轮是否探测到本机 CarLife 在监听 7240（true = 引擎走主动拨号 md_host）。 */
    @Volatile
    private var carLifeDialMode = false

    /** 本轮引擎配置里的 md_host（重试 nativeStart 时复用）。 */
    @Volatile
    private var sessionMdHost: String? = null

    /**
     * 正在进行的「拉起 CarLife」协程。
     *
     * 为什么要记着它：唤醒路径里有 3s 等待 + 服务兜底，而 AirPlay 端口连接可能在这期间就失败、
     * 触发 `releaseSessionResources()`（会 force-stop CarLife 收窗口）。若不取消这个协程，
     * 它随后又会把 `CarlifeConnectService` 拉起来——留下一个"7240 在听但不回 Phase 1"的
     * 空壳，下一轮连接必卡（2026-10-26 12:47 日志：57.6s 清理、00.4s 又拉起服务）。
     */
    private var wakeJob: kotlinx.coroutines.Job? = null

    /** 上一次 `am start` 拉起 CarLife 的时间（uptimeMillis），用于避免 300ms 内重复拉起。 */
    @Volatile
    /** 是否处于 CARLIFE-ONLY 调试模式（不接 CarPlay，需要单独收尾）。 */
    private var carLifeOnlyMode = false
    private var carLifeLaunchedAt: Long = 0L

    /** CarLife 握手完成（`carlife_active`）的时间戳，握手看门狗用它判断是否卡住。 */
    @Volatile
    private var carLifeActiveAt: Long = 0L

    /** 拿到的 CarLife 投屏 binder（ICarLifeCastManager，探测用）。 */
    @Volatile
    private var castBinder: android.os.IBinder? = null

    /** CarLife 的 IDisplayController（startDisplay/stopDisplay）。 */
    @Volatile
    private var castDisplayController: android.os.IBinder? = null

    /** 已推给车机的视频帧数（`onStats` 每秒刷新），自动取证用。 */
    @Volatile
    private var lastVideoFrames: Long = 0L

    // ---- 掉线自动重连（方案 A-3）----
    // 车机侧链路断开（ACL 掉、AirPlay 会话结束、车机重启）后自动重新建链，
    // 不需要用户去点「连接」。用户主动断开时置 userDisconnectRequested，不再重连。
    private var reconnectJob: Job? = null

    /** 连续失败次数（成功进 connected 时清零），用来做退避与上限。 */
    private var reconnectAttempts = 0

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
        // 投屏宿主（ICarLifeCastManager）→ 统一日志与画布回调（见 carlife/CarLifeCastHostBinder.kt）
        io.github.hypercar.carlife.CarLifeCastHostBinder.logger = { line -> log(line) }
        io.github.hypercar.carlife.CarLifeCastHostBinder.onPrepareCast = { w, h, dpi, surface ->
            log("CASTHOST: prepareCast 画布 ${w}x${h}@${dpi}dpi surface=${surface != null}")
            // 建虚显（sink = CarLife 的编码面）并把验证页启动上去（carlife/CastDisplay.kt）
            val ctx = appContext()
            if (ctx != null && surface != null) {
                runCatching { io.github.hypercar.carlife.CastDisplay.attach(ctx, w, h, dpi, surface) }
                    .onFailure { log("CAST-DISPLAY: attach 失败 ${it.message}") }
            } else {
                log("CAST-DISPLAY: 跳过（ctx=${ctx != null} surface=${surface != null}）")
            }
        }
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
            AutoStart.handler = { ctx, device, connected ->
                val app = ctx.applicationContext
                if (connected) {
                    // 记住刚连上 ACL 的车机：本轮 iAP2 只拨/只收这台，别再去碰
                    // HI-BOX 之类已配对的旧 dongle（每次 RFCOMM 失败都白占栈、添噪音，
                    // 2026-10-25 现场日志里 10 分钟里一半是它们的连接失败）
                    runCatching { device.address }.getOrNull()?.let { address ->
                        if (address != aclCarMac) {
                            aclCarMac = address
                            log("AUTO: 本轮 iAP2 目标锁定为 ACL 车机 $address（${runCatching { device.name }.getOrNull() ?: "?"}）")
                        }
                    }
                    if (!HyperCarService.isRunning) {
                        runCatching {
                            app.startForegroundService(HyperCarService.startIntent(app, device))
                        }.onFailure {
                            // FGS 被系统拦（未授悬浮窗等）：降级直接连接（进程可能被回收）
                            log("AUTO: FGS 启动被拦（${it.message}）——降级直接拉起会话")
                            connect(app)
                        }
                    } else {
                        connect(app)
                    }
                } else {
                    // 收摊路径必须幂等，且**只在服务确实在跑时**投 ACTION_STOP：
                    // 服务收到 ACTION_STOP 会反向调本 handler(false)（见 HyperCarService），
                    // 若这里无条件再投 ACTION_STOP，就形成
                    //   handler(false) → startService(STOP) → onStartCommand(STOP) → handler(false) …
                    // 的自激回环——实测每秒数百轮，只有「车机蓝牙 断开 + 会话已断开」两种日志，
                    // 且车机不在场也照刷（进程自己把自己钉在前台）。
                    if (HyperCarService.isRunning) {
                        runCatching { app.startService(HyperCarService.stopIntent(app, device)) }
                    }
                    runCatching { device.address }.getOrNull()?.let { address ->
                        if (address == aclCarMac) aclCarMac = null
                    }
                    disconnect()
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
        val name = runCatching { device.name }.getOrNull() ?: "未知设备"
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

    fun connect(context: Context) {
        init(context)
        if (connecting || streaming) {
            log("CONNECT: 已在连接/已连接，忽略重复触发")
            return
        }
        // 手动/自动发起连接都算"用户想要连"，清掉断开标记与重连倒计时
        userDisconnectRequested = false
        reconnectJob?.cancel()
        reconnectJob = null
        sessionHadLink = false
        connecting = true
        sessionLive.set(true)
        // 阶段计时基准：之后每条 CONNECT 日志都带 +Nms，一眼看出卡在哪一步
        sessionStageStart = System.currentTimeMillis()
        push(link = LinkState.Negotiating("检查 Shizuku"))
        connectJob?.cancel()
        connectJob = scope.launch { runConnect(context.applicationContext) }
    }

    fun disconnect() {
        // CARLIFE-ONLY（调试）模式没走状态机，必须在这里显式收尾，否则界面永远停在"协商中"
        if (carLifeOnlyMode) {
            carLifeOnlyMode = false
            runCatching { RustCoreBridge.nativeStop() }
            runCatching { io.github.hypercar.carlife.CastDisplay.release() }
            push(link = LinkState.Idle)
            log("CARLIFE-ONLY: 已停止并释放虚显")
            return
        }
        // 用户主动断开：不再自动重连
        userDisconnectRequested = true
        reconnectJob?.cancel()
        reconnectJob = null
        // 先掐在途连接：否则它会一直阻塞在「等 iAP2 端点」上，`connecting` 恒 true，
        // 之后再上车也进不来（表现为「连了一次就再也连不上」）。
        // 拆除等它在途协程真正退出后再动资源（join），避免「拆到一半新连接又建起来」；
        // join 有上限——连接可能正卡在 WiFi 入网轮询这类不可中断的等待里。
        val pending = connectJob
        connectJob = null
        pending?.cancel()
        scope.launch {
            withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { pending?.join() }
            runDisconnect("用户/系统断开")
        }
    }

    fun clearLogs() {
        _snapshots.value = _snapshots.value.copy(recentLogs = emptyList())
        // **清空后立刻补回环境自述**：否则用户点一下"清空"，后面的日志就再也没有
        // 设备/系统/SDK/App 版本了（反馈出去的日志无法定位版本，2026-10-25 实测踩到）。
        // 不走 log()：它在「记录已暂停」时会直接丢弃，而这一行恰恰是最该保留的。
        val ctx = appCtx ?: return
        val stamp = logTimeFormat.get().format(java.util.Date())
        val env = "$stamp  ENV: ${DeviceInfo.fullSummary(ctx)}"
        appendDiskLog(env)
        _snapshots.update { it.copy(recentLogs = it.recentLogs + env) }
    }

    // ---------------------------------------------------------------- 连接主流程

    private suspend fun runConnect(context: Context) {
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
            run {
                val p = headUnitProfile
                log(
                    "PROFILE: ${p.id}（${p.label}）0x4300=${p.sendCarPlayAvailability} " +
                        "EA=${p.openEaSessions} 0x4E0E=${p.sendTransportIdentifier} " +
                        "断HFP/A2DP=${p.dropProfilesBeforeIap2} 档案A退场=${p.abortWhenCarPlayWindowClosed} " +
                        "保iAP2=${p.keepIap2AliveOnly}",
                )
            }
            // ---- 实验：先拉 CarLife，再起 iAP2（SettingsStore.carlifeFirst，默认关）----
            if (SettingsStore.carlifeFirst) {
                experimentWakeCarLifeFirst()
            }
            val coordinator = Iap2Coordinator(
                context,
                // 目标优先级：设置里显式指定 > 本轮 ACL 连上的车机 > 自动（全部已配对）。
                // 带上 ACL 车机可以避免每轮去拨 HI-BOX 之类旧 dongle（10 分钟日志里一半是
                // 它们的连接失败），也让入站只收这台车。
                targetMac = { SettingsStore.targetCarMac ?: aclCarMac },
                // force=true：resolve() 有 10s 静默窗，上下层几乎同时解析时非 force 调用
                // 会返回 null → 退回稳定伪 MAC（2026-10-25 现场：同一轮出现两个身份）。
                deviceMacProvider = { BluetoothMac.resolveOrStable(force = true) },
                dropProfilesBeforeIap2 = headUnitProfile.dropProfilesBeforeIap2,
                log = ::log,
            )
            iap2 = coordinator

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
            val advertiser = CarPlayCtrlAdvertiser(context, { deviceMac }, ::log)
            ctrlAdvertiser = advertiser
            val lastSeen = AtomicReference<CarPlayEndpoint?>(null)
            // **等端点超时也不拆 iAP2**（2026-10-24 iPhone HCI 对照 / 2026-10-25 现场）：
            // 真 iPhone 在拿到 0x5703 后 ≥34s 不发任何 iAP2 报文、也从不主动拆链；
            // 而「拆除会让车机收起 CarPlay 图标」——一旦车机把本机从 CarPlay 设备里摘掉，
            // 它连 iAP2 都不再发起（10-25 日志：10 分钟里 0 次连入）。所以这里改成
            // 「超时只提示、不拆链，继续下一轮等」，直到用户断开 / ACL 断开 / 拿到端点。
            var endpoint: CarPlayEndpoint? = null
            var waitRounds = 0
            while (endpoint == null) {
                endpoint =
                    awaitUsableEndpoint(context, coordinator, lastSeen, advertiser)
                        ?: gatewayFallbackEndpoint(context, lastSeen.get())
                if (endpoint == null) {
                    waitRounds++
                    val seen = lastSeen.get()
                    val reason =
                        if (seen == null) {
                            if (coordinator.sessionStartCount == 0) {
                                "车机未发起 iAP2（ACL 已连但收不到 …cafe 连入）——" +
                                    "请在 iDrive 里删除本机重新配对 / 确认 Apple CarPlay 已启用"
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

            // ---- 联调开关：只保 iAP2 链路，不起 AirPlay ----
            // 目的：WinPlay 这类接收端的「接收会话」与蓝牙 iAP2 强绑定（链路一断就整进程退出），
            // 而 CarLife MD 只在车机侧（PC）才连得上。于是让手机只当 iAP2 保活端，
            // AirPlay 由 PC 侧 hypercar-hub 去推，两边就不会抢同一条 AirPlay 会话。
            // 现场 2026-10-23 22:37：手机引擎与 PC hub 同时连 17000，WinPlay 画面被后者顶掉。
            //
            // 读**档案**而不是 SettingsStore 的派生开关：那个值只是「选档案时的副作用」，
            // 旧版本/手动改过设置的情况下会和当前档案不一致（真车档案却停在联调模式，
            // 表现为「一直连不上 AirPlay」）。档案是唯一事实源。
            if (headUnitProfile.keepIap2AliveOnly) {
                log("联调: 只保持 iAP2 链路（不启动 AirPlay 引擎）——端点 ips=${endpoint.ipAddresses} port=${endpoint.airPlayPort}")
                // 不 fail、不 return 前关闭协调器：iAP2 链路由 coordinator 自己的保活定时器维持，
                // 这样接收端（WinPlay）的 BT 桥接不会因链路关闭而退出。
                push(
                    link = LinkState.Connected(
                        carName = SettingsStore.targetCarName ?: "BMW ID8.5",
                        sinceMillis = System.currentTimeMillis(),
                    ),
                    carLife = CarLifeState.Idle,
                )
                return
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
            val mdnsEndpoint = if (headUnit.preferMdnsAirplay && !endpoint.fromInvite && !haveEndpoint) {
                AirplayServiceProbe(context, ::log).browse(boundNetwork)
            } else {
                when {
                    endpoint.fromInvite ->
                        log("MDNS: 端点来自 ctrl 邀请（${endpoint.ipAddresses}）——跳过 mDNS 查询")
                    haveEndpoint ->
                        log("MDNS: 0x4301 已给出端点 ${endpoint.ipAddresses}:${endpoint.airPlayPort}——跳过 mDNS 查询")
                }
                null
            }
            val apHost = mdnsEndpoint?.host ?: host
            val candidatePorts = if (mdnsEndpoint != null) {
                listOf(mdnsEndpoint.port)
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

            // ---- 唤醒在前 + 探测优先（CarLife 端口归属）----
            // 小米 CarLife / CarWith 的「车辆热点 → 开始连接」模式是 **MD 自己 bind
            // 7240~9340** 等车机连入；我们若先 bind，它必然 EADDRINUSE、界面一直 Waiting。
            // 所以先把 CarLife 叫起来（它启动要 1~3s，正好和下面连 AirPlay 端口并行），
            // 再探测 7240：有人监听 → 走「主动拨号」；没人监听 → 仍由 HU 监听（客户端型）。
            //
            // **但已经在监听时绝不能再拉起**：CarLife 的单实例逻辑会把正在服务我们的那份
            // 进程杀掉，新实例虽然也 bind 7240，却**不回 Phase 1**（空壳）。
            // 2026-10-26 13:54 现场：22.758 连上 → 22.869 我们 am start → 22.767 通道断开
            // → 23.77 新实例、一直"握手进行中（Phase 1: 等版本匹配）"。
        // **顺序很关键**：CarLife 只在"编码器初始化"那一刻问宿主 use_carlife_opengl。
        // 所以必须在发 VIDEO_ENCODER_INIT 之前就把我们的宿主 Service 绑上（= 冒充 CarWith）。
        runCatching {
            // 注意：这里要绑的是真正提供投屏服务的 CarLife，不是 CarWith（CarWith 没有我们的宿主实现）
            val pkg = resolveCastHostCarLifePackage()
            log("CASTHOST: 会话开始前先绑定宿主 target=$pkg")
            io.github.hypercar.carlife.CastHostService.startCarLifeSession(appContext() ?: return@runCatching, pkg)
        }.onFailure { log("CASTHOST: 预绑定失败 ${it.message}") }
            if (isLocalPortListening(CARLIFE_CMD_PORT)) {
                log("WAKE: CarLife 已在监听 7240——跳过拉起（避免触发单实例重启）")
                _snapshots.value = _snapshots.value.copy(carLife = CarLifeState.Waiting())
            } else {
                wakeLocalCarLife()
            }

            // ---- 连 AirPlay 控制端口并移交 FD ----
            push(link = LinkState.Negotiating("连接车机 AirPlay 端口"))
            var port = 0
            var controlFd: Int? = null
            // 晚到的 0x4301 会给出**真实端口与地址**（WinPlay：17000 + IPv6 link-local）。
            // 以前它只更新一行日志，我们还在用猜的 [7000,7001,7100] 死连到超时
            // （2026-10-25 21:48 现场：0x4301 早给了 airPlayPort=17000，我们 14s 后还在试 7100）。
            // 现在每轮先看它一眼：给了就**立刻改用**，不再陪跑候选端口。
            var hostNow = apHost
            var portsNow: List<Int> = candidatePorts
            // 一个**短重试窗口**：接收端/车机常常在 ctrl 邀请之后才把 7000 打开，
            // 或者中途重启一次 AirPlay 服务；单轮「全 refused 就 fail」会误判成连不上
            // （2026-10-26 12:47 桌面联调现场：探测时 7000 开着，1.5s 后真连全 refused）。
            // 窗口内每 400ms 重试一轮，单次连接超时缩短到 1.2s，避免最坏情况拖太久。
            val connectDeadline = SystemClock.uptimeMillis() + AIRPLAY_CONNECT_WINDOW_MILLIS
            var pass = 0
            var lateSwitched = false
            while (true) {
                pass++
                val perTry = if (pass == 1) {
                    CONTROL_CONNECT_TIMEOUT_MILLIS.toInt()
                } else {
                    AIRPLAY_CONNECT_RETRY_TIMEOUT_MILLIS.toInt()
                }
                for (candidate in portsNow) {
                    val fd = openControlFd(context, hostNow, candidate, perTry)
                    if (fd != null && fd >= 0) {
                        port = candidate
                        controlFd = fd
                        break
                    }
                }
                if (controlFd != null) break
                // 晚到的 0x4301：给了真实端口/地址就立刻改用它（只切一次，避免来回跳）
                val latePort = lateAirplayPort
                val lateHostRaw = lateAirplayHost
                if (!lateSwitched && latePort > 0) {
                    lateSwitched = true
                    // 只在晚到地址是 **IPv4** 时改 host：fe80:: link-local 不带 scope 连不上
                    // （EINVAL，2026-10-25 两轮现场如此）。这种情况只借它的端口，host 保持原样。
                    val lateHost = lateHostRaw?.takeIf { it.contains('.') && !it.contains(':') }
                    if (lateHost != null) {
                        log("SESSION: ✓ 晚到的 0x4301 给出端点 ${lateHost}:${latePort}——改用它重连")
                        hostNow = lateHost
                        airplayHost = lateHost
                    } else {
                        log("SESSION: 晚到的 0x4301 端口=${latePort}（地址 ${lateHostRaw ?: "-"} 不是 IPv4）——沿用 $hostNow，只换端口")
                    }
                    portsNow = listOf(latePort)
                }
                if (SystemClock.uptimeMillis() >= connectDeadline) break
                if (pass == 1) {
                    log(
                        "SESSION: 候选端口全部连不上——${AIRPLAY_CONNECT_WINDOW_MILLIS / 1000}s 内重试" +
                            "（接收端可能还没把 AirPlay 端口打开）",
                    )
                }
                kotlinx.coroutines.delay(400)
            }
            val apHostFinal = hostNow
            if (controlFd == null || controlFd < 0 || port == 0) {
                fail(
                    "AirPlay 控制端口连接失败 $apHostFinal（试过 ${portsNow};" +
                        " 端口探测时可能还开着，连接被拒说明接收端已把 AirPlay 服务关掉）",
                )
                return
            }
            airplayPort = port
            // 记住这次成功的端点：下次同一热点直接试它（盒子就是这么稳的）。
            //
            // **只记 IPv4**：fe80:: 链路本地地址不带 scope 连不上（EINVAL），一旦被记下来，
            // 之后每次都会先试它、每次都秒失败——2026-10-25 23:53 现场就是这样
            // 「成功过一次以后再也连不上」，记忆里存的是 fe80::db93:…。
            val savedHost = apHostFinal.takeIf { it.contains('.') && !it.contains(':') }
            if (savedHost != null) {
                runCatching {
                    SettingsStore.lastAirplayEndpoint = "$savedHost:$port|${currentWifiSsid(context).orEmpty()}"
                }
            } else {
                log("SESSION: 端点 $apHostFinal 不是 IPv4——不写入记忆（避免下次先试死地址）")
            }

            // ---- 启动 Rust 引擎（协商在 Rust 内继续）----
            push(link = LinkState.Negotiating("Rust 核心层协商 AirPlay 会话"))
            // 探测优先：CarLife 若已经在 7240 上监听，就把引擎切成「主动拨号」，
            // 绝不能再让它去 bind（否则它永远抢不到端口）。探测窗口 ~2.5s（刚被唤醒时
            // 需要一点启动时间；客户端型 CarLife 不监听，最多白等这一小段）。
            // 探测窗口 2.5s → **30s**：CarLife（com.baidu.carlife.xiaomi）被唤醒后启动较慢，
            // 日志实测从广播到开始监听 7240 常要十几秒（2026-10-26 00:24 现场）。
            // 窗口短了就会误判「无人监听」，转去 HU 监听模式——那样我们占住 7240，
            // 反而把慢启动的 CarLife 挡在门外。等待期间每 4s 补发一次唤醒广播。
            // ⚠ 关掉联动时**必须跳过这个 30s 等待**：CarLife 不会被拉起，7240 永远不会监听，
            // 而车机侧 CarPlay 只等十几秒就拆链路（2026-10-26 16:48 现场：iAP2 Phase 1 全过、
            // 之后一直"仍在等 CarLife 监听 7240"到第 16s，车机 LINK closed，AirPlay 根本没开始）。
            val mdHost = if (!SettingsStore.carlifeEnabled && !SettingsStore.carlifeFirst) {
                log("CARLIFE: 联动已关闭——跳过 7240 探测，引擎直接以 HU 身份监听并马上协商 AirPlay")
                null
            } else {
                probeLocalCarLifeMd(timeoutMillis = 30_000L) { attempt ->
                    if (attempt % 8 == 0) {   // 8 × 200ms ≈ 1.6s → 每 ~1.6s 之后 4s 补一次
                        log("CARLIFE: 仍在等 CarLife 监听 7240（第 ${attempt * 200 / 1000}s），补发唤醒广播")
                        wakeLocalCarLife()
                    }
                }
            }
            // 只有 7240 在监听还不够：CarLife 被系统/后台拉起、服务还没起全时，
            // 7240 能连、8240/9240 却一律 refused，于是拨号模式连了个"空壳"，
            // 之后一直 `连 MD … Connection refused` 死循环（2026-10-26 01:11 现场）。
            // **仍然只用零接触探测**（bind 冲突）：绝不能再 Socket().connect(8240)——
            // 那会把 CarLife 的视频 accept 抢走，后面 Rust 拨进去一帧都收不到。
            val mediaOk = mdHost == null || isLocalPortListening(CARLIFE_VIDEO_PORT)
            if (mdHost != null && !mediaOk) {
                log("CARLIFE: 7240 在监听但 $CARLIFE_VIDEO_PORT 连不上——CarLife 是空壳，改走 HU 监听模式")
            }
            val effectiveMdHost = if (mediaOk) mdHost else null
            carLifeDialMode = effectiveMdHost != null
            sessionMdHost = effectiveMdHost
            if (effectiveMdHost != null) {
                log("CARLIFE: ✓ 探测到本机 CarLife 正在监听 127.0.0.1:7240 → 引擎走「主动拨号」模式")
            } else {
                log("CARLIFE: 7240 无人监听（或端口不全）→ 由 HU 监听（客户端型 CarLife/盒子会连进来）")
            }
            // 下发配置原样打一行（不含密钥）：分辨率/帧率/md_host/voice 一目了然，
            val identityPath = context.filesDir.resolve("controller_identity.txt").absolutePath
            val json = SettingsStore.engineConfigJson(apHost, port, deviceMac, identityPath, effectiveMdHost)
            // 免得再靠猜「这次到底请求了多大尺寸、是监听还是拨号」
            log("CORE +${stageMs()}ms 引擎配置：$json")
            // 前后各记一行：原生崩溃（Rust panic 越过 JNI → SIGABRT）会直接把进程带走，
            // 这两行能区分「崩在 nativeStart 里」还是「崩在引擎跑起来之后」。
            log("CORE: 调用 nativeStart（$apHost:$port fd=$controlFd）")
            val started = RustCoreBridge.nativeStart(json, controlFd, socketProvider)
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
            // CarLife 唤醒放在 onState("connected") 里做（AirPlay RECORD 完成后），
            // 避免 CarLife 视频帧在屏幕流就绪前被丢弃。
            watchCarLifeHandshake()
            armVideoDiag()
        } catch (cancelled: CancellationException) {
            // disconnect() 掐掉在途连接：正常取消，不算失败（也不要把 Failed 写进快照）
            throw cancelled
        } catch (t: Throwable) {
            fail("会话异常: ${t.message}")
        } finally {
            connecting = false
        }
    }

    /**
     * 会话拆除（幂等）：只有真正还活着的会话才拆一次。
     *
     * 幂等闸门不可省：ACL 断开广播 + service 的 ACTION_STOP 转交会让本方法被重复调用，
     * 每调一次都 nativeStop + 重建快照 + 记一行日志，历史版本正是在这里配合
     * ACTION_STOP 回灌，把「断开」刷成了每秒几百条。
     */
    private suspend fun runDisconnect(reason: String) {
        // 幂等：重复的断开事件（ACL 断开广播 + 引擎 ended + service 转交）只真正拆一次。
        // 但**状态必须回 Idle**——曾经这里直接 return，导致链路已断、界面还显示"已连接"。
        if (!sessionLive.compareAndSet(true, false)) {
            _snapshots.update { s ->
                if (s.link is LinkState.Connected) {
                    s.copy(link = LinkState.Idle, carLife = CarLifeState.Idle)
                } else {
                    s
                }
            }
            return
        }
        push(link = LinkState.Stopping)
        val hadLink = sessionHadLink
        releaseSessionResources()
        log("SESSION: 已断开（$reason）")
        _snapshots.update { s ->
            s.copy(
                link = LinkState.Idle,
                carLife = CarLifeState.Idle,
                video = VideoStats(),
                audio = AudioStats(),
                shizukuReady = ShizukuTools.isReady,
                logRecording = logRecording,
            )
        }
        scheduleAutoReconnect(reason, hadLink)
    }

    /**
     * 掉线自动重连（方案 A-3）。
     *
     * 触发条件：**本次会话真正建立过链路**（`hadLink`）、不是用户主动点断开、开着「上车自动连接」。
     * 退避：1s → 2s → 4s → 8s → 15s，最多 5 次；成功进 connected 时计数清零。
     *
     * 为什么要自己重连而不是等 ACL 广播：ACL 只是**蓝牙层**的连接。链路断开后我们把
     * iap2 监听一起停了（见 releaseSessionResources），若车机 ACL 一直没断，就再也不会
     * 有 ACL_CONNECTED 广播进来 —— 表现就是"断了之后再也连不上，必须手动点连接"。
     */
    private fun scheduleAutoReconnect(reason: String, hadLink: Boolean) {
        if (!hadLink) {
            log("RECONNECT: 本次会话没建立过链路（$reason）——不自动重连")
            return
        }
        if (userDisconnectRequested) {
            log("RECONNECT: 用户主动断开——不自动重连")
            return
        }
        if (!SettingsStore.autoConnect) {
            log("RECONNECT: 「上车自动连接」关着——不自动重连")
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log("RECONNECT: 已连续失败 $reconnectAttempts 次——停止自动重连，等车机重新连入")
            return
        }
        val ctx = appContext() ?: return
        val attempt = reconnectAttempts + 1
        val delayMs = RECONNECT_BACKOFF_MILLIS[
            (attempt - 1).coerceAtMost(RECONNECT_BACKOFF_MILLIS.lastIndex),
        ]
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // 车机自己先连回来就不用我们出手（ACL 广播会走 connect()）
            if (streaming || connecting) return@launch
            log("RECONNECT: 链路断开（$reason）——${delayMs}ms 后自动重连（第 $attempt/$MAX_RECONNECT_ATTEMPTS 次）")
            kotlinx.coroutines.delay(delayMs)
            if (userDisconnectRequested) return@launch
            if (streaming || connecting) {
                log("RECONNECT: 已经有连接在跑——取消本次重连")
                return@launch
            }
            reconnectAttempts = attempt
            connect(ctx)
        }
    }

    /**
     * 释放 iap2 / Rust / WiFi 绑定（不碰 UI 状态位；拆除与失败路径共用）。
     * 必须把 iAP2 停掉：只要它在跑，出站循环就每 3s 断一次车机的音频配置文件，
     * 既打扰车机，也会不断制造「ACL 断开」事件。
     */
    private fun releaseSessionResources() {
        runCatching { io.github.hypercar.carlife.CastDisplay.release() }
        val wasDialMode = carLifeDialMode
        carLifeDialMode = false
        sessionMdHost = null
        streaming = false
        stopVoiceMic()
        runCatching { RustCoreBridge.nativeStop() }
        // 先掐掉"正在拉起 CarLife"的协程，再清理进程：
        // 否则它会在 3s 等待结束后把无界面服务重新拉起来，留下 7240 空壳（2026-10-26 12:47 现场）。
        wakeJob?.cancel()
        wakeJob = null
        carLifeActiveAt = 0L
        carLifeLaunchedAt = 0L
        // Rust 引擎停了之后清理本机 CarLife：否则它会留下"半死空壳"（7240 还占着、
        // 内部状态机停在上一轮 Phase 7），下次上车连上去永远不回 Phase 1。
        stopLocalCarLife(wasDialMode)
        runCatching { iap2?.stop() }
        iap2 = null
        runCatching { ctrlAdvertiser?.stop() }
        ctrlAdvertiser = null
        runCatching { wifiJoiner?.release() }
        wifiJoiner = null
        joinedSsid = null
        joinedNetwork = null
        boundNetwork = null
    }

    // ---------------------------------------------------------------- 语音助手麦克风
    //
    // CarLife 的语音识别要求「车机」侧提供麦克风数据（VR 通道 VR_MIC_DATA 0x00058001）。
    // 本机 HU 就用手机自己的麦克风：AudioRecord(16k mono) → nativeSendMic → carlife::inject_mic。
    // 现场 2026-10-24：不给麦克风时手机端报「麦克风有问题」。

    @Volatile
    private var micRecord: android.media.AudioRecord? = null
    private var micJob: Job? = null

    private fun startVoiceMic() {
        if (micRecord != null) return
        val ctx = appContext() ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.RECORD_AUDIO,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            log("VOICE: 缺少录音权限（RECORD_AUDIO）——语音助手会报麦克风异常，去设置授权后重连")
            return
        }
        val rate = 16_000
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            rate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        // 至少 80ms 缓冲：20ms 一帧读，留点余量防采集中断
        val bufSize = maxOf(minBuf, rate / 50 * 2 * 4)
        val rec = runCatching {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        }.getOrNull()
        if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            log("VOICE: AudioRecord 初始化失败（state=${rec?.state}）")
            runCatching { rec?.release() }
            return
        }
        val started = runCatching { rec.startRecording() }.isSuccess
        if (!started) {
            log("VOICE: startRecording 失败")
            runCatching { rec.release() }
            return
        }
        micRecord = rec
        micJob = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(320) // 20ms @16kHz
            while (isActive) {
                val n = runCatching { rec.read(chunk, 0, chunk.size) }.getOrDefault(0)
                if (n > 0) {
                    val pcm = if (n == chunk.size) chunk else chunk.copyOf(n)
                    runCatching { RustCoreBridge.nativeSendMic(pcm) }
                }
            }
        }
        log("VOICE: 手机麦克风已接入 CarLife VR 通道（16kHz 单声道，20ms/帧）")
    }

    private fun stopVoiceMic() {
        micJob?.cancel()
        micJob = null
        micRecord?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        micRecord = null
    }

    // ---------------------------------------------------------------- WiFi

    private suspend fun joinAndBind(context: Context, endpoint: CarPlayEndpoint): Network? {
        val ssid = endpoint.ssid
        val psk = endpoint.passphrase
        // 幂等：先拿 0x5703 入过网、端点到了又走一遍（0x4301 晚到）时不要重复 connect-network
        if (!ssid.isNullOrBlank() && ssid == joinedSsid && joinedNetwork != null) {
            log("WIFI: 本轮已加入热点 $ssid——复用网络，不重复连接")
            return joinedNetwork
        }
        // 静默网关试探已经把进程绑到该 WiFi 了（无密码、只知道网关）——别再用
        // 「当前默认网络」把它顶掉（热点无外网时默认网络常常是蜂窝）
        if (!ssid.isNullOrBlank() && psk.isNullOrBlank() && boundNetwork != null && endpoint.gateway != null) {
            log("WIFI: 已由静默试探绑定 \"$ssid\" 的网络——复用，不重新选网")
            joinedSsid = ssid
            joinedNetwork = boundNetwork
            return boundNetwork
        }
        val joiner = WifiJoiner(context) {
            // 车机熄火热点消失：结束会话，回到待机
            log("WIFI: 车机热点消失（熄火/离开）");
            scope.launch { runDisconnect("热点消失") }
        }
        wifiJoiner = joiner

        val network: Network? = if (!ssid.isNullOrBlank() && !psk.isNullOrBlank()) {
            log("WIFI: 尝试静默加入热点 $ssid（Wi-Fi 开关=${if (joiner.isWifiEnabled()) "开" else "关"}）")
            when (val result = joiner.joinCarHotspotSilently(ssid, psk)) {
                is WifiJoiner.JoinResult.Joined -> {
                    // 降级模式成功连上 = 系统确认框已批准（或此前批准过），记首连完成
                    markDegradedFirstConnectIfNeeded()
                    result.network
                }
                WifiJoiner.JoinResult.Unsupported -> {
                    log("WIFI: 静默入网不支持（${joiner.lastFailureReason ?: "WEP/ROM 限制"}），尝试复用当前网络")
                    null
                }
                WifiJoiner.JoinResult.Failed -> {
                    log("WIFI: 静默入网失败（${joiner.lastFailureReason ?: "原因未知"}）")
                    log("WIFI: → 若手机已手动连上该热点，会继续用下面挑出来的网络试连；否则请在手机上手动连接")
                    null
                }
            }
        } else {
            log("WIFI: 端点无热点凭据（可能已在车机网络），直接绑定当前网络")
            null
        }
        val effective = network ?: currentDefaultNetwork(context)
        if (effective != null) {
            joiner.bindProcess(effective)
            boundNetwork = effective
            if (!ssid.isNullOrBlank() && network != null) {
                joinedSsid = ssid
                joinedNetwork = effective
            }
            // 绑定后把「实际拿到的网络长什么样」打全：IP/网关/DNS/接口名。
            // 真车排查最常缺的就是这几个值（例如 DIRECT- 链路没有网关、拿到 169.254 自分配）
            log(
                "WIFI +${stageMs()}ms ✓ 已绑定网络（进程级）：${describeNetwork(context, effective)}" +
                    if (network == null) "——注意：这是兜底网络，不一定是车机热点" else "",
            )
        } else {
            log("WIFI +${stageMs()}ms 没有可绑定的网络（Wi-Fi 未连接/未授权）")
        }
        return effective
    }

    /** 把某个 [Network] 的链路属性压成一行（IP/前缀/网关/DNS/接口），供日志排查。 */
    private suspend fun describeNetwork(context: Context, network: Network): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.getLinkProperties(network)
        val caps = cm?.getNetworkCapabilities(network)
        val v4 = lp?.linkAddresses?.filter { it.address is java.net.Inet4Address }
            ?.joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" }
        val v6 = lp?.linkAddresses?.count { it.address is java.net.Inet6Address } ?: 0
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(",")
        "if=${lp?.interfaceName ?: "?"} ipv4=[${v4 ?: "-"}] ipv6=${v6} gw=${gw ?: "-"} dns=[${dns ?: "-"}]" +
            " ssid=${currentWifiSsid(context) ?: "-"}" +
            " validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false}" +
            " internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false}"
    }.getOrElse { "网络属性读取失败（${it.message}）" }

    /** 兜底：拿当前默认网络（连上车机热点但静默路径失败时，Android 可能仍选蜂窝）。 */
    private fun currentDefaultNetwork(context: Context): Network? = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
    }.getOrNull()

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
    /**
     * 在**车机热点网段里主动找 AirPlay 端点**（网关为空时的唯一出路）。
     *
     * 为什么要这么干：宝马 DIRECT-BMW 热点不回默认网关（现场日志 `gw=0.0.0.0`，
     * 172.16.222.105/20 只有 IPv4、没有 DNS/网关），于是
     *
     *  - 「把网关当车机」这条路直接不可用（拿不到 gateway 就 continue 了）；
     *  - 车机又明说 `0x4301` 不发（清单里 `CarPlayStartSession(0x4301)=不发`），
     *  - mDNS 只在**端点确定之后**才查——死锁在「等车机地址」直到车机 30s 后拆 iAP2。
     *
     * 这里改成：入网后立刻在**本网段**扫 7000 端口。候选来自三处，覆盖面从窄到宽：
     *
     *  1. ARP/邻居表：车机若给过我们任何一帧，它就一定在表里；
     *  2. 自己所在 /24 的 `.1`/`.2`/`.254`（车机做 AP 时通常是其中之一）；
     *  3. 掩码小于 /24 时（宝马 /20），每个 /24 的 `.1`/`.254`（最多 48 个）。
     *
     * 只做 TCP 连接探测（250ms），确认能连就把 IP 当端点交给后续 pair-verify。/@return 命中的端点；全都不通返回 null
     */
    private suspend fun discoverAirplayOnLocalSubnet(
        context: Context,
        ssid: String,
    ): CarPlayEndpoint? {
        val network = boundNetwork ?: wifiNetworks(context).firstOrNull() ?: return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        val self = lp.linkAddresses
            .mapNotNull { it.address }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: return null
        val prefix = lp.linkAddresses
            .firstOrNull { it.address == self }
            ?.prefixLength
            ?: 24
        val octets = self.address.map { it.toInt() and 0xFF }
        val base = "${octets[0]}.${octets[1]}.${octets[2]}"

        val candidates = LinkedHashSet<String>()
        // ① ARP 里已经出现过的邻居（车机只要回过包就在里面）
        runCatching {
            java.io.File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.split(Regex("\\\\s+"))
                val ip = parts.firstOrNull()?.trim().orEmpty()
                val device = parts.lastOrNull()?.trim().orEmpty()
                val flags = parts.getOrNull(2)?.trim().orEmpty()
                if (ip.count { it == '.' } == 3 && device.startsWith("wlan") && flags != "0x0") {
                    candidates.add(ip)
                }
            }
        }
        // ②/③ 网关候选：自己 /24 的三个常见地址 + 每个 /24 的 .1/.254
        listOf(".1", ".2", ".254").forEach { candidates.add("$base$it") }
        if (prefix in 16..23) {
            val step = 1 shl (24 - prefix)
            val thirdStart = octets[2] - (octets[2] % step)
            var i = 0
            while (i < step && candidates.size < 48) {
                val third = thirdStart + i
                candidates.add("${octets[0]}.${octets[1]}.$third.1")
                candidates.add("${octets[0]}.${octets[1]}.$third.254")
                i++
            }
        }
        candidates.remove(self.hostAddress)
        if (candidates.isEmpty()) return null

        val ports = headUnitProfile.fallbackAirplayPorts.distinct().ifEmpty { listOf(7000) }
        val started = System.currentTimeMillis()
        log("WIFI: 网关为空——在 $base/${prefix} 扫 AirPlay（先试 ${candidates.size} 个常见地址 × 端口 ${ports}）")

        // 阶段 1：常见地址（ARP 邻居 + .1/.2/.254），多端口都试
        probeHostsForAirplay(candidates.toList(), ports)?.let { hit ->
            log("WIFI: ✓ 网段扫描命中车机 AirPlay 端点 ${hit.first}:${hit.second}（${System.currentTimeMillis() - started}ms）")
            return endpointFor(ssid, hit.first, hit.second)
        }

        // 阶段 2：整个前缀全扫（宝马是 /20 → 4094 个地址）。只探主端口，
        // 分块并发、命中即刻返回；未命中的地址大多是 EHOSTUNREACH，会远快于超时。
        val full = fullPrefixHosts(self, prefix, alreadyTried = candidates)
        if (full.isNotEmpty()) {
            log("WIFI: 常见地址无一响应——扩到整个 /$prefix 全扫（${full.size} 个地址，单端口 ${ports.first()}）")
            probeHostsForAirplay(full, listOf(ports.first()))?.let { hit ->
                log("WIFI: ✓ 全段扫描命中车机 AirPlay 端点 ${hit.first}:${hit.second}（${System.currentTimeMillis() - started}ms）")
                return endpointFor(ssid, hit.first, hit.second)
            }
        }
        log("WIFI: 全段扫描未找到车机 AirPlay（${System.currentTimeMillis() - started}ms）——继续等车机邀请/0x4301")
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

    /**
     * 并行探测一组地址（分块 128 并发，命中即返回）。
     *
     * 为什么分块而不是一次全发：/20 有 4094 个地址，一次性建 4094 个 socket 会被
     * 系统 fd 上限和 AP 的客户端表打回来；分块还能在命中后立刻停止、不浪费后面的探测。
     */
    private suspend fun probeHostsForAirplay(
        hosts: List<String>,
        ports: List<Int>,
    ): Pair<String, Int>? = coroutineScope {
        val hit = java.util.concurrent.atomic.AtomicReference<Pair<String, Int>?>(null)
        for (chunk in hosts.chunked(PROBE_CHUNK_SIZE)) {
            if (hit.get() != null) break
            chunk.map { ip ->
                async(Dispatchers.IO) {
                    if (hit.get() != null) return@async
                    for (port in ports) {
                        val ok = runCatching {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(ip, port), FULL_SWEEP_TIMEOUT_MILLIS.toInt())
                            }
                        }.isSuccess
                        if (ok) {
                            hit.compareAndSet(null, ip to port)
                            return@async
                        }
                    }
                }
            }.forEach { it.await() }
        }
        hit.get()
    }

    /**
     * 列出整个前缀内的全部主机地址（排除自己、网络地址、广播地址与已试过的）。
     *
     * 只对 /16../23 生效：/24 及更小的前缀本身就是"全部"，已在阶段 1 覆盖；
     * 再往上（/8 之类）不可能出现在热点场景，直接跳过避免扫爆。
     */
    private fun fullPrefixHosts(
        self: java.net.Inet4Address,
        prefix: Int,
        alreadyTried: Set<String>,
    ): List<String> {
        if (prefix !in 16..23) return emptyList()
        val o = self.address.map { it.toInt() and 0xFF }
        val hostBits = 32 - prefix
        val mask = if (hostBits >= 32) 0 else (1 shl hostBits) - 1
        val network = ((o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]) and mask.inv()
        val limit = TOTAL_SWEEP_MAX
        val out = ArrayList<String>(minOf(1 shl hostBits, limit))
        var addr = network + 1
        val last = network + mask - 1
        while (addr <= last && out.size < limit) {
            val ip = "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
            if (ip != self.hostAddress && ip !in alreadyTried) out.add(ip)
            addr++
        }
        return out
    }

    private fun probeGatewayAsCarHotspot(
        context: Context,
        ssid: String,
    ): CarPlayEndpoint? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val previous = boundNetwork
        for (network in wifiNetworks(context)) {
            val gateway =
                cm.getLinkProperties(network)
                    ?.routes
                    ?.firstOrNull { it.isDefaultRoute }
                    ?.gateway
                    ?.hostAddress
                    ?: continue
            if (!gateway.contains('.') || gateway.contains(':')) continue
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

    /**
     * 连车机 AirPlay 控制端口（TCP），按候选网络逐个试，成功返回已 detach 的 FD。
     *
     * 为什么不能「绑定默认网络后连一次」：手机手动连上热点后，系统默认网络仍可能是
     * 移动数据（热点无外网，HyperOS 会主动切走），这时连 192.168.137.1 必然超时
     * （2026-10-23 20:06、20:08 两次现场都是 5s 超时）。这里把
     * 「已绑定的网络 → 手机上所有 Wi-Fi 网络 → 进程默认网络」都试一遍，哪条通走哪条。
     */
    private suspend fun openControlFd(
        context: Context,
        host: String,
        port: Int,
        timeoutMillis: Int = CONTROL_CONNECT_TIMEOUT_MILLIS.toInt(),
    ): Int? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val candidates = buildList<Network?> {
            boundNetwork?.let { add(it) }
            addAll(wifiNetworks(context))
            add(null)
        }.distinct()
        for (network in candidates) {
            val label = if (network == null) "进程默认网络" else "Wi-Fi 网络 #$network"
            if (network != null) runCatching { cm?.bindProcessToNetwork(network) }
            val startedAt = System.currentTimeMillis()
            var lastError: String? = null
            val fd = runCatching {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(resolveEndpoint(host, port), timeoutMillis)
                keepAliveForRust(socket)
                ParcelFileDescriptor.fromSocket(socket).detachFd()
            }.onFailure { lastError = it.javaClass.simpleName + ": " + it.message }.getOrNull()
            val cost = System.currentTimeMillis() - startedAt
            if (fd != null && fd >= 0) {
                if (network != null) {
                    boundNetwork = network
                    // 同步给 joiner：它的 onLost 只认自己记录的网络，不同步就解不掉绑定
                    wifiJoiner?.bindProcess(network)
                }
                log("NET +${stageMs()}ms ✓ $host:$port 已连通（$label，耗时 ${cost}ms，fd=$fd）")
                return@withContext fd
            }
            log("NET +${stageMs()}ms ✗ 经 $label 连 $host:$port 失败（${cost}ms，${lastError ?: "未知原因"}）")
        }
        // 全败：恢复默认绑定，别让进程一直卡在一块无外网的网络上
        runCatching { cm?.bindProcessToNetwork(null) }
        boundNetwork = null
        null
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

    /**
     * 交给 Rust 的 socket 宿主（Socket / DatagramSocket）**必须保持可达**。
     *
     * `detachFd()` 只是把 fd 从 PFD 里摘出来交给 Rust；Java 的 Socket 对象仍然持有同一个
     * fd，是它背后的 `FileDescriptor` 负责在 GC 时 close。若此时 Socket 已不可达，GC 会在
     * Rust 还在用的时候把 fd 关掉（推流中途断流、引擎 EBADF）。这里把宿主对象留一份强引用：
     * 实际 fd 由 Rust 持有并在 nativeStop 时关闭，Java 这边只是不再去 close 它。
     * 每个会话只留几个小对象，只增不减（不 close、不回收，避免重复 close 到被复用的 fd 号）。
     *
     * 注：不能用 `android.system.Os.dup(int)` 做副本——该重载不是公开 API（只有
     * `dup(FileDescriptor)`），编译期就过不了。
     */
    private val socketKeepAlive = java.util.Collections.synchronizedList(mutableListOf<Any>())

    /**
     * 配对身份自愈：**文件是主、SharedPreferences 是备份**。
     *
     * 车机记住的是这条配对的 pairingId + 公钥；身份文件一旦丢失就会重新生成密钥，
     * 车机认为「新设备」→ 第二次上车连不上、只有两边删配对才能恢复（现场 2026-10-25）。
     * 这里在每次下发引擎配置前对齐两处：
     *  - 文件缺失 + 有备份 → 写回文件（**不会**重新生成，保住车机那边的配对）
     *  - 文件存在 → 更新备份（升级/换机后仍可恢复）
     */
    private fun ensurePairingIdentity(context: Context, path: java.io.File) {
        runCatching {
            if (!path.exists()) {
                val backup = SettingsStore.pairingIdentityBackup
                if (backup.isNotBlank()) {
                    path.writeText(backup)
                    log("PAIR: 身份文件缺失——已用备份恢复（车机侧配对保持有效）")
                }
            }
            if (path.exists()) {
                val text = path.readText().trim()
                if (text.isNotBlank() && text != SettingsStore.pairingIdentityBackup) {
                    SettingsStore.pairingIdentityBackup = text
                    log("PAIR: 已备份配对身份到设置（换机/清缓存后可恢复）")
                }
                val pairingId = text.substringBefore(' ')
                if (pairingId.isNotBlank()) log("PAIR: 当前身份 pairingId=$pairingId")
            }
        }.onFailure { log("PAIR: 身份自愈失败 - ${it.message}") }
    }
    private fun keepAliveForRust(owner: Any) {
        socketKeepAlive.add(owner)
    }

    /**
     * 解析 AirPlay 端点地址：**链路本地 IPv6（fe80::）必须带 scope** 才能连。
     *
     * 不带 scope 时 Android 直接回 `EINVAL`——连接包根本发不出去（2026-10-25 23:58 现场：
     * DiPlay 的 0x4301 只给 fe80::db93:…，控制端口每个尝试都是 2ms 内 EINVAL）。
     * 补 `%wlan0` 后控制通道能通，但**事件通道/数据通道**同样走这里，所以必须集中在一处。
     * 依次尝试 %wlan0 / %wlan1 / %ap0 / %wlan2，再枚举本机所有非回环网卡名。
     */
    private fun resolveEndpoint(host: String, port: Int): java.net.InetSocketAddress {
        val raw = host.trim()
        val linkLocal = raw.contains(':') && raw.substringBefore('%').lowercase().let {
            it.startsWith("fe80") || it.startsWith("fe90") || it.startsWith("fea0") || it.startsWith("feb0")
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

    private val socketProvider = object : RustCoreBridge.SocketProvider {
        override fun openTcpFd(host: String, port: Int, timeoutMs: Int): Int = runCatching {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(resolveEndpoint(host, port), timeoutMs.coerceAtLeast(1_000))
            keepAliveForRust(socket)
            ParcelFileDescriptor.fromSocket(socket).detachFd()
        }.getOrElse { -1 }

        override fun openUdpFd(host: String, port: Int): Int = runCatching {
            val udp = DatagramSocket()
            udp.connect(resolveEndpoint(host, port))
            keepAliveForRust(udp)
            ParcelFileDescriptor.fromDatagramSocket(udp).detachFd()
        }.getOrElse { -1 }
    }

    // ---------------------------------------------------------------- Rust 回调

    private val rustListener = object : RustCoreBridge.EventListener {
        override fun onState(state: String, detail: String) {
            log("CORE: [$state] $detail")
            when (state) {
                "negotiating" -> push(link = LinkState.Negotiating(detail))
                "connected" -> {
                    streaming = true
                    sessionHadLink = true
                    reconnectAttempts = 0
                    push(
                        link = LinkState.Connected(
                            carName = SettingsStore.targetCarName ?: "BMW ID8.5",
                            sinceMillis = System.currentTimeMillis(),
                        ),
                        carLife = CarLifeState.Waiting(),
                    )
                    if (carLifeDialMode) {
                        // 已经探测到 CarLife 在监听、引擎是拨过去的——此时再发一次唤醒广播
                        // 只会打断已建立的 CarLife 会话（它会重新走一遍连接），所以跳过。
                        log("WAKE: 本轮走主动拨号模式，CarLife 已连上——跳过重复唤醒广播")
                        push(carLife = CarLifeState.Waiting())
                    } else {
                        wakeLocalCarLife()
                    }
                    // 语音助手要靠车机侧供麦克风；CarLife 连上后再开采集，
                    // 避免没会话时空录（也会占着麦克风影响其他应用）
                    startVoiceMic()
                }
                "carlife_waiting" -> push(carLife = CarLifeState.Waiting())
                // Rust 侧 CarLife 14 步握手完成（VIDEO_ENCODER_START 已发）
                "carlife_active" -> {
                    // CarLife 收到 LAUNCH_MODE_NORMAL 会 `openHome()` 把自己的主页弹到前台
                    // （反编译 CarlifeHsgHandler:293）。它需要这条命令才会进投屏态，
                    // 但我们不需要用户看见它 → 过 3 秒用 Shizuku 把我们自己拉回前台，
                    // CarLife 留在后台继续编码（视频通路不受影响，已实测）。
                    scope.launch {
                        kotlinx.coroutines.delay(3_000)
                        runCatching {
                            ShizukuTools.exec("am start -n io.github.hypercar/.ui.MainActivity", 5_000)
                        }.onSuccess { log("CARLIFE: 已把 HyperCar 拉回前台（CarLife 留在后台继续推流）") }
                    }
                    push(carLife = CarLifeState.Active())
                    carLifeActiveAt = SystemClock.uptimeMillis()
                    // CarWith 用 ICarLifeManager.register + IDisplayController.startDisplay 让
                    // CarLife 真正开始投屏（见 startCarLifeDisplay）；我们也调一次试试。
                    scope.launch {
                        kotlinx.coroutines.delay(600)
                        startCarLifeDisplay()
                    }
                    // CarLife 有 ~35 秒的空闲看门狗：Activity 一旦不在前台，它自己的
                    // `ProcessUtil.killAllProcess` 就把整进程干掉（2026-10-26 00:45/00:48 两次
                    // 现场都是"握手成功后第 34~35 秒进程自杀"，紧接着 CarWith 又 force-stop 重启）。
                    // 所以握手一成要把 CarLife 推到前台——但**不能刚拉起就再推一次**：
                    // 2026-10-26 13:13 现场，唤醒时（31.741）已经 `am start` 过一次，
                    // 这里 300ms 后又来一次（32.056），紧接着就出现「CarLife 重新连一遍 7240、
                    // 新的 CMD 会话永远不回 Phase 1」——已握手的那份实例被顶掉了。
                    val sinceLaunch = SystemClock.uptimeMillis() - carLifeLaunchedAt
                    if (sinceLaunch < 25_000L) {
                        log("CARLIFE: ${sinceLaunch}ms 前刚拉起过——跳过重复 am start（防止单实例重启）")
                    } else {
                        scope.launch {
                            val r = runCatching {
                                ShizukuTools.exec(
                                    "am start -n com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity",
                                    8_000,
                                )
                            }.getOrNull()
                            carLifeLaunchedAt = SystemClock.uptimeMillis()
                            log("CARLIFE: 已把 CarLife 推回前台（防 35s 空闲自杀） exit=${r?.code}")
                        }
                    }
                }
                "failed" -> retryAirplayOrFail(detail)
                "ended" -> {
                    scope.launch { runDisconnect(detail) }
                }
                "stopping" -> { /* runDisconnect 内处理 */ }
            }
        }

        override fun onLog(line: String) = log(line)

        override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {
            // 用 update（CAS）而不是读-改-写：每秒一次，与 log() 的读-改-写并发会吞掉日志行。
            lastVideoFrames = frames
            _snapshots.update { s ->
                s.copy(
                    video = s.video.copy(fps = fps, frames = frames, kbps = kbps),
                    audio = s.audio.copy(playing = audioPlaying, ducking = ducking),
                )
            }
        }
    }

    /**
     * CarLife 系 app 的候选包名（按唤醒优先级）。
     *
     * `com.baidu.carlife.xiaomi` 是 HyperOS 预装的**小米定制版 CarLife**——现场
     * 2026-10-23 实测：手机上只有它和 CarWith，设置里默认的 `com.baidu.carlife`
     * 根本没装，于是广播静默丢弃、7240 永远等不到接入。
     */
    private val carLifePackageCandidates = listOf(
        "com.baidu.carlife",
        "com.baidu.carlife.xiaomi",
        "com.miui.carlink",
        "com.xiaomi.carlink",
        "com.hihonor.carlink",
    )

    /** 实际可用的唤醒包名：设置里配的优先；没装就自动换一个装了的，并把换的结果写进日志。 */
    private fun resolveCarLifePackage(): String {
        val configured = SettingsStore.carlifePackage
        val ctx = appContext() ?: return configured
        fun installed(p: String) = runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess
        // 小米这套的角色分工（2026-10-26 00:36 实测）：
        //   com.miui.carlink（CarWith）  = 有界面、能拉起，负责"CarLife 连接"入口；
        //   com.baidu.carlife.xiaomi     = 真正的 CarLife 引擎，**没有 Launcher 界面**，
        //                                  `monkey -p` 拉它什么都不会发生。
        // 所以即使设置里填的是引擎包，只要同时装了 CarWith，就优先用 CarWith。
        if (installed("com.miui.carlink") && configured != "com.miui.carlink") {
            log("WAKE: 配置的是 $configured（引擎包，无界面），改用它上层的 CarWith（com.miui.carlink）来拉起")
            return "com.miui.carlink"
        }
        if (installed(configured)) return configured
        val fallback = carLifePackageCandidates.firstOrNull { it != configured && installed(it) }
        if (fallback != null) {
            log("WAKE: ⚠ 唤醒包名 $configured 未安装，自动改用 $fallback（建议到设置里改过来）")
            return fallback
        }
        log("WAKE: ⚠ 设备上没有 CarLife 系 app（候选：${carLifePackageCandidates.joinToString(", ")}）")
        return configured
    }

    /** 给调试广播用：返回当前实际可用的 CarLife 包名（优先已安装的候选）。 */
    fun resolveInstalledCarLifePackage(context: Context): String {
        init(context)
        return resolveCarLifePackage()
    }

    /**
     * 调试用：**只跑 CarLife 那一段**（绑宿主 → CMD 握手 → 读 8240 帧），完全不碰蓝牙/iAP2/AirPlay。
     * 用 `am broadcast -a io.github.hypercar.PROBE_CARLIFE_ONLY -p io.github.hypercar` 触发。
     * 判定标准：日志里 `CARLIFE-ONLY: 累计 N 帧` 是否持续增长。
     */
    fun startCarLifeOnly(context: Context, pkgOverride: String? = null) {
        carLifeOnlyMode = true
        userDisconnectRequested = false
        // 先停掉可能还在跑的旧引擎，否则会和新会话互相踢（"停止信号 → VIDEO_ENCODER_PAUSE"）
        runCatching { if (RustCoreBridge.nativeIsRunning()) RustCoreBridge.nativeStop() }
        Thread.sleep(400)
        io.github.hypercar.carlife.CastDisplay.release()
        init(context)
        val pkg = pkgOverride ?: resolveCastHostCarLifePackage()
        log("CARLIFE-ONLY: 目标 $pkg；先绑定投屏宿主")
        io.github.hypercar.carlife.CastHostService.startCarLifeSession(context, pkg)
        val identity = context.filesDir.resolve("carlife_only_identity.txt")
        ensurePairingIdentity(context, identity)
        val pair = ParcelFileDescriptor.createSocketPair()
        val fd = pair[0].detachFd()
        pair[1].close()
        val json = SettingsStore.engineConfigJson(
            carIp = "127.0.0.1",
            airplayPort = 1,
            deviceBluetoothMac = SettingsStore.stableDeviceId,
            identityPath = identity.absolutePath,
            carlifeOnly = true,
        )
        val ok = RustCoreBridge.nativeStart(json, fd, socketProvider)
        log("CARLIFE-ONLY: nativeStart=$ok（只跑 CarLife，不做 AirPlay）")
    }

    /** 投屏宿主绑定只认这两种包（CarWith 不实现我们的宿主 Service，绑了也没用）。 */
    private fun resolveCastHostCarLifePackage(): String {
        val ctx = appContext() ?: return "com.baidu.carlife.xiaomi"
        return listOf("com.baidu.carlife", "com.baidu.carlife.xiaomi")
            .firstOrNull { p -> runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess }
            ?: "com.baidu.carlife"
    }

    /**
     * 探测本机 CarLife（MD）是否在 `127.0.0.1:7240` 监听。
     *
     * 小米 CarLife / CarWith 的「车辆热点」模式是 **MD 自己 bind 7240..9340** 等车机连入；
     * 我们若先去 bind，它必然 `EADDRINUSE`、界面永远 Waiting。所以唤醒后先探一下：
     *  - 命中 → 把 `md_host=127.0.0.1` 传给引擎，走「主动拨号」（我们当车机）；
     *  - 未命中 → 引擎照旧自己监听（客户端型 CarLife 会连进来）。
     *
     * 窗口默认 2.5s：刚被广播唤醒的 CarLife 需要一点启动时间；客户端型 CarLife 不监听，
     * 最多白等这一小段（与 AirPlay 握手相比可接受）。
     *
     * [onAttempt] 用于等待期间补发唤醒广播（CarLife 启动慢，单次广播容易错过）。
     */
    /**
     * **零接触**判断本机某端口是否已有人 LISTEN。
     *
     * **千万不要用 `Socket().connect()` 去探测**：CarLife 的 `ServerSocket.accept()` 只接一次，
     * 我们连上又立刻 close，会被它当成「车机连上又断开」，直接把 CMD/VIDEO 线程踢回初始态
     * （2026-10-26 现场：8240 被探测过一次之后，Rust 真正拨进去 0 字节、一帧视频都没有）。
     *
     * 这里用「bind 冲突」判断：能 bind 成功 = 没人监听（立刻关掉）；抛 `BindException` = 有人在听。
     * **全程不产生任何 TCP 握手**，不会碰 CarLife 的 accept 队列。
     */
    private fun isLocalPortListening(port: Int): Boolean =
        try {
            java.net.ServerSocket().use { ss ->
                ss.reuseAddress = false // 必须在 bind 前设：SO_REUSEADDR 会让「有监听」也绑得上
                ss.bind(java.net.InetSocketAddress("127.0.0.1", port), 1)
            }
            false // 绑上了 → 没人监听（已立刻关掉）
        } catch (_: java.net.BindException) {
            true // EADDRINUSE → CarLife 正在 LISTEN
        } catch (_: Throwable) {
            false
        }

    /** 单次零接触探测 127.0.0.1:7240 是否在监听（唤醒流程里用，不要阻塞）。 */
    private fun probeCarLifeOnce(): Boolean = isLocalPortListening(CARLIFE_CMD_PORT)

    private fun probeLocalCarLifeMd(
        timeoutMillis: Long = CARLIFE_PROBE_TIMEOUT_MILLIS,
        onAttempt: ((Int) -> Unit)? = null,
    ): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var attempts = 0
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++
            onAttempt?.invoke(attempts)
            if (isLocalPortListening(CARLIFE_CMD_PORT)) {
                log("CARLIFE: 第 $attempts 次零接触探测命中 127.0.0.1:$CARLIFE_CMD_PORT（CarLife 在监听）")
                return "127.0.0.1"
            }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    /**
     * 唤醒本机 CarLife / CarWith·CarLife / 荣耀车联，令其连 127.0.0.1 回环网关。
     *
     * 三级递进（现场 2026-10-23 22:00：应用内广播发出去后，7240 一个连接都没有）：
     *  ① 应用内广播 `com.baidu.carlife.Action.StartWireless`——最轻，但 Android 14+
     *     对「非导出接收器」会静默丢弃，且包名不对时也毫无反馈；
     *  ② Shizuku 已就绪时用 shell 再广播一次（`am broadcast` 以 shell 身份投递，
     *     不受导出限制）；
     *  ③ 仍未接入就把 app 直接拉起来（`monkey -c LAUNCHER`），让它自己走无线连接。
     * 顺带把设备上「CarLife/CarLink 系」的包名列进日志——包名配错时一眼可见。
     */
    private fun wakeLocalCarLife() {
        // 用户默认关掉 CarLife 联动：CarLife 一激活就会抢蓝牙，把 CarPlay 的 ACL 顶断。
        // （实验开关 carlifeFirst 打开时允许拉起：那正是我们要验证的场景。）
        if (!SettingsStore.carlifeEnabled && !SettingsStore.carlifeFirst) {
            log("CARLIFE: 联动已关闭（设置里关着）——不拉起 CarLife，避免它抢蓝牙顶掉 CarPlay")
            return
        }
        val pkg = resolveCarLifePackage()
        _snapshots.value = _snapshots.value.copy(carLife = CarLifeState.Waiting())

        // 小米这套有两个进程会互相打架：CarWith（com.miui.carlink）收到唤醒广播后会自己
        // 启动一份 CarLife，而 CarLife 有"单实例"逻辑（ProcessUtil.killAllProcess）。
        // 2026-10-26 00:45 现场：我们用 `am start` 拉起的那份已经握手到 Phase 7，
        // 35 秒后 CarWith 又起了一份，直接把**我们正连着的那份杀掉**
        // （ActivityManager: Process com.baidu.carlife.xiaomi … has died: fg）——
        // 于是"握手成功但一会儿就断、视频一帧都收不到"。
        //
        // 结论：**只走一条路**。能直接拉起引擎页（实测有效）就不要再发唤醒广播，
        // 否则等于自己制造双实例。
        val directLaunch = "com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity"
        if (ShizukuTools.isReady && pkg == "com.miui.carlink") {
            wakeJob?.cancel()
            wakeJob = scope.launch {
                // 只拉主屏：副屏（--display N）方案随「CarLife 启动屏」设置一起删了。
                val started = runCatching {
                    ShizukuTools.exec("am start -n $directLaunch", 8_000)
                }.getOrNull()
                log(
                    "WAKE: 拉起 CarLife 引擎页 exit=${started?.code} " +
                        started?.out?.lineSequence()?.firstOrNull().orEmpty().take(120),
                )
                carLifeLaunchedAt = SystemClock.uptimeMillis()
                kotlinx.coroutines.delay(3_000)
                // 会话可能在这 3s 里就失败了（AirPlay 端口连不上 → releaseSessionResources
                // 会 force-stop CarLife）。此时继续拉起只会留下"7240 在听但不回 Phase 1"的空壳。
                if (!sessionLive.get()) {
                    log("WAKE: 会话已结束——放弃继续拉起 CarLife（避免留下空壳进程）")
                    return@launch
                }
                if (!probeCarLifeOnce() && sessionLive.get()) {
                    val svc = runCatching {
                        ShizukuTools.exec(
                            "am start-foreground-service -n com.baidu.carlife.xiaomi/" +
                                "com.baidu.carlife.service.CarlifeConnectService",
                            8_000,
                        )
                    }.getOrNull()
                    log("WAKE: 引擎页没让 7240 起来——退回无界面服务 exit=${svc?.code}")
                }
            }
            return
        }

        runCatching {
            val intent = android.content.Intent("com.baidu.carlife.Action.StartWireless").apply {
                setPackage(pkg)
                addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            runCatching { appContext()?.sendBroadcast(intent) }
                .onFailure { log("WAKE: 广播发送失败 - ${it.message}") }
            log("WAKE: ① 应用内广播 StartWireless → $pkg")
        }
        if (!ShizukuTools.isReady) {
            log("WAKE: 无 Shizuku，跳过 shell 兜底（②③）——若一直 Waiting，检查设置里的唤醒包名")
            return
        }
        scope.launch {
            // 包名到底存不存在、还有哪些 CarLife 系 app
            val listed = runCatching {
                ShizukuTools.exec(
                    "pm list packages | grep -iE 'carlife|carlink|carwith|carlife'",
                    6_000,
                )
            }.getOrNull()
            val packages = listed?.out
                ?.lineSequence()
                ?.map { it.removePrefix("package:").trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.toList()
                .orEmpty()
            log("WAKE: 设备上 CarLife 系包 = ${if (packages.isEmpty()) "（没找到）" else packages.joinToString(", ")}")
            if (packages.isNotEmpty() && pkg !in packages) {
                log("WAKE: ⚠ 设置里的 $pkg 不在上面列表里——很可能是包名不对（改成实际那个）")
            }

            // ② shell 广播：以 shell 身份投递，绕开 Android 14+ 非导出接收器限制
            val bcast = runCatching {
                ShizukuTools.exec(
                    "am broadcast -a com.baidu.carlife.Action.StartWireless -p $pkg --user 0",
                    6_000,
                )
            }.getOrNull()
            log("WAKE: ② shell 广播 exit=${bcast?.code} ${bcast?.out?.replace("\n", " ")?.take(160).orEmpty()}")

            // ③ 拉起 CarLife **进程**（不打开它那个设置页）：
            //    CarLifeApplication.onCreate():83 → CarlifeConnectInitUtils.initConnectModule()
            //      → CarlifeCoreSDK.initConnectModule() → ConnectClient.init()
            //      → startTcpListener() → ConnectManager.startAcceptThread()
            //    也就是说：**进程一起来，7240/8240/… 就开始监听了**，不需要任何 Activity。
            //    这正是 CarWith 的做法（它从来不给用户看那个页面）。
            val svc = "$pkg/com.baidu.carlife.service.CarlifeConnectService"
            val hostPkg = appContext()?.packageName ?: "io.github.hypercar"
            val started = runCatching {
                ShizukuTools.exec(
                    "am startservice -n $svc --es bindPackageName $hostPkg " +
                        "--es bindClassName io.github.hypercar.carlife.CastHostService --ei usb_type 0",
                    8_000,
                )
            }.getOrNull()
            log("WAKE: ③ 启动 CarLife 服务（不开界面）exit=${started?.code} " +
                started?.out?.lineSequence()?.firstOrNull().orEmpty().take(120))
            // 等端口起来；起不来才退回"打开引擎页"（这一步会露出 CarLife 的设置页，属兜底）
            var up = false
            for (i in 0 until 10) {
                delay(500)
                if (isLocalPortListening(CARLIFE_CMD_PORT)) { up = true; break }
            }
            if (!up) {
                log("WAKE: ③ 端口仍未监听 → 退回打开 CarLife 引擎页（会出现它的设置页）")
                val deepLink = "$pkg/com.baidu.carlife.CarlifeActivity"
                val launched = runCatching {
                    ShizukuTools.exec("am start -n $deepLink", 8_000)
                }.getOrNull()
                log("WAKE: ③ 退回 am start exit=${launched?.code}")
            } else {
                log("WAKE: ③ CarLife 端口已就绪（未打开任何界面）")
            }
        }
    }

    @Volatile
    private var appCtx: android.content.Context? = null

    private fun appContext(): android.content.Context? = appCtx

    /**
     * 下车/断开会话时清理本机 CarLife。
     *
     * 为什么必须清：小米 CarLife 断开后经常只关掉 8240 视频端口、却留着 `CarlifeConnectService`
     * 继续占住 7240，内部状态机也不重置——下次上车连入 7240 它永远不回 Phase 1
     * （2026-10-26 的"空壳"现场）。所以断链后：
     *  ① 先发无线断开广播（无 Shizuku 的降级模式也能让 CarLife 自己收尾）；
     *  ② 有 Shizuku 时再 `am force-stop` 掉 **CarLife 引擎包**（`com.baidu.carlife.xiaomi` /
     *     `com.baidu.carlife`），**绝不杀 `com.miui.carlink`（CarWith 外壳）**——
     *     它管着系统级蓝牙/连接状态，杀了反而要在车机上重新配对。
     * @param wasDialMode 本轮是否走主动拨号（是则给 Rust 的 FIN 留 250ms 再强杀）。
     */
    private fun stopLocalCarLife(wasDialMode: Boolean) {
        val ctx = appContext() ?: return
        val configured = SettingsStore.carlifePackage
        for (action in listOf(
            "com.baidu.carlife.Action.StopWireless",
            "com.baidu.carlife.Action.Disconnect",
        )) {
            runCatching {
                ctx.sendBroadcast(android.content.Intent(action).setPackage(configured))
            }
        }
        if (!ShizukuTools.isReady) return
        scope.launch {
            if (wasDialMode) kotlinx.coroutines.delay(250) // 让 Rust 的 PAUSE+FIN 先送到
            for (pkg in listOf("com.baidu.carlife.xiaomi", "com.baidu.carlife")) {
                if (pkg == "com.miui.carlink") continue
                runCatching { ShizukuTools.exec("am force-stop $pkg", 4_000) }
            }
            log("CARLIFE: 已清理后台 CarLife 引擎进程（释放 7240~9340），下次上车重新拉起")
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 握手看门狗：引擎起来后 12s 内没收到 `carlife_active`（14 步握手完成），
     * 就判定本机 CarLife 是"空壳"——7240 在听、但新的 CMD 会话永远不回 Phase 1
     * （2026-10-26 13:13 现场：CarLife 被重复拉起后单实例重启，新实例不回握手）。
     * 处置：force-stop 后重新拉起一次（每轮会话最多一次，避免反复杀）。
     */
    private fun watchCarLifeHandshake() {
        val startedAt = SystemClock.uptimeMillis()
        carLifeActiveAt = 0L
        scope.launch {
            while (sessionLive.get()) {
                kotlinx.coroutines.delay(1_000)
                if (carLifeActiveAt >= startedAt) return@launch
                if (SystemClock.uptimeMillis() - startedAt >= 12_000L) break
            }
            if (!sessionLive.get() || carLifeActiveAt >= startedAt) return@launch
            log("CARLIFE: 12s 内没走完握手（Phase 1 无回应）——判定空壳，force-stop 后重拉一次")
            restartLocalCarLife()
        }
    }

    /**
     * 自动取证：引擎起来 12s 后若视频帧数仍是 0，自己抓一次 CarLife 现场。
     *
     * **为什么不用按钮**：点按钮必须先切回 HyperCar，这一下就会把 CarLife 打成
     * paused（2026-10-26 13:16 现场：DIAG-ACT `topResumedActivity=HyperCar`、
     * `Task{com.baidu.carlife.xiaomi … visible=false}`、编码器 `Qin:0`），
     * 取证动作本身把现场破坏了。这里在"用户还没动手机"的时间点自动采一次。
     */
    private fun armVideoDiag() {
        lastVideoFrames = 0L
        scope.launch {
            kotlinx.coroutines.delay(12_000)
            if (!sessionLive.get() || !streaming) return@launch
            if (lastVideoFrames > 0) {
                log("DIAG: 12s 内已出帧（frames=$lastVideoFrames）——不需要取证")
                return@launch
            }
            log("DIAG: 12s 内视频帧仍为 0——自动取证（不用碰手机）")
            // CarLife 的 APK 里有 MediaProjection/createVirtualDisplay/createInputSurface：
            // 它的视频很可能是"录屏 → 编码"。Android 13+ 每次都要系统授权（或 PROJECT_MEDIA appop），
            // 没授权就只会出现"编码器 STARTED 但 Qin:0"。
            log(
                "DIAG: 提示——CarLife 视频依赖录屏授权（MediaProjection）。若手机上刚弹过" +
                    "「开始录制/投放」请点允许；没有弹窗时可在 Shizuku 里执行 " +
                    "`appops set com.baidu.carlife.xiaomi PROJECT_MEDIA allow`",
            )
            dumpCarLifeDiag()
        }
    }

    /** 强杀本机 CarLife 后重新拉起（只用于空壳自愈）。 */
    private fun restartLocalCarLife() {
        if (!SettingsStore.carlifeEnabled) return
        scope.launch {
            for (pkg in listOf("com.baidu.carlife.xiaomi", "com.baidu.carlife")) {
                runCatching { ShizukuTools.exec("am force-stop $pkg", 4_000) }
            }
            kotlinx.coroutines.delay(600)
            wakeLocalCarLife()
        }
    }

    private fun pickHost(endpoint: CarPlayEndpoint): String? {
        // IPv4 优先 → 其余地址；全空再试网关（cardeck EndpointHosts 同序）
        val ips = endpoint.ipAddresses.filter { it.isNotBlank() }
        return ips.firstOrNull { it.contains('.') && !it.contains(':') }
            ?: ips.firstOrNull()
            ?: endpoint.gateway?.takeIf { it.isNotBlank() }
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
    ): CarPlayEndpoint? = withTimeoutOrNull(ENDPOINT_TIMEOUT_MILLIS) {
        // ⓿ 记住的端点优先：与上次成功时同一个热点就直接试它。
        //    宝马盒子实测就是这样连上的（docs/NEGOTIATION-GAPS.md §14）：它不在连接时
        //    做发现，而是直连记住的 IP:端口。我们照抄这一条，能把"第二次起"的耗时
        //    从几十秒（等 0x4301/扫网段）压到一次 TCP 连接。
        val remembered = SettingsStore.lastAirplayEndpoint
        if (remembered.contains('|')) {
            val (endpointPart, ssidPart) = remembered.split('|', limit = 2)
            // 只认 IPv4 的端点：老版本可能把 fe80:: 写进来（那种地址不带 scope 必然 EINVAL），
            // 读到就丢弃，走正常发现流程。
            val hostCandidate = endpointPart.substringBefore(':').trim()
            val hostUsable = hostCandidate.contains('.') && !hostCandidate.contains(':')
            if (!hostUsable && hostCandidate.isNotEmpty()) {
                log("SESSION: 记忆里的端点 $hostCandidate 不是 IPv4——忽略它，走正常发现")
                runCatching { SettingsStore.lastAirplayEndpoint = "" }
            }
            val parts = endpointPart.split(':')
            val host = if (hostUsable) hostCandidate else ""
            val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            val currentSsid = currentWifiSsid(context)
            val ssidMatches = ssidPart.isBlank() || (currentSsid != null && currentSsid == ssidPart)
            if (host.isNotEmpty() && port in 1..65535 && ssidMatches) {
                val alive = withContext(Dispatchers.IO) {
                    runCatching {
                        Socket().use { s -> s.connect(InetSocketAddress(host, port), REMEMBERED_PROBE_TIMEOUT_MILLIS.toInt()) }
                    }.isSuccess
                }
                if (alive) {
                    log("SESSION: ✓ 上次成功的端点 ${host}:${port} 仍然可连——直接用它（跳过发现）")
                    // 注意：**端点省了，iAP2 不能省**。真 iPhone 在整个 AirPlay 会话期间
                    // 都保持 iAP2 链路，车机也靠它维持 CarPlay 窗口；我们若跳过 iAP2，
                    // ACL 空闲几十秒就被车机拆掉——CarWith 的「选择车机蓝牙」列表里就
                    // 看不到这台车了，连下一步都点不下去（现场反馈 2026-10-25）。
                    // 这里只把回调丢掉：端点已定，0x5703/0x4301 来了也不需要。
                    coordinator.start { ep ->
                        log("IAP2: 后台会话端点回调（端点已定，仅记录）ips=${ep.ipAddresses}")
                        val ip = ep.ipAddresses.firstOrNull { it.contains('.') && !it.contains(':') }
                            ?: ep.ipAddresses.firstOrNull()
                        if (ip != null && ep.airPlayPort > 0) {
                            lateAirplayHost = ip; lateAirplayPort = ep.airPlayPort
                        }
                    }
                    return@withTimeoutOrNull CarPlayEndpoint(
                        ssid = currentSsid,
                        passphrase = null,
                        airPlayPort = port,
                        deviceIdentifier = null,
                        publicKeyHex = null,
                        sourceVersion = null,
                        ipAddresses = listOf(host),
                        gateway = host,
                    )
                } else {
                    log("SESSION: 上次的端点 ${host}:${port} 连不上（换了网段/车机换了地址）——回退到发现流程")
                }
            }
        }

        val callbacks = Channel<CarPlayEndpoint>(Channel.UNLIMITED)
        coordinator.start { endpoint ->
            callbacks.trySend(endpoint)
            // 端口阶段（awaitUsableEndpoint 已返回）再来的 0x4301：记下来供端口循环热切换
            if (sessionLive.get() && airplayHost != null && endpoint.airPlayPort > 0) {
                // 优先 IPv4：0x4301 往往同时给 IPv4 和 fe80:: link-local，
                // 后者不带 scope 直接 connect 会 EINVAL（2026-10-25 21:55 现场）。
                val ip = endpoint.ipAddresses.firstOrNull { it.contains('.') && !it.contains(':') }
                    ?: endpoint.ipAddresses.firstOrNull()
                if (ip != null) {
                    lateAirplayHost = ip
                    lateAirplayPort = endpoint.airPlayPort
                }
            }
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
                val next = withTimeoutOrNull(ENDPOINT_TICK_MILLIS) { callbacks.receive() }
                if (next == null) {
                    ticks++
                    // ① 车机已经来邀请过（GET /ctrl-int/1/connect）→ 直接用邀请方 IP 当端点。
                    //    BMW MGU-55 实测：清单里声明「0x4301 不发」，等它是死等；而邀请一旦
                    //    到达，TCP 对端地址就是车机在热点网段上的地址，AirPlay 控制面在 7000。
                    val inviteIp = advertiser.lastInvitePeerIp
                    if (inviteIp != null) {
                        val base = lastSeen.get()
                        log("SESSION: ✓ 车机已邀请（$inviteIp）——用它当 AirPlay 端点 :7000")
                        usable = CarPlayEndpoint(
                            ssid = base?.ssid,
                            passphrase = base?.passphrase,
                            airPlayPort = 7000,
                            deviceIdentifier = base?.deviceIdentifier,
                            publicKeyHex = base?.publicKeyHex,
                            sourceVersion = base?.sourceVersion,
                            ipAddresses = listOf(inviteIp),
                            gateway = inviteIp,
                            fromInvite = true,
                        )
                        continue
                    }
                    // ① 静默网关试探：手机刚连上（或本来就该连）车机热点时，不用等 0x4301——
                    //    车机的 AirPlay 控制面通常就在热点网关的 7000 端口。
                    val ssid = currentWifiSsid(context)
                    if (!ssid.isNullOrBlank() && ssid !in probedSsid) {
                        val changed = observedSsid != null && observedSsid != ssid
                        val isCarHotspot = ssid == lastSeen.get()?.ssid
                        if (changed || isCarHotspot) {
                            probedSsid.add(ssid)
                            log("WIFI: 检测到 Wi-Fi \"$ssid\"——静默试一次「把网关当车机热点」（:7000）")
                            val probed = probeGatewayAsCarHotspot(context, ssid)
                            if (probed != null) {
                                usable = probed
                                continue
                            }
                            // 网关拿不到（宝马 DIRECT 热点 gw=0.0.0.0）时，改在网段里主动找 —— 这是唯一出路
                            if (ssid == lastSeen.get()?.ssid && localScanned.add(ssid)) {
                                // 先试 mDNS（车机若广播 _airplay._tcp，这一步就能定端点，比扫网段快）
                                val mdnsHere = runCatching {
                                    AirplayServiceProbe(context, ::log).browse(boundNetwork)
                                }.getOrNull()
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
                                    )
                                    continue
                                }
                                val found = discoverAirplayOnLocalSubnet(context, ssid)
                                if (found != null) {
                                    usable = found
                                    continue
                                }
                            }
                        }
                    }
                    if (ssid != null) observedSsid = ssid
                    // ② 每 15s 报一次进度（tick 是 5s，探测要跟得上换网速度）
                    if (ticks % TICKS_PER_PROGRESS == 0) {
                        val seen = lastSeen.get()
                        val waited = ticks * ENDPOINT_TICK_MILLIS / 1000
                        // 区分「车机根本没连进来」与「连进来了但没给凭据」——前者是车机
                        // 侧的问题（iDrive 里没把本机当 CarPlay 手机），后者才是协议问题。
                        if (seen == null && coordinator.sessionStartCount == 0) {
                            log(
                                "SESSION: ${waited}s 内车机一次都没发起 iAP2（ACL 已连但收不到 …cafe 连入）——" +
                                    "这是车机侧状态：请在 iDrive「移动设备」里删除本机后重新配对，" +
                                    "或确认「Apple CarPlay」已为该设备启用（设置里可手动指定目标车机）",
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
                    wifiJoined = true
                    log("SESSION: 先拿到热点凭据（0x5703）——立即入网 + 广播 _carplay-ctrl，再等 0x4301")
                    joinAndBind(context, merged)
                    val ssid = merged.ssid
                    if (ssid != null) {
                        advertiser.ensureForNetwork(ssid, force = true, network = boundNetwork)
                    }
                }
                if (pickHost(merged) != null) {
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

    private fun fail(reason: String) {
        log("SESSION 失败: $reason")
        val hadLink = sessionHadLink
        // 失败即停蓝牙/WiFi 侧（保留 Failed 状态给 UI）：否则 iAP2 出站循环会继续每 3s
        // 断一次车机音频配置文件，还占着 RFCOMM 监听，下一次上车也会被旧会话拖住。
        releaseSessionResources()
        _snapshots.value = _snapshots.value.copy(
            link = LinkState.Failed(reason),
            carLife = CarLifeState.Idle,
        )
        // 失败也走自动重连（同样受 hadLink / 用户断开 / 自动连接开关 / 次数上限约束），
        // 否则"车机主动关控制通道"这类场景会一直停在 Failed 等人点。
        scheduleAutoReconnect("失败: $reason", hadLink)
    }

    private fun push(link: LinkState? = null, carLife: CarLifeState? = null) {
        _snapshots.update { s ->
            s.copy(
                link = link ?: s.link,
                carLife = carLife ?: s.carLife,
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
        val stamp = logTimeFormat.get().format(java.util.Date())
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
     * 诊断：把当前所有 display 打进行日志（判断有没有可投的副屏/虚拟屏）。
     *
     * 用途：排查副屏/虚拟屏（历史上的「CarLife 启动屏」设置已删除，这里只留诊断能力）——
     * 在副屏（如 10）上跑。这里直接读 `dumpsys display`，把 id/名称/尺寸/flags 打出来，
     * 就不用连电脑查了。仅当有 Shizuku 时可用（`dumpsys` 需要 shell 权限）。
     */
    fun dumpDisplays() {
        if (!ShizukuTools.isReady) {
            log("DISP: 需要 Shizuku 才能读 display 列表（dumpsys display）——先授权再点一次")
            return
        }
        scope.launch {
            val r = runCatching {
                ShizukuTools.exec(
                    "dumpsys display | grep -E 'mDisplayId=|DisplayDeviceInfo\\{|DisplayInfo\\{|mDisplayName=' | head -60",
                    10_000,
                )
            }.getOrNull()
            if (r == null || r.code != 0) {
                log("DISP: dumpsys display 失败 exit=${r?.code} ${r?.out?.take(120).orEmpty()}")
                return@launch
            }
            val lines = r.out
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(40)
                .toList()
            // 只认出 id 个数，先给一行结论，细节跟在后面
            val ids = lines.mapNotNull { Regex("mDisplayId=(\\d+)").find(it)?.groupValues?.get(1) }
                .distinct()
            log(
                "DISP: 共 ${lines.size} 行，含 display id = " +
                    if (ids.isEmpty()) "（没解析到，往下看原始行）" else ids.joinToString(", ") +
                        "；主屏=0，要投副屏就填上面出现的非 0 id",
            )
            lines.forEach { log("DISP: $it") }
        }
    }

    /**
     * 诊断：抓 CarLife 的"现场"，用来定位「握手全过、8240 一帧没有」这类问题。
     *
     * 抓三样东西（都只读，不改行为）：
     *  ① Activity 是否真的在前台（`dumpsys activity activities`）；
     *  ② 它有没有 Surface（`dumpsys SurfaceFlinger --list`——没有 Surface 就没有编码输入）；
     *  ③ logcat 里 CarLife / MediaCodec / `video-debug` 相关最后几十行（编码器有没有输出）。
     *
     * 只在有 Shizuku 时可用；输出截断到 40 行、每行 200 字符，避免把环形日志刷满。
     */
    fun dumpCarLifeDiag() {
        if (!ShizukuTools.isReady) {
            log("DIAG: 需要 Shizuku 才能抓 CarLife 现场（dumpsys/logcat）——先授权再点一次")
            return
        }
        scope.launch(Dispatchers.IO) {
            suspend fun run(prefix: String, cmd: String, maxLines: Int) {
                val r = runCatching { ShizukuTools.exec(cmd, 12_000) }.getOrNull()
                if (r == null) {
                    log("$prefix: 执行失败（无输出）")
                    return
                }
                val lines = r.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                log("$prefix: exit=${r.code}，${lines.size} 行")
                lines.take(maxLines).forEach { log("$prefix ${it.take(200)}") }
            }
            log("DIAG: ==== CarLife 现场开始 ====")
            run(
                "DIAG-ACT",
                "dumpsys activity activities | grep -iE 'carlife|mResumedActivity|topResumedActivity' | head -6",
                6,
            )
            run(
                "DIAG-SF",
                "dumpsys SurfaceFlinger --list | grep -iE 'carlife|SurfaceView|Video' | head -10",
                10,
            )
            run(
                "DIAG-ENC",
                "logcat -d -t 2000 | grep -iE 'video-debug' | tail -6",
                6,
            )
            run(
                "DIAG-LOG",
                "logcat -d -t 3000 | grep -iE 'carlife|video-debug|avcE_|MediaCodec' | tail -30",
                30,
            )
            log("DIAG: ==== CarLife 现场结束 ====")
            // 顺手探一下 CarLife 的投屏 AIDL（CarWith 就是靠它驱动投屏的）
            probeCarLifeCastBinder()
            // 顺便把 register/getDisplayController/startDisplay 这套也跑一遍：
            // 不需要先连车机，点一次「CarLife」就能看到结果。
            startCarLifeDisplay()
        }
    }

    /**
     * 探测 CarLife 的投屏 AIDL 通道（`com.baidu.carlife.mixing.aidl.ICarLifeCastManager`）。
     *
     * 逆向 CarLife APK 得到：
     * ```
     * interface ICarLifeCastManager {
     *     Bundle invokeMethod(in Intent);   // code 1
     *     void   prepareCast(in CastConfig);// code 2
     *     void   setCastCallback(ICarLifeCastCallback); // code 3
     *     void   setSystemAudioCallback(ISystemAudioCallback); // code 4
     *     void   stopCast();                // code 5
     * }
     * class CastConfig { int width; int height; int dpi; Surface surface; }  // Parcelable
     * ```
     * 也就是说 **调用方提供一个 Surface，CarLife 把车机画面渲染进去**——CarWith 就是这么做的。
     * 如果这个 binder 能拿到，我们就能把 CastConfig.surface 设成 MediaCodec 的输入 Surface，
     * 直接拿到 CarLife 的真实车机画面（不再需要 CarWith，也不需要 root）。
     */
    /**
     * 让 CarLife 真正开始投屏：走它自己的 `ICarLifeManager` AIDL（CarWith 就是这么做的）。
     *
     * descriptor 与 transact code 全部来自 CarLife APK 的 Stub 常量：
     * ```
     * com.baidu.carlifelink.listener.ICarLifeManager      （由导出的 RemotePresentationService 提供）
     *     register(String): boolean          code 1
     *     unregister(String): boolean        code 2
     *     getDisplayController(String): IBinder code 3
     * com.baidu.carlifelink.listener.IDisplayController
     *     startDisplay(String)               code 1
     *     stopDisplay(String)                code 2
     *     currentDisplayPage(String): String code 3
     * ```
     * 推测：`startDisplay` 就是"开始投屏"的开关，CarLife 会创建虚拟屏/启动编码；
     * 如果此时我们的引擎正以 HU 身份连着它（7240/8240），帧应该就会从 8240 出来。
     */
    /** 供 adb 广播（DebugProbeReceiver）触发的投屏探测入口。 */
    fun startCarLifeDisplayProbe() = startCarLifeDisplay()

    /** 探测日志：同时进 App 日志与 /sdcard/Android/data/<pkg>/files/cast_probe.txt（adb 可直接读）。 */
    private fun probeLog(msg: String) {
        log(msg)
        runCatching {
            val ctx = appCtx ?: return
            java.io.File(ctx.getExternalFilesDir(null), "cast_probe.txt").appendText(msg + "\n")
        }
    }

    private fun startCarLifeDisplay() {
        if (!SettingsStore.carlifeEnabled) return
        val ctx = appContext() ?: return
        // register(tag) 的 tag 不一定是任意字符串：CarLife 很可能拿它当"客户端标识"校验。
        // 先按最可能的值逐个试，命中（返回 1）才继续 getDisplayController/startDisplay。
        // RemoteClientConfig 里只认这几个 client key（见 dex 常量），其余一律 false。
        val candidates = listOf(
            // register(tag) 实际比的是 RemoteClient.pkgName（不是 key），
            // RemoteClientConfig.supportRemoteClients 里只有这三个 pkgName：
            //   video_client.dev / com.baidu.BaiduMap / com.szats.breakthrough
            // 且 getClients() **不检查是否已安装**，所以没装百度地图也能命中。
            "com.baidu.BaiduMap", "com.szats.breakthrough", "video_client.dev",
            "baidu_map", "map", "mini_map", "baidu_mini_map", "carlife_rcc_display",
            ctx.packageName, "com.miui.carlink", "com.baidu.carlife",
        )

        fun register(svc: android.os.IBinder, tag: String): Int = runCatching {
            val d = android.os.Parcel.obtain()
            val r = android.os.Parcel.obtain()
            try {
                d.writeInterfaceToken("com.baidu.carlifelink.listener.ICarLifeManager")
                d.writeString(tag)
                svc.transact(1, d, r, 0)
                r.readException()
                if (r.dataSize() >= 4) r.readInt() else -1
            } finally {
                d.recycle()
                r.recycle()
            }
        }.onFailure {
            log("CAST: register(\"$tag\") 抛异常 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(-1)

        fun displayController(svc: android.os.IBinder, tag: String): android.os.IBinder? = runCatching {
            val d = android.os.Parcel.obtain()
            val r = android.os.Parcel.obtain()
            try {
                d.writeInterfaceToken("com.baidu.carlifelink.listener.ICarLifeManager")
                d.writeString(tag)
                svc.transact(3, d, r, 0)
                r.readException()
                r.readStrongBinder()
            } finally {
                d.recycle()
                r.recycle()
            }
        }.getOrNull()

        fun startDisplay(ctrl: android.os.IBinder, tag: String): Boolean = runCatching {
            val d = android.os.Parcel.obtain()
            val r = android.os.Parcel.obtain()
            try {
                d.writeInterfaceToken("com.baidu.carlifelink.listener.IDisplayController")
                d.writeString(tag)
                ctrl.transact(1, d, r, 0)
                r.readException()
                true
            } finally {
                d.recycle()
                r.recycle()
            }
        }.getOrDefault(false)

        runCatching {
            val intent = android.content.Intent().setComponent(
                android.content.ComponentName(
                    "com.baidu.carlife.xiaomi",
                    "com.baidu.carlife.complex.RemotePresentationService",
                ),
            )
            ctx.bindService(
                intent,
                object : android.content.ServiceConnection {
                    override fun onServiceConnected(
                        name: android.content.ComponentName?,
                        service: android.os.IBinder?,
                    ) {
                        if (service == null) {
                            log("CAST: RemotePresentationService 绑定成功但 binder 为 null")
                            return
                        }
                        try {
                            for (tag in candidates) {
                                val reg = register(service, tag)
                                probeLog("CAST: register(\"$tag\") → $reg")
                                if (reg != 1) continue
                                val ctrl = displayController(service, tag)
                                probeLog("CAST: getDisplayController(\"$tag\") = $ctrl")
                                if (ctrl == null) continue
                                castDisplayController = ctrl
                                // startDisplay 的参数可能是 client 的 displayName（key）而不是 pkgName：
                                // 两个都试，各看一次 displays。
                                val keys = when (tag) {
                                    "com.baidu.BaiduMap" -> listOf("baidu_map", "baidu_mini_map")
                                    "video_client.dev" -> listOf("map", "mini_map")
                                    "com.szats.breakthrough" -> listOf("carlife_rcc_display")
                                    else -> emptyList()
                                }
                                for (arg in listOf(tag) + keys) {
                                    val started = startDisplay(ctrl, arg)
                                    val dm = ctx.getSystemService(
                                        android.hardware.display.DisplayManager::class.java,
                                    )
                                    val displays = dm?.displays
                                        ?.joinToString(", ") { "${it.displayId}:${it.name}" }
                                        ?: "-"
                                    probeLog("CAST: startDisplay(\"$arg\") = $started，displays=$displays")
                                }
                                return // CarLife 同一时刻只接受一个远程客户端
                            }
                            log("CAST: 候选 tag 全部注册失败——枚举 CarLife 的导出服务，找别的 AIDL 入口")
                            probeCarLifeExportedServices()
                        } catch (t: Throwable) {
                            log("CAST: 调用异常 ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        log("CAST: RemotePresentationService 断开")
                        castDisplayController = null
                    }
                },
                android.content.Context.BIND_AUTO_CREATE,
            )
        }.onFailure { log("CAST: bindService 异常 ${it.message}") }
    }

    /**
     * 枚举 CarLife 的**导出**服务，逐个 bind 并打印 binder 的 interfaceDescriptor。
     *
     * 目的：找出哪个组件暴露了 `com.baidu.carlife.mixing.aidl.ICarLifeCastManager`
     * （带 `prepareCast(CastConfig{surface})`，能把 CarLife 的画面渲染进我们给的 Surface）。
     */
    private fun probeCarLifeExportedServices() {
        val ctx = appContext() ?: return
        val pm = ctx.packageManager
        val services = runCatching {
            pm.getPackageInfo("com.baidu.carlife.xiaomi", android.content.pm.PackageManager.GET_SERVICES)
                .services
        }.getOrNull().orEmpty()
        log("CAST-SVC: CarLife 共 ${services.size} 个 service，导出中…")
        for (s in services) {
            if (!s.exported) continue
            val cls = s.name
            log("CAST-SVC: $cls permission=${s.permission ?: "-"}")
            runCatching {
                var conn: android.content.ServiceConnection? = null
                conn = object : android.content.ServiceConnection {
                    override fun onServiceConnected(
                        name: android.content.ComponentName?,
                        binder: android.os.IBinder?,
                    ) {
                        val desc = runCatching { binder?.interfaceDescriptor }.getOrNull()
                        val impl = binder?.javaClass?.name ?: "null"
                        log("CAST-SVC: $cls → descriptor=$desc binder=$impl")
                        runCatching { conn?.let { ctx.unbindService(it) } }
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        log("CAST-SVC: $cls 断开")
                    }
                }
                val intent = android.content.Intent().setComponent(
                    android.content.ComponentName("com.baidu.carlife.xiaomi", cls),
                )
                ctx.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)
            }.onFailure { log("CAST-SVC: 绑定 $cls 失败 ${it.message}") }
        }
    }

    private fun probeCarLifeCastBinder() {
        val ctx = appContext() ?: return
        val components = listOf(
            "com.baidu.carlife.service.CarlifeConnectService",
            "com.baidu.carlife.complex.RemotePresentationService",
        )
        for (cls in components) {
            runCatching {
                val intent = android.content.Intent().setComponent(
                    android.content.ComponentName("com.baidu.carlife.xiaomi", cls),
                )
                val conn = object : android.content.ServiceConnection {
                    override fun onServiceConnected(
                        name: android.content.ComponentName?,
                        binder: android.os.IBinder?,
                    ) {
                        val desc = runCatching { binder?.interfaceDescriptor }.getOrNull()
                        val binderClass = binder?.javaClass?.name ?: "null"
                        log("CAST: ✓ 绑定 $cls → binder=$binderClass descriptor=$desc")
                        castBinder = binder
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        log("CAST: 断开 $cls")
                        castBinder = null
                    }
                }
                val ok = ctx.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)
                log("CAST: bindService($cls) = $ok")
            }.onFailure { log("CAST: 绑定 $cls 异常 ${it.message}") }
        }
    }

    /**
     * 导出日志到 `下载/HyperCar/`（MediaStore，免存储权限，文件管理器/微信可见）。
     * 完成后路径写进快照 [SessionSnapshot.lastExportPath] 并落一条日志（诊断页最上方可见）。
     * 必须在后台线程调用（内部已切 IO）。
     */
    fun exportLogs() {
        scope.launch {
            val logs = _snapshots.value.recentLogs
            if (logs.isEmpty()) {
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
            appendLine("# 说明：以下时间戳为本机时间；日志共 ${logs.size} 行")
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
    /** 网段扫描时单个 TCP 探测的超时（毫秒）：要快，宁可漏也不能拖住握手。 */
    private const val LOCAL_PROBE_TIMEOUT_MILLIS = 250L
    /** 复用「上次成功的端点」时的探测超时：连通就用，不通再走完整发现。 */
    private const val REMEMBERED_PROBE_TIMEOUT_MILLIS = 600L
    /** 全段扫描的并发块大小：一次最多同时建这么多 socket（太多会被 fd 上限/AP 客户端表打回）。 */
    private const val PROBE_CHUNK_SIZE = 128
    /** 全段扫描单个地址的超时：没人应答的地址多数会立刻 EHOSTUNREACH，超时只是兜底。 */
    private const val FULL_SWEEP_TIMEOUT_MILLIS = 150L
    /** 全段扫描地址总数上限（/20 = 4094，留点余量；更宽的网段也不会扫爆）。 */
    private const val TOTAL_SWEEP_MAX = 8192

    /** 停止时等在途连接协程退出的上限（超过就先拆，连接侧另有 sessionLive 兜底）。 */
    private const val JOIN_TIMEOUT_MILLIS = 5_000L

    /** 掉线自动重连：退避序列（ms）与最大连续尝试次数。 */
    private val RECONNECT_BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    private const val MAX_RECONNECT_ATTEMPTS = 6

    /** CarLife CMD 端口（MD 监听时我们改拨它）与探测窗口。 */
    private const val CARLIFE_CMD_PORT = 7240
    private const val CARLIFE_PROBE_TIMEOUT_MILLIS = 2_500L
    private const val CARLIFE_VIDEO_PORT = 8240

    /** 单个候选网络上连 AirPlay 控制端口的超时（多网络轮试，单次不能给太久）。 */
    private const val CONTROL_CONNECT_TIMEOUT_MILLIS = 2_500L

    /** 连 AirPlay 控制端口的重试窗口（接收端常在 invite 之后才开端口，一轮失败不能当死）。 */
    private const val AIRPLAY_CONNECT_WINDOW_MILLIS = 8_000L

    /** 重试轮里的单次连接超时（比首轮短，避免窗口被几轮超时吃光）。 */
    private const val AIRPLAY_CONNECT_RETRY_TIMEOUT_MILLIS = 1_200L

    /** 环形日志保留行数（诊断页展示 + 导出）。 */
    private const val LOG_KEEP_LINES = 300

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
    private const val DISK_LOG_TAIL_LINES = 120
}














