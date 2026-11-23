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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class CtrlSrvObservation(
    val instance: String, val host: String, val port: Int, val receivedAt: Long,
    val localAddresses: Set<InetAddress> = emptySet(),
) {
    fun matches(name: String, listenerPort: Int, registrationStartedAt: Long): Boolean =
        receivedAt >= registrationStartedAt && port == listenerPort && instance.equals(name, ignoreCase = true)
}

/** Scoped to a selected network and accessed under the advertiser's monitor. */
internal class CtrlSrvEvidence {
    private var addresses: Set<InetAddress>? = null
    private var changedAt = Long.MIN_VALUE
    var latest: CtrlSrvObservation? = null
        private set

    fun addressesChanged(current: Set<InetAddress>, at: Long): Boolean {
        if (addresses == current) return false
        addresses = current
        changedAt = at
        latest = null
        return true
    }

    fun record(srv: CtrlSrvObservation) {
        if (srv.receivedAt < changedAt || srv.receivedAt < (latest?.receivedAt ?: Long.MIN_VALUE)) return
        if (addresses != null && addresses != srv.localAddresses) return
        addresses = srv.localAddresses
        latest = srv
    }

    fun clear() { latest = null }

    fun resetNetwork() {
        addresses = null
        changedAt = Long.MIN_VALUE
        clear()
    }
}

