package io.github.hypercar.bluetooth

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeShell
import java.util.UUID
import kotlinx.coroutines.*

private const val AUTO_BMW_KEY_PREFIX = "bt:auto:"
private val bluetoothAddress = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
private val bmwName = Regex("""(?:^|[^a-z])bmw(?![a-z])|宝马""", RegexOption.IGNORE_CASE)

/** 每轮连接只消费一次机会，网络/服务未就绪时不启动宽限计时；ACL 唤醒可再给一次。 */
internal class ReconnectDiscoverabilityPolicy {
    private var key: String? = null
    private var readyAt = 0L
    private var attempted = false
    private var wakeArmed = false
    /** Session already started — no further discoverable windows this watch. */
    private var sessionClaimed = false

    @Synchronized
    fun shouldAttempt(enabled: Boolean, networkKey: String?, servicesReady: Boolean, sessionStarted: Boolean, now: Long,
                      preparationKey: String? = null): Boolean {
        if (sessionStarted) {
            attempted = true
            wakeArmed = false
            sessionClaimed = true
            return false
        }
        if (sessionClaimed) return false
        val readyKey = preparationKey ?: networkKey?.takeIf { servicesReady }
        if (!enabled || readyKey == null) { key = null; return false }
        if (wakeArmed) {
            wakeArmed = false
            if (readyKey.startsWith(AUTO_BMW_KEY_PREFIX)) return false
            attempted = true
            key = readyKey
            readyAt = now
            return true
        }
        if (attempted) return false
        if (key != readyKey) { key = readyKey; readyAt = now; return false }
        if (now - readyAt < 5_000) return false
        attempted = true
        return true
    }

    /** Car ACL while still waiting for invite/iAP2: allow one extra discoverable window. */
    @Synchronized
    fun armForWake(): Boolean {
        if (sessionClaimed || !attempted || wakeArmed) return false
        wakeArmed = true
        return true
    }
}

/** Token cleanup runs even when cancellation arrives during the synchronous Binder start. */
internal suspend fun runDiscoverableWindow(
    start: () -> Boolean,
    keepOpen: () -> Boolean,
    stop: suspend () -> Unit,
) {
    try {
        if (!start()) return
        withTimeoutOrNull(30_000) {
            while (keepOpen()) delay(250)
        }
    } finally {
        withContext(NonCancellable) { stop() }
    }
}

internal object ReconnectDiscoverability {
    internal fun automaticBmwPreparationKey(
        profileId: String, listening: Boolean, connectionAllowed: Boolean,
        successfulCars: Set<String>, bondedName: (String) -> String?,
    ): String? {
        if (profileId != "bmw_id85" || !listening || !connectionAllowed) return null
        // 历史名称可能是当时档案的默认标签；用同一地址的系统蓝牙名称识别宝马。
        val mac = successfulCars.asSequence()
            .map { it.substringBefore('|').trim().uppercase(java.util.Locale.ROOT) }
            .distinct()
            .filter { bluetoothAddress.matches(it) && bmwName.containsMatchIn(bondedName(it).orEmpty()) }
            .singleOrNull() ?: return null
        return "$AUTO_BMW_KEY_PREFIX$mac"
    }

    /** 仅准备被动接受蓝牙连接；不授权主动拨号、Wi-Fi 入网或 RTSP 建链。 */
    fun bmwPreparationKey(profileId: String, targetMac: String?, isBonded: Boolean,
                          listening: Boolean, connectionAllowed: Boolean): String? {
        if (profileId != "bmw_id85" || !isBonded || !listening || !connectionAllowed) return null
        val mac = targetMac?.trim()?.uppercase(java.util.Locale.ROOT) ?: return null
        if (!bluetoothAddress.matches(mac)) return null
        return "bt:$mac"
    }

    @Volatile private var activePolicy: ReconnectDiscoverabilityPolicy? = null
    /** 首扇窗已结束，或这轮不会再开窗。不在这里再武装第二扇。 */
    @Volatile private var firstWindowFinished = false

    fun armForWake(): Boolean = activePolicy?.armForWake() == true

    fun firstWindowOver(): Boolean = firstWindowFinished

    suspend fun watch(
        context: Context,
        networkKey: () -> String?,
        servicesReady: () -> Boolean,
        sessionStarted: () -> Boolean,
        log: (String) -> Unit,
        preparationKey: () -> String? = { null },
    ) {
        firstWindowFinished = false
        val policy = ReconnectDiscoverabilityPolicy()
        activePolicy = policy
        try {
            while (currentCoroutineContext().isActive) {
                if (sessionStarted()) return
                val network = networkKey()
                val preparation = preparationKey()
                fun readyForWindow(): Boolean = if (preparation != null) {
                    preparationKey() == preparation
                } else preparationKey() == null && servicesReady() && networkKey() == network
                if (policy.shouldAttempt(true, network, servicesReady(), false,
                        SystemClock.elapsedRealtime(), preparation)) {
                    try {
                        if (!ShizukuTools.isReady) { log("BT-DISCOVERY: 跳过短暂可发现，Shizuku 未就绪"); return }
                        val bind = SpikeShell.connect(context)
                        // 绑定 shell 可能花数秒，期间车机可能已建立会话或换网。
                        if (sessionStarted() || !readyForWindow()) return
                        if (!SpikeShell.isConnected()) { log("BT-DISCOVERY: shell 绑定未完成：$bind"); return }
                        val token = UUID.randomUUID().toString()
                        val owner = Binder()
                        var started = false
                        runDiscoverableWindow(
                            start = {
                                val result = SpikeShell.startBluetoothDiscoverable(owner, token, 30)
                                val source = when {
                                    preparation?.startsWith(AUTO_BMW_KEY_PREFIX) == true -> "ID8.5 自动匹配唯一已连接宝马/蓝牙监听"
                                    preparation != null -> "ID8.5 已配对目标/蓝牙监听"
                                    else -> "已配对车机 Wi-Fi/广播/监听"
                                }
                                log("BT-DISCOVERY: ${source}就绪 5s 后：$result")
                                result.startsWith("OK").also { started = it }
                            },
                            keepOpen = {
                                !sessionStarted() && readyForWindow()
                            },
                            stop = {
                                // 旧轮次只清理本轮 token，不能关闭新窗口。
                                withContext(Dispatchers.IO) {
                                    log("BT-DISCOVERY: ${SpikeShell.stopBluetoothDiscoverable(token)}")
                                }
                            },
                        )
                        if (!started) return
                    } finally {
                        // 窗已关，或这轮没能开成。进度据此停止把空窗写成交棒完成。
                        firstWindowFinished = true
                    }
                    // Stay in the loop so an ACL wake can arm one more window while still waiting.
                    continue
                }
                delay(500)
            }
        } finally {
            if (activePolicy === policy) activePolicy = null
        }
    }
}
