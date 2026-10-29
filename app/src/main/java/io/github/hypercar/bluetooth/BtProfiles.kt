package io.github.hypercar.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 蓝牙「**只配对、不连接**」策略：iAP2 建链前断开目标设备的 HFP/A2DP/LeAudio。
 *
 * cardeck 奥迪 MIB3 实测（2026-10-18）：手机与车机处于蓝牙**已连接**状态
 * （HFP 通话 / A2DP 媒体配置文件在线）时，车机不开放无线 CarPlay 的 iAP2 服务
 * 窗口——表现为出站连接每 3 秒失败。真 iPhone 的无线 CarPlay 也不依赖这些配置
 * 文件：控制通道走 iAP2 RFCOMM（独立 SDP 服务），声音走 WiFi 上的 AirPlay。
 * 因此正确形态是：**系统级配对 + iAP2 RFCOMM，其余配置文件一律不连**。
 *
 * 实现约束：
 * - `BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)` 是 **@SystemApi**
 *   （编译期不可见），运行时只要 `BLUETOOTH_CONNECT`（本应用已声明）——反射调用；
 * - 查询状态用公开的 `getProfileProxy` + `connectedDevices`，其回调经**主线程
 *   Handler 投递**——所以本对象所有入口**必须在后台线程调用**（主线程调用必然
 *   等满 1.2s 超时并返回 null，还白卡 UI）；
 * - **只对「声明 iAP2 服务或用户选定」的设备调用**（闸门在 Iap2Bluetooth 的
 *   候选过滤里），别误伤耳机/手表的音频连接。
 *
 * cardeck 曾试过把这段挪到 Shizuku UserService（shell uid）执行，实测不可行：
 * UserService 里 `BluetoothManager.getAdapter()` 返回 null（无主 Looper 的应用
 * 上下文），查询恒为「未连接」，反而掩盖真实状态。故固定在应用进程内做。
 */
object BtProfiles {
    private const val TAG = "BT-PROFILE"

    /** 「自己断开」宽限窗口：足够覆盖广播到达延迟，又不至于把真正的下车事件吞掉。 */
    private const val SELF_DROP_GRACE_MILLIS = 6_000L

    /** 地址 → 宽限截止时刻（uptimeMillis）。见 [markSelfDrop]。 */
    private val selfDropUntil = ConcurrentHashMap<String, Long>()

    /** 关心的配置文件（按断开优先级排列）。 */
    private val profiles: List<Int> =
        listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.LE_AUDIO,
        )

    private fun profileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.HEADSET -> "通话(HFP)"
            BluetoothProfile.A2DP -> "音频(A2DP)"
            BluetoothProfile.LE_AUDIO -> "低功耗音频(LE)"
            else -> "profile#$profile"
        }

    /** profile id → 已获取的代理（进程内复用；代理本身无会话状态）。 */
    private val proxies = HashMap<Int, BluetoothProfile>()

    /** 该地址当前在线（已连接）的配置文件名称。 */
    fun connectedNames(
        context: Context,
        address: String,
    ): List<String> =
        profiles.mapNotNull { id ->
            val p = proxy(context, id) ?: return@mapNotNull null
            val connected =
                try {
                    @Suppress("DEPRECATION")
                    p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                } catch (_: Throwable) {
                    false
                }
            if (connected) profileName(id) else null
        }

    /**
     * 尽力断开该设备的全部配置文件连接。
     *
     * 真断开了东西时会先打一条「自己断开」归因标记（见 [markSelfDrop]）：紧接着的系统
     * `ACTION_ACL_DISCONNECTED` 广播是我们造成的，接收器不能当成下车去拆会话。
     * 什么都没连（返回空列表）则不动标记——别把宽限窗口白白撑大。
     *
     * @return 断开前在线的配置文件（空 = 本来就没连接，无需任何动作）
     */
    fun disconnect(
        context: Context,
        device: BluetoothDevice,
    ): List<String> {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return emptyList()
            }
        val before = connectedNames(context, address)
        if (before.isEmpty()) return emptyList()
        markSelfDrop(address)
        for (id in profiles) {
            if (profileName(id) !in before) continue
            proxy(context, id)?.let { disconnectProfile(it, device) }
        }
        // 只允许配置文件级操作，不能升级成 BluetoothDevice.disconnect()：
        // 整个设备的断开可能连带影响系统配对及同设备的其它连接。
        awaitDisconnected(context, address, 1_500L)
        return before
    }

    /**
     * 标记「接下来 [graceMillis] 内该地址的 ACL 断开可能是我们自己造成的」。
     *
     * 为什么要这个标记：iAP2 的「只配对、不连接」策略会主动断车机的 HFP/A2DP
     * （个别 ROM 还退到设备级断开），系统随即广播 `ACTION_ACL_DISCONNECTED`。
     * 那条广播**不是下车**，接收器必须认出来并忽略——否则每次建链都会被自己的
     * 断开广播拆掉会话，还会和前台服务的 ACTION_STOP 串成刷屏回环。
     *
     * 正常不需要直接调：[disconnect] 在真断开前会自动标记。
     */
    fun markSelfDrop(
        address: String,
        graceMillis: Long = SELF_DROP_GRACE_MILLIS,
    ) {
        selfDropUntil[address.uppercase(Locale.US)] = SystemClock.elapsedRealtime() + graceMillis
    }

    /** 该地址此刻是否处在我们主动断开的宽限窗口内（窗口过期自动清掉）。 */
    fun isSelfDrop(address: String): Boolean {
        val key = address.uppercase(Locale.US)
        val until = selfDropUntil[key] ?: return false
        if (SystemClock.elapsedRealtime() <= until) return true
        selfDropUntil.remove(key)
        return false
    }

    /** 断开后仍在线的配置文件（用于日志判断是否真的断开）。 */
    fun stillConnected(
        context: Context,
        address: String,
    ): List<String> = connectedNames(context, address)

    private fun awaitDisconnected(
        context: Context,
        address: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (connectedNames(context, address).isEmpty()) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return connectedNames(context, address).isEmpty()
    }

    private fun proxy(
        context: Context,
        profile: Int,
    ): BluetoothProfile? {
        synchronized(proxies) { proxies[profile]?.let { return it } }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?: return null
        // `getProfileProxy` 的回调经**主线程 Handler** 投递：若本方法跑在主线程，
        // 回调永远无法执行 → 必然白等到超时（1.2s）并返回 null。宁可显式失败也不
        // 静默卡顿——调用方必须转到后台线程（Iap2Bluetooth 的连接循环本就是后台）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "proxy() 在主线程调用——getProfileProxy 回调会被阻塞，返回 null")
            return null
        }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BluetoothProfile?>(null)
        val requested =
            try {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            id: Int,
                            service: BluetoothProfile,
                        ) {
                            ref.set(service)
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(id: Int) {
                            // 蓝牙服务重启后旧代理失效：清缓存，下次重新获取
                            synchronized(proxies) { proxies.remove(id) }
                            latch.countDown()
                        }
                    },
                    profile,
                )
            } catch (_: Throwable) {
                false
            }
        if (!requested) return null
        if (!latch.await(1_200, TimeUnit.MILLISECONDS)) return null
        return ref.get()?.also { synchronized(proxies) { proxies[profile] = it } }
    }

    /**
     * 断开某配置文件的连接。`disconnect(BluetoothDevice)` 是 @SystemApi（编译期
     * 不可见），反射调用；失败不抛，由调用方按最终状态判定。
     */
    private fun disconnectProfile(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): Boolean =
        try {
            proxy.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
                ?.let { it as? Boolean }
                ?: true
        } catch (_: Throwable) {
            false
        }
}
