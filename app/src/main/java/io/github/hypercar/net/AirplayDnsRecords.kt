package io.github.hypercar.net

/** Per-query DNS state. Reads answers, authority and additional records with bounded names. */
internal class AirplayDnsRecords(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Endpoint(val host: String, val port: Int, val instance: String, val model: String?)
    private data class Record<T>(val value: T, val expires: Long)
    private val services = mutableMapOf<String, Record<Pair<Int, String>>>()
    private val addresses = mutableMapOf<String, Record<String>>()
    private val models = mutableMapOf<String, Record<String>>()

    fun accept(data: ByteArray, length: Int) {
        if (length !in 12..data.size) return
        val bytes = data.copyOf(length)
        fun u16(i: Int): Int {
            require(i >= 0 && i + 2 <= length)
            return ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)
        }
        fun name(start: Int): Pair<String, Int> {
            var cursor = start; var end = -1; var hops = 0
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
                        labels += String(bytes, cursor + 1, size, Charsets.US_ASCII)
                        cursor += size + 1
                    }
                }
            }
        }
        // Decode the entire datagram before publishing: a truncated tail must not leak records.
        val actions = mutableListOf<() -> Unit>()
        runCatching {
            require(u16(2) and 0x800f == 0x8000) // Response, no error.
            var pos = 12
            repeat(u16(4)) { val (_, end) = name(pos); pos = end + 4; require(pos <= length) }
            val count = u16(6) + u16(8) + u16(10)
            require(count <= length / 11)
            repeat(count) {
                val (owner, end) = name(pos); pos = end
                val type = u16(pos); val dnsClass = u16(pos + 2) and 0x7fff
                val ttl = (u16(pos + 4).toLong() shl 16) or u16(pos + 6).toLong()
                val size = u16(pos + 8); val start = pos + 10; pos = start + size
                require(pos <= length)
                val expires = now() + ttl * 1000
                if (dnsClass == 1) when (type) {
                    33 -> if (owner.endsWith("._airplay._tcp.local")) {
                        require(size >= 7)
                        val port = u16(start + 4)
                        val (host, nameEnd) = name(start + 6) // Skip priority, weight and port.
                        require(nameEnd <= pos && port in 1..65535)
                        actions += { if (ttl == 0L) services.remove(owner) else services[owner] = Record(port to host, expires) }
                    }
                    1 -> {
                        require(size == 4)
                        val ip = (start until start + 4).joinToString(".") { (bytes[it].toInt() and 255).toString() }
                        actions += { if (ttl == 0L) addresses.remove(owner) else addresses[owner] = Record(ip, expires) }
                    }
                    16 -> if (owner.endsWith("._airplay._tcp.local")) {
                        var i = start
                        while (i < pos) {
                            val n = bytes[i++].toInt() and 255; require(i + n <= pos)
                            val text = String(bytes, i, n, Charsets.UTF_8); i += n
                            if (text.startsWith("model=")) {
                                val model = text.removePrefix("model=")
                                actions += { if (ttl == 0L) models.remove(owner) else models[owner] = Record(model, expires) }
                            }
                        }
                    }
                }
            }
        }.onSuccess { actions.forEach { it() } }
    }

    fun first(accept: (Endpoint) -> Boolean = { true }): Endpoint? {
        val time = now()
        services.entries.removeAll { it.value.expires <= time }
        addresses.entries.removeAll { it.value.expires <= time }
        models.entries.removeAll { it.value.expires <= time }
        return services.entries.firstNotNullOfOrNull { (instance, service) ->
            val (port, host) = service.value
            addresses[host]?.let { Endpoint(it.value, port, instance, models[instance]?.value).takeIf(accept) }
        }
    }
}
