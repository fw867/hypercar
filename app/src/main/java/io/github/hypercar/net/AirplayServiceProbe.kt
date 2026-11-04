package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * 车机 AirPlay 接收端的 mDNS 探测（`_airplay._tcp.local`）。
 *
 * 为什么自己发查询而不用 `NsdManager`：`NsdManager` 由 system_server 代跑，浏览走
 * **系统默认网络**；车机热点往往没有外网、默认网络还是蜂窝，于是「服务明明在却浏览不到」。
 * 这里直接在**已绑定的车机网络**上发一次 PTR 查询并自己解析应答，拿车机自报的
 * SRV 端口与 A 地址。
 *
 * 真机依据（2026-10-24）：iOS sysdiagnose 的 unified log 里有 `Bonjour PTR Add … _airplay._tcp`，
 * 说明车机确实广播该服务；而写死 7000 得到的是 `455 Method Not Valid In This State`。
 */
class AirplayServiceProbe(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 探测结果：车机广播的 AirPlay 主机与端口。 */
    data class Endpoint(val host: String, val port: Int, val instance: String, val model: String?)

    private companion object {
        const val TAG = "MDNS"
        const val GROUP = "224.0.0.251"
        const val PORT = 5353
        const val SERVICE = "_airplay._tcp.local"
    }

    /**
     * 在 [network]（车机热点网络）上查询 `_airplay._tcp`，最多等 [timeoutMillis]。
     *
     * 返回第一个「SRV 端口 + A 地址」都齐的实例；拿不到返回 null（调用方退回端口列表）。
     */
    suspend fun browse(network: Network?, timeoutMillis: Long = 3_000, strictNetwork: Boolean = false): Endpoint? =
        withContext(Dispatchers.IO) {
            var socket: MulticastSocket? = null
            try {
                val interfaceName = network?.let { interfaceNameOf(it) }
                val nif = interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
                socket = MulticastSocket(null)
                socket.reuseAddress = true
                // 先让 Network 绑定（决定源地址/出口接口），绑定失败再退回「任意地址」；
                // 顺序反了的话 socket 已 bound，bindSocket 会失败，包就会走默认网络发出去。
                val boundToCarNetwork = network != null &&
                    runCatching { network.bindSocket(socket) }.isSuccess
                if (strictNetwork && !boundToCarNetwork) error("car network binding failed; skip rediscovery")
                if (!boundToCarNetwork) socket.bind(InetSocketAddress(0))
                if (nif != null) runCatching { socket.networkInterface = nif }
                runCatching { socket.joinGroup(InetSocketAddress(InetAddress.getByName(GROUP), PORT), nif) }

                val query = buildQuery(SERVICE)
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(GROUP), PORT))
                log("$TAG: 已查询 $SERVICE（if=${interfaceName ?: "-"}），等应答 ${timeoutMillis}ms")

                val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
                val records = AirplayDnsRecords()
                val buf = ByteArray(4096)
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    socket.soTimeout = (deadline - android.os.SystemClock.elapsedRealtime()).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buf, buf.size)
                    val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!received) break
                    records.accept(packet.data, packet.length)
                    records.first()?.let { endpoint ->
                        log("$TAG: ✓ ${endpoint.instance} → ${endpoint.host}:${endpoint.port}")
                        return@withContext Endpoint(endpoint.host, endpoint.port, endpoint.instance, endpoint.model)
                    }
                }
                log("$TAG: 未拿到可用应答——继续等待车机邀请或本轮端口重试")
                null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log("$TAG: 探测异常 ${t.javaClass.simpleName}: ${t.message}")
                null
            } finally {
                runCatching { socket?.close() }
            }
        }

    private fun interfaceNameOf(network: Network): String? = runCatching {
        context.getSystemService(ConnectivityManager::class.java)
            ?.getLinkProperties(network)
            ?.interfaceName
    }.getOrNull()

    /** 组一条标准 mDNS 查询（PTR / IN / 无应答位）。 */
    private fun buildQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        u16(0)
        u16(0)
        u16(1)
        u16(0); u16(0); u16(0)
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(12)
        u16(1)
        return out.toByteArray()
    }

}
