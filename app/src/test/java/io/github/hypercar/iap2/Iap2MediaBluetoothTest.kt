package io.github.hypercar.iap2

import io.github.hypercar.headunit.HeadUnitProfiles
import org.junit.Assert.*
import org.junit.Test

class Iap2MediaBluetoothTest {
    private val descriptor=byteArrayOf(5,12,9,1,0xa1.toByte(),1,0x15,0,0x25,2,9,0,9,0xb5.toByte(),9,0xb6.toByte(),0x75,8,0x95.toByte(),1,0x81.toByte(),0,0xc0.toByte())
    private fun start(d:ByteArray=descriptor)=Iap2Csm.encode(0x6800,Iap2Csm.u16(0,1)+Iap2Csm.param(4,d))
    private fun report(value:Int)=Iap2Csm.encode(0x6802,Iap2Csm.u16(0,1)+Iap2Csm.param(1,byteArrayOf(value.toByte())))
    private fun wifiCredentials(ssid: String) = Iap2Csm.encode(0x5703, Iap2Csm.string(1, ssid) +
        Iap2Csm.string(2, "fixture-pass") + Iap2Csm.u8(3, 2) + Iap2Csm.u8(4, 6))
    private fun legacyBmwIdentification(model: String) = Iap2Csm.encode(0x1d01,
        Iap2Csm.string(1, model) + Iap2Csm.string(2, "BMW Group") +
            Iap2Csm.param(6, byteArrayOf(0x57, 3, 0xea.toByte(), 3, 0x68, 0)) +
            Iap2Csm.param(7, byteArrayOf(0x57, 2, 0xea.toByte(), 0)) +
            Iap2Csm.group(10, Iap2Csm.u8(0, 8), Iap2Csm.string(1, "com.bmw.carplay")) +
            Iap2Csm.group(0x12, Iap2Csm.u16(0, 1), Iap2Csm.u8(2, 1)))
    private fun identity(serial:Boolean=false)=Iap2Csm.encode(0x1d01,
        Iap2Csm.string(0,"BMW-test")+Iap2Csm.string(1,"MGU-fixture")+
        Iap2Csm.param(6,byteArrayOf(0x57,3,0x68,0,0x68,2,0x68,3)+(if(serial) byteArrayOf(0xaa.toByte(),6) else byteArrayOf()))+
        Iap2Csm.param(7,byteArrayOf(0x57,2))+
        Iap2Csm.group(0x12,Iap2Csm.u16(0,1),Iap2Csm.u8(2,1)))
    private inner class Channel(val subscribeEarly: Boolean = false,
        val serial:Boolean=false,val statusEarly:Boolean=false,val rejectIdentification:Boolean=false,
        val certificateBody:ByteArray=Iap2Csm.param(0,byteArrayOf(1)),
        val signatureBody:ByteArray=Iap2Csm.param(0,byteArrayOf(1)),
        val communicationsBody:ByteArray=byteArrayOf(),
        val identificationExchange:ByteArray?=null, val afterGreetings:ByteArray?=null,
        val controlVersion: Int? = null, val silentChallenge: Boolean = false,
        val wifiReply:ByteArray=wifiCredentials("fixture-ap")) : Iap2LinkChannel {
        val queue=ArrayDeque<ByteArray>()
        val sent=mutableListOf<Int>()
        val updates=mutableListOf<Iap2Csm.Frame>()
        val deviceUuids=mutableListOf<String>()
        val statusUpdates=mutableListOf<Iap2Csm.Frame>()
        var time=1_000_000L
        var open=true
        var endWhenEmpty=false
        override val isAlive get()=open
        override fun awaitReady(timeoutMillis:Long)=true
        override fun peerMaxControlPayload()=65525
        override val peerControlVersion get() = controlVersion
        override fun close(){open=false}
        override fun recvControl(timeoutMillis:Long):ByteArray? {
            if(queue.isNotEmpty())return queue.removeFirst()
            time+=timeoutMillis*1_000_000
            if(endWhenEmpty)open=false
            return null
        }
        override fun sendControl(bytes:ByteArray,awaitAck:Boolean):Boolean {
            for(frame in Iap2Csm.Parser().offer(bytes)) {
                if(frame.messageId==0x4e0c) deviceUuids += Iap2Csm.parseParams(frame.body).single().asStringOrNull()!!
                if(frame.messageId in listOf(0x4155,0x4158,0x4171,0xae01)) {
                    assertTrue("Status subscriptions must wait for auth",0xaa05 in sent)
                    statusUpdates += frame
                }
                if(frame.messageId==0x5001) {
                    assertTrue("Metadata must wait for authentication",0xaa05 in sent)
                    updates+=frame
                }
                sent+=frame.messageId
                when(frame.messageId){
                    0x1d00 -> {
                        val response = if (rejectIdentification) Iap2Csm.encode(0x1d03) else identity(serial)
                        val subscription = if (subscribeEarly) Iap2Csm.encode(0x5000) else byteArrayOf()
                        val statuses = if (statusEarly) listOf(0x4154,0x4157,0x4170,0xae00).fold(byteArrayOf()) { bytes,id ->
                            bytes + Iap2Csm.encode(id, if (id == 0x4157) communicationsBody else byteArrayOf())
                        } else byteArrayOf()
                        queue += identificationExchange ?: (response + start() + report(1) + subscription + statuses)
                    }
                    0xaa00->queue+=if(serial && frame.body.isNotEmpty()) Iap2Csm.encode(0xaa06,Iap2Csm.param(0,byteArrayOf(1)))
                        else Iap2Csm.encode(0xaa01,certificateBody)
                    0xaa02->if (!silentChallenge) queue+=Iap2Csm.encode(0xaa03,signatureBody)
                    0x4e0b -> afterGreetings?.let { queue += it }
                    0x5702 -> if (wifiReply.isNotEmpty()) queue += wifiReply
                }
            }
            return open
        }
    }
    private fun appsIdentification() = Iap2Csm.encode(0x1d01, Iap2Csm.string(1, "MGU-55-BMW") + Iap2Csm.string(2, "BMW Group") +
            Iap2Csm.param(6, byteArrayOf(0xea.toByte(), 3, 0x4c, 0)) +
            Iap2Csm.param(7, byteArrayOf(0xea.toByte(), 0, 0x4e, 0x0d, 0x4c, 1)) +
            Iap2Csm.group(10, Iap2Csm.u8(0, 1), Iap2Csm.string(1, "de.bmw.a4a.pairing")))
    @Test fun onlyPairedAppsV1RequiresVerifiedCertificateAndKeepsIdentificationFirst() {
        for ((paired, version) in listOf(true to 1, true to 2, false to 1)) {
            val channel = Channel(identificationExchange = appsIdentification(), controlVersion = version)
            val session = Iap2SenderSession(channel, {}, onEndpoint = {}, pairedReceiver = paired, nanoTime = { channel.time })
            val result = runCatching { session.run(10_000) }
            assertEquals(listOf(0x1d00, 0x1d02, 0xaa00), channel.sent.take(3))
            if (paired && version == 1) {
                assertTrue(result.exceptionOrNull() is java.io.IOException)
                assertEquals(0xaa04, channel.sent.last())
                assertFalse(0xaa02 in channel.sent || 0xaa05 in channel.sent)
            } else {
                assertTrue(0xaa05 in channel.sent)
            }
        }
    }

