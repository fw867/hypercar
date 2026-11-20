package io.github.hypercar.net

import java.net.InetAddress

/** Read-only DNS metadata. Never publishes a partial/malformed datagram or arbitrary TXT values. */
internal data class MdnsDiagnosticPacket(val response: Boolean, val details: List<String>) {
    companion object {
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
            val questions = u16(4); val records = u16(6) + u16(8) + u16(10)
            require(questions <= 64 && records <= 128)
            var pos = 12
            val details = mutableListOf<String>()
            repeat(questions) {
                val (owner, end) = name(pos); pos = end
                val type = u16(pos); val cls = u16(pos + 2); pos += 4
                if (cls and 0x7fff == 1 && (service(owner) || owner == "_services._dns-sd._udp.local" ||
                        (type in listOf(1, 28, 255) && owner.endsWith(".local"))))
                    details += "Q name=$owner type=$type QU=${cls and 0x8000 != 0}"
            }
            repeat(records) {
                val (owner, end) = name(pos); pos = end
                val type = u16(pos); val cls = u16(pos + 2)
                val ttl = (u16(pos + 4).toLong() shl 16) or u16(pos + 6).toLong()
                val n = u16(pos + 8); val start = pos + 10; pos = start + n
                require(pos <= bytes.size)
                if (cls and 0x7fff != 1) return@repeat
                val value: String? = when (type) {
                    12 -> {
                        val (target, next) = name(start); require(next <= pos)
                        if (service(owner) || service(target)) "target=$target" else null
                    }
                    33 -> {
                        require(n >= 7)
                        val (target, next) = name(start + 6); require(next <= pos)
                        if (service(owner)) "port=${u16(start + 4)} host=$target" else null
                    }
                    16 -> {
                        var i = start; val allowed = mutableListOf<String>()
                        while (i < pos) {
                            val len = bytes[i++].toInt() and 255; require(i + len <= pos)
                            val txt = String(bytes, i, len, Charsets.UTF_8); i += len
                            if (txt.substringBefore('=').lowercase() in listOf("id", "srcvers")) allowed += safe(txt)
                        }
                        if (service(owner)) "txt=[${allowed.joinToString(",")}]" else null
                    }
                    1, 28 -> {
                        require(n == if (type == 1) 4 else 16)
                        if (owner.endsWith(".local")) "address=${InetAddress.getByAddress(bytes.copyOfRange(start, pos)).hostAddress}" else null
                    }
                    else -> null
                }
                if (value != null) details += "RR name=$owner type=$type ttl=$ttl flush=${cls and 0x8000 != 0} $value"
            }
            MdnsDiagnosticPacket(flags and 0x8000 != 0, details)
        }.getOrNull()
    }
}
