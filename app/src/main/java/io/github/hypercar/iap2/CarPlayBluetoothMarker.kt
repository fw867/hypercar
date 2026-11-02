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
 * 标记有自己的 RFCOMM 通道；来连接时使用同一 iAP2 协议，不能挂一个空服务冒充就绪。
 * 主 iAP2 监听先注册，本服务失败不影响它；关闭 socket 由系统撤销 SDP/EIR。
 */
internal class CarPlayBluetoothMarker(
    adapter: BluetoothAdapter,
    ownerAlive: () -> Boolean,
    primaryReady: () -> Boolean,
    onConnection: (BluetoothSocket) -> Unit,
    log: (String) -> Unit,
) : Closeable {
    companion object {
        val UUID_PHONE: UUID = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a")
    }

    private var lastErrorAt: Long? = null
    @SuppressLint("MissingPermission")
    private val listener = RecoveringListener(
        ownerAlive = ownerAlive,
        open = {
            if (!primaryReady()) throw IOException("等待主 iAP2 服务注册")
            try {
                if (!adapter.isEnabled) throw IOException("蓝牙未开启")
                adapter.listenUsingRfcommWithServiceRecord("CarPlay Phone", UUID_PHONE).also {
                    log("BT-MARKER: 系统标记服务已注册 uuid=$UUID_PHONE（等待系统更新 EIR）")
                }
            } catch (error: RuntimeException) {
                throw IOException("标记服务不可用：${error.javaClass.simpleName}", error)
            }
        },
        accept = { socket: BluetoothServerSocket -> socket.accept() },
        onFailure = {
            val now = SystemClock.elapsedRealtime()
            if (lastErrorAt == null || now - lastErrorAt!! >= 30_000) {
                log("BT-MARKER: ${it.message}；保留主 iAP2 监听，5s 后重试")
                lastErrorAt = now
            }
        },
        awaitRetry = { try { Thread.sleep(5_000) } catch (_: InterruptedException) { } },
    )
    private val thread = Thread({
        try {
            while (ownerAlive()) {
                val socket = listener.acceptNext() ?: break
                try { onConnection(socket) } catch (error: Exception) {
                    runCatching { socket.close() }
                    log("BT-MARKER: 接入失败 ${error.message}")
                }
            }
        } finally { listener.close() }
    }, "carplay-bt-marker").apply { isDaemon = true }

    fun start() = thread.start()
    override fun close() {
        listener.close()
        thread.interrupt()
    }
}
