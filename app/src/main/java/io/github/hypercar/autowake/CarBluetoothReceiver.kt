package io.github.hypercar.autowake

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.hypercar.bluetooth.BtProfiles
import io.github.hypercar.bluetooth.CarAclPolicy
import io.github.hypercar.settings.SettingsStore

/**
 * 上车秒连第一环：清单静态接收车机蓝牙 ACL 广播，冷启动进程并转交会话层。
 *
 * 为什么静态注册能拉起被杀进程：`ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` 在
 * 隐式广播豁免清单里（Android 8+ 对清单隐式广播限制的例外），车机蓝牙一连上系统就把本进程
 * 拉起来，不需要任何前台 Activity。
 *
 * 目标过滤：优先用户选定的目标车机（SettingsStore.targetCarMac），否则按蓝牙名关键词
 * （BMW/MINI/宝马）兜底——耳机/手表不会误触发。拿不到 address/name（缺 BLUETOOTH_CONNECT）
 * 无法判断是否目标车机，直接放弃并记日志。
 *
 * 「上车自动连接」开关（SettingsStore.autoConnect）每次广播都读（进程可能刚冷启动，无内存缓存）。
 *
 * 注：能否 startForegroundService 由 [AutoStart.handler]（会话层）决定并兜底捕获系统拦截，
 * 本接收器只负责过滤与转交，不直接碰 Service——职责解耦，便于会话层替换策略。
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val connected = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val app = context.applicationContext
        // 每次都读开关：进程可能刚被广播冷启动，无内存缓存可依赖
        if (!SettingsStore.autoConnect) return

        val device = deviceOf(intent)
        if (device == null) {
            Log.i(TAG, "ACL 广播缺少 EXTRA_DEVICE——放弃")
            return
        }
        val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
        val deviceType = runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        if (!CarAclPolicy.isClassicCarLink(transport, deviceType)) {
            Log.i(TAG, "忽略 LE ACL：数字钥匙/近场连接不触发或结束 CarPlay")
            return
        }
        // address/name 都要 BLUETOOTH_CONNECT；拿不到（或拿到占位 MAC）无法判断是否目标车机
        val address = runCatching { device.address }.getOrNull()
        val name = runCatching { device.name }.getOrNull()
        if (address == null || address.equals(PLACEHOLDER_MAC, ignoreCase = true) || name == null) {
            Log.i(TAG, "拿不到蓝牙 name/address（缺 BLUETOOTH_CONNECT？）——放弃")
            return
        }
        if (!isTargetCar(app, address, name)) {
            Log.i(TAG, "忽略非车机蓝牙 $name（$address）")
            return
        }
        // iAP2 的「只配对、不连接」会主动断车机音频配置文件（个别 ROM 退到设备级断开），
        // 系统随即广播 ACL 断开——那不是下车，是**我们自己干的**。放行会立刻拆掉刚建的
        // 会话，并与前台服务的 ACTION_STOP 串成刷屏回环（历史故障）。
        if (!connected && BtProfiles.isSelfDrop(address)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSelfDropLogAt > SELF_DROP_LOG_THROTTLE_MILLIS) {
                lastSelfDropLogAt = now
                Log.i(TAG, "忽略 ACL 断开（$name）：iAP2 建链主动断开音频配置文件所致，不按「下车」处理")
            }
            return
        }
        val handler = AutoStart.handler
        if (handler == null) {
            Log.i(TAG, "AutoStart.handler 未注入——放弃")
            return
        }
        // 转交会话层：connected 区分上车/下车。handler 内部决定 startForegroundService/stop，
        // 并做会话重活。这里只兜底捕获异常（如 ForegroundServiceStartNotAllowedException——
        // Android 12+ 后台 FGS 豁免靠 SYSTEM_ALERT_WINDOW，用户没授悬浮窗时会被拦，只记日志不崩）。
        runCatching { handler(app, device, connected) }
            .onFailure { t ->
                Log.e(
                    TAG,
                    "转交会话层失败（${t.javaClass.simpleName}: ${t.message}）——" +
                        "若是 FGS 启动受限，请授予「显示在其他应用上层」或手动打开 App 连一次",
                )
            }
    }

    /** 目标车机判定：选定 MAC 优先，否则按蓝牙名关键词兜底。 */
    private fun isTargetCar(context: Context, address: String, name: String): Boolean {
        val targetMac = SettingsStore.targetCarMac
        if (!targetMac.isNullOrBlank()) return address.equals(targetMac, ignoreCase = true)
        return CAR_KEYWORDS.any { name.contains(it, ignoreCase = true) }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "CarBluetoothReceiver"
        const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

        /** 「自己断开导致的 ACL 断开」日志节流（出站循环每 3s 一轮，别一行一行刷）。 */
        const val SELF_DROP_LOG_THROTTLE_MILLIS = 30_000L

        /** 未选目标车机时的兜底：常见车机蓝牙名关键词（真车 BMW/MINI/宝马）。 */
        val CAR_KEYWORDS = listOf("BMW", "MINI", "宝马")

        /** 日志节流时间戳。放 companion：清单接收器每次广播都是新实例，实例字段记不住。 */
        @Volatile
        var lastSelfDropLogAt = 0L
    }
}
