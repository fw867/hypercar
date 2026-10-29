package io.github.hypercar.iap2

import java.io.IOException
import java.io.InterruptedIOException
import java.security.SecureRandom
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/**
 * 会话层看到的链路通道（由 [Iap2Bluetooth] 用 RFCOMM + [Iap2LinkEngine] 实现；
 * 抽成接口便于单测注入 mock 时序）。
 */
interface Iap2LinkChannel {
    /** 等待链路层协商完成（NORMAL）。 */
    fun awaitReady(timeoutMillis: Long): Boolean

    /** 对端协商的最大控制载荷（已扣除帧头与校验和）；协商前为 null。 */
    fun peerMaxControlPayload(): Int?

    /** 排入一个完整控制载荷；链路已死返回 false。[awaitAck]=false 为发后不管。 */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean

    /** 取下一个控制载荷；超时或链路结束返回 null。 */
    fun recvControl(timeoutMillis: Long): ByteArray?

    val isAlive: Boolean

    /** 传输层保留的关闭原因；不包含认证报文或密钥。 */
    val closeReason: String? get() = null
    val isCancelled: Boolean get() = false

    fun close()
}

/**
 * 车机本次为「档案 A」：0x1D01 里没有任何无线 CarPlay 能力（无 0x5703/0x4301，
 * EA 里也没有 `com.bmw.carplay`）。此时继续等、继续发请求都不会有结果——
 * 但**也不能一直握着这条会话**：车机下一次连入会被 [Iap2Bluetooth] 的
 * `sessionActive` 门闸挡掉，而车机的下一轮很可能就是档案 B（BMW 实测 A/B 交替）。
 */
class NoCarPlayWindowException(message: String) : IOException(message)

