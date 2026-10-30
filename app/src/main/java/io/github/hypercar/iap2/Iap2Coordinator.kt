package io.github.hypercar.iap2

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * 蓝牙 iAP2 协商产出的「车机端点」——交给上层去连 AirPlay 的完整信息。
 *
 * @ssid 车机热点 SSID（0x5703 或 0x4301 无线组；null = 未拿到）
 * @passphrase 车机热点密码（同上；可能为空串——开放热点）
 * @airPlayPort AirPlay 控制端口（0x4301；0 = 未拿到，上层可用 mDNS 兜底）
 * @deviceIdentifier 车机设备标识（0x4301）
 * @publicKeyHex 车机公钥（0x4301，hex）
 * @sourceVersion 车机 AirPlay SDK 版本（0x4301，如 "950.7.1"）
 * @ipAddresses 车机在热点网内的地址列表（0x4301 无线组；**已 rstrip 全部尾部 NUL**）
 * @gateway 网关（iAP2 消息不携带；上层入网后从 DHCP/链路信息补）
 * @fromInvite 端点来自车机的 `GET /ctrl-int/1/connect` 邀请（取 TCP 对端地址 + 7000），
 *   不是 0x4301 —— 地址/端口是车机亲口说的，上层不必再花 3s 查 mDNS
 */
data class CarPlayEndpoint(
    val ssid: String?,
    val passphrase: String?,
    val airPlayPort: Int,
    val deviceIdentifier: String?,
    val publicKeyHex: String?,
    val sourceVersion: String?,
    val ipAddresses: List<String>,
    val gateway: String?,
    val fromInvite: Boolean = false,
)

/**
 * iAP2 协商层对外门面（Orchestrator 的唯一入口）。
 *
 * 内部持有 [Iap2Bluetooth]（RFCOMM 出站 + 入站，谁先通谁跑会话），
 * 会话线程跑 [Iap2SenderSession] 完成识别/MFi/问候/EA/0x5703/0x4301 全序列，
 * 产出 [CarPlayEndpoint] 回调给上层（0x5703 凭据与 0x4301 端点各一次，
 * 保活期车机重发也会再次回调——上层自行忽略冗余）。
 *
 * 线程模型：所有蓝牙/协议工作都在 Iap2Bluetooth 的命名后台线程上
 * （accept/connect/session/link-reader/link-pacer），本类只做转发。
 * [deviceMacProvider] 是 suspend（Shizuku 解析真实蓝牙 MAC），在会话线程里
 * 经 runBlocking 桥接——绝不放进无主 Looper 上下文（那样的上下文里
 * `BluetoothManager.getAdapter()` 返回 null），因此这里固定用 applicationContext。
 */
