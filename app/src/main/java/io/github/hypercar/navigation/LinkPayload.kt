package io.github.hypercar.navigation

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import org.json.JSONObject

/** Per-binding bounded transport. Large callbacks can carry a pipe or ordered base64 chunks. */
internal class LinkPayload {
    private data class Transfer(val size: Int, val chunks: Array<ByteArray?>, val started: Long, var bytes: Int = 0)
    private val transfers = linkedMapOf<String, Transfer>()

    fun accept(raw: String, now: Long): String? = runCatching {
        require(raw.length <= MAX_BYTES * 2)
        transfers.entries.removeAll { now - it.value.started > 10_000 }
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@runCatching raw
        val type = json.optString("type")
        if (type !in setOf("begin_transfer", "chunk_data", "end_transfer")) return@runCatching raw
        val id = json.getString("transferId")
        require(id.length in 1..100)
        when (type) {
            "begin_transfer" -> {
                transfers.remove(id)
                require(transfers.size < 2)
                val size = if (json.has("totalSize")) json.getLong("totalSize") else json.getLong("total_size")
                val count = json.getLong("totalChunks")
                require(size in 1..MAX_BYTES.toLong() && count in 1..128)
                transfers[id] = Transfer(size.toInt(), arrayOfNulls(count.toInt()), now)
                null
            }
            "chunk_data" -> {
                val t = transfers[id] ?: return@runCatching null
                val index = json.getLong("chunkIndex")
                val encoded = json.getString("data")
                if (index < 0 || index >= t.chunks.size || encoded.length > 65536) {
                    transfers.remove(id); return@runCatching null
                }
                val bytes = Base64.getDecoder().decode(encoded)
                val old = t.chunks[index.toInt()]
                if (old != null) { if (!old.contentEquals(bytes)) transfers.remove(id); return@runCatching null }
                if (bytes.size > 32768 || t.bytes + bytes.size > t.size) {
                    transfers.remove(id); return@runCatching null
                }
                t.chunks[index.toInt()] = bytes; t.bytes += bytes.size
                null
            }
            else -> {
                val t = transfers.remove(id) ?: return@runCatching null
                require(json.optString("status") == "success" && t.bytes == t.size && t.chunks.all { it != null })
                val out = ByteArrayOutputStream(t.size)
                t.chunks.forEach { out.write(it!!) }
                out.toByteArray().toString(Charsets.UTF_8)
            }
        }
    }.getOrNull()

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        fun read(input: InputStream): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(out.size() + n <= MAX_BYTES) { "Link payload exceeds limit" }
                out.write(buffer, 0, n)
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
