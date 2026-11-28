package io.github.hypercar.iap2

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.hypercar.bluetooth.BtProfiles
import io.github.hypercar.bluetooth.isBmwBluetoothName
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class RfcommStamp(val attempt: Long, val uptime: Long)

/**
 * iAP2 蓝牙 RFCOMM 出站客户端 + 入站服务端（合并实现）。
 *
 * 真实无线 CarPlay 建链方向（cardeck 真车实测）：
 * - 手机与车机完成系统级蓝牙配对后，由**手机主动连入**车机 SDP 广播的配件端服务
 *   （UUID …decacaff，奥迪 MIB3 SDP 实测存在）；车机未就绪时连接会持续失败。
 * - BMW iD8.5/MGU-86 实测相反：车机**主动连入**手机监听的服务（…decacafe），
 *   我方出站每 3s 失败——所以两条路径必须并存，谁先通谁跑会话。
 *
 * 三条硬约束（cardeck 实测教训）：
 *  1. **只配对、不连接**：车机处于 HFP/A2DP 已连接状态时不开放 iAP2 服务窗口——
 *     出站连接前先断目标设备的音频/通话配置文件（且**只对声明 iAP2 服务或用户
 *     选定的设备做**，别误伤耳机）。
 *  2. **出站候选只保留已配对且声明 iAP2 服务的设备**（SDP 缓存 …caff 判定），
 *     否则未指定目标时会去连蓝牙耳机、白断它们的 HFP/A2DP。
 *  3. **世代号防线程泄漏**：start()/stop() 都递增世代；卡在阻塞 connect() 里的
 *     线程被 stop() 解除阻塞（关闭 connecting socket）后，因世代不符直接退出——
 *     防止「stop 后线程残活、新旧两代并发连车机」（多僵尸线程并发 RFCOMM 会让
 *     车机频繁拆链）。connect 前登记 socket，保证 stop 能打断阻塞的 connect。
 */
