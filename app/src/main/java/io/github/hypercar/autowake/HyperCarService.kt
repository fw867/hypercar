package io.github.hypercar.autowake

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.hypercar.HyperCarApp
import io.github.hypercar.R

/**
 * 秒连链路的前台保活服务（foregroundServiceType=connectedDevice，manifest 已配）。
 *
 * 职责边界：**只负责前台豁免与持锁**，会话重活全部转交 [AutoStart.handler]。
 *
 * cardeck 两条铁律（内化）：
 *  ① Android 12+ 后台 FGS 启动豁免**没有「蓝牙广播」这一项**；本项目靠 SYSTEM_ALERT_WINDOW
 *     （manifest 已声明）满足官方豁免项。被拦时（用户没授悬浮窗）startForegroundService 抛
 *     ForegroundServiceStartNotAllowedException——只记日志，别崩；用户手动打开 App 连一次即可
 *     （那次有可见 Activity，不受后台启动限制）。
 *  ② 停止用 startService 投递 ACTION_STOP：服务在跑时进程已是前台服务态，允许 startService
 *     （不受后台启动限制）。
 *
 * 循环防护：onStartCommand 置 [isRunning]=true **早于**转交 handler；handler 据此区分
 * 「receiver 首次拉起（isRunning=false→startForegroundService）」与「service 触发会话
 * （isRunning=true→做重活）」，不会形成 receiver↔service 无限循环。停止侧同理：
 * ACTION_STOP 只在 **isRunning 由 true 变 false 的那一次**转交 handler(false)——
 * handler(false) 内部又会投 ACTION_STOP 收摊前台，无此判据就会 handler↔service 互相
 * startService 刷屏（历史 bug）。
 */
class HyperCarService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device = deviceOf(intent)
        when (intent?.action) {
            ACTION_STOP -> {
                // 只在「服务确实在跑」的这一次停止里转交会话层收尾。
                // 会话层的 handler(connected=false) 自己也会投 ACTION_STOP（收摊前台），
                // 若这里无条件回灌 handler，就形成
                //   onStartCommand(STOP) → handler(false) → startService(STOP) → onStartCommand …
                // 的自激循环：每秒数百次 nativeStop + 快照重置 + 两条日志，且与车机是否在
                // 场无关（下车后进程还在循环里刷）。wasRunning 判据把循环卡在第一圈。
                val wasRunning = isRunning
                isRunning = false
                if (device != null && wasRunning) {
                    runCatching { AutoStart.handler?.invoke(applicationContext, device, false) }
                        .onFailure { Log.e(TAG, "转交停止失败", it) }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseLocks()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 必须用显式 connectedDevice 类型：2 参 startForeground 会拾取 manifest 声明的
                // 全部类型，Android 14 对 mediaProjection 校验 AppOps 会直接崩（本服务无投影）。
                startForegroundSafe()
                acquireLocks()
                isRunning = true
                // 转交 handler 触发会话（重活在 handler 内）。receiver 路径已在 handler 里
                // startForegroundService 过；这里 service 已前台，handler 见 isRunning=true 直接做会话。
                if (intent?.action == ACTION_START && device != null) {
                    runCatching { AutoStart.handler?.invoke(applicationContext, device, true) }
                        .onFailure { Log.e(TAG, "触发会话失败", it) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseLocks()
        Log.i(TAG, "前台保活已停止，通知与唤醒锁已释放")
        super.onDestroy()
    }

    /**
     * 5s 内进入前台。
     *
     * **必须带上 microphone 类型**（权限已授权时）：语音助手要手机供麦（AudioRecord），
     * 而 Android 14+ 禁止「只有 connectedDevice 类型的 FGS」在后台录音——表现为
     * AudioRecord 静默返回 0 或直接 SecurityException（2026-10-25 定谳）。
     * 类型与 manifest 的 `connectedDevice|microphone` 对应；失败时退回纯 connectedDevice，
     * 至少保证会话能进前台（麦克风随后会在 AudioRecord 处再报一次原因）。
     */
    private fun startForegroundSafe() {
        val micGranted =
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val types =
            if (android.os.Build.VERSION.SDK_INT >= 30 && micGranted) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground（含 microphone 类型）失败：${t.message}，退回 connectedDevice")
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground 失败：${t2.message}")
            }
        }
    }

    /** 息屏/WiFi 保活锁：CPU 唤醒锁维持会话线程与心跳；WiFi 低延迟锁避免激进省电断流。 */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperCar:session").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "HyperCar:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    /** 渠道已在 HyperCarApp 创建（CHANNEL_LINK + R.string.notification_channel_link）。 */
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, HyperCarApp.CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_link_title))
            .setContentText("正在保持与车机的连接")
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent?): BluetoothDevice? {
        val i = intent ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            i.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            i.getParcelableExtra(EXTRA_DEVICE)
        }
    }

    companion object {
        private const val TAG = "HyperCarService"
        private const val NOTIFICATION_ID = 1

        /** 上车：车机 ACL 连上时（经 AutoStart.handler）拉起本服务。 */
        const val ACTION_START = "io.github.hypercar.action.AUTO_START"

        /** 下车：ACL 断开时（经 startService）收摊。 */
        const val ACTION_STOP = "io.github.hypercar.action.AUTO_STOP"

        /** 随 intent 传递的目标车机（BluetoothDevice），供 service 转交 handler 用。 */
        const val EXTRA_DEVICE = "io.github.hypercar.extra.DEVICE"

        /** 服务是否在前台运行（handler 据此区分「拉起服务」与「触发会话」，防循环）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(context: Context, device: BluetoothDevice): Intent =
            Intent(context, HyperCarService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE, device)

        fun stopIntent(context: Context, device: BluetoothDevice?): Intent =
            Intent(context, HyperCarService::class.java)
                .setAction(ACTION_STOP)
                .let { if (device != null) it.putExtra(EXTRA_DEVICE, device) else it }
    }
}
