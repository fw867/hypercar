package io.github.hypercar.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置仓库（SharedPreferences 落盘；UI 只依赖这里的默认值与键名）。
 * 与 UI（子代理E）的契约：全部属性为 suspend-free 同步读，写入立即生效并广播 Flow。
 */
object SettingsStore {

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences("hypercar_settings", Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun p(): SharedPreferences = requireNotNull(prefs) { "SettingsStore.init(context) 未调用" }

    private const val KEY_TARGET_MAC = "target_car_mac"
    private const val KEY_TARGET_NAME = "target_car_name"
    private const val KEY_AUTO_CONNECT = "auto_connect_on_bt"
    private const val KEY_MANUAL_BT_MAC = "manual_bt_mac"
    private const val KEY_HEAD_UNIT = "head_unit_profile"
    private const val KEY_REMOTE_PROFILES = "head_unit_remote_json"
    private const val KEY_REMOTE_PROFILES_AT = "head_unit_remote_at"
    private const val KEY_REMOTE_PROFILES_URL = "head_unit_remote_url"

    private const val KEY_WIDTH = "carlife_width"
    private const val KEY_HEIGHT = "carlife_height"
    private const val KEY_FPS = "carlife_fps"
    private const val KEY_ENABLE_AUDIO = "enable_audio"
    private const val KEY_CARLIFE_PKG = "carlife_package"
    private const val KEY_CARLIFE_ENABLED = "carlife_enabled"
    private const val KEY_CARLIFE_FIRST = "carlife_first"
    private const val KEY_IAP2_KEEPALIVE_ONLY = "iap2_keepalive_only"
    private const val KEY_KEEP_ALIVE_STATS = "keep_alive_stats"
    private const val KEY_DEBUG_LOG = "debug_log_enabled"
    private const val KEY_STABLE_DEVICE_ID = "stable_device_id"
    private const val KEY_DEGRADED_FIRST_CONNECT = "degraded_first_connect_done"
    private const val KEY_LAST_AIRPLAY_ENDPOINT = "last_airplay_endpoint"
    private const val KEY_PAIRING_IDENTITY = "pairing_identity"

    /** MAC 格式（字段写入前先大写归一，再按此校验；不合法直接存 null）。 */
    private val MAC_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

    /** 目标车机（多车场景）：null = 自动（来者不拒）。 */
    var targetCarMac: String?
        get() = p().getString(KEY_TARGET_MAC, null)
        set(v) = p().edit().putString(KEY_TARGET_MAC, v).apply()

    var targetCarName: String?
        get() = p().getString(KEY_TARGET_NAME, null)
        set(v) = p().edit().putString(KEY_TARGET_NAME, v).apply()

    /** 上车自动连接（ACL 广播唤醒），默认开。 */
    var autoConnect: Boolean
        get() = p().getBoolean(KEY_AUTO_CONNECT, true)
        set(v) = p().edit().putBoolean(KEY_AUTO_CONNECT, v).apply()

    /**
     * CarLife 编码分辨率的**兜底值**（仅当车机 `/info` 没回报显示器尺寸、
     * 且手机也没回报实际编码尺寸时才用）。
     *
     * 正常运行时不再读这里：连上车机后按 `/info` 的 displays 尺寸请求编码，
     * 手机在 `VIDEO_ENCODER_INIT_DONE` 里回报实际值后再覆盖——所以设置里的
     * 数值与车机原生分辨率不同也不会影响画质。
     */
    /** 选中的车机档案 id（见 `headunit/HeadUnitProfile.kt`）；默认宝马 iD8.5。 */
    var headUnitProfileId: String
        get() = p().getString(KEY_HEAD_UNIT, "bmw_id85") ?: "bmw_id85"
        set(v) = p().edit().putString(KEY_HEAD_UNIT, v).apply()

    /**
     * 远程车机档案（GitHub 下发的 JSON 原文）。空 = 只用内置档案。
     *
     * 存原文而不是解析结果：格式升级时不用管旧数据，解析失败也只是回落到内置表。
     */
    var remoteHeadUnitProfilesJson: String
        get() = p().getString(KEY_REMOTE_PROFILES, "") ?: ""
        set(v) = p().edit().putString(KEY_REMOTE_PROFILES, v).apply()

    /** 上次成功拉取远程档案的时间戳（0 = 从未拉过），设置页显示「x 分钟前」。 */
    var remoteProfilesFetchedAt: Long
        get() = p().getLong(KEY_REMOTE_PROFILES_AT, 0L)
        set(v) = p().edit().putLong(KEY_REMOTE_PROFILES_AT, v).apply()

    /** 远程档案下载地址（可指向镜像/私有分支）；空 = 用内置默认地址。 */
    var remoteProfilesUrl: String

        get() = p().getString(KEY_REMOTE_PROFILES_URL, "") ?: ""
        set(v) = p().edit().putString(KEY_REMOTE_PROFILES_URL, v).apply()

    /**
     * 上次**连接成功**的 AirPlay 端点，格式 `host:port|ssid`（ssid 可为空）。
     *
     * 为什么要记：宝马专用 CarPlay 盒子实测（docs/NEGOTIATION-GAPS.md §14）——
     * 它能稳定连上，是因为**直接连记住的 IP:端口**，连接时根本不做发现。
     * 我们每次上车都从 0x5703/0x4301/mDNS/扫网段重来，发现环节一卡整轮就失败。
     * 所以：连成功一次就存下来，下次同一热点直接试它（连通即用，失败再走发现）。
     */

    /**
     * 配对身份（AirPlay pair-setup 的长期身份）**备份**，格式与 `controller_identity.txt` 相同：
     * `pairingId hex私钥`。
     *
     * 为什么要有备份：车机记住的是这条配对的 pairingId + 公钥。身份文件一旦丢失
     * （进程崩溃时写盘失败、清理缓存、升级覆盖），我们会重新生成一对新密钥，
     * 车机就认为「这是台新设备」——现象正是「第二次上车连不上、只有两边删配对才能恢复」。
     * 这里把身份同时存进 SharedPreferences，文件丢了自动写回，相当于给配对加了份保险。
     */
    var pairingIdentityBackup: String
        get() = p().getString(KEY_PAIRING_IDENTITY, "") ?: ""
        set(v) = p().edit().putString(KEY_PAIRING_IDENTITY, v).apply()

    var lastAirplayEndpoint: String
        get() = p().getString(KEY_LAST_AIRPLAY_ENDPOINT, "") ?: ""
        set(v) = p().edit().putString(KEY_LAST_AIRPLAY_ENDPOINT, v).apply()


    var carlifeWidth: Int
        get() = p().getInt(KEY_WIDTH, 1920)   // 公版 CarLife 宽上限 1920（16:9 支持到 1920x1080）
        set(v) = p().edit().putInt(KEY_WIDTH, v.coerceAtLeast(2)).apply()

    var carlifeHeight: Int
        get() = p().getInt(KEY_HEIGHT, 1080)
        set(v) = p().edit().putInt(KEY_HEIGHT, v.coerceAtLeast(2)).apply()

    var carlifeFps: Int
        get() = p().getInt(KEY_FPS, 60)
        set(v) = p().edit().putInt(KEY_FPS, v.coerceIn(20, 60)).apply()

    var enableAudio: Boolean
        get() = p().getBoolean(KEY_ENABLE_AUDIO, true)
        set(v) = p().edit().putBoolean(KEY_ENABLE_AUDIO, v).apply()

    /** 唤醒目标 CarLife 系 app 的包名（CarLife / CarWith 的 CarLife 兼容 / 荣耀车联）。 */
    @Deprecated("已废弃：目标固定 com.baidu.carlife")
    var carlifePackage: String
        get() = p().getString(KEY_CARLIFE_PKG, "com.baidu.carlife") ?: "com.baidu.carlife"
        set(v) = p().edit().putString(KEY_CARLIFE_PKG, v).apply()

    /**
     * 拉起 CarLife 引擎页时投到哪块 display：**-1 = 主屏（默认）**。
     *
     * 为什么要有：CarLife 的 Activity 必须保持"可见前台"，否则它自带的 35 秒空闲 watchdog
     * 会 `ProcessUtil.killAllProcess` 自杀（现场实测 34~35s 必死）；但窗口摆在手机主屏又会
     * 挡住用户。`am start --display N` 可以把它投到副屏/虚拟屏（例如 CarWith 的 display 10），
     * 主屏留给 HyperCar——**前提是设备上真的存在这块屏**；不存在时 `am start` 会失败，
     * 唤醒逻辑会自动回退主屏重试，不会把 CarLife 弄没。
     */
    /**
     * 是否启用 CarLife 联动（默认**关**）。
     *
     * 关掉后不拉起、不探测、不做 AIDL 投屏探测：小米 CarLife 一被激活就会去动蓝牙
     * （车机互联要抢 BT 通道），实测会把 CarPlay 的 iAP2 ACL 链路顶断
     * （2026-10-26 16:31 现场：强制前台 3 次 → 车机重连 → 会话 torn down）。
     */
    var carlifeEnabled: Boolean
        get() = p().getBoolean(KEY_CARLIFE_ENABLED, false)
        set(v) = p().edit().putBoolean(KEY_CARLIFE_ENABLED, v).apply()
    /**
     * 实验开关（默认关）：**先**把 CarLife 拉到前台，等它做完蓝牙初始化，**再**启动 iAP2。
     *
     * 依据：CarLife 只有在前台才产视频（2015 协议「视频生成策略」），但它一起前台就会
     * 抢蓝牙把 iAP2 链路顶断。若它的"抢蓝牙"只发生在启动阶段，顺序反转后两者可能共存。
     */
    var carlifeFirst: Boolean
        get() = p().getBoolean(KEY_CARLIFE_FIRST, false)
        set(v) = p().edit().putBoolean(KEY_CARLIFE_FIRST, v).apply()

    /**
     * 联调开关：只保持 iAP2 链路，不在手机上启动 AirPlay 引擎。
     *
     * 用途：接收端（WinPlay）的接收会话与蓝牙 iAP2 强绑定——链路一断它就整进程退出；
     * 而 CarLife MD 只在「车机」侧（PC 上的 hypercar-hub）才连得上。开启后手机只当
     * iAP2 保活端，AirPlay 由 PC 侧推，两边不会抢同一条 AirPlay 会话。
     */
    var iap2KeepAliveOnly: Boolean
        get() = p().getBoolean(KEY_IAP2_KEEPALIVE_ONLY, false)
        set(v) = p().edit().putBoolean(KEY_IAP2_KEEPALIVE_ONLY, v).apply()

    /** 车机 /info 声明 keepAliveSendStatsAsBody 时置 true（屏幕 keepAlive 带统计体）。 */
    var keepAliveStats: Boolean
        get() = p().getBoolean(KEY_KEEP_ALIVE_STATS, false)
        set(v) = p().edit().putBoolean(KEY_KEEP_ALIVE_STATS, v).apply()

    /** 调试日志记录开关（诊断页切换；关=暂停写入环形缓冲）。 */
    var debugLogEnabled: Boolean
        get() = p().getBoolean(KEY_DEBUG_LOG, true)
        set(v) = p().edit().putBoolean(KEY_DEBUG_LOG, v).apply()

    /**
     * 无 Shizuku 时用的稳定 deviceID（首次生成后持久化，格式仿 MAC）。
     *
     * Android 11+ 把本机真实蓝牙 MAC 锁死（无 root/Shizuku 拿不到）。AirPlay 会话级
     * SETUP 的 deviceID、iAP2 0x4300/0x4E0C/0x4E0E 的传输标识都要它。WinPlay 会按
     * deviceID 严格 403、奥迪校验 0x4300，但宝马 iD8.5 实测未出现 MAC 校验——
     * 故无 Shizuku 时退化为一个「本地管理位置位」的稳定伪 MAC，保证同一台手机跨会话
     * 标识一致（比恒定占位 02:00:00:00:00:00 更不易被当占位值丢弃）。
     */
    val stableDeviceId: String
        get() {
            p().getString(KEY_STABLE_DEVICE_ID, null)?.let { return it }
            // 首次生成：本地管理位置位(0x02) + 单播位清零，规避与真实 MAC 撞车
            val bytes = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            val mac = bytes.joinToString(":") { "%02X".format(it) }
            p().edit().putString(KEY_STABLE_DEVICE_ID, mac).apply()
            return mac
        }

    /**
     * 手动指定的本机蓝牙 MAC（大小写归一，非法值写不进去）。
     *
     * 为什么需要它：Android 6.0+ 应用层拿不到本机蓝牙地址，读 `settings/secure` 又要
     * Shizuku/root。WinPlay / 部分奥迪会校验协议里的 bluetoothTransportIdentifier，
     * 伪 ID 会被 403；这类接收端下用户可以用 PC 跑一次
     * `adb shell settings get secure bluetooth_address`，把真值填进来。
     *
     * 优先级：手动值 > 自动解析（Shizuku/getAddress）> 稳定伪 ID。
     * 设为 null 即恢复自动。
     */
    var manualBtMac: String?
        get() = p().getString(KEY_MANUAL_BT_MAC, null)
        set(v) {
            val normalized = v?.trim()?.uppercase()?.takeIf { MAC_PATTERN.matches(it) }
            p().edit().putString(KEY_MANUAL_BT_MAC, normalized).apply()
        }

    /** 手动 MAC 是否已设置（UI 用来显示"手动/自动"）。 */
    val hasManualBtMac: Boolean
        get() = manualBtMac != null

    /**
     * 无 Shizuku 降级模式下，是否已完成过一次「前台首连」（系统确认框已批准）。
     *
     * WifiNetworkSpecifier 每个热点**首次**连接弹一次系统确认框，且后台 FGS 拉起时
     * 不保证弹得出。所以推荐装好后在前台手动连一次完成批准；本标记用于 UI 判断是否
     * 还需要提示用户「去前台完成首连」。只在降级模式有意义，装了 Shizuku 就不再看它。
     */
    var degradedFirstConnectDone: Boolean
        get() = p().getBoolean(KEY_DEGRADED_FIRST_CONNECT, false)
        set(v) = p().edit().putBoolean(KEY_DEGRADED_FIRST_CONNECT, v).apply()

    /**
     * 引擎配置 JSON（Rust EngineConfig::from_json 消费）。
     *
     * @param mdHost 非空 = CarLife 主动拨号模式（MD 自己在监听，我们当车机去拨它）；
     *   为空 = HU 监听模式（我们自己 bind 7240..9340 等 CarLife 连入）。
     */
    fun engineConfigJson(
        carIp: String,
        airplayPort: Int,
        deviceBluetoothMac: String,
        identityPath: String,
        mdHost: String? = null,
        carlifeOnly: Boolean = false,
    ): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{")
            append("\"device_id\":\"").append(esc(deviceBluetoothMac)).append("\",")
            append("\"car_ip\":\"").append(esc(carIp)).append("\",")
            append("\"airplay_port\":").append(airplayPort).append(",")
            append("\"width\":").append(carlifeWidth.coerceAtLeast(2) / 2 * 2).append(",")
            append("\"height\":").append(carlifeHeight.coerceAtLeast(2) / 2 * 2).append(",")
            append("\"fps\":").append(carlifeFps).append(",")
            append("\"enable_audio\":").append(enableAudio).append(",")
            append("\"keep_alive_stats\":").append(keepAliveStats).append(",")
            if (carlifeOnly) append("\"carlife_only\":true,")
            // 主动拨号模式：探测到本机 CarLife（MD）自己在 7240 监听时传 "127.0.0.1"，
            // Rust 就会逐个拨这 6 条通道，而不是去 bind（两边抢端口必有一边 EADDRINUSE）。
            if (!mdHost.isNullOrBlank()) {
                append("\"md_host\":\"").append(esc(mdHost)).append("\",")
            }
            append("\"identity_path\":\"").append(esc(identityPath)).append("\"")
            append("}")
        }
    }
}

