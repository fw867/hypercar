package io.github.hypercar.iap2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2HandshakeBeginTest {
    @Test fun credentialOmitsTheFirstPairSuffix() {
        val line = Iap2Bluetooth.handshakeBeginLine("HS", "9C:DF:03:5D:FB:90", true, true)
        assertTrue(line.startsWith("BEGIN direction=HS target=9C:DF:03:5D:FB:90 identity=true receiverCredential=true"))
        assertFalse(line.contains("无本车凭证"))
    }

    @Test fun missingCredentialKeepsTheFirstPairSuffix() {
        val line = Iap2Bluetooth.handshakeBeginLine("HS", "自动", false, false)
        assertTrue(line.contains("identity=false receiverCredential=false"))
        assertTrue(line.endsWith("；无本车凭证=首次或未知，不主动断系统配置文件"))
    }
}
