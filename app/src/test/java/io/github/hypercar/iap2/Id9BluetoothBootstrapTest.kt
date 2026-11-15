package io.github.hypercar.iap2

import io.github.hypercar.headunit.HeadUnitProfiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class Id9BluetoothBootstrapTest {
    @Before fun resetCache() { MfiCertificateCache.clearAll(null) }
    private class Channel(
        val serial: ByteArray? = null,
        val certificate: ByteArray = MfiCacheFixture.certificate,
        val signature: ByteArray? = Iap2Csm.param(0,byteArrayOf(1)),
        val serialOverride: ByteArray? = null,
        val legacy: Boolean = false,
        val subscribeEarly: Boolean = false,
    ) : Iap2LinkChannel {
        val sent = mutableListOf<Iap2Csm.Frame>()
        val queue = ArrayDeque<ByteArray>()
        var time = 1_000_000L
        override val isAlive get() = true
        override fun awaitReady(timeoutMillis: Long) = true
        override fun peerMaxControlPayload() = 65525
        override fun close() {}
        override fun recvControl(timeoutMillis: Long): ByteArray? {
            if (queue.isNotEmpty()) return queue.removeFirst()
            time += timeoutMillis * 1_000_000
            return null
        }
        override fun sendControl(bytes: ByteArray, awaitAck: Boolean): Boolean {
            for (frame in Iap2Csm.Parser().offer(bytes)) {
                sent += frame
                when (frame.messageId) {
                    0x1d00 -> queue += Iap2Csm.encode(0x1d01,
                        Iap2Csm.string(0,"BMW-fixture") + Iap2Csm.string(1,"IDC23-fixture") +
                        Iap2Csm.param(6,byteArrayOf(0xaa.toByte(),1,0xaa.toByte(),3,0x43,1) +
                            (if(serial!=null) byteArrayOf(0xaa.toByte(),6) else byteArrayOf()) +
                            (if(legacy) byteArrayOf(0x57,3) else byteArrayOf())) +
                        Iap2Csm.param(7,byteArrayOf(0xaa.toByte(),0,0xaa.toByte(),2,0x43,0) +
                            (if(legacy) byteArrayOf(0x57,2) else byteArrayOf())))
                    0xaa00 -> queue += if(serial!=null && frame.body.isNotEmpty())
                        Iap2Csm.encode(0xaa06,serialOverride ?: Iap2Csm.param(0,serial))
                        else Iap2Csm.encode(0xaa01,certificate)
                    0xaa02 -> if(signature!=null) queue += Iap2Csm.encode(0xaa03,signature) +
                        (if(subscribeEarly) Iap2Csm.encode(0xae00,Iap2Csm.param(6,byteArrayOf())) else byteArrayOf())
                    0x5702 -> queue += Iap2Csm.encode(0x5703,Iap2Csm.string(1,"fixture-ap")+Iap2Csm.string(2,"fixture-pass"))
                    0x4300 -> queue += Iap2Csm.encode(0x4301,Iap2Csm.group(1,
                        Iap2Csm.string(0,"fixture-ap") + Iap2Csm.string(1,"fixture-pass") + Iap2Csm.u8(2,1)))
                }
            }
            return true
        }
    }
    @Test fun id9SendsAvailabilityImmediatelyAfterAuthWithoutUnrequestedGreetings() {
        val channel = Channel()
        Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.BMW_ID9,nanoTime={channel.time}).run(10_000)
        val ids = channel.sent.map { it.messageId }
        assertEquals(0x4300,ids[ids.indexOf(0xaa05)+1])
        assertEquals(1,ids.count { it==0x4300 })
        assertTrue(ids.none { it in 0x4e09..0x4e0e || it==0xea00 || it==0x5702 })
    }
    @Test fun mguKeepsExistingGreetingsBeforeAvailability() {
        val channel = Channel()
        Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.BMW_ID85,nanoTime={channel.time}).run(10_000)
        val ids = channel.sent.map { it.messageId }
        assertTrue(ids.indexOf(0x4e09)>ids.indexOf(0xaa05))
        assertTrue(ids.indexOf(0x4300)>ids.indexOf(0x4e09))
    }
    private fun run(channel: Channel, peer: String? = "00:11:22:33:44:55", id9: Boolean = true) {
        Iap2SenderSession(channel,{},onEndpoint={},receiverAddress=peer,
            profile=if(id9) HeadUnitProfiles.BMW_ID9 else HeadUnitProfiles.BMW_ID85,nanoTime={channel.time}).run(10_000)
    }
    @Test fun firstConnectionFetchesCertificateAndNextUsesSerialCacheButStillChallenges() {
        val first=Channel(serial=MfiCacheFixture.serial);run(first)
        assertEquals(2,first.sent.count { it.messageId==0xaa00 })
        val next=Channel(serial=MfiCacheFixture.serial);run(next)
        assertEquals(1,next.sent.count { it.messageId==0xaa00 })
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4300),next.sent.map { it.messageId })
        val firstChallenge=Iap2Csm.parseParams(first.sent.single { it.messageId==0xaa02 }.body).single().value
        val nextChallenge=Iap2Csm.parseParams(next.sent.single { it.messageId==0xaa02 }.body).single().value
        assertEquals(32,nextChallenge.size)
        assertFalse(firstChallenge.contentEquals(nextChallenge))
    }
    @Test fun changedSerialOrOtherCarNeverUsesPreviousCertificate() {
        run(Channel(serial=MfiCacheFixture.serial))
        val other=Channel(serial=MfiCacheFixture.serial);run(other,"00:11:22:33:44:66")
        assertEquals(2,other.sent.count { it.messageId==0xaa00 })
        val changed=Channel(serial=byteArrayOf(42));run(changed)
        assertEquals(2,changed.sent.count { it.messageId==0xaa00 })
        assertNull(MfiCertificateCache.load(null,"00:11:22:33:44:55",byteArrayOf(42)))
    }
    @Test fun cachedChallengeFailureDropsCacheAndDoesNotAnnounceAvailability() {
        run(Channel(serial=MfiCacheFixture.serial))
        val failed=Channel(serial=MfiCacheFixture.serial,signature=Iap2Csm.param(0,byteArrayOf()))
        try { run(failed);fail("invalid signature payload") } catch(expected:java.io.IOException) {}
        assertTrue(failed.sent.none { it.messageId in listOf(0xaa05,0x4300) })
        val retry=Channel(serial=MfiCacheFixture.serial);run(retry)
        assertEquals(2,retry.sent.count { it.messageId==0xaa00 })
    }
    @Test fun malformedSerialFailsBeforeChallengeAndUnknownPeerCannotCache() {
        for(body in listOf(byteArrayOf(),Iap2Csm.param(0,byteArrayOf()),Iap2Csm.param(0,byteArrayOf(1))+byteArrayOf(0),
            Iap2Csm.param(0,byteArrayOf(1))+Iap2Csm.param(0,byteArrayOf(1)))) {
            val channel=Channel(serial=MfiCacheFixture.serial,serialOverride=body)
            try { run(channel);fail("malformed serial") } catch(expected:java.io.IOException) {}
            assertTrue(channel.sent.none { it.messageId in listOf(0xaa02,0xaa05,0x4300) })
        }
        repeat(2) {
            val channel=Channel(serial=MfiCacheFixture.serial);run(channel,null)
            assertEquals(2,channel.sent.count { it.messageId==0xaa00 })
        }
    }
    @Test fun nonId9StillFetchesFullCertificateAndLegacyId9KeepsOldExchange() {
        run(Channel(serial=MfiCacheFixture.serial))
        val mgu=Channel(serial=MfiCacheFixture.serial);run(mgu,id9=false)
        assertEquals(2,mgu.sent.count { it.messageId==0xaa00 })
        assertTrue(mgu.sent.any { it.messageId==0x4e09 })
        val legacy=Channel(serial=MfiCacheFixture.serial,legacy=true);run(legacy)
        assertEquals(2,legacy.sent.count { it.messageId==0xaa00 })
        val ids=legacy.sent.map { it.messageId }
        assertTrue(ids.indexOf(0x4e09)<ids.indexOf(0x5702))
    }
    @Test fun earlyPowerSubscriptionCannotPreemptImmediateAvailability() {
        val channel=Channel(subscribeEarly=true);run(channel)
        val ids=channel.sent.map { it.messageId }
        assertEquals(0x4300,ids[ids.indexOf(0xaa05)+1])
        assertTrue(ids.indexOf(0xae01)>ids.indexOf(0x4300))
    }

    @Test fun opaqueCertificateAndTimeoutCompatibilityNeverBecomeCachedAuthentication() {
        repeat(2) {
            val channel=Channel(serial=MfiCacheFixture.serial,certificate=Iap2Csm.param(0,byteArrayOf(1)))
            run(channel)
            assertEquals(2,channel.sent.count { it.messageId==0xaa00 })
        }
        val timeout=Channel(serial=MfiCacheFixture.serial,signature=null)
        run(timeout)
        assertNull(MfiCertificateCache.load(null,"00:11:22:33:44:55",MfiCacheFixture.serial))
        val retry=Channel(serial=MfiCacheFixture.serial);run(retry)
        assertEquals(2,retry.sent.count { it.messageId==0xaa00 })
    }

}