    @Test fun verifiedAppsCertificateWithBadSignatureOrSilenceNeverSendsSuccess() {
        val leaf = javaClass.getResourceAsStream("/io/github/hypercar/iap2/mfi3-leaf.der")!!.use { it.readBytes() }
        for (silent in listOf(false, true)) {
            val channel = Channel(identificationExchange = appsIdentification(), controlVersion = 1,
                certificateBody = Iap2Csm.param(0, leaf), signatureBody = Iap2Csm.param(0, ByteArray(64)),
                silentChallenge = silent)
            val session = Iap2SenderSession(channel, {}, onEndpoint = {}, pairedReceiver = true, nanoTime = { channel.time })
            assertThrows(java.io.IOException::class.java) { session.run(10_000) }
            assertEquals(listOf(0x1d00, 0x1d02, 0xaa00, 0xaa02, 0xaa04), channel.sent)
        }
    }

    @Test fun authFirstExperimentIsLimitedToPairedBmwV1AndStopsOnBadCertificate() {
        val cases = listOf(
            Triple(HeadUnitProfiles.BMW_ID85, true, 1),
            Triple(HeadUnitProfiles.BMW_ID85, true, 2),
            Triple(HeadUnitProfiles.BMW_ID85, false, 1),
            Triple(HeadUnitProfiles.WINPLAY, true, 1),
        )
        for ((profile, paired, version) in cases) {
            val channel = Channel(identificationExchange = appsIdentification(), controlVersion = version)
            val session = Iap2SenderSession(channel, {}, onEndpoint = {}, profile = profile,
                pairedReceiver = paired, nanoTime = { channel.time }, bmwAppsV1AuthFirst = true)
            val result = runCatching { session.run(10_000) }
            if (profile == HeadUnitProfiles.BMW_ID85 && paired && version == 1) {
                assertTrue(result.exceptionOrNull() is java.io.IOException)
                assertEquals(listOf(0xaa00, 0xaa04), channel.sent)
            } else {
                assertEquals(listOf(0x1d00, 0x1d02, 0xaa00), channel.sent.take(3))
            }
        }
    }

