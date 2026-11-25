package io.github.hypercar.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置仓库（SharedPreferences 落盘；UI 只依赖这里的默认值与键名）。
 * 与 UI 的契约：全部属性为 suspend-free 同步读，写入立即生效并广播 Flow。
 */
object SettingsStore {
    const val DEFAULT_VIDEO_WIDTH = 1920
    const val DEFAULT_VIDEO_HEIGHT = 1080


    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        io.github.hypercar.bluetooth.LocalDeviceName.init(context)
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences("hypercar_settings", Context.MODE_PRIVATE).also {
                        // Retired Wi-Fi experiments must not survive an upgrade as saved opt-ins.
                        if (it.contains("proactive_wifi_reconnect") || it.contains("proactive_wifi_session")) {
                            it.edit().remove("proactive_wifi_reconnect").remove("proactive_wifi_session").apply()
                        }
                    }
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

    private const val KEY_WIDTH = "video_width"
    private const val KEY_HEIGHT = "video_height"
    private const val KEY_FPS = "video_fps"
    private const val KEY_KEEP_ALIVE_STATS = "keep_alive_stats"
    private const val KEY_DEBUG_LOG = "debug_log_enabled"
    private const val KEY_STABLE_DEVICE_ID = "stable_device_id"
    private const val KEY_DEGRADED_FIRST_CONNECT = "degraded_first_connect_done"
    private const val KEY_LAST_AIRPLAY_ENDPOINT = "last_airplay_endpoint"
    private const val KEY_PAIRING_IDENTITY = "pairing_identity"
    private const val KEY_PAIRED_CARS = "paired_cars"

    /** MAC 格式（字段写入前先大写归一，再按此校验；不合法直接存 null）。 */

    /** 目标车机（多车场景）：null = 自动（来者不拒）。 */
    var targetCarMac: String?
        get() = p().getString(KEY_TARGET_MAC, null)
        set(v) = p().edit().putString(KEY_TARGET_MAC, v).apply()

    var targetCarName: String?
        get() = p().getString(KEY_TARGET_NAME, null)
        set(v) = p().edit().putString(KEY_TARGET_NAME, v).apply()

    fun setTargetCar(mac: String?, name: String?) {
        p().edit().putString(KEY_TARGET_MAC, mac).putString(KEY_TARGET_NAME, name).apply()
    }