/** Observe NSD multicast on the selected car network. Replay and the model TXT are opt-in. */
internal class CtrlMdnsObserver(
    private val context: Context,
    private val network: Network,
    private val generation: Long,
    private val log: (String) -> Unit,
    private val attemptId: Long = 0,
    private val onLocalAnnouncement: (family: Int) -> Boolean = { false },
    private val trace: (String) -> Unit = {},
    private val onLocalSrv: (CtrlSrvObservation) -> Unit = {},
    private val onObservation: (family: Int, localAnnouncementPort: Int?) -> Unit = { _, _ -> },
) : Closeable {
    private val prefix = "attempt=$attemptId gen=$generation network=$network"
    @Volatile private var closed = false
    private val sockets = mutableListOf<MulticastSocket>()
    private var lock: WifiManager.MulticastLock? = null
    private val workers = AtomicInteger()
    private val startedAt = SystemClock.elapsedRealtime()
    private data class LocalAnnouncement(
        val bytes: ByteArray, val metadata: MdnsDiagnosticPacket.Announcement,
        val source: InetAddress, val seenAt: Long,
    )
    private val localAnnouncements = ConcurrentHashMap<Int, LocalAnnouncement>(2)
    // Bound both history and logging across the two receive workers.
    private val foreignCarPlaySources = MdnsSourceLogGate(SystemClock::elapsedRealtime)
    private fun linkProperties() = context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(network)
    // Both receive workers share the same short-lived view; replay deliberately bypasses it.
    private val localAddresses = MdnsLocalAddressCache(SystemClock::elapsedRealtime) {
        runCatching { linkProperties()?.linkAddresses?.map { it.address }?.toSet() }.getOrNull()
    }

    fun invalidateLocalAddresses() = localAddresses.invalidate()

    private fun multicastGroup(family: Int, nif: NetworkInterface): InetAddress =
        if (family == 4) InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
        else Inet6Address.getByAddress(null,
            byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte()), nif.index)

    /** null = no eligible packet; a nonnegative result is the number of successful family sends. */
    fun resend(
        name: String, port: Int, mac: String, version: String, registrationStartedAt: Long,
        onlyFamily: Int? = null, canSend: () -> Boolean,
    ): Int? {
        if (closed) return null
        val link = linkProperties() ?: return null
        val nif = link.interfaceName?.let(NetworkInterface::getByName) ?: return null
        val addresses = link.linkAddresses.map { it.address }.toSet()
        val tag = if (onlyFamily == null) "MDNS-RESEND" else "MDNS-DEVICE-INFO"
        val eligible = localAnnouncements.entries.filter { (family, packet) ->
            (onlyFamily == null || family == onlyFamily) && packet.seenAt >= registrationStartedAt && packet.source in addresses &&
                packet.source in packet.metadata.addresses && packet.metadata.matches(name, port, mac, version, addresses)
        }
        if (eligible.isEmpty()) {
            if (onlyFamily != null) return null
            val candidates = localAnnouncements.values.toList()
            log("MDNS-RESEND: $prefix cached=${candidates.size} " +
                "current=${candidates.count { it.seenAt >= registrationStartedAt }} " +
                "matching=${candidates.count { it.source in addresses && it.metadata.matches(name, port, mac, version, addresses) }}")
            return null
        }
        var sent = 0
        for ((family, packet) in eligible) {
            if (closed || !canSend()) break
            try {
                val group = multicastGroup(family, nif)
                val source = if (packet.source is Inet6Address && packet.source.isLinkLocalAddress)
                    Inet6Address.getByAddress(null, packet.source.address, nif.index) else packet.source
                val enriched = MdnsDiagnosticPacket.withDeviceInfo(packet.bytes, name)
                if (enriched == null && onlyFamily != null) continue
                val payload = enriched ?: packet.bytes
                val datagram = DatagramPacket(payload, payload.size, group, 5353)
                MulticastSocket(null).use { socket ->
                    socket.reuseAddress = true
                    network.bindSocket(socket)
                    // Bind to our source address, not a wildcard :5353 listener; close immediately after send.
                    socket.bind(InetSocketAddress(source, 5353))
                    socket.networkInterface = nif
                    socket.timeToLive = 255
                    if (!canSend() || closed) return sent
                    socket.send(datagram)
                    sent++
                }
                log("$tag: $prefix if=${nif.name} IPv$family host=${packet.metadata.host} port=$port " +
                    "bytes=${payload.size} deviceInfo=${if (enriched != null) CARPLAY_DEVICE_INFO_BOARD else "none"}")
                if (onlyFamily == null) trace("event=mdns-resent $prefix family=IPv$family " +
                    "host=${packet.metadata.host} currentPort=$port")
            } catch (e: Exception) {
                log("$tag: $prefix if=${nif.name} IPv$family failed=${e.javaClass.simpleName}:${e.message}")
            }
        }
        return sent
    }

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
            "addresses=${link?.linkAddresses} multicast-visible-only, no TCP-SYN/old-port/unicast guarantee")
        workers.set(2)
        for (family in listOf(4, 6)) Thread({ receive(family, nif) }, "ctrl-mdns-v$family").apply {
            isDaemon = true; start()
        }
    }

    private fun receive(family: Int, nif: NetworkInterface) {
        var opened: MulticastSocket? = null
        var received = 0L; var relevant = 0L; var malformed = 0L; var suppressed = 0L
        var window = SystemClock.elapsedRealtime(); var emitted = 0
        var lastPeerQueryAt: Long? = null
        var lastDeviceInfoAt: Long? = null
        val label = "$prefix if=${nif.name} IPv$family"
        try {
            val socket = MulticastSocket(null)
            opened = socket
            synchronized(this) {
                if (closed) { socket.close(); return }
                sockets += socket
            }
            socket.reuseAddress = true
            val group = multicastGroup(family, nif)
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
                    log("MDNS-OBS: stats $label received=$received relevant=$relevant malformed=$malformed suppressed=$suppressed ${foreignCarPlaySources.summary()}")
                    window = now; emitted = 0
                }
                val packet = DatagramPacket(bytes, bytes.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val receivedAt = SystemClock.elapsedRealtime()
                received++
                val wire = bytes.copyOf(packet.length)
                val decoded = MdnsDiagnosticPacket.decode(wire)
                if (decoded == null) { malformed++; continue }
                val addrs = localAddresses.current()
                if (addrs != null && !closed) {
                    val isLocal = packet.address in addrs
                    if (isLocal && packet.port == 5353) decoded.carPlaySources.forEach { service ->
                        onLocalSrv(CtrlSrvObservation(service.instance, service.host, service.port, receivedAt, addrs))
                    }
                    val evidence = decoded.details.filter { detail ->
                        "_carplay-ctrl._tcp.local" in detail || (!isLocal && detail.startsWith("Q "))
                    }
                    if (evidence.isNotEmpty()) trace("event=mdns-observed $prefix family=IPv$family " +
                        "origin=${if (isLocal) "local" else "peer"} peer=${packet.address.hostAddress} " +
                        "kind=${if (decoded.response) "response" else "query"} ${evidence.take(16).joinToString("; ")}")
                }
                if (addrs != null && !(packet.address in addrs) && decoded.carPlaySources.isNotEmpty()) {
                    for (src in decoded.carPlaySources) {
                        if (src.addresses.any { it in addrs }) continue
                        val key = "${src.instance}@${src.host}:${src.port}"
                        if (!closed && foreignCarPlaySources.admit(key)) {
                            log("MDNS-OBS: $prefix remote-service instance=${src.instance} sender=${packet.address.hostAddress} " +
                                "host=${src.host} port=${src.port} addresses=${src.addresses.map { it.hostAddress }} " +
                                "txt=[${src.txt.joinToString(",")}]；仅证明观察到服务记录，不代表车机已选择该设备")
                        }
                    }
                }
                if (decoded.announcement != null || decoded.ctrlQuestion) {
                    val addresses = localAddresses.current()
                    if (!closed && addresses != null) {
                        val local = packet.address in addresses
                        val announcement = decoded.announcement
                        if (announcement != null && local && packet.port == 5353 && packet.address in announcement.addresses) {
                            localAnnouncements[family] = LocalAnnouncement(wire, announcement, packet.address, SystemClock.elapsedRealtime())
                            onObservation(family, announcement.port)
                            val observedAt = SystemClock.elapsedRealtime()
                            if (lastDeviceInfoAt?.let { observedAt - it < 1_000 } != true && onLocalAnnouncement(family)) {
                                lastDeviceInfoAt = observedAt
                            }
                        } else if (decoded.ctrlQuestion && !local && !packet.address.isAnyLocalAddress &&
                            !packet.address.isMulticastAddress) {
                            val observedAt = SystemClock.elapsedRealtime()
                            if (lastPeerQueryAt?.let { observedAt - it < 1_000L } != true) {
                                lastPeerQueryAt = observedAt
                                onObservation(family, null)
                            }
                        }
                    }
                }
                if (decoded.details.isEmpty()) continue
                relevant++
                if (emitted >= 24) { suppressed++; continue }
                emitted++
                log("MDNS-OBS: packet $label peer=${packet.address.hostAddress}:${packet.port} bytes=${packet.length} " +
                    "kind=${if (decoded.response) "response" else "query"} " +
                    decoded.details.take(12).joinToString("; ") + " records=${decoded.details.size} " +
                    "replayCandidate=${decoded.announcement != null}")
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
