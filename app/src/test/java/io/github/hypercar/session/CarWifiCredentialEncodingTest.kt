package io.github.hypercar.session

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class CarWifiCredentialEncodingTest {
    @Test fun oldEncryptedPayloadRemainsReadableWithoutSecurityOrChannel() {
        val legacy = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { it.writeUTF("fixture"); it.writeUTF("password") }
        }.toByteArray()
        val restored = CarHotspotReconnect.Credentials.decode(legacy)
        assertEquals("fixture", restored.ssid)
        assertEquals("password", restored.passphrase)
        assertNull(restored.securityType)
    }

    @Test fun savedSaeCredentialsRetainTheirSecurityRatherThanRejoiningAsWpa2() {
        val original = CarHotspotReconnect.Credentials("fixture", "password", 4)
        val restored = CarHotspotReconnect.Credentials.decode(original.encode())
        assertEquals("fixture", restored.ssid)
        assertEquals("password", restored.passphrase)
        assertEquals(4, restored.securityType)
    }
}
