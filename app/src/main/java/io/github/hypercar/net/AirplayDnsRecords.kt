package io.github.hypercar.net

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet6Address
import java.net.InetAddress

/** Query-local cache; a malformed datagram never publishes partial records. No DNS lookups. */
internal class AirplayDnsRecords(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Endpoint(val host: String, val port: Int, val instance: String, val model: String?, val targetHost: String = "", val addresses: List<String> = listOf(host))
    data class Question(val labels: List<String>, val type: Int) {
        constructor(name: String, type: Int) : this(name.trimEnd('.').split('.'), type)
    }
    private data class Record(
        val owner: List<String>, val type: Int, val value: String, val target: List<String>?,
        val expires: Long, val flush: Boolean, val received: Long, val goodbye: Boolean,
    ) {
        fun matches(other: Record): Boolean =
            owner == other.owner && type == other.type && value == other.value && target == other.target
    }
    private val cache = mutableListOf<Record>()
    private val browsedInstances = mutableSetOf<List<String>>()

    fun accept(data: ByteArray, length: Int, scopeId: Int = 0) {
        if (length !in 12..data.size) return
        val bytes = data.copyOf(length)
        fun u16(i: Int): Int {
            require(i >= 0 && i + 2 <= length)
            return ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)
        }
        fun name(start: Int): Pair<List<String>, Int> {
            var cursor = start; var end = -1; var hops = 0; var total = 0
            val labels = mutableListOf<String>()
            while (true) {
                require(cursor in 0 until length && hops++ < 128)
                val size = bytes[cursor].toInt() and 255
                when {
                    size == 0 -> return labels to if (end >= 0) end else cursor + 1
                    size and 0xc0 == 0xc0 -> {
                        val pointer = u16(cursor) and 0x3fff
                        if (end < 0) end = cursor + 2
                        cursor = pointer
                    }
                    else -> {
                        require(size <= 63 && cursor + size + 1 <= length)
                        total += size + 1; require(total <= 254)
                        // DNS folds ASCII case. Literal punctuation and UTF-8
                        // characters inside an instance label must survive resolve.
                        val label = String(bytes, cursor + 1, size, Charsets.UTF_8)
                        labels += buildString(label.length) {
                            label.forEach { append(if (it in 'A'..'Z') it.lowercaseChar() else it) }
                        }
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
                val isInstance = owner.size == SERVICE_LABELS.size + 1 && owner.drop(1) == SERVICE_LABELS
                var target: List<String>? = null
                val value = when (type) {
                    12 -> if (owner == SERVICE_LABELS) {
                        val (instance, nameEnd) = name(start)
                        require(nameEnd <= pos)
                        target = instance
                        ""
                    } else null
                    33 -> if (isInstance) {
                        require(size >= 7)
                        val port = u16(start + 4); require(port in 1..65535)
                        val (host, nameEnd) = name(start + 6); require(nameEnd <= pos)
                        target = host
                        port.toString()
                    } else null
                    1, 28 -> {
                        require(size == if (type == 1) 4 else 16)
                        val raw = bytes.copyOfRange(start, pos)
                        val ip = InetAddress.getByAddress(raw)
                        if (ip is Inet6Address && ip.isLinkLocalAddress && scopeId > 0)
                            Inet6Address.getByAddress(null, raw, scopeId).hostAddress else ip.hostAddress
                    }
                    16 -> if (isInstance) {
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
                    decoded += Record(owner, type, value, target, received + ttl * 1000, rawClass and 0x8000 != 0, received, ttl == 0L)
                }
            }
        }.onSuccess {
            val time = now()
            prune(time)
            // RFC 6762 10.2: preserve recently received siblings across packets/interfaces;
            // older flushed records expire after one second, allowing a peer to rescue them.
            val flushSets = decoded.filter { it.type != 12 && it.flush && !it.goodbye }
                .map { it.owner to it.type }.toSet()
            for (i in cache.indices) {
                val old = cache[i]
                if ((old.owner to old.type) in flushSets && time - old.received >= 1000)
                    cache[i] = old.copy(expires = minOf(old.expires, time + 1000))
            }
            decoded.forEach { record ->
                if (record.type == 12) browsedInstances += requireNotNull(record.target)
                if (record.goodbye) {
                    // Keep this exact record available for a rescue announcement.
                    var matched = false
                    for (i in cache.indices) {
                        val old = cache[i]
                        if (old.matches(record)) {
                            matched = true
                            cache[i] = old.copy(
                                expires = minOf(old.expires, time + 1000),
                                received = if (old.type == 12 && !old.goodbye) time else old.received,
                                goodbye = true,
                            )
                        }
                    }
                    if (!matched && record.type == 12) cache += record.copy(expires = time + 1000)
                } else {
                    cache.removeAll { it.matches(record) }
                    cache += record
                }
            }
        }
    }

    private fun prune(time: Long = now()) {
        val expiredInstances = cache.filter { it.type == 12 && it.expires <= time }
            .associate { requireNotNull(it.target) to if (it.goodbye) it.received else it.expires }
        // SRV/TXT from the old listing must not be reused for a new PTR.
        // Keep fresher split replies and shared host address records.
        cache.removeAll { record ->
            record.expires <= time || ((record.type == 33 || record.type == 16) &&
                expiredInstances[record.owner]?.let { record.received <= it } == true)
        }
    }

    fun pendingQuestions(): List<Question> {
        prune()
        val questions = linkedSetOf(Question(SERVICE_LABELS, 12))
        val instances = cache.filter { it.type == 12 }.mapNotNull { it.target } +
            cache.filter { it.type == 33 }.map { it.owner }
        instances.distinct().forEach { instance ->
            if (cache.none { it.owner == instance && it.type == 33 }) questions += Question(instance, 33)
            if (cache.none { it.owner == instance && it.type == 16 }) questions += Question(instance, 16)
        }
        cache.filter { it.type == 33 }.groupBy { it.owner }.values.map { it.last() }.forEach { service ->
            val host = requireNotNull(service.target)
            // Query both even when one family already resolved; the peer may have several addresses.
            if (cache.none { it.owner == host && it.type == 1 }) questions += Question(host, 1)
            if (cache.none { it.owner == host && it.type == 28 }) questions += Question(host, 28)
        }
        return questions.toList()
    }

    fun first(accept: (Endpoint) -> Boolean = { true }): Endpoint? {
        prune()
        val listed = cache.filter { it.type == 12 && !it.goodbye }.mapNotNull { it.target }.toSet()
        cache.filter { it.type == 33 && (it.owner !in browsedInstances || it.owner in listed) }
            .groupBy { it.owner }.values.map { it.last() }.forEach { service ->
            val port = service.value.toInt()
            val host = requireNotNull(service.target)
            val model = cache.lastOrNull { it.owner == service.owner && it.type == 16 }?.value?.ifEmpty { null }
            cache.filter { it.owner == host && (it.type == 1 || it.type == 28) }.forEach { address ->
                val candidates = cache.filter { it.owner == host && (it.type == 1 || it.type == 28) }.map { it.value }.distinct()
                val endpoint = Endpoint(address.value, port, service.owner.joinToString("."), model, host.joinToString("."),
                    listOf(address.value) + candidates.filter { it != address.value })
                if (accept(endpoint)) return endpoint
            }
        }
        return null
    }

    companion object {
        const val SERVICE = "_airplay._tcp.local"
        private val SERVICE_LABELS = SERVICE.split('.')
        fun query(questions: List<Question>): ByteArray = ByteArrayOutputStream().apply {
            val out = DataOutputStream(this)
            out.writeShort(0); out.writeShort(0); out.writeShort(questions.size)
            out.writeShort(0); out.writeShort(0); out.writeShort(0)
            questions.forEach { question ->
                question.labels.forEach { label ->
                    val bytes = label.toByteArray(Charsets.UTF_8); require(bytes.size in 1..63)
                    out.writeByte(bytes.size); out.write(bytes)
                }
                out.writeByte(0); out.writeShort(question.type); out.writeShort(1)
            }
        }.toByteArray()
    }
}
