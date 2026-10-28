package io.github.hypercar.iap2

/**
 * 无线 CarPlay 会话中「发送端（iPhone 角色）」视角的消息目录 + 参数编解码。
 *
 * 完整无线时序（对照 xcertplay Iap2WirelessControlClient 与 carplay-wifi-extractor
 * README 的抓包流程；本项目实测补充见 docs 与类注释）：
 *
 *  1. 蓝牙 RFCOMM 建链（两台设备需先完成系统级蓝牙配对）
 *  2. 链路层握手：marker FF55 → 双方 SYN/SYN|ACK（本项目手机为发起方）
 *  3. 手机 → 配件: StartIdentification(0x1D00)
 *     配件 → 手机: IdentificationInformation(0x1D01)   // 车机身份 + 能力清单
 *     手机 → 配件: IdentificationAccepted(0x1D02)
 *  4. MFi 认证（0xAA00 段，配件证明自己；手机侧可跳过校验）
 *  5. 手机 → 配件: 设备状态问候 0x4E0C/0x4E0A/0x4E0B/0x4E09（**必须紧跟 0xAA05**，
 *     BMW iD8.5/MGU-86 实测：发晚了车机 5.4s 拆链，见 §9j）
 *  6. EA 会话 0xEA00（param0=u8 协议号、param1=**协议名字符串**——BMW §9j 第三证据）
 *  7. 手机 → 配件: RequestAccessoryWiFiConfiguration(0x5702)
 *     配件 → 手机: AccessoryWiFiConfiguration(0x5703)   // 明文 TLV：SSID/密码/信道
 *  8. 手机 → 配件: CarPlayAvailability(0x4300) → 配件回 CarPlayStartSession(0x4301)
 *  9. AirPlay 主会话 —— 由上层（Rust 核心层）接管，iAP2 只负责产出端点
 */
object Iap2MessageIds {
    // ---- 识别（Identification）----
    const val START_IDENTIFICATION = 0x1D00 // 手机 → 配件
    const val IDENTIFICATION_INFORMATION = 0x1D01 // 配件 → 手机
    const val IDENTIFICATION_ACCEPTED = 0x1D02 // 手机 → 配件
    const val IDENTIFICATION_REJECTED = 0x1D03 // 配件 → 手机

    // ---- 无线 CarPlay ----
    const val CARPLAY_AVAILABILITY = 0x4300 // 手机 → 配件：询问能否开始
    const val CARPLAY_START_SESSION = 0x4301 // 配件 → 手机：无线参数 + AirPlay 端点
    const val WIRELESS_CARPLAY_UPDATE = 0x4E0D // 手机 → 配件：无线可用性通知
    const val DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E // 手机 → 配件：蓝牙/USB 标识通知

    // ---- 设备状态问候（真 iPhone 在 0xAA05 之后突发的一批消息）----
    const val DEVICE_INFORMATION_UPDATE = 0x4E09 // 手机 → 配件：设备名
    const val DEVICE_LANGUAGE_UPDATE = 0x4E0A // 手机 → 配件：语言
    const val DEVICE_TIME_UPDATE = 0x4E0B // 手机 → 配件：时间（u64 epoch + s16 tz + u8 dst）
    const val DEVICE_UUID_UPDATE = 0x4E0C // 手机 → 配件：无线会话标识 UUID

    // ---- WiFi 配置交换 ----
    const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702 // 手机 → 配件
    const val ACCESSORY_WIFI_CONFIGURATION = 0x5703 // 配件 → 手机

    // ---- HID（接收端发 0x6800，触摸/HID 数据由 Rust 侧经 AirPlay 事件通道回传，
    //      iAP2 层不实现 0x68xx 出向，仅识别入向）----
    const val START_HID = 0x6800 // 配件 → 手机
    const val DEVICE_HID_REPORT = 0x6801 // 手机 → 配件（应答，本项目不发）
    const val ACCESSORY_HID_REPORT = 0x6802 // 配件 → 手机（触摸/旋钮事件）
    const val STOP_HID = 0x6803

