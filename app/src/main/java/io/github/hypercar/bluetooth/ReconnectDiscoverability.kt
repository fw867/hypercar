package io.github.hypercar.bluetooth

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeShell
import java.util.UUID
import kotlinx.coroutines.*

/** 每轮连接只消费一次机会，网络/服务未就绪时不启动宽限计时；ACL 唤醒可再给一次。 */
internal class ReconnectDiscoverabilityPolicy {
    private var key: String? = null
    private var readyAt = 0L
    private var attempted = false
    private var wakeArmed = false
    /** Session already started — no further discoverable windows this watch. */
    private var sessionClaimed = false

    fun shouldAttempt(enabled: Boolean, networkKey: String?, servicesReady: Boolean, sessionStarted: Boolean, now: Long): Boolean {
        if (sessionStarted) {
            attempted = true
            wakeArmed = false
            sessionClaimed = true
            return false
        }
        if (sessionClaimed) return false
        if (!enabled || networkKey == null || !servicesReady) { key = null; return false }
        if (wakeArmed) {
            wakeArmed = false
            attempted = true
            key = networkKey
            readyAt = now
            return true
        }
        if (attempted) return false
        if (key != networkKey) { key = networkKey; readyAt = now; return false }
        if (now - readyAt < 5_000) return false
        attempted = true
        return true
    }

    /** Car ACL while still waiting for invite/iAP2: allow one extra discoverable window. */
    fun armForWake(): Boolean {
        if (sessionClaimed || !attempted || wakeArmed) return false
        wakeArmed = true
        return true
    }
}

internal object ReconnectDiscoverability {
    @Volatile private var activePolicy: ReconnectDiscoverabilityPolicy? = null

    fun armForWake(): Boolean = activePolicy?.armForWake() == true

    suspend fun watch(
        context: Context,
        networkKey: () -> String?,
        servicesReady: () -> Boolean,
        sessionStarted: () -> Boolean,
        log: (String) -> Unit,
    ) {
        val policy = ReconnectDiscoverabilityPolicy()
        activePolicy = policy
        try {
            while (currentCoroutineContext().isActive) {
                if (sessionStarted()) return
                val network = networkKey()
                if (policy.shouldAttempt(SettingsStore.bluetoothDiscoverableFallback, network, servicesReady(), false,
                        SystemClock.elapsedRealtime())) {
                    if (!ShizukuTools.isReady) { log("BT-DISCOVERY: 跳过短暂可发现，Shizuku 未就绪"); return }
                    val bind = SpikeShell.connect(context)
                    // 绑定 shell 可能花数秒，期间车机可能已建立会话、换网或用户关闭开关。
                    if (sessionStarted() || !servicesReady() || networkKey() != network || !SettingsStore.bluetoothDiscoverableFallback) return
                    if (!SpikeShell.isConnected()) { log("BT-DISCOVERY: shell 绑定未完成：$bind"); return }
                    val token = UUID.randomUUID().toString()
                    val owner = Binder()
                    try {
                        val result = SpikeShell.startBluetoothDiscoverable(owner, token, 30)
                        log("BT-DISCOVERY: 已配对车机 Wi-Fi/广播/监听就绪 5s 后：$result")
                        if (!result.startsWith("OK")) return
                        withTimeoutOrNull(30_000) {
                            while (!sessionStarted() && networkKey() == network && SettingsStore.bluetoothDiscoverableFallback) {
                                delay(250)
                            }
                        }
                    } finally {
                        // 即使启动 binder 返回时取消，也用本轮 token 清理；旧轮次不会关闭新窗口。
                        withContext(NonCancellable + Dispatchers.IO) {
                            log("BT-DISCOVERY: ${SpikeShell.stopBluetoothDiscoverable(token)}")
                        }
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