    /** 清理临时排查版留下的组件禁用；正式行为仅由 autoConnect 控制。 */
    fun migrateLegacyListeningMode(context: Context) {
        if (!p().contains("listening_disabled")) return
        if (p().getBoolean("listening_disabled", false)) autoConnect = false
        for (name in listOf(
            "io.github.hypercar.autowake.CarBluetoothReceiver",
            "io.github.hypercar.carui.MusicNotificationListener",
            "io.github.hypercar.autowake.HyperCarService",
        )) {
            context.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(context.packageName, name),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
        p().edit().remove("listening_disabled").apply()
    }

    val developerModeEnabled: Boolean
        get() = p().getBoolean("developer_mode_enabled", false)

    fun enableDeveloperMode() {
        if (developerModeEnabled) return
        p().edit().putBoolean("developer_mode_enabled", true).apply()
        SettingsRevision.bump()
    }

    var developerMenuVisible: Boolean
        get() = developerModeEnabled && p().getBoolean("developer_menu_visible", true)
        set(value) {
            if (!developerModeEnabled) return
            p().edit().putBoolean("developer_menu_visible", value).apply()
            SettingsRevision.bump()
        }

    var appThemeMode: AppThemeMode
        get() = AppThemeMode.fromValue(p().getString("app_theme_mode", "dark"))
        set(value) {
            p().edit().putString("app_theme_mode", value.value).apply()
            SettingsRevision.bump()
        }

    /** Manual-stop cooldown; one atomic preference retains the deadline across process restarts. */
    var automaticPause: String
        get() = p().getString("automatic_connection_pause", "").orEmpty()
        set(value) = p().edit().putString("automatic_connection_pause", value).apply()

    /** 默认开启，启动条件满足后开始监听。 */
    var autoConnect: Boolean
        get() = p().getBoolean(KEY_AUTO_CONNECT, true)
        set(v) = p().edit().putBoolean(KEY_AUTO_CONNECT, v).apply()

    /** 实验：根据主屏使用权建立或释放视频通道。 */
    var screenResourceLifecycle: Boolean
        get() = p().getBoolean("screen_resource_lifecycle", false)
        set(v) = p().edit().putBoolean("screen_resource_lifecycle", v).apply()

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
     * 所以：连成功一次就存下来，下次同一热点直接试它（连通即用，失败再走发现）。
     */
    var lastAirplayEndpoint: String
        get() = p().getString(KEY_LAST_AIRPLAY_ENDPOINT, "") ?: ""
        set(v) = p().edit().putString(KEY_LAST_AIRPLAY_ENDPOINT, v).apply()

    /** Known Wi-Fi/receiver associations survive an expired AirPlay IP/port. No passwords. */
    var knownCarNetworks: Set<String>
        get() = p().getStringSet("known_car_networks", emptySet())?.toSet().orEmpty()
        set(value) = p().edit().putStringSet("known_car_networks", value).apply()

    fun rememberCarNetwork(ssid: String, mac: String) {
        if (ssid.isBlank() || io.github.hypercar.session.PairingEvidence.receiverKey(mac) == null) return
        knownCarNetworks = knownCarNetworks + "$ssid|${mac.uppercase(java.util.Locale.ROOT)}"
    }

    internal fun carHotspotBssid(receiver: String?, ssid: String): String? {
        val key = io.github.hypercar.net.CarWifiApMemory.key(receiver, ssid) ?: return null
        return io.github.hypercar.net.CarWifiApMemory.normalize(p().getString(key, null))
    }

    internal fun rememberCarHotspotBssid(receiver: String, ssid: String, bssid: String) {
        val key = io.github.hypercar.net.CarWifiApMemory.key(receiver, ssid) ?: return
        val ap = io.github.hypercar.net.CarWifiApMemory.normalize(bssid) ?: return
        p().edit().putString(key, ap).apply()
    }

    /**
     * 配对身份（AirPlay pair-setup 的长期身份）**备份**，格式与 `controller_identity.txt` 相同：
     * `pairingId hex私钥`。
     *
     * 为什么要有备份：车机记住的是这条配对的 pairingId + 公钥。身份文件一旦丢失
     * （进程崩溃时写盘失败、清理缓存、升级覆盖），我们会重新生成一对新密钥，
     * 车机就认为「这是台新设备」——现象正是「第二次上车连不上、只有两边删配对才能恢复」。
     */
    var pairingIdentityBackup: String
        get() = p().getString(KEY_PAIRING_IDENTITY, "") ?: ""
        set(v) = p().edit().putString(KEY_PAIRING_IDENTITY, v).apply()

    /**
     * 成功配对过（AirPlay 会话曾经激活）的车机记录，元素格式 `MAC|名称|时间戳`。
     *
     * 用途：设置页「目标车机」的已配对设备列表里给这些车机打「已配对」标记——
     * 蓝牙已配对 ≠ CarPlay 配对成功过（后者才需要本机保存的 AirPlay 身份）。
     * 由 [markCarPaired] 在会话进入 connected 时写入，最多保留 12 条。
     */
    var pairedCars: Set<String>
        get() = p().getStringSet(KEY_PAIRED_CARS, emptySet())?.toSet().orEmpty()
        private set(v) = p().edit().putStringSet(KEY_PAIRED_CARS, v).apply()

    /** 记录一台"配对成功过"的车机（同 MAC 覆盖，保留最近 12 台）。 */
    fun markCarPaired(mac: String, name: String) {
        val m = mac.trim().uppercase()
        if (m.isEmpty() || !m.contains(':')) return
        val safeName = name.replace('|', '/').ifBlank { "车机" }.take(48)
        val now = System.currentTimeMillis()
        val kept = pairedCars.filterNot { it.substringBefore('|').equals(m, ignoreCase = true) }
        pairedCars = (kept + "$m|$safeName|$now")
            .sortedByDescending { it.substringAfterLast('|').toLongOrNull() ?: 0L }
            .take(12)
            .toSet()
    }

    /** 这台车机是否曾经配对成功过。 */
    fun isCarPaired(mac: String): Boolean {
        val m = mac.trim()
        if (m.isEmpty()) return false
        return pairedCars.any { it.substringBefore('|').equals(m, ignoreCase = true) }
    }

    /** 记录的配对时间（毫秒；没有记录返回 null）。 */
    fun pairedCarAt(mac: String): Long? {
        val m = mac.trim()
        val hit = pairedCars.firstOrNull { it.substringBefore('|').equals(m, ignoreCase = true) } ?: return null
        return hit.substringAfterLast('|').toLongOrNull()
    }

    fun receiverPairingIdentityBackup(key: String): String =
        p().getString("${KEY_PAIRING_IDENTITY}_$key", "").orEmpty()

    fun saveReceiverPairingIdentityBackup(key: String, value: String) {
        p().edit().putString("${KEY_PAIRING_IDENTITY}_$key", value).apply()
    }

    fun forgetAllCarPairings() {
        val editor = p().edit().remove(KEY_PAIRING_IDENTITY).remove(KEY_PAIRED_CARS)
            .remove(KEY_LAST_AIRPLAY_ENDPOINT).remove("known_car_networks")
        p().all.keys.filter { it.startsWith("${KEY_PAIRING_IDENTITY}_bt_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Preserve the shared phone identity and all other cars' records. Target selection is
     * cleared separately by the session owner after file cleanup succeeds. */
    fun forgetCarPairing(mac: String) {
        val key = requireNotNull(io.github.hypercar.session.PairingEvidence.receiverKey(mac))
        p().edit()
            .putStringSet(KEY_PAIRED_CARS, io.github.hypercar.session.PairingResetRecords.pairedCars(pairedCars, mac))
            .putStringSet("known_car_networks", io.github.hypercar.session.PairingResetRecords.networks(knownCarNetworks, mac))
            .remove("${KEY_PAIRING_IDENTITY}_$key")
            .apply()
    }
    /** 屏幕推送分辨率**兜底值**（车机 `/info` 没回报尺寸时才用）。 */
    var videoWidth: Int
        get() = p().getInt(KEY_WIDTH, DEFAULT_VIDEO_WIDTH)
        set(v) = p().edit().putInt(KEY_WIDTH, v.coerceAtLeast(2)).apply()

    var videoHeight: Int
        get() = p().getInt(KEY_HEIGHT, DEFAULT_VIDEO_HEIGHT)
        set(v) = p().edit().putInt(KEY_HEIGHT, v.coerceAtLeast(2)).apply()

    var videoCodec: String
        get() = VideoCodecPolicy.storedPreference(p().getString("video_codec", null))
        set(v) = p().edit().putString("video_codec", if (v == "hevc") "hevc" else "h264").apply()

    val videoCapabilities = VideoSessionCapabilities()
    val preferredVideoCodec: VideoCodec
        get() = VideoCodecPolicy.preferred(videoCodec, videoCapabilities.hevcAllowed)

    @Volatile var receiverMaxFps: Int? = null
    @Volatile var receiverViewport: Pair<Int, Int>? = null

    /** CarWith prewarm uses only this receiver's observed canvas, never custom UI size. */
    fun carWithCanvas(receiver: String?): Pair<Int, Int>? {
        if (receiver.isNullOrBlank()) return null
        val width = p().getInt("carwith_canvas_width_$receiver", 0)
        val height = p().getInt("carwith_canvas_height_$receiver", 0)
        return (width to height).takeIf { width in 2..8192 && height in 2..8192 }
    }

    fun rememberCarWithCanvas(receiver: String?, width: Int, height: Int) {
        if (receiver.isNullOrBlank() || width !in 2..8192 || height !in 2..8192) return
        p().edit().putInt("carwith_canvas_width_$receiver", width)
            .putInt("carwith_canvas_height_$receiver", height).apply()
    }

    val encodingPlan: VideoEncodingPlan
        get() {
            // 有 /info 时用它；没有 /info 才回退设置。编码画布与引擎必须同源，
            // 但最终比例要跟车机实际 viewport 一致，否则接收端会横向压缩。
            val viewport = receiverViewport ?: (videoWidth to videoHeight)
            return VideoEncodingPlan.fit(viewport.first, viewport.second, videoFps).withReceiverMaxFps(receiverMaxFps)
        }

    var videoFps: Int
        get() = p().getInt(KEY_FPS, 60)
        set(v) = p().edit().putInt(KEY_FPS, v.coerceIn(20, 60)).apply()

    /** 车机画面来源（2026-11-03）：
     *  · "car_ui"   —— 本 App 自绘车机首页（当前默认，地图卡 + 音乐卡 + Dock）
     *  · "carwith"  —— 走小米 CarWith：拉起它的 CarLife 入口，由 CarWith 自己建显示/编码，
     *                 我们只做 CarLife 通道 → AirPlay 的转发（需装 CarWith + 小米版 CarLife）
     *  · "system_carlife" —— 手机 CarLife+（三星/荣耀等），用户开启无线连接后接入本地引擎
     */
    var carSource: String
        get() = p().getString("car_source", "car_ui") ?: "car_ui"
        set(v) = p().edit().putString("car_source", v).apply()

    /** Existing explicit choices survive upgrades; fresh installs must choose a source. */
    val carSourceSelected: Boolean get() = p().contains("car_source")

    /** 是否 CarWith 模式。 */
    val carWithMode: Boolean get() = carSource == "carwith"
    val systemCarLifeMode: Boolean get() = carSource == "system_carlife"
    val carLifeMode: Boolean get() = io.github.hypercar.session.CarLifeSourcePolicy.isGateway(carSource)

    // The retired self-rendered speech experiment also gates incoming native callbacks.
    val carVoiceRecognition: Boolean get() = false

    var audioCodec: String
        get() = p().getString("audio_codec", "auto")?.takeIf { it in setOf("auto", "pcm", "aac") } ?: "auto"
        set(v) { p().edit().putString("audio_codec", v.takeIf { it in setOf("auto", "pcm", "aac") } ?: "auto").apply() }

    /** 车机 /info 声明 keepAliveSendStatsAsBody 时置 true（屏幕 keepAlive 带统计体）。 */
    var keepAliveStats: Boolean
        get() = p().getBoolean(KEY_KEEP_ALIVE_STATS, false)
        set(v) = p().edit().putBoolean(KEY_KEEP_ALIVE_STATS, v).apply()

    /** 调试日志记录开关（诊断页切换；关=暂停写入环形缓冲）。 */
    var debugLogEnabled: Boolean
        get() = p().getBoolean(KEY_DEBUG_LOG, false)
        set(v) = p().edit().putBoolean(KEY_DEBUG_LOG, v).apply()

    /**
     * 无 Shizuku 时用的稳定 deviceID（首次生成后持久化，格式仿 MAC）。
     *
     * Android 11+ 把本机真实蓝牙 MAC 锁死（无 root/Shizuku 拿不到）。AirPlay 会话级
     * SETUP 的 deviceID、iAP2 0x4300/0x4E0C/0x4E0E 的传输标识都要它。
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

    // Use the existing preference so addresses entered in older versions retain priority.
    internal val savedPhoneMac = SavedPhoneMac(
        read = { manualBtMac },
        write = { value ->
            p().edit().putString(KEY_MANUAL_BT_MAC, value).apply()
            SettingsRevision.bump()
        },
    )

    var manualBtMac: String?
        get() = io.github.hypercar.net.PhoneIdentitySnapshot.normalize(p().getString(KEY_MANUAL_BT_MAC, null))
        set(v) {
            savedPhoneMac.edit(v)
        }

    /** 手动 MAC 是否已设置（UI 用来显示"手动/自动"）。 */
    val hasManualBtMac: Boolean
        get() = manualBtMac != null

    /**
     * 无 Shizuku 降级模式下，是否已完成过一次「前台首连」（系统确认框已批准）。
     */
    var degradedFirstConnectDone: Boolean
        get() = p().getBoolean(KEY_DEGRADED_FIRST_CONNECT, false)
        set(v) = p().edit().putBoolean(KEY_DEGRADED_FIRST_CONNECT, v).apply()

    /**
     * 引擎配置 JSON（Rust `EngineConfig::from_json` 消费）。
     *
     * 现在只有 CarPlay 一条链路：屏幕内容由本机自绘界面自采自编，通过
     * `nativePushVideoFrame` 推进引擎；音频同样由本机采集后推入。
     */
    fun engineConfigJson(
        carIp: String,
        airplayPort: Int,
        deviceBluetoothMac: String,
        identityPath: String,
        width: Int = videoWidth,
        height: Int = videoHeight,
        receiverBluetoothMac: String? = null,
        carWithSource: Boolean = carLifeMode,
        receiverModel: String? = null,
        headUnitProfile: io.github.hypercar.headunit.HeadUnitProfile,
        systemCarLifeSource: Boolean = systemCarLifeMode,
    ): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        // 触摸兜底空间：车机档案若给了 touchDisplay 就用它；否则用设置里的
        // 屏幕推送分辨率（与视频编码一致）。真车 /info 有尺寸时仍优先 /info。
        val profile = headUnitProfile.connectionPolicy()
        val selectedProfileId = profile.id
        val profileTouch = profile.touchDisplay
        val fallbackW = width.coerceAtLeast(2) / 2 * 2
        val fallbackH = height.coerceAtLeast(2) / 2 * 2
        val (tdW, tdH) = profileTouch
            ?.split('x', 'X')
            ?.takeIf { it.size == 2 }
            ?.let { (it[0].toIntOrNull() ?: 0) to (it[1].toIntOrNull() ?: 0) }
            ?.takeIf { it.first > 0 && it.second > 0 }
            ?: (fallbackW to fallbackH)
        val plan = VideoEncodingPlan.fit(
            (receiverViewport?.first ?: width),
            (receiverViewport?.second ?: height),
            videoFps,
        )
        return buildString {
            append("{")
            append("\"device_id\":\"").append(esc(deviceBluetoothMac)).append("\",")
            append("\"device_name\":").append(org.json.JSONObject.quote(io.github.hypercar.bluetooth.LocalDeviceName.snapshot())).append(",")
            append("\"car_ip\":\"").append(esc(carIp)).append("\",")
            append("\"airplay_port\":").append(airplayPort).append(",")
            append("\"head_unit_profile\":").append(org.json.JSONObject.quote(profile.protocolProfileId)).append(",")
            if (profile.mbuxMediaCompat && !receiverModel.isNullOrBlank()) {
                append("\"receiver_model\":").append(org.json.JSONObject.quote(receiverModel)).append(",")
            }
            // CarWith 模式：视频/音频源改由本机 CarLife（CarWith）提供（2026-11-03）
            append("\"carlife_source\":").append(if (carWithSource) "true" else "false").append(",")
            append("\"system_carlife_source\":").append(carWithSource && systemCarLifeSource).append(",")
            if (carWithSource && systemCarLifeSource) {
                io.github.hypercar.session.CarLifeSourcePolicy.huIdentity(receiverBluetoothMac,targetCarMac,targetCarName,pairedCars)?.let { hu ->
                    append("\"hu_bt_address\":").append(org.json.JSONObject.quote(hu.address)).append(",")
                    append("\"hu_bt_name\":").append(org.json.JSONObject.quote(hu.name)).append(",")
                    append("\"hu_manufacturer\":\"HyperCar\",\"hu_model\":\"HyperCar HU\",")
                }
            }
            append("\"mbux_screen_bootstrap\":").append(profile.mbuxScreenBootstrap).append(",")
            append("\"id9_screen_bootstrap\":").append(profile.id9ScreenBootstrap).append(",")
            append("\"nbt_audio_first\":").append(profile.nbtAudioFirst).append(",")
            append("\"mbux_media_compat\":").append(profile.mbuxMediaCompat).append(",")
            append("\"screen_resource_lifecycle\":").append(if (profile.screenResourceLifecycleEnabled(screenResourceLifecycle)) "true" else "false").append(",")
            append("\"width\":").append(plan.width).append(",")
            append("\"height\":").append(plan.height).append(",")
            append("\"touch_display_w\":").append(tdW).append(",")
            append("\"touch_display_h\":").append(tdH).append(",")
            append("\"fps\":").append(plan.fps).append(",")
            append("\"enable_audio\":").append(profile.airplayAudioEnabled).append(",")
            // Public CarLife+ keeps its phone microphone, as in the reference.
            // The existing Xiaomi car-microphone bridge remains unchanged.
            append("\"carwith_microphone\":").append(profile.airplayAudioEnabled && carWithSource && !systemCarLifeSource).append(",")
            append("\"video_codec\":\"").append(VideoCodecPolicy.forHardware(videoCodec, VideoHardwareCapabilities.hevcEncoderAvailable)).append("\",")
            append("\"bmw_capabilities\":").append(profile.protocolProfileId.startsWith("bmw_")).append(",")
            append("\"audio_codec\":\"").append(audioCodec).append("\",")
            append("\"self_microphone_asr\":").append(profile.airplayAudioEnabled && carVoiceRecognition && !carWithSource).append(",")
            append("\"keep_alive_stats\":").append(keepAliveStats).append(",")
            append("\"request_receiver_stats\":").append(debugLogEnabled).append(",")
            append("\"bmw_box_bootstrap\":").append(io.github.hypercar.headunit.ReceiverCompatibility.useBmwBootstrap(
                selectedProfileId, io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(receiverBluetoothMac))).append(",")
            append("\"skip_bluetooth_handoff\":").append(profile.skipBluetoothHandoff).append(",")
            append("\"desktop_receiver\":").append(io.github.hypercar.headunit.ReceiverCompatibility.isDesktopReceiver(
                selectedProfileId, io.github.hypercar.iap2.Iap2Peer.softwareIdentityFor(receiverBluetoothMac))).append(",")
            append("\"identity_path\":\"").append(esc(identityPath)).append("\"")
            io.github.hypercar.session.PairingEvidence.receiverKey(receiverBluetoothMac)?.let {
                append(",\"receiver_id\":\"").append(it).append("\"")
                append(",\"receiver_previously_connected\":").append(isCarPaired(receiverBluetoothMac.orEmpty()))
            }
            append("}")
        }
    }
}
