package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

internal object MfiCacheFixture {
    val der: ByteArray get() = Base64.getDecoder().decode("MIIBUDCB96ADAgECAghvCGFvVowvIDAKBggqhkjOPQQDAjAcMRowGAYDVQQDExFIeXBlckNhci1NRmktVGVzdDAgFw0yNjExMTUwOTQ4MjJaGA8yMTI2MTAyMjA5NDgyMlowHDEaMBgGA1UEAxMRSHlwZXJDYXItTUZpLVRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQcZlovtMi0GQh9T0tFmpdv0z3pthneiRasmTufZbrPj1TUW7b1tceZJeJ9PmxcTgMItz0t7Qgt95zGjcyrB9wroyEwHzAdBgNVHQ4EFgQUFaRw6UKT+cgsKT61+7OspSOjezYwCgYIKoZIzj0EAwIDSAAwRQIhAKuZGRogbCIA8DnEzHiskGm8JhumQe83zOnJx2ieWeHuAiALiLvfLKsvXqotLrjUZVjpMNbbJ/hQnImKjBbquirWaQ==")
    val certificate: ByteArray get() = Iap2Csm.param(0,der)
    val serial: ByteArray get() = (CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate).serialNumber.toByteArray()
}

class MfiCertificateCacheTest {
    private val peer = "00:11:22:33:44:55"
    private val now = System.currentTimeMillis()
    @Before fun reset() { MfiCertificateCache.clearAll(null) }
    @Test fun cachedCertificateRequiresSamePeerAndMatchingX509Serial() {
        assertTrue(MfiCertificateCache.remember(null,peer,MfiCacheFixture.serial,MfiCacheFixture.certificate,now))
        assertArrayEquals(MfiCacheFixture.certificate,MfiCertificateCache.load(null,peer.lowercase(),MfiCacheFixture.serial,now))
        assertNull(MfiCertificateCache.load(null,"00:11:22:33:44:66",MfiCacheFixture.serial,now))
        assertNull(MfiCertificateCache.load(null,null,MfiCacheFixture.serial,now))
        assertFalse(MfiCertificateCache.remember(null,null,MfiCacheFixture.serial,MfiCacheFixture.certificate,now))
        assertNull(MfiCertificateCache.load(null,peer,byteArrayOf(42),now))
    }
    @Test fun serialPaddingIsNormalizedButWrongOrMalformedCertificateCannotBeRemembered() {
        val padded=byteArrayOf(0,0)+MfiCacheFixture.serial
        assertTrue(MfiCertificateCache.remember(null,peer,padded,MfiCacheFixture.certificate,now))
        assertNotNull(MfiCertificateCache.load(null,peer,MfiCacheFixture.serial,now))
        assertFalse(MfiCertificateCache.remember(null,peer,byteArrayOf(42),MfiCacheFixture.certificate,now))
        assertFalse(MfiCertificateCache.remember(null,peer,MfiCacheFixture.serial,Iap2Csm.param(0,byteArrayOf(1)),now))
        assertFalse(MfiCertificateCache.remember(null,peer,MfiCacheFixture.serial,MfiCacheFixture.certificate+byteArrayOf(0),now))
        assertFalse(MfiCertificateCache.remember(null,peer,byteArrayOf(),MfiCacheFixture.certificate,now))
    }
    @Test fun expiredOrFutureEntryFallsBackAndSingleClearPreservesOtherCar() {
        val other="00:11:22:33:44:66"
        assertTrue(MfiCertificateCache.remember(null,peer,MfiCacheFixture.serial,MfiCacheFixture.certificate,now))
        assertTrue(MfiCertificateCache.remember(null,other,MfiCacheFixture.serial,MfiCacheFixture.certificate,now))
        MfiCertificateCache.forget(null,peer)
        assertNull(MfiCertificateCache.load(null,peer,MfiCacheFixture.serial,now))
        assertNotNull(MfiCertificateCache.load(null,other,MfiCacheFixture.serial,now))
        assertNull(MfiCertificateCache.load(null,other,MfiCacheFixture.serial,now+MfiCertificateCache.MAX_AGE_MILLIS+1))
        MfiCertificateCache.remember(null,other,MfiCacheFixture.serial,MfiCacheFixture.certificate,now)
        assertNull(MfiCertificateCache.load(null,other,MfiCacheFixture.serial,now-1))
    }
    @Test fun boundedCacheEvictsOldestAndFullResetDropsAll() {
        for(i in 0..MfiCertificateCache.MAX_ENTRIES) {
            assertTrue(MfiCertificateCache.remember(null,"00:11:22:33:44:%02X".format(i),MfiCacheFixture.serial,MfiCacheFixture.certificate,now+i))
        }
        assertNull(MfiCertificateCache.load(null,"00:11:22:33:44:00",MfiCacheFixture.serial,now+100))
        assertNotNull(MfiCertificateCache.load(null,"00:11:22:33:44:10",MfiCacheFixture.serial,now+100))
        MfiCertificateCache.clearAll(null)
        assertNull(MfiCertificateCache.load(null,"00:11:22:33:44:10",MfiCacheFixture.serial,now+100))
    }
    @Test fun x509ValidityIsCheckedAndBadCertificateCannotPopulateCache() {
        assertFalse(MfiCertificateCache.remember(null,peer,MfiCacheFixture.serial,MfiCacheFixture.certificate,0))
        val beyondValidity=now+101L*366*24*60*60*1000
        assertFalse(MfiCertificateCache.remember(null,peer,MfiCacheFixture.serial,MfiCacheFixture.certificate,beyondValidity))
        assertNull(MfiCertificateCache.load(null,peer,MfiCacheFixture.serial,now))
    }

}