    // ---- MFi 认证（单向：手机验证配件；发送端可跳过校验、自报成功）----
    const val MFI_REQUEST_CERTIFICATE = 0xAA00 // 手机 → 配件
    const val MFI_CERTIFICATE = 0xAA01 // 配件 → 手机（参数0=证书）
    const val MFI_REQUEST_CHALLENGE = 0xAA02 // 手机 → 配件（参数0=挑战 N 字节）
    const val MFI_RESPONSE = 0xAA03 // 配件 → 手机（参数0=签名）
    const val MFI_AUTHENTICATION_FAILED = 0xAA04 // 手机 → 配件（或配件拒绝挑战）
    const val MFI_AUTHENTICATION_SUCCEEDED = 0xAA05 // 手机 → 配件（自报结果）
    const val ACCESSORY_AUTHENTICATION_SERIAL_NUMBER = 0xAA06 // 配件 → 手机，认证序列号通知

    // ---- 配件 bring-up 期间发来的订阅请求（接收并记录即可）----
    const val START_NOW_PLAYING_UPDATES = 0x5000
    const val STOP_NOW_PLAYING_UPDATES = 0x5001
    const val START_ROUTE_GUIDANCE_UPDATES = 0x5200
    const val START_POWER_UPDATES = 0xAE00

    /** 0xAE01 电源状态上报（车机发 0xAE00 后手机回）。 */
    const val POWER_UPDATE = 0xAE01
    const val START_COMMUNICATIONS_UPDATES = 0x4157
    const val START_CALL_STATE_UPDATES = 0x4154

    // ---- ExternalAccessory 会话 ----
    const val EA_OPEN_SESSION = 0xEA00 // 手机 → 配件
    const val EA_CLOSE_SESSION = 0xEA01 // 手机 → 配件
    const val EA_SESSION_STATUS = 0xEA03 // 配件 → 手机

    fun nameOf(messageId: Int): String =
        when (messageId) {
            START_IDENTIFICATION -> "StartIdentification"
            IDENTIFICATION_INFORMATION -> "IdentificationInformation"
            IDENTIFICATION_ACCEPTED -> "IdentificationAccepted"
            IDENTIFICATION_REJECTED -> "IdentificationRejected"
            CARPLAY_AVAILABILITY -> "CarPlayAvailability"
            CARPLAY_START_SESSION -> "CarPlayStartSession"
            WIRELESS_CARPLAY_UPDATE -> "WirelessCarPlayUpdate"
            DEVICE_TRANSPORT_IDENTIFIER -> "DeviceTransportIdentifier"
            DEVICE_INFORMATION_UPDATE -> "DeviceInformationUpdate"
            DEVICE_LANGUAGE_UPDATE -> "DeviceLanguageUpdate"
            DEVICE_TIME_UPDATE -> "DeviceTimeUpdate"
            DEVICE_UUID_UPDATE -> "DeviceUUIDUpdate"
            REQUEST_ACCESSORY_WIFI_CONFIGURATION -> "RequestAccessoryWiFiConfiguration"
            ACCESSORY_WIFI_CONFIGURATION -> "AccessoryWiFiConfiguration"
            START_HID -> "StartHID"
            DEVICE_HID_REPORT -> "DeviceHIDReport"
            ACCESSORY_HID_REPORT -> "AccessoryHIDReport"
            STOP_HID -> "StopHID"
            MFI_REQUEST_CERTIFICATE -> "MFiRequestCertificate"
            MFI_CERTIFICATE -> "MFiCertificate"
            MFI_REQUEST_CHALLENGE -> "MFiRequestChallenge"
            MFI_RESPONSE -> "MFiResponse"
            MFI_AUTHENTICATION_FAILED -> "MFiAuthenticationFailed"
            MFI_AUTHENTICATION_SUCCEEDED -> "MFiAuthenticationSucceeded"
            ACCESSORY_AUTHENTICATION_SERIAL_NUMBER -> "AccessoryAuthenticationSerialNumber"
            EA_OPEN_SESSION -> "StartEASession"
            EA_CLOSE_SESSION -> "StopEASession"
            EA_SESSION_STATUS -> "EASessionStatus"
            START_NOW_PLAYING_UPDATES -> "StartNowPlayingUpdates"
            STOP_NOW_PLAYING_UPDATES -> "StopNowPlayingUpdates"
            START_ROUTE_GUIDANCE_UPDATES -> "StartRouteGuidanceUpdates"
            START_POWER_UPDATES -> "StartPowerUpdates"
            START_COMMUNICATIONS_UPDATES -> "StartCommunicationsUpdates"
            START_CALL_STATE_UPDATES -> "StartCallStateUpdates"
            else -> "0x%04X".format(messageId)
        }
}

