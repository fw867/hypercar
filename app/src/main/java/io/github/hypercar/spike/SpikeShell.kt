package io.github.hypercar.spike

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import android.view.Surface
import android.view.MotionEvent
import io.github.hypercar.BuildConfig
import io.github.hypercar.core.RustCoreBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import java.io.File

/**
 * SPIKE-P0：绑定 Shizuku UserService（shell 进程），在那里建 D2。
 *
 * Shizuku 绑定事务在后台执行，宿主确认后在主线程发布可用的 shell 服务。
 */
object SpikeShell {
    internal const val MEDIA_CARD_DISPLAY_NAME = "hypercar-media-card"

    @Volatile
    private var service: ISpikeShell? = null
    @Volatile private var serviceBroker: IBinder? = null

    private var binding: UserServiceBinding? = null
    private var serviceDeath: Pair<IBinder, IBinder.DeathRecipient>? = null
    private var outputJournalDelivery: OutputJournalDelivery? = null
    private val outputJournalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()
    private val bindingState = Any()
    private val bindingRpcs = UserServiceBindings()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val wifiProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @OptIn(ExperimentalCoroutinesApi::class)
    private val callAudioProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private data class CallDiagnosticBinding(val binder: IBinder, val delivery: CallAudioDiagnosticDelivery)
    private val callDiagnosticBindingLock = Any()
    private var callDiagnosticBinding: CallDiagnosticBinding? = null
    private val processHost = Binder()
    private val hostProbeLock = Any()
    private var hostProbe: Pair<IBinder, Deferred<Boolean>>? = null
    private val displayOwner = Binder()
    @Volatile var isBinding: Boolean = false
        private set

    private data class ConnectedBinding(val ticket: UserServiceBinding, val service: ISpikeShell, val broker: IBinder)

    private fun currentBinding(): ConnectedBinding? = synchronized(bindingState) {
        val ticket = binding ?: return null
        val bound = service ?: return null
        val broker = serviceBroker ?: return null
        val binder = bound.asBinder()
        if (serviceDeath?.first != binder || !binder.isBinderAlive || !broker.isBinderAlive || broker != Shizuku.getBinder())
            return null
        ConnectedBinding(ticket, bound, broker)
    }

    internal val userServiceBinder: IBinder? get() = currentBinding()?.service?.asBinder()

    fun isConnected(): Boolean = userServiceBinder != null

    fun startAudioCapture(owner: IBinder): android.os.ParcelFileDescriptor? = service?.startAudioCapture(owner)
    fun startSplitAudioCapture(owner: IBinder, navigationUid: Int): Array<android.os.ParcelFileDescriptor>? =
        service?.startSplitAudioCapture(owner, navigationUid)
    fun stopAudioCapture() { service?.stopAudioCapture() }
    internal fun pollCallAudioIdentity(verify: Boolean, log: (String) -> Unit): CallAudioBridgeIdentity? {
        val bound = (if (verify) requestOutputJournal() else service) ?: return null
        return pollCallAudioIdentity(bound, verify, log)
    }

