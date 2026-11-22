package io.github.hypercar.bluetooth

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeShell
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal const val DISCOVERABLE_WINDOW_SECONDS = 30

/**
 * 自动连接开着、已配对车机的经典 ACL 上来、本轮还没开始 iAP2、当前没有可发现窗口时，才开一扇。
 * 自动连接关着、未配对的旁边设备、iAP2 已经开始、窗口还开着，都不开。
 */
internal fun knownBondedCar(
    bonded: Boolean,
    peer: String,
    explicitTarget: String?,
    pairedBefore: Boolean,
    sessionCar: String?,
): Boolean {
    if (!bonded || peer.isBlank()) return false
    if (!explicitTarget.isNullOrBlank()) return explicitTarget.equals(peer, ignoreCase = true)
    if (pairedBefore) return true
    return !sessionCar.isNullOrBlank() && sessionCar.equals(peer, ignoreCase = true)
}

internal fun shouldDiscoverForCarAcl(
    autoConnect: Boolean,
    knownBonded: Boolean,
    iap2Started: Boolean,
    discoverableAlreadyOn: Boolean,
): Boolean = autoConnect && knownBonded && !iap2Started && !discoverableAlreadyOn

internal typealias SystemDiscoverabilityPrompt = suspend (() -> Boolean, SystemDiscoverabilityRequest) -> Unit

/** The Activity owns a request once accepted; cancelling the watch cannot discard its result. */
internal class SystemDiscoverabilityRequest(private val onResult: (Int?) -> Unit) {
    private enum class State { NEW, OWNED, FINISHED }
    private var state = State.NEW
    private val result = CompletableDeferred<Int?>()

    @Synchronized fun takeOwnership(): Boolean {
        if (state != State.NEW) return false
        state = State.OWNED
        return true
    }

    /** null = unavailable/destroyed, 0 = rejected, positive = granted duration. */
    @Synchronized fun finish(seconds: Int?) {
        if (state == State.OWNED) complete(seconds)
    }

    @Synchronized fun abandonIfUnowned() {
        if (state == State.NEW) complete(null)
    }

    suspend fun await(): Int? = result.await()

    private fun complete(seconds: Int?) {
        state = State.FINISHED
        try {
            onResult(seconds)
        } finally {
            result.complete(seconds)
        }
    }
}

/** One immutable snapshot distinguishes a deliberate restart from unrelated replacement. */
internal class DiscoverabilityHandoff(val origin: Long, val successor: Long? = null) {
    fun keepsWindow(windowAttempt: Long, liveAttempt: Long, live: Boolean): Boolean =
        origin == windowAttempt && (successor == null || (live && liveAttempt == successor))
}

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
internal fun isBmwBluetoothName(name: String?): Boolean = bmwName.containsMatchIn(name.orEmpty())

