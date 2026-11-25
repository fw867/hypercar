package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.os.Build
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * 手机侧（iPhone 角色）的 `_carplay-ctrl._tcp` 控制服务：mDNS 广播 + HTTP 应答。
 *
 * **为什么必须有它**（2026-10-24 对照 cardeck `net/CarPlayCtrlAdvertiser` 定谳）：
 * 真车（Audi MIB3 / BMW）的「无线 CarPlay 窗口」不是等我们发 0x4300 才开的——车机是在
 * **WiFi 上发现手机的 `_carplay-ctrl._tcp`** 后，反连该端口发
 * `GET /ctrl-int/1/connect`（带 `AirPlay-Receiver-Device-ID`）来「邀请」手机，
 * **邀请成功后车机才下发 0x4301**（AirPlay 端口/地址/公钥）。没有这个广播，车机永远
 * 不邀请 → 0x4301 永不到达 → 上层只能干等（hypercar 旧实现正是如此：只有 0x4301 一条
 * 端点来源，而且从不在 WiFi 上露面）。
 *
 * 注册与身份约束：
 *  1. TXT `srcvers` 与 AirPlay SETUP / User-Agent 统一为盒子固件的 770.8.1。
 *     旧奥迪记录曾使用 950.7.1；该记录不构成当前版本已在奥迪验证的证据。
 *  2. **TXT `id` 必须是本机真实蓝牙 MAC**：车机按它匹配「待邀请的手机」，占位值
 *     （02:00:…）会导致永不邀请。
 *  3. Android 13+ 通过 NsdServiceInfo.network 限定本轮车机网络；较旧系统保留进程绑定。
 *
 * ID8.5 以本轮手机名称发布实例，其它档案保留 `sender`；TXT 与端口不随名称变化。
 */
