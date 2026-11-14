package io.github.hypercar.net

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

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
 * 三条硬约束（cardeck 真车实测，改动前请先复测）：
 *  1. **TXT `srcvers` 必须是 950.7.1**：改成 catplay 的 566.25.21 后车机连 ctrl-int 探测
 *     都不再发、0x4301 永不到达（奥迪 MMI 3315 对照：950.7.1 时 0.65s 就下发）。
 *  2. **TXT `id` 必须是本机真实蓝牙 MAC**：车机按它匹配「待邀请的手机」，占位值
 *     （02:00:…）会导致永不邀请。
 *  3. Android 13+ 通过 NsdServiceInfo.network 限定本轮车机网络；较旧系统保留进程绑定。
 *
 * 服务名沿用 cardeck 验证过的 `sender`（改 `iPhone` 与 srcvers 同时变更时无法单次归因）。
 */
class CarPlayCtrlAdvertiser(
    context: Context,
    /** 本机真实蓝牙 MAC（TXT id；解析失败时返回占位值，车机不会邀请）。 */
    private val bluetoothMacProvider: () -> String,
    private val log: (String) -> Unit,
    private val onDiagnostic: (Event) -> Unit = {},
) {
    enum class Event { REGISTERED, INVITED }
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"
        private const val SERVICE_NAME = "sender"
        private const val SRCVERS = "950.7.1"
        private const val TAG = "CTRL"
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
        private const val REGISTRATION_TIMEOUT_MILLIS = 10_000L
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val registration = ServiceRegistrationState()
    val isAdvertising: Boolean get() = registration.ready

    private var registeredSsid: String? = null
    private var registeredNetwork: Network? = null
    private var registeredAtMillis = 0L
    private var registeredMac: String? = null

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
    internal class Invitation(val host: String, val receivedAtMillis: Long, val network: Network?)
    private val invitations = java.util.concurrent.atomic.AtomicReference<Invitation?>(null)
    internal val lastInvitation: Invitation? get() = invitations.get()
    val lastInvitePeerIp: String? get() = lastInvitation?.host
    internal fun discardInvitation(expected: Invitation): Boolean = invitations.compareAndSet(expected, null)

    /** 已注册的端口（-1 = 未启动）。 */
    val port: Int get() = serverSocket?.localPort ?: -1

    /** Keep the same service alive across ACL edges and polling; replace only a changed identity or failed registration. */
    @Synchronized
    fun ensureForNetwork(ssid: String, network: Network? = null): Boolean {
        val effectiveNetwork = network ?: registeredNetwork
        val currentMac = bluetoothMacProvider()
        val same = registeredSsid == ssid && registeredNetwork == effectiveNetwork &&
            registeredMac.equals(currentMac, ignoreCase = true)
        val age = SystemClock.uptimeMillis() - registeredAtMillis
        val socket = serverSocket?.takeUnless { it.isClosed }
        if (socket != null && registration.canReuse(same, age, REGISTRATION_TIMEOUT_MILLIS)) {
            ensureAcceptThread(socket)
            return true
        }
        if (registeredNetwork != effectiveNetwork) invitations.set(null)
        if (registrationListener != null) {
            log("$TAG: 更新 mDNS 注册 reason=${if (!same) "network/identity changed" else "registration/listener unavailable"} network=$effectiveNetwork port=$port")
            unregisterMdns()
        }
        registeredSsid = ssid
        registeredNetwork = effectiveNetwork
        // A rejected request is retried by the existing wait loop, without blocking or withdrawing a healthy service.
        return start()
    }

    /** Bluetooth is an independent transport: its edges must not withdraw a healthy Wi-Fi service. */
    @Synchronized
    fun nudgeWhileWaiting(reason: String, ssid: String?, network: Network?): Boolean {
        if (ssid.isNullOrBlank() || network == null || lastInvitePeerIp != null) return false
        val token = registration.currentGeneration
        val ensured = ensureForNetwork(ssid, network)
        if (ensured) {
            log("$TAG: 车机状态变化（$reason）——${if (token == registration.currentGeneration) "保留现有 mDNS 注册，无注销重播" else "修复/建立 mDNS 注册"} network=$network port=$port")
        }
        return ensured
    }

    private fun ensureAcceptThread(socket: ServerSocket) {
        if (acceptThread?.isAlive != true) {
            acceptThread = Thread({ acceptLoop(socket) }, "carplay-ctrl-accept").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun start(): Boolean {
        // socket 与 accept 线程**只在第一次创建**，之后重注册一直复用（端口稳定）
        val socket =
            serverSocket?.takeUnless { it.isClosed }
                ?: try {
                    ServerSocket(0).also {
                        serverSocket = it
                    }
                } catch (error: Exception) {
                    log("$TAG: 监听端口创建失败 - ${error.message}")
                    return false
                }
        ensureAcceptThread(socket)
        if (registration.ready || registration.pending) return true
        val token = registration.begin()
        return try {
            val mac = bluetoothMacProvider()
            val info =
                NsdServiceInfo().apply {
                    serviceName = SERVICE_NAME
                    serviceType = SERVICE_TYPE
                    setPort(socket.localPort)
                    // 车机在 WiFi 上唯一能看到的手机身份就是这两个 TXT 字段
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
                            runCatching { onDiagnostic(Event.REGISTERED) }
                            log("$TAG: mDNS 注册成功 ${serviceInfo.serviceName} network=$registeredNetwork port=$port")
                        }
                    }

                    override fun onRegistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        synchronized(this@CarPlayCtrlAdvertiser) {
                            if (!registration.failed(token)) return
                            log("$TAG: mDNS 注册失败 code=$errorCode network=$registeredNetwork（等待期间重试）")
                        }
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        synchronized(this@CarPlayCtrlAdvertiser) {
                            if (registration.failed(token)) log("$TAG: 当前广播已注销，等待期间重试")
                        }
                    }

                    override fun onUnregistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) = Unit
                }
            registrationListener = listener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registeredAtMillis = SystemClock.uptimeMillis()
            registeredMac = mac
            log(
                "$TAG: 已监听 :${socket.localPort}，提交广播 $SERVICE_TYPE network=$registeredNetwork " +
                    "name=$SERVICE_NAME id=$mac srcvers=$SRCVERS" +
                    if (mac == PLACEHOLDER_MAC) "（⚠ 占位 MAC：车机不会邀请）" else "",
            )
            true
        } catch (error: Exception) {
            registration.failed(token)
            log("$TAG: 启动失败 - ${error.message}")
            false
        }
    }

    /** 只注销 mDNS 注册（保留 socket 与 accept 线程，端口不变）。 */
    @Synchronized
    private fun unregisterMdns() {
        registration.invalidate()
        val previous = registrationListener
        registrationListener = null
        registeredMac = null
        previous?.let { runCatching { nsdManager.unregisterService(it) } }
    }

    private fun acceptLoop(socket: ServerSocket) {
        // 循环条件不能绑 `registered`：重注册期间 registered 会短暂为 false，
        // 旧实现会让 accept 线程退出、之后再也没人接车机的 ctrl 邀请
        while (!socket.isClosed) {
            val client =
                try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
            Thread({ handleClient(client) }, "carplay-ctrl-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 应答车机探测：读请求头 → 记请求行 → 一律回 `200 OK`（空体）。
     * `GET /ctrl-int/1/connect` 是「车机已发现手机、AirPlay 窗口已开」的权威信号，
     * 记进日志并计数（上层据此判断是否该立刻去连 AirPlay）。
     */
    private fun handleClient(client: Socket) {
        val peer = runCatching { client.inetAddress?.hostAddress }.getOrNull() ?: "?"
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readUntilHeaderEnd(input)
            if (head.isEmpty()) {
                log("$TAG: $peer 连入但无请求（EOF）")
                return
            }
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val headers =
                head.substringAfter("\r\n", "")
                    .lineSequence()
                    .filter { it.contains(':') }
                    .joinToString("; ") { it.trim() }
            log("$TAG: $peer -> [$requestLine] $headers")
            if (requestLine.startsWith("GET /ctrl-int/1/connect")) {
                val receiverId =
                    head.lineSequence()
                        .firstOrNull {
                            it.substringBefore(':').trim().equals("AirPlay-Receiver-Device-ID", ignoreCase = true)
                        }?.substringAfter(':')
                        ?.trim()
                inviteCount++
                runCatching { onDiagnostic(Event.INVITED) }
                EndpointScope.invitationHost(peer)?.let { host ->
                    invitations.set(Invitation(host, SystemClock.elapsedRealtime(), registeredNetwork))
                }
                log("$TAG: ✓ 车机 ctrl 邀请（第 $inviteCount 次，receiver=${receiverId ?: "?"}）——AirPlay 窗口已开启")
            }
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            // 只 write+flush，**不要 close**：Socket 的输出流一关，后面的读就直接 EOF，
            // 车机邀请后可能继续发隧道数据，那条转储就永远拿不到
            val output = client.getOutputStream()
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
            // 应答后车机可能继续发隧道数据（后续按需实现），先转储前 64 字节便于定谳
            client.soTimeout = 3_000
            val rest = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                    if (rest.size() > 64 * 1024) break
                }
            } catch (_: IOException) {
                // 读超时即停止
            }
            if (rest.size() > 0) {
                log(
                    "$TAG: 应答后收到 ${rest.size()} 字节：" +
                        rest.toByteArray().take(64).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) },
                )
            }
        } catch (error: IOException) {
            log("$TAG: $peer 连接异常 - ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val count =
                try {
                    input.read(chunk)
                } catch (_: IOException) {
                    break
                }
            if (count < 0) break
            buffer.write(chunk, 0, count)
            val text = buffer.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.toString("ISO-8859-1")
    }

    @Synchronized
    fun stop() {
        unregisterMdns()
        registeredSsid = null
        registeredNetwork = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        log("$TAG: 已停止")
    }
}
