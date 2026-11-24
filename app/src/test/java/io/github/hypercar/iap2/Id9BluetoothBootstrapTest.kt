package io.github.hypercar.iap2

import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.headunit.HeadUnitProfile
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class Id9BluetoothBootstrapTest {
    @Test fun mbuxDeclaredStartSessionCanArriveAfterTheInitialWait() {
        val channel = Channel(replyWithWifi = false, replyWithEndpoint = false)
        val lines = mutableListOf<String>()
        Iap2SenderSession(channel, lines::add, onEndpoint = {},
            profile = HeadUnitProfiles.builtin.single { it.id == "mercedes_mbux" },
            nanoTime = { channel.time }).run(30_000)
        assertTrue(lines.any { "车机声明会发" in it && "窗口内未到" in it })
        assertFalse(lines.any { "也未声明 0x4301" in it })
        assertTrue(channel.isAlive)
    }

    @Test fun id9AvailabilityAddsStableGroupZeroAndKeepsWirelessBytes() {
        val mac = "02:13:24:35:46:57"
        fun availability() = Channel().let { channel ->
            Iap2SenderSession(channel, {}, bluetoothMac = mac, onEndpoint = {},
                profile = HeadUnitProfiles.BMW_ID9, nanoTime = { channel.time }).run(10_000)
            channel.sent.single { it.messageId == 0x4300 }
        }
        val first = availability()
        val second = availability()
        assertArrayEquals(first.body, second.body)
        val wire = Iap2Csm.encode(first.messageId, first.body)
        assertEquals(75, wire.size)
        val groups = Iap2Csm.parseParams(first.body)
        assertEquals(listOf(0, 1), groups.map { it.id })
        val local = Iap2Csm.parseParams(groups[0].value)
        assertEquals(listOf(0, 1), local.map { it.id })
        assertArrayEquals(byteArrayOf(1), local[0].value)
        assertEquals(25, local[1].value.size)
        assertEquals(0.toByte(), local[1].value.last())
        val identifier = local[1].value.dropLast(1).toByteArray().toString(Charsets.US_ASCII)
        assertTrue(Regex("[0-9A-F]{24}").matches(identifier))
        assertArrayEquals(Iap2Messages.carPlayAvailability(mac),
            Iap2Csm.group(groups[1].id, groups[1].value))
    }

    @Test fun otherProfilesKeepTheOriginalWirelessOnlyAvailabilityBytes() {
        val mac = "02:13:24:35:46:57"
        for (profile in HeadUnitProfiles.builtin.filter { it.id != "bmw_id9" }) {
            val channel = Channel()
            Iap2SenderSession(channel, {}, bluetoothMac = mac, onEndpoint = {},
                profile = profile, nanoTime = { channel.time }).run(10_000)
            val frames = channel.sent.filter { it.messageId == 0x4300 }
            if (profile.sendCarPlayAvailability) assertTrue(profile.id, frames.isNotEmpty())
            for (frame in frames) {
                assertEquals(profile.id, 37, Iap2Csm.encode(frame.messageId, frame.body).size)
                assertArrayEquals(profile.id, Iap2Messages.carPlayAvailability(mac), frame.body)
            }
        }
    }

    @Test fun id9LegacyAndNewWifiRetriesKeepTheIdenticalTwoGroups() {
        for (legacy in listOf(false, true)) {
            val channel = Channel(legacy = legacy, replyWithWifi = false, replyWithEndpoint = false)
            val session = Iap2SenderSession(channel, {}, bluetoothMac = "02:13:24:35:46:57",
                onEndpoint = {}, profile = HeadUnitProfiles.BMW_ID9, nanoTime = { channel.time })
            session.run(10_000)
            channel.closeAtNanos = channel.time + 12_000_000_000L
            session.pumpIncomingForever()
            val frames = channel.sent.filter { it.messageId == 0x4300 }
            assertTrue("legacy=$legacy must exercise idle retries", frames.size > 1)
            for (frame in frames) {
                assertEquals(75, Iap2Csm.encode(frame.messageId, frame.body).size)
                assertArrayEquals(frames.first().body, frame.body)
            }
        }
    }

    @Before fun resetMfiState() {
        MfiCertificateCache.clearAll(null)
        MfiLengthMemory.clearAll(null)
    }
    private class Channel(
        val serial: ByteArray? = null,
        val certificate: ByteArray = MfiCacheFixture.certificate,
        val signature: ByteArray? = Iap2Csm.param(0,byteArrayOf(1)),
        val serialOverride: ByteArray? = null,
        val legacy: Boolean = false,
        val subscribeEarly: Boolean = false,
        val replyWithWifi: Boolean = true,
        val replyWithEndpoint: Boolean = true,
        val model: String = "IDC23-fixture",
        val manufacturer: String = "BMW Group",
    ) : Iap2LinkChannel {
        val sent = mutableListOf<Iap2Csm.Frame>()
        val queue = ArrayDeque<ByteArray>()
        var time = 1_000_000L
        var closeAtNanos = Long.MAX_VALUE
        override val isAlive get() = time < closeAtNanos
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
                        Iap2Csm.string(0,"BMW-fixture") + Iap2Csm.string(1,model) + Iap2Csm.string(2,manufacturer) +
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
                    0x5702 -> if (replyWithWifi) queue += Iap2Csm.encode(0x5703,Iap2Csm.string(1,"fixture-ap")+Iap2Csm.string(2,"fixture-pass"))
                    0x4300 -> if (replyWithEndpoint) queue += Iap2Csm.encode(0x4301,Iap2Csm.group(1,
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
    @Test fun audi5703IdentifiesTheHotspotForFirstConnection() {
        val channel = Channel(legacy = true, model = "MIB3", manufacturer = "Audi", replyWithEndpoint = false)
        assertBluetoothHotspot(channel, HeadUnitProfiles.AUDI_MIB3)
    }

    @Test fun id9WirelessGroupIdentifiesTheHotspotWithout5703() {
        val channel = Channel(replyWithWifi = false)
        assertBluetoothHotspot(channel, HeadUnitProfiles.BMW_ID9)
        assertFalse(channel.sent.any { it.messageId == 0x5702 })
    }

    private fun assertBluetoothHotspot(channel: Channel, profile: HeadUnitProfile) {
        val endpoints = mutableListOf<CarPlayEndpoint>()
        val peer = "00:11:22:33:44:55"
        Iap2SenderSession(channel, {}, onEndpoint = endpoints::add, receiverAddress = peer,
            profile = profile, nanoTime = { channel.time }).run(10_000)
        val ap = endpoints.first { it.ssid == "fixture-ap" }.bluetoothHotspot
        assertEquals(peer, ap?.peer)
        assertEquals("fixture-ap", ap?.ssid)
    }

    @Test fun id85NewWifiAlsoUsesTheBoxGreetingsBeforeAvailability() {
        val channel = Channel()
        Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.BMW_ID85,nanoTime={channel.time}).run(10_000)
        val ids = channel.sent.map { it.messageId }
        assertEquals(listOf(0x4e0c,0x4e0d,0x4e0a,0x4e09,0x4e0b),
            ids.drop(ids.indexOf(0xaa05)+1).take(5))
        assertTrue(ids.indexOf(0x4300)>ids.indexOf(0x4e09))
        assertEquals(1,ids.count { it==0x4e0d })
        assertFalse(0x5702 in ids)
    }
    @Test fun mguAndMotorcycleLegacyWifiUseTheSameBoxGreetings() {
        for (profile in listOf(HeadUnitProfiles.BMW_ID85, HeadUnitProfiles.MOTORCYCLE)) {
            val channel = Channel(legacy = true)
            Iap2SenderSession(channel, {}, deviceName = "fixture-phone", onEndpoint = {},
                profile = profile, nanoTime = { channel.time }).run(10_000)
            val ids = channel.sent.map { it.messageId }
            assertEquals(profile.id, listOf(0x4e0c, 0x4e0d, 0x4e0a, 0x4e09, 0x4e0b),
                ids.drop(ids.indexOf(0xaa05) + 1).take(5))
            assertTrue(ids.indexOf(0x5702) > ids.indexOf(0x4e0b))
            assertEquals(1, ids.count { it == 0x4e0d })
            assertArrayEquals(Iap2Csm.u8(0, 1), channel.sent.single { it.messageId == 0x4e0d }.body)
            assertArrayEquals(Iap2Csm.string(0, "fixture-phone"), channel.sent.single { it.messageId == 0x4e09 }.body)
        }
    }
    @Test fun otherProtocolsKeepWirelessUpdateAfterLegacyGreetings() {
        val boxProfiles = setOf(HeadUnitProfiles.BMW_ID85.id, HeadUnitProfiles.MOTORCYCLE.id)
        for (profile in HeadUnitProfiles.builtin.filter { it.id !in boxProfiles }) {
            val channel = Channel(legacy = true)
            Iap2SenderSession(channel,{},onEndpoint={},profile=profile,nanoTime={channel.time}).run(10_000)
            val ids = channel.sent.map { it.messageId }
            assertEquals(profile.id,listOf(0x4e0c,0x4e0a,0x4e0b,0x4e09),
                ids.drop(ids.indexOf(0xaa05)+1).take(4))
            assertEquals(profile.id,0x4e0d,ids[ids.indexOf(0x5702)+1])
        }
    }
    @Test fun id85CanRepeatTheCompleteGreetingSequenceForTheExistingWifiRescue() {
        val channel = Channel(legacy = true, replyWithWifi = false)
        Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.BMW_ID85,nanoTime={channel.time}).run(10_000)
        val greetings = channel.sent.map { it.messageId }.filter { it in 0x4e09..0x4e0d }
        assertEquals(listOf(0x4e0c,0x4e0d,0x4e0a,0x4e09,0x4e0b,
            0x4e0c,0x4e0d,0x4e0a,0x4e09,0x4e0b),greetings)
    }
    private fun run(channel: Channel, peer: String? = "00:11:22:33:44:55", id9: Boolean = true) {
        Iap2SenderSession(channel,{},onEndpoint={},receiverAddress=peer,
            profile=if(id9) HeadUnitProfiles.BMW_ID9 else HeadUnitProfiles.BMW_ID85,nanoTime={channel.time}).run(10_000)
    }

    @Test fun compatibilityChallengeUsesOnlyTheCertificateParameterLengthIncludingItsHeader() {
        val otherParameter = Iap2Csm.param(1, ByteArray(64))
        val atThreshold = Channel(certificate = otherParameter + Iap2Csm.param(0, ByteArray(640)))
        run(atThreshold)
        assertEquals(listOf(32), challengeLengths(atThreshold))

        val aboveThreshold = Channel(certificate = otherParameter + Iap2Csm.param(0, ByteArray(641)))
        run(aboveThreshold, peer = "00:11:22:33:44:66")
        assertEquals(listOf(20), challengeLengths(aboveThreshold))
    }

    @Test fun compatibilityChallengeRetainsTheOtherLengthsWhenTheCertificatePreferenceIsSilent() {
        val channel = Channel(certificate = Iap2Csm.param(0, ByteArray(641)), signature = null)
        run(channel)
        assertEquals(listOf(20, 32, 16), challengeLengths(channel))
    }

    @Test fun rememberedSuccessfulChallengeTakesPrecedenceOverTheCertificateLength() {
        run(Channel(certificate = Iap2Csm.param(0, ByteArray(640))))
        val channel = Channel(certificate = Iap2Csm.param(0, ByteArray(641)))
        run(channel)
        assertEquals(listOf(32), challengeLengths(channel))
    }

    @Test fun rememberedDisconnectStillRotatesToTheNextEstablishedCandidate() {
        val peer = "00:11:22:33:44:55"
        val key = MfiLengthMemory.receiverKey("BMW-fixture IDC23-fixture BMW Group", peer)
        MfiLengthMemory.recordDisconnected(null, key, 20)
        val channel = Channel(certificate = Iap2Csm.param(0, ByteArray(641)))
        run(channel, peer)
        assertEquals(listOf(16), challengeLengths(channel))
    }

    @Test fun malformedCertificateCannotSetTheChallengeLength() {
        val parameter = Iap2Csm.param(0, ByteArray(641))
        for (certificate in listOf(parameter + byteArrayOf(0), parameter + Iap2Csm.param(0, byteArrayOf(1)))) {
            val channel = Channel(certificate = certificate)
            assertThrows(IOException::class.java) { run(channel) }
            assertTrue(challengeLengths(channel).isEmpty())
        }
    }

    private fun challengeLengths(channel: Channel): List<Int> = channel.sent
        .filter { it.messageId == Iap2MessageIds.MFI_REQUEST_CHALLENGE }
        .map { Iap2Csm.parseParams(it.body).single().value.size }

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