/**
 * 0x1D01 / 0x5703 / 0x4301 / 0xEA00 的参数解析与构建 —— 全部为**纯函数**（object
 * 内独立可调用，便于补单测）。0x1D01 参数布局照 §5（字段 id → 含义）：
 *
 *  0=name, 1=modelIdentifier, 2=manufacturer, 3=serialNumber, 4=firmwareVersion,
 *  5=hardwareVersion, 6=发出的消息ID列表(u16[]), 7=接收的消息ID列表(u16[]),
 *  8=电源类型, 9=最大电流(mA), 10=EA协议组, 12=语言, 13=语言列表,
 *  16=USB传输组件(有线), 17=蓝牙传输组件(无线), 18=HID组件, 24=无线CarPlay组件(含SSID)。
 */
object Iap2Messages {
    // ---- 0x1D01 身份参数 id ----
    const val IDENT_NAME = 0
    const val IDENT_MODEL = 1
    const val IDENT_MANUFACTURER = 2
    const val IDENT_SERIAL = 3
    const val IDENT_FIRMWARE = 4
    const val IDENT_HARDWARE = 5
    const val IDENT_SEND_IDS = 6 // 车机声明「它可发送」的 u16 清单
    const val IDENT_RECEIVE_IDS = 7 // 车机声明「它可接收」的 u16 清单
    const val IDENT_EA_GROUP_FIRST = 10 // EA 协议组起始 id（10/11/12 均可能）
    const val IDENT_EA_GROUP_LAST = 12

    /** 无线 CarPlay 传输组件（BMW 档案 B 才有；值里含热点 SSID 类信息）。 */
    const val IDENT_WIRELESS_CARPLAY_COMPONENT = 24

    // ---- 0x5703 WiFi 配置参数 id ----
    const val WIFI_SSID = 1
    const val WIFI_PASSPHRASE = 2
    const val WIFI_SECURITY_TYPE = 3
    const val WIFI_CHANNEL = 4

    // ---- 0x4301 CarPlayStartSession 参数 id ----
    const val START_SESSION_WIRELESS_GROUP = 1
    const val START_SESSION_AIRPLAY_PORT = 2
    const val START_SESSION_DEVICE_IDENTIFIER = 3
    const val START_SESSION_PUBLIC_KEY = 4
    const val START_SESSION_SOURCE_VERSION = 5

    /** 0x1D01 解析结果：身份 + 能力清单 + EA 协议。 */
    data class Identification(
        val name: String?,
        val model: String?,
        val manufacturer: String?,
        /** 车机声明「它会发送」的消息 ID；null = 未提供清单（未知，按保守策略等待）。 */
        val sendIds: Set<Int>?,
        /** 车机声明「它可接收」的消息 ID；null = 未提供清单。 */
        val receiveIds: Set<Int>?,
        /** ExternalAccessory 协议（(协议号, 协议名)）。 */
        val eaProtocols: List<EaProtocol>,
        /**
         * 是否声明了 **WirelessCarPlayTransportComponent（参数 id=0x18=24）**。
         *
         * 这是「车机当前愿意做无线 CarPlay」的组件级证据：BMW iD8.5 的档案 B 才带它
         * （component 名形如 `BMW 31726 Wifi`），档案 A 里没有（cardeck 2026-10-21
         * 两份 0x1D01 原始体对照定谳）。A/B 判定用它做第三判据。
         */
        val hasWirelessCarPlayComponent: Boolean,
    ) {
        /** 车机是否声明会发某消息；null = 未提供清单（未知）。 */
        fun declaresSend(messageId: Int): Boolean? = sendIds?.contains(messageId)

        /** 车机是否声明能收某消息；null = 未提供清单（未知）。 */
        fun declaresReceive(messageId: Int): Boolean? = receiveIds?.contains(messageId)
    }