    @Test fun authFirstRequestsV1SignatureBeforeIdentification() {
        val leaf = javaClass.getResourceAsStream("/io/github/hypercar/iap2/mfi3-leaf.der")!!.use { it.readBytes() }
        val channel = Channel(identificationExchange = appsIdentification(), controlVersion = 1,
            certificateBody = Iap2Csm.param(0, leaf), signatureBody = Iap2Csm.param(0, ByteArray(64)))
        val session = Iap2SenderSession(channel, {}, onEndpoint = {}, pairedReceiver = true,
            nanoTime = { channel.time }, bmwAppsV1AuthFirst = true)
        assertThrows(java.io.IOException::class.java) { session.run(10_000) }
        assertEquals(listOf(0xaa00, 0xaa02, 0xaa04), channel.sent)
    }

    @Test fun returningMguKeepsAppsLinkAndAcceptsCarPlayOnReidentification() {
        val channel = Channel(identificationExchange = appsIdentification())
        val endpoints = mutableListOf<CarPlayEndpoint>()
        val session = Iap2SenderSession(channel, {}, onEndpoint = endpoints::add, profile = HeadUnitProfiles.BMW_ID85,
            nanoTime = { channel.time }, pairedReceiver = true)
        assertNull(session.run(10_000).ssid)
        assertTrue(channel.isAlive)
        assertTrue(channel.sent.none { it in setOf(0xea00, 0x4300, 0x5702, 0x4e0e) })
        assertTrue(0x4e0d in channel.sent)
        channel.queue += Iap2Csm.encode(0x4c00) + legacyBmwIdentification("MGU-55-BMW")
        channel.endWhenEmpty = true
        session.pumpIncomingForever()
        assertTrue(0x4c01 in channel.sent)
        assertEquals(listOf(0x1d02, 0x1d02, 0x5702), channel.sent.filter { it in setOf(0x1d02, 0xea00, 0x5702) })
        assertEquals(listOf("fixture-ap"), endpoints.map { it.ssid })
    }

    @Test fun returningMguCarPlayConnectionRequestsCredentialsWithoutOpeningEa() {
        for (model in listOf("MGU-55-BMW", "MGU-86-BMW")) {
            val channel = Channel(identificationExchange = legacyBmwIdentification(model))
            val start = channel.time
            val session = Iap2SenderSession(channel, {}, onEndpoint = {}, profile = HeadUnitProfiles.BMW_ID85,
                nanoTime = { channel.time }, pairedReceiver = true)
            assertEquals("fixture-ap", session.run(10_000).ssid)
            assertEquals(100L, (channel.time - start) / 1_000_000)
            assertFalse(0xea00 in channel.sent)
            assertEquals(1, channel.sent.count { it == 0x5702 })
        }
    }

