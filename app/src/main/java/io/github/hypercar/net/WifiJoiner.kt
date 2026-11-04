package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import android.util.Log
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.shizuku.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 静默加入车机热点并把进程网络绑上去（cardeck `docs/WIFI-JOIN.md` 的坑全部内化）。
 *
 * **按 Shizuku 可用性自动分流**（Shizuku 从「必须」降级为「可选增强」）：
 *  - **有 Shizuku**：`cmd wifi connect-network`——**0 弹窗**地让系统连上热点（进系统已保存网络）。
 *  - **无 Shizuku**：公开 API `WifiNetworkSpecifier`——**每个热点首次连接弹一次系统确认框**
 *    （批准结果按 SSID 持久化，之后同样静默）。app 专属网络不落系统已保存网络，
 *    反而绕开「热点已被保存反而连不上」的地雷（cardeck §13b 大众探歌实测）。
 *
 * 两条路径最终都走 [bindProcess]：
 *  **必须绑 `bindProcessToNetwork` 的原因**：mDNS `NsdManager.registerService` 没有 Network 参数
 *  （API 36 才有 `AdvertisingRequest` 重载），广播跟随进程默认网络；不绑就从蜂窝发出去，车机
 *  永远发现不了手机（0x4301/邀请不到）。它只影响本进程，虚拟屏里的导航/音乐是独立进程不受影响。
 *
 * 三个内化的坑（Shizuku 路径）：
 *  - `connect-network` 的 **exit=0 不代表已连上**（命令只把连接投给 WifiService 就返回）→
 *    必须轮询 `cmd wifi status` 等 SSID 匹配（断旧网→扫描→关联→DHCP 实测 2~10s，5GHz 更久）。
 *  - SSID 校验用 `cmd wifi status` 而**不是** `WifiInfo`（后者在缺定位权限时返回 `<unknown ssid>`）。
 *  - `onLost` 必须处理（车机熄火热点消失），否则会话层停在「已连接」的假态。
 *
 * 无 Shizuku 路径（specifier）额外需要运行时权限：Android 12/12L 要 ACCESS_FINE_LOCATION
 * 且定位服务开启，13+ 要 NEARBY_WIFI_DEVICES——requestNetwork 会因缺权限直接 onUnavailable。
 */
