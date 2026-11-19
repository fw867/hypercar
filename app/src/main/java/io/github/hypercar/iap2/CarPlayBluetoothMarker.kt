package io.github.hypercar.iap2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/**
 * CatPlay 的手机 EIR 标记。Android 通过系统服务注册更新 EIR，而非 Linux MGMT add_uuid。
 * 原有 Phone 标记可接入 iAP2；宝马档案的额外 UUID 只用于发现。
 * 主 iAP2 监听先注册，本服务失败不影响它；关闭 socket 由系统撤销 SDP/EIR。
 */
internal class CarPlayBluetoothMarker(
    adapter: BluetoothAdapter,
    private val service: Service,
    ownerAlive: () -> Boolean,
    primaryReady: () -> Boolean,
    onRegistrationChanged: () -> Unit,
    onConnection: (BluetoothSocket) -> Unit,
    log: (String) -> Unit,
) : Closeable {
    enum class Service(val serviceName: String, val uuid: UUID, val acceptsIap2: Boolean) {
        PHONE("CarPlay Phone", UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a"), true),
        DISCOVERY("CarPlay Discovery", UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77"), false),
    }

    companion object {
        fun usesDiscoveryMarker(profileId: String): Boolean = profileId.startsWith("bmw_")

        fun forProfile(profileId: String): List<Service> =
            if (usesDiscoveryMarker(profileId)) listOf(Service.DISCOVERY, Service.PHONE) else listOf(Service.PHONE)

        // Keep the first unpublished listener running so it can register or retry.
        fun registrationPrefix(services: List<Service>, published: (Service) -> Boolean): List<Service> {
            val pending = services.indexOfFirst { !published(it) }
            return if (pending < 0) services else services.take(pending + 1)
        }
    }

    private var lastErrorAt: Long? = null
    @SuppressLint("MissingPermission")
    private val listener = RecoveringListener(
        ownerAlive = ownerAlive,
        open = {
            if (!primaryReady()) throw IOException("等待主 iAP2 服务注册")
            try {
                if (!adapter.isEnabled) throw IOException("蓝牙未开启")
                adapter.listenUsingRfcommWithServiceRecord(service.serviceName, service.uuid).also {
                    log("BT-MARKER: 系统标记服务已注册 uuid=${service.uuid}（等待系统更新 EIR）")
                }
            } catch (error: RuntimeException) {
                throw IOException("标记服务不可用：${error.javaClass.simpleName}", error)
            }
        },
        accept = { socket: BluetoothServerSocket -> socket.accept() },
        onRegistered = onRegistrationChanged,
        onFailure = {
            onRegistrationChanged()
            val now = SystemClock.elapsedRealtime()
            if (lastErrorAt == null || now - lastErrorAt!! >= 30_000) {
                log("BT-MARKER: ${service.uuid} ${it.message}；保留主 iAP2 监听，5s 后重试")
                lastErrorAt = now
            }
        },
        awaitRetry = { try { Thread.sleep(5_000) } catch (_: InterruptedException) { } },
    )
    private val thread = Thread({
        try {
            while (ownerAlive()) {
                val socket = listener.acceptNext() ?: break
                try {
                    if (service.acceptsIap2) onConnection(socket) else {
                        log("BT-MARKER: 发现标记收到 RFCOMM uuid=${service.uuid}，关闭该探测连接")
                        socket.close()
                    }
                } catch (error: Exception) {
                    runCatching { socket.close() }
                    log("BT-MARKER: 接入失败 ${error.message}")
                }
            }
        } finally { listener.close() }
    }, "carplay-bt-marker").apply { isDaemon = true }

    fun start() = thread.start()
    val isRegistered: Boolean get() = listener.isRegistered
    override fun close() {
        listener.close()
        thread.interrupt()
    }
}