    private fun pollCallAudioIdentity(bound: ISpikeShell, verify: Boolean, log: (String) -> Unit): CallAudioBridgeIdentity? {
        val delivery = synchronized(callDiagnosticBindingLock) {
            if (service !== bound) return null
            val binder = bound.asBinder()
            val current = callDiagnosticBinding?.takeIf { it.binder == binder }
                ?: CallDiagnosticBinding(binder, CallAudioDiagnosticDelivery()).also { callDiagnosticBinding = it }
            current.delivery
        }
        val snapshot = bound.pollCallAudioIdentity(verify, delivery.publishedThrough) ?: return null
        if (service !== bound || snapshot.size < 3) return null
        val firstSequence = snapshot[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        delivery.publish(CallAudioDiagnosticBatch(firstSequence, snapshot.drop(3))) { log("CALL-AUDIO: $it") }
        if (service !== bound || snapshot[0].isEmpty() || snapshot[1].isEmpty()) return null
        return CallAudioBridgeIdentity(snapshot[0], snapshot[1])
    }

    internal suspend fun flushCallAudioDiagnosticsForExport(log: (String) -> Unit) {
        val bound = service ?: return
        val read = callAudioProbeScope.async {
            runCatching { pollCallAudioIdentity(bound, verify = false, log) }
        }
        try { withTimeoutOrNull(500L) { read.await() } }
        finally { read.cancel() }
    }

    fun callAudioStatus(): String? = service?.callAudioStatus()
    suspend fun awaitCallAudioOfferStatus(token: Long, offer: Long): String? {
        val bound = service ?: return null
        // Await the result separately so an uninterruptible Binder call cannot extend the deadline.
        val read = callAudioProbeScope.async {
            while (service === bound) {
                val status = runCatching { bound.callAudioOfferStatus(token, offer) }.getOrElse { return@async null }
                if (status != null) return@async status
                delay(100L)
            }
            null
        }
        return try { withTimeoutOrNull(500L) { read.await() } }
        finally { read.cancel() }
    }
    fun callAudioOpenNeedsIdentity(): Boolean = runCatching {
        callAudioStatus()?.endsWith(" endpoint=${ShellCallAudio.OPEN_IDENTITY_UNCONFIRMED}") == true
    }.getOrDefault(false)

    fun openCallAudio(owner: IBinder, token: Long, offer: Long, callId: String, rate: Int,
                      route: ICallAudioRoute): android.os.ParcelFileDescriptor? =
        requestOutputJournal()?.openCallAudio(owner, token, offer, callId, rate, route)
    fun closeCallAudio(token: Long) { service?.closeCallAudio(token) }
    fun revokeCallAudio(token: Long, offer: Long) { service?.revokeCallAudio(token, offer) }
    fun audioCaptureStatus(): String = service?.audioCaptureStatus() ?: "shell disconnected"
    fun startBluetoothDiscoverable(owner: IBinder, token: String, seconds: Int): String =
        call("startBluetoothDiscoverable") { it.startBluetoothDiscoverable(owner, token, seconds) }
    fun stopBluetoothDiscoverable(token: String): String =
        call("stopBluetoothDiscoverable") { it.stopBluetoothDiscoverable(token) }
    fun startWifiSelection(owner: IBinder, token: String, ssid: String, bssid: String, uid: Int, protocolFrequency: Int): String =
        call("startWifiSelection") { it.startWifiSelection(owner, token, ssid, bssid, uid, protocolFrequency) }
    fun stopWifiSelection(token: String): String =
        call("stopWifiSelection") { it.stopWifiSelection(token) }

    suspend fun hasSavedWifiWithAutojoin(ssid: String, remainingMs: Long): Boolean? =
        readWifiState(remainingMs.coerceAtMost(200L)) { it.hasSavedWifiWithAutojoin(ssid) }

    suspend fun isWifiStationJoiningOrConnected(ssid: String): Boolean =
        readWifiState(200L) { it.isWifiStationJoiningOrConnected(ssid) } == true

    private suspend fun readWifiState(waitMs: Long, query: (ISpikeShell) -> Boolean): Boolean? {
        val bound = service ?: return null
        if (waitMs <= 0) return null
        // Binder reads cannot be interrupted. A stalled read must not hold up a new endpoint.
        val read = wifiProbeScope.async { runCatching { query(bound) }.getOrNull() }
        return try { withTimeoutOrNull(waitMs) { read.await() } }
        finally { read.cancel() }
    }

    fun updateDisplaySurface(id: Int, surface: Surface?, width: Int, height: Int, dpi: Int): String =
        service?.updateDisplaySurface(id, surface, width, height, dpi) ?: "ERR shell disconnected"

    fun touchEvent(displayId: Int, event: MotionEvent): String =
        call("touchEvent") { it.touchEvent(displayId, event) }

    fun prepareProjection(retainedMapName: String): String =
        call("prepareProjection") { it.prepareProjection(retainedMapName) }

    internal fun captureInstrumentMap(id: Int, w: Int, h: Int, maxW: Int, maxH: Int): android.graphics.Bitmap? {
        val shell = service ?: return null
        if (maxW !in 1..960 || maxH !in 1..540) return null
        return runCatching {
            android.os.SharedMemory.create("hypercar-instrument-map", maxW * maxH * 4).use { memory ->
                val size = shell.captureInstrumentMap(displayOwner, id, w, h, maxW, maxH, memory) ?: return null
                if (size.size != 2 || size[0] !in 1..maxW || size[1] !in 1..maxH) return null
                val buffer = memory.mapReadOnly()
                try {
                    val bitmap = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888)
                    try { bitmap.apply { copyPixelsFromBuffer(buffer) } }
                    catch (error: Throwable) { bitmap.recycle(); throw error }
                } finally { android.os.SharedMemory.unmap(buffer) }
            }
        }.getOrNull()
    }

