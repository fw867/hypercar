package io.github.hypercar.net

import java.net.InetAddress

/** Recent complete NSD announcements on one selected car network. */
internal class CtrlMdnsReplayCache(private val elapsedRealtime: () -> Long) {
    class Packet(
        val family: Int, val bytes: ByteArray, val metadata: MdnsDiagnosticPacket.Announcement,
        val source: InetAddress, val seenAt: Long,
    ) {
        fun isLive(now: Long): Boolean = now - seenAt in 0 until metadata.ttlMillis
    }

    private data class Key(val family: Int, val instance: String, val port: Int, val txt: Set<String>)
    private data class SrvKey(val instance: String, val port: Int)
    private data class SrvTarget(val host: String, val receivedAt: Long)
    private val packets = LinkedHashMap<Key, Packet>()
    private val latestSrv = LinkedHashMap<SrvKey, SrvTarget>()
    private val lastCtrlMulticast = mutableMapOf<Int, Long>()
    private var addressEpoch = 0L

    @Synchronized fun epoch(): Long = addressEpoch

    @Synchronized fun invalidate() {
        addressEpoch++
        packets.clear()
        latestSrv.clear()
        lastCtrlMulticast.clear()
    }

    @Synchronized fun noteLocalSrv(family: Int, instance: String, port: Int, host: String, receivedAt: Long, epoch: Long): Boolean {
        if (epoch != addressEpoch) return false
        val key = SrvKey(instance, port)
        val latest = latestSrv[key]
        if (latest != null && latest.receivedAt > receivedAt) return latest.host == host
        latestSrv.remove(key)
        latestSrv[key] = SrvTarget(host, receivedAt)
        if (latestSrv.size > 16) latestSrv.remove(latestSrv.keys.first())
        packets.values.removeAll { packet ->
            val metadata = packet.metadata
            packet.seenAt <= receivedAt && metadata.instance == instance && metadata.port == port &&
                metadata.host != host
        }
        noteMulticast(family, receivedAt)
        return true
    }

    private fun noteMulticast(family: Int, receivedAt: Long) {
        if (receivedAt > (lastCtrlMulticast[family] ?: Long.MIN_VALUE))
            lastCtrlMulticast[family] = receivedAt
    }

    @Synchronized fun record(family: Int, bytes: ByteArray, metadata: MdnsDiagnosticPacket.Announcement,
        source: InetAddress, receivedAt: Long, epoch: Long) {
        if (epoch != addressEpoch || source !in metadata.addresses) return
        if (!noteLocalSrv(family, metadata.instance, metadata.port, metadata.host, receivedAt, epoch)) return
        val packet = Packet(family, bytes, metadata, source, receivedAt)
        val key = Key(family, metadata.instance, metadata.port, metadata.txt)
        if ((packets[key]?.seenAt ?: Long.MIN_VALUE) > receivedAt || !packet.isLive(elapsedRealtime())) return
        packets.remove(key)
        packets[key] = packet
        if (packets.size > 8) packets.remove(packets.keys.first())
    }

    @Synchronized fun eligible(name: String, port: Int, mac: String, version: String, addresses: Set<InetAddress>,
        registeredSince: Long, onlyFamily: Int?): List<Packet> {
        val now = elapsedRealtime()
        packets.values.removeAll { !it.isLive(now) }
        return packets.values.filter { packet ->
            (onlyFamily == null || packet.family == onlyFamily) && packet.seenAt >= registeredSince &&
                packet.source in addresses && packet.metadata.matches(name, port, mac, version, addresses)
        }
    }

    @Synchronized fun canSend(packet: Packet): Boolean =
        packet.isLive(elapsedRealtime()) && packet in packets.values

    @Synchronized fun replayDue(packet: Packet): Boolean =
        canSend(packet) && elapsedRealtime() - (lastCtrlMulticast[packet.family] ?: packet.seenAt) >= 1_000L

    @Synchronized fun claimReplay(packet: Packet): Boolean {
        if (!replayDue(packet)) return false
        lastCtrlMulticast[packet.family] = elapsedRealtime()
        return true
    }
}
