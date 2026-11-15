package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Network/interface-bound mDNS browse and active SRV/TXT/A/AAAA resolution for every profile. */
class AirplayServiceProbe(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    data class Endpoint(val host: String, val port: Int, val instance: String, val model: String?,
        val targetHost: String = "", val addresses: List<String> = listOf(host))

    suspend fun browse(network: Network?, timeoutMillis: Long = 3_000, strictNetwork: Boolean = false,
        accept: (Endpoint) -> Boolean = { true }): Endpoint? {
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            if (strictNetwork && network == null) {
                log("MDNS: car network unavailable; skip strict discovery")
                return null
            }
            val interfaceName = network?.let {
                context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(it)?.interfaceName
            }
            val nif = interfaceName?.let { NetworkInterface.getByName(it) }
            if (network != null && nif == null) {
                log("MDNS: bound network has no interface; skip discovery")
                return null
            }
            try {
                multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
                    ?.createMulticastLock("hypercar-mdns")?.apply { setReferenceCounted(false); acquire() }
            } catch (error: Exception) { log("MDNS: multicast lock unavailable: ${error.message}") }
            log("MDNS: dual-stack browse _airplay._tcp.local (if=${interfaceName ?: "default"}, ${timeoutMillis}ms)")
            val endpoint = MdnsDiscovery(log).browse(timeoutMillis,
                open = { family -> openTransport(family, network, nif) },
                accept = { accept(Endpoint(it.host, it.port, it.instance, it.model, it.targetHost, it.addresses)) })
            return endpoint?.let {
                log("MDNS: ✓ ${it.instance} → ${it.host}:${it.port}")
                Endpoint(it.host, it.port, it.instance, it.model, it.targetHost, it.addresses)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            log("MDNS: discovery failed ${error.javaClass.simpleName}: ${error.message}")
            return null
        } finally { runCatching { multicastLock?.release() } }
    }

    private fun openTransport(family: Int, network: Network?, nif: NetworkInterface?): MdnsDiscovery.Transport {
        val socket = MulticastSocket(null)
        try {
            socket.reuseAddress = true
            // Binding the local mDNS port does not select a route. Network.bindSocket requires an
            // unconnected datagram socket; no query is sent until BOTH network and interface bind.
            socket.bind(InetSocketAddress(InetAddress.getByAddress(ByteArray(if (family == 4) 4 else 16)), 5353))
            if (network != null) network.bindSocket(socket) // Failure closes this family; never default-route fallback.
            if (nif != null) socket.networkInterface = nif
            socket.timeToLive = 255
            val group = if (family == 4) InetAddress.getByAddress(byteArrayOf(224.toByte(),0,0,251.toByte()))
                else Inet6Address.getByAddress(null,
                    byteArrayOf(0xff.toByte(),2,0,0,0,0,0,0,0,0,0,0,0,0,0,0xfb.toByte()), nif?.index ?: 0)
            val destination = InetSocketAddress(group, 5353)
            socket.joinGroup(destination, nif)
            log("MDNS: IPv$family socket ready if=${nif?.name ?: "default"} index=${nif?.index ?: 0} group=${group.hostAddress}:5353")
            return object : MdnsDiscovery.Transport {
                override fun send(query: ByteArray) {
                    socket.send(DatagramPacket(query, query.size, destination))
                }
                override fun receive(timeoutMillis: Int): MdnsDiscovery.Reply? {
                    socket.soTimeout = timeoutMillis.coerceAtLeast(1)
                    val bytes = ByteArray(9000)
                    val packet = DatagramPacket(bytes, bytes.size)
                    return try {
                        socket.receive(packet)
                        MdnsDiscovery.Reply(bytes.copyOf(packet.length), nif?.index ?: 0)
                    } catch (_: SocketTimeoutException) { null }
                }
                override fun close() { socket.close() }
            }
        } catch (error: Throwable) { socket.close(); throw error }
    }
}