    /** 绑定（幂等）。返回人话结果，写进 spike 报告。 */
    suspend fun connect(context: Context): String {
        return bindMutex.withLock {
            if (confirmCurrentHost(::currentBinding) { confirmHost(it.service, 750) }) {
                requestOutputJournal()
                return@withLock "已连接"
            }
            disconnect()
            isBinding = true
            try {
                val result = connectUnlocked(context)
                if (result != HOST_UNAVAILABLE) return@withLock result
                // The idle process can reject transactions just before Shizuku sees its death.
                disconnect()
                delay(800)
                connectUnlocked(context)
            } catch (cancelled: CancellationException) {
                disconnect()
                throw cancelled
            } finally {
                if (!isConnected()) disconnect()
                isBinding = false
            }
        }
    }

    private suspend fun confirmHost(bound: ISpikeShell, timeoutMs: Long): Boolean {
        val binder = bound.asBinder()
        val probe = synchronized(hostProbeLock) {
            hostProbe?.takeIf { it.first == binder && it.second.isActive }?.second ?: outputJournalScope.async {
                runCatching { bound.attachHost(processHost) }.getOrDefault(false)
            }.also { hostProbe = binder to it }
        }
        // The independent probe may still be inside Binder when the waiting connection expires.
        return awaitHostAttachment(probe, timeoutMs)
    }