class WifiJoiner(
    private val context: Context,
    onLost: () -> Unit,
) {
    /** 构造参数转存：避免与 NetworkCallback.onLost 回调方法名冲突。 */
    private val lostCallback: () -> Unit = onLost

    /** 入网结果。 */
    sealed interface JoinResult {
        /** 已连上并拿到 [network] 句柄（尚未绑进程，调用 [bindProcess] 后 socket/mDNS 才走它）。 */
        data class Joined(val network: Network) : JoinResult

        /** 失败（connect-network 被拒 / 轮询超时 / requestNetwork 未回调）。 */
        data object Failed : JoinResult

        /** 该安全类型无法静默表达（WEP）——调用方走兜底路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** 当前持有的网络回调（join 时注册，供 onLost 使用）。 */
    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    /** 当前已绑定的网络（bindProcess 记录，供快路径与 onLost 解绑）。 */
    private val boundNetwork = AtomicReference<Network?>(null)

    /** 串行化 bind/release（网络回调线程与调用方线程可能并发）。 */
    private val bindLock = Any()

    /** 当前绑定网络；未绑定返回 null。 */
    val currentNetwork: Network? get() = boundNetwork.get()

    /**
     * 最近一次静默入网失败的原因（人话，供会话层写进导出日志）。
     *
     * 为什么要这个：specifier 路径的失败在界面上**完全没有痕迹**——系统连弹窗都不弹，
     * 只有 `onUnavailable` 回调（或 requestNetwork 直接抛异常）。没有这行原因，
     * 现场日志只有「静默入网失败，尝试直接绑定当前网络」，看不出到底是
     * 前台/权限/Wi-Fi 开关/热点不在范围内里的哪一种。
     */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /** Wi-Fi 开关是否打开：specifier 请求在 Wi-Fi 关闭时会立刻 onUnavailable（无弹窗）。 */
    fun isWifiEnabled(): Boolean =
        runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.isWifiEnabled == true
        }.getOrDefault(false)

    /**
     * 加入车机热点并返回可用的 Network 句柄（尚未绑进程，调用 [bindProcess] 后才生效）。
     *
     * **按 Shizuku 可用性自动分流**：有 Shizuku 走 [joinViaShell]（0 弹窗）；
     * 无 Shizuku 走 [joinViaSpecifier]（公开 API，热点首连弹一次系统确认框，批准按 SSID 持久化）。
     *
     * @param ssid 热点名（可能含空格/中文）。
     * @param psk  热点密码；空=开放热点；WEP 形态返回 [JoinResult.Unsupported]。
     */
    suspend fun joinCarHotspotSilently(ssid: String, psk: String): JoinResult {
        lastFailureReason = null
        // Password length alone cannot distinguish WEP from valid 10/13/26-character WPA2.
        if (isUnsupportedPassphrase(psk)) {
            lastFailureReason = "热点密码不足 8 位，无法按 WPA2 入网"
            return JoinResult.Unsupported
        }
        // 清掉旧回调/旧绑定，干净起步
        release()
        return if (ShizukuTools.isReady) {
            Log.i(TAG, "Shizuku 可用：走 cmd wifi 静默路径（0 弹窗）")
            joinViaShell(ssid, psk)
        } else {
            Log.i(TAG, "Shizuku 不可用：降级为 WifiNetworkSpecifier（热点首连需确认一次）")
            joinViaSpecifier(ssid, psk)
        }
    }

    /** Shizuku 路径：`cmd wifi connect-network` 静默入网（0 弹窗，热点进系统已保存网络）。 */
    private suspend fun joinViaShell(ssid: String, psk: String): JoinResult =
        withContext(Dispatchers.IO) {
            // ① 确保 WiFi 开关打开
            ShizukuTools.exec("cmd wifi set-wifi-enabled enabled")

            // ② connect-network：exit=0 仅表示「已投递给 WifiService」，不代表已连上
            val connectCmd =
                if (psk.isBlank()) {
                    "cmd wifi connect-network ${shellQuote(ssid)} open"
                } else {
                    "cmd wifi connect-network ${shellQuote(ssid)} wpa2 ${shellQuote(psk)}"
                }
            ShizukuTools.exec(connectCmd)

            // ③ 轮询 cmd wifi status 直到 SSID 匹配（500ms × 15s，可取消）
            if (!awaitSsid(ssid, JOIN_WAIT_MS)) {
                lastFailureReason = "cmd wifi 在 ${JOIN_WAIT_MS / 1000}s 内未连上 \"$ssid\"（密码错/热点不在范围/热点未开）"
                Log.i(TAG, "connect-network 后 ${JOIN_WAIT_MS}ms 内未连上 \"$ssid\"")
                return@withContext JoinResult.Failed
            }

            // ④ 不带 specifier 的 requestNetwork 取 Network 句柄（不会弹任何确认框）
            val network = awaitNetworkHandle(HANDLE_WAIT_MS)
            if (network == null) {
                lastFailureReason = "已连上 \"$ssid\" 但拿不到 Network 句柄"
                return@withContext JoinResult.Failed
            }

            JoinResult.Joined(network)
        }

    /**
     * 无 Shizuku 兜底：公开 API `WifiNetworkSpecifier` 申请一个 **app 专属** WiFi 网络。
     *
     * 与系统「保存并连接」不同，这条路是进程隔离的点对点连接——正合我们 bindProcess 的用法，
     * 且不落系统已保存网络（避开 cardeck §13b「热点已保存反而连不上」的地雷）。
     *
     * 代价：**每个热点首次连接弹一次系统确认框**（批准结果按 SSID+安全类型持久化，之后静默）。
     * 确认框由 SystemUI 呈现；**后台 FGS 拉起的首连不保证能弹出**——所以无 Shizuku 时的推荐
     * 用法是：装好后先手动打开一次 App 完成首连确认，之后「上车自动连接」才会全程静默。
     * 拿不到句柄（框弹不出/用户拒绝/超时）一律返回 Failed，由调用方降级到「绑定当前网络」。
     */
    private suspend fun joinViaSpecifier(ssid: String, psk: String): JoinResult =
        withContext(Dispatchers.IO) {
            val specifier = WifiNetworkSpecifier.Builder().apply {
                setSsid(ssid)
                // 开放网络：Builder 没有 setOpenNetwork()，不设任何密码方法即为开放；
                // WPA2 只有 setWpa2Passphrase 一条路径。
                if (psk.isNotBlank()) setWpa2Passphrase(psk)
            }.build()

            // app 专属网络：只能加 TRANSPORT_WIFI + specifier，
            // 不能声明 NET_CAPABILITY_INTERNET（车机热点本就无外网，且 specifier 网络不允许）。
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val network = awaitSpecifierHandle(request, SPECIFIER_WAIT_MS)
                ?: return@withContext JoinResult.Failed
            JoinResult.Joined(network)
        }

    /** 用 specifier 请求拿 Network 句柄；首连时系统确认框的等待时间也算在 [timeoutMs] 内。 */
    private suspend fun awaitSpecifierHandle(request: NetworkRequest, timeoutMs: Long): Network? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        // 立刻 onUnavailable 与「等到超时」是完全不同的病因，必须分开记
        val unavailable = java.util.concurrent.atomic.AtomicBoolean(false)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback.get() !== this) return
                result.set(network)
                latch.countDown()
            }

            override fun onUnavailable() {
                if (callback.get() !== this) return
                unavailable.set(true)
                latch.countDown()
            }

            override fun onLost(network: Network) = handleNetworkLost(this, network)
        }
        callback.set(cb)
        try {
            // 带超时的 requestNetwork：系统确认框期间不超时，用户长期不点才失败
            // （该重载的 timeout 参数是 Int，需从 Long 转换）
            connectivityManager.requestNetwork(request, cb, timeoutMs.toInt())
        } catch (t: Throwable) {
            callback.compareAndSet(cb, null)
            lastFailureReason = "requestNetwork 抛异常：${t.javaClass.simpleName}: ${t.message}"
            Log.i(TAG, "specifier requestNetwork 失败：${t.message}")
            return null
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (callback.compareAndSet(cb, null)) {
                runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            }
            throw cancelled
        }
        val network = result.get()
        if (network == null) {
            // 未拿到句柄（含用户拒绝/超时）：注销回调，别泄漏
            release()
            lastFailureReason =
                when {
                    unavailable.get() && !isWifiEnabled() ->
                        "系统立刻拒绝：Wi-Fi 开关是关的（没有弹窗是正常的）"
                    unavailable.get() ->
                        "系统立刻拒绝（onUnavailable）：通常因为 App 当时不在前台" +
                            "（Android 10+ 的 WifiNetworkSpecifier 只给前台 App 弹确认框），" +
                            "或该热点已被系统保存但处于拒绝状态"
                    else ->
                        "等 ${timeoutMs / 1000}s 没等到网络句柄：确认框没弹出/没点，或热点不在范围内/密码不对"
                }
            return null
        }
        return network
    }

    /**
     * 把进程 socket 绑到 [network]（mDNS/裸 socket 跟随进程默认网络，必须显式绑）。
     * 同一热点重复绑定走快路径：句柄未变时直接返回 true，不再重复 bind（避免无谓解绑/重绑抖动）。
     */
    fun bindProcess(network: Network): Boolean = synchronized(bindLock) {
        if (boundNetwork.get() == network && connectivityManager.boundNetworkForProcess == network) return true // 快路径：已绑同一网络
        val ok = connectivityManager.bindProcessToNetwork(network)
        if (ok) boundNetwork.set(network)
        ok
    }

    private fun handleNetworkLost(owner: ConnectivityManager.NetworkCallback, network: Network) {
        val notify = synchronized(bindLock) {
            if (callback.get() !== owner || boundNetwork.get() != network) false
            else {
                boundNetwork.set(null)
                if (connectivityManager.boundNetworkForProcess == network) {
                    connectivityManager.bindProcessToNetwork(null)
                }
                true
            }
        }
        if (notify) lostCallback()
    }

    /** Release only this instance's process binding and callback. */
    fun release() = synchronized(bindLock) {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        val previous = boundNetwork.getAndSet(null)
        if (previous != null && connectivityManager.boundNetworkForProcess == previous) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    /** 轮询 `cmd wifi status` 直到出现目标 SSID；[timeoutMs] 内未匹配返回 false。可取消。 */
    private suspend fun awaitSsid(ssid: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            currentCoroutineContext().ensureActive() // 协程取消即时打断等待
            val status = ShizukuTools.exec("cmd wifi status", 4_000)
            if (statusShowsSsid(status.out, ssid)) return true
            delay(500)
        }
        return false
    }

    /** 解析 `cmd wifi status` 是否已连在 [ssid]（统一走 shell 查询，不依赖定位权限/WifiInfo）。 */
    private fun statusShowsSsid(status: String, ssid: String): Boolean {
        if (status.isBlank()) return false
        val m = Regex("connected\\s+to\\s+\"([^\"]*)\"").find(status)
        if (m != null) return m.groupValues[1] == ssid
        return status.contains("\"$ssid\"") // 兜底：输出里出现带引号的 SSID
    }

    /** 用不带 specifier 的 requestNetwork 捕获当前 WiFi 的 Network 句柄；超时/onUnavailable 返回 null。 */
    private suspend fun awaitNetworkHandle(timeoutMs: Long): Network? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // 车机热点无外网，别让它被拆
            .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback.get() !== this) return
                result.set(network)
                latch.countDown()
            }

            override fun onUnavailable() {
                if (callback.get() !== this) return
                latch.countDown()
            }

            override fun onLost(network: Network) = handleNetworkLost(this, network)
        }
        callback.set(cb)
        try {
            connectivityManager.requestNetwork(request, cb)
        } catch (t: Throwable) {
            callback.compareAndSet(cb, null)
            Log.i(TAG, "requestNetwork 失败：${t.message}")
            return null
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (callback.compareAndSet(cb, null)) {
                runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            }
            throw cancelled
        }
        val network = result.get()
        if (network == null) {
            release() // 未拿到句柄：注销回调，别泄漏
            return null
        }
        return network
    }

    internal companion object {
        internal fun isUnsupportedPassphrase(psk: String): Boolean = psk.isNotEmpty() && psk.length < 8
        const val TAG = "WifiJoiner"
        const val JOIN_WAIT_MS = 15_000L
        const val HANDLE_WAIT_MS = 15_000L

        /** specifier 路径：首连要等用户点系统确认框，窗口给宽（30s）。 */
        const val SPECIFIER_WAIT_MS = 30_000L
    }
}