class Iap2Bluetooth(
    context: Context,
    /** 用户选定车机 MAC；null = 自动（全部已配对且声明 iAP2 服务的设备）。 */
    private val targetProvider: () -> String?,
    /** 本机真实蓝牙 MAC（会话里 0x4300/0x4E0C/0x4E0E 用）。 */
    private val bluetoothMacProvider: () -> String,
    private val profile: io.github.hypercar.headunit.HeadUnitProfile,
    private val canStartSession: () -> Boolean,
    private val preferredProvider: () -> String? = { null },
    private val log: (String) -> Unit,
    /** 每次拿到/更新端点即回调（0x5703 凭据与 0x4301 端点都会触发，可能多次）。 */
    private val onEndpoint: (Iap2EndpointProposal) -> Boolean,
    /** 会话结束（拿到端点后链路断开，或中途失败）——上层用来收尾/重排。 */
    private val onSessionEnded: (String) -> Unit,
    private val onInboundSession: (String?, RfcommStamp, RfcommStamp) -> Unit = { _, _, _ -> },
    private val captureRfcommStamp: (Long) -> RfcommStamp = { RfcommStamp(0L, it) },
    private val wifiCredentialHistory: () -> Iap2WifiCredentialHistory = { Iap2WifiCredentialHistory() },
) {
    private val profileId = profile.protocolProfileId

    companion object {
        private val bluetoothIds = AtomicLong()
        private val HANDSHAKE_IDS = java.util.concurrent.atomic.AtomicLong(0)
        /** 手机侧（我们监听）的 iAP2 服务 UUID；配件端（车机监听）是 …decacaff。 */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        /** cafe 记录的 SDP 服务名。车机按 UUID 查找，这个名字不参与其它 Profile。 */
        private const val SDP_NAME = "Wireless iAP"
        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val BMW_INBOUND_HANDOFF_MILLIS = 1_200L
        private val cafeHandoffTimer = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "iap2-bmw-handoff-timer").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val rfcommCleanup = Executors.newCachedThreadPool { task ->
            Thread(task, "iap2-rfcomm-cleanup").apply { isDaemon = true }
        }
        private val admissionLogs = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
            { task -> Thread(task, "iap2-admission-log").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /**
         * 首轮出站前的「等车机主动连入」宽限：BMW 是车机拨我们（…cafe）。
         * 若我们先走「只配对、不连接」（断它的 HFP/A2DP），车机往往把整条 ACL 也拽掉，
         * 于是系统广播 ACL 断开 → 上层拆会话 → 再连、再断（2026-10-24 实车 10:55~11:01
         * 那串「蓝牙已连接——已断开 / 车机蓝牙连上 / 断开」刷屏就是这个自激环）。
         * 只等**首轮**：等到了就不碰配置文件；等不到照旧走 outbound（Audi 是这条）。
         */
        private const val INBOUND_GRACE_MILLIS = 8_000L

        /** SDP 服务探测等待窗（fetchUuidsWithSdp → ACTION_UUID 广播）。 */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L

        /** 无本车凭证才附加首次说明；已有凭证时不要把这行读成首次配对。 */
        internal fun handshakeBeginLine(
            direction: String,
            target: String,
            identityPresent: Boolean,
            receiverCredentialPresent: Boolean,
        ): String {
            val line = "BEGIN direction=$direction target=$target identity=$identityPresent receiverCredential=$receiverCredentialPresent"
            return if (receiverCredentialPresent) line
            else "$line；无本车凭证=首次或未知，不主动断系统配置文件"
        }
    }

    private val appContext = context.applicationContext

    private val bluetoothId = bluetoothIds.incrementAndGet()
    private val generation = AtomicLong(0)

    @Volatile
    private var running = false

    /** 当前会话占用的 socket（stop/notifyBluetoothLost 关它来打断阻塞读写）。 */
    private val rfcomm = RfcommConnections<BluetoothSocket>()
    private var bmwProbe: BmwCaffProbe? = null
    private var activeCaffSocket: RfcommTransport<BluetoothSocket>? = null
    private var activeCaffAttempt = 0L
    @Volatile private var activePrimaryInbound: RfcommTransport<BluetoothSocket>? = null
    private val cafeReservations = BmwCafeReservations<RfcommTransport<BluetoothSocket>>(::cancelAdmittedSocket)
    private var legacyReplacement: RfcommTransport<BluetoothSocket>? = null
    @Volatile private var lastInboundAdmission = -1L

    private class AcceptWorker(
        val generation: Long,
        val listener: RecoveringListener<BluetoothServerSocket, BluetoothSocket>,
        val thread: Thread,
    ) {
        val accepted = AtomicLong()
        val rejected = AtomicLong()
        @Volatile var lastAcceptAt = 0L
        @Volatile var lastWorkerAt = 0L
    }

    @Volatile private var acceptWorker: AcceptWorker? = null
    private data class HeldInbound(val transport: RfcommTransport<BluetoothSocket>, val worker: AcceptWorker, val tag: String,
                                   val arrival: RfcommStamp, val ticket: Long)
    private class InboundPreparation(val attempt: Long, val peer: String?) {
        var held: HeldInbound? = null
    }
    private var inboundPreparation: InboundPreparation? = null
    private val carPlayMarkers = mutableMapOf<CarPlayBluetoothMarker.Service, CarPlayBluetoothMarker>()
    private val markerLock = Any()
    @Volatile private var markerEnabled = false

    @Volatile
    private var clientThread: Thread? = null

    /**
     * The admission monitor publishes one RFCOMM owner. An old worker releases only its own
     * reference, including when a bounded handoff has already passed ownership to a successor.
     */
    private val sessionOwner = AtomicReference<RfcommTransport<BluetoothSocket>?>()
    // EOF can leave an accepted route usable. A later admission retires the old producer.
    private var previousSession: RfcommTransport<BluetoothSocket>? = null
    private val sessions = Iap2SessionGate()
    fun diagnosticSummary(): String = diagnosticSummary(acceptWorker)

    private fun diagnosticSummary(worker: AcceptWorker?): String {
        val now = SystemClock.elapsedRealtime()
        fun age(at: Long?) = if (at == null || at == 0L) "none" else "${now - at}ms"
        val sdp = worker?.listener?.diagnostics
        val acceptThreadState = worker?.thread?.state
        val gate = sessions.diagnostics()
        val outboundPaused = sessions.outboundPausedForWifi
        val sessionRunning = isSessionActive
        val active = worker != null && worker === acceptWorker && worker.generation == generation.get() && running
        return "bluetooth_id=$bluetoothId generation=${worker?.generation ?: "none"} running=$active " +
            "registered=${active && worker?.listener?.isRegistered == true} " +
            "sdp_scope=local sdp_registrations=${sdp?.registrations ?: 0} " +
            "sdp_register_failures=${sdp?.registerFailures ?: 0} rfcomm_accept_failures=${sdp?.acceptFailures ?: 0} " +
            "last_listener_failure=${sdp?.lastFailure?.name?.lowercase() ?: "none"} " +
            "acceptThread=$acceptThreadState alive=${acceptThreadState != null && acceptThreadState != Thread.State.NEW && acceptThreadState != Thread.State.TERMINATED} " +
            (if (active) "$gate outboundPausedForWifi=$outboundPaused active=$sessionRunning " else "") +
            "accepted=${worker?.accepted?.get() ?: 0} rejected=${worker?.rejected?.get() ?: 0} " +
            "acceptAge=${age(worker?.lastAcceptAt)} workerAge=${age(worker?.lastWorkerAt)}"
    }

    private fun rejectSocket(transport: RfcommTransport<BluetoothSocket>, tag: String, reason: String,
                             worker: AcceptWorker?, handoff: BmwCaffHandoff<RfcommTransport<BluetoothSocket>>? = null) {
        val socket = transport.socket
        worker?.rejected?.incrementAndGet()
        if (handoff == null) cancelAdmittedSocket(transport) else handoff.cancel()
        logAdmission("BT-REJECT: entry=$tag reason=$reason peer=${safeAddress(socket.remoteDevice)} ${diagnosticSummary(worker)}")
    }

    private fun logAdmission(line: String) { admissionLogs.execute { log(line) } }

    private fun cancelAdmittedSocket(transport: RfcommTransport<BluetoothSocket>) {
        transport.markLocalStop()
        rfcommCleanup.execute { transport.cancelSocket() }
    }

    private fun closeAfterAdmission(snapshot: RfcommClose, afterClose: () -> Unit = {}) {
        snapshot.markBeforeDeferredClose()
        rfcommCleanup.execute {
            snapshot.close()
            afterClose()
        }
    }

    /** This runs before the admission monitor, so a blocked handler is observable too. */
    private fun acceptSocket(socket: BluetoothSocket, worker: AcceptWorker, tag: String) {
        val acceptedAt = SystemClock.uptimeMillis()
        val transport = rfcomm.register(socket)
        val acceptedTicket = sessions.inboundTicket()
        val current = running && generation.get() == worker.generation && acceptWorker === worker
        val peerAddress = safeAddress(socket.remoteDevice)
        val admittable = current && sessions.admitInbound(acceptedTicket) != null &&
            io.github.hypercar.bluetooth.CarTargetPolicy.acceptsIncoming(targetProvider(), peerAddress)
        val arrival = if (current) captureRfcommStamp(acceptedAt) else RfcommStamp(0L, acceptedAt)
        if (admittable) {
            lastInboundAdmission = acceptedAt
        }
        val elapsedAt = SystemClock.elapsedRealtime()
        worker.accepted.incrementAndGet()
        worker.lastAcceptAt = elapsedAt
        logAdmission("BT-ACCEPT: entry=$tag attempt=${arrival.attempt} acceptedUptimeMs=${arrival.uptime} " +
            "elapsedMs=$elapsedAt epochMs=${System.currentTimeMillis()} listenerGeneration=${worker.generation} " +
            "acceptTicket=${acceptedTicket ?: "none"} " +
            "peer=$peerAddress ${diagnosticSummary(worker)}")
        handleIncomingSocket(transport, worker, tag, arrival, acceptedTicket)
    }

    @Synchronized internal fun prepareInbound(attempt: Long, peer: String?) {
        if (!running || sessions.ticket() != null || inboundPreparation?.attempt == attempt) return
        inboundPreparation?.let { cancelPreparedInbound(it.attempt) }
        inboundPreparation = InboundPreparation(attempt, peer)
        sessions.prepareInbound()
    }

    @Synchronized internal fun cancelPreparedInbound(attempt: Long) {
        val preparation = inboundPreparation?.takeIf { it.attempt == attempt } ?: return
        inboundPreparation = null
        sessions.setEnabled(false)
        preparation.held?.let { rejectSocket(it.transport, it.tag, "inbound-preparation-ended", it.worker) }
    }

    @Synchronized fun setSessionEnabled(enabled: Boolean, reason: String = "caller", closeCurrent: Boolean = true,
                                        inboundAttempt: Long? = null) {
        val previous = sessions.diagnostics()
        val preparation = inboundPreparation
        val promote = enabled && preparation != null && preparation.attempt == inboundAttempt
        if (promote) inboundPreparation = null
        else preparation?.let { cancelPreparedInbound(it.attempt) }
        sessions.setEnabled(enabled)
        if (!enabled) {
            cancelBmwProbe("sessions disabled")
            cafeReservations.clear()
            cancelLegacyReplacement()
        }
        if (previous != sessions.diagnostics()) logAdmission("BT-GATE: reason=$reason before=[$previous] after=[${sessions.diagnostics()}] generation=${generation.get()}")
        if (!enabled && closeCurrent) dropCurrentSession()
        if (promote) preparation?.held?.let {
            handleIncomingSocket(it.transport, it.worker, it.tag, it.arrival, it.ticket, promoted = true)
        }
    }

    /** 「蓝牙已连接→已断开」日志节流（车机可能反复自动重连，避免刷屏）。 */
    @Volatile
    private var lastProfileLogAt = 0L

    @Volatile
    private var lastFailureLogAt = 0L

    /** 「档案不要求断配置文件」只打一行日志。 */
    @Volatile
    private var profileDropSkippedLogged = false

    /**
     * 本轮会话是否已拿到「可用凭据/端点」。false = 还在等 0x5703/0x4301 的空转轮次，
     * 这种轮次**允许被车机的新连入顶替**（见 acceptLoop），这样既不用主动拆链
     * （拆链会让车机收起 CarPlay 图标），也不会把车机的下一轮挡在门外。
     */
    @Volatile
    private var sessionEndpoints = Iap2RfcommEndpointState()

    /** 「是否提供 iAP2 服务」结论缓存（地址→结论）：SDP 查询不便宜，勿每 3s 重查。 */
    internal data class ServiceResult(val revision: Long, val available: Boolean, val checkedAt: Long) {
        fun canReuse(currentRevision: Long, now: Long): Boolean =
            revision == currentRevision && (available || now - checkedAt in 0 until 10_000L)

        companion object {
            fun fromProbe(revision: Long, result: String, cached: Boolean, checkedAt: Long): ServiceResult =
                ServiceResult(revision, result == "present" || cached, checkedAt)
        }
    }
    private val iap2ServiceCache = ConcurrentHashMap<String, ServiceResult>()
    private val targetRevision = java.util.concurrent.atomic.AtomicLong()

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    @Synchronized fun setOutboundPausedForWifi(paused: Boolean) {
        if (sessions.outboundPausedForWifi == paused) return
        sessions.outboundPausedForWifi = paused
        if (paused) cancelBmwProbe("Wi-Fi handoff")
        logAdmission("BT-HANDOFF: outboundPausedForWifi=$paused generation=${generation.get()}")
    }

    val isRunning: Boolean get() = running

    /** 当前是否有会话在跑（上层诊断用）。 */
    val activePeerAddress: String?
        get() = rfcomm.session.get()?.let { runCatching { safeAddress(it.socket.remoteDevice) }.getOrNull() }

    val isSessionActive: Boolean get() = sessionOwner.get() != null
    val isListening: Boolean get() = running && acceptWorker?.listener?.isRegistered == true
    val rfcommAccepting: Boolean get() = acceptWorker?.let {
        it.listener.isRegistered && it.thread.isAlive && sessions.inboundTicket() != null
    } == true

    /** 只变更额外标记，不重建主服务、不打断当前会话。 */
    fun setCarPlayMarkerEnabled(enabled: Boolean) = synchronized(markerLock) {
        markerEnabled = enabled
        if (!enabled || !running) {
            closeMarkers()
            return
        }
        startMarkerWhenListening(generation.get())
    }

    private fun startMarkerWhenListening(epoch: Long): Unit = synchronized(markerLock) {
        if (generation.get() != epoch || !markerEnabled) return
        if (!isListening) {
            if (CarPlayBluetoothMarker.usesDiscoveryMarker(profileId)) closeMarkers()
            return
        }
        val adapter = adapterOrNull() ?: return
        val worker = acceptWorker ?: return
        val required = CarPlayBluetoothMarker.registrationPrefix(CarPlayBluetoothMarker.forProfile(profileId)) {
            carPlayMarkers[it]?.isRegistered == true
        }
        for (service in carPlayMarkers.keys - required.toSet()) carPlayMarkers.remove(service)?.close()
        for (service in required) {
            if (service in carPlayMarkers) continue
            val marker = CarPlayBluetoothMarker(
                adapter = adapter,
                service = service,
                ownerAlive = { running && markerEnabled && generation.get() == epoch },
                primaryReady = { isListening },
                onRegistrationChanged = { startMarkerWhenListening(epoch) },
                onConnection = { socket -> acceptSocket(socket, worker, "BT-MARKER") },
                log = { message -> log("$message bluetooth_id=$bluetoothId generation=$epoch") },
            )
            carPlayMarkers[service] = marker
            marker.start()
        }
    }

    private fun closeMarkers() {
        carPlayMarkers.values.forEach { it.close() }
        carPlayMarkers.clear()
    }

    /** 本次 start() 以来成功建过几轮 iAP2 会话（0 = 车机从未连入/从未连上，诊断用）。 */
    val sessionStartCount: Int get() = sessionStarts.get()

    private val sessionStarts = java.util.concurrent.atomic.AtomicInteger(0)

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** 启动监听（入站）+ 出站循环。返回 false 表示蓝牙不可用/缺权限。 */
    @SuppressLint("MissingPermission")
    @Synchronized fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter = adapterOrNull()
        if (adapter == null) {
            log("BT: 蓝牙不可用")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        targetRevision.incrementAndGet()
        iap2ServiceCache.clear()
        skippedNoService.clear()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT: 启动——监听 $SDP_NAME（…cafe，等车机连入）" +
                (if (profile.allowOutboundIap2) " + 出站尝试车机 iAP2 服务（…caff）" else "，仅被动监听") +
                if (target != null) {
                    "，目标：$target（只连/只收该设备）"
                } else {
                    if (profile.allowOutboundIap2) "（自动：只尝试已配对且声明 iAP2 服务的设备）" else "（等车机发起，不操作其它已配对设备）"
                },
        )
        val lastErrorAt = mutableMapOf<RecoveringListener.FailureStage, Long>()
        var lastRegistrationAt: Long? = null
        val newListener = RecoveringListener(
            ownerAlive = { running && generation.get() == myGeneration },
            open = {
                try {
                    if (!adapter.isEnabled) throw IOException("蓝牙未开启，等待系统恢复")
                    adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID)
                } catch (error: SecurityException) {
                    throw IOException("蓝牙权限不可用", error)
                }
            },
            accept = { socket: BluetoothServerSocket -> socket.accept() },
            onRegistered = {
                val now = SystemClock.elapsedRealtime()
                if (lastRegistrationAt == null || now - lastRegistrationAt!! >= 30_000) {
                    log("BT: SDP 监听已注册 bluetooth_id=$bluetoothId generation=$myGeneration " +
                        "uuid=$IPHONE_IAP2_UUID scope=local peer_sdp_eir=unverified")
                    lastRegistrationAt = now
                }
                startMarkerWhenListening(myGeneration)
            },
            onFailure = { stage, error ->
                if (CarPlayBluetoothMarker.usesDiscoveryMarker(profileId)) startMarkerWhenListening(myGeneration)
                val now = SystemClock.elapsedRealtime()
                val previous = lastErrorAt[stage]
                if (previous == null || now - previous >= 30_000) {
                    val operation = if (stage == RecoveringListener.FailureStage.REGISTER) "SDP 注册失败" else "RFCOMM accept 失败"
                    log("BT: $operation bluetooth_id=$bluetoothId generation=$myGeneration " +
                        "uuid=$IPHONE_IAP2_UUID error=${error.message}；保留会话任务，1s 后重新注册")
                    lastErrorAt[stage] = now
                }
            },
            awaitRetry = { sleepQuietly(1_000) },
        )
        lateinit var worker: AcceptWorker
        val acceptThread = Thread({ try { acceptLoop(worker) } finally { newListener.close() } }, "iap2-bt-accept").apply {
            isDaemon = true
        }
        worker = AcceptWorker(myGeneration, newListener, acceptThread)
        acceptWorker = worker
        acceptThread.start()
        if (profile.allowOutboundIap2) {
            Thread({ connectLoop(worker) }, "iap2-bt-client").apply {
                isDaemon = true
                clientThread = this
                start()
            }
        } else {
            log("BT: 被动监听模式——保留系统蓝牙连接，不扫描、不出站拨号、不主动断开配置文件")
        }
        return true
    }

    /**
     * 终态停止：关 RFCOMM、关监听、递增世代（过期线程自杀）。
     * 只在用户断开时调用；「ACL 断开但保留监听」用 [dropCurrentSession]。
     */
    fun stop() {
        val worker = synchronized(this) {
            if (!running) return
            running = false
            inboundPreparation?.let { cancelPreparedInbound(it.attempt) }
            sessions.setEnabled(false)
            generation.incrementAndGet()
            previousSession?.revokeEndpointPublication()
            previousSession = null
            cafeReservations.clear()
            cancelLegacyReplacement()
            cancelBmwProbe("listener stopped")
            val retiring = acceptWorker
            acceptWorker = null
            dropCurrentSession()
            clientThread?.interrupt()
            clientThread = null
            retiring
        }
        worker?.listener?.close()
        worker?.thread?.interrupt()
        synchronized(markerLock) { closeMarkers() }
        log("BT: 已停止（监听与出站均已关闭）")
    }

    // ACL broadcasts identify a device, not an RFCOMM generation. A pending dial also
    // reports isConnected=false, so only inspect a socket published by runSession.
    @SuppressLint("MissingPermission")
    fun captureBluetoothLost(address: String): (() -> Unit)? =
        PeerDisconnectCleanup.capture(rfcomm.session,
            matches = { owner -> runCatching { owner.socket.remoteDevice.address.equals(address, true) }.getOrDefault(false) },
            disconnected = { owner -> runCatching { !owner.socket.isConnected }.getOrDefault(false) },
        ) { owner ->
            owner.endIo()
            log("BT: ACL 断开，已清理 $address 的旧 RFCOMM")
        }

    internal fun captureSessionClose(): RfcommClose =
        rfcomm.captureClose { owners ->
            synchronized(this) {
                if (cafeReservations.pending?.incoming in owners) cafeReservations.clear()
            }
        }

    internal fun captureWifiClose(registration: Long): RfcommClose = rfcomm.captureWifiClose(registration)

    private fun dropCurrentSession() = closeAfterAdmission(captureSessionClose())

    @Synchronized fun onTargetChanged() {
        inboundPreparation?.let { cancelPreparedInbound(it.attempt) }
        cafeReservations.clear()
        cancelLegacyReplacement()
        cancelBmwProbe("target changed")
        targetRevision.incrementAndGet()
        iap2ServiceCache.clear()
        skippedNoService.clear()
        // Preserve an existing connection; only cancel an unfinished outbound dial.
        rfcomm.pending.getAndSet(null)?.let(::cancelAdmittedSocket)
        logAdmission("BT: 目标已更新 explicit=${targetProvider() ?: "自动"} hint=${preferredProvider() ?: "无"} active=$isSessionActive")
    }

    // ---- 入站：车机连入 ----

    @SuppressLint("MissingPermission")
    private fun acceptLoop(worker: AcceptWorker) {
        while (running && generation.get() == worker.generation) {
            val socket = worker.listener.acceptNext() ?: break
            acceptSocket(socket, worker, "BT-S")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun handleIncomingSocket(transport: RfcommTransport<BluetoothSocket>, worker: AcceptWorker, tag: String,
                                                  arrival: RfcommStamp, acceptedTicket: Long?, promoted: Boolean = false) {
        val socket = transport.socket
        fun reject(reason: String) = rejectSocket(transport, tag, reason, worker = worker)
        if (transport.localStopRequested) {
            reject("transport-cancelled")
            return
        }
        if (!running || generation.get() != worker.generation) {
            reject("stale-listener-generation")
            return
        }
        val preparation = inboundPreparation?.takeIf { sessions.canHoldInbound(acceptedTicket) }
        val ticket = sessions.admitInbound(acceptedTicket) ?: acceptedTicket?.takeIf { preparation != null }
        if (ticket == null) {
            reject(if (acceptedTicket == null) "sessions-disabled" else "stale-session-ticket")
            return
        }
        val peerAddress = safeAddress(socket.remoteDevice)
        // 指定了目标车机时拒绝其它设备的连入（多台车机场景：非目标的控制器
        // 也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
        val target = targetProvider()
        if (!io.github.hypercar.bluetooth.CarTargetPolicy.acceptsIncoming(target, peerAddress)) {
            logAdmission("$tag: 忽略非目标设备连入 addr=${peerAddress ?: "?"}（当前目标：$target）")
            reject("non-target")
            return
        }
        if (!canStartSession()) {
            reject("profile-no-longer-available")
            return
        }
        if (preparation != null) {
            if (!io.github.hypercar.bluetooth.CarTargetPolicy.acceptsIncoming(preparation.peer, peerAddress)) {
                reject("non-target-inbound-preparation")
                return
            }
            val held = preparation.held
            if (held != null) {
                val samePeer = peerAddress != null && peerAddress.equals(safeAddress(held.transport.socket.remoteDevice), true)
                val preferred = tag == "BT-S" && (held.tag != "BT-S" || samePeer)
                if (!preferred) {
                    reject("inbound-preparation-already-held")
                    return
                }
                rejectSocket(held.transport, held.tag, "primary-inbound-preferred", held.worker)
            }
            preparation.held = HeldInbound(transport, worker, tag, arrival, ticket)
            logAdmission("$tag: 保留入站 RFCOMM，等待本轮端点处理就绪 attempt=${preparation.attempt} ticket=$ticket")
            return
        }
        if (promoted) lastInboundAdmission = SystemClock.uptimeMillis()
        cancelBmwProbe("inbound $tag")
        if (cafeReservations.pending != null || legacyReplacement != null) {
            reject("inbound-handoff-already-pending")
            return
        }
        if (isSessionActive) {
            // 额外身份入口只补充发现，不能用它的探测替换正在建立的主 iAP2 会话。
            if (tag == "BT-MARKER") {
                logAdmission("$tag: 已有 iAP2 会话，关闭额外入口连接并保留原会话")
                reject("duplicate-marker")
                return
            }
            // **本轮已拿到凭据（端点）**才拒绝新连入；还在等凭据的「空转轮次」必须让位：
            // 车机只有在**新一轮** iAP2 里才会给档案 B 的能力清单，而我们又不肯为了
            // 换轮去主动拆链（拆链会让车机收起 CarPlay 图标，2026-10-25 现场定谳）。
            // 所以由车机发起的新连接来顶替旧会话——既不拆链，也不挡路。
            // The invitation can arrive on Wi-Fi without fresh Bluetooth credentials.
            // Once AirPlay owns a transport, let the car retire the active RFCOMM.
            if (io.github.hypercar.session.HyperCarRepo.hasAirPlayTransport) {
                reject("active-session-during-airplay")
                return
            }
            if (profileId == "bmw_id85" && tag == "BT-S" && peerAddress != null) {
                val retained = cafeReservations.reserveAfterPeerClose(
                    arrival.attempt, activePrimaryInbound, transport,
                    sameBondedPeer = { old, incoming -> sameBondedPeer(old, incoming, peerAddress) },
                ) { handoff -> startInboundWorker(transport, worker, tag, ticket, arrival, handoff) }
                if (retained != null) {
                    if (retained) {
                        armCafeDeadline(worker, ticket)
                        cafeReservations.pending?.old?.takeUnless { it.socketClosed }?.let { old ->
                            rfcommCleanup.execute { old.endIo() }
                        }
                        logAdmission("$tag: 保留同一车机的入站 RFCOMM，等待已断开的旧 …cafe 会话退出 peer=$peerAddress")
                    } else {
                        reject("inbound-handoff-attempt-retired")
                    }
                    return
                }
            }
            val previousOwner = sessionOwner.get()
            val sameBmwPeer = profileId == "bmw_id85" && tag == "BT-S" && previousOwner != null &&
                peerAddress != null && sameBondedPeer(previousOwner.socket, socket, peerAddress)
            if (sessionEndpoints.hasCurrentEndpoint &&
                !(sameBmwPeer && io.github.hypercar.session.HyperCarRepo.endedAirPlayOwnsRfcomm(previousOwner.registration))) {
                logAdmission("$tag: 已有 iAP2 会话在跑且已拿到凭据，忽略新的连入 addr=${peerAddress ?: "?"}")
                reject("productive-session")
                return
            }
            val old = activeCaffSocket
            if (profileId == "bmw_id85" && tag == "BT-S" && old != null && peerAddress != null &&
                peerAddress.equals(safeAddress(old.socket.remoteDevice), true)) {
                val retained = cafeReservations.reserve(activeCaffAttempt, old, transport) { handoff ->
                    startInboundWorker(transport, worker, tag, ticket, arrival, handoff)
                }
                if (!retained) {
                    reject("inbound-handoff-attempt-retired")
                    return
                }
                activeCaffSocket = null // Discard late endpoint callbacks from the retiring worker.
                armCafeDeadline(worker, ticket)
                logAdmission("$tag: 保留入站 RFCOMM，等待旧 …caff 握手退出 peer=$peerAddress")
                cancelAdmittedSocket(old)
                return
            }
            if (sameBmwPeer) {
                val retained = cafeReservations.reserve(arrival.attempt, previousOwner, transport) { handoff ->
                    startInboundWorker(transport, worker, tag, ticket, arrival, handoff)
                }
                if (!retained) {
                    reject("inbound-handoff-attempt-retired")
                    return
                }
                armCafeDeadline(worker, ticket)
                cancelAdmittedSocket(previousOwner)
                return
            }
            logAdmission("$tag: 车机重新连入（上一轮仍在等凭据）——结束旧会话让位给新一轮 addr=${peerAddress ?: "?"}")
            legacyReplacement = transport
            val deadline = cafeHandoffTimer.schedule({
                synchronized(this) {
                    if (legacyReplacement === transport) {
                        legacyReplacement = null
                        reject("previous-session-close-timeout")
                    }
                }
            }, BMW_INBOUND_HANDOFF_MILLIS, TimeUnit.MILLISECONDS)
            closeAfterAdmission(rfcomm.captureClose(includeWaiting = false)) {
                sleepQuietly(500)
                synchronized(this) {
                    if (legacyReplacement !== transport) return@closeAfterAdmission
                    legacyReplacement = null
                    deadline.cancel(false)
                    if (isSessionActive) reject("previous-session-still-closing")
                    else startInboundWorker(transport, worker, tag, ticket, arrival)
                }
            }
            return
        }
        startInboundWorker(transport, worker, tag, ticket, arrival)
    }

    @Synchronized fun retireCafeHandoff(attempt: Long, reason: String, currentInvite: (() -> Boolean)?, peer: String?) {
        val retired = cafeReservations.retire(attempt,
            matchesPeer = { peer == null || peer.equals(safeAddress(it.socket.remoteDevice), true) },
            currentInvite = currentInvite)
        if (retired) logAdmission("BT-CAFF#$attempt: 撤销尚未接管的 …cafe reason=$reason")
    }

    private fun sameBondedPeer(old: BluetoothSocket, next: BluetoothSocket, peer: String): Boolean =
        peer.equals(safeAddress(old.remoteDevice), true) && isBonded(next.remoteDevice)

    private fun cancelLegacyReplacement() {
        val held = legacyReplacement ?: return
        legacyReplacement = null
        cancelAdmittedSocket(held)
    }

    private fun armCafeDeadline(worker: AcceptWorker, ticket: Long) {
        val held = cafeReservations.pending ?: return
        held.setDeadline(cafeHandoffTimer.schedule({
            val expired = synchronized(this) {
                cafeReservations.expire(held) { old ->
                    if (!running || acceptWorker !== worker || generation.get() != worker.generation ||
                        !sessions.allows(ticket) || !old.socketClosed) return@expire false
                    val owner = sessionOwner.get()
                    if (owner != null && owner !== old) return@expire false
                    old.revokeEndpointPublication()
                    rfcomm.forget(old)
                    sessionOwner.compareAndSet(old, null)
                    if (activePrimaryInbound === old) activePrimaryInbound = null
                    true
                }
            }
            if (expired) logAdmission("BT-S: …cafe 交接等待旧线程超时 oldSocketClosed=${held.old.socketClosed}")
        }, BMW_INBOUND_HANDOFF_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Synchronized private fun caffWorkerExited(transport: RfcommTransport<BluetoothSocket>) {
        if (activeCaffSocket === transport) activeCaffSocket = null
    }

    private fun startInboundWorker(transport: RfcommTransport<BluetoothSocket>, worker: AcceptWorker, tag: String,
                                   ticket: Long, arrival: RfcommStamp, handoff: BmwCaffHandoff<RfcommTransport<BluetoothSocket>>? = null) {
        Thread({
            runSession(transport, tag, ticket, arrival, socketWorker = worker, handoff = handoff)
        }, "iap2-bt-session").apply {
            isDaemon = true
            start()
        }
    }

    // ---- 出站：主动连入车机 ----

    @Synchronized private fun cancelBmwProbe(reason: String) {
        val probe = bmwProbe ?: return
        if (!probe.finished) logAdmission("BT-CAFF#${probe.attempt}: yield reason=$reason")
        probe.cancel()
        bmwProbe = null
    }

    /** Called on the attempt's IO job. The Bluetooth accept thread remains available during SDP/connect. */
    @SuppressLint("MissingPermission")
    internal fun pollBmwProbe(probe: BmwCaffProbe, device: BluetoothDevice, since: Long, ctrlReady: Boolean,
                              manual: Boolean, valid: () -> Boolean) {
        val ticket = sessions.ticket() ?: return
        fun eligible() = running && isListening && sessions.allowsOutbound(ticket) && valid() &&
            !probe.finished && !isSessionActive && lastInboundAdmission < since &&
            device.bondState == BluetoothDevice.BOND_BONDED
        if (!eligible()) return
        val profiles = BtProfiles.connectedNames(appContext, device.address, classicOnly = true)
        val origin: AcceptWorker?
        synchronized(this) {
            if (!eligible()) return
            origin = acceptWorker
            if (bmwProbe !== probe) {
                cancelBmwProbe("new attempt")
                bmwProbe = probe
            }
            when (probe.poll(device.address.takeIf { profiles.isNotEmpty() }, SystemClock.uptimeMillis(), ctrlReady, manual)) {
                BmwCaffProbe.Decision.WAIT -> return
                BmwCaffProbe.Decision.ENTER_CTRL_GRACE -> {
                    logAdmission("BT-CAFF#${probe.attempt}: 本车 Wi-Fi ctrl 已注册并监听；普通配置文件连续在线 ${BmwCaffProbe.CTRL_GRACE_MS}ms 前优先等 …cafe/ctrl（尚未收到邀请）")
                    return
                }
                BmwCaffProbe.Decision.DIAL -> {
                    if (manual) logAdmission("BT-CAFF#${probe.attempt}: manual probe started")
                    Unit
                }
            }
        }
        val tag = "BT-CAFF#${probe.attempt}"
        // A cached UUID only saves the broad fetch. It never supplies an RFCOMM channel:
        // the secure UUID socket still resolves its service on the target, or fails closed.
        val cacheHit = device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
        val service = if (cacheHit) "present" else freshIap2Service(device, ::eligible)
        log("$tag: SDP result=$service source=${if (cacheHit) "uuid-cache" else "uuid-fetch"} profiles=$profiles")
        if (!probe.recordSdp(service == "present")) return
        if (!eligible() || BtProfiles.connectedNames(appContext, device.address, classicOnly = true).isEmpty()) {
            log("$tag: RFCOMM skipped; attempt superseded or classic profiles offline")
            probe.cancel()
            return
        }
        val socket = device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
        val transport = rfcomm.register(socket)
        if (!probe.attach { cancelAdmittedSocket(transport) }) {
            transport.cancelSocket()
            return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeout = Runnable {
            log("$tag: RFCOMM connect timeout after 8000ms")
            probe.cancel()
        }
        handler.postDelayed(timeout, 8_000)
        var handedToWorker = false
        try {
            if (!eligible()) return
            log("$tag: RFCOMM connect peer=${device.address} uuid=…caff")
            socket.connect()
            if (!eligible()) return
            log("$tag: RFCOMM connected; waiting for handshake admission")
            Thread({
                try {
                    runSession(transport, tag, ticket, caffAttempt = probe.attempt,
                        probeAdmission = { eligible() && probe.promote() }, socketWorker = origin)
                } finally {
                    probe.cancel()
                    caffWorkerExited(transport)
                }
            }, "iap2-bmw-caff").apply { isDaemon = true; start() }
            handedToWorker = true
        } catch (e: IOException) {
            log("$tag: RFCOMM failed=${e.message} cancelled=${probe.finished}")
        } finally {
            handler.removeCallbacks(timeout)
            if (!handedToWorker) probe.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(worker: AcceptWorker) {
        val myGeneration = worker.generation
        val adapter = adapterOrNull() ?: return
        var inboundGraceDone = false
        while (running && generation.get() == myGeneration) {
            // 会话期间静默：两条 RFCOMM 会互相踩（见 sessionOwner 注释）
            val ticket = sessions.ticket()
            if (ticket == null || sessions.outboundPausedForWifi || isSessionActive || !canStartSession()) {
                sleepQuietly(2_000)
                continue
            }
            // 首轮先给车机主动连入的机会——但只对「会因此断配置文件」的档案有意义：
            // 宝马档案压根不断 HFP/A2DP（车机自己会在 ACL 连上后 ~4s 连入 iAP2），
            // 等这 8s 只会白白推迟出站探测。
            if (profile.dropProfilesBeforeIap2 && !inboundGraceDone) {
                inboundGraceDone = true
                val deadline = SystemClock.uptimeMillis() + INBOUND_GRACE_MILLIS
                while (running && generation.get() == myGeneration &&
                    sessions.allowsOutbound(ticket) && !isSessionActive && SystemClock.uptimeMillis() < deadline
                ) {
                    sleepQuietly(250)
                }
                if (!sessions.allowsOutbound(ticket) || isSessionActive || !running || generation.get() != myGeneration) continue
                log("BT-C: ${INBOUND_GRACE_MILLIS / 1000}s 内车机未主动连入——改走出站（会先断开该车机的 HFP/A2DP）")
            }
            val explicitTarget = targetProvider()
            val preferred = preferredProvider()
            val candidates = candidateDevices(adapter, explicitTarget, preferred)
            if (candidates.isEmpty()) {
                if (explicitTarget != null) {
                    log("BT-C: 目标 $explicitTarget 不在已配对列表（或未声明 iAP2 服务）——请先在系统设置里与车机完成配对")
                    sleepQuietly(RETRY_DELAY_MILLIS * 4)
                } else {
                    log("BT-C: 没有「已配对且声明 iAP2 服务」的设备——请先与车机完成系统蓝牙配对")
                    sleepQuietly(RETRY_DELAY_MILLIS * 4)
                }
                continue
            }
            var connected: RfcommTransport<BluetoothSocket>? = null
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                if (!sessions.allowsOutbound(ticket) || isSessionActive) break
                // SDP 等待期间目标可能由 ACL 广播锁定；旧候选不能继续拨号。
                if (targetProvider() != explicitTarget || preferredProvider() != preferred) break
                if (device.bondState != BluetoothDevice.BOND_BONDED) continue
                val name = safeName(device) ?: device.address
                // 系统正进行发现/配对时让路，不能由后台循环全局取消发现。
                if (adapter.isDiscovering) {
                    sleepQuietly(RETRY_DELAY_MILLIS)
                    break
                }
                // 「只配对、不连接」：断开该车机的 HFP/A2DP/LeAudio——车机在音频配置
                // 文件在线时不开放 iAP2 服务窗口（奥迪 MIB3 实测每 3s 连接失败）。
                // **宝马档案不做这一步**：它的 HFP/A2DP 在线时照样主动连入 iAP2，而断开
                // 会连带把 ACL 拽掉（车机反复重连），反而再也不发起 CarPlay。
                if (!sessions.allowsOutbound(ticket) || !canStartSession()) break
                val evidence = io.github.hypercar.session.PairingEvidence.read(appContext.filesDir, device.address)
                if (profile.dropProfilesBeforeIap2 && evidence.mayDropProfiles) {
                    // 注意：BtProfiles.disconnect 内部会打「自己断开」归因标记——断开
                    // 配置文件（个别 ROM 退到设备级断开）会让系统广播 ACL 断开，接收器
                    // 靠那个标记认出「这是我们干的」，不按「下车」拆会话。
                    val dropped = BtProfiles.disconnect(appContext, device)
                    if (dropped.isNotEmpty()) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                            lastProfileLogAt = now
                            val left = BtProfiles.stillConnected(appContext, device.address)
                            log(
                                "BT-C: $name 蓝牙已连接（${dropped.joinToString("/")}）——已断开" +
                                    if (left.isEmpty()) "" else "（仍有 ${left.joinToString("/")} 在线）",
                            )
                        }
                    }
                } else if (!profileDropSkippedLogged) {
                    profileDropSkippedLogged = true
                    log("BT-C: 保留 HFP/A2DP：档案允许断开=${profile.dropProfilesBeforeIap2} 身份=${evidence.identityPresent} 本车凭证=${evidence.receiverCredentialPresent}；首配/未知状态不主动断配置文件")
                }
                val socket = tryConnect(device, ticket)
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的 iAP2 服务（RFCOMM 建立）")
                    connected = socket
                    break
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        log("BT-C: 连接 $name 失败（车机未就绪或不在无线配对窗口），${RETRY_DELAY_MILLIS / 1000}s 后重试…")
                    }
                }
            }
            if (connected == null) {
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            if (generation.get() != myGeneration || !running) {
                // stop() 与 connect() 成功竞争：本线程已过期，不建会话
                runCatching { connected.close() }
                return
            }
            runSession(connected, "BT-C", ticket, socketWorker = worker)
            sleepQuietly(RETRY_DELAY_MILLIS)
        }
    }

    /**
     * 出站候选：只保留「已配对且（声明 iAP2 服务 或 用户选定）」的设备。
     * 未指定目标时，靠系统 uuids 缓存 + SDP 探测过滤掉耳机/手表——否则既连不上
     * 还白断它们的 HFP/A2DP（cardeck 2026-10-19 实测踩坑）。
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(
        adapter: android.bluetooth.BluetoothAdapter,
        target: String?,
        preferred: String?,
    ): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        val byAddress = bonded.associateBy { it.address }
        val addresses = io.github.hypercar.bluetooth.CarTargetPolicy.outgoingCandidates(
            target, preferred, byAddress.keys.toList(),
        ) { address ->
            val device = byAddress.getValue(address)
            val found = awaitIap2Service(device)
            if (!found && skippedNoService.add(address)) {
                log("BT-C: 暂不尝试「${safeName(device) ?: address}」（尚未确认 iAP2 服务，保持其音频连接）")
            }
            found
        }
        return addresses.map(byAddress::getValue)
    }

    /**
     * 设备是否提供配件端 iAP2 服务（…caff）：先读系统 uuids 缓存，缺失再
     * `fetchUuidsWithSdp` 触发一次 SDP 查询并等 `ACTION_UUID` 广播（超时按无服务）。
     * 肯定结果缓存到下次 start()/目标切换；否定结果短期缓存，允许稍后重新发现。
     * 权限：调用方已校验 BLUETOOTH_CONNECT；SecurityException 由 runCatching 吸收。
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Boolean {
        val ticket = sessions.ticket() ?: return false
        val revision = targetRevision.get()
        fun current(): Boolean = targetRevision.get() == revision && sessions.allows(ticket)
        val previous = iap2ServiceCache[device.address]?.takeIf { it.canReuse(revision, SystemClock.elapsedRealtime()) }
        if (previous?.available == true) return current()

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        val cached = cachedHas()
        if (!cached && previous != null) return false
        val probe = if (cached) "present" else freshIap2Service(device, ::current)
        val evidence = ServiceResult.fromProbe(revision, probe, cached || cachedHas(), SystemClock.elapsedRealtime())
        if (!current()) return false
        iap2ServiceCache[device.address] = evidence
        return evidence.available
    }

    @SuppressLint("MissingPermission")
    private fun freshIap2Service(device: BluetoothDevice, allowed: () -> Boolean): String {
        val latch = CountDownLatch(1)
        var result = "timeout"
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address != device.address) return
                    @Suppress("DEPRECATION")
                    val services = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    result = when {
                        services == null -> "unknown"
                        services.any { (it as? android.os.ParcelUuid)?.uuid == ACCESSORY_IAP2_UUID } -> "present"
                        else -> "absent"
                    }
                    latch.countDown()
                }
            }
        if (!allowed()) return "cancelled"
        val started =
            runCatching {
                val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(receiver, filter)
                }
                allowed() && device.fetchUuidsWithSdp()
            }.getOrDefault(false)
        return try {
            if (!started) "not-started"
            else if (latch.await(SDP_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) result else "timeout"
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            "cancelled"
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice, ticket: Long): RfcommTransport<BluetoothSocket>? {
        if (!sessions.allowsOutbound(ticket)) return null
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (_: IOException) {
                return null
            }
        val transport = rfcomm.register(socket)
        // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
        // stop()/dropCurrentSession() 只有拿到 socket 引用才能 close() 打断它。
        synchronized(this) {
            if (!running || !sessions.allowsOutbound(ticket)) {
                cancelAdmittedSocket(transport)
                return null
            }
            rfcomm.current.set(transport)
            rfcomm.pending.set(transport)
        }
        return try {
            socket.connect()
            if (!sessions.allows(ticket)) {
                rfcomm.current.compareAndSet(transport, null)
                transport.endIo()
                null
            } else transport
        } catch (_: IOException) {
            rfcomm.current.compareAndSet(transport, null)
            transport.endIo()
            null
        } finally {
            rfcomm.pending.compareAndSet(transport, null)
        }
    }

    // ---- 会话 ----

    private fun runSession(
        transport: RfcommTransport<BluetoothSocket>,
        tag: String,
        ticket: Long,
        inbound: RfcommStamp? = null,
        caffAttempt: Long = 0L,
        probeAdmission: (() -> Boolean)? = null,
        socketWorker: AcceptWorker? = null,
        handoff: BmwCaffHandoff<RfcommTransport<BluetoothSocket>>? = null,
    ) {
        val socket = transport.socket
        val workerEndpoints = Iap2RfcommEndpointState()
        val inboundAt = inbound?.uptime
        // Publish the socket atomically with the pause check, so pausing can always close it.
        val admitted: RfcommStamp
        synchronized(this) {
            val rejected = when {
                transport.localStopRequested -> "transport-cancelled"
                !running -> "not-running"
                inboundAt != null && socketWorker != null && generation.get() != socketWorker.generation -> "stale-worker-generation"
                !sessions.allows(ticket) -> "stale-session-ticket"
                !canStartSession() -> "profile-no-longer-available"
                cafeReservations.pending !== handoff -> "inbound-handoff-superseded"
                handoff != null && (!cafeReservations.canClaim(handoff) ||
                    io.github.hypercar.session.HyperCarRepo.prefersCtrlOverCafe(
                        safeAddress(socket.remoteDevice))) -> "inbound-handoff-ctrl-invited"
                handoff != null && (io.github.hypercar.session.HyperCarRepo.hasAirPlayTransport ||
                    !io.github.hypercar.bluetooth.CarTargetPolicy.acceptsIncoming(targetProvider(), safeAddress(socket.remoteDevice))) ->
                    "inbound-handoff-no-longer-needed"
                probeAdmission != null && !probeAdmission() -> "probe-superseded"
                isSessionActive -> "session-already-active"
                handoff != null && !handoff.claim(transport) -> "inbound-handoff-not-ready"
                else -> null
            }
            if (handoff != null) cafeReservations.finish(handoff)
            if (rejected != null) {
                rejectSocket(transport, tag, rejected, socketWorker, handoff)
                return
            }
            previousSession?.revokeEndpointPublication()
            previousSession = transport
            sessionOwner.set(transport)
            socketWorker?.lastWorkerAt = SystemClock.elapsedRealtime()
            activeCaffSocket = if (probeAdmission != null) transport else null
            activeCaffAttempt = caffAttempt
            activePrimaryInbound = transport.takeIf { profileId == "bmw_id85" && tag == "BT-S" }
            rfcomm.admit(transport)
            sessionStarts.incrementAndGet()
            sessionEndpoints = workerEndpoints
            val stamp = captureRfcommStamp(SystemClock.uptimeMillis())
            admitted = if (caffAttempt == 0L) stamp else stamp.copy(attempt = caffAttempt)
        }
        val attempt = admitted.attempt
        fun ownsSession() = sessionOwner.get() === transport && sessions.allows(ticket) && !transport.localStopRequested
        val wifiHistory = wifiCredentialHistory()
        val handshakeId = HANDSHAKE_IDS.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        val sessionLog: (String) -> Unit = { line -> log("HS#$handshakeId +${SystemClock.elapsedRealtime() - started}ms attempt=$attempt $line") }
        var channel: RfcommLinkChannel? = null
        try {
            if (!ownsSession()) throw InterruptedIOException("RFCOMM 接入已取消")
            inbound?.let { onInboundSession(safeAddress(socket.remoteDevice), it, admitted) }
            log("BT-WORKER: entry=$tag acceptAttempt=${inbound?.attempt ?: "none"} handshakeAttempt=$attempt " +
                "handshake=$handshakeId acceptedUptimeMs=${inboundAt ?: "none"} handshakeUptimeMs=${admitted.uptime} " +
                "ticket=$ticket peer=${safeAddress(socket.remoteDevice)} ${diagnosticSummary(socketWorker)}")
            // 记住本轮对端车机（ACL 广播可能没收到，记录「已配对车机」要靠它拿 MAC）
            val receiver = socket.remoteDevice
            val receiverAddress = safeAddress(receiver)
            val receiverName = runCatching { safeName(receiver) }.getOrNull()
            if (inbound != null) {
                val admission = if (handoff == null) "车机已连入" else "接管保留的入站 RFCOMM"
                log("$tag: $admission name=${receiverName ?: receiverAddress ?: "unknown"}，开始 iAP2 发送端会话")
            }
            runCatching { Iap2Peer.remember(receiverAddress, receiverName) }
            val evidence = io.github.hypercar.session.PairingEvidence.read(appContext.filesDir, receiverAddress)
            sessionLog(handshakeBeginLine(tag, targetProvider() ?: "自动", evidence.identityPresent, evidence.receiverCredentialPresent))
            val deviceName = io.github.hypercar.bluetooth.LocalDeviceName.snapshot()
            val service = if (probeAdmission != null || tag == "BT-C") "…caff" else "…cafe"
            sessionLog("ROLE: iAP2 Device/iPhone，SDP=$service，deviceName=$deviceName，保留无线可用性/传输身份声明")
            val openedChannel = RfcommLinkChannel(transport, sessionLog)
            channel = openedChannel
            transport.channel = openedChannel
            val session =
                Iap2SenderSession(
                    channel = openedChannel,
                    log = sessionLog,
                    bluetoothMac = bluetoothMacProvider(),
                nowPlayingProvider = Iap2NowPlayingSource::snapshot,
                    deviceName = deviceName,
                    onIdentification = { ident ->
                        if (ownsSession()) Iap2Peer.rememberIdentification(safeAddress(socket.remoteDevice),
                            listOfNotNull(ident.name, ident.model, ident.manufacturer).joinToString(" "), ident.model)
                    },
                    // 端点回调顺手标记「本轮已拿到凭据」：acceptLoop 用它决定是否允许
                    // 车机的新连入顶替本轮（空转轮次可顶替，有凭据的不许抢）
                    publishEndpoint = { propose ->
                        val current = ownsSession() && (probeAdmission == null || synchronized(this) {
                            activeCaffSocket === transport
                        })
                        current && onEndpoint {
                            if (!ownsSession()) null else propose()?.let { update ->
                                Iap2EndpointUpdate(update.endpoint, { released ->
                                    update.commitDeferred(released)
                                    workerEndpoints.accepted(released)
                                }) {
                                    update.commit()
                                    workerEndpoints.accepted(update.endpoint)
                                }
                            }
                        }
                    },
                    context = appContext,
                    receiverAddress = receiverAddress,
                    wifiHistory = wifiHistory.forReceiver(receiverAddress),
                    pairedReceiver = evidence.identityPresent && evidence.receiverCredentialPresent,
                    bondedBmwInbound = inboundAt != null &&
                        isBmwBluetoothName(receiverName) && isBonded(receiver),
                    navigationEnabled = { ownsSession() && io.github.hypercar.session.HyperCarRepo.hudNavigationAllowed },
                    navigationCue = { io.github.hypercar.navigation.NavigationSources.snapshot() },
                    onMediaKey = { usage -> io.github.hypercar.session.HyperCarRepo.onIap2MediaKey(usage, ::ownsSession) },
                    profile = profile,
                )
            session.run(SESSION_TIMEOUT_MILLIS)
            // Phase 1 完成：进入保活泵，直到链路断开（0x4301 等生命周期消息在此处理）
            session.pumpIncomingForever()
            onSessionEnded("链路断开")
        } catch (profileA: NoCarPlayWindowException) {
            // 档案 A（仅在 profile.abortWhenCarPlayWindowClosed=true 时抛出）：这一轮车机
            // 没给机会，放掉 RFCOMM 让车机下次连入被 accept 接住。默认档案不会走这里。
            log("$tag ${profileA.message}——结束本轮，等车机下一次连入")
            onSessionEnded(profileA.message ?: "档案 A")
        } catch (error: IOException) {
            if (error is InterruptedIOException) {
                log("$tag SESSION 已取消: ${error.message}")
                onSessionEnded("已取消")
            } else {
                log("$tag SESSION 失败: ${error.message}")
                onSessionEnded(error.message ?: "IO 错误")
            }
        } catch (error: Throwable) {
            // 协议解析/异常路径不能只落 logcat（本机型 logcat 受限）——收进会话结束回调
            log("$tag SESSION 异常: ${error.javaClass.simpleName}: ${error.message}")
            onSessionEnded("异常: ${error.javaClass.simpleName}")
        } finally {
            channel?.let { opened ->
                runCatching { sessionLog("END alive=${opened.isAlive} cancelled=${opened.isCancelled} reason=${opened.closeReason ?: "会话层退出"}") }
            }
            transport.finish()
            transport.finishWhenSocketClosed {
                synchronized(this) {
                    rfcomm.forget(transport)
                    sessionOwner.compareAndSet(transport, null)
                    if (activePrimaryInbound === transport) activePrimaryInbound = null
                    if (activeCaffSocket === transport) activeCaffSocket = null
                    if (profileId == "bmw_id85") cafeReservations.exited(transport)
                }
                log("$tag 会话结束（RFCOMM 已关闭；监听与出站循环仍在运行）")
            }
        }
    }

    // ---- 工具 ----

    private fun adapterOrNull(): android.bluetooth.BluetoothAdapter? =
        try {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (_: Exception) {
            null
        }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice?): String? =
        try {
            device?.name
        } catch (_: SecurityException) {
            null
        }

    @SuppressLint("MissingPermission")
    private fun safeAddress(device: BluetoothDevice?): String? =
        try {
            device?.address
        } catch (_: SecurityException) {
            null
        }

    @SuppressLint("MissingPermission")
    private fun isBonded(device: BluetoothDevice?): Boolean =
        try {
            device?.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * RFCOMM 之上的 iAP2 链路通道：一个读线程 + 一个节奏线程驱动 [Iap2LinkEngine]，
 * 向会话层暴露阻塞式的 sendControl/recvControl/awaitReady。
 *
 * 心跳：链路进入 NORMAL 后，引擎按对端声明的 ackTimeout 自动发裸 ACK 保活
 * （无对端值时 1s 兜底），本通道只负责按 nextDeadlineMillis 定时 advanceTime。
 */
private class RfcommLinkChannel(
    private val transport: RfcommTransport<BluetoothSocket>,
    private val log: (String) -> Unit,
) : Iap2LinkChannel, Closeable {
    private val socket = transport.socket
    private val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
    private val lock = Any()
    private val openedAt = SystemClock.elapsedRealtime()
    private var socketReadBytes = 0L
    private var socketWriteBytes = 0L
    private val files = java.util.concurrent.ArrayBlockingQueue<ByteArray>(64)
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private var readyLatch = CountDownLatch(1)
    private var workerGeneration: Long? = null
    // Endpoint commits can hold the application inbox lock. Keep that lock order
    // independent of the RFCOMM engine lock used by Android socket I/O.
    private var endpointSource = transport.endpointGeneration()

    @Volatile override var linkGeneration = 0L
        private set

    @Volatile
    private var terminated = false

    @Volatile
    private var terminationReason: String? = null


    override val closeReason: String? get() = terminationReason
    override val isCancelled: Boolean get() = transport.isCancelled
    override val localStopRequested: Boolean get() = transport.localStopRequested
    override fun <T> commitMfi(action: () -> T): T = transport.commitMfi(action)

    /** 逐帧摘要日志预算（每方向每连接，防刷屏）。 */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0

    override val isAlive: Boolean get() = !terminated

    init {
        engine.hudLog = log
        engine.mfiLog = log
        engine.onDetectionRestart = {
            endpointSource.retire()
            linkGeneration++
            endpointSource = transport.endpointGeneration()
            log("LINK: DETECT restart; retain RFCOMM, clear old control queue and sequence/window state")
            readyLatch.countDown()
            readyLatch = CountDownLatch(1)
            files.clear()
            controls.clear()
            // Empty payload is an ordered CSM parser boundary, distinct from POISON by identity.
            controls.offer(RESTART)
        }
        engine.onPeerSynchronizationAdopted = { sync ->
            log(
                "LINK: 采纳对端会话表 maxOutgoing=${sync.maxOutgoing} maxLen=${sync.maxLength} " +
                    "sessions=" + sync.sessions.joinToString { "${it.id}/k${it.kind}/v${it.version}" },
            )
        }
        engine.onUnknownSessionData = { sessionId, payload ->
            // 只 dump 前 96 字节：EA 会话的报文结构（BMW 私有协议）从这几行就能看出
            // 是 TLV 还是明文文本；ACK 已由链路层回掉，不影响对端窗口。
            val shown = payload.take(96).joinToString("") { "%02x".format(it) }
            val tail = if (payload.size > 96) "…" else ""
            log(
                "LINK: 会话 $sessionId 数据 ${payload.size}B 已 ACK 未处理（EA/私有通道）=" +
                    shown + tail,
            )
        }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            if (rxFrameLogs < FRAME_LOG_LIMIT) {
                rxFrameLogs++
                log("LINK rx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack session=$sessionId len=$size")
            }
        }
        engine.onFrameSent = { control, sequence, ack, sessionId, size ->
            if (txFrameLogs < FRAME_LOG_LIMIT) {
                txFrameLogs++
                log("LINK tx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack session=$sessionId len=$size")
            }
        }
        Thread({ runReader() }, "iap2-link-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "iap2-link-pacer").apply {
            isDaemon = true
            start()
        }
    }

    override fun awaitReady(timeoutMillis: Long): Boolean = awaitReadyGeneration(timeoutMillis) != null

    override fun endpointGeneration(expected: Long): Iap2EndpointGeneration = synchronized(lock) {
        if (linkGeneration == expected) endpointSource else Iap2EndpointGeneration().apply { retire() }
    }

    override fun awaitReadyGeneration(timeoutMillis: Long): Long? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val latch = synchronized(lock) {
                if (terminated) return null
                if (engine.state == Iap2LinkEngine.State.NORMAL) {
                    workerGeneration = linkGeneration
                    controls.remove(RESTART)
                    return linkGeneration
                }
                readyLatch
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            try {
                if (!latch.await(remaining, TimeUnit.NANOSECONDS)) return null
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("iAP2 链路协商已取消").apply { initCause(error) }
            }
        }
    }

    override fun peerMaxControlPayload(): Int? =
        synchronized(lock) {
            engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1)
        }

    override val peerControlVersion: Int? get() = synchronized(lock) {
        engine.peerSynchronization?.sessions?.firstOrNull { it.kind == 0 }?.version
    }

    override val supportsFileTransfer: Boolean get() = synchronized(lock) { engine.fileTransferSession != null }
    override val canQueueMedia: Boolean get() = synchronized(lock) {
        workerCanSendLocked() && engine.queuedControlPackets < 4 && engine.outstandingPackets < 8
    }
    override fun recvFileTransfer(): ByteArray? = synchronized(lock) {
        if (workerCanSendLocked()) files.poll() else null
    }
    override fun sendFileTransfer(bytes: ByteArray): Boolean = synchronized(lock) {
        if (!workerCanSendLocked()) return@synchronized false
        engine.sendFileTransfer(bytes, nowMillis()).also { drainOutputLocked() }
    }

    override fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean,
    ): Boolean = sendHudControl(bytes, awaitAck, 0)

    override fun sendHudControl(bytes: ByteArray, awaitAck: Boolean, messageId: Int): Boolean {
        synchronized(lock) {
            if (!workerCanSendLocked()) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck, hudMessageId = messageId)
            drainOutputLocked()
            return accepted
        }
    }

    override val hasQueuedHudMessages: Boolean get() = synchronized(lock) { engine.hasBulkControl }

    override fun queueHudMessage(
        bytes: ByteArray, messageId: Int, canStart: () -> Boolean, commitStart: (() -> Boolean)?,
        onDiscarded: (() -> Unit)?,
    ): Boolean = synchronized(lock) {
        workerCanSendLocked() && engine.queueBulkControl(bytes, awaitAck = false, messageId, canStart, commitStart,
            onDiscarded = onDiscarded)
    }

    override fun flushHudMessages(allowHudStart: Boolean, discardUnstartedHud: Boolean) = synchronized(lock) {
        if (!workerCanSendLocked()) return@synchronized
        engine.discardUnstartedBulkControl(discardHud = discardUnstartedHud)
        engine.flushBulkControl(nowMillis(), 8, allowBulkStart = controls.isEmpty(), allowHudStart = allowHudStart)
        drainOutputLocked()
    }

    override fun sendMfiControl(bytes: ByteArray, awaitAck: Boolean) = sendMfi(bytes, awaitAck, result = false)

    override fun sendMfiResult(bytes: ByteArray, awaitAck: Boolean) = sendMfi(bytes, awaitAck, result = true)

    private fun sendMfi(bytes: ByteArray, awaitAck: Boolean, result: Boolean): Boolean = synchronized(lock) {
        if (!workerCanSendLocked()) {
            if (result) log("MFi RFCOMM ${if (localStopRequested) "cancelled" else "rejected"} id=0xaa05 before_enqueue")
            return@synchronized false
        }
        transport.sendMfiControl(engine, bytes, nowMillis(), awaitAck, result, ::drainOutputLocked)
    }

    private fun workerCanSendLocked(): Boolean = !terminated &&
        workerGeneration == linkGeneration && engine.state == Iap2LinkEngine.State.NORMAL

    override fun recvControl(timeoutMillis: Long): ByteArray? {
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        return if (polled == null || polled === POISON) null else polled
    }

    override fun close() {
        synchronized(lock) {
            finishLocked(null)
        }
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            synchronized(lock) {
                // 车机连入后先发探测。这里只进入等待，不把探测和同步写出；
                // 第一次 read 到 FF 55 之后，引擎再回探测，并等车机的 SYN。
                engine.start(nowMillis(), passive = true)
            }
            val buffer = ByteArray(4096)
            val input: InputStream = socket.inputStream
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    throw IOException("RFCOMM 对端关闭（EOF）")
                }
                if (count == 0) continue
                transport.setSocketReadPending(true)
                synchronized(lock) {
                    try {
                        if (terminated) return
                        if (socketReadBytes == 0L) log("RFCOMM-IO: first-read bytes=$count elapsed=${SystemClock.elapsedRealtime() - openedAt}ms")
                        socketReadBytes += count
                        engine.feed(buffer.copyOf(count), nowMillis())
                        processEventsLocked()
                    } finally {
                        transport.setSocketReadPending(false)
                    }
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            transport.readFailed(error, ::finishLocked)
        }
    }

    private fun runPacer() {
        while (!terminated) {
            val deadline: Long?
            synchronized(lock) {
                if (terminated) return
                engine.advanceTime(nowMillis())
                processEventsLocked()
                drainOutputLocked()
                signalReadyLocked()
                deadline = engine.nextDeadlineMillis()
            }
            val sleep =
                if (deadline == null) {
                    PACER_POLL_MILLIS
                } else {
                    (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                }
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** 持锁调用：抽取引擎事件并搬运输出。 */
    private fun processEventsLocked() {
        while (true) {
            val event = engine.pollEvent() ?: return
            when (event) {
                is Iap2LinkEngine.Event.Control -> {
                    if (!controls.offer(event.bytes)) {
                        engine.feedEof()
                        return
                    }
                }

                is Iap2LinkEngine.Event.FileTransfer -> {
                    // Optional artwork cannot occupy the control queue or block the reader.
                    files.offer(event.bytes)
                }
                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(IOException(event.reason ?: "link died"))
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked(): IOException? {
        val hud = engine.takeHudOutput()
        try {
            val written = transport.writeOutputFrom(engine) { socket.outputStream }
            if (written == 0) return null
            hud.filter { it.offset < written }.forEach { log("HUD RFCOMM written CSeq=NA ${it.label}") }
            if (socketWriteBytes == 0L) log("RFCOMM-IO: first-write-returned bytes=$written elapsed=${SystemClock.elapsedRealtime() - openedAt}ms; peer reception unconfirmed")
            socketWriteBytes += written
        } catch (error: IOException) {
            hud.forEach { log("HUD RFCOMM write_failed CSeq=NA ${it.label}") }
            finishLocked(error)
            return error
        }
        signalReadyLocked()
        return null
    }

    private fun signalReadyLocked() {
        if (engine.state == Iap2LinkEngine.State.NORMAL) readyLatch.countDown()
    }

    /** 可重入（close 可能从任意线程调用）：置终止位、清队列、关 socket。 */
    private fun finishLocked(reason: Throwable?) {
        synchronized(lock) {
            if (terminated) return
            transport.endIo()
            terminationReason = if (isCancelled) "本地取消" else reason?.message ?: "链路结束"
            terminated = true
            engine.reportDiscardedMfi(if (isCancelled) "local_cancel" else "transport_ended")
            engine.endHudTrace("transport_ended")
            files.clear()
            controls.offer(POISON) // 哨兵：唤醒阻塞的接收方（LBQ 不接受 null）
            readyLatch.countDown()
            log("RFCOMM-IO: end read_bytes=$socketReadBytes socket_write_bytes=$socketWriteBytes elapsed=${SystemClock.elapsedRealtime() - openedAt}ms local_cancel=$isCancelled")
            log(if (reason == null) "LINK closed" else "LINK closed: ${reason.message}")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val PACER_POLL_MILLIS = 20L

        /** 逐帧摘要日志上限（每方向每连接）。 */
        const val FRAME_LOG_LIMIT = 128

        /** 链路终结哨兵；真实控制载荷至少包含一段 CSM 帧，不会为空。 */
        val POISON = ByteArray(0)
        val RESTART = ByteArray(0)
    }
}

