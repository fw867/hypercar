package io.github.hypercar.yadea

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal object YadeaInstrumentNetwork {
    const val CONTROL_PORT = 8888

    data class Route(val network: Network, val interfaceName: String?)

    enum class Ping(val message: String) {
        Reply("仪表已响应 Ping。"),
        NoReply("Ping 未收到回应，请核对仪表 IP 和手机 Wi-Fi。"),
        NoRoute("找不到通往该 IP 的 Wi-Fi 路由，请先接入仪表网络。"),
        Unavailable("系统无法执行 Ping，请检查 Wi-Fi 后重试。"),
    }

    fun parseAddress(host: String): InetAddress? =
        runCatching { InetAddresses.parseNumericAddress(host.trim()) }.getOrNull()
            ?.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }

    @Suppress("DEPRECATION")
    fun findRoute(connectivity: ConnectivityManager, target: InetAddress): Route? =
        connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val route = properties.routes.filter { !it.hasGateway() && it.destination.contains(target) }
                .maxByOrNull { it.destination.prefixLength } ?: return@mapNotNull null
            Route(network, route.`interface` ?: properties.interfaceName) to route.destination.prefixLength
        }.maxByOrNull { it.second }?.first

    suspend fun check(context: Context, host: String): String {
        val address = parseAddress(host) ?: return Ping.NoRoute.message
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val route = findRoute(connectivity, address) ?: return Ping.NoRoute.message
        val result = ping(route, address)
        if (result == Ping.Reply) return result.message

        val tcpReachable = runInterruptible(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    route.network.bindSocket(socket)
                    socket.connect(InetSocketAddress(address, CONTROL_PORT), 2_000)
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        return "${result.message} " + if (tcpReachable) "投屏端口 TCP $CONTROL_PORT 可以连接。"
        else "投屏端口 TCP $CONTROL_PORT 连接失败。"
    }

    suspend fun ping(route: Route, address: InetAddress): Ping = runInterruptible(Dispatchers.IO) {
        val interfaceName = route.interfaceName ?: return@runInterruptible Ping.Unavailable
        val target = address.hostAddress ?: return@runInterruptible Ping.Unavailable
        val executable = if (address is Inet6Address) "/system/bin/ping6" else "/system/bin/ping"
        try {
            val process = ProcessBuilder(executable, "-n", "-c", "1", "-W", "2", "-I", interfaceName, target)
                .redirectErrorStream(true)
                .apply { environment()["LC_ALL"] = "C" }
                .start()
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) Ping.NoReply
                else if (process.exitValue() == 0) Ping.Reply
                else {
                    // Android ping can return 1 for local socket errors as well as packet loss.
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    if (process.exitValue() == 1 && output.lineSequence().none { it.startsWith("ping:") }) Ping.NoReply
                    else Ping.Unavailable
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
            }
        } catch (_: IOException) {
            Ping.Unavailable
        } catch (_: SecurityException) {
            Ping.Unavailable
        }
    }
}
