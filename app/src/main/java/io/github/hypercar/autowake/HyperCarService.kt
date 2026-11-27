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
import io.github.hypercar.session.HyperCarRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var identityOnly = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val projectionAllowed = MutableStateFlow(false)
    @Volatile private var keeperGeneration: Any? = null
    private var foregroundTypes = 0

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            var keeper: Job? = null
            combine(projectionAllowed, HyperCarRepo.projectionOwner) { allowed, owner -> owner.takeIf { allowed } }
                .distinctUntilChanged().collect { owner ->
                val generation = owner?.let { Any() }
                keeperGeneration = generation
                keeper?.cancel()
                if (projectionAllowed.value && hasPlaybackForeground()) startForegroundSafe()
                keeper = if (owner != null && generation != null) serviceScope.launch {
                    coroutineScope {
                        launch { watchHeartbeat(owner, generation, "main") }
                        launch(Dispatchers.Default) { watchHeartbeat(owner, generation, "worker") }
                        ProjectionPlayback.withTrackSlot { holdProjectionPlayback(owner, generation) }
                    }
                } else null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val automatic = intent?.action != ACTION_SESSION
        val autoEnabled = io.github.hypercar.settings.SettingsStore.autoConnect
        val paused = autoEnabled && io.github.hypercar.session.HyperCarRepo.automaticPauseRemaining > 0
        val requestedIdentity = intent?.action == ACTION_IDENTITY || (intent == null && paused)
        if (automatic && io.github.hypercar.session.HyperCarRepo.hasCurrentSession &&
            (requestedIdentity || intent == null || intent.action == ACTION_LISTEN)) return START_STICKY
        if (automatic && paused) {
            if (io.github.hypercar.session.HyperCarRepo.hasCurrentSession) return START_NOT_STICKY
            if (!requestedIdentity) {
                io.github.hypercar.session.HyperCarRepo.startAutomaticListening(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseLocks()
                isRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        identityOnly = requestedIdentity && paused
        if (automatic && autoEnabled && !identityOnly && !io.github.hypercar.session.HyperCarRepo.hasCurrentSession) {
            val readiness = io.github.hypercar.session.StartupRequirements.read(this)
            if (readiness.automaticStandbyIssues.isNotEmpty()) {
                if (readiness.bluetoothEnabled) io.github.hypercar.session.HyperCarRepo.setAutoConnect(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseLocks()
                isRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_COMPANION && !CompanionWakePolicy.accepts(
                autoEnabled, io.github.hypercar.settings.SettingsStore.targetCarMac, intent.getStringExtra(EXTRA_ADDRESS),
            )) {
            if (!io.github.hypercar.session.HyperCarRepo.hasCurrentSession) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP ||
            (automatic && !autoEnabled && !io.github.hypercar.session.HyperCarRepo.hasCurrentSession)) {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseLocks()
            stopSelf()
            return START_NOT_STICKY
        }
        if (automatic && !autoEnabled) return START_NOT_STICKY
        listeningOnly = requestedIdentity || intent == null || intent.action == ACTION_LISTEN
        startForegroundSafe(playback = !listeningOnly && hasPlaybackForeground())
        projectionAllowed.value = !listeningOnly && foregroundTypes != 0
        if (listeningOnly) releaseLocks() else acquireLocks()
        isRunning = true
        val device = deviceOf(intent)
        if (intent?.action == ACTION_START && device != null) {
            runCatching {
                val observedAt = intent.getLongExtra(EXTRA_ACL_OBSERVED_AT, android.os.SystemClock.elapsedRealtime())
                AutoStart.handler?.invoke(applicationContext, device, true, true, observedAt)
            }
                .onFailure { Log.e(TAG, "触发会话失败", it) }
        } else if (intent?.action == ACTION_COMPANION) {
            intent.getStringExtra(EXTRA_ADDRESS)?.let {
                io.github.hypercar.session.HyperCarRepo.connectFromCompanion(applicationContext, it)
            }
        } else if (autoEnabled && (intent == null || (requestedIdentity && !paused))) {
            // 系统回收后只恢复自动待机，不能凭空重建一轮手动会话。
            io.github.hypercar.session.HyperCarRepo.startAutomaticListening(applicationContext)
        }
        return if (autoEnabled) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        projectionAllowed.value = false
        keeperGeneration = null
        serviceScope.cancel()
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
    private fun startForegroundSafe(playback: Boolean = false) {
        val micGranted = !listeningOnly &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notification = buildNotification()
        for (types in ProjectionForegroundPolicy.types(micGranted, playback)) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
                foregroundTypes = types
                return
            } catch (error: RuntimeException) {
                Log.w(TAG, "startForeground types=0x${types.toString(16)} failed", error)
            }
        }
    }

    private fun hasPlaybackForeground() = foregroundTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0

    private fun projectionCurrent(owner: Long, generation: Any) = keeperGeneration === generation &&
        projectionAllowed.value && HyperCarRepo.projectionOwner.value == owner

    private suspend fun holdProjectionPlayback(owner: Long, generation: Any) = withContext(Dispatchers.IO) {
        if (!projectionCurrent(owner, generation)) return@withContext
        val track = runCatching { ProjectionPlayback.start { projectionCurrent(owner, generation) } }.getOrElse {
            HyperCarRepo.log("KEEPER: 静音音轨启动失败 ${it.javaClass.simpleName}: ${it.message}")
            return@withContext
        } ?: return@withContext
        try {
            val promoted = withContext(Dispatchers.Main.immediate) {
                if (!projectionCurrent(owner, generation)) false
                else {
                    startForegroundSafe(playback = true)
                    hasPlaybackForeground()
                }
            }
            if (promoted) {
                HyperCarRepo.log("KEEPER: 投屏静音音轨已开始")
                awaitCancellation()
            } else {
                HyperCarRepo.log("KEEPER: 投屏静音音轨未获前台播放类型")
            }
        } finally {
            ProjectionPlayback.releaseAfterForeground(
                restoreForeground = {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (projectionCurrent(owner, generation) && hasPlaybackForeground()) startForegroundSafe()
                    }
                },
                releaseTrack = {
                    runCatching { track.release() }
                        .onSuccess { HyperCarRepo.log("KEEPER: 投屏静音音轨已释放") }
                        .onFailure { HyperCarRepo.log("KEEPER: 投屏静音音轨释放失败 ${it.javaClass.simpleName}") }
                },
            )
        }
    }

    private suspend fun watchHeartbeat(owner: Long, generation: Any, scheduler: String) = ProjectionHeartbeat.monitor { gap ->
        serviceScope.launch(Dispatchers.IO) {
            if (projectionCurrent(owner, generation))
                HyperCarRepo.log("FREEZE: engine=$owner scheduler=$scheduler elapsed_gap_ms=${gap.elapsedMs} uptime_gap_ms=${gap.uptimeMs} suspend_ms=${gap.suspendMs}")
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
        projectionAllowed.value = false
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
            .setContentText(when {
                identityOnly -> "iPhone 身份已保留，自动连接暂停中"
                listeningOnly -> "正在监听车机，等待自动连接"
                else -> "正在保持与车机的连接"
            })
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
        private const val ACTION_IDENTITY = "io.github.hypercar.action.IDENTITY_ONLY"
        private const val ACTION_LISTEN = "io.github.hypercar.action.LISTEN"
        private const val ACTION_SESSION = "io.github.hypercar.action.SESSION"
        private const val ACTION_COMPANION = "io.github.hypercar.action.COMPANION"
        private const val EXTRA_ADDRESS = "io.github.hypercar.extra.COMPANION_ADDRESS"

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

        fun identityIntent(context: Context): Intent =
            Intent(context, HyperCarService::class.java).setAction(ACTION_IDENTITY)

        fun sessionIntent(context: Context): Intent =
            Intent(context, HyperCarService::class.java).setAction(ACTION_SESSION)

        fun companionIntent(context: Context, address: String): Intent =
            Intent(context, HyperCarService::class.java).setAction(ACTION_COMPANION).putExtra(EXTRA_ADDRESS, address)

        private const val EXTRA_ACL_OBSERVED_AT = "io.github.hypercar.extra.ACL_OBSERVED_AT"

        fun startIntent(context: Context, device: BluetoothDevice, observedAt: Long): Intent =
            Intent(context, HyperCarService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE, device)
                .putExtra(EXTRA_ACL_OBSERVED_AT, observedAt)

        fun stopIntent(context: Context, device: BluetoothDevice?): Intent =
            Intent(context, HyperCarService::class.java)
                .setAction(ACTION_STOP)
                .let { if (device != null) it.putExtra(EXTRA_DEVICE, device) else it }
    }
}