class CarPlayCtrlAdvertiser(
    context: Context,
    /** 本机真实蓝牙 MAC（TXT id；解析失败时返回占位值，车机不会邀请）。 */
    private val bluetoothMacProvider: () -> String,
    private val log: (String) -> Unit,
    private val onDiagnostic: (Event, Int) -> Unit = { _, _ -> },
    private val serviceNameProvider: () -> String = { "sender" },
    private val onNetworkInvitation: (CarPlayCtrlAdvertiser, Network, () -> Boolean) -> Unit = { _, _, _ -> },
    private val attemptId: Long = 0L,
    private val onMdnsObservation: (Int, Int?) -> Unit = { _, _ -> },
    /** Diagnostic-only callback under the advertiser monitor; must not do I/O. */
    private val onCtrlEpochChanged: () -> Unit = {},
    private val traceDiscovery: (String) -> Unit = {},
) {
    private fun logCtrl(message: String) = log("$message attempt=$attemptId")
    enum class Event { REGISTERED, TCP_ACCEPTED, INVITE_HEAD, INVITE_ACK, INVITED }
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"
        private const val SRCVERS = "770.8.1"
        private const val TAG = "CTRL"
        private const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
        private const val REGISTRATION_TIMEOUT_MILLIS = 10_000L

        internal fun isConnectRequest(line: String): Boolean = CtrlHttpHead.isConnectRequestLine(line)
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var serverSocket: ServerSocket? = null
    private var previousPort = 0
    private var acceptThread: Thread? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val registration = ServiceRegistrationState()
    val isAdvertising: Boolean get() = registration.ready

    /** Local readiness only; this says nothing about whether the HU has invited us. */
    @Synchronized internal fun listeningOn(network: Network): Boolean =
        registeredNetwork == network && invites.current?.network == network && registration.ready &&
            serverSocket?.isClosed == false && acceptThread?.isAlive == true

    private var registeredSsid: String? = null
    @Volatile private var registeredNetwork: Network? = null
    private var registeredAtMillis = 0L
    private var registeredMac: String? = null
    private var registeredName: String? = null
    private var actualRegisteredName: String? = null
    private var registrationStartedAt = 0L
    private val srvEvidence = CtrlSrvEvidence()
    private var mdnsObserver: CtrlMdnsObserver? = null
    private var observationGeneration = 0L
    private var observationAttempts = 0
    private val tcpAccepted = AtomicLong()
    private val httpHeads = AtomicLong()
    private val httpResponses = AtomicLong()
    private val invitesBeingAcknowledged = AtomicLong()
    @Volatile private var lastTcpAt = 0L

    @Synchronized internal fun currentSrv(network: Network): CtrlSrvObservation? {
        if (!listeningOn(network)) return null
        val name = actualRegisteredName ?: return null
        return srvEvidence.latest?.takeIf { it.matches(name, port, registrationStartedAt) }
    }

    /** Registration is local state; only MDNS-OBS describes datagrams actually delivered to us. */
    @Synchronized fun diagnosticSummary(): String {
        val network = invites.current?.network
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val link = network?.let { runCatching { cm?.getLinkProperties(it) }.getOrNull() }
        return "obs=$observationGeneration network=$network default=${cm?.activeNetwork} " +
            "if=${link?.interfaceName} addresses=${link?.linkAddresses} port=$port " +
            "listenerClosed=${serverSocket?.isClosed} acceptAlive=${acceptThread?.isAlive == true} " +
            "registration=${registration.currentGeneration} ready=${registration.ready} pending=${registration.pending} " +
            "registrationAgeMs=${SystemClock.uptimeMillis() - registeredAtMillis} " +
            "tcp=${tcpAccepted.get()} heads=${httpHeads.get()} responses=${httpResponses.get()} invites=$inviteCount " +
            "lastTcpAgeMs=${if (lastTcpAt == 0L) "none" else SystemClock.elapsedRealtime() - lastTcpAt}"
    }

    /** 车机 ctrl 邀请计数（每收到一次 `GET /ctrl-int/1/connect` 加一）。 */
    @Volatile
    var inviteCount: Int = 0
        private set

    /**
     * 最近一次邀请方的 IP —— **这就是车机在热点网段上的地址**。
     *
     * 为什么需要它：BMW（MGU-55 实测）在 0x1d01 清单里明确声明
     * 「CarPlayStartSession(0x4301)=不发」，所以永远等不到 0x4301 里的端点地址；
     * 但它会主动来 `GET /ctrl-int/1/connect` 邀请手机，TCP 对端地址就是车机自己。
     * 于是把「邀请方 IP + 7000」当 AirPlay 端点，是这条链路上唯一可靠的来源。
     */
    private val invites = CtrlInvitationInbox<Network, InetAddress, CtrlInvitation<Network>> { epoch, invite ->
        inviteCount++
        runCatching { onNetworkInvitation(this, epoch.network, epoch::isCurrent) }
        runCatching { onDiagnostic(Event.INVITED, port) }
        logCtrl("$TAG: ✓ 车机 ctrl 邀请（第 $inviteCount 次，receiver=${invite.receiverId ?: "?"} network=#${epoch.network}）——AirPlay 窗口已开启")
    }
    internal val lastInvitation: CtrlInvitation<Network>? @Synchronized get() = invites.latest.get()
    // Read without taking the advertiser monitor: the Bluetooth probe may hold its own lock.
    internal val hasUnresolvedOrPublishedInvite: Boolean get() =
        invitesBeingAcknowledged.get() > 0 || invites.awaitingConfirmation || invites.latest.get() != null
    val lastInvitePeerIp: String? get() = lastInvitation?.host
    internal fun discardInvitation(expected: CtrlInvitation<Network>): Boolean = invites.latest.discard(expected)

    /** A client accepted on A always carries A's listener epoch, even if B reuses both IPs. */
    @Synchronized internal fun selectNetwork(network: Network?) {
        if (invites.current?.network == network) return
        logCtrl("CTRL-DIAG: network-change old=${invites.current?.network} new=$network oldPort=$port")
        mdnsObserver?.close()
        mdnsObserver = null
        observationAttempts = 0
        invites.select(network)
        srvEvidence.resetNetwork()
        if (registrationListener != null) unregisterMdns()
        registeredNetwork = null
        serverSocket?.let {
            traceDiscovery("event=listener-close attempt=$attemptId closedPort=${it.localPort}")
            previousPort = it.localPort
            runCatching { it.close() }
        }
        serverSocket = null
        acceptThread = null
        if (previousPort != 0 && network != null) openListener()
    }

    @Synchronized internal fun networkAddresses(network: Network, addresses: Set<InetAddress>) {
        val epoch = invites.current ?: return
        if (epoch.network == network) {
            if (srvEvidence.addressesChanged(addresses, SystemClock.elapsedRealtime())) mdnsObserver?.invalidateLocalAddresses()
            invites.addresses(epoch, addresses)
        }
    }

    private fun refreshAddresses(epoch: CtrlInvitationInbox.Epoch<Network>) {
        val link = runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(epoch.network)
        }.getOrNull()
        if (link != null) invites.addresses(epoch, link.linkAddresses.map { it.address }.toSet())
    }

    /** 已注册的端口（-1 = 未启动）。 */
    val port: Int get() = serverSocket?.localPort ?: -1
    /** Pair the current listener with diagnostic evidence under the same epoch lock; read must not do I/O. */
    @Synchronized internal fun <T> withListeningPortFor(attempt: Long, read: (Int?) -> T): T =
        read(serverSocket?.takeIf { attemptId == attempt && !it.isClosed }?.localPort?.takeIf { it > 0 })

    /** Keep the same service alive across ACL edges and polling; replace only a changed identity or failed registration. */
    @Synchronized
    fun ensureForNetwork(ssid: String, network: Network? = null): Boolean {
        val effectiveNetwork = network ?: registeredNetwork
        val epoch = invites.current ?: return false
        if (epoch.network != effectiveNetwork) return false
        if (invites.awaitingConfirmation) refreshAddresses(epoch)
        val currentMac = bluetoothMacProvider()
        val currentName = serviceNameProvider()
        val same = registeredSsid == ssid && registeredNetwork == effectiveNetwork &&
            registeredMac.equals(currentMac, ignoreCase = true) && registeredName == currentName
        val age = SystemClock.uptimeMillis() - registeredAtMillis
        val socket = serverSocket?.takeUnless { it.isClosed }
        if (socket != null && registration.canReuse(same, age, REGISTRATION_TIMEOUT_MILLIS)) {
            ensureAcceptThread(socket, epoch)
            ensureObserver(epoch, socket)
            invites.advertise(epoch)
            return true
        }
        if (registrationListener != null) {
            logCtrl("$TAG: 更新 mDNS 注册 reason=${if (!same) "network/identity changed" else "registration/listener unavailable"} network=$effectiveNetwork port=$port")
            unregisterMdns()
        }
        registeredSsid = ssid
        registeredNetwork = effectiveNetwork
        // A rejected request is retried by the existing wait loop, without blocking or withdrawing a healthy service.
        return start().also { if (it) invites.advertise(epoch) }
    }

    /** Bluetooth is an independent transport: its edges must not withdraw a healthy Wi-Fi service. */
    @Synchronized
    fun nudgeWhileWaiting(reason: String, ssid: String?, network: Network?): Boolean {
        if (ssid.isNullOrBlank() || network == null || lastInvitePeerIp != null) return false
        val token = registration.currentGeneration
        val ensured = ensureForNetwork(ssid, network)
        if (ensured) {
            logCtrl("$TAG: 车机状态变化（$reason）——${if (token == registration.currentGeneration) "保留现有 mDNS 注册，无注销重播" else "修复/建立 mDNS 注册"} network=$network port=$port")
        }
        return ensured
    }

    private data class MdnsReplaySnapshot(
        val epoch: CtrlInvitationInbox.Epoch<Network>, val registrationToken: Long,
        val listener: ServerSocket, val observer: CtrlMdnsObserver?, val name: String, val mac: String,
        val port: Int, val registeredSince: Long, val publishedInvites: Int,
    )

    internal data class ResendResult(val status: String, val message: String)

    /** Developer command: never restarts NSD, changes the listener, or initiates a connection. */
    internal fun resendAnnouncement(network: Network, stillWaiting: () -> Boolean = { true }): ResendResult {
        val notWaiting = ResendResult("session_changed", "连接状态已变化，不再重发 mDNS 通告")
        if (!stillWaiting()) return notWaiting
        val snapshot = synchronized(this) {
            val epoch = invites.current
            val listener = serverSocket
            val name = actualRegisteredName
            val mac = registeredMac
            if (!listeningOn(network) || epoch == null || listener == null || name == null || mac == null)
                return ResendResult("listener_not_ready", "车机 Wi-Fi 控制服务尚未就绪")
            if (hasUnresolvedOrPublishedInvite)
                return ResendResult("invite_received", "本轮已收到车机邀请，无需重发")
            MdnsReplaySnapshot(epoch, registration.currentGeneration, listener, mdnsObserver, name, mac,
                listener.localPort, registrationStartedAt, inviteCount)
        }
        fun canSend(): Boolean = stillWaiting() && synchronized(this) {
            invites.current === snapshot.epoch && registration.currentGeneration == snapshot.registrationToken &&
                serverSocket === snapshot.listener && mdnsObserver === snapshot.observer && listeningOn(network) &&
                inviteCount == snapshot.publishedInvites && !hasUnresolvedOrPublishedInvite
        }
        // No Binder, UDP or logging under the advertiser monitor. A real invite takes priority at
        // the last check before each send; an invite racing the actual send syscall cannot unsend it.
        traceDiscovery("event=mdns-resend-request attempt=$attemptId network=$network currentPort=${snapshot.port}")
        var superseded = false
        val sent = runCatching {
            snapshot.observer?.resend(snapshot.name, snapshot.port, snapshot.mac, SRCVERS, snapshot.registeredSince) {
                canSend().also { superseded = !it }
            }
        }.getOrElse {
            logCtrl("MDNS-RESEND: failed network=$network port=${snapshot.port} error=${it.javaClass.simpleName}:${it.message}")
            return ResendResult("send_exception", "mDNS 通告发送失败，请查看日志")
        }
        if (sent != null && sent > 0)
            return ResendResult("submitted", "已提交 mDNS 通告（$sent 个数据包，端口 ${snapshot.port}）")
        if (superseded || !canSend()) return notWaiting
        if (sent == 0) return ResendResult("no_datagrams_sent", "mDNS 通告发送失败，请查看日志")
        logCtrl("MDNS-RESEND: skipped=no-current-announcement network=$network port=${snapshot.port}")
        return ResendResult("no_current_announcement", "未发送：未捕获当前服务的完整 mDNS 通告")
    }

    private fun ensureAcceptThread(socket: ServerSocket, epoch: CtrlInvitationInbox.Epoch<Network>) {
        if (acceptThread?.isAlive != true) {
            acceptThread = Thread({ acceptLoop(socket, epoch) }, "carplay-ctrl-accept").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun ensureObserver(epoch: CtrlInvitationInbox.Epoch<Network>, listener: ServerSocket) {
        if (mdnsObserver?.retryDue() == true && observationAttempts < 3) {
            mdnsObserver?.close()
            mdnsObserver = null
        }
        if (mdnsObserver == null && observationAttempts < 3) {
            observationAttempts++
            val generation = ++observationGeneration
            mdnsObserver = CtrlMdnsObserver(appContext, epoch.network, generation, log, attemptId,
                trace = traceDiscovery,
                onLocalSrv = { srv ->
                    synchronized(this) {
                        val name = actualRegisteredName ?: registeredName
                        if (name != null && invites.current === epoch && registeredNetwork == epoch.network &&
                            serverSocket === listener && !listener.isClosed && observationGeneration == generation &&
                            srv.matches(name, listener.localPort, registrationStartedAt)) srvEvidence.record(srv)
                    }
                },
            ) { family, observedPort ->
                synchronized(this) {
                    // Serialize evidence with epoch invalidation, not with packet I/O or logging.
                    if (invites.current === epoch && serverSocket === listener && !listener.isClosed &&
                        observationGeneration == generation &&
                        (observedPort == null || observedPort == port)) onMdnsObservation(family, observedPort)
                }
            }.also { it.start() }
        }
    }

    private fun openListener(): ServerSocket? {
        val epoch = invites.current ?: return null
        val socket = try {
            serverSocket?.takeUnless { it.isClosed } ?: run {
                val prefs = appContext.getSharedPreferences("carplay_ctrl_listener", Context.MODE_PRIVATE)
                val preferred = previousPort.takeIf { it > 0 } ?: prefs.getInt("last_port", 0)
                val listener = bindCtrlPort(preferred) { reason ->
                    logCtrl("CTRL-PORT: preferred=$preferred fallback=dynamic reason=$reason")
                }
                val actual = listener.localPort
                prefs.edit().putInt("last_port", actual).apply()
                logCtrl("CTRL-PORT: preferred=$preferred actual=$actual reused=${preferred == actual} network=${epoch.network}")
                listener
            }
        } catch (error: IOException) {
            logCtrl("$TAG: 监听端口创建失败 - ${error.message}")
            return null
        }
        if (serverSocket !== socket) {
            mdnsObserver?.close()
            mdnsObserver = null
            observationAttempts = 0
            acceptThread = null
            serverSocket = socket
            traceDiscovery("event=listener-open attempt=$attemptId network=${epoch.network} currentPort=${socket.localPort}")
            // Keep the last evidence while disconnected; reset before a new listener can publish.
            runCatching(onCtrlEpochChanged)
        }
        ensureAcceptThread(socket, epoch)
        return socket
    }

    private fun start(): Boolean {
        val socket = openListener() ?: return false
        val epoch = invites.current ?: return false
        ensureObserver(epoch, socket)
        if (registration.ready || registration.pending) return true
        val token = registration.begin()
        return try {
            val mac = bluetoothMacProvider()
            val name = serviceNameProvider()
            val info =
                NsdServiceInfo().apply {
                    serviceName = name
                    serviceType = SERVICE_TYPE
                    setPort(socket.localPort)
                    // These are the control service's TXT fields. Device info has its own DNS owner.
                    setAttribute("id", mac)
                    setAttribute("srcvers", SRCVERS)
                    if (Build.VERSION.SDK_INT >= 33) setNetwork(registeredNetwork)
                }
            // 每次注册用全新 listener：unregisterService 是异步的，复用同一实例会
            // 撞 "listener already in use"
            val listener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                        synchronized(this@CarPlayCtrlAdvertiser) {
                            if (!registration.registered(token)) {
                                // 注销可能与系统注册完成交错；只清理过期 listener，不能撤销当前注册。
                                if (registrationListener !== this) runCatching { nsdManager.unregisterService(this) }
                                return
                            }
                            registeredAtMillis = SystemClock.uptimeMillis()
                            actualRegisteredName = serviceInfo.serviceName
                            // Android may return only the registered service name in this callback.
                            runCatching { onDiagnostic(Event.REGISTERED, socket.localPort) }
                            traceDiscovery("event=nsd-registered attempt=$attemptId network=$registeredNetwork " +
                                "currentPort=${socket.localPort} instance=${serviceInfo.serviceName}")
                            logCtrl("$TAG: mDNS 注册成功 ${serviceInfo.serviceName} network=$registeredNetwork port=$port")
                            logCtrl("CTRL-DIAG: NSD registered token=$token requested=$name actual=${serviceInfo.serviceName} " +
                                "type=${serviceInfo.serviceType} callbackPort=${serviceInfo.port} ${diagnosticSummary()}")
                        }
                    }

                    override fun onRegistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        synchronized(this@CarPlayCtrlAdvertiser) {
                            if (!registration.failed(token)) return
                            logCtrl("$TAG: mDNS 注册失败 code=$errorCode network=$registeredNetwork（等待期间重试）")
                        }
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        synchronized(this@CarPlayCtrlAdvertiser) {
                            if (registration.failed(token)) logCtrl("$TAG: 当前广播已注销，等待期间重试")
                        }
                    }

                    override fun onUnregistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) { logCtrl("CTRL-DIAG: NSD unregister-failed token=$token error=$errorCode current=${registration.currentGeneration}") }
                }
            registrationListener = listener
            registrationStartedAt = SystemClock.elapsedRealtime()
            srvEvidence.clear()
            registeredMac = mac
            registeredName = name
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registeredAtMillis = SystemClock.uptimeMillis()
            log(
                "$TAG: 已监听 :${socket.localPort}，提交广播 $SERVICE_TYPE network=$registeredNetwork " +
                    "name=$name id=$mac srcvers=$SRCVERS" +
                    if (mac == PLACEHOLDER_MAC) "（⚠ 占位 MAC：车机不会邀请）" else "",
            )
            true
        } catch (error: Exception) {
            registration.failed(token)
            logCtrl("$TAG: 启动失败 - ${error.message}")
            false
        }
    }

    /** 只注销 mDNS 注册（保留 socket 与 accept 线程，端口不变）。 */
    @Synchronized
    private fun unregisterMdns() {
        logCtrl("CTRL-DIAG: NSD unregister token=${registration.currentGeneration} network=$registeredNetwork port=$port")
        registration.invalidate()
        val previous = registrationListener
        registrationListener = null
        registeredMac = null
        registeredName = null
        actualRegisteredName = null
        srvEvidence.clear()
        previous?.let { runCatching { nsdManager.unregisterService(it) } }
    }

    private fun acceptLoop(socket: ServerSocket, epoch: CtrlInvitationInbox.Epoch<Network>) {
        // 循环条件不能绑 `registered`：重注册期间 registered 会短暂为 false，
        // 旧实现会让 accept 线程退出、之后再也没人接车机的 ctrl 邀请
        while (!socket.isClosed) {
            val client =
                try {
                    socket.accept()
                } catch (error: Exception) {
                    logCtrl("CTRL-TCP: accept-end network=${epoch.network} current=${epoch.isCurrent} closed=${socket.isClosed} error=${error.javaClass.simpleName}:${error.message}")
                    break
                }
            val connection = tcpAccepted.incrementAndGet()
            lastTcpAt = SystemClock.elapsedRealtime()
            logCtrl("CTRL-TCP: accept connection=$connection network=${epoch.network} current=${epoch.isCurrent} " +
                "peer=${client.remoteSocketAddress} local=${client.localSocketAddress}")
            traceDiscovery("event=tcp-accepted attempt=$attemptId network=${epoch.network} " +
                "connection=$connection peer=${client.remoteSocketAddress} acceptedPort=${client.localPort}")
            val receipt = synchronized(this) {
                invites.accept(epoch)?.takeIf { serverSocket === socket }?.also { runCatching { onDiagnostic(Event.TCP_ACCEPTED, port) } }
            }
            if (receipt == null) {
                logCtrl("CTRL-TCP: reject connection=$connection reason=stale-network")
                runCatching { client.close() }
                continue
            }
            Thread({ handleClient(client, socket, receipt, connection) }, "carplay-ctrl-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Acknowledge only a complete connect; hand it off after the ACK and network/address checks. */
    private fun handleClient(client: Socket, listener: ServerSocket, receipt: CtrlInvitationInbox.Receipt<Network>, connection: Long) {
        val peer = runCatching { client.inetAddress?.hostAddress }.getOrNull() ?: "?"
        val started = SystemClock.elapsedRealtime()
        var acknowledgingInvite = false
        try {
            val headDeadline = started + 10_000L
            var readDeadline = headDeadline
            val input = BufferedInputStream(object : FilterInputStream(client.getInputStream()) {
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    val remaining = readDeadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw SocketTimeoutException()
                    // Only on buffer refill: each blocking read uses the remaining budget.
                    client.soTimeout = remaining.toInt()
                    return super.read(bytes, offset, count)
                }
            })
            val read = CtrlHttpHead.read(input) { SystemClock.elapsedRealtime() < headDeadline }
            val head = read.text
            logCtrl("CTRL-TCP: read connection=$connection termination=${read.termination} bytes=${head.length} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - started}")
            if (head.isEmpty()) {
                logCtrl("$TAG: $peer 连入但无请求（${read.termination}）")
                logCtrl("CTRL-TCP: head-empty connection=$connection elapsedMs=${SystemClock.elapsedRealtime() - started}")
                return
            }
            httpHeads.incrementAndGet()
            val decision = read.decide()
            decision.request?.let { request ->
                val headers = request.diagnosticHeaders.joinToString("; ") { (name, value) -> "$name: $value" }
                val safeRequest = request.line.substringBefore('?').take(256)
                logCtrl("$TAG: $peer -> [$safeRequest] $headers")
            }
            logCtrl("CTRL-TCP: head connection=$connection bytes=${head.length} complete=${read.termination == "COMPLETE"} " +
                "connect=${decision.reply == CtrlHttpHead.Reply.OK} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            var reply = decision.reply
            var invite: CtrlInvitation<Network>? = null
            if (reply == CtrlHttpHead.Reply.OK) {
                val host = EndpointScope.invitationHost(peer)
                synchronized(this) {
                    when {
                        invites.current !== receipt.epoch || serverSocket !== listener -> reply = CtrlHttpHead.Reply.STALE_NETWORK
                        host == null -> reply = CtrlHttpHead.Reply.BAD_REQUEST
                        else -> {
                            invite = CtrlInvitation(host, SystemClock.elapsedRealtime(), receipt.epoch, decision.request?.receiverId)
                            invitesBeingAcknowledged.incrementAndGet()
                            acknowledgingInvite = true
                            runCatching { onDiagnostic(Event.INVITE_HEAD, port) }
                        }
                    }
                }
            }
            // Never hold the advertiser monitor across socket I/O. Recheck the epoch after flushing.
            val output = client.getOutputStream()
            output.write(reply.encode())
            output.flush()
            httpResponses.incrementAndGet()
            logCtrl("CTRL-TCP: response connection=$connection status=${reply.status} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            val candidate = invite ?: return
            synchronized(this) {
                if (invites.current !== receipt.epoch || serverSocket !== listener) {
                    logCtrl("CTRL-TCP: invitation-rejected connection=$connection reason=stale-network")
                    return
                }
                runCatching { onDiagnostic(Event.INVITE_ACK, port) }
                // An early request can be ACKed before Android reports its local Wi-Fi address.
                // Only the inbox may publish it, after advertisement and address confirmation.
                refreshAddresses(receipt.epoch)
                invites.offer(receipt, client.localAddress, candidate)
                traceDiscovery("event=ctrl-invitation attempt=$attemptId network=${receipt.epoch.network} " +
                    "connection=$connection acceptedPort=${client.localPort}")
                logCtrl("CTRL-TCP: invitation connection=$connection network=${receipt.epoch.network} " +
                    "local=${client.localAddress.hostAddress} published=${invites.latest.get() === candidate} " +
                    "awaitingAddress=${invites.awaitingConfirmation}")
            }
            invitesBeingAcknowledged.decrementAndGet()
            acknowledgingInvite = false
            // Count bounded trailing data for diagnostics only; never parse it as a second request.
            readDeadline = SystemClock.elapsedRealtime() + 3_000L
            var trailingBytes = 0
            val buffer = ByteArray(4096)
            try {
                while (trailingBytes < 64 * 1024 && SystemClock.elapsedRealtime() < readDeadline) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 64 * 1024 - trailingBytes))
                    if (count <= 0) break
                    trailingBytes += count
                }
            } catch (_: IOException) {
                // 读超时即停止
            }
            if (trailingBytes > 0) logCtrl("$TAG: 应答后收到 $trailingBytes 字节（仅统计，不转储内容）")
        } catch (error: IOException) {
            logCtrl("$TAG: $peer 连接异常 - ${error.message}")
            logCtrl("CTRL-TCP: error connection=$connection kind=${error.javaClass.simpleName} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        } finally {
            if (acknowledgingInvite) invitesBeingAcknowledged.decrementAndGet()
            runCatching { client.close() }
            logCtrl("CTRL-TCP: closed connection=$connection elapsedMs=${SystemClock.elapsedRealtime() - started}")
        }
    }

    @Synchronized
    fun stop() {
        logCtrl("CTRL-DIAG: stop ${diagnosticSummary()}")
        mdnsObserver?.close()
        mdnsObserver = null
        invites.select(null)
        srvEvidence.resetNetwork()
        previousPort = 0
        unregisterMdns()
        registeredSsid = null
        registeredNetwork = null
        serverSocket?.let { traceDiscovery("event=listener-close attempt=$attemptId closedPort=${it.localPort}") }
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        logCtrl("$TAG: 已停止")
    }
}

internal fun bindCtrlPort(preferred: Int, onFallback: (String) -> Unit): ServerSocket {
    fun listen(port: Int): ServerSocket = ServerSocket().apply {
        try {
            reuseAddress = true
            bind(InetSocketAddress(port))
        } catch (error: IOException) {
            close()
            throw error
        }
    }
    if (preferred !in 1..65535) return listen(0)
    return try {
        listen(preferred)
    } catch (error: java.net.BindException) {
        onFallback("BindException:${error.message}")
        listen(0)
    }
}
