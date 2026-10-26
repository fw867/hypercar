package io.github.hypercar.net

import android.os.SystemClock
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本机真实蓝牙 MAC 解析（无线 CarPlay 协议身份必需）。
 *
 * Android 11+ 应用层调 `BluetoothAdapter.getAddress()` 只会拿到占位 `02:00:00:00:00:00`；
 * 车机会校验协议里的 bluetoothTransportIdentifier 是否与 RFCOMM 对端一致，占位值会被静默丢弃
 * （现象：链路层照常 ACK，上层永远等不到后续消息）。解析顺序：
 *   1. 应用层 `getAddress()`——AOSP 恒占位，但个别 OEM ROM 放行（零成本，先试）；
 *   2. Shizuku shell 读系统安全设置（shell 身份免 root）：
 *      `content query --uri content://settings/secure/bluetooth_address`；
 *   3. 都拿不到 → 上层回落稳定伪 ID（见 [resolveOrStable]）。
 *
 * 结果**进程内缓存**；解析失败进入 10s 静默窗口（窗口内直接返回 null，避免每次连接都阻塞在
 * shell 查询上）；`force=true` 跳过缓存与静默窗口立即重查（占位 MAC 需要能立刻重解析）。
 */
object BluetoothMac {
    /** Android 应用层可见的占位 MAC——解析必须得到与之不同的真值才算成功。 */
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private const val QUIET_MILLIS = 10_000L

    @Volatile
    private var cached: String? = null

    @Volatile
    private var lastAttemptAt = 0L

    /** 用协程友好的 Mutex 而非 synchronized：下面要挂起等 shell，不能持 JVM 锁跨挂起点。 */
    private val mutex = Mutex()

    /**
     * 解析真实蓝牙 MAC（大写冒号格式）。
     *
     * @param force 忽略缓存与 10s 静默窗口立即重查。
     * @return 真实 MAC；解析失败或处于静默窗口内返回 null。
     */
    suspend fun resolve(force: Boolean = false): String? {
        cached?.let { return it }
        if (!force && SystemClock.uptimeMillis() - lastAttemptAt < QUIET_MILLIS) return null
        return mutex.withLock {
            cached?.let { return it }
            lastAttemptAt = SystemClock.uptimeMillis()
            val mac = query()
            if (mac != null) cached = mac
            mac
        }
    }

    private suspend fun query(): String? {
        // 先试应用层直读：AOSP 6.0+ 恒返回 02:00:00:00:00:00，但**少数 OEM ROM 仍会返回真值**
        // （部分 MIUI/ColorOS 历史版本）。这一枪几乎零成本，能拿到就不必走 shell。
        localAddress()?.let { return it }
        val r = ShizukuTools.exec("content query --uri content://settings/secure/bluetooth_address", 4_000)
        if (!r.ok) return null
        return MAC_REGEX.find(r.out)?.value?.uppercase()?.takeIf { it != PLACEHOLDER_MAC }
    }

    /**
     * 应用层能拿到的本机蓝牙地址。
     *
     * `BluetoothAdapter.getAddress()` 自 Android 6.0 起对普通应用返回占位值，
     * Android 11+ 更是完全固定；这里只在**返回非占位值时**采信（个别 ROM 放行）。
     */
    @Suppress("DEPRECATION")
    private fun localAddress(): String? = runCatching {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return null
        val addr = adapter.address ?: return null
        addr.uppercase().takeIf { MAC_REGEX.matches(it) && it != PLACEHOLDER_MAC }
    }.getOrNull()

    /**
     * 取用于协议身份的 deviceID，优先级：
     *   ① [SettingsStore.manualBtMac] 手动指定（用户在无 Shizuku 环境下用 adb 查到真值后填入）
     *   ② 自动解析（`getAddress()` / Shizuku 读 secure 设置）
     *   ③ [SettingsStore.stableDeviceId] 持久化稳定伪 MAC
     *
     * 无 Shizuku 时 Android 11+ 拿不到真 MAC；宝马 iD8.5 实测未校验 MAC，稳定伪 ID 即可通，
     * 只有 WinPlay/部分奥迪这类按 deviceID 严格过滤的接收端会拒——那时要么装 Shizuku，
     * 要么手动填一次真值。
     */
    suspend fun resolveOrStable(force: Boolean = false): String {
        SettingsStore.manualBtMac?.let { return it }
        return resolve(force) ?: SettingsStore.stableDeviceId
    }

    /**
     * 丢弃缓存：用户改了手动 MAC 后必须调用，否则旧值会一直用到进程重启。
     */
    fun invalidate() {
        cached = null
        lastAttemptAt = 0L
    }
}