    private suspend fun connectUnlocked(context: Context): String {
        val broker = Shizuku.getBinder()?.takeIf { it.isBinderAlive } ?: return "Shizuku 未运行"
        val remote = IShizukuService.Stub.asInterface(broker)
        val deferred = CompletableDeferred<String>()
        val attempt = UserServiceBinding()
        val conn = object : IShizukuServiceConnection.Stub() {
            private var callbackVersion = 0L

            override fun connected(binder: IBinder?) {
                if (binder == null) {
                    died()
                    return
                }
                val version = synchronized(bindingState) {
                    if (binding !== attempt) return
                    clearServiceDeath()
                    if (!broker.isBinderAlive || broker != Shizuku.getBinder()) {
                        died()
                        return
                    }
                    val death = IBinder.DeathRecipient {
                        synchronized(bindingState) { if (serviceDeath?.first == binder) died() }
                    }
                    try { binder.linkToDeath(death, 0) }
                    catch (_: android.os.RemoteException) { died(); return }
                    serviceDeath = binder to death
                    ++callbackVersion
                }
                val bound = service?.takeIf { it.asBinder() == binder } ?: ISpikeShell.Stub.asInterface(binder)
                outputJournalScope.launch {
                    if (!attempt.finished.await()) return@launch
                    val attached = confirmHost(bound, 4_000)
                    withContext(Dispatchers.Main.immediate) {
                        synchronized(bindingState) {
                            if (binding !== attempt || callbackVersion != version) return@withContext
                            if (!attached || !binder.isBinderAlive || !broker.isBinderAlive || broker != Shizuku.getBinder()) {
                                deferred.complete(HOST_UNAVAILABLE)
                                return@withContext
                            }
                            if (service !== bound || serviceBroker != broker) {
                                outputJournalDelivery?.close()
                                outputJournalDelivery = newOutputJournalDelivery(context.applicationContext, bound, broker)
                            }
                            serviceBroker = broker
                            service = bound
                            outputJournalDelivery?.request()
                            deferred.complete("connected")
                        }
                    }
                }
            }

            override fun died() {
                synchronized(bindingState) {
                    if (binding !== attempt) return
                    callbackVersion++
                    clearServiceDeath()
                    service = null
                    serviceBroker = null
                    outputJournalDelivery?.close()
                    outputJournalDelivery = null
                    deferred.complete(HOST_UNAVAILABLE)
                }
                SpikeReport.add("SHELL", "user service 断开")
            }
        }
        val component = ComponentName(context.packageName, SpikeShellService::class.java.name)
        val identity = Bundle().apply {
            putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, component)
            userServiceTag(Process.myUid())?.let { putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, it) }
        }
        val options = Bundle(identity).apply {
            putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, false)
            putString(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME, "spike")
            putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, BuildConfig.DEBUG)
            // UserService revisions also distinguish local installs sharing the same versionCode.
            putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, BuildConfig.VERSION_CODE + 29)
        }
        synchronized(bindingState) { binding = attempt }
        SpikeReport.add("SHELL", "调用 bindUserService → ${context.packageName}:spike")
        // The SDK caches callbacks by component and unbind(false) clears the whole set. Use the
        // same AIDL and service identity, but keep both transactions on this broker and callback.
        var canDetach = false
        bindingRpcs.start(broker, attempt, bind = {
            runCatching {
                if (Shizuku.getBinder() != broker) return@runCatching false
                val version = Shizuku.getVersion()
                // Match the pinned 13.1.5 SDK's unbind(false) gate. Earlier servers
                // do not support detaching just this callback.
                @android.annotation.SuppressLint("RestrictedApi")
                val supportsDetach = version >= 14 || (version == 13 && Shizuku.getServerPatchVersion() >= 4)
                if (attempt.closed.isCompleted || Shizuku.getBinder() != broker) return@runCatching false
                canDetach = supportsDetach
                remote.addUserService(conn, options)
                true
            }
                .onFailure { SpikeReport.add("SHELL", "bindUserService 异常: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
        }, unbind = {
            if (canDetach && broker.isBinderAlive) runCatching {
                val remove = Bundle(identity).apply {
                    putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE, false)
                }
                remote.removeUserService(conn, remove)
            }
        })
        val result = awaitUserServiceBinding(attempt.finished, deferred, 12_000)
        if (result != "connected") disconnect()
        SpikeReport.add("SHELL", "bind 结果: $result")
        return result
    }

    private fun requestOutputJournal(): ISpikeShell? = synchronized(bindingState) {
        outputJournalDelivery?.request()
        service
    }

    private fun newOutputJournalDelivery(context: Context, bound: ISpikeShell, broker: IBinder): OutputJournalDelivery {
        var watchingBroker = false
        return OutputJournalDelivery(outputJournalScope, deliver = {
            runCatching {
                if (service !== bound || serviceBroker != broker || !broker.isBinderAlive) return@runCatching false
                if (!watchingBroker) watchingBroker = bound.attachCallAudioBroker(broker)
                if (!watchingBroker) return@runCatching false
                if (bound.hasCallOutputJournal()) return@runCatching true
                val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
                val file = File(context.noBackupFilesDir, "call-output-recovery")
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
                    .use { descriptor ->
                        // Binder transfers the locked open file description, and the shell retains its copy.
                        RustCoreBridge.nativeTryCallOutputJournalLock(descriptor.fd) &&
                            bound.setCallOutputJournal(descriptor, boot)
                    }
            }.getOrDefault(false)
        }, onWaiting = { SpikeReport.add("CALL-AUDIO", "output recovery journal waiting for shell acknowledgement") })
    }

    fun createDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int = 0,
        name: String = "hypercar-d2",
    ): String = runCatching {
        service?.createDisplay(displayOwner, surface, width, height, dpi, flags, name) ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    fun releaseDisplay(): String = runCatching {
        service?.releaseDisplay() ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    internal data class MediaCardDisplay(val id: Int, val owner: ISpikeShell)

    internal fun createMediaCardDisplay(surface: Surface, width: Int, height: Int, dpi: Int) = runCatching {
        val owner = checkNotNull(service) { "shell disconnected" }
        val result = owner.createDisplay(displayOwner, surface, width, height, dpi, 0, MEDIA_CARD_DISPLAY_NAME)
        val id = result.removePrefix("OK id=").substringBefore(' ').toIntOrNull()
        check(id != null && id > 0) { result }
        MediaCardDisplay(id, owner)
    }

    /** P2：只释放指定副屏（D1/D2 并存）。 */
    fun releaseDisplay(displayId: Int): String = runCatching {
        service?.releaseDisplayById(displayId) ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    fun status(): String = runCatching {
        service?.status() ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    /** 真息屏：物理面板断电（系统保持唤醒），false=断电，true=点亮。 */
    fun setPhysicalScreenPower(on: Boolean): Boolean = runCatching {
        service?.setPhysicalScreenPower(on) ?: false
    }.getOrDefault(false)

    fun motion(displayId: Int, action: Int, x: Int, y: Int, downTimeMs: Long): String =
        call("motion") { it.motion(displayId, action, x, y, downTimeMs) }

    fun tap(displayId: Int, x: Int, y: Int): String =
        call("tap") { it.tap(displayId, x, y) }

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        call("swipe") { it.swipe(displayId, x1, y1, x2, y2, durationMs) }

    fun pinch(displayId: Int, cx: Int, cy: Int, from: Int, to: Int, steps: Int): String =
        call("pinch") { it.pinch(displayId, cx, cy, from, to, steps) }

    fun inputState(): String = call("inputState") { it.inputState() }

    fun readDisplayImePolicy(displayId: Int): String = call("readDisplayImePolicy") { it.readDisplayImePolicy(displayId) }

    fun consoleKey(displayId: Int, keyCode: Int, metaState: Int): String =
        call("consoleKey") { it.consoleKey(displayId, keyCode, metaState) }

    private inline fun call(name: String, block: (ISpikeShell) -> String): String =
        runCatching { service?.let(block) ?: "ERR 未连接" }.getOrElse {
            "ERR $name ${it.javaClass.simpleName}: ${it.message}"
        }

    // Called under bindingState. Drop the death registration even when the remote process survives a retry.
    private fun clearServiceDeath() {
        serviceDeath?.let { (binder, death) -> runCatching { binder.unlinkToDeath(death, 0) } }
        serviceDeath = null
    }

    private fun disconnect() {
        synchronized(bindingState) {
            binding?.close()
            binding = null
            clearServiceDeath()
            service = null
            serviceBroker = null
            outputJournalDelivery?.close()
            outputJournalDelivery = null
        }
    }

    private const val HOST_UNAVAILABLE = "宿主 Binder 注册失败"
}

// Shizuku keys its records by package and tag (class name by default), across all Android users.
internal fun userServiceTag(uid: Int): String? {
    val user = uid / 100_000 // Android UserHandle.PER_USER_RANGE
    return if (user == 0) null else "spike-u$user"
}

internal class OutputJournalDelivery(
    private val scope: CoroutineScope,
    private val deliver: () -> Boolean,
    private val onWaiting: () -> Unit,
) {
    @Volatile private var finished = false
    private var worker: Job? = null

    @Synchronized fun request() {
        if (finished || worker?.isActive == true) return
        worker = scope.launch {
            // A persistent OFD contention or filesystem failure must let an unused shell retire.
            for (waitMs in longArrayOf(0, 250, 500, 1_000, 1_000)) {
                delay(waitMs)
                if (deliver()) {
                    finished = true
                    return@launch
                }
                if (waitMs == 0L) onWaiting()
            }
        }
    }

    @Synchronized fun close() {
        finished = true
        worker?.cancel()
    }
}

internal suspend fun awaitHostAttachment(probe: Deferred<Boolean>, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { probe.await() } == true

internal suspend fun <T : Any> confirmCurrentHost(current: () -> T?, confirm: suspend (T) -> Boolean): Boolean {
    val bound = current() ?: return false
    return confirm(bound) && current() == bound
}

internal suspend fun awaitUserServiceBinding(request: Deferred<Boolean>, callback: Deferred<String>, timeoutMs: Long): String =
    withTimeoutOrNull(timeoutMs) {
        if (request.await()) callback.await() else "bindUserService 调用失败"
    } ?: "绑定超时（12s）"
