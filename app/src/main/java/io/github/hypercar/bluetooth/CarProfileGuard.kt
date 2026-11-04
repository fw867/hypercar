package io.github.hypercar.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.hypercar.settings.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * **不让车机蓝牙挂上 HFP/A2DP**（无线 CarPlay 的"只配对、不连接"）。
 *
 * 档案策略的现场线索（2026-11-01，不能仅据此确定因果）：
 * ```
 * 11:05:10.840 HEADSET DISCONNECTED→CONNECTING   ← 手机自己回连 HFP
 * 11:05:10.959 A2DP    DISCONNECTED→CONNECTING   ← 手机自己回连 A2DP
 * 11:05:12.478 CLASSICAL-CONNECTED  (ACL 起来)
 * 11:05:13.038 CLASSICAL-DISCONNECTED status=Remote Terminated   ← 车机 0.56s 就拆了
 * 11:05:17.237 同样一轮，0.61s 拆
 * ```
 * 两次 iAP2 **从未被打开**；最终成功那次（11:07:16）没有 HFP 先行。真 iPhone 的
 * 无线 CarPlay 也不依赖 HFP/A2DP：控制面走 iAP2 RFCOMM，声音走 WiFi 上的 AirPlay。
 *
 * 之前 `dropProfilesBeforeIap2` 只在**出站循环**（`connectLoop`）里生效，而宝马档案
 * `allowOutboundIap2=false` → 出站循环根本不启动 → 那个开关对宝马**形同虚设**。
 * 本对象补上被动模式下的执行：监听 HFP/A2DP 的连接事件，发现是车机就连上断开。
 */
object CarProfileGuard {
    private const val TAG = "CAR-PROFILE-GUARD"

    /** 节流窗口见 [CarProfilePolicy.MIN_INTERVAL_MILLIS]。 */
    private val lastEnforceAt = ConcurrentHashMap<String, Long>()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "car-profile-guard").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()

    @Volatile
    private var carAddress: String? = null

    /** 档案允许，且自动待机或手动/自动会话进行时才拦截。 */
    @Volatile
    var enabled: Boolean = false
        private set

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private var logSink: ((String) -> Unit)? = null

    /** 记住本轮车机地址（ACL 广播/目标设置里来）。 */
    fun noteCar(address: String?) {
        val next = address?.trim()?.takeIf { it.isNotEmpty() }
        if (!next.equals(carAddress, ignoreCase = true)) {
            generation.incrementAndGet()
            carAddress = next
            lastEnforceAt.clear()
        }
    }

    fun knownCar(): String? = SettingsStore.targetCarMac ?: carAddress

    /** 开始监听车机的 HFP/A2DP 连接事件（幂等）。 */
    @Synchronized fun start(context: Context, log: (String) -> Unit) {
        logSink = log
        if (receiver != null) return
        val app = context.applicationContext
        appContext = app
        fun log(msg: String) = logSink?.invoke(msg)
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                @Suppress("DEPRECATION")
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device == null) return
                val address = runCatching { device.address }.getOrNull() ?: return
                if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                    val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
                    val type = try { device.type } catch (_: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
                    if (CarAclPolicy.isClassicCarLink(transport, type)) onCarConnected(app, device, address)
                    return
                }
                if (action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED &&
                    action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
                enforce(
                    context = app,
                    device = device,
                    address = address,
                    reason = if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) "HFP 已连接" else "A2DP 已连接",
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        // Bluetooth 来自独立高权限 UID；NOT_EXPORTED 会漏收该进程的广播。
        // 仅订阅系统保护的蓝牙 action，并继续校验经典传输和目标地址。
        try {
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (t: Exception) {
            log("BT-GUARD: 注册失败 ${t.message}")
            return
        }
        enabled = true
        receiver = r
        log("BT-GUARD: 已开始拦截车机的 HFP/A2DP（${knownCar() ?: "车机未知，等 ACL 锁定"}）")
    }

    @Synchronized fun stop() {
        enabled = false
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        lastEnforceAt.clear()
        receiver?.let { r -> appContext?.let { ctx -> runCatching { ctx.unregisterReceiver(r) } } }
        val wasRunning = receiver != null
        receiver = null
        if (wasRunning) logSink?.invoke("BT-GUARD: 已停止")
        appContext = null
    }

    /** 独立于自动唤醒入口：手动连接关闭 autoConnect 时也接收 ACL。 */
    fun onCarConnected(context: Context, device: BluetoothDevice, address: String) {
        if (!enabled || !address.equals(knownCar(), ignoreCase = true)) return
        val epoch = generation.get()
        enforce(context, device, address, "ACL 连上（立即）")
        mainHandler.postDelayed({
            if (epoch == generation.get()) enforce(context, device, address, "ACL 连上 +4s 复查")
        }, 4_000)
    }

    /**
     * 立刻断一次该车机的 HFP/A2DP（后台线程；内部有 [CarProfilePolicy.MIN_INTERVAL_MILLIS] 节流）。
     * @return true = 本次已排队；停止/目标切换后排队任务会放弃
     */
    @Synchronized fun enforce(
        context: Context,
        device: BluetoothDevice,
        address: String,
        reason: String,
    ): Boolean {
        val target = knownCar()
        val now = SystemClock.elapsedRealtime()
        val last = lastEnforceAt[address.uppercase()] ?: 0L
        if (!CarProfilePolicy.shouldEnforce(target, address, now, last)) {
            // 目标不匹配 / 节流中：静默（节流命中时最多记一行，避免刷屏）
            return false
        }
        if (!enabled) return false
        val epoch = generation.get()
        lastEnforceAt[address.uppercase()] = now
        worker.execute {
            if (!enabled || epoch != generation.get() || !address.equals(knownCar(), ignoreCase = true)) return@execute
            val before = BtProfiles.connectedNames(context, address)
            // 获取 profile 代理可能等待系统回调；期间停止/换车也要取消。
            if (!enabled || epoch != generation.get() || !address.equals(knownCar(), ignoreCase = true)) return@execute
            // disconnect 只在确有音频 profile 时标记 self-drop，空操作不吞掉真正的下车事件。
            val dropped = BtProfiles.disconnect(context, device)
            val left = BtProfiles.stillConnected(context, address)
            logSink?.invoke(
                "BT-GUARD: $reason → 断开 ${dropped.ifEmpty { before }.joinToString("/").ifEmpty { "无" }}" +
                    if (left.isEmpty()) "（已全部断开）" else "（仍有 ${left.joinToString("/")} 在线）",
            )
        }
        return true
    }

    /** 主线程投递用（ACL 回调里已经不在主线程时也安全）。 */
    fun enforceAsync(context: Context, device: BluetoothDevice, address: String, reason: String) {
        val epoch = generation.get()
        mainHandler.post {
            if (epoch == generation.get()) enforce(context, device, address, reason)
        }
    }
}
