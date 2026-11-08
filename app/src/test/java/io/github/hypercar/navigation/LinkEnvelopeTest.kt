package io.github.hypercar.navigation

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class LinkEnvelopeTest {
    private fun envelope(plain: String): ByteArray {
        val id = "test-session".toByteArray()
        val key = ByteArray(32) { it.toByte() }; val nonce = ByteArray(12) { (it + 7).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(id)
        val encrypted = cipher.doFinal(plain.toByteArray())
        return ByteBuffer.allocate(8 + id.size + key.size + nonce.size + encrypted.size)
            .putInt(id.size).put(id).putInt(key.size).put(key).put(nonce).put(encrypted).array()
    }
    @Test fun acceptsPlainAndAuthenticatedEnvelope() {
        val json = "{\"type\":\"test\",\"data\":{\"distance\":36}}"
        assertEquals(json, LinkEnvelope.decode(json))
        val base64 = Base64.getEncoder().encodeToString(envelope(json))
        assertEquals(json, LinkEnvelope.decode(base64))
        assertEquals(json, LinkEnvelope.decode("{\"encrypted\":true,\"content\":\"$base64\"}"))
    }
    @Test fun rejectsBadTagTruncationAndHugeInput() {
        val bytes = envelope("{\"data\":1}")
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertNull(LinkEnvelope.decode(Base64.getEncoder().encodeToString(bytes)))
        assertNull(LinkEnvelope.decode(Base64.getEncoder().encodeToString(ByteArray(40))))
        assertNull(LinkEnvelope.decode("x".repeat(131073)))
    }
}
