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
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * 发 iAP2 前是否主动断开该车机的 HFP/A2DP（车机档案 `dropProfilesBeforeIap2`）。
     *
     * 奥迪需要；**宝马不能断**（2026-10-25 现场：断了会把 ACL 一起拽掉，车机反复重连，
     * 反而不再发起 CarPlay）。
     */
    private val dropProfilesBeforeIap2: Boolean = true,
    private val allowOutboundIap2: Boolean = true,
    private val preferredProvider: () -> String? = { null },
    private val log: (String) -> Unit,
    /** 每次拿到/更新端点即回调（0x5703 凭据与 0x4301 端点都会触发，可能多次）。 */
    private val onEndpoint: (CarPlayEndpoint) -> Unit,
    /** 会话结束（拿到端点后链路断开，或中途失败）——上层用来收尾/重排。 */
    private val onSessionEnded: (String) -> Unit,
) {
    companion object {
        private val HANDSHAKE_IDS = java.util.concurrent.atomic.AtomicLong(0)
        /** 手机侧（我们监听）的 iAP2 服务 UUID；配件端（车机监听）是 …decacaff。 */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SDP_NAME = "iAP2"
        private const val SESSION_TIMEOUT_MILLIS = 90_000L
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
    }

    private val appContext = context.applicationContext

    private val generation = AtomicLong(0)

    @Volatile
    private var running = false

    /** 当前会话占用的 socket（stop/notifyBluetoothLost 关它来打断阻塞读写）。 */
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)
    private val pendingDial = AtomicReference<BluetoothSocket?>(null)

    /** 当前会话的链路通道（主动结束会话用）。 */
    private val activeChannel = AtomicReference<RfcommLinkChannel?>(null)

    private val serverSocket = AtomicReference<BluetoothServerSocket?>(null)

    @Volatile
    private var serverThread: Thread? = null

    @Volatile
    private var clientThread: Thread? = null

    /**
     * 会话活跃门闸（AtomicBoolean：accept 与 connect 两侧的会话启动存在竞态，
     * 必须用 CAS 保证「只有一条 RFCOMM 会话」——车机通常只有单条无线 iAP2 上下文，
     * 双向同时建链会让双方互相踩，cardeck 实测车机频繁拆链）：
     * 任一 RFCOMM 会话运行期间，出站循环暂停连接尝试。
     */
    private val sessionActive = java.util.concurrent.atomic.AtomicBoolean(false)

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
    private var sessionProductive = false

    /** 「是否提供 iAP2 服务」结论缓存（地址→结论）：SDP 查询不便宜，勿每 3s 重查。 */
    private val iap2ServiceCache = ConcurrentHashMap<String, Boolean>()

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    val isRunning: Boolean get() = running

    /** 当前是否有会话在跑（上层诊断用）。 */
    val isSessionActive: Boolean get() = sessionActive.get()

    /** 本次 start() 以来成功建过几轮 iAP2 会话（0 = 车机从未连入/从未连上，诊断用）。 */
    val sessionStartCount: Int get() = sessionStarts.get()

    private val sessionStarts = java.util.concurrent.atomic.AtomicInteger(0)

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** 启动监听（入站）+ 出站循环。返回 false 表示蓝牙不可用/缺权限。 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter = adapterOrNull()
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        iap2ServiceCache.clear()
        skippedNoService.clear()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT: 启动——监听 $SDP_NAME（…cafe，等车机连入）" +
                (if (allowOutboundIap2) " + 出站尝试车机 iAP2 服务（…caff）" else "，仅被动监听") +
                if (target != null) {
                    "，目标：$target（只连/只收该设备）"
                } else {
                    if (allowOutboundIap2) "（自动：只尝试已配对且声明 iAP2 服务的设备）" else "（等车机发起，不操作其它已配对设备）"
                },
        )
        try {
            serverSocket.set(adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID))
        } catch (error: IOException) {
            log("BT: 监听失败 - ${error.message}")
            running = false
            return false
        }
        Thread({ acceptLoop(myGeneration) }, "iap2-bt-accept").apply {
            isDaemon = true
            serverThread = this
            start()
        }
        if (allowOutboundIap2) {
            Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
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
        if (!running) return
        running = false
        generation.incrementAndGet()
        serverSocket.getAndSet(null)?.let { socket -> runCatching { socket.close() } }
        dropCurrentSession()
        serverThread?.interrupt()
        clientThread?.interrupt()
        serverThread = null
        clientThread = null
        log("BT: 已停止（监听与出站均已关闭）")
    }

    /**
     * 只结束当前会话、保留监听/出站循环（ACL 断开时调用）。
     * 车机蓝牙断开后，旧 RFCOMM socket 的读会卡在半开连接上不返回——必须主动关。
     */
    fun dropCurrentSession() {
        pendingDial.getAndSet(null)?.let { socket -> runCatching { socket.close() } }
        activeChannel.getAndSet(null)?.let { channel -> runCatching { channel.close() } }
        currentSocket.getAndSet(null)?.let { socket -> runCatching { socket.close() } }
    }

    fun onTargetChanged() {
        // 不碰 currentSocket：入站会话可能恰好在此时建立，只取消尚在 connect() 的拨号。
        pendingDial.getAndSet(null)?.let { runCatching { it.close() } }
        log("BT: 目标已更新 explicit=${targetProvider() ?: "自动"} hint=${preferredProvider() ?: "无"} active=${sessionActive.get()}")
    }

    // ---- 入站：车机连入 ----

    @SuppressLint("MissingPermission")
    private fun acceptLoop(myGeneration: Long) {
        while (running && generation.get() == myGeneration) {
            val socket: BluetoothSocket =
                try {
                    serverSocket.get()?.accept() ?: break
                } catch (error: IOException) {
                    if (running && generation.get() == myGeneration) log("BT: accept 失败 - ${error.message}")
                    break
                }
            if (!running || generation.get() != myGeneration) {
                runCatching { socket.close() }
                return
            }
            val peerAddress = safeAddress(socket.remoteDevice)
            // 指定了目标车机时拒绝其它设备的连入（多台车机场景：非目标的控制器
            // 也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            if (!io.github.hypercar.bluetooth.CarTargetPolicy.acceptsIncoming(target, peerAddress)) {
                log("BT-S: 忽略非目标设备连入 addr=${peerAddress ?: "?"}（当前目标：$target）")
                runCatching { socket.close() }
                continue
            }
            if (sessionActive.get()) {
                // **本轮已拿到凭据（端点）**才拒绝新连入；还在等凭据的「空转轮次」必须让位：
                // 车机只有在**新一轮** iAP2 里才会给档案 B 的能力清单，而我们又不肯为了
                // 换轮去主动拆链（拆链会让车机收起 CarPlay 图标，2026-10-25 现场定谳）。
                // 所以由车机发起的新连接来顶替旧会话——既不拆链，也不挡路。
                if (sessionProductive) {
                    log("BT-S: 已有 iAP2 会话在跑且已拿到凭据，忽略新的连入 addr=${peerAddress ?: "?"}")
                    runCatching { socket.close() }
                    continue
                }
                log("BT-S: 车机重新连入（上一轮仍在等凭据）——结束旧会话让位给新一轮 addr=${peerAddress ?: "?"}")
                dropCurrentSession()
                sleepQuietly(500)
                if (sessionActive.get()) {
                    log("BT-S: 旧会话未在 500ms 内退出，忽略本次连入 addr=${peerAddress ?: "?"}")
                    runCatching { socket.close() }
                    continue
                }
            }
            val peerName = safeName(socket.remoteDevice) ?: peerAddress ?: "unknown"
            log("BT-S: 车机已连入 name=$peerName，开始 iAP2 发送端会话")
            Thread({ runSession(socket, "BT-S") }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    // ---- 出站：主动连入车机 ----

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter = adapterOrNull() ?: return
        var inboundGraceDone = false
        while (running && generation.get() == myGeneration) {
            // 会话期间静默：两条 RFCOMM 会互相踩（见 sessionActive 注释）
            if (sessionActive.get()) {
                sleepQuietly(2_000)
                continue
            }
            // 首轮先给车机主动连入的机会——但只对「会因此断配置文件」的档案有意义：
            // 宝马档案压根不断 HFP/A2DP（车机自己会在 ACL 连上后 ~4s 连入 iAP2），
            // 等这 8s 只会白白推迟出站探测。
            if (dropProfilesBeforeIap2 && !inboundGraceDone) {
                inboundGraceDone = true
                val deadline = SystemClock.uptimeMillis() + INBOUND_GRACE_MILLIS
                while (running && generation.get() == myGeneration &&
                    !sessionActive.get() && SystemClock.uptimeMillis() < deadline
                ) {
                    sleepQuietly(250)
                }
                if (sessionActive.get() || !running || generation.get() != myGeneration) continue
                log("BT-C: ${INBOUND_GRACE_MILLIS / 1000}s 内车机未主动连入——改走出站（会先断开该车机的 HFP/A2DP）")
            }
            val explicitTarget = targetProvider()
            val preferred = preferredProvider()
            val target = io.github.hypercar.bluetooth.CarTargetPolicy.outgoingTarget(explicitTarget, preferred)
            val candidates = candidateDevices(adapter, target)
            if (candidates.isEmpty()) {
                if (target != null) {
                    log("BT-C: 目标 $target 不在已配对列表（或未声明 iAP2 服务）——请先在系统设置里与车机完成配对")
                    sleepQuietly(RETRY_DELAY_MILLIS * 4)
                } else {
                    log("BT-C: 没有「已配对且声明 iAP2 服务」的设备——请先与车机完成系统蓝牙配对")
                    sleepQuietly(RETRY_DELAY_MILLIS * 4)
                }
                continue
            }
            var connected: BluetoothSocket? = null
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                if (sessionActive.get()) break
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
                val evidence = io.github.hypercar.session.PairingEvidence.read(appContext.filesDir, device.address)
                if (dropProfilesBeforeIap2 && evidence.mayDropProfiles) {
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
                    log("BT-C: 保留 HFP/A2DP：档案允许断开=$dropProfilesBeforeIap2 身份=${evidence.identityPresent} 本车凭证=${evidence.receiverCredentialPresent}；首配/未知状态不主动断配置文件")
                }
                val socket = tryConnect(device)
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
            runSession(connected, "BT-C")
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
    ): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        if (target != null) {
            // 目标策略：地址命中即唯一候选——用户显式指定就该尝试（SDP 缓存可能缺失）
            return bonded.filter { it.address.equals(target, ignoreCase = true) }
        }
        return bonded.filter { device ->
            if (awaitIap2Service(device)) {
                true
            } else {
                if (skippedNoService.add(device.address)) {
                    log("BT-C: 跳过「${safeName(device) ?: device.address}」（未发现 iAP2 服务——非 CarPlay 设备，保持其音频连接）")
                }
                false
            }
        }
    }

    /**
     * 设备是否提供配件端 iAP2 服务（…caff）：先读系统 uuids 缓存，缺失再
     * `fetchUuidsWithSdp` 触发一次 SDP 查询并等 `ACTION_UUID` 广播（超时按无服务）。
     * 结论缓存到下一次 start()——车机固件升级换 UUID 的场景由重启覆盖。
     * 权限：调用方已校验 BLUETOOTH_CONNECT；SecurityException 由 runCatching 吸收。
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Boolean {
        iap2ServiceCache[device.address]?.let { return it }

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return true
        }
        val latch = CountDownLatch(1)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address == device.address) latch.countDown()
                }
            }
        val started =
            runCatching {
                val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(receiver, filter)
                }
                device.fetchUuidsWithSdp()
            }.getOrDefault(false)
        if (started) {
            try {
                latch.await(SDP_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        runCatching { appContext.unregisterReceiver(receiver) }
        val has = cachedHas()
        iap2ServiceCache[device.address] = has
        return has
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (_: IOException) {
                return null
            }
        // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
        // stop()/dropCurrentSession() 只有拿到 socket 引用才能 close() 打断它。
        currentSocket.set(socket)
        pendingDial.set(socket)
        return try {
            socket.connect()
            socket
        } catch (_: IOException) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            null
        } finally {
            pendingDial.compareAndSet(socket, null)
        }
    }

    // ---- 会话 ----

    private fun runSession(
        socket: BluetoothSocket,
        tag: String,
    ) {
        if (!sessionActive.compareAndSet(false, true)) {
            // 竞争兜底（accept 与 connect 两侧可能同时启动会话）：后来者直接让位
            log("$tag 已有 iAP2 会话在跑，本连接不建会话")
            runCatching { socket.close() }
            return
        }
        sessionStarts.incrementAndGet()
        sessionProductive = false
        currentSocket.set(socket)
        // 记住本轮对端车机（ACL 广播可能没收到，记录「已配对车机」要靠它拿 MAC）
        runCatching { Iap2Peer.remember(safeAddress(socket.remoteDevice), safeName(socket.remoteDevice)) }
        val handshakeId = HANDSHAKE_IDS.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        val sessionLog: (String) -> Unit = { line -> log("HS#$handshakeId +${SystemClock.elapsedRealtime() - started}ms $line") }
        val evidence = io.github.hypercar.session.PairingEvidence.read(appContext.filesDir, safeAddress(socket.remoteDevice))
        sessionLog("BEGIN direction=$tag target=${targetProvider() ?: "自动"} identity=${evidence.identityPresent} receiverCredential=${evidence.receiverCredentialPresent}；无本车凭证=首次或未知，不主动断系统配置文件")
        sessionLog("ROLE: iAP2 Device/iPhone，SDP=…cafe，deviceName=iPhone，保留无线可用性/传输身份声明")
        val channel = RfcommLinkChannel(socket, sessionLog)
        activeChannel.set(channel)
        val session =
            Iap2SenderSession(
                channel = channel,
                log = sessionLog,
                bluetoothMac = bluetoothMacProvider(),
                deviceName = "iPhone",
                // 端点回调顺手标记「本轮已拿到凭据」：acceptLoop 用它决定是否允许
                // 车机的新连入顶替本轮（空转轮次可顶替，有凭据的不许抢）
                onEndpoint = { endpoint ->
                    if (!endpoint.ssid.isNullOrBlank() || endpoint.ipAddresses.isNotEmpty()) {
                        sessionProductive = true
                    }
                    onEndpoint(endpoint)
                },
                context = appContext,
                // **必须显式传当前车机档案**：不传就一直用构造函数默认值 BMW_ID85，
                // 设置页切成 通用(0.27)/WinPlay/Audi（或远程下发的档案）后，
                // 0x4300 / EA / 0x4E0E / 档案A退场这些差异全都不生效（2026-10-25 定谳）。
                profile = io.github.hypercar.headunit.HeadUnitProfiles.current(),
            )
        try {
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
            sessionLog("END alive=${channel.isAlive} cancelled=${channel.isCancelled} reason=${channel.closeReason ?: "会话层退出"}")
            activeChannel.compareAndSet(channel, null)
            currentSocket.compareAndSet(socket, null)
            runCatching { channel.close() }
            runCatching { socket.close() }
            sessionActive.set(false)
            log("$tag 会话结束（RFCOMM 已关闭；监听与出站循环仍在运行）")
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
    private val socket: BluetoothSocket,
    private val log: (String) -> Unit,
) : Iap2LinkChannel, Closeable {
    private val engine = Iap2LinkEngine()
    private val lock = Any()
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var terminated = false

    @Volatile
    private var terminationReason: String? = null

    @Volatile
    private var locallyCancelled = false

    override val closeReason: String? get() = terminationReason
    override val isCancelled: Boolean get() = locallyCancelled

    private var readySignalled = false

    /** 逐帧摘要日志预算（每方向每连接，防刷屏）。 */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0

    override val isAlive: Boolean get() = !terminated

    init {
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

    override fun awaitReady(timeoutMillis: Long): Boolean {
        try {
            if (!readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("iAP2 链路协商已取消").apply { initCause(error) }
        }
        return !terminated
    }

    override fun peerMaxControlPayload(): Int? =
        synchronized(lock) {
            engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1)
        }

    override fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean,
    ): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck)
            drainOutputLocked()
            return accepted
        }
    }

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
            if (!terminated) locallyCancelled = true
            finishLocked(null)
        }
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            synchronized(lock) {
                engine.start(nowMillis())
                drainOutputLocked()
            }
            val buffer = ByteArray(4096)
            val input: InputStream = socket.inputStream
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    finishLocked(IOException("RFCOMM 对端关闭（EOF）"))
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (terminated) return
                    engine.feed(buffer.copyOf(count), nowMillis())
                    processEventsLocked()
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            finishLocked(error)
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

                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(IOException(event.reason ?: "link died"))
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked() {
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return
        try {
            socket.outputStream.write(bytes)
        } catch (error: IOException) {
            finishLocked(error)
        }
        signalReadyLocked()
    }

    private fun signalReadyLocked() {
        if (!readySignalled && engine.state == Iap2LinkEngine.State.NORMAL) {
            readySignalled = true
            readyLatch.countDown()
        }
    }

    /** 可重入（close 可能从任意线程调用）：置终止位、清队列、关 socket。 */
    private fun finishLocked(reason: Throwable?) {
        synchronized(lock) {
            if (terminated) return
            terminationReason = reason?.message ?: if (locallyCancelled) "本地取消" else "链路结束"
            terminated = true
            controls.offer(POISON) // 哨兵：唤醒阻塞的接收方（LBQ 不接受 null）
            readyLatch.countDown()
            runCatching { socket.close() }
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
    }
}

