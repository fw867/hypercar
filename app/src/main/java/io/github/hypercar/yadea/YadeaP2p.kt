package io.github.hypercar.yadea

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.yadea.YadeaPendingConnectCleanup.Cancellation
import io.github.hypercar.yadea.YadeaPendingConnectCleanup.Group
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class YadeaP2pPeer(val name: String, val address: String)

@SuppressLint("MissingPermission")
internal class YadeaP2p(context: Context) : Closeable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupDeadline = YadeaCleanupDeadline(CLEANUP_TIMEOUT_MS, SystemClock::elapsedRealtime)
    private val cleanupFinished = CompletableDeferred<Boolean>()
    private val peerChanges = Channel<Unit>(Channel.CONFLATED)
    private val connectionChanges = Channel<Unit>(Channel.CONFLATED)
    private val connection = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = connection.asStateFlow()

    @Volatile
    private var activeInterfaceName: String? = null
    val interfaceName: String? get() = activeInterfaceName
    val closing: Boolean get() = cleanupDeadline.started

    // 操作和系统回调均在主线程，close 可由任意线程调用。
    private var session: Session? = null
    private var channelLost = false
    private var receiverRegistered = false
    private var operation: Job? = null
    private var monitor: Job? = null
    private var ownedGroup: GroupIdentity? = null
    private var connectAttempt: ConnectAttempt? = null
    private var discoveryRequest: CompletableDeferred<Int>? = null
    private var selectingPeer = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION,
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> peerChanges.trySend(Unit)
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> connectionChanges.trySend(Unit)
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    peerChanges.trySend(Unit)
                    connectionChanges.trySend(Unit)
                }
            }
        }
    }

    suspend fun discover(onPeers: (List<YadeaP2pPeer>) -> Unit) = useSession {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            app.getSystemService(LocationManager::class.java)?.isLocationEnabled == false
        ) {
            throw IOException("请先打开手机定位服务，再扫描 Wi‑Fi 直连设备")
        }
        val discoveryState = query<Int> { callback ->
            manager.requestDiscoveryState(channel) { callback(it) }
        }
        if (discoveryState != WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
            throw IOException("系统正在扫描 Wi‑Fi 直连设备，请稍后重试")
        }
        ensureWifiDirectSelected()
        val start = action(peerChanges) { manager.discoverPeers(channel, it) }
        discoveryRequest = start
        try {
            start.requireSuccess("扫描 Wi‑Fi 直连设备")
            var previous: List<YadeaP2pPeer>? = null
            withTimeoutOrNull(SCAN_WINDOW_MS) {
                while (true) {
                    ensureWifiDirectSelected()
                    val peers = query<List<YadeaP2pPeer>> { callback ->
                        manager.requestPeers(channel) { result ->
                            callback(
                                result.deviceList.mapNotNull { device ->
                                    val address = device.deviceAddress?.takeIf(::validPeerAddress)
                                        ?: return@mapNotNull null
                                    YadeaP2pPeer(
                                        device.deviceName.orEmpty().ifBlank { "未知设备" },
                                        address.lowercase(),
                                    )
                                }.distinctBy { it.address }.sortedBy { it.address },
                            )
                        }
                    }
                    if (peers != previous) {
                        previous = peers
                        onPeers(peers)
                    }
                    // 广播即时触发刷新，定期查询兼容部分不发送广播的系统。
                    withTimeoutOrNull(PEER_REFRESH_MS) { peerChanges.receive() }
                    ensureWifiDirectSelected()
                    val currentState = query<Int> { callback ->
                        manager.requestP2pState(channel) { callback(it) }
                    }
                    requireEnabled(currentState)
                }
            }
        } finally {
            if (!selectingPeer && !cleanupDeadline.started)
                withContext(NonCancellable) { stopOwnedDiscovery(confirm = false) }
        }
        Unit
    }

    /** Called on the main thread before cancelling the scan and handing its channel to the service. */
    fun finishScanForConnection() {
        selectingPeer = true
    }

    private suspend fun Session.stopOwnedDiscovery(confirm: Boolean): Boolean {
        val request = discoveryRequest ?: return true
        return try {
            if (!confirm && !request.wasAccepted()) return false
            var stop: CompletableDeferred<Int>? = null
            var requests = 0
            while (!channelLost) {
                val accepted = request.acceptedOrPending()
                if (accepted == null) {
                    withTimeoutOrNull(PEER_REFRESH_MS) { peerChanges.receive() }
                    continue
                }
                if (!accepted || confirm && cleanupProbe { discoveryStopped() }?.value == true) {
                    discoveryRequest = null
                    return true
                }
                if (stop == null || stop.acceptedOrPending() == false && requests < CLEANUP_REQUEST_LIMIT) {
                    checkCleanupAction()
                    stop = action(peerChanges) { manager.stopPeerDiscovery(channel, it) }
                    requests++
                    if (!confirm) {
                        // The action reply confirms submission. Keep the scanner tracked for exit.
                        return stop.wasAccepted()
                    }
                }
                withTimeoutOrNull(PEER_REFRESH_MS) { peerChanges.receive() }
            }
            false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun Session.discoveryStopped(): Boolean =
        query<Int> { callback -> manager.requestDiscoveryState(channel) { callback(it) } } ==
            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED

    suspend fun connect(peer: YadeaP2pPeer): String = useSession {
        if (!validPeerAddress(peer.address)) throw IOException("仪表的 Wi‑Fi 直连设备地址无效，请重新扫描")
        if (group() != null || networkInUse()) {
            throw IOException("系统已有 Wi‑Fi 直连连接或正在协商，请先在系统中断开后重试")
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address.lowercase()
            // Match the working toupin configuration. The platform negotiates the group role.
            wps.setup = WpsInfo.PBC
        }
        ensureWifiDirectSelected()
        val attempt = action(connectionChanges) { manager.connect(channel, config, it) }
        // Submission owns cleanup even when Android has not delivered the connect callback yet.
        connectAttempt = ConnectAttempt(peer, attempt)
        attempt.requireSuccess("连接仪表")
        Log.i(TAG, "连接请求已接受，等待组网")
        val endpoint = awaitGroup(peer)
        currentCoroutineContext().ensureActive()
        ensureWifiDirectSelected()
        ownedGroup = endpoint.group
        activeInterfaceName = endpoint.group.interfaceName
        connection.value = true
        Log.i(TAG, "直连已组网，已获取远端地址和接口")
        monitor = scope.launch { watchGroup(endpoint.group, peer) }
        endpoint.ip
    }

    override fun close() {
        if (!cleanupDeadline.start()) return
        scope.launch {
            var cleaned = false
            val radioOwner = Any()
            try {
                setDisconnected()
                monitor?.cancel()
                val active = operation
                active?.cancel()
                cleaned = withTimeoutOrNull(cleanupDeadline.remainingMillis()) {
                    active?.join()
                    val owned = ownedGroup
                    val attempt = connectAttempt
                    val currentSession = session
                    val confirmed = if (currentSession == null || channelLost)
                        attempt == null && discoveryRequest == null
                    else {
                        radio.lock(radioOwner)
                        coroutineScope {
                            val discoveryStopped = async { currentSession.stopOwnedDiscovery(confirm = true) }
                            val connectionReleased = when {
                                owned != null -> currentSession.releaseGroup(owned, checkNotNull(attempt).peer)
                                attempt != null -> currentSession.releaseAttempt(attempt)
                                else -> true
                            }
                            discoveryStopped.await() && connectionReleased
                        }
                    }
                    if (!confirmed) awaitCancellation()
                    cleanupDeadline.remainingMillis() > 0L
                } == true
            } finally {
                ownedGroup = null
                connectAttempt = null
                discoveryRequest = null
                try {
                    if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
                    if (session?.let { runCatching { it.channel.close() }.isFailure } == true) cleaned = false
                    session = null
                } finally {
                    if (radio.holdsLock(radioOwner)) radio.unlock(radioOwner)
                    cleanupFinished.complete(cleaned)
                    scope.cancel()
                }
            }
        }
    }

    suspend fun closeAndAwait(): Boolean {
        close()
        if (cleanupFinished.isCompleted) return cleanupFinished.await()
        return withTimeoutOrNull(cleanupDeadline.remainingMillis()) { cleanupFinished.await() } == true
    }

    private suspend fun <T> useSession(block: suspend Session.() -> T): T =
        withContext(Dispatchers.Main.immediate) {
            ensureAvailable()
            check(operation == null && ownedGroup == null) { "此 Wi‑Fi 直连后端已有操作或连接" }
            operation = currentCoroutineContext().job
            try {
                radio.withLock {
                    ensureWifiDirectSelected()
                    val current = session ?: openSession().also { session = it }
                    requireEnabled(query<Int> { callback ->
                        current.manager.requestP2pState(current.channel) { callback(it) }
                    })
                    current.block()
                }
            } catch (error: SecurityException) {
                throw IOException("无法访问 Wi‑Fi 直连，请检查附近设备或位置权限", error)
            } finally {
                operation = null
            }
        }

    private fun openSession(): Session {
        if (!supported(app)) throw IOException("此手机不支持 Wi‑Fi 直连")
        if (permissions().any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            throw IOException("请先授予 Wi‑Fi 直连所需的附近设备或位置权限")
        }
        val manager = app.getSystemService(WifiP2pManager::class.java)
            ?: throw IOException("无法打开系统 Wi‑Fi 直连服务")
        ensureWifiDirectSelected()
        val channel = manager.initialize(app, app.mainLooper) {
            channelLost = true
            setDisconnected()
            peerChanges.trySend(Unit)
            connectionChanges.trySend(Unit)
        } ?: throw IOException("无法初始化 Wi‑Fi 直连，请检查 Wi‑Fi 开关")
        try {
            ensureWifiDirectSelected()
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            return Session(manager, channel)
        } catch (error: Exception) {
            runCatching { channel.close() }
            throw error
        }
    }

    private fun ensureAvailable() {
        if (cleanupDeadline.started) throw IOException("Wi‑Fi 直连已关闭")
        if (channelLost) throw IOException("系统 Wi‑Fi 直连服务已断开，请重试")
    }

    private fun ensureWifiDirectSelected() {
        ensureAvailable()
        if (!SettingsStore.yadea.allowsWifiDirect(SettingsStore.projectionTarget))
            throw IOException("当前未选择雅迪仪表 Wi‑Fi 直连")
    }

    private suspend fun checkCleanupAction() {
        currentCoroutineContext().ensureActive()
        if (channelLost || cleanupDeadline.started && cleanupDeadline.remainingMillis() == 0L)
            throw IOException("Wi‑Fi 直连清理期限已过或通道已关闭")
    }

    private suspend fun <T> cleanupProbe(timeoutMs: Long = CALLBACK_TIMEOUT_MS, read: suspend () -> T): QueryResult<T>? =
        withTimeoutOrNull(minOf(timeoutMs, cleanupDeadline.remainingMillis())) {
            checkCleanupAction()
            try {
                QueryResult(read())
            } catch (_: P2pQueryTimeout) {
                null
            }
        }

    private fun requireEnabled(state: Int) {
        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
            throw IOException("请先打开手机 Wi‑Fi，再使用 Wi‑Fi 直连")
        }
    }

    private suspend fun Session.awaitGroup(peer: YadeaP2pPeer): Endpoint {
        var endpoint: Endpoint? = null
        var sawLocalOwner = false
        withTimeoutOrNull(CONNECT_WINDOW_MS) {
            while (endpoint == null) {
                ensureWifiDirectSelected()
                val group = group()
                if (group != null) {
                    if (group.isGroupOwner) {
                        sawLocalOwner = true
                        if (group.matches(peer)) {
                            throw IOException("手机成为了 P2P 组主，请先让仪表开启直连，再重新连接")
                        }
                    } else {
                        val ownerAddress = group.owner?.deviceAddress
                        if (ownerAddress != null && validPeerAddress(ownerAddress) && !group.matches(peer)) {
                            throw IOException("P2P 组主与所选仪表不一致，请重新扫描连接")
                        }
                        if (group.matches(peer)) endpoint = remoteEndpoint(group)
                    }
                }
                if (endpoint == null) {
                    withTimeoutOrNull(GROUP_REFRESH_MS) { connectionChanges.receive() }
                }
            }
        }
        return endpoint ?: throw IOException(
            if (sawLocalOwner) "手机成为了 P2P 组主，请先让仪表开启直连，再重新连接"
            else "连接仪表超时，未能确认仪表的 Wi‑Fi 直连地址",
        )
    }

    private suspend fun Session.remoteEndpoint(group: WifiP2pGroup): Endpoint? {
        val identity = group.identity()
        if (identity.interfaceName == null) return null
        val info = query<WifiP2pInfo> { callback ->
            manager.requestConnectionInfo(channel) { callback(it) }
        }
        val address = info.groupOwnerAddress
        if (!info.groupFormed || info.isGroupOwner || address == null ||
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress
        ) return null
        if (group()?.identity() != identity) return null
        return address.hostAddress?.let { Endpoint(it, identity) }
    }

    private suspend fun Session.watchGroup(owned: GroupIdentity, peer: YadeaP2pPeer) {
        while (currentCoroutineContext().isActive && !cleanupDeadline.started && !channelLost) {
            val stillConnected = try {
                val current = group()
                current != null && current.identity() == owned && current.matches(peer)
            } catch (_: SecurityException) {
                false
            } catch (_: IOException) {
                // 查询超时不等同于断开，下次广播或轮询再确认。
                !channelLost
            }
            if (!stillConnected) break
            withTimeoutOrNull(GROUP_WATCH_MS) { connectionChanges.receive() }
        }
        setDisconnected()
    }

    private fun setDisconnected() {
        activeInterfaceName = null
        connection.value = false
    }

    private suspend fun Session.releaseAttempt(attempt: ConnectAttempt): Boolean {
        val cleanup = YadeaPendingConnectCleanup()
        var cancelRequest: CompletableDeferred<Int>? = null
        var groupProbeTimeout = GROUP_REFRESH_MS
        return try {
            while (!channelLost) {
                // A state query taken before the connect result cannot confirm that attempt is gone.
                val connectResult = attempt.result.acceptedOrPending()
                val cancellation = cancelRequest.cleanupResult()
                val observed = if (connectResult == false) null else cleanupProbe(groupProbeTimeout) { group() }
                groupProbeTimeout = CALLBACK_TIMEOUT_MS
                val formed = observed?.value
                val accepted = if (attempt.result.acceptedOrPending() == false) false else connectResult
                if (accepted != false && formed != null && formed.matches(attempt.peer)) {
                    if (!releaseGroup(formed.identity(), attempt.peer)) return false
                    cleanup.recordMatchingGroupGone()
                } else {
                    val group = when {
                        observed == null -> Group.Unknown
                        formed == null -> Group.Absent
                        else -> Group.Foreign
                    }
                    // cancelConnect is not scoped to a peer. Require our own accepted request
                    // and a fresh, successful group query before using it.
                    when (cleanup.afterGroupCheck(accepted, cancellation, group)) {
                        YadeaPendingConnectCleanup.Action.Cancel -> {
                            checkCleanupAction()
                            cancelRequest = action(connectionChanges) { manager.cancelConnect(channel, it) }
                        }
                        YadeaPendingConnectCleanup.Action.ObserveIdle ->
                            if (attemptIsIdle()) return true
                        YadeaPendingConnectCleanup.Action.Done -> return true
                        YadeaPendingConnectCleanup.Action.Wait -> Unit
                    }
                }
                withTimeoutOrNull(GROUP_REFRESH_MS) { connectionChanges.receive() }
            }
            false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun Session.attemptIsIdle(): Boolean =
        cleanupProbe { !networkInUse() && group() == null }?.value == true

    private suspend fun Session.releaseGroup(owned: GroupIdentity, peer: YadeaP2pPeer): Boolean {
        return try {
            var remove: CompletableDeferred<Int>? = null
            var requests = 0
            while (!channelLost) {
                val observed = cleanupProbe { group() }
                val current = observed?.value
                if (observed != null && current == null) return true
                if (current != null && current.identity() == owned && current.matches(peer) &&
                    (remove == null || remove.acceptedOrPending() == false && requests < CLEANUP_REQUEST_LIMIT)) {
                    checkCleanupAction()
                    remove = action(connectionChanges) { manager.removeGroup(channel, it) }
                    requests++
                }
                withTimeoutOrNull(GROUP_REFRESH_MS) { connectionChanges.receive() }
            }
            false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun WifiP2pGroup.matches(peer: YadeaP2pPeer): Boolean = if (isGroupOwner) {
        // 拒绝清理由本机担任组主且包含其他客户端的组。
        clientList.singleOrNull()?.deviceAddress.equals(peer.address, ignoreCase = true)
    } else {
        owner?.deviceAddress.equals(peer.address, ignoreCase = true)
    }

    private fun WifiP2pGroup.identity() = GroupIdentity(
        interfaceName = getInterface()?.takeIf { it.isNotBlank() },
        networkName = networkName,
        ownerAddress = owner?.deviceAddress?.lowercase(),
        localOwner = isGroupOwner,
    )

    private data class Endpoint(val ip: String, val group: GroupIdentity)

    private data class ConnectAttempt(val peer: YadeaP2pPeer, val result: CompletableDeferred<Int>)

    private data class QueryResult<T>(val value: T)

    private class P2pQueryTimeout : IOException("系统 Wi‑Fi 直连服务响应超时，请重试")

    private data class GroupIdentity(
        val interfaceName: String?,
        val networkName: String?,
        val ownerAddress: String?,
        val localOwner: Boolean,
    )

    private data class Session(val manager: WifiP2pManager, val channel: WifiP2pManager.Channel) {
        suspend fun group(): WifiP2pGroup? = query { callback ->
            manager.requestGroupInfo(channel) { callback(it) }
        }

        @Suppress("DEPRECATION")
        suspend fun networkInUse(): Boolean = query { callback ->
            manager.requestNetworkInfo(channel) { callback(it.isConnectedOrConnecting) }
        }
    }

    companion object {
        private const val TAG = "YadeaP2p"
        private const val SCAN_WINDOW_MS = 14_000L
        private const val CONNECT_WINDOW_MS = 30_000L
        private const val CALLBACK_TIMEOUT_MS = 2_000L
        private const val CLEANUP_TIMEOUT_MS = 6_000L
        private const val CLEANUP_REQUEST_LIMIT = 2
        private const val PEER_REFRESH_MS = 1_000L
        private const val GROUP_REFRESH_MS = 350L
        private const val GROUP_WATCH_MS = 2_000L
        private const val ACTION_SUCCESS = -1
        private val MAC = Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
        // 系统发现与组协商是共享操作，等待扫描清理后才允许服务开始连接。
        private val radio = Mutex()

        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }

        fun supported(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
                context.getSystemService(WifiP2pManager::class.java) != null

        private fun validPeerAddress(address: String): Boolean =
            MAC.matches(address) && !address.equals("ff:ff:ff:ff:ff:ff", ignoreCase = true) &&
                address != "00:00:00:00:00:00" && address != "02:00:00:00:00:00"

        private suspend fun <T> query(send: ((T) -> Unit) -> Unit): T {
            // 包装结果以区分合法的 null（没有组）和没有收到回调。
            val result = withTimeoutOrNull<QueryResult<T>>(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    send { value -> continuation.resume(QueryResult(value)) }
                }
            } ?: throw P2pQueryTimeout()
            return result.value
        }

        private fun action(changes: Channel<Unit>, send: (WifiP2pManager.ActionListener) -> Unit): CompletableDeferred<Int> {
            val reply = YadeaP2pActionResult(changes)
            send(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { reply.complete(ACTION_SUCCESS) }
                override fun onFailure(reason: Int) { reply.complete(reason) }
            })
            return reply.result
        }

        private suspend fun CompletableDeferred<Int>.wasAccepted(): Boolean =
            withTimeoutOrNull(CALLBACK_TIMEOUT_MS) { await() } == ACTION_SUCCESS

        private suspend fun CompletableDeferred<Int>.acceptedOrPending(): Boolean? =
            if (isCompleted) await() == ACTION_SUCCESS else null

        private suspend fun CompletableDeferred<Int>?.cleanupResult(): Cancellation = when {
            this == null -> Cancellation.None
            !isCompleted -> Cancellation.Pending
            await() == ACTION_SUCCESS -> Cancellation.Accepted
            else -> Cancellation.Rejected
        }

        private suspend fun CompletableDeferred<Int>.requireSuccess(operation: String) {
            when (val reason = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) { await() }) {
                ACTION_SUCCESS -> Unit
                null -> throw IOException("${operation}超时，请重试")
                WifiP2pManager.BUSY -> throw IOException("${operation}失败：系统 Wi‑Fi 直连正忙")
                WifiP2pManager.P2P_UNSUPPORTED -> throw IOException("当前系统不支持 Wi‑Fi 直连")
                else -> throw IOException("${operation}失败（系统错误码 ${reason}）")
            }
        }
    }
}