class Iap2Coordinator(
    context: Context,
    /** 用户选定车机 MAC；null = 自动（连任意声明 iAP2 服务的已配对设备）。 */
    private val targetMac: () -> String?,
    private val preferredMac: () -> String? = { null },
    /** 本机真实蓝牙 MAC（Shizuku 解析，由上层注入；0x4300/0x4E0C/0x4E0E 用）。 */
    private val deviceMacProvider: suspend () -> String,
    /** 车机档案：发 iAP2 前是否主动断开该车机的 HFP/A2DP（宝马=false，奥迪=true）。 */
    private val dropProfilesBeforeIap2: Boolean = true,
    private val allowOutboundIap2: Boolean = true,
    private val log: (String) -> Unit,
) {
    companion object {
        /** 未拿到真实 MAC 时的兜底占位（仅保底可用性；真车可能校验传输标识）。 */
        private const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
    }

    private val appContext = context.applicationContext

    @Volatile
    private var bluetooth: Iap2Bluetooth? = null

    @Volatile
    private var endpointCallback: ((CarPlayEndpoint) -> Unit)? = null

    @Volatile
    private var resolvedMac: String? = null

    val isRunning: Boolean get() = bluetooth?.isRunning == true

    /** 本次启动以来成功建过几轮 iAP2 会话（0 = 车机一次都没连进来）。 */
    val sessionStartCount: Int get() = bluetooth?.sessionStartCount ?: 0

    fun onTargetChanged() = bluetooth?.onTargetChanged()

    /**
     * 开始监听/出站；拿到或更新端点即回调（可能多次：0x5703 凭据一次、
     * 0x4301 端点一次、保活期重发再回调）。
     *
     * 可重复调用：已在运行时只更新回调，不重建蓝牙栈。
     */
    fun start(onEndpoint: (CarPlayEndpoint) -> Unit) {
        bluetooth?.setWifiHandoff(false)
        endpointCallback = onEndpoint
        synchronized(this) {
            bluetooth?.let { existing ->
                if (existing.isRunning) {
                    log("IAP2: 已在运行，仅更新端点回调")
                    return
                }
            }
            // 预解析真实 MAC：早失败早暴露（Shizuku 未就绪时占位值可能被真车拒绝）。
            // 一次性后台任务，不是长循环；解析结果由会话线程按需再取/重取。
            Thread({ prefetchDeviceMac() }, "iap2-mac-prefetch").apply {
                isDaemon = true
                start()
            }
            val bt =
                Iap2Bluetooth(
                    context = appContext,
                    targetProvider = targetMac,
                    preferredProvider = preferredMac,
                    bluetoothMacProvider = ::currentBluetoothMac,
                    dropProfilesBeforeIap2 = dropProfilesBeforeIap2,
                    allowOutboundIap2 = allowOutboundIap2,
                    log = log,
                    onEndpoint = { endpoint -> endpointCallback?.invoke(endpoint) },
                    onSessionEnded = { reason -> log("IAP2: 会话结束（$reason），继续监听/出站等待重连") },
                )
            bluetooth = bt
            if (!bt.start()) {
                log("IAP2: 蓝牙启动失败（权限/开关），请检查后重试")
                bluetooth = null
            }
        }
    }

    /** 终态：关 RFCOMM 与监听（只在用户断开时调用）。 */
    fun stop() {
        synchronized(this) {
            bluetooth?.stop()
            bluetooth = null
        }
        endpointCallback = null
    }

    /**
     * ACL 断开：结束当前会话但保留监听。
     * 车机蓝牙断开后旧 RFCOMM socket 的读会卡在半开连接上不返回——必须主动关，
     * 否则会话线程滞留、下一轮「秒连」被旧会话占位。
     */
    fun retireForWifi() { bluetooth?.setWifiHandoff(true) }

    fun endCurrentSession() { bluetooth?.dropCurrentSession() }

    fun notifyBluetoothLost() {
        log("IAP2: 蓝牙 ACL 断开——结束当前 iAP2 会话（监听保留）")
        bluetooth?.dropCurrentSession()
    }

    // ---- 内部 ----

    /**
     * 当前会话用的本机 MAC：优先真实值（缓存），未解析到时现场 runBlocking 取一次
     * （会话线程是后台线程，阻塞安全）。
     *
     * deviceMacProvider 已是「真 MAC 或稳定伪 MAC」（BluetoothMac.resolveOrStable），
     * 返回的一定是合法 MAC 格式——无 Shizuku 时的稳定伪 MAC 也是有效值，直接采用。
     */
    private fun currentBluetoothMac(): String {
        resolvedMac?.let { return it }
        val mac =
            runCatching { runBlocking { deviceMacProvider() } }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (mac != null) {
            resolvedMac = mac
            if (mac == io.github.hypercar.settings.SettingsStore.stableDeviceId) {
                log(
                    "IAP2: ⚠ 本轮用的是**稳定伪 MAC** $mac（真 MAC 未解析到）——车机若按设备身份认 CarPlay，" +
                        "伪 ID 会被当成另一台设备；请确认 Shizuku 已授权或到设置里手动填真实蓝牙 MAC",
                )
            } else {
                log("IAP2: 本机蓝牙标识=$mac")
            }
            return mac
        }
        log("IAP2: ⚠ deviceMacProvider 未返回值，本轮用稳定伪 MAC 兜底")
        return PLACEHOLDER_MAC
    }

    /**
     * 预解析真实 MAC。
     *
     * **必须 force**：`BluetoothMac.resolve()` 解析失败后有 10s 静默窗，而上下层在
     * 差不多同一毫秒都会发起解析——非 force 调用会正好落在「另一次解析正在进行」的窗口里
     * 返回 null → 退回稳定伪 MAC（2026-10-25 现场日志：`BT: 使用真实蓝牙 MAC 84:AE:…`
     * 但 `IAP2: 本机蓝牙标识预解析成功=B6:65:…`，同一轮出现两个身份）。
     */
    private fun prefetchDeviceMac() {
        if (resolvedMac != null) return
        val mac =
            runCatching { runBlocking { io.github.hypercar.net.BluetoothMac.resolveOrStable(force = true) } }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return
        resolvedMac = mac
        if (mac == io.github.hypercar.settings.SettingsStore.stableDeviceId) {
            log("IAP2: ⚠ 预解析到的是**稳定伪 MAC** $mac（真 MAC 不可用）——车机可能不认这台设备")
        } else {
            log("IAP2: 本机蓝牙标识预解析成功=$mac")
        }
    }
}
