package io.github.hypercar.net

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet6Address
import java.net.InetAddress

/** Query-local cache; a malformed datagram never publishes partial records. No DNS lookups. */
internal class AirplayDnsRecords(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Endpoint(val host: String, val port: Int, val instance: String, val model: String?, val targetHost: String = "", val addresses: List<String> = listOf(host))
    data class Question(val name: String, val type: Int)
    private data class Record(val owner: String, val type: Int, val value: String, val expires: Long, val flush: Boolean, val received: Long, val goodbye: Boolean)
    private val cache = mutableListOf<Record>()

    fun accept(data: ByteArray, length: Int, scopeId: Int = 0) {
        if (length !in 12..data.size) return
        val bytes = data.copyOf(length)
        fun u16(i: Int): Int {
            require(i >= 0 && i + 2 <= length)
            return ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)
        }
        fun name(start: Int): Pair<String, Int> {
            var cursor = start; var end = -1; var hops = 0; var total = 0
            val labels = mutableListOf<String>()
            while (true) {
                require(cursor in 0 until length && hops++ < 128)
                val size = bytes[cursor].toInt() and 255
                when {
                    size == 0 -> return labels.joinToString(".").lowercase() to if (end >= 0) end else cursor + 1
                    size and 0xc0 == 0xc0 -> {
                        val pointer = u16(cursor) and 0x3fff
                        if (end < 0) end = cursor + 2
                        cursor = pointer
                    }
                    else -> {
                        require(size <= 63 && cursor + size + 1 <= length)
                        total += size + 1; require(total <= 254)
                        labels += String(bytes, cursor + 1, size, Charsets.UTF_8)
                        cursor += size + 1
                    }
                }
            }
        }
        val decoded = mutableListOf<Record>()
        runCatching {
            require(u16(2) and 0x800f == 0x8000)
            var pos = 12
            repeat(u16(4)) { val (_, end) = name(pos); pos = end + 4; require(pos <= length) }
            val count = u16(6) + u16(8) + u16(10)
            require(count <= length / 11)
            repeat(count) {
                val (owner, end) = name(pos); pos = end
                val type = u16(pos); val rawClass = u16(pos + 2)
                val ttl = (u16(pos + 4).toLong() shl 16) or u16(pos + 6).toLong()
                val size = u16(pos + 8); val start = pos + 10; pos = start + size
                require(pos <= length)
                if (rawClass and 0x7fff != 1) return@repeat
                val value = when (type) {
                    12 -> if (owner == SERVICE) name(start).also { require(it.second <= pos) }.first else null
                    33 -> if (owner.endsWith(".$SERVICE")) {
                        require(size >= 7)
                        val port = u16(start + 4); require(port in 1..65535)
                        val (host, nameEnd) = name(start + 6); require(nameEnd <= pos)
                        "$port|$host"
                    } else null
                    1, 28 -> {
                        require(size == if (type == 1) 4 else 16)
                        val raw = bytes.copyOfRange(start, pos)
                        val ip = InetAddress.getByAddress(raw)
                        if (ip is Inet6Address && ip.isLinkLocalAddress && scopeId > 0)
                            Inet6Address.getByAddress(null, raw, scopeId).hostAddress else ip.hostAddress
                    }
                    16 -> if (owner.endsWith(".$SERVICE")) {
                        var i = start; var model = ""
                        while (i < pos) {
                            val n = bytes[i++].toInt() and 255; require(i + n <= pos)
                            val text = String(bytes, i, n, Charsets.UTF_8); i += n
                            if (text.startsWith("model=", true)) model = text.substringAfter('=')
                        }
                        model
                    } else null
                    else -> null
                }
                if (value != null) {
                    val received = now()
                    decoded += Record(owner, type, value, received + ttl * 1000, rawClass and 0x8000 != 0, received, ttl == 0L)
                }
            }
        }.onSuccess {
            val time = now()
            // RFC 6762 10.2: preserve recently received siblings across packets/interfaces;
            // older flushed records expire after one second, allowing a peer to rescue them.
            val flushSets = decoded.filter { it.flush && !it.goodbye }.map { it.owner to it.type }.toSet()
            for (i in cache.indices) {
                val old = cache[i]
                if ((old.owner to old.type) in flushSets && time - old.received >= 1000)
                    cache[i] = old.copy(expires = minOf(old.expires, time + 1000))
            }
            decoded.forEach { record ->
                if (record.goodbye) {
                    // Goodbye affects this exact RR only, never dependent SRV/TXT records.
                    for (i in cache.indices) {
                        val old = cache[i]
                        if (old.owner == record.owner && old.type == record.type && old.value == record.value)
                            cache[i] = old.copy(expires = minOf(old.expires, time + 1000))
                    }
                } else {
                    cache.removeAll { it.owner == record.owner && it.type == record.type && it.value == record.value }
                    cache += record
                }
            }
        }
    }

    private fun prune() { val time = now(); cache.removeAll { it.expires <= time } }

    fun pendingQuestions(): List<Question> {
        prune()
        val questions = linkedSetOf(Question(SERVICE, 12))
        val instances = cache.filter { it.type == 12 && it.owner == SERVICE }.map { it.value } +
            cache.filter { it.type == 33 }.map { it.owner }
        instances.distinct().forEach { instance ->
            if (cache.none { it.owner == instance && it.type == 33 }) questions += Question(instance, 33)
            if (cache.none { it.owner == instance && it.type == 16 }) questions += Question(instance, 16)
        }
        cache.filter { it.type == 33 }.groupBy { it.owner }.values.map { it.last() }.forEach { service ->
            val host = service.value.substringAfter('|')
            // Query both even when one family already resolved; the peer may have several addresses.
            if (cache.none { it.owner == host && it.type == 1 }) questions += Question(host, 1)
            if (cache.none { it.owner == host && it.type == 28 }) questions += Question(host, 28)
        }
        return questions.toList()
    }

    fun first(accept: (Endpoint) -> Boolean = { true }): Endpoint? {
        prune()
        cache.filter { it.type == 33 }.groupBy { it.owner }.values.map { it.last() }.forEach { service ->
            val port = service.value.substringBefore('|').toInt(); val host = service.value.substringAfter('|')
            val model = cache.lastOrNull { it.owner == service.owner && it.type == 16 }?.value?.ifEmpty { null }
            cache.filter { it.owner == host && (it.type == 1 || it.type == 28) }.forEach { address ->
                val candidates = cache.filter { it.owner == host && (it.type == 1 || it.type == 28) }.map { it.value }.distinct()
                val endpoint = Endpoint(address.value, port, service.owner, model, host,
                    listOf(address.value) + candidates.filter { it != address.value })
                if (accept(endpoint)) return endpoint
            }
        }
        return null
    }

    companion object {
        const val SERVICE = "_airplay._tcp.local"
        fun query(questions: List<Question>): ByteArray = ByteArrayOutputStream().apply {
            val out = DataOutputStream(this)
            out.writeShort(0); out.writeShort(0); out.writeShort(questions.size)
            out.writeShort(0); out.writeShort(0); out.writeShort(0)
            questions.forEach { question ->
                question.name.trimEnd('.').split('.').forEach { label ->
                    val bytes = label.toByteArray(Charsets.UTF_8); require(bytes.size in 1..63)
                    out.writeByte(bytes.size); out.write(bytes)
                }
                out.writeByte(0); out.writeShort(question.type); out.writeShort(1)
            }
        }.toByteArray()
    }
}
