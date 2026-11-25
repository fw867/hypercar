package io.github.hypercar.net

import java.net.InetAddress
import java.net.Inet4Address

/** Read-only DNS metadata. Never publishes a partial/malformed datagram or arbitrary TXT values. */
internal data class MdnsDiagnosticPacket(
    val response: Boolean,
    val details: List<String>,
    val announcement: Announcement? = null,
    val ctrlQuestion: Boolean = false,
    val carPlaySources: List<CarPlaySource> = emptyList(),
) {
    /** 网络中广播的 CarPlay 源（实例名不含 _carplay-ctrl._tcp.local 后缀）。 */
    data class CarPlaySource(val instance: String, val host: String, val port: Int,
        val txt: Set<String>, val addresses: Set<InetAddress>)
    data class Announcement(
        val instance: String, val host: String, val port: Int, val txt: Set<String>, val addresses: Set<InetAddress>,
        val ttlMillis: Long,
    ) {
        fun matches(name: String, port: Int, mac: String, version: String, linkAddresses: Set<InetAddress>): Boolean =
            instance == "${name.lowercase()}.$CTRL" && this.port == port &&
                txt == setOf("id=${mac.lowercase()}", "srcvers=$version") && linkAddresses.containsAll(addresses)
    }

    private data class Record(val owner: String, val type: Int, val ttl: Long, val target: String? = null,
        val port: Int = 0, val txt: Set<String> = emptySet(), val address: InetAddress? = null)

    companion object {
        private const val CTRL = "_carplay-ctrl._tcp.local"
        private const val SERVICES = "_services._dns-sd._udp.local"
        private fun reverseName(address: InetAddress): String = if (address is Inet4Address) {
            address.address.reversed().joinToString(".") { (it.toInt() and 255).toString() } + ".in-addr.arpa"
        } else {
            address.address.reversed().joinToString(".") {
                val byte = it.toInt() and 255
                "${(byte and 15).toString(16)}.${(byte shr 4).toString(16)}"
            } + ".ip6.arpa"
        }

        private fun completeAnnouncement(records: List<Record>): Announcement? {
            if (records.any { it.ttl == 0L }) return null
            val ptr = records.singleOrNull { it.type == 12 && it.owner == CTRL } ?: return null
            val instance = ptr.target?.takeIf { it.endsWith(".$CTRL") } ?: return null
            val srv = records.singleOrNull { it.type == 33 && it.owner == instance && it.port != 0 } ?: return null
            val host = srv.target?.takeIf { it.endsWith(".local") } ?: return null
            val txt = records.singleOrNull { it.type == 16 && it.owner == instance && it.txt.size == 2 } ?: return null
            val addresses = records.filter { it.owner == host }.mapNotNull { it.address }.toSet()
            // Android NSD also bundles reverse PTRs and their NSEC records for these addresses.
            val reverseNames = addresses.map(::reverseName).toSet()
            val reverseOwners = records.filter { it.type == 12 && it.owner in reverseNames && it.target == host }
                .map { it.owner }.toSet()
            if (addresses.isEmpty() || records.any {
                when (it.type) {
                    1, 28 -> it.owner != host
                    12 -> it != ptr && !(it.owner == SERVICES && it.target == CTRL) &&
                        !(it.owner in reverseOwners && it.target == host)
                    16 -> it != txt
                    33 -> it != srv
                    47 -> it.target != it.owner || (it.owner != host && it.owner != instance && it.owner !in reverseOwners)
                    else -> true
                }
            }) return null
            return Announcement(instance, host, srv.port, txt.txt, addresses, records.minOf { it.ttl } * 1_000)
        }

        fun decode(bytes: ByteArray): MdnsDiagnosticPacket? = runCatching {
            require(bytes.size in 12..9000)
            fun u16(i: Int): Int {
                require(i >= 0 && i + 2 <= bytes.size)
                return ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)
            }
            fun safe(text: String) = text.map { if (it.isISOControl()) '?' else it }.joinToString("").take(255)
            fun name(start: Int): Pair<String, Int> {
                var pos = start; var end = -1; var hops = 0; var size = 0
                val labels = mutableListOf<String>()
                while (true) {
                    require(pos in bytes.indices && hops++ < 128)
                    val n = bytes[pos].toInt() and 255
                    when {
                        n == 0 -> return safe(labels.joinToString(".")).lowercase() to if (end < 0) pos + 1 else end
                        n and 0xc0 == 0xc0 -> { if (end < 0) end = pos + 2; pos = u16(pos) and 0x3fff }
                        else -> {
                            require(n <= 63 && pos + 1 + n <= bytes.size)
                            size += n + 1; require(size <= 254)
                            labels += String(bytes, pos + 1, n, Charsets.UTF_8); pos += n + 1
                        }
                    }
                }
            }
            fun service(s: String) = listOf("_carplay-ctrl._tcp.local", "_airplay._tcp.local").any {
                s == it || s.endsWith(".$it")
            }
            val flags = u16(2)
            require(flags and 0x780f == 0)
            val response = flags and 0x8000 != 0
            val questions = u16(4)
            val answers = u16(6)
            val authorityEnd = answers + u16(8)
            val records = authorityEnd + u16(10)
            require(questions <= 64 && records <= 128)
            var pos = 12
            val details = mutableListOf<String>()
            var ctrlQuestion = false
            val srcServices = mutableMapOf<String, Pair<String, Int>>()
            val withdrawnSources = mutableSetOf<String>()
            val srcTxts = mutableMapOf<String, Set<String>>()
            val srcAddresses = mutableMapOf<String, MutableSet<InetAddress>>()
            val replayRecords = if (flags == 0x8400 && u16(0) == 0 && questions == 0 && answers > 0 && authorityEnd == answers)
                mutableListOf<Record>() else null
            repeat(questions) {
                val (owner, end) = name(pos); pos = end
                val type = u16(pos); val cls = u16(pos + 2); pos += 4
                if (!response && cls and 0x7fff == 1 && (owner == CTRL || owner.endsWith(".$CTRL"))) ctrlQuestion = true
                if (cls and 0x7fff == 1 && (service(owner) || owner == "_services._dns-sd._udp.local" ||
                        (type in listOf(1, 28, 255) && owner.endsWith(".local"))))
                    details += "Q section=question name=$owner type=$type QU=${cls and 0x8000 != 0}"
            }
            repeat(records) { index ->
                // mDNS queries carry cached answers in Answer and proposed records in Authority.
                val section = when {
                    index < answers -> if (response) "response-answer" else "known-answer"
                    index < authorityEnd -> if (response) "response-authority" else "probe-authority"
                    else -> "additional"
                }
                val (owner, end) = name(pos); pos = end
                val type = u16(pos); val cls = u16(pos + 2)
                val ttl = (u16(pos + 4).toLong() shl 16) or u16(pos + 6).toLong()
                val n = u16(pos + 8); val start = pos + 10; pos = start + n
                require(pos <= bytes.size)
                if (cls and 0x7fff != 1) {
                    replayRecords?.add(Record(owner, -1, ttl))
                    return@repeat
                }
                var replay = Record(owner, type, ttl)
                val value: String? = when (type) {
                    12 -> {
                        val (target, next) = name(start); require(next == pos)
                        replay = replay.copy(target = target)
                        if (service(owner) || service(target)) "target=$target" else null
                    }
                    33 -> {
                        require(n >= 7)
                        val (target, next) = name(start + 6); require(next == pos)
                        replay = replay.copy(target = target, port = u16(start + 4))
                        if (response && owner.endsWith(".$CTRL")) {
                            val port = u16(start + 4)
                            if (ttl > 0 && port > 0 && target.isNotBlank()) srcServices[owner] = target to port
                            else withdrawnSources.add(owner)
                        }
                        if (service(owner)) "port=${u16(start + 4)} host=$target" else null
                    }
                    16 -> {
                        var i = start
                        val allowed = mutableListOf<String>()
                        var count = 0
                        while (i < pos) {
                            val len = bytes[i++].toInt() and 255; require(i + len <= pos)
                            val txt = String(bytes, i, len, Charsets.UTF_8); i += len
                            count++
                            if (txt.substringBefore('=').lowercase() in listOf("id", "srcvers")) allowed += safe(txt)
                        }
                        // Do not retain unknown TXT fields or replay packets that contain any.
                        replay = replay.copy(txt = allowed.map { it.lowercase() }.toSet().takeIf { it.size == count }.orEmpty())
                        if (response && ttl > 0 && owner.endsWith(".$CTRL") && allowed.isNotEmpty())
                            srcTxts[owner] = allowed.map { it.lowercase() }.toSet()
                        if (service(owner)) "txt=[${allowed.joinToString(",")}]" else null
                    }
                    1, 28 -> {
                        require(n == if (type == 1) 4 else 16)
                        val address = InetAddress.getByAddress(bytes.copyOfRange(start, pos))
                        if (response && ttl > 0) srcAddresses.getOrPut(owner) { mutableSetOf() }.add(address)
                        replay = replay.copy(address = address)
                        if (owner.endsWith(".local")) "address=${address.hostAddress}" else null
                    }
                    47 -> {
                        if (replayRecords != null) {
                            val (target, next) = name(start)
                            require(next < pos)
                            replay = replay.copy(target = target)
                        }
                        null
                    }
                    else -> null
                }
                replayRecords?.add(replay)
                if (value != null) details += "RR section=$section name=$owner type=$type ttl=$ttl flush=${cls and 0x8000 != 0} $value"
            }
            val announcement = replayRecords?.takeIf { pos == bytes.size }?.let(::completeAnnouncement)
            val carPlaySources = srcServices
                .filterKeys { it !in withdrawnSources }
                .map { (inst, service) -> CarPlaySource(inst.removeSuffix(".$CTRL"), service.first, service.second,
                    srcTxts[inst].orEmpty(), srcAddresses[service.first].orEmpty().toSet()) }
                .distinct()
            MdnsDiagnosticPacket(response, details, announcement, ctrlQuestion, carPlaySources)
        }.getOrNull()
    }
}