/**
 * iAP2 发送端（iPhone 角色）控制会话：走通无线 CarPlay 的 bring-up 序列，
 * 产出 [CarPlayEndpoint]（热点凭据 + AirPlay 端点）交给上层。
 *
 * 序列（BMW iD8.5/MGU-86 实测次序，见 §9j）：
 *   0x1D00 → 0x1D01 → 0x1D02（识别）
 *   0xAA00 → 0xAA01 → 0xAA02 → 0xAA03 → 0xAA05（MFi，跳过证书/签名校验）
 *   **0xAA05 之后立即突发设备状态问候** 0x4E0C/0x4E0A/0x4E0B/0x4E09（§9j 核心修复：
 *   真 iPhone 就是这个次序；旧实现把问候排在 0x5703 等待之后，BMW 等问候、我们等
 *   0x5703，5.4s 后车机判 accessory 未就绪拆链）
 *   0xEA00（EA 会话：param1 必须是**协议名字符串**——§9j 第三证据；车机声明的
 *   每一条 EA 协议都要各开一次，BMW MGU-55 一次声明 7 条）
 *   0x5702 → 0x5703（按能力清单，拿热点 SSID/密码/信道）
 *   0x4300 → 0x4301（按能力清单，拿 AirPlay 端口/公钥/地址）
 *   进入保活泵：0x4301 是生命周期事件，重复到达都回调 onEndpoint；
 *   车机没下发凭据时**不拆链**，按节奏重探并 dump 未知消息体（BMW MGU-55 实测：
 *   清单里根本没有 0x57xx/0x43xx，只有私有 0x4C00..0x4C07——凭据若存在只会在那里）
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机真实蓝牙 MAC（0x4300/0x4E0E 的传输标识、0x4E0C UUID 派生源；真车会校验）。 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 本机蓝牙名（0x4E09 设备信息）。 */
    private val deviceName: String = "iPhone",
    /** 每次拿到/更新端点即回调（0x5703 与 0x4301 各触发一次，保活期 0x4301 重发也触发）。 */
    private val onEndpoint: (CarPlayEndpoint) -> Unit,
    /** 仅用于 MFi 挑战长度的按车机持久化记忆；null = 只用进程内内存表（纯 JVM 单测）。 */
    private val context: android.content.Context? = null,
    /** 车机档案：品牌特有的行为差异都从这里读（见 `headunit/HeadUnitProfile.kt`）。 */
    private val profile: io.github.hypercar.headunit.HeadUnitProfile =
        io.github.hypercar.headunit.HeadUnitProfiles.BMW_ID85,
    /** 单调时钟，可在时序回归测试中控制超时。 */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val parser = Iap2Csm.Parser()
    private var phaseName = "IDLE"
    private var phaseStarted = nanoTime()
    private var tracedReceivedFrames = 0

    private fun enterPhase(name: String) {
        val now = nanoTime()
        log("PHASE $phaseName -> $name previousElapsed=${(now - phaseStarted) / 1_000_000L}ms")
        phaseName = name
        phaseStarted = now
    }

    /** 刚解析、尚未分类的帧（到达顺序）。 */
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /**
     * [expectOrNull] 等待期间取到的「非预期」帧暂存区；[recvFrame] 优先从这里取。
     *
     * **必须与 [receivedFrames] 分开**：两者若混用，expectOrNull 把非目标帧塞回
     * 它自己下一轮优先读取的队列，就会同一帧反复取出—记录—塞回形成活锁
     * （cardeck 2026-10-18 实测：车机 bring-up 期间发 0x5000，日志 ~1000 行/秒刷屏
     * 直到等待超时）。
     */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 是否已经打过「开始回应电源更新」的日志（车机周期发 0xAE00，日志只留第一条）。 */
    private var powerUpdateReplied = false

    /** 暂存区上限：车机订阅类消息可能在长等待期间持续到达，无上限会吃内存。 */
    private val maxPendingFrames = 256
    private var pendingOverflowLogged = false

    private val random = SecureRandom()

    /** 0x5703 解析结果（保活期车机重发 0x5703 时需重新应答/回调）。 */
    @Volatile
    private var wifi: Iap2Messages.WifiConfiguration? = null

    /** 0x4301 是否已产出过端点（区分「首达」与「重发」日志）。 */
    private var endpointEmitted = false

    /** 0x4301 原始体是否已记录（每轮会话一次，避免周期补发刷屏）。 */
    private var startSessionRawLogged = false

    /** 最近一次晚到 0x4301 的端点指纹（车机周期重发相同内容时不重复打详情）。 */
    private var lastLateStartSessionSignature: Int? = null

    /** 设备状态问候是否已发过（一轮一次；force=true 才重发，§9j 救急重发用）。 */
    private var greetingsSent = false

    /** 本轮识别出的 EA 协议（保活期重新识别/重开 EA 会话时要用，故留档）。 */
    private var eaProtocols: List<Iap2Messages.EaProtocol> = emptyList()

    /** 车机是否回过 0xEA03（EA 会话确认）；没回过才有必要重发 0xEA00。 */
    private var eaSessionConfirmed = false

    /** 保活期「等不到热点凭据」时的重探节奏（见 [pokeWhileWaiting]）。 */
    private var lastPokeNanos = 0L
    private var pokeCount = 0

    /** 已 dump 过原始体的消息 id（每个 id 一次，未知消息的报文结构只能靠它定谳）。 */
    private val bodyDumped = HashSet<Int>()

    /** 车机最近一次识别结果（保活期重探按能力清单决定发哪条，不能硬塞）。 */
    @Volatile
    private var peerIdentification: Iap2Messages.Identification? = null

    private companion object {
        /** MFi 挑战阶梯（长度, 首次试探窗口）：DiPlay/Audi/BMW 双端实测顺序。 */
        private val MFI_ATTEMPTS = listOf(32 to 3_000L, 20 to 6_000L, 16 to 3_000L)

        /**
         * 已知「静默过」的档位重试窗口。首次试探保留原窗口（可能是慢车机），
         * 但已经失败过一次的档位没必要再等满 6 秒——真 iPhone 应答在 100ms 级。
         */
        const val MFI_SILENT_RETRY_WINDOW_MS = 1_500L

        /** 等 0x4301 的短窗口（声明会发的车机毫秒级应答；没声明的直接跳过）。 */
        const val START_SESSION_WAIT_NANOS = 3_000_000_000L

        /**
         * 没有 0x5703（新协议车机：AP 信息只在 0x4301 里）时的等待窗口。
         * 这类车机必须先把热点信息给手机，手机才可能入网——窗口太短等于直接判死。
         */
        const val START_SESSION_WAIT_NEW_PROTOCOL_NANOS = 8_000_000_000L

        /** 0x4301 原始体日志上限（Audi 实测 261B，完整记录；异常大的只截前段）。 */
        const val START_SESSION_RAW_LOG_BYTES = 1_024

        /** 0x5703 分段等待：1.5s 未到先重发问候，再 1.3s 未到重发一次 0x5702（§9j 处置 3/4）。 */
        const val WIFI_RESCUE_GREETING_MILLIS = 1_500L
        const val WIFI_RESCUE_RETRY_MILLIS = 1_300L
        const val WIFI_EXCHANGE_BUDGET_MILLIS = 5_000L

        /** 保活期空闲轮询间隔：必须短于重探周期，否则「一个包都不来」时轮不到重探。 */
        const val IDLE_POLL_MILLIS = 1_000L

        /** 等不到热点凭据时的重探节奏：前 12 次 5s（覆盖上车点屏幕那一分钟），之后 30s。 */
        val POKE_INTERVAL_NANOS = 5_000_000_000L
        val POKE_INTERVAL_SLOW_NANOS = 30_000_000_000L

        /** 0xEA00 没被 0xEA03 确认时最多补开几轮（再多会变成骚扰车机）。 */
        const val EA_REOPEN_ATTEMPTS = 6
        /**
         * 是否开 iAP2 EA 会话（0xEA00）。
         *
         * 2026-10-24 用真 iPhone 的 HCI 日志对照：iPhone 一个都不发，车机也从不回 0xEA03；
         * CarPlay 的媒体面走 AirPlay（Wi-Fi），不依赖 EA 隧道。默认关闭以减少与车机状态机的分歧；
         * 若某车型确实需要 EA 隧道，改 true 即恢复（EA_REOPEN_ATTEMPTS 的重发逻辑一并生效）。
         */
        const val ENABLE_EA_SESSIONS = false
        /**
     * 是否发 0x4E0E（设备传输标识）。
     *
     * 真 iPhone 不发它（HCI 实测），MGU-55 也只是 ACK，故默认关闭。
     */
        const val SEND_TRANSPORT_IDENTIFIER = false

        /** 未知消息体 dump 上限（够看清 TLV 结构，又不刷屏）。 */
        const val BODY_DUMP_BYTES = 256

        /** 等 0x6800 StartHID 的窗口（车机声明会发才等；应答 0x6801）。 */
        const val HID_WAIT_NANOS = 1_500_000_000L
    }

    /**
     * 跑完整个 bring-up，返回（可能不完整的）端点。
     *
     * **拿不到热点凭据/端点不是错误**：车机（如 BMW MGU-55）可能只在用户点了车机上的
     * Apple CarPlay 之后才下发 0x5703/0x4301，所以这里返回空端点、由
     * [pumpIncomingForever] 继续守着链路等——不能再 throw 把 RFCOMM 关掉
     * （关掉 → 车机断 ACL → 上层拆会话 → 3s 后重连，形成连-断死循环）。
     *
     * @throws IOException 识别/链路超时（链路本身不可用）
     */
    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        enterPhase("LINK_NEGOTIATION")
        val deadline = nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(remainingMillis(deadline))) {
            throw IOException("iAP2 链路协商超时")
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")

        // ---- 识别 ----
        enterPhase("IDENTIFICATION")
        send(Iap2MessageIds.START_IDENTIFICATION)
        val identFrame = expect(Iap2MessageIds.IDENTIFICATION_INFORMATION, deadline)
        val ident = Iap2Messages.parseIdentification(identFrame.body)
        peerIdentification = ident
        logIdentification(ident, identFrame)
        logWirelessCarPlayProfile(ident)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        log("iap2 tx=0x1d02 identification-accepted")

        // ---- MFi（跳过校验：0xAA05 自报成功）----
        enterPhase("MFI_EXCHANGE")
        val identityText = listOfNotNull(ident.name, ident.model, ident.manufacturer).joinToString(" ").lowercase()
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size}（跳过校验）")
        val mfiResult = runMfiChallenge(deadline, receiverKey = MfiLengthMemory.receiverKey(identityText))
        ensureActive("MFi 结果发送前")
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 compatibility-result=$mfiResult（未做证书/签名验证，不代表标准认证通过）")

        // ---- 设备状态问候：必须**紧贴** 0xAA05 突发（§9j 核心修复）----
        // 真 iPhone 在 0xAA05 之后立刻发 0x4E0C/0x4E0A/0x4E0B/0x4E09，然后才轮到
        // 订阅/请求。旧实现排在 0x5703 等待之后：BMW 等问候、我们等 0x5703，
        // 两轮实测稳定 5.37~5.45s 后车机拆 RFCOMM（另一台 MGU-86 五轮全败同签名）。
        sendDeviceStateGreetings()
        enterPhase("EA_AND_HID")

        // ---- EA 会话（MFi 完成后发；认证前发会噎死 DiPlay）----
        // BMW iD8.5 档案 B 声明 com.bmw.carplay protocol=8；档案 A 声明 de.bmw.a4a.bt。
        // param1 必须是**协议名字符串**：旧代码发成 u16 协议号，车机不回 0xEA03、
        // CarPlay 数据通道不打开（§9j 第三证据，对所有车机都存在的错误）。
        //
        // BMW MGU-55 实测（2026-10-24）声明了 7 条 EA 协议（id 全部是 10）——
        // 旧解析把重复 id 压成一条、只开最后那条（de.bmw.a4a.bt）；de.bmw.a4a.pairing
        // 这条「配对通道」从未被打开过。解析已修（[Iap2Messages.parseIdentification]），
        // 这里逐条开、逐条记，日志里能直接看到车机回过哪条 0xEA03。
        eaProtocols = ident.eaProtocols
        if (eaProtocols.isNotEmpty()) {
            log("iap2 车机声明 EA 协议(${eaProtocols.size})=" + eaProtocols.joinToString { "${it.protocolId}:${it.name}" })
        }
        if (!profile.openEaSessions) {
            // 2026-10-24 iPhone HCI 对照：真 iPhone **一个 0xEA00 都不发**，车机也从不回 0xEA03。
            // 我们发 8 条、全无应答，只会让车机的状态机多走一遍不确定分支。
            // CarPlay 的媒体面走 AirPlay（Wi-Fi），不依赖 EA 隧道，故默认关闭；
            // 若哪天需要 EA 隧道（例如 iD9 的 ap 信息走 EA），把常量改 true 即恢复。
            if (eaProtocols.isNotEmpty()) log("iap2 跳过 0xea00 EA 会话（档案 ${profile.id} 不要求，对齐真 iPhone 行为）")
        } else {
            for (protocol in eaProtocols) {
                sendFireAndForget(Iap2MessageIds.EA_OPEN_SESSION, Iap2Messages.eaOpenSession(protocol.protocolId, protocol.name))
                log("iap2 tx=0xea00 startEASession protocol=${protocol.protocolId} name=${protocol.name}")
            }
        }
        if (profile.openEaSessions && eaProtocols.isNotEmpty() && ident.declaresSend(Iap2MessageIds.EA_SESSION_STATUS) != false) {
            // 探 800ms 的 0xEA03 并记日志——回没回一眼可见（§9j 处置 1）
            val status = expectOrNull(Iap2MessageIds.EA_SESSION_STATUS, minOf(deadline, nanoTime() + 800_000_000L))
            if (status != null) {
                handleEaSessionStatus(status, phase1 = true)
            } else {
                log("iap2 0xea03 未到（EA 会话未确认）——继续 WiFi 交换")
            }
        }

        // ---- 档案 A（BMW 常见）：车机本次没开无线 CarPlay 窗口 ----
        // 车机单方面决定 A/B（cardeck 2026-10-21 日志：同一台车每轮 A/B 交替）。
        // **默认不结束会话**（档案字段 abortWhenCarPlayWindowClosed，宝马/奥迪都为 false）：
        // 2026-10-25 现场 A/B 证明「档案 A 立即拆链」会让车机把本机降级成普通蓝牙设备、
        // CarPlay 图标消失，之后连 iAP2 都不再发起——图标比「换一轮」值钱得多。
        // 只保留一个开关：确有车机需要「快点换轮」时才在档案里打开。
        val looksLikeBmw =
            identityText.contains("bmw") ||
                ident.eaProtocols.any { it.name.startsWith("de.bmw.", ignoreCase = true) }
        if (profile.abortWhenCarPlayWindowClosed && looksLikeBmw && !isCarPlayProfileB(ident)) {
            throw NoCarPlayWindowException("车机本次为档案 A（无 0x5703/0x4301、EA 无 com.bmw.carplay）")
        }
        if (looksLikeBmw && !isCarPlayProfileB(ident)) {
            log(
                "iap2 档案 A：车机本次没开无线 CarPlay 窗口（无 0x5703/0x4301）——" +
                    "保持链路不拆（拆了车机会收起 CarPlay 图标），保活期等车机重发 0x1D01/0x5703",
            )
        }

        // ---- StartHID（0x6800 → 0x6801）：真 iPhone 会应答，漏了车机可能判定设备未就绪 ----
        awaitOptionalHid(deadline)

        // ---- WiFi 配置交换（按「车机画像 wifiGen」分派，不按清单硬拦）----
        // 旧协议（0x5702 → 0x5703）：手机请求，车机回 AP 明文。
        // 新协议（0x4300 → 0x4301）：AP 信息由 0x4301 自带（BMW iD9 走这条）。
        //
        // **不要用「清单里有没有 0x5702」当硬门禁**（cardeck 0.1.14 奥迪 3315 定谳）：
        // 清单只能证明「有」，不能证明「没有」。奥迪 3315 的可收清单只有 5 项、不含
        // 0x5702，但它完整支持 0x5702→0x5703，而且**要靠收到 0x5702 才把 AirPlay 服务
        // 拉起来**——按清单跳过的那一版：0 次 0x5702 → 端口 7000 全程 ECONNREFUSED。
        // 明确声明 0x4301 且未声明 0x57xx 才优先新路径，不按清单数量推断完整性。
        val wifiGen = ReceiverProfile.classifyWifiGen(ident.sendIds, ident.receiveIds)
        enterPhase("WIFI_CREDENTIALS")
        log("iap2 车机画像 wifiGen=${ReceiverProfile.describe(wifiGen)}")
        val declaresReceiveWifi = ident.declaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
        val declaresSendWifi = ident.declaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)
        val legacyWifiExchange = wifiGen != ReceiverProfile.WifiGen.NEW
        if (legacyWifiExchange && (declaresReceiveWifi == false || declaresSendWifi == false)) {
            log(
                "iap2 清单未声明 0x5702/0x5703（收=${declaresReceiveWifi ?: "未知"} " +
                    "发=${declaresSendWifi ?: "未知"}）但画像判 ${wifiGen}——仍发 0x5702" +
                    "（清单不完整不能反证「不支持」；车机对未声明消息通常照常处理）",
            )
        }
        val wifiFromExchange: Iap2Messages.WifiConfiguration? =
            if (!legacyWifiExchange) {
                null
            } else {
                send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                // 只占用本阶段预算，为 0x4300/0x4301 留出时间；4301 早到也立即结束等待。
                val wifiDeadline = rescueDeadline(deadline, WIFI_EXCHANGE_BUDGET_MILLIS)
                val credentialMessages = setOf(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, Iap2MessageIds.CARPLAY_START_SESSION)
                var parsed = expectAnyOrNull(credentialMessages, rescueDeadline(wifiDeadline, WIFI_RESCUE_GREETING_MILLIS))
                if (parsed == null) {
                    sendDeviceStateGreetings(force = true)
                    log("iap2 0x5703 1.5s 未到——强制重发设备状态问候，继续等待")
                    parsed = expectAnyOrNull(credentialMessages, rescueDeadline(wifiDeadline, WIFI_RESCUE_RETRY_MILLIS))
                }
                if (parsed == null) {
                    send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                    log("iap2 0x5702 重发一次（首次仅链路 ACK）")
                    parsed = expectAnyOrNull(credentialMessages, wifiDeadline)
                }
                if (parsed == null) {
                    log("iap2 0x5703 未在窗口内到达——改走 0x4300/0x4301（新版协议自带 AP 信息）")
                    null
                } else if (parsed.messageId == Iap2MessageIds.CARPLAY_START_SESSION) {
                    pendingFrames.addFirst(parsed)
                    log("iap2 0x4301 先到——立即转入端点处理，不再等待 0x5703")
                    null
                } else {
                    val wifiConfiguration = Iap2Messages.parseWifiConfiguration(parsed.body)
                    log(
                        "iap2 rx=0x5703 ssid=${wifiConfiguration.ssid} channel=${wifiConfiguration.channel} " +
                            "security=${wifiConfiguration.securityType} " +
                            "psk=${if (wifiConfiguration.passphrase.isEmpty()) "(空)" else "***"}",
                    )
                    this.wifi = wifiConfiguration
                    // 凭据即刻回调：上层据此绑车机网络 + mDNS，0x4301 到达前就能入网
                    onEndpoint(endpointOf(null, wifiConfiguration))
                    wifiConfiguration
                }
            }

        // ---- CarPlay 会话启动 ----
        enterPhase("CARPLAY_START_SESSION")
        // 0x4300 发后不管：Audi 实测车机曾对它停止 ACK（其余包毫秒级 ACK），
        // 计入重传清单会让看门狗在 +20s 杀掉整条蓝牙链路。
        //
        // **改由车机档案决定**（2026-10-25 现场 A/B，本文件最重要的一条回归结论）：
        //   - 0.27（发 0x4300）：车机显示 CarPlay 图标，手机被识别成 CarPlay 设备；
        //   - 0.29 起（按「真 iPhone 不发 0x4300」的 HCI 对照改成不发）：**图标消失**，
        //     车机把手机降级成「只有电话/语音的普通蓝牙设备」，之后连 iAP2 都不再发起。
        // 所以宝马车机档案恢复 0.27 行为（sendCarPlayAvailability=true），并且
        // **不再看可收清单**——清单只能证明「有」，不能证明「没有」（cardeck 0.1.14 定谳）。
        // 0x4300 里带的是本机蓝牙传输标识：车机靠它把 CarPlay 设备与已配对手机对上，
        // 这也是必须用真实 MAC 的原因（伪 MAC 会对不上任何已配对设备）。
        if (profile.sendCarPlayAvailability) {
            if (ident.declaresReceive(Iap2MessageIds.CARPLAY_AVAILABILITY) == false) {
                log("iap2 车机清单未声明可收 0x4300，但档案 ${profile.id} 要求发——照发（清单可能不完整）")
            }
            sendFireAndForget(Iap2MessageIds.CARPLAY_AVAILABILITY, Iap2Messages.carPlayAvailability(bluetoothMac))
            log("iap2 tx=0x4300 CarPlayAvailability（档案 ${profile.id}；mac=$bluetoothMac）")
        } else {
            log("iap2 跳过 0x4300（CarPlayAvailability）——档案 ${profile.id} 不要求")
        }
        val declaresStartSession = ident.declaresSend(Iap2MessageIds.CARPLAY_START_SESSION)
        // 没拿到 0x5703（新协议车机）时 0x4301 是**唯一** AP 来源——窗口给足；
        // 已有 0x5703 的旧协议车机保持 3 秒短窗口（应答都在毫秒级）。
        val startSessionWindow =
            if (wifiFromExchange == null) START_SESSION_WAIT_NEW_PROTOCOL_NANOS else START_SESSION_WAIT_NANOS
        val startSession =
            if (declaresStartSession == false && pendingFrames.none { it.messageId == Iap2MessageIds.CARPLAY_START_SESSION }) {
                log("iap2 车机未声明会发 0x4301——跳过等待（上层可用 mDNS/网关兜底）")
                null
            } else {
                expectOrNull(Iap2MessageIds.CARPLAY_START_SESSION, minOf(deadline, nanoTime() + startSessionWindow))
            }

        val endpoint =
            if (startSession != null) {
                emitStartSession(startSession, phase1 = true)
            } else {
                // 不能断言「该车机不发」：Audi MMI 9310 声明会发、也确实发，只是晚到
                // 2 分 35 秒（cardeck 2026-10-18 日志）。措辞保留「稍后可能补发」的预期。
                log(
                    if (declaresStartSession == true) {
                        "iap2 0x4301 在 ${startSessionWindow / 1_000_000_000}s 窗口内未到" +
                            "（车机声明会发——可能稍后补发，保活期间收到会重新回调端点）"
                    } else {
                        "iap2 0x4301 未到（车机未声明会发）"
                    },
                )
                val wifiOnly = this.wifi ?: wifiFromExchange
                if (wifiOnly == null) {
                    // 既没有 0x5703、也没有 0x4301：**不要拆链**。
                    //
                    // 真车现场（BMW MGU-55，2026-10-24）：车机 0x1D01 清单里根本没有
                    // 0x5702/0x5703/0x4300/0x4301（只声明 iPod/BMW Apps 那一套：
                    // 0x4C00..0x4C07 / 0x5000 / 0x6800 / 0xAE00 / 0xEA03）。旧实现在这里
                    // throw，runSession 随即关 RFCOMM → 车机见到 iAP2 链路消失就断 ACL →
                    // 系统广播 ACL 断开 → HyperCarRepo.disconnect() 拆掉整个会话 →
                    // 3s 后重新连、重新识别、再拆，周而复始（日志里 10:55~11:01 的
                    // 连上/断开刷屏就是这个环）。
                    //
                    // 真 iPhone 在这种车机上的做法是**把链路留着等**：用户在 iDrive 里
                    // 点一次「Apple CarPlay/手机」后，车机才打开无线 CarPlay 窗口并下发
                    // 热点凭据（0x5703 或 0x4301）。所以这里只记日志、返回空端点，
                    // 由 [pumpIncomingForever] 继续守着这条链路等凭据。
                    log(
                        "iap2 ⚠ 车机既未声明 0x5702/0x5703，也未声明 0x4301" +
                            "（iAP2 清单里没有无线 CarPlay 组件）——保持链路等待车机开放 CarPlay 窗口",
                    )
                    log("iap2 提示：请在车机上进入「移动设备 → 选择本机 → Apple CarPlay」点一次；链路会一直保持")
                }
                endpointOf(null, wifiOnly)
            }
        // 真机顺序：0x4301（或只有 0x5703 时也算 Phase 1 末）之后回 0x4E0E，把本机蓝牙
        // 传输标识告诉车机（车机按它把 iAP2 设备与 RFCOMM 对端关联起来）。
        // Audi 实测车机「接受但从不 ACK」→ 必须发后不管，否则重传看门狗在 +20s 杀链路。
        //
        // **不能按清单声明拦**（cardeck 2026-10-22 定谳：清单只能证明「有」）：
        // 同日日志里 0x4E0D/0x4E0B/0x4E0E 三条不在清单里，车机逐条 ACK 到位。
        //
        // 但 2026-10-24 用真 iPhone 的 HCI 日志对照：真 iPhone **不发 0x4E0E**
        // （只发 4E09/4E0A/4E0B/4E0C），MGU-55 也只是 ACK 掉。
        // 为减少与真机行为的差异，默认不发；需要时把 SEND_TRANSPORT_IDENTIFIER 改 true。
        if (profile.sendTransportIdentifier) {
            sendFireAndForget(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER, Iap2Csm.string(0, bluetoothMac))
            log("iap2 tx=0x4e0e 设备传输标识（mac=$bluetoothMac）")
        } else {
            log("iap2 跳过 0x4e0e 传输标识（档案 ${profile.id} 不要求，对齐真 iPhone 行为）")
        }
        log(
            if (endpoint.ssid.isNullOrEmpty() && endpoint.ipAddresses.isEmpty()) {
                "iap2 Phase 1 完成：尚无热点凭据/AirPlay 端点——进入保活等待车机下发"
            } else {
                "iap2 Phase 1 完成：拿到热点凭据与（可能的）AirPlay 端点"
            },
        )
        return endpoint
    }

    /**
     * 车机「无线 CarPlay 档案」判定（BMW iD8.5 的 A/B 两套清单，见 docs/NEGOTIATION-GAPS §8.5
     * 与 cardeck PROTOCOL-NOTES §9j）：
     *  - **B**：发送清单含 0x5703（或 0x4301）、EA 里有 `com.bmw.carplay` = 车机已把
     *    「无线 CarPlay」这一档能力打开；
     *  - **A**：以上都没有，只有 iPod/BMW Apps 那一套（0x4Cxx/0x5000/0x6800/0xAE00/EA
     *    `de.bmw.a4a.*`）= 车机没开 CarPlay 窗口。
     *
     * **A/B 是车机单方面给的，手机发什么都改不了它**（cardeck 2026-10-21 日志：同一台
     * BMW 31726 在 20:22:39=A、20:23:07=B、20:23:20=A、20:23:43=B 来回切）。
     * 所以档案 A 下唯一有意义的事是**尽快结束本会话**，让车机下一次连入（很可能就是 B）。
     * 旧实现直接拆链看似一样，但它会连带把上层会话拆掉（ACL 断开 → disconnect()），
     * 而且 3 分钟的端点等待期间车机再连入会被 sessionActive 挡掉。
     */
    private fun isCarPlayProfileB(ident: Iap2Messages.Identification): Boolean {
        val declaresWifi = ident.declaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true
        val declaresStart = ident.declaresSend(Iap2MessageIds.CARPLAY_START_SESSION) == true
        val bmwCarplay = ident.eaProtocols.any { it.name.contains("com.bmw.carplay", ignoreCase = true) }
        return declaresWifi || declaresStart || bmwCarplay || ident.hasWirelessCarPlayComponent
    }

    private fun logWirelessCarPlayProfile(ident: Iap2Messages.Identification) {
        val declaresWifi = ident.declaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true
        val declaresStart = ident.declaresSend(Iap2MessageIds.CARPLAY_START_SESSION) == true
        val eaNames = ident.eaProtocols.map { it.name }
        val bmwCarplay = eaNames.any { it.contains("com.bmw.carplay", ignoreCase = true) }
        if (isCarPlayProfileB(ident)) {
            log(
                "iap2 无线 CarPlay 档案=B（车机已开放 CarPlay 窗口：0x5703=$declaresWifi " +
                    "0x4301=$declaresStart com.bmw.carplay=$bmwCarplay " +
                    "WirelessCarPlay组件=${ident.hasWirelessCarPlayComponent}）",
            )
        } else {
            log(
                "iap2 无线 CarPlay 档案=A（车机没开 CarPlay 窗口：无 0x5703/0x4301、无 WirelessCarPlay 组件，" +
                    "EA=${eaNames.joinToString("/").ifEmpty { "-" }}）——尽快结束本会话让车机重开",
            )
        }
    }

    /**
     * Phase 2 过渡：bring-up 完成后保持链路打开，持续处理配件发来的控制消息，
     * 直到链路断开。真实无线 CarPlay 中这条控制通道与 AirPlay 会话并存于整个连接生命周期。
     *
     * 心跳（裸 ACK 保活）由链路层 [Iap2LinkEngine] 在 NORMAL 态按对端 ackTimeout 自动发。
     *
     * 除收发之外这里还负责三件「等车机」的事（BMW MGU-55 实车定谳，2026-10-24）：
     *  - **不拆链**：没拿到凭据就一直守在这条链路上——链路一关，车机就断 ACL，
     *    上层再把整个会话拆掉，形成 3s 一轮的连-断循环；
     *  - **重探**：车机迟迟不下发凭据时，按 [POKE_INTERVAL_NANOS] 重发 0x5702/0x4300
     *    （仅当车机声明可收），EA 会话没确认时补发 0xEA00；
     *  - **未知消息体 dump**：BMW 只声明 0x4C00..0x4C07 这一套私有消息、不下发
     *    0x5703/0x4301 时，无线凭据只可能藏在这些报文里——每个消息 id 的原始体
     *    必须落进日志，下一轮现场才有得解。
     */
    fun pumpIncomingForever() {
        enterPhase("MAINTAIN_LINK")
        log("SESSION 保活中（链路保持打开，持续处理配件消息）")
        // 同一消息 ID 连续到达时限流：车机进入媒体播放后会周期性发 NowPlaying 更新，
        // 逐条打日志会把文件日志撑爆（文件超限被删 = 丢掉真正有用的上下文）。
        var lastLoggedId = -1
        var repeatCount = 0
        while (true) {
            // 空闲轮询间隔要短于重探周期：否则「保活期一个包都不来」时永远轮不到重探
            val frame = recvFrame(IDLE_POLL_MILLIS)
            if (frame == null) {
                if (!channel.isAlive) break
                pokeWhileWaiting()
                continue
            }
            // 生命周期消息必须先于日志限流处理：0x4301 可能作为「车机刚打开 CarPlay
            // 窗口」的晚信号在 Phase 1 之后才到（Audi MMI 9310 晚 2 分 35 秒）。若跟着
            // 限流逻辑走，它要么被当成 keep-alive 只记长度，要么因与上一条同 ID 被
            // continue 掉——两种都等于丢弃（车机等 15 秒无人连入 AirPlay 就拆 RFCOMM）。
            when (frame.messageId) {
                Iap2MessageIds.CARPLAY_START_SESSION -> handleLateStartSession(frame)
                Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION -> handleLateWifiConfiguration(frame)
                Iap2MessageIds.EA_SESSION_STATUS -> handleEaSessionStatus(frame, phase1 = false)
                Iap2MessageIds.IDENTIFICATION_INFORMATION -> handleReidentification(frame)
                // 车机要「电源更新」：真 iPhone 会回 3 条 0xAE01（HCI 实测），我们此前从不回。
                // 只回一次就够（车机周期发 0xAE00，我们也跟着周期回），避免刷屏。
                Iap2MessageIds.START_POWER_UPDATES -> {
                    if (!powerUpdateReplied) {
                        powerUpdateReplied = true
                        log("iap2 rx=0xae00 StartPowerUpdates → 回 3 条 0xae01 PowerUpdate（对齐真 iPhone）")
                    }
                    for (body in Iap2Messages.powerUpdates()) {
                        runCatching { sendFireAndForget(Iap2MessageIds.POWER_UPDATE, body) }
                    }
                }
                else -> Unit
            }
            // 未知消息的报文结构只能靠原始体定谳；每个 id 只 dump 一次
            dumpBodyOnce(frame)
            if (frame.messageId == lastLoggedId) {
                repeatCount++
                if (repeatCount % 100 != 0) continue
            } else {
                if (repeatCount >= 99) {
                    log("iap2 上一条 0x${lastLoggedId.toString(16)} 重复 ${repeatCount + 1} 次（同类已限流）")
                }
                lastLoggedId = frame.messageId
                repeatCount = 0
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) keep-alive bytes=${frame.body.size}",
            )
        }
        log("SESSION 保活结束（链路断开）")
    }

    /** 每个消息 id 的原始体只记一次（车机私有消息的结构/长度只能靠它定谳）。 */
    private fun dumpBodyOnce(frame: Iap2Csm.Frame) {
        if (!bodyDumped.add(frame.messageId)) return
        if (frame.body.isEmpty()) return
        val shown = if (frame.body.size <= BODY_DUMP_BYTES) frame.body else frame.body.copyOf(BODY_DUMP_BYTES)
        val truncated = if (frame.body.size > shown.size) "（前 ${shown.size}/${frame.body.size} 字节）" else ""
        val hint =
            when (frame.messageId) {
                in 0x4C00..0x4CFF ->
                    "——BMW 私有 0x4Cxx：清单里唯一没被本实现消费的就这一组，" +
                        "无线 CarPlay 凭据若不走 0x5703/0x4301 就只可能在这里"
                else -> ""
            }
        log(
            "iap2 0x${frame.messageId.toString(16).padStart(4, '0')} 原始体$truncated=" +
                shown.joinToString("") { "%02x".format(it) } + hint,
        )
    }

    /**
     * 等不到热点凭据时的重探（保活期空闲时调用）：
     *  - 0x5702（旧协议：让车机回 0x5703）——仅当车机声明可收；
     *  - 0x4300（新协议：让车机回 0x4301）——仅当车机声明可收；
     *  - 0xEA00（EA 会话未确认时补开）——最多 [EA_REOPEN_ATTEMPTS] 轮。
     *
     * 为什么可以重发：真 iPhone 在车机重新打开 CarPlay 窗口后，车机会主动重发
     * 0x5703/0x4301；但我们的「设备」对车机来说是新的，窗口开关的时机不可控，
     * 只被动等会整场空转。重发是廉价、可解释的探询（声明可收才发，不硬塞）。
     */
    private fun pokeWhileWaiting() {
        if (hasAccessoryEndpoint()) return
        val now = nanoTime()
        // 前 12 次每 5s（覆盖上车点屏幕的一分钟），之后退到 30s（长等不刷屏）
        val interval = if (pokeCount < 12) POKE_INTERVAL_NANOS else POKE_INTERVAL_SLOW_NANOS
        if (lastPokeNanos != 0L && now - lastPokeNanos < interval) return
        lastPokeNanos = now
        pokeCount++
        val peer = peerIdentification
        // 链路正在断开时 sendControl 会返回 false / 抛 IOException——重探不是关键路径，
        // 吞掉异常交给下一轮 recvFrame 判定链路已死（否则日志会出现莫须有的「会话异常」）
        runCatching {
            if (peer?.declaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true) {
                sendFireAndForget(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION, ByteArray(0))
            }
            if (peer?.declaresReceive(Iap2MessageIds.CARPLAY_AVAILABILITY) == true) {
                sendFireAndForget(Iap2MessageIds.CARPLAY_AVAILABILITY, Iap2Messages.carPlayAvailability(bluetoothMac))
            }
            if (!eaSessionConfirmed && pokeCount <= EA_REOPEN_ATTEMPTS) {
                for (protocol in eaProtocols) {
                    sendFireAndForget(Iap2MessageIds.EA_OPEN_SESSION, Iap2Messages.eaOpenSession(protocol.protocolId, protocol.name))
                }
                log("iap2 重发 0xea00（EA 会话未确认，第 $pokeCount 次，共 $EA_REOPEN_ATTEMPTS 次）")
            }
        }
        if (pokeCount % 6 == 1) {
            log("iap2 仍在等车机下发无线 CarPlay 凭据（第 $pokeCount 次重探）——请在车机上点一次手机/Apple CarPlay")
        }
    }

    /** 是否已经拿到能用的车机端点（有热点 SSID 或 AirPlay 地址）。 */
    private fun hasAccessoryEndpoint(): Boolean =
        !wifi?.ssid.isNullOrEmpty() || lastStartSession?.ipAddresses?.isNotEmpty() == true

    /** 车机 0xEA03：EA 会话状态。文档未公开字段，只记原始体 + 尽力解析协议号/状态。 */
    private fun handleEaSessionStatus(
        frame: Iap2Csm.Frame,
        phase1: Boolean,
    ) {
        eaSessionConfirmed = true
        val subs = Iap2Csm.parseParams(frame.body)
        val protocolId = subs.firstOrNull { it.id == 0 }?.asU8()
        val status = subs.firstOrNull { it.id == 1 }?.asU8()
        val parsed =
            if (protocolId != null || status != null) {
                " protocol=${protocolId ?: "?"} status=${status ?: "?"}"
            } else {
                ""
            }
        log(
            "iap2 rx=0xea03 EA 会话${if (phase1) "" else "（保活期）"}已确认$parsed" +
                " bytes=${frame.body.size}=" + frame.body.take(BODY_DUMP_BYTES).joinToString("") { "%02x".format(it) },
        )
    }

    /**
     * 车机重新下发 0x1D01（重新识别）：能力清单可能已经变了——
     * 典型是用户刚在 iDrive 里点了「Apple CarPlay」，车机这才把 0x4300/0x4301 加进清单。
     * 这里更新清单并立刻重探一次，不重跑整轮 MFi（iAP2 允许重新识别，但重跑 MFi
     * 会打断正在等待的车机窗口，得不偿失）。
     */
    private fun handleReidentification(frame: Iap2Csm.Frame) {
        val ident = Iap2Messages.parseIdentification(frame.body)
        peerIdentification = ident
        log(
            "iap2 rx=0x1d01（重新识别）name=${ident.name} 发送清单=${ident.sendIds?.sorted()?.joinToString { "0x%04x".format(it) }} " +
                "可收清单=${ident.receiveIds?.sorted()?.joinToString { "0x%04x".format(it) }}",
        )
        ident.eaProtocols.takeIf { it.isNotEmpty() }?.let { eaProtocols = it }
        val declaresWifi = ident.declaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) != false
        val declaresStart = ident.declaresSend(Iap2MessageIds.CARPLAY_START_SESSION) != false
        if (declaresWifi || declaresStart) {
            log("iap2 车机重新识别后声明了无线 CarPlay——立刻重探 0x5702/0x4300")
            lastPokeNanos = 0L
            pokeWhileWaiting()
        }
    }

    /**
     * StartHID（0x6800）：车机让设备开始上报 HID 时，真 iPhone 会回一条 0x6801
     * `[u16 0][u8 0]`（cardeck 真车实测的应答形状）。hypercar 旧实现只把 0x6800 当日志、
     * 从不回答——BMW 的发送清单里有 0x6800、可收清单里有 0x6801，缺应答会被车机当成
     * 「HID 通道没建立」（触摸本身走 AirPlay 事件通道，但车机侧的就绪判定会受影响）。
     *
     * 窗口 [HID_WAIT_NANOS]：声明里没有 0x6800 就直接跳过（DiPlay/Audi 不发，旧流程白等）。
     * 取帧用 [recvNewFrame] 而不是 [recvFrame]：后者优先消费暂存区，把非目标帧塞回去会
     * 同一帧反复取出（活锁，见 [pendingFrames] 注释）；非目标帧统一进暂存区留待后续消费。
     */
    private fun awaitOptionalHid(deadline: Long) {
        if (peerIdentification?.declaresSend(Iap2MessageIds.START_HID) == false) {
            log("iap2 车机未声明会发 0x6800 StartHID——跳过等待")
            return
        }
        val window = minOf(deadline, nanoTime() + HID_WAIT_NANOS)
        while (true) {
            val remaining = (window - nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                log("iap2 未收到 0x6800（可选，继续）")
                return
            }
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return
                continue
            }
            if (frame.messageId == Iap2MessageIds.START_HID) {
                log("iap2 rx=0x6800 start-hid bytes=${frame.body.size}——应答 0x6801")
                send(
                    Iap2MessageIds.DEVICE_HID_REPORT,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.param(1, byteArrayOf(0)),
                )
                return
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，暂存待后续消费",
            )
            dumpBodyOnce(frame)
            // 0xAE00 在 Phase 1 的等待窗口里也会到（车机一问就要答）；
            // 若只靠主循环的 when 分支，第一条就被暂存区吞掉了——这里直接回。
            if (frame.messageId == Iap2MessageIds.START_POWER_UPDATES) {
                replyPowerUpdates()
            }
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) pendingFrames.removeFirst()
        }
    }

    /**
     * 回 0xAE01 PowerUpdate（3 条，与真 iPhone 一致）。
     *
     * 车机在 MFi 通过后立刻发 0xAE00 要电源状态；真 iPhone 回 3 条、我们此前一条不回。
     * 报文来自真机抓包（见 `Iap2Messages.powerUpdates`），只重放不自造数值。
     */
    private fun replyPowerUpdates() {
        if (!powerUpdateReplied) {
            powerUpdateReplied = true
            log("iap2 rx=0xae00 StartPowerUpdates → 回 3 条 0xae01 PowerUpdate（对齐真 iPhone）")
        }
        for (body in Iap2Messages.powerUpdates()) {
            runCatching { sendFireAndForget(Iap2MessageIds.POWER_UPDATE, body) }
        }
    }

    /** 保活期收到 0x4301：解析成端点回调（重复到达也回调，上层自行忽略冗余）。 */
    private fun handleLateStartSession(frame: Iap2Csm.Frame) {        val endpoint = emitStartSession(frame, phase1 = false)
        if (endpoint.ssid.isNullOrEmpty()) {
            log("iap2 rx=0x4301（晚到）——无线组与缓存里都没有 SSID，仍回调端点交上层处理")
        }
    }

    /** 保活期车机重发 0x5703：更新缓存并回调（入网状态可能已变化）。 */
    private fun handleLateWifiConfiguration(frame: Iap2Csm.Frame) {
        val parsed = Iap2Messages.parseWifiConfiguration(frame.body)
        log(
            "iap2 rx=0x5703（保活期重发）ssid=${parsed.ssid} channel=${parsed.channel} " +
                "psk=${if (parsed.passphrase.isEmpty()) "(空)" else "***"}",
        )
        wifi = parsed
        onEndpoint(endpointOf(lastStartSession, parsed))
    }

    /** 解析 0x4301 → [CarPlayEndpoint] → 回调；返回解析结果。 */
    private var lastStartSession: Iap2Messages.StartSession? = null

    private fun emitStartSession(
        frame: Iap2Csm.Frame,
        phase1: Boolean,
    ): CarPlayEndpoint {
        logStartSessionRaw(frame)
        val parsed = Iap2Messages.parseStartSession(frame.body)
        lastStartSession = parsed
        // 新协议车机：0x4301 无线组是唯一的 AP 来源——解析出来就缓存，供端点合成
        if (wifi == null && parsed.wirelessAp != null) {
            wifi = parsed.wirelessAp
            log("iap2 0x4301 自带 AP：ssid=${parsed.wirelessAp.ssid}——已缓存")
        }
        val endpoint = endpointOf(parsed, wifi)
        if (endpointEmitted && endpoint.hashCode() == lastLateStartSessionSignature) {
            // 车机周期性重发同一份 0x4301：详情只在端点变化时打，避免刷日志；
            // 但回调照发（契约：0x4301 是生命周期事件，重复到达都回调）。
            onEndpoint(endpoint)
            return endpoint
        }
        endpointEmitted = true
        lastLateStartSessionSignature = endpoint.hashCode()
        log(
            "iap2 rx=0x4301${if (phase1) "" else "（晚到）"} airPlayPort=${endpoint.airPlayPort} " +
                "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKeyHex?.take(16)}… " +
                "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}",
        )
        onEndpoint(endpoint)
        return endpoint
    }

    /** 0x4301 原始体只记一次：车机间端点字段差异（有无 IPv4/端口/公钥）只能靠它定谳。 */
    private fun logStartSessionRaw(frame: Iap2Csm.Frame) {
        if (startSessionRawLogged) return
        startSessionRawLogged = true
        val body = frame.body
        val shown = if (body.size <= START_SESSION_RAW_LOG_BYTES) body else body.copyOf(START_SESSION_RAW_LOG_BYTES)
        val truncated = if (body.size > shown.size) "（前 ${shown.size}/${body.size} 字节）" else ""
        log("iap2 0x4301 原始体$truncated=" + shown.joinToString("") { "%02x".format(it) })
    }

    /** 由 0x4301（可为 null）+ 0x5703 缓存合成对外端点。 */
    private fun endpointOf(
        startSession: Iap2Messages.StartSession?,
        wifi: Iap2Messages.WifiConfiguration?,
    ): CarPlayEndpoint =
        CarPlayEndpoint(
            ssid = wifi?.ssid ?: startSession?.wirelessAp?.ssid,
            passphrase = wifi?.passphrase ?: startSession?.wirelessAp?.passphrase,
            airPlayPort = startSession?.airPlayPort ?: 0,
            deviceIdentifier = startSession?.deviceIdentifier,
            publicKeyHex = startSession?.publicKeyHex,
            sourceVersion = startSession?.sourceVersion,
            ipAddresses = startSession?.ipAddresses ?: emptyList(),
            // 网关不在 iAP2 消息里；上层入网后从 DHCP/链路信息取
            gateway = null,
        )

    // ---- MFi 挑战 ----

    private enum class MfiResult { RESPONSE_RECEIVED_UNVERIFIED, TIMEOUT_COMPATIBILITY }

    /** 保留已跑通接收端的超时兼容策略；断链/取消绝不能作为正常认证结果返回。 */
    private fun runMfiChallenge(
        deadline: Long,
        receiverKey: String,
    ): MfiResult {
        val baseline = MFI_ATTEMPTS.map { it.first }
        val windowOf = MFI_ATTEMPTS.toMap()
        val memory = MfiLengthMemory.load(context, receiverKey)
        val order = MfiLengthMemory.orderAttempts(baseline, memory)
        if (!memory.isEmpty) {
            log(
                "iap2 MFi 按车机记忆（$receiverKey）：${MfiLengthMemory.describe(memory)}" +
                    " → 本轮顺序 ${order.joinToString("/")}B",
            )
        }
        for ((position, size) in order.withIndex()) {
            ensureActive("MFi 挑战发送前")
            // 已知「静默过」的档位缩短窗口：它已经失败过一次，没必要再等满
            val baseWindow = windowOf[size] ?: MFI_ATTEMPTS.last().second
            val windowMillis =
                if (size in memory.silent && size != memory.succeeded) {
                    minOf(baseWindow, MFI_SILENT_RETRY_WINDOW_MS)
                } else {
                    baseWindow
                }
            val challenge = ByteArray(size).also(random::nextBytes)
            val started = nanoTime()
            try {
                send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            } catch (error: IOException) {
                if (error !is InterruptedIOException && !channel.isCancelled && !channel.isAlive) {
                    MfiLengthMemory.recordDisconnected(context, receiverKey, size)
                }
                throw error
            }
            log("iap2 tx=0xaa02 challenge=${size}B（第 ${position + 1} 次，窗口 ${windowMillis}ms）")
            val window = minOf(nanoTime() + windowMillis * 1_000_000L, deadline)
            var gotResponse = false
            var gotFailure = false
            while (true) {
                val remaining = (window - nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val frame = recvFrame(remaining)
                if (frame == null) {
                    if (Thread.currentThread().isInterrupted || channel.isCancelled) throw InterruptedIOException("MFi 挑战已取消")
                    if (!channel.isAlive) {
                        MfiLengthMemory.recordDisconnected(context, receiverKey, size)
                        val elapsed = (nanoTime() - started) / 1_000_000L
                        throw IOException("MFi 等待 0xaa03 时链路关闭：挑战=${size}B，经过=${elapsed}ms，原因=${channel.closeReason ?: "未知"}；下次连接轮换候选，未发送 0xaa05")
                    }
                    continue
                }
                when (frame.messageId) {
                    Iap2MessageIds.MFI_RESPONSE -> {
                        log("iap2 rx=0xaa03 signature bytes=${frame.body.size}（跳过校验）")
                        gotResponse = true
                    }

                    Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                        log("iap2 rx=0xaa04 AuthenticationFailed（非预期方向，不推断为长度被拒）")
                        gotFailure = true
                    }

                    else -> log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
                }
                if (gotResponse || gotFailure) break
            }
            if (gotResponse) {
                ensureActive("MFi 响应接收后")
                if (position == 0) {
                    MfiLengthMemory.recordSuccess(context, receiverKey, size)
                } else {
                    log("iap2 AA03 可能属于先前超时挑战——不把 ${size}B 写成成功长度")
                }
                return MfiResult.RESPONSE_RECEIVED_UNVERIFIED
            }
            if (gotFailure) {
                throw IOException("MFi 收到非预期 0xaa04，停止本轮（不能据此判定挑战长度）")
            } else {
                MfiLengthMemory.recordSilent(context, receiverKey, size)
            }
            if (position < order.size - 1) {
                log("iap2 MFi ${size}B 无应答，改用 ${order[position + 1]}B 重试")
            } else {
                log("iap2 MFi 挑战全部无应答——跳过校验直接发 0xaa05（由后续消息判定车机是否放行）")
            }
        }
        ensureActive("MFi 超时兼容处理前")
        return MfiResult.TIMEOUT_COMPATIBILITY
    }

    // ---- 设备状态问候 ----

    /**
     * 设备状态问候：0x4E0D 无线可用性 / 0x4E0C 无线会话标识（UUID 大写）/ 0x4E0A 语言 /
     * 0x4E0B 设备时间 / 0x4E09 设备名。一轮一次（[force]=true 才重发，§9j 处置 3）。
     *
     * **0x4E0D（WirelessCarPlayUpdate，u8=1）不能省**（2026-10-24 对照 cardeck 定谳）：
     * 真 iPhone 在 0xAA05 之后会发这条「本机支持无线 CarPlay」的可用性通知；
     * Apple《CarPlay Accessory Interface Specification》§23.2.5.1 明确「配件可用
     * WirelessCarPlayUpdate 得知设备是否具备无线 CarPlay」。hypercar 旧实现漏了这条，
     * 而 BMW MGU-55 的 iAP2 可收清单里恰恰有 0x4E0D——车机可能就是靠它判断
     * 「这台设备是不是 CarPlay 设备」，没有就只给 iPod/BMW Apps 档案（不放 0x5703）。
     */
    private fun sendDeviceStateGreetings(force: Boolean = false) {
        if (greetingsSent && !force) return
        greetingsSent = true
        val timezoneMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val daylightSaving = if (TimeZone.getDefault().inDaylightTime(Date())) 1 else 0
        send(
            Iap2MessageIds.WIRELESS_CARPLAY_UPDATE,
            Iap2Csm.u8(0, 1),
        )
        send(
            Iap2MessageIds.DEVICE_UUID_UPDATE,
            Iap2Csm.string(0, deviceUuid()),
        )
        send(
            Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
            Iap2Csm.string(0, "zh"),
        )
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Messages.deviceTime(System.currentTimeMillis() / 1_000L, timezoneMinutes, daylightSaving),
        )
        send(
            Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
            Iap2Csm.string(0, deviceName),
        )
        log("iap2 tx=0x4e0d/0x4e0c/0x4e0a/0x4e0b/0x4e09 设备状态问候（name=$deviceName mac=$bluetoothMac）")
    }

    /** 设备 UUID：由蓝牙 MAC 确定性派生，跨会话稳定（车机按它标识设备；大写输出）。 */
    private fun deviceUuid(): String =
        UUID
            .nameUUIDFromBytes(bluetoothMac.toByteArray())
            .toString()
            .uppercase()

    // ---- 帧收发 ----

    /** 分段等待用的截止时刻：取「整体 deadline」与「再等 millis 毫秒」的较小值。 */
    private fun rescueDeadline(
        deadlineNanos: Long,
        millis: Long,
    ): Long = minOf(deadlineNanos, nanoTime() + millis * 1_000_000L)

    private fun send(
        messageId: Int,
        vararg body: ByteArray,
    ) {
        val joined =
            when (body.size) {
                0 -> ByteArray(0)
                1 -> body[0]
                else -> body.reduce { acc, bytes -> acc + bytes }
            }
        sendOrThrow(Iap2Csm.encode(messageId, joined), messageId, awaitAck = true)
    }

    /** [send] 的发后不管变体：链路不因对端不 ACK 此消息而重传/死亡（0x4300/0xEA00/0x4E0E 用）。 */
    private fun sendFireAndForget(
        messageId: Int,
        body: ByteArray,
    ) {
        sendOrThrow(Iap2Csm.encode(messageId, body), messageId, awaitAck = false)
    }

    /** 按对端协商的 maxLength 分片发送；链路关闭时抛 IOException（会话层据此收尾）。 */
    private fun sendOrThrow(
        frame: ByteArray,
        messageId: Int,
        awaitAck: Boolean,
    ) {
        ensureActive("发送 0x${messageId.toString(16)} 前")
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        log("TX phase=$phaseName id=0x${messageId.toString(16)} bytes=${frame.size} maxPayload=$limit awaitAck=$awaitAck")
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end), awaitAck)) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log(
            "iap2 tx=0x${messageId.toString(16).padStart(4, '0')}" +
                if (awaitAck) "" else "（发后不管）",
        )
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("等待 0x${messageId.toString(16)} 超时")

    /**
     * 同 [expect]，但超时返回 null（用于可选消息）。非预期帧进暂存区供后续消费。
     *
     * 只从 [receivedFrames] 读新帧，**不重读自己刚暂存的帧**：旧实现两者共用一个
     * 队列，非预期帧被塞回后立刻又被本函数取出，形成活锁（见 pendingFrames 注释）。
     * 目标帧若已在暂存区（早到），先从这里取，保证不漏。
     */
    private fun expectOrNull(
        messageId: Int,
        deadlineNanos: Long,
    ): Iap2Csm.Frame? = expectAnyOrNull(setOf(messageId), deadlineNanos)

    private fun expectAnyOrNull(
        messageIds: Set<Int>,
        deadlineNanos: Long,
    ): Iap2Csm.Frame? {
        ensureActive("等待控制消息")
        val waitStarted = nanoTime()
        val expected = messageIds.joinToString("/") { "0x${it.toString(16)}" }
        log("WAIT phase=$phaseName ids=$expected budget=${((deadlineNanos - waitStarted) / 1_000_000L).coerceAtLeast(0)}ms pending=${pendingFrames.size}")
        pendingFrames.firstOrNull { it.messageId in messageIds }?.let {
            pendingFrames.remove(it)
            log("MATCH phase=$phaseName id=0x${it.messageId.toString(16)} source=pending")
            return it
        }
        while (true) {
            val remaining = (deadlineNanos - nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                log("TIMEOUT phase=$phaseName ids=$expected elapsed=${(nanoTime() - waitStarted) / 1_000_000L}ms alive=${channel.isAlive}")
                return null
            }
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                ensureActive("等待控制消息")
                continue
            }
            if (frame.messageId in messageIds) {
                log("MATCH phase=$phaseName id=0x${frame.messageId.toString(16)} elapsed=${(nanoTime() - waitStarted) / 1_000_000L}ms")
                return frame
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，暂存待后续消费",
            )
            // bring-up 期间的非预期消息（BMW 0x4C00/0xAE00/0xEA03..）也要留原始体：
            // 车机若在识别后立刻把凭据塞进私有消息，日志里只有长度是查不出结构的。
            dumpBodyOnce(frame)
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) {
                pendingFrames.removeFirst()
                if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true
                    log("iap2 非预期帧暂存区超过 $maxPendingFrames 条——丢弃最旧的（车机订阅类消息过多）")
                }
            }
        }
    }

    /**
     * 通用取帧（MFi 挑战 / 保活泵）：优先消费暂存的非预期帧
     * （保持到达顺序：暂存 > 新解析），再读新帧。
     */
    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    /** 只取新帧（先已解析队列、再读 socket）；绝不返回暂存区的帧。 */
    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        if (Thread.currentThread().isInterrupted || channel.isCancelled) throw InterruptedIOException("iAP2 接收已取消")
        receivedFrames.firstOrNull()?.let {
            receivedFrames.removeFirst()
            return it
        }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        for (frame in frames) {
            if (tracedReceivedFrames++ < 192) {
                log("RX phase=$phaseName id=0x${frame.messageId.toString(16)} bytes=${frame.body.size}（不记录凭据/签名内容）")
            } else if (tracedReceivedFrames == 193) {
                log("RX: 逐帧摘要达到 192 条，后续保留阶段/错误日志")
            }
        }
        if (frames.isEmpty()) return null
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    // ---- 日志 ----

    private fun logIdentification(
        ident: Iap2Messages.Identification,
        frame: Iap2Csm.Frame,
    ) {
        log("iap2 rx=0x1d01 车机身份 name=${ident.name} model=${ident.model} manufacturer=${ident.manufacturer}")
        ident.sendIds?.let { sends ->
            log("iap2 0x1d01 车机发送清单(${sends.size})=" + sends.sorted().joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 可选消息声明：StartHID(0x6800)=" +
                    (ident.declaresSend(Iap2MessageIds.START_HID)?.let { if (it) "会发" else "不发" } ?: "未知") +
                    " CarPlayStartSession(0x4301)=" +
                    (ident.declaresSend(Iap2MessageIds.CARPLAY_START_SESSION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        ident.receiveIds?.let { receives ->
            log("iap2 0x1d01 车机可收清单(${receives.size})=" + receives.sorted().joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 协议代际：请求 WiFi 配置(0x5702)=" +
                    (ident.declaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)?.let { if (it) "可收" else "不收" } ?: "未知") +
                    " 回 WiFi 配置(0x5703)=" +
                    (ident.declaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        log("iap2 0x1d01 原始体=" + frame.body.joinToString("") { "%02x".format(it) })
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }

    private fun ensureActive(stage: String) {
        if (Thread.currentThread().isInterrupted || channel.isCancelled) throw InterruptedIOException("iAP2 已取消（$stage）")
        if (!channel.isAlive) throw IOException("iAP2 链路已关闭（$stage）：${channel.closeReason ?: "未知原因"}")
    }
}