    data class EaProtocol(
        val protocolId: Int,
        val name: String,
    )

    /** 0x5703 解析结果（明文热点凭据）。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    /** 0x4301 解析结果（AirPlay 端点 + 可选的自带 AP 信息）。 */
    data class StartSession(
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKeyHex: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        /** 无线组内解析出的 AP（新协议车机的唯一凭据来源；旧协议为 null，用 0x5703）。 */
        val wirelessAp: WifiConfiguration?,
    )

    /**
     * 纯函数解析 0x1D01。
     *
     * **同一个参数 id 允许重复出现**：EA 协议组就是「一条协议一个 id=10 参数」。
     * BMW MGU-55 实测声明了 7 条 EA 协议（de.bmw.a4a.pairing/data/tc/ar/metadata/
     * mainline/bt，全部 id=10）。旧实现 `associateBy { it.id }` 把它们压成一条（只剩
     * 最后一个 de.bmw.a4a.bt），于是 0xEA00 只给最后那条协议开过会话——
     * de.bmw.a4a.pairing 这种「配对通道」根本没打开过（2026-10-24 实车日志定谳）。
     */
    fun parseIdentification(body: ByteArray): Identification {
        val params = Iap2Csm.parseParams(body)
        // 标量字段取首个出现值；重复 id 只对 EA 组有语义，不能整体 associateBy
        val firstById = params.groupBy { it.id }.mapValues { it.value.first() }
        val sendIds = firstById[IDENT_SEND_IDS]?.asU16List()?.takeIf { it.isNotEmpty() }?.toSet()
        val receiveIds = firstById[IDENT_RECEIVE_IDS]?.asU16List()?.takeIf { it.isNotEmpty() }?.toSet()
        val ea = ArrayList<EaProtocol>()
        // 按出现顺序（车机给的顺序就是它的优先级），不要再按 id 排序/去重
        for (param in params) {
            if (param.id !in IDENT_EA_GROUP_FIRST..IDENT_EA_GROUP_LAST) continue
            val sub = Iap2Csm.parseParams(param.value).groupBy { it.id }.mapValues { it.value.first() }
            val protocolId = sub[0]?.asU8() ?: continue
            val name = sub[1]?.asStringOrEmpty() ?: "?"
            ea += EaProtocol(protocolId, name)
        }
        return Identification(
            name = firstById[IDENT_NAME]?.asStringOrNull(),
            model = firstById[IDENT_MODEL]?.asStringOrNull(),
            manufacturer = firstById[IDENT_MANUFACTURER]?.asStringOrNull(),
            sendIds = sendIds,
            receiveIds = receiveIds,
            eaProtocols = ea,
            hasWirelessCarPlayComponent = firstById[IDENT_WIRELESS_CARPLAY_COMPONENT] != null,
        )
    }

    /** 纯函数解析 0x5703：SSID/密码/安全类型/信道（地址类字段一律 rstrip 全部尾部 NUL）。 */
    fun parseWifiConfiguration(body: ByteArray): WifiConfiguration {
        val params = Iap2Csm.parseParams(body).associateBy { it.id }
        return WifiConfiguration(
            ssid = params[WIFI_SSID]?.asStringOrEmpty() ?: "",
            passphrase = params[WIFI_PASSPHRASE]?.asStringOrEmpty() ?: "",
            securityType = params[WIFI_SECURITY_TYPE]?.asU8() ?: 0,
            channel = params[WIFI_CHANNEL]?.asU8() ?: 0,
        )
    }

