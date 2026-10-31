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
 * 自动待机与当前会话的前台保活。待机只保持监听，不持 CPU / WiFi 锁；
 * 当前会话（含自动模式关闭时的手动连接）独立持锁。
 * ACTION_START 用于 ACL 唤醒，ACTION_LISTEN 只更新待机通知，ACTION_SESSION 保活当前会话。
 * 停止不回灌 ACL 事件，避免服务和会话层相互触发。
 */
class HyperCarService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var listeningOnly = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val automatic = intent?.action != ACTION_SESSION
        val autoEnabled = io.github.hypercar.settings.SettingsStore.autoConnect
        if (intent?.action == ACTION_STOP ||
            (automatic && !autoEnabled && !io.github.hypercar.session.HyperCarRepo.hasCurrentSession)) {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseLocks()
            stopSelf()
            return START_NOT_STICKY
        }
        if (automatic && !autoEnabled) return START_NOT_STICKY
        listeningOnly = intent == null || intent.action == ACTION_LISTEN
        startForegroundSafe()
        if (listeningOnly) releaseLocks() else acquireLocks()
        isRunning = true
        val device = deviceOf(intent)
        if (intent?.action == ACTION_START && device != null) {
            runCatching { AutoStart.handler?.invoke(applicationContext, device, true) }
                .onFailure { Log.e(TAG, "触发会话失败", it) }
        } else if (intent == null && autoEnabled) {
            // 系统回收后只恢复自动待机，不能凭空重建一轮手动会话。
            io.github.hypercar.session.HyperCarRepo.startAutomaticListening(applicationContext)
        }
        return if (autoEnabled) START_STICKY else START_NOT_STICKY
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
    private fun buildNotification(): Notification {
        // **点通知回 App 首页**（2026-10-31）：之前这条例行通知点了没反应。
        // 用 MainActivity 的 launcher intent，FLAG_ACTIVITY_SINGLE_TOP+RESET_TASK_IF_NEEDED
        // 保证不叠任务栈、回到已存在的实例（不会重建界面丢状态）。
        val open = Intent(this, io.github.hypercar.ui.MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
        }
        val pending = android.app.PendingIntent.getActivity(
            this,
            0,
            open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, HyperCarApp.CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_link_title))
            .setContentText(if (listeningOnly) "正在监听车机，等待自动连接" else "正在保持与车机的连接")
            .setContentIntent(pending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

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
        private const val ACTION_LISTEN = "io.github.hypercar.action.LISTEN"
        private const val ACTION_SESSION = "io.github.hypercar.action.SESSION"

        /** 下车：ACL 断开时（经 startService）收摊。 */
        const val ACTION_STOP = "io.github.hypercar.action.AUTO_STOP"

        /** 随 intent 传递的目标车机（BluetoothDevice），供 service 转交 handler 用。 */
        const val EXTRA_DEVICE = "io.github.hypercar.extra.DEVICE"

        /** 服务是否在前台运行（handler 据此区分「拉起服务」与「触发会话」，防循环）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun listenIntent(context: Context): Intent =
            Intent(context, HyperCarService::class.java).setAction(ACTION_LISTEN)

        fun sessionIntent(context: Context): Intent =
            Intent(context, HyperCarService::class.java).setAction(ACTION_SESSION)

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