    @Test fun mgu55WifiRequestIsIndependentOfOptionalEaAndHidReplies() {
        for ((model, expectedDelayMs) in listOf("MGU-55-BMW" to 100L, "MGU-86-BMW" to 2300L)) {
            val lateStatus = Iap2Csm.encode(0xea03, Iap2Csm.u16(0, 1) + Iap2Csm.u8(1, 0))
            val channel = Channel(identificationExchange = legacyBmwIdentification(model),
                wifiReply = lateStatus + start() + report(1) + wifiCredentials("fixture-ap"))
            val logs = mutableListOf<String>()
            val keys = mutableListOf<Int>()
            val startTime = channel.time
            val session = Iap2SenderSession(channel, logs::add, onEndpoint = {},
                profile = HeadUnitProfiles.BMW_ID85, nanoTime = { channel.time }, onMediaKey = keys::add)
            assertEquals("fixture-ap", session.run(10_000).ssid)
            assertEquals(model, expectedDelayMs, (channel.time - startTime) / 1_000_000)
            assertEquals(1, channel.sent.count { it == 0x5702 })
            assertEquals(1, channel.sent.count { it == 0xea00 })
            channel.endWhenEmpty = true
            session.pumpIncomingForever()
            assertTrue(logs.any { "0xea03 accepted=true allConfirmed=true" in it })
            assertEquals(listOf(0xb5), keys)
        }
    }
    @Test fun mgu55ReceivesCredentialsBeforeItsRequestOrAfterTheInitialExchange() {
        for (early in listOf(true, false)) {
            val identity = legacyBmwIdentification("MGU-55-BMW")
            val channel = Channel(identificationExchange = identity,
                afterGreetings = if (early) wifiCredentials("early") else null, wifiReply = byteArrayOf())
            val endpoints = mutableListOf<CarPlayEndpoint>()
            val session = Iap2SenderSession(channel, {}, onEndpoint = endpoints::add,
                profile = HeadUnitProfiles.BMW_ID85, nanoTime = { channel.time })
            assertEquals(if (early) "early" else null, session.run(10_000).ssid)
            if (!early) channel.queue += wifiCredentials("late")
            channel.endWhenEmpty = true
            session.pumpIncomingForever()
            assertEquals(listOf(if (early) "early" else "late"), endpoints.mapNotNull { it.ssid })
        }
    }
    @Test fun bluetoothUsesTheSameRandomDeviceUuidAsTheWifiTransport() {
        val channel=Channel()
        Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,
            nanoTime={channel.time}).run(10_000)
        assertTrue(channel.deviceUuids.isNotEmpty())
        assertTrue(channel.deviceUuids.all { it==Iap2DeviceUuid.value })
        assertEquals(4,java.util.UUID.fromString(channel.deviceUuids.first()).version())
    }

    @Test fun bluetoothKeepAliveServicesTheBmwMediaLibraryExchange() {
        val channel = Channel()
        val session = Iap2SenderSession(channel, {}, onEndpoint = {}, profile = HeadUnitProfiles.WINPLAY,
            nanoTime = { channel.time })
        session.run(10_000)
        val flags = intArrayOf(4, 5, 6).map { Iap2Csm.param(it, byteArrayOf()) }
        channel.queue += Iap2Csm.encode(0x4c00) + Iap2Csm.encode(0x4c03,
            Iap2Csm.string(0, Iap2MediaLibrary.identifier) + flags.reduce { body, flag -> body + flag })
        channel.endWhenEmpty = true
        session.pumpIncomingForever()
        assertEquals(listOf(0x4c01, 0x4c04, 0x4c04, 0x4c04), channel.sent.filter { it in 0x4c00..0x4c05 })
    }

    @Test fun bluetoothEarlyCommunicationsSubscriptionUsesTheSharedFilteredReply() {
        val channel = Channel(statusEarly=true, communicationsBody=byteArrayOf(0,4,0,17,0,4,0,6))
        Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,
            nanoTime={channel.time}).run(10_000)
        assertArrayEquals(byteArrayOf(0,5,0,6,0,0,5,0,17,0),
            channel.statusUpdates.single { it.messageId==0x4158 }.body)
    }

    @Test fun identificationRejectionFailsImmediatelyInsteadOfWaitingForTimeout() {
        val channel=Channel(rejectIdentification=true)
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
        try {session.run(10_000);fail("rejected identity") } catch(expected:java.io.IOException) {
            assertTrue(expected.message!!.contains("拒绝"))
        }
        assertEquals(listOf(0x1d00),channel.sent)
        assertEquals(1_000_000L,channel.time)
    }

    @Test fun serialCertificateFlowAndEarlyStatusesSurviveBluetoothBringup() {
        val channel=Channel(serial=true,statusEarly=true)
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
        session.run(10_000)
        assertEquals(2,channel.sent.count{it==0xaa00})
        assertEquals(1,channel.sent.count{it==0x4155})
        assertEquals(1,channel.sent.count{it==0x4158})
        assertEquals(1,channel.sent.count{it==0x4171})
        assertEquals(1,channel.sent.count{it==0xae01})
        assertTrue(channel.sent.indexOf(0x4e09)<channel.sent.indexOf(0x4155))
    }
    @Test fun malformedCertificateOrSignatureNeverProducesAuthenticationSuccess() {
        for(channel in listOf(Channel(certificateBody=byteArrayOf()),Channel(signatureBody=byteArrayOf()),
                Channel(signatureBody=Iap2Csm.param(0,byteArrayOf(1))+byteArrayOf(0)))) {
            val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
            try { session.run(10_000);fail("invalid auth data must fail") } catch(expected:java.io.IOException) { }
            assertFalse(0xaa05 in channel.sent)
        }
    }
    @Test fun lateStartSessionApOverridesPreviousConfigurationFromSameBluetoothSession() {
        val channel=Channel();val endpoints=mutableListOf<CarPlayEndpoint>()
        val session=Iap2SenderSession(channel,{},onEndpoint=endpoints::add,receiverAddress="00:11:22:33:44:55",
            profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
        session.run(10_000)
        assertEquals("fixture-ap",endpoints.last().ssid)
        channel.queue+=Iap2Csm.encode(0x4301,Iap2Csm.group(1,Iap2Csm.string(0,"new-ap"),
            Iap2Csm.string(1,"new-password"),Iap2Csm.u8(2,1),Iap2Csm.u8(4,2)))
        channel.endWhenEmpty=true;session.pumpIncomingForever()
        assertEquals("new-ap",endpoints.last().ssid);assertEquals("new-password",endpoints.last().passphrase)
        assertEquals("new-ap", endpoints.last().bluetoothHotspot?.ssid)
    }

    @Test fun bluetoothEarlySubscriptionPublishesRealMetadataAfterAuthAndHonoursStop() {
        val channel=Channel(subscribeEarly=true)
        var state=Iap2NowPlaying(title="track",artist="artist",album="album",status=1)
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,
            nanoTime={channel.time},nowPlayingProvider={state})
        session.run(10_000)
        assertEquals(1,channel.updates.size)
        val item=Iap2Csm.parseParams(Iap2Csm.parseParams(channel.updates.single().body).first { it.id==0 }.value)
        assertEquals("track",item.first { it.id==1 }.asStringOrNull())
        assertEquals("album",item.first { it.id==6 }.asStringOrNull())
        assertTrue(item.none { it.id==26 }) // Fake link has no file-transfer capability.
        channel.queue+=Iap2Csm.encode(0x5002)
        state=state.copy(title="next");channel.endWhenEmpty=true
        session.pumpIncomingForever()
        assertEquals(1,channel.updates.size)
    }

    @Test fun authenticatedBluetoothVoiceEdgesUseTheSameCallbackAsWifiCsm() {
        val channel=Channel();val keys=mutableListOf<Int>()
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,
            nanoTime={channel.time},onMediaKey={keys+=it})
        session.run(10_000);keys.clear()
        val voice=byteArrayOf(5,12,9,1,0xa1.toByte(),1,0x15,0,0x25,1,0x75,1,0x95.toByte(),1,9,0xcf.toByte(),0x81.toByte(),2,0xc0.toByte())
        channel.queue+=start(voice)+report(1)+report(1)+report(0)
        channel.endWhenEmpty=true;session.pumpIncomingForever()
        assertEquals(listOf(0xcf,0x100cf),keys)
    }

    @Test fun bluetoothSameReadRegistrationWaitsForAuthAndLateRestartRemainsLive() {
        val channel=Channel();val keys=mutableListOf<Int>()
        val session=Iap2SenderSession(channel,{},onEndpoint={},
            profile=HeadUnitProfiles.WINPLAY, nanoTime={channel.time}, onMediaKey={
                assertTrue("Media must not dispatch before authentication",0xaa05 in channel.sent)
                keys+=it
            })
        val endpoint=session.run(10_000)
        assertEquals("fixture-ap",endpoint.ssid)
        assertEquals(listOf(0xb5),keys)
        assertFalse("StartHID is not an output-report request",0x6801 in channel.sent)
        channel.queue+=Iap2Csm.encode(0x6803,Iap2Csm.u16(0,1))+report(2)+start()+report(2)
        channel.endWhenEmpty=true
        session.pumpIncomingForever()
        assertEquals(listOf(0xb5,0xb6),keys)
    }
}
