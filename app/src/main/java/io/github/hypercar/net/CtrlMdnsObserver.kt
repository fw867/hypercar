package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.io.Closeable
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** No queries, announcements or connections: observe multicast delivered on the selected car network. */
internal class CtrlMdnsObserver(
    private val context: Context,
    private val network: Network,
    private val generation: Long,
    private val log: (String) -> Unit,
    private val attemptId: Long = 0,
) : Closeable {
    private val prefix = "attempt=$attemptId gen=$generation network=$network"
    @Volatile private var closed = false
    private val sockets = mutableListOf<MulticastSocket>()
    private var lock: WifiManager.MulticastLock? = null
    private val workers = AtomicInteger()
    private val startedAt = SystemClock.elapsedRealtime()

    fun retryDue(): Boolean = !closed && workers.get() == 0 && SystemClock.elapsedRealtime() - startedAt >= 15_000

    fun start() {
        val link = context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(network)
        val nif = runCatching { link?.interfaceName?.let(NetworkInterface::getByName) }.getOrNull()
        if (nif == null) {
            log("MDNS-OBS: $prefix unavailable=no-interface; no default-network fallback")
            return
        }
        try {
            lock = context.getSystemService(WifiManager::class.java)?.createMulticastLock("hypercar-ctrl-diag")
                ?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            log("MDNS-OBS: $prefix multicast-lock=${e.javaClass.simpleName}")
        }
        log("MDNS-OBS: start $prefix if=${nif.name} index=${nif.index} " +
            "addresses=${link?.linkAddresses} passive=true; multicast-visible-only, no TCP-SYN/old-port/unicast guarantee")
        workers.set(2)
        for (family in listOf(4, 6)) Thread({ receive(family, nif) }, "ctrl-mdns-v$family").apply {
            isDaemon = true; start()
        }
    }

    private fun receive(family: Int, nif: NetworkInterface) {
        var opened: MulticastSocket? = null
        var received = 0L; var relevant = 0L; var malformed = 0L; var suppressed = 0L
        var window = SystemClock.elapsedRealtime(); var emitted = 0
        val label = "$prefix if=${nif.name} IPv$family"
        try {
            val socket = MulticastSocket(null)
            opened = socket
            synchronized(this) {
                if (closed) { socket.close(); return }
                sockets += socket
            }
            socket.reuseAddress = true
            val group = if (family == 4) InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
                else Inet6Address.getByAddress(null,
                    byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte()), nif.index)
            // A wildcard :5353 listener could steal NSD's unicast traffic (RFC 6762 §15.1).
            // Binding the multicast destination also makes the observation boundary explicit.
            socket.bind(InetSocketAddress(group, 5353))
            network.bindSocket(socket)
            socket.networkInterface = nif
            socket.joinGroup(InetSocketAddress(group, 5353), nif)
            socket.soTimeout = 2000
            log("MDNS-OBS: ready $label group=${group.hostAddress}:5353")
            val bytes = ByteArray(9000)
            while (!closed) {
                val now = SystemClock.elapsedRealtime()
                if (now - window >= 15_000) {
                    log("MDNS-OBS: stats $label received=$received relevant=$relevant malformed=$malformed suppressed=$suppressed")
                    window = now; emitted = 0
                }
                val packet = DatagramPacket(bytes, bytes.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                received++
                val decoded = MdnsDiagnosticPacket.decode(bytes.copyOf(packet.length))
                if (decoded == null) { malformed++; continue }
                if (decoded.details.isEmpty()) continue
                relevant++
                if (emitted >= 24) { suppressed++; continue }
                emitted++
                log("MDNS-OBS: packet $label peer=${packet.address.hostAddress}:${packet.port} bytes=${packet.length} " +
                    "kind=${if (decoded.response) "response" else "query"} " +
                    decoded.details.take(12).joinToString("; ") + " records=${decoded.details.size}")
            }
        } catch (e: Exception) {
            if (!closed) log("MDNS-OBS: unavailable $label error=${e.javaClass.simpleName}:${e.message}")
        } finally {
            opened?.close()
            synchronized(this) {
                sockets.remove(opened)
                if (workers.decrementAndGet() == 0) { runCatching { lock?.release() }; lock = null }
            }
            log("MDNS-OBS: end $label received=$received relevant=$relevant malformed=$malformed suppressed=$suppressed")
        }
    }

    @Synchronized override fun close() {
        closed = true
        sockets.forEach { it.close() }
        sockets.clear()
        runCatching { lock?.release() }; lock = null
    }
}
