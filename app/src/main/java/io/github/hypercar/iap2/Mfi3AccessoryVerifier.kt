package io.github.hypercar.iap2

import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXCertPathChecker
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey

/** Hardware MFi 3 leaf signed directly by a locally pinned Apple Accessories CA. */
internal object Mfi3AccessoryVerifier {
    private const val CAPABILITIES = "1.2.840.113635.100.6.36"

    // Public production CAs _v3ca and _root_AACA2 from apple-oss-distributions/Security,
    // db15acbe6a7f257a859ad9a3bb86097bfe0679d9, tests/TrustTests/EvaluationTests/iAPTests_data.h.
    private val anchors by lazy {
        val stream = javaClass.getResourceAsStream("/io/github/hypercar/iap2/mfi3-accessory-ca.pem")
            ?: throw IOException("缺少内置 MFi 3 配件 CA")
        stream.use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input)
                .map { TrustAnchor(it as X509Certificate, null) }.toSet()
        }
    }

    private fun parameter(body: ByteArray): ByteArray {
        val field = Iap2Csm.parseParams(body).singleOrNull()
        if (field == null || field.id != 0 || field.value.isEmpty() || field.value.size + 4 != body.size)
            throw IOException("MFi 参数 0 不完整或重复")
        return field.value
    }

    fun verifyCertificate(body: ByteArray): ECPublicKey {
        val bytes = parameter(body)
        try {
            val factory = CertificateFactory.getInstance("X.509")
            val input = bytes.inputStream()
            val certificates = factory.generateCertificates(input).filterIsInstance<X509Certificate>()
            val leaf = certificates.singleOrNull { it.basicConstraints < 0 }
                ?: throw CertificateException("需要唯一配件叶证书")
            if (input.available() != 0) throw CertificateException("证书后存在多余数据")
            val key = leaf.publicKey as? ECPublicKey ?: throw CertificateException("配件公钥不是 EC")
            if (key.params.order.bitLength() != 256 || leaf.keyUsage?.firstOrNull() == false)
                throw CertificateException("配件公钥不能用于 MFi 3 签名")
            val issuers = anchors.filter { it.trustedCert.subjectX500Principal == leaf.issuerX500Principal }.toSet()
            if (issuers.isEmpty()) throw CertificateException("未收录的配件 CA: ${leaf.issuerX500Principal.name}")
            val params = PKIXParameters(issuers).apply {
                isRevocationEnabled = false // Offline accessory link, no online revocation service.
                addCertPathChecker(CapabilitiesChecker())
            }
            CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(listOf(leaf)), params)
            return key
        } catch (error: GeneralSecurityException) {
            throw IOException("MFi 3 证书链校验失败: ${error.message}", error)
        }
    }

    // The critical Apple extension is an ASN.1 OCTET STRING containing a 32-byte capability mask.
    private class CapabilitiesChecker : PKIXCertPathChecker() {
        override fun init(forward: Boolean) = Unit
        override fun isForwardCheckingSupported() = false
        override fun getSupportedExtensions() = setOf(CAPABILITIES)
        override fun check(cert: Certificate, unresolved: MutableCollection<String>) {
            val value = (cert as X509Certificate).getExtensionValue(CAPABILITIES)
            if (value == null || value.size != 36 || value[0] != 4.toByte() || value[1] != 34.toByte() ||
                value[2] != 4.toByte() || value[3] != 32.toByte())
                throw java.security.cert.CertPathValidatorException("MFi 3 能力扩展缺失或格式错误")
            unresolved.remove(CAPABILITIES)
        }
    }

    fun verifyResponse(key: ECPublicKey, challenge: ByteArray, body: ByteArray) {
        val raw = parameter(body)
        if (challenge.size != 32 || raw.size != 64) throw IOException("MFi 3 挑战或签名长度错误")
        // iAP2 supplies the 32-byte challenge digest to the coprocessor. AA03 carries fixed-width r || s.
        fun integer(offset: Int): ByteArray {
            val n = BigInteger(1, raw.copyOfRange(offset, offset + 32))
            if (n.signum() == 0 || n >= key.params.order) throw IOException("MFi 3 签名标量越界")
            val value = n.toByteArray()
            return byteArrayOf(2, value.size.toByte()) + value
        }
        val pair = integer(0) + integer(32)
        val der = byteArrayOf(0x30, pair.size.toByte()) + pair
        try {
            val verifier = Signature.getInstance("NONEwithECDSA")
            verifier.initVerify(key)
            verifier.update(challenge)
            if (!verifier.verify(der)) throw IOException("MFi 3 签名与本轮挑战不匹配")
        } catch (error: GeneralSecurityException) {
            throw IOException("MFi 3 挑战签名校验失败: ${error.message}", error)
        }
    }
}
