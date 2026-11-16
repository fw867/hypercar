package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import android.util.Log
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Local-only Wi-Fi request owned by HyperCar. Never save/connect a system-wide internet network.
 * Shizuku may enable Wi-Fi and select this app's matching request.
 * The NetworkCallback stays alive for the complete CarPlay session. */
class WifiJoiner(
    private val context: Context,
    private val diagnostic: (String) -> Unit = {},
    onLost: () -> Unit,
) {
    /** 构造参数转存：避免与 NetworkCallback.onLost 回调方法名冲突。 */
    private val lostCallback: () -> Unit = onLost
    private fun diagnose(message: String) {
        Log.i(TAG, message)
        diagnostic("WIFI-JOIN: $message")
    }

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
    private val association = WifiRequestAssociation<ConnectivityManager.NetworkCallback, Network>()

    /** 当前已绑定的网络（bindProcess 记录，供快路径与 onLost 解绑）。 */
    private val boundNetwork = AtomicReference<Network?>(null)
    @Volatile private var requestIdentity: CarWifiRequestIdentity? = null
    private val selectionOwner = android.os.Binder()
    private val selectionToken = AtomicReference<String?>(null)

    /** 串行化 bind/release（网络回调线程与调用方线程可能并发）。 */
    private val bindLock = Any()

    /** 当前绑定网络；未绑定返回 null。 */
    val currentNetwork: Network? get() = association.network() ?: boundNetwork.get()

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
     * 始终走 app 专属 WifiNetworkSpecifier；Shizuku 可选择本应用的匹配请求。
     * 无 Shizuku 或 ROM 不支持静默选择时，首次可能需要系统确认。
     *
     * @param ssid 热点名（可能含空格/中文）。
     * @param psk  热点密码；空=开放热点；WEP 形态返回 [JoinResult.Unsupported]。
     */
    suspend fun joinCarHotspotSilently(ssid: String, psk: String,
        verifiedFallback: suspend () -> Network? = { null }, deviceIdentifierHint: () -> String? = { null }): JoinResult {
        lastFailureReason = null
        // Password length alone cannot distinguish WEP from valid 10/13/26-character WPA2.
        if (isUnsupportedPassphrase(psk)) {
            lastFailureReason = "热点密码不足 8 位，无法按 WPA2 入网"
            return JoinResult.Unsupported
        }
        val existing = currentNetwork
        if (existing != null && ownsLiveRequest(existing) && !requiresDifferentHotspot(ssid)) {
            if (ready(existing)) return JoinResult.Joined(existing)
            lastFailureReason = "已连接同一车机热点，等待网络属性或地址就绪；不重复入网"
            return JoinResult.Failed
        }
        if (!canRequestHotspot(ssid, psk)) {
            lastFailureReason = "热点名称或密码无法构造有效入网请求；保留现有连接"
            return JoinResult.Unsupported
        }
        val blocked = SpecifierPermissions.degradeBlockReason(context)
        if (blocked != null) {
            lastFailureReason = blocked
            return JoinResult.Failed
        }
        // Only a deliberate different-SSID request can replace an available association.
        release()
        if (ShizukuTools.isReady) withContext(Dispatchers.IO) {
            val shell = io.github.hypercar.spike.SpikeShell.connect(context)
            Log.i(TAG, "local-only shell: $shell")
            if (!isWifiEnabled()) ShizukuTools.exec("cmd wifi set-wifi-enabled enabled")
            // Never tear down a same-car system association or persistently disable its
            // autojoin before a replacement request has even succeeded.
        }
        Log.i(TAG, "申请 HyperCar 专用局域网；不保存系统热点，不接管其它 App 默认网络")
        // Scan hints help ROMs that abandon SSID-only scans. They are optional evidence,
        // not proof that every SSID-only failure has the same cause.
        val hint = if (ShizukuTools.isReady) withContext(Dispatchers.IO) {
            WifiApHint.resolve(ssid, deviceIdentifierHint,
                scan = { ShizukuTools.exec("cmd wifi list-scan-results", 3_000).out },
                startScan = { ShizukuTools.exec("cmd wifi start-scan", 3_000); Unit },
                now = SystemClock::elapsedRealtime)
        } else null
        currentCoroutineContext().ensureActive()
        diagnose("scan_hint=${hint != null} freq=${hint?.frequency ?: 0} age_s=${hint?.ageSeconds ?: -1.0}; no AP address logged")
        requestIdentity = CarWifiRequestIdentity(ssid, psk)
        return joinViaSpecifier(ssid, psk, hint, verifiedFallback)
    }

    /**
     * 公开 API `WifiNetworkSpecifier` 申请一个 **app 专属** WiFi 网络。
     *
     * 与系统「保存并连接」不同，这条路是进程隔离的点对点连接——正合我们 bindProcess 的用法，
     * 且不落系统已保存网络（避开 cardeck §13b「热点已保存反而连不上」的地雷）。
     *
     * 无可用 shell 选择器时，首次连接需要系统确认；批准按 App/AP 保存，不能承诺每次都静默。
     * 确认框由 SystemUI 呈现；**后台 FGS 拉起的首连不保证能弹出**——所以首次连接的推荐
     * 用法是：装好后先手动打开一次 App 完成首连确认，之后「上车自动连接」才会全程静默。
     * 拿不到句柄（框弹不出/用户拒绝/超时）一律返回 Failed，由调用方降级到「绑定当前网络」。
     */
    private suspend fun joinViaSpecifier(ssid: String, psk: String, hint: WifiApHint?, verifiedFallback: suspend () -> Network?): JoinResult =
        withContext(Dispatchers.IO) {
            val specifier = WifiNetworkSpecifier.Builder().apply {
                setSsid(ssid)
                // A specific, freshly invited AP can use the system's cached approval/scan
                // immediately. SSID-only requests always start scans, which this ROM can abort.
                hint?.let {
                    setBssid(android.net.MacAddress.fromString(it.bssid))
                    if (android.os.Build.VERSION.SDK_INT >= 34 && it.ageSeconds <= 30 && it.frequency in 2400..7125)
                        setPreferredChannelsFrequenciesMhz(intArrayOf(it.frequency))
                    Log.i(TAG, "local-only exact AP from invitation + scan scan_age_s=${it.ageSeconds}")
                }
                // 开放网络：Builder 没有 setOpenNetwork()，不设任何密码方法即为开放；
                // WPA2 只有 setWpa2Passphrase 一条路径。
                if (psk.isNotEmpty()) setWpa2Passphrase(psk)
            }.build()

            // app 专属网络：只能加 TRANSPORT_WIFI + specifier，
            // 不能声明 NET_CAPABILITY_INTERNET（车机热点本就无外网，且 specifier 网络不允许）。
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val network = awaitSpecifierHandle(request, specifier, ssid, hint, SPECIFIER_WAIT_MS, verifiedFallback)
                ?: return@withContext JoinResult.Failed
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (!CarWifiPolicy.canReuse(caps, ownsLiveRequest(network))) {
                cancelPendingRegistration()
                lastFailureReason = "系统网络属性尚未就绪，保留已建立的车机热点连接"
                return@withContext JoinResult.Failed
            }
            Log.i(TAG, "LOCAL-ONLY network=$network internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                "uidDefault=${connectivityManager.activeNetwork}")
            JoinResult.Joined(network)
        }

    /** 用 specifier 请求拿 Network 句柄；首连时系统确认框的等待时间也算在 [timeoutMs] 内。 */
    private suspend fun awaitSpecifierHandle(request: NetworkRequest, specifier: WifiNetworkSpecifier, ssid: String, hint: WifiApHint?, timeoutMs: Long,
        verifiedFallback: suspend () -> Network?): Network? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        // 立刻 onUnavailable 与「等到超时」是完全不同的病因，必须分开记
        val unavailable = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = SystemClock.elapsedRealtime()
        val trace = WifiJoinDiagnostics(context, ssid, ::diagnose)
        val waiting = java.util.concurrent.atomic.AtomicBoolean(true)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var sawCapabilities = false
            private var sawProperties = false
            override fun onAvailable(network: Network) {
                if (!association.available(this, network)) return
                result.set(network)
                trace.log("callback=available handle=${network.networkHandle}")
                latch.countDown()
            }

            override fun onUnavailable() {
                if (!association.unavailable(this)) return
                unavailable.set(true)
                trace.log("callback=unavailable handle_present=${result.get() != null}")
                latch.countDown()
            }

            override fun onLost(network: Network) {
                if (association.owner() !== this) return
                result.compareAndSet(network, null)
                trace.log("callback=lost handle=${network.networkHandle} bound=${boundNetwork.get() == network}")
                handleNetworkLost(this, network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (association.owner() !== this || (sawCapabilities && !waiting.get())) return
                sawCapabilities = true
                trace.log("callback=capabilities handle=${network.networkHandle} wifi=${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                    "ssid_match=${WifiJoinDiagnostics.ssidMatch((caps.transportInfo as? WifiInfo)?.ssid, ssid)} " +
                    "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            }

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                if (association.owner() !== this || (sawProperties && !waiting.get())) return
                sawProperties = true
                trace.log("callback=link_properties handle=${network.networkHandle} " +
                    "v4_count=${props.linkAddresses.count { it.address is java.net.Inet4Address }} " +
                    "v6_count=${props.linkAddresses.count { it.address is java.net.Inet6Address }} routes=${props.routes.size}")
            }
        }
        association.register(cb)
        trace.log("request timeout_ms=$timeoutMs exact_ap=${hint != null}")
        trace.start(specifier) { waiting.get() && association.owner() === cb }
        try {
            // 系统确认框和关联/DHCP 共用这一个超时窗口，不是单独计时。
            // （该重载的 timeout 参数是 Int，需从 Long 转换）
            connectivityManager.requestNetwork(request, cb, timeoutMs.toInt())
            trace.log("request_submitted")
        } catch (t: Throwable) {
            cancelPendingRegistration(cb)
            waiting.set(false)
            trace.log("request_exception=${t.javaClass.simpleName}")
            trace.close()
            lastFailureReason = "requestNetwork 抛异常：${t.javaClass.simpleName}: ${t.message}"
            Log.i(TAG, "specifier requestNetwork 失败：${t.message}")
            return null
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        try {
            val shizukuReady = ShizukuTools.isReady
            val shellConnected = io.github.hypercar.spike.SpikeShell.isConnected()
            if (shizukuReady && shellConnected) {
                val token = trace.id
                selectionToken.set(token)
                val selected = io.github.hypercar.spike.SpikeShell.startWifiSelection(
                    selectionOwner, token, ssid, hint?.bssid.orEmpty(), android.os.Process.myUid())
                trace.log("selection=shell armed=${selected.startsWith("OK")}")
                if (association.owner() !== cb || selectionToken.get() != token)
                    io.github.hypercar.spike.SpikeShell.stopWifiSelection(token)
            } else trace.log("selection=system shizuku_ready=$shizukuReady shell_connected=$shellConnected approval_ui=unobservable")
            var nextSample = SystemClock.elapsedRealtime() + 2_000
            while (SystemClock.uptimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
                if (SystemClock.elapsedRealtime() >= nextSample) {
                    trace.station()
                    nextSample = SystemClock.elapsedRealtime() + 2_000
                }
            }
            // Do not unregister a potentially live request before checking the actual network.
            // A system Wi-Fi association alone is insufficient: fallback must pass the caller's
            // existing SSID/BSSID/address/route and local-only capability checks.
            val remaining = (timeoutMs - (SystemClock.elapsedRealtime() - started)).coerceIn(0L, 3_000L)
            val verified = VerifiedWifiWait.await(remaining, SystemClock::elapsedRealtime,
                current = { association.owner() === cb }, probe = {
                    result.get()?.takeIf { ready(it) } ?: verifiedFallback()?.takeIf { ready(it) }
                })
            if (verified != null) {
                trace.log("verified=true handle=${verified.networkHandle} source=${if (result.get() == verified) "request_callback" else "existing_wifi"} unavailable=${unavailable.get()}")
                return verified
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Cancelling CarPlay must not withdraw a Wi-Fi association already supplied by
            // the system. The session owner parks that request until actual network loss.
            cancelPendingRegistration(cb)
            throw cancelled
        } catch (error: Exception) {
            cancelPendingRegistration(cb)
            lastFailureReason = "入网状态复核异常：${error.javaClass.simpleName}"
            diagnose("verification exception=${error.javaClass.simpleName}")
            return null
        } finally {
            waiting.set(false)
            stopSelection(trace.id)
            trace.close()
        }
        val network = association.network() ?: result.get()
        if (network == null || !ready(network)) {
            trace.log("verified=false candidate=${network != null} ${readiness(network)}")
            // No association: release a pending request. An associated AP without a ready
            // address belongs to the Wi-Fi lifetime, rather than this failed CarPlay attempt.
            cancelPendingRegistration(cb)
            lastFailureReason = if (network != null) "系统已返回 Wi-Fi 网络，但地址或局域网能力未就绪；请确认系统中的车机 Wi-Fi 连接状态" else SpecifierJoinFailure.describe(
                elapsedMs = SystemClock.elapsedRealtime() - started,
                timeoutMs = timeoutMs,
                unavailable = unavailable.get(),
                wifiEnabled = isWifiEnabled(),
            )
            return null
        }
        return network
    }

    private fun ready(network: Network): Boolean =
        CarWifiPolicy.canReuse(connectivityManager.getNetworkCapabilities(network), ownsLiveRequest(network)) &&
            connectivityManager.getLinkProperties(network)?.linkAddresses?.any {
                !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress && !it.address.isMulticastAddress &&
                    (it.address is java.net.Inet6Address || !it.address.isLinkLocalAddress)
            } == true

    private fun readiness(network: Network?): String {
        val caps = network?.let(connectivityManager::getNetworkCapabilities)
        val addresses = network?.let(connectivityManager::getLinkProperties)?.linkAddresses.orEmpty()
        return "wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true} " +
            "internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true} " +
            "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true} " +
            "v4_count=${addresses.count { it.address is java.net.Inet4Address }} v6_count=${addresses.count { it.address is java.net.Inet6Address }}"
    }

    /**
     * 把进程 socket 绑到 [network]（mDNS/裸 socket 跟随进程默认网络，必须显式绑）。
     * 同一热点重复绑定走快路径：句柄未变时直接返回 true，不再重复 bind（避免无谓解绑/重绑抖动）。
     */
    fun ownsBinding(network: Network): Boolean = boundNetwork.get() == network

    fun ownsLiveRequest(network: Network): Boolean = association.owns(network)

    fun requiresDifferentHotspot(ssid: String?): Boolean = requestIdentity?.requiresDifferentHotspot(ssid) == true

    fun isReady(network: Network): Boolean = ready(network)

    private fun cancelPendingRegistration(owner: ConnectivityManager.NetworkCallback? = association.owner()) {
        owner ?: return
        association.cancelPending(owner)?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
    }

    /** Drop only the process route; the request must survive a short protocol recovery. */
    fun unbindProcess() = synchronized(bindLock) {
        val network = boundNetwork.get()
        if (network != null && connectivityManager.boundNetworkForProcess == network) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    /** An available callback for these exact credentials proves ownership even if SSID is redacted. */
    fun ownsHotspot(network: Network, ssid: String?, passphrase: String?): Boolean =
        association.owns(network) &&
            requestIdentity?.matches(ssid, passphrase) == true

    fun bindProcess(network: Network): Boolean = synchronized(bindLock) {
        if (boundNetwork.get() == network && connectivityManager.boundNetworkForProcess == network) return true // 快路径：已绑同一网络
        if (!CarWifiPolicy.canReuse(connectivityManager.getNetworkCapabilities(network), ownsLiveRequest(network))) {
            Log.w(TAG, "拒绝复用未验证的系统互联网 Wi-Fi；必须通过局域网请求连接")
            return false
        }
        val ok = connectivityManager.bindProcessToNetwork(network)
        if (ok) boundNetwork.set(network)
        ok
    }

    private fun handleNetworkLost(owner: ConnectivityManager.NetworkCallback, network: Network) {
        val notify = synchronized(bindLock) {
            val lost = association.lost(owner, network, boundFallback = boundNetwork.get() == network)
            boundNetwork.compareAndSet(network, null)
            if (connectivityManager.boundNetworkForProcess == network) {
                connectivityManager.bindProcessToNetwork(null)
            }
            lost
        }
        if (notify) lostCallback()
    }

    /** Release only this instance's process binding and callback. */
    fun release() = synchronized(bindLock) {
        requestIdentity = null
        val previousCallback = association.release()
        stopSelection()
        previousCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        val previous = boundNetwork.getAndSet(null)
        if (previous != null && connectivityManager.boundNetworkForProcess == previous) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    private fun stopSelection(token: String? = selectionToken.get()) {
        if (token == null || !selectionToken.compareAndSet(token, null)) return
        val summary = runCatching { io.github.hypercar.spike.SpikeShell.stopWifiSelection(token) }.getOrNull()
        diagnose("request_id=$token selection_result=${summary?.takeIf { it.startsWith("OK Wi-Fi selector released") } ?: "unavailable"}")
    }

    internal companion object {
        fun canRequestHotspot(ssid: String?, passphrase: String?): Boolean {
            if (ssid.isNullOrBlank() || passphrase == null) return false
            return try {
                WifiNetworkSpecifier.Builder().apply {
                    setSsid(ssid)
                    if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase)
                }.build()
                true
            } catch (_: IllegalArgumentException) { false }
        }
        internal fun isUnsupportedPassphrase(psk: String): Boolean = psk.isNotEmpty() && psk.length < 8
        const val TAG = "WifiJoiner"

        /** specifier 路径：首连要等用户点系统确认框，窗口给宽（60s）。 */
        const val SPECIFIER_WAIT_MS = 60_000L
    }
}