/** 手动启动在目标已配对且蓝牙监听就绪后直接开一轮；自动重连沿用原窗口策略。 */
internal class ReconnectDiscoverabilityPolicy(
    private val immediatePreparation: Boolean = false,
    suppressManualWindow: Boolean = false,
) {
    private var key: String? = null
    private var readyAt = 0L
    // The decision made at click time survives a restart whose setup outlasts the first window.
    private var attempted = immediatePreparation && suppressManualWindow
    private var restoreManualAfterStaleAcl = false
    private var wakeArmed = false
    /** Session already started — no further discoverable windows this watch. */
    private var sessionClaimed = false
    /** The 30s window is open; another ACL must not start a second one. */
    private var windowOpen = false
    @Synchronized
    fun shouldAttempt(enabled: Boolean, networkKey: String?, servicesReady: Boolean, sessionStarted: Boolean, now: Long,
                      preparationKey: String? = null, leaseOpen: Boolean = false,
                      aclPreparationKey: String? = null): Boolean {
        if (sessionStarted) {
            attempted = true
            wakeArmed = false
            sessionClaimed = true
            windowOpen = false
            return false
        }
        if (sessionClaimed) return false
        if (aclPreparationKey != null && enabled && !windowOpen && !leaseOpen) {
            // One edge replaces a companion wake waiting for the same window.
            wakeArmed = false
            restoreManualAfterStaleAcl = immediatePreparation && !attempted
            return accept()
        }
        val readyKey = preparationKey ?: networkKey?.takeIf { servicesReady }
        if (!enabled || readyKey == null) { key = null; return false }
        if (immediatePreparation && preparationKey != null && !attempted) return accept(leaseOpen || windowOpen)
        if (wakeArmed) {
            wakeArmed = false
            if (windowOpen || readyKey.startsWith(AUTO_BMW_KEY_PREFIX)) return false
            key = readyKey
            readyAt = now
            return accept(leaseOpen)
        }
        if (attempted || windowOpen) return false
        if (key != readyKey) { key = readyKey; readyAt = now; return false }
        if (now - readyAt < 5_000) return false
        return accept(leaseOpen)
    }

    @Synchronized
    fun noteWindowClosed(staleAclBeforeStart: Boolean = false): Boolean {
        windowOpen = false
        val manualRestored = staleAclBeforeStart && restoreManualAfterStaleAcl && !sessionClaimed
        if (manualRestored) attempted = false
        restoreManualAfterStaleAcl = false
        return manualRestored
    }

    @Synchronized
    fun discoverableWindowOpen(): Boolean = windowOpen

    private fun accept(alreadyOpen: Boolean = false): Boolean {
        attempted = true
        windowOpen = !alreadyOpen
        return !alreadyOpen
    }

    /** Companion wake while still waiting for invite/iAP2: allow one extra discoverable window. */
    @Synchronized
    fun armForWake(): Boolean {
        if (sessionClaimed || windowOpen || !attempted || immediatePreparation || wakeArmed) return false
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

/** Reservation excludes overlapping starts; it doesn't suppress a manual click until committed. */
internal class DiscoverabilityReservation {
    enum class Phase { IDLE, RESERVED, STARTED }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    @Synchronized fun acquire(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.RESERVED
        return true
    }

    @Synchronized fun start() {
        check(phase == Phase.RESERVED)
        phase = Phase.STARTED
    }

    @Synchronized fun release() {
        phase = Phase.IDLE
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
            .filter { bluetoothAddress.matches(it) && isBmwBluetoothName(bondedName(it)) }
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

    /** A reservation prevents duplicate ACL edges, but is not yet a window to share. */
    fun discoverableWindowOpen(): Boolean = reservation.phase != DiscoverabilityReservation.Phase.IDLE ||
        activePolicy?.discoverableWindowOpen() == true

    /** Snapshot at the actual click, before an asynchronous restart. */
    fun windowOpenAtManualClick(): Boolean = reservation.phase == DiscoverabilityReservation.Phase.STARTED

    fun firstWindowOver(): Boolean = firstWindowFinished

    private val reservation = DiscoverabilityReservation()
    private val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    private fun commitShellStart(policy: ReconnectDiscoverabilityPolicy, job: Job): Boolean {
        if (activePolicy !== policy || !job.isActive) return false
        reservation.start()
        return true
    }

    suspend fun watch(
        context: Context,
        networkKey: () -> String?,
        servicesReady: () -> Boolean,
        sessionStarted: () -> Boolean,
        log: (String) -> Unit,
        preparationKey: () -> String? = { null },
        immediatePreparation: Boolean = false,
        systemPrompt: SystemDiscoverabilityPrompt? = null,
        discoverableStillUseful: () -> Boolean = { true },
        takeAclPreparationKey: () -> String? = { null },
        aclPreparationStillValid: (String) -> Boolean = { false },
        suppressManualWindow: Boolean = false,
    ) {
        val watchJob = currentCoroutineContext().job
        val policy = ReconnectDiscoverabilityPolicy(immediatePreparation, suppressManualWindow)
        synchronized(this) {
            firstWindowFinished = false
            activePolicy = policy
        }
        try {
            while (watchJob.isActive) {
                if (sessionStarted() || activePolicy !== policy) return
                val shizukuReady = ShizukuTools.isReady
                // A missing shell must not let an ACL edge replace the ordinary foreground request.
                val aclPreparation = if (shizukuReady) takeAclPreparationKey()?.takeIf(aclPreparationStillValid) else null
                val phase = reservation.phase
                if (phase == DiscoverabilityReservation.Phase.RESERVED) {
                    // An older watch that has not started yet isn't a window to share. Do not eat the manual request.
                    delay(500)
                    continue
                }
                val network = networkKey()
                val preparation = preparationKey()
                val windowAlreadyOpen = phase == DiscoverabilityReservation.Phase.STARTED
                val fromAcl = aclPreparation != null && !windowAlreadyOpen
                fun readyForWindow(): Boolean = when {
                    fromAcl -> ShizukuTools.isReady && aclPreparationStillValid(checkNotNull(aclPreparation))
                    preparation != null -> preparationKey() == preparation
                    else -> preparationKey() == null && servicesReady() && networkKey() == network
                }
                fun canStart(): Boolean = watchJob.isActive && activePolicy === policy &&
                    !sessionStarted() && readyForWindow()
                if (policy.shouldAttempt(true, network, servicesReady(), false,
                        SystemClock.elapsedRealtime(), preparation, windowAlreadyOpen, aclPreparation)) {
                    if (!reservation.acquire()) {
                        policy.noteWindowClosed(staleAclBeforeStart = fromAcl && !windowOpenAtManualClick())
                        delay(500)
                        continue
                    }
                    var handedOff = false
                    val shellStarted = AtomicBoolean(false)
                    try {
                        when (discoverabilityRoute(shizukuReady, immediatePreparation,
                                preparation != null, systemPrompt != null)) {
                            DiscoverabilityRoute.SKIP -> {
                                log("BT-DISCOVERY: 跳过短暂可发现，Shizuku 未就绪")
                                return
                            }
                            DiscoverabilityRoute.SYSTEM_CONFIRMATION -> {
                                val request = SystemDiscoverabilityRequest { seconds ->
                                    when {
                                        seconds == null -> {
                                            reservation.release()
                                            log("BT-DISCOVERY: 未弹出系统可发现确认，继续等待车机")
                                        }
                                        seconds <= 0 -> {
                                            reservation.release()
                                            log("BT-DISCOVERY: 未同意系统可发现请求，继续等待车机")
                                        }
                                        else -> {
                                            reservation.start()
                                            val expiresAt = SystemClock.elapsedRealtime() + seconds * 1_000L
                                            windowScope.launch {
                                                try {
                                                    delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                                                } finally {
                                                    reservation.release()
                                                }
                                            }
                                            log("BT-DISCOVERY: 系统已允许蓝牙可发现 ${seconds}s，由系统到时关闭")
                                        }
                                    }
                                }
                                handedOff = true
                                try {
                                    systemPrompt?.invoke({ !ShizukuTools.isReady && canStart() }, request)
                                } finally {
                                    request.abandonIfUnowned()
                                }
                                val seconds = request.await()
                                if (seconds != null && seconds > 0) {
                                    withTimeoutOrNull(seconds * 1_000L) {
                                        while (!sessionStarted() && readyForWindow()) delay(250)
                                    }
                                }
                                return
                            }
                            DiscoverabilityRoute.SHELL -> Unit
                        }
                        val bind = SpikeShell.connect(context)
                        currentCoroutineContext().ensureActive()
                        // Binding can take seconds. A stale ACL must not retire the watch for new edges.
                        if (!canStart()) {
                            if (fromAcl) continue
                            return
                        }
                        if (!SpikeShell.isConnected()) {
                            log("BT-DISCOVERY: shell 绑定未完成：$bind")
                            if (fromAcl) continue
                            return
                        }
                        val token = UUID.randomUUID().toString()
                        val owner = Binder()
                        // 窗口跟自己的 30 秒走，不跟这次连接协程。手动重试取消 watch 时，已经开着的窗口留着，不再开第二扇。
                        val window = windowScope.launch {
                            try {
                                runDiscoverableWindow(
                                    start = start@{
                                        // The detached job may be dispatched after the old watch was cancelled.
                                        if (!canStart() || !commitShellStart(policy, watchJob)) return@start false
                                        shellStarted.set(true)
                                        val result = SpikeShell.startBluetoothDiscoverable(owner, token, DISCOVERABLE_WINDOW_SECONDS)
                                        val source = when {
                                            preparation?.startsWith(AUTO_BMW_KEY_PREFIX) == true -> "ID8.5 匹配唯一已配对历史宝马/蓝牙监听"
                                            preparation != null -> "已配对目标/蓝牙监听"
                                            else -> "已配对车机 Wi-Fi/广播/监听"
                                        }
                                        val trigger = when {
                                            fromAcl -> "已配对车机 ACL 连上且本轮尚未 iAP2"
                                            immediatePreparation && preparation != null -> "手动启动，${source}就绪即开"
                                            else -> "${source}就绪后"
                                        }
                                        log("BT-DISCOVERY: $trigger：$result")
                                        result.startsWith("OK")
                                    },
                                    keepOpen = { discoverableStillUseful() },
                                    stop = {
                                        // 旧轮次只清理本轮 token，不能关闭新窗口。
                                        if (shellStarted.get()) log("BT-DISCOVERY: ${SpikeShell.stopBluetoothDiscoverable(token)}")
                                    },
                                )
                            } finally {
                                reservation.release()
                            }
                        }
                        handedOff = true
                        window.join()
                    } finally {
                        if (!handedOff) reservation.release()
                        // 窗已关，或这轮没能开成。进度据此停止把空窗写成交棒完成。
                        val manualRestored = policy.noteWindowClosed(staleAclBeforeStart = fromAcl && !shellStarted.get())
                        if (!manualRestored) synchronized(this) {
                            if (activePolicy === policy) firstWindowFinished = true
                        }
                    }
                    // Stay in the loop for a genuinely new ACL edge or companion wake.
                }
                delay(500)
            }
        } finally {
            synchronized(this) {
                if (activePolicy === policy) activePolicy = null
            }
        }
    }
}
