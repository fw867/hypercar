package io.github.hypercar.navigation

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Public Link callback framing; the envelope carries its per-message key, not an authorization key. */
internal object LinkEnvelope {
    fun decode(raw: String): String? = runCatching {
        require(raw.length <= 131072)
        var content = raw.trim()
        if (content.startsWith("{")) {
            val json = JSONObject(content)
            if (!json.optBoolean("encrypted")) return@runCatching content
            content = json.getString("content")
        }
        val bytes = Base64.getDecoder().decode(content.filterNot(Char::isWhitespace))
        require(bytes.size in 36..65536)
        val b = ByteBuffer.wrap(bytes)
        val idSize = b.int
        require(idSize in 1..99 && b.remaining() >= idSize + 4)
        val id = ByteArray(idSize).also(b::get)
        val keySize = b.int
        require(keySize in setOf(16, 24, 32) && b.remaining() >= keySize + 28)
        val key = ByteArray(keySize).also(b::get)
        try {
            val nonce = ByteArray(12).also(b::get)
            val encrypted = ByteArray(b.remaining()).also(b::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(id)
            val plain = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            val json = JSONObject(plain)
            if (json.length() == 1 && json.has("data") && json.opt("data") is String) json.getString("data") else plain
        } finally { key.fill(0) }
    }.getOrNull()
}