    /**
     * 纯函数解析 0x4301。顶层：#1=无线组（内嵌 AP 信息 + 地址）、#2=AirPlay 端口、
     * #3=deviceIdentifier、#4=公钥、#5=sourceVersion。
     *
     * 新协议无线组与 CarPlay Addendum 的 AccessoryWiFiConfigurationInformation 同构
     * （0/1=SSID/密码、2=信道、3=IP、4=安全类型），但个别实现从 1 起编号——两种都兼容：
     * 前两个「非 IP」的 NUL 结尾字符串依次当 SSID/密码，单字节字段按取值范围归类。
     */
    fun parseStartSession(body: ByteArray): StartSession {
        val params = Iap2Csm.parseParams(body).associateBy { it.id }
        val wireless = params[START_SESSION_WIRELESS_GROUP]?.let { Iap2Csm.parseParams(it.value) }.orEmpty()
        // 地址字段：定长 NUL 填充，必须 rstrip 全部尾部 0x00（见 Iap2Csm.asStringOrNull 注释）
        //
        // 2026-10-25 WinPlay 现场：0x4301 原始体里**同时**有 IPv4（"192.168.137.1"）和
        // IPv6 link-local（fe80::…），但 IPv4 不在无线组的 id==3 上——只按 id==3 取会只剩
        // fe80，而 fe80 在 Android 上不带 scope 直接 connect 必然 EINVAL，表现为
        // 「地址拿到了却连不上」（连续两版日志都是这个死法）。
        // 改成：**内外层都扫**，只认"长得像地址"的字符串，并把 IPv4 排到最前。
        // MAC 形如 "C2:BF:BE:E1:85:C0" 也会满足「冒号 >= 2」，必须排除（deviceIdentifier 就是它）
        val macRe = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        val ipv4Re = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        // 只认**私有网段 / 链路本地**地址：车机热点必然是这两类；
        // 这条过滤能挡掉参数里长得像 IP 的版本号（如 "1.0.0.0"）和公网地址。
        fun isPrivateIpv4(s: String): Boolean {
            val p = s.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p.any { it !in 0..255 }) return false
            return p[0] == 10 ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 169 && p[1] == 254)
        }
        fun isLocalIpv6(s: String): Boolean {
            val l = s.lowercase()
            // fe80::/10 链路本地、fc00::/7 唯一本地（ULA）
            return l.startsWith("fe8") || l.startsWith("fe9") || l.startsWith("fea") ||
                l.startsWith("feb") || l.startsWith("fc") || l.startsWith("fd")
        }
        val raw =
            (wireless.mapNotNull { it.asStringOrNull() } + params.values.mapNotNull { it.asStringOrNull() })
                .map { it.trim() }
                .filter { s ->
                    (ipv4Re.matches(s) && isPrivateIpv4(s)) ||
                        (s.count { it == ':' } >= 2 && s.length <= 45 && !macRe.matches(s) && isLocalIpv6(s))
                }
                .distinct()
                .sortedBy { s -> if (ipv4Re.matches(s)) 0 else 1 }
        // 过滤后为空就退回"只是排除 MAC"的宽口径：宁可多一个候选，也不要一个地址都没有
        val addresses = if (raw.isNotEmpty()) {
            raw
        } else {
            (wireless.mapNotNull { it.asStringOrNull() } + params.values.mapNotNull { it.asStringOrNull() })
                .map { it.trim() }
                .filter { s ->
                    ipv4Re.matches(s) ||
                        (s.count { it == ':' } >= 2 && s.length <= 45 && !macRe.matches(s))
                }
                .distinct()
                .sortedBy { s -> if (ipv4Re.matches(s)) 0 else 1 }
        }
        return StartSession(
            airPlayPort = params[START_SESSION_AIRPLAY_PORT]?.asU32() ?: 0,
            deviceIdentifier = params[START_SESSION_DEVICE_IDENTIFIER]?.asStringOrNull(),
            publicKeyHex = params[START_SESSION_PUBLIC_KEY]?.asStringOrNull(),
            sourceVersion = params[START_SESSION_SOURCE_VERSION]?.asStringOrNull(),
            ipAddresses = addresses,
            wirelessAp = parseWirelessAp(wireless),
        )
    }

    /**
     * 从 0x4301 的无线组里解析 AP 信息（新协议车机的唯一凭据来源）。
     * 只取「不像 IP 地址」的 NUL 结尾字符串：第一个当 SSID、第二个当密码；
     * 单字节字段按取值范围归类（信道 1..196、安全类型 0..4），信道优先 id=2、
     * 其次 id=4/id=3 中不是安全类型的那个。解析不出来返回 null（调用方按「无 AP」处理）。
     */
    fun parseWirelessAp(group: List<Iap2Csm.Param>): WifiConfiguration? {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val strings =
            group
                .sortedBy { it.id }
                .mapNotNull { it.asStringOrNull() }
                .filter { it.isNotEmpty() && !ipv4.matches(it) && !it.contains(':') }
        val ssid = strings.getOrNull(0)?.takeIf { it.length <= 32 } ?: return null
        val passphrase = strings.getOrNull(1) ?: ""
        val singles = group.filter { it.value.size == 1 }.associate { it.id to (it.value[0].toInt() and 0xFF) }
        val security =
            singles[4]?.takeIf { it in 0..4 }
                ?: singles[3]?.takeIf { it in 0..4 }
                ?: 0
        val channel =
            singles[2]?.takeIf { it in 1..196 }
                ?: singles[4]?.takeIf { it in 1..196 && it != security }
                ?: singles[3]?.takeIf { it in 1..196 && it != security }
                ?: 0
        return WifiConfiguration(ssid = ssid, passphrase = passphrase, channel = channel, securityType = security)
    }

    // ---- 出向消息构建 ----

    /** 0xEA00：param0=u8 协议号、param1=**协议名字符串**（BMW §9j 第三证据：发成 u16 协议号时车机不回 0xEA03）。 */
    fun eaOpenSession(protocolId: Int, protocolName: String): ByteArray =
        Iap2Csm.u8(0, protocolId) + Iap2Csm.string(1, protocolName)


    /**
     * 0xAE01 PowerUpdate：**原样重放真 iPhone 的报文**（2026-10-24 HCI 抓包，BMW MGU-55）。
     *
     * 参数语义尚未定谳（param 0x0006 / 0x0004，值按小端浮点解释疑似「电池电压 3.66」与
     * 「供电状态」），所以这里**不解释、不自造数值**，直接复用真机字节：
     * 车机要的是「有正常 iOS 设备的电源上报」，而不是某个具体读数。
     *
     * 原始抓包（含 msgID，供核对）：
     *   ae01 0006 0006 004f6a40 0000
     *   ae01 0005 0004 00bd40   0000
     * 末尾 0000 是链路帧尾，不属于消息体。
     */
    fun powerUpdates(): List<ByteArray> = listOf(
        byteArrayOf(0x00, 0x06, 0x00, 0x06, 0x00, 0x4f, 0x6a, 0x40),
        byteArrayOf(0x00, 0x05, 0x00, 0x04, 0x00, 0xbd.toByte(), 0x40),
        byteArrayOf(0x00, 0x06, 0x00, 0x06, 0x00, 0x4f, 0x6a, 0x40),
    )
    /** 0x4300：无线组件（transport=1 蓝牙）+ 传输标识（本机蓝牙 MAC）。 */
    fun carPlayAvailability(bluetoothMac: String): ByteArray =
        Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac))

    /** 0x4E0B 设备时间：u64 epoch 秒 + s16 时区分钟 + u8 夏令时。 */
    fun deviceTime(epochSeconds: Long, timezoneOffsetMinutes: Int, daylightSaving: Int): ByteArray =
        Iap2Csm.u64(0, epochSeconds) +
            Iap2Csm.s16(1, timezoneOffsetMinutes) +
            Iap2Csm.u8(2, daylightSaving)


}
