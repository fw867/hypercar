package io.github.hypercar.bluetooth

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeShell
import java.util.UUID
import kotlinx.coroutines.*

internal const val DISCOVERABLE_WINDOW_SECONDS = 30

/** null = 无法请求，0 = 未同意，正数 = 系统返回的实际秒数。 */
internal typealias SystemDiscoverabilityPrompt = suspend (() -> Boolean) -> Int?

internal enum class DiscoverabilityRoute { SHELL, SYSTEM_CONFIRMATION, SKIP }

internal fun discoverabilityRoute(shizukuReady: Boolean, immediatePreparation: Boolean,
                                  prepared: Boolean, hasForegroundPrompt: Boolean): DiscoverabilityRoute = when {
    shizukuReady -> DiscoverabilityRoute.SHELL
    immediatePreparation && prepared && hasForegroundPrompt -> DiscoverabilityRoute.SYSTEM_CONFIRMATION
    else -> DiscoverabilityRoute.SKIP
}

private const val AUTO_BMW_KEY_PREFIX = "bt:auto:"
private val bluetoothAddress = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
private val bmwName = Regex("""(?:^|[^a-z])bmw(?![a-z])|宝马""", RegexOption.IGNORE_CASE)

/** 手动启动在目标已配对且蓝牙监听就绪后直接开一轮；自动重连沿用原窗口策略。 */
internal class ReconnectDiscoverabilityPolicy(private val immediatePreparation: Boolean = false) {
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
        if (immediatePreparation && preparationKey != null && !attempted) {
            attempted = true
            return true
        }
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
        if (sessionClaimed || !attempted || immediatePreparation || wakeArmed) return false
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
        withTimeoutOrNull(DISCOVERABLE_WINDOW_SECONDS * 1_000L) {
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
    fun preparationKey(
        profileId: String, manual: Boolean, targetMac: String?, listening: Boolean,
        connectionAllowed: Boolean, successfulCars: Set<String>, bondedName: (String) -> String?,
    ): String? {
        if (!listening || !connectionAllowed || (!manual && profileId != "bmw_id85")) return null
        if (!targetMac.isNullOrBlank()) {
            val mac = targetMac.trim().uppercase(java.util.Locale.ROOT)
            return if (bluetoothAddress.matches(mac) && bondedName(mac) != null) "bt:$mac" else null
        }
        if (manual) {
            // “自动识别”只在历史成功车机中有唯一仍配对的候选时通用，不猜测其他蓝牙设备。
            val mac = successfulCars.asSequence()
                .map { it.substringBefore('|').trim().uppercase(java.util.Locale.ROOT) }
                .distinct()
                .filter { bluetoothAddress.matches(it) && bondedName(it) != null }
                .singleOrNull()
            if (mac != null) return "bt:$mac"
        }
        return automaticBmwPreparationKey(profileId, listening, connectionAllowed, successfulCars, bondedName)
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
        immediatePreparation: Boolean = false,
        systemPrompt: SystemDiscoverabilityPrompt? = null,
    ) {
        firstWindowFinished = false
        val policy = ReconnectDiscoverabilityPolicy(immediatePreparation)
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
                        when (discoverabilityRoute(ShizukuTools.isReady, immediatePreparation,
                                preparation != null, systemPrompt != null)) {
                            DiscoverabilityRoute.SKIP -> {
                                log("BT-DISCOVERY: 跳过短暂可发现，Shizuku 未就绪")
                                return
                            }
                            DiscoverabilityRoute.SYSTEM_CONFIRMATION -> {
                                val job = currentCoroutineContext().job
                                val seconds = systemPrompt?.invoke {
                                    job.isActive && !ShizukuTools.isReady && !sessionStarted() && readyForWindow()
                                }
                                when {
                                    seconds == null -> log("BT-DISCOVERY: 未弹出系统可发现确认，继续等待车机")
                                    seconds <= 0 -> log("BT-DISCOVERY: 未同意系统可发现请求，继续等待车机")
                                    else -> {
                                        log("BT-DISCOVERY: 系统已允许蓝牙可发现 ${seconds}s，由系统到时关闭")
                                        withTimeoutOrNull(seconds * 1_000L) {
                                            while (!sessionStarted() && readyForWindow()) delay(250)
                                        }
                                    }
                                }
                                return
                            }
                            DiscoverabilityRoute.SHELL -> Unit
                        }
                        val bind = SpikeShell.connect(context)
                        currentCoroutineContext().ensureActive()
                        // 绑定 shell 可能花数秒，期间车机可能已建立会话或换网。
                        if (sessionStarted() || !readyForWindow()) return
                        if (!SpikeShell.isConnected()) { log("BT-DISCOVERY: shell 绑定未完成：$bind"); return }
                        val token = UUID.randomUUID().toString()
                        val owner = Binder()
                        var started = false
                        runDiscoverableWindow(
                            start = {
                                val result = SpikeShell.startBluetoothDiscoverable(owner, token, DISCOVERABLE_WINDOW_SECONDS)
                                val source = when {
                                    preparation?.startsWith(AUTO_BMW_KEY_PREFIX) == true -> "ID8.5 匹配唯一已配对历史宝马/蓝牙监听"
                                    preparation != null -> "已配对目标/蓝牙监听"
                                    else -> "已配对车机 Wi-Fi/广播/监听"
                                }
                                val trigger = if (immediatePreparation && preparation != null) {
                                    "手动启动，${source}就绪即开"
                                } else "${source}就绪后"
                                log("BT-DISCOVERY: $trigger：$result")
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
