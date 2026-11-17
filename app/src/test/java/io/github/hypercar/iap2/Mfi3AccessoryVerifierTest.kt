package io.github.hypercar.iap2

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class Mfi3AccessoryVerifierTest {
    @Test fun alteredCertificateCannotChainToPinnedIssuer() {
        val bytes = javaClass.getResourceAsStream("/io/github/hypercar/iap2/mfi3-leaf.der")!!.use { it.readBytes() }
        Mfi3AccessoryVerifier.verifyCertificate(Iap2Csm.param(0, bytes))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { Mfi3AccessoryVerifier.verifyCertificate(Iap2Csm.param(0, bytes)) }
    }

    @Test fun signatureMustMatchBothCurrentChallengeAndLeafKey() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = keys.generateKeyPair()
        val challenge = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val signature = Signature.getInstance("NONEwithECDSAinP1363Format").run {
            initSign(pair.private)
            update(challenge)
            Iap2Csm.param(0, sign())
        }
        val pub = pair.public as ECPublicKey
        Mfi3AccessoryVerifier.verifyResponse(pub, challenge, signature)
        assertThrows(IOException::class.java) {
            Mfi3AccessoryVerifier.verifyResponse(keys.generateKeyPair().public as ECPublicKey, challenge, signature)
        }
        challenge[0] = (challenge[0].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { Mfi3AccessoryVerifier.verifyResponse(pub, challenge, signature) }
    }
}
