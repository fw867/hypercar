package io.github.hypercar.iap2

import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.net.CarWifiRequestIdentity
import io.github.hypercar.session.CarHotspotReconnect
import io.github.hypercar.session.verifiedAirplayEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class Iap2MediaBluetoothTest {
    private val descriptor=byteArrayOf(5,12,9,1,0xa1.toByte(),1,0x15,0,0x25,2,9,0,9,0xb5.toByte(),9,0xb6.toByte(),0x75,8,0x95.toByte(),1,0x81.toByte(),0,0xc0.toByte())
    private fun start(d:ByteArray=descriptor)=Iap2Csm.encode(0x6800,Iap2Csm.u16(0,1)+Iap2Csm.param(4,d))
    private fun report(value:Int)=Iap2Csm.encode(0x6802,Iap2Csm.u16(0,1)+Iap2Csm.param(1,byteArrayOf(value.toByte())))
    private fun wifiCredentials(ssid: String, password: String = "fixture-pass") = Iap2Csm.encode(
        0x5703, Iap2Csm.string(1, ssid) + Iap2Csm.string(2, password) + Iap2Csm.u8(3, 2) + Iap2Csm.u8(4, 6),
    )
    private fun Iap2WifiCredentialHistory.Receiver.acceptStart(
        start: Iap2Messages.StartSession, current: Iap2Messages.WifiConfiguration?,
    ): Iap2WifiCredentialHistory.Receiver.Resolution = checkNotNull(resolve(start, current)).also(::commitStart)
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
        var controlVersion: Int? = null, val silentChallenge: Boolean = false,
        val wifiReply:ByteArray=wifiCredentials("fixture-ap"),
        val signChallenge: ((ByteArray) -> ByteArray)? = null) : Iap2LinkChannel {
        val queue=ArrayDeque<ByteArray>()
        val sent=mutableListOf<Int>()
        val updates=mutableListOf<Iap2Csm.Frame>()
        val deviceUuids=mutableListOf<String>()
        val statusUpdates=mutableListOf<Iap2Csm.Frame>()
        var time=1_000_000L
        var open=true
        var endWhenEmpty=false
        override var linkGeneration = 0L
        var nextReady: (() -> Unit)? = null
        var closeCount = 0
        override val isAlive get()=open
        override fun awaitReady(timeoutMillis:Long): Boolean {
            nextReady?.also { nextReady = null }?.invoke()
            return open
        }
        override fun peerMaxControlPayload()=65525
        override val peerControlVersion get() = controlVersion
        override fun close(){closeCount++;open=false}
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
                    0xaa02->if (!silentChallenge) {
                        val signature = signChallenge?.invoke(Iap2Csm.parseParams(frame.body).single().value)
                        queue += Iap2Csm.encode(0xaa03, signature?.let { Iap2Csm.param(0, it) } ?: signatureBody)
                    }
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

    @Test fun detectRenegotiatesBluetoothBusinessOrderUsingTheNewControlVersion() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        for ((before, after) in listOf(2 to 1, 1 to 2)) {
            val channel = Channel(identificationExchange = appsIdentification(), controlVersion = before,
                signChallenge = { challenge ->
                    Signature.getInstance("NONEwithECDSAinP1363Format").run {
                        initSign(keys.private)
                        update(challenge)
                        sign()
                    }
                })
            val session = Iap2SenderSession(channel, {}, pairedReceiver = true, bondedBmwInbound = true,
                nanoTime = { channel.time }, verifyMfi3Certificate = { keys.public as ECPublicKey })
            session.run(10_000)
            val originalMessages = channel.sent.size
            channel.queue += byteArrayOf()
            channel.endWhenEmpty = true
            channel.nextReady = { channel.controlVersion = after }
            session.pumpIncomingForever()

            val secondRound = channel.sent.drop(originalMessages)
            val order = if (after == 1) listOf(0xaa00, 0xaa02, 0xaa05, 0x1d00, 0x1d02)
                else listOf(0x1d00, 0x1d02, 0xaa00, 0xaa02, 0xaa05)
            assertEquals(order, secondRound.take(5))
            assertEquals(1, secondRound.count { it == 0x4e0c })
            assertEquals(0, channel.closeCount)
        }
    }
    @Test fun onlyPairedAppsV1RequiresVerifiedCertificateOnTheIdentificationFirstPath() {
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

    @Test fun outboundPairedAppsV1StillRequiresVerifiedAuthenticationAfterDetect() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        var validSignature = true
        val channel = Channel(identificationExchange = appsIdentification(), controlVersion = 1,
            signChallenge = { challenge ->
                if (validSignature) Signature.getInstance("NONEwithECDSAinP1363Format").run {
                    initSign(keys.private)
                    update(challenge)
                    sign()
                } else ByteArray(64)
            })
        val session = Iap2SenderSession(channel, {}, pairedReceiver = true,
            nanoTime = { channel.time }, verifyMfi3Certificate = { keys.public as ECPublicKey })
        session.run(10_000)
        assertEquals(1, channel.sent.count { it == 0xaa05 })
        val originalMessages = channel.sent.size
        validSignature = false
        channel.linkGeneration++
        channel.endWhenEmpty = true
        assertThrows(java.io.IOException::class.java) { session.pumpIncomingForever() }
        assertEquals(listOf(0xaa00, 0xaa02, 0xaa04), channel.sent.drop(originalMessages))
    }

    @Test fun earlyAuthenticatedWirelessV1ContinuesWifiAndOrdinaryAppsWaits() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        for (wireless in listOf(true, false)) {
            val identification = if (wireless) legacyBmwIdentification("NBT Evo") else appsIdentification()
            val channel = Channel(identificationExchange = identification, controlVersion = 1, signChallenge = {
                Signature.getInstance("NONEwithECDSAinP1363Format").run {
                    initSign(keys.private)
                    update(it)
                    sign()
                }
            })
            val session = Iap2SenderSession(channel, {}, onEndpoint = {}, profile = HeadUnitProfiles.BMW_ID6,
                pairedReceiver = true, nanoTime = { channel.time }, bondedBmwInbound = true,
                verifyMfi3Certificate = { keys.public as ECPublicKey })
            val endpoint = session.run(10_000)
            assertEquals(listOf(0xaa00, 0xaa02, 0xaa05, 0x1d00, 0x1d02), channel.sent.take(5))
            assertEquals(1, channel.sent.count { it == 0xaa00 })
            assertEquals(wireless, 0x5702 in channel.sent)
            assertEquals(if (wireless) "fixture-ap" else null, endpoint.ssid)
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

    @Test fun ordinaryAppsAuthenticatesFirstOnEveryPairedBmwInboundV1Profile() {
        val cases = listOf(
            Triple(HeadUnitProfiles.BMW_ID85, true, 1),
            Triple(HeadUnitProfiles.BMW_ID6, true, 1),
            Triple(HeadUnitProfiles.BMW_ID9, true, 1),
            Triple(HeadUnitProfiles.BMW_ID9.copy(id = "bmw_fixture"), true, 1),
            Triple(HeadUnitProfiles.BMW_ID85, true, 2),
            Triple(HeadUnitProfiles.BMW_ID6, true, 2),
            Triple(HeadUnitProfiles.BMW_ID9, true, 2),
            Triple(HeadUnitProfiles.BMW_ID85, true, null),
            Triple(HeadUnitProfiles.BMW_ID85, false, 1),
            Triple(HeadUnitProfiles.WINPLAY, true, 1),
        )
        for ((profile, paired, version) in cases) {
            val channel = Channel(identificationExchange = appsIdentification(), controlVersion = version)
            val session = Iap2SenderSession(channel, {}, onEndpoint = {}, profile = profile,
                pairedReceiver = paired, nanoTime = { channel.time }, bondedBmwInbound = true)
            val result = runCatching { session.run(10_000) }
            if (profile.id.startsWith("bmw_") && paired && version == 1) {
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
            nanoTime = { channel.time }, bondedBmwInbound = true)
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
        assertEquals(2, endpoints.single().wifiSecurityType)
        assertEquals(6, endpoints.single().wifiChannel)
    }

    @Test fun newHotspotCapabilitiesSurviveAddressOnlyReportsAndDoNotRestoreOldSecurity() {
        val channel = Channel()
        val endpoints = mutableListOf<CarPlayEndpoint>()
        val sender = Iap2SenderSession(channel, {}, onEndpoint = endpoints::add,
            receiverAddress = "00:11:22:33:44:55", profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time })
        sender.run(10_000)
        channel.queue += Iap2Csm.encode(0x5703,
            Iap2Csm.string(1, "new-ap") + Iap2Csm.string(2, "new-password") +
                Iap2Csm.u8(3, 4) + Iap2Csm.u8(4, 157)) +
            Iap2Csm.encode(0x4301, Iap2Csm.group(1, Iap2Csm.string(3, "192.168.6.2")) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, 7200))
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()
        for (endpoint in endpoints.takeLast(2)) {
            assertEquals("new-ap", endpoint.ssid)
            assertEquals(4, endpoint.wifiSecurityType)
            assertEquals(157, endpoint.wifiChannel)
        }
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
    @Test fun updated5703IsPublishedBefore4301WithoutWaitingForTheIncomingPump() {
        val startSession = Iap2Csm.encode(0x4301, Iap2Csm.group(1, Iap2Csm.string(0, "fixture-ap"),
            Iap2Csm.string(1, "final-password"), Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2)))
        val identification = Iap2Csm.encode(0x1d01, Iap2Csm.param(6, byteArrayOf(0x57, 3, 0x43, 1, 0x68, 0)) +
            Iap2Csm.param(7, byteArrayOf(0x57, 2)))
        val frames = wifiCredentials("fixture-ap") + wifiCredentials("fixture-ap", "updated-password") + startSession
        for (bufferedEarly in listOf(false, true)) {
            val channel = if (bufferedEarly) Channel(identificationExchange = identification,
                afterGreetings = frames, wifiReply = byteArrayOf())
                else Channel(identificationExchange = identification, wifiReply = frames)
            val passwords = mutableListOf<String>()
            val session = Iap2SenderSession(channel, {}, onEndpoint = { it.passphrase?.let(passwords::add) },
                receiverAddress = "00:11:22:33:44:55", profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time })
            assertEquals("bufferedEarly=$bufferedEarly", "final-password", session.run(10_000).passphrase)
            assertEquals("bufferedEarly=$bufferedEarly", listOf("fixture-pass", "updated-password", "final-password"), passwords)
            channel.endWhenEmpty = true
            session.pumpIncomingForever()
            assertEquals("final-password", passwords.last())
        }
    }

    @Test fun repeated4301CanRefreshTheEndpointWithoutRestoringTheOldPassword() {
        fun startSession(host: String, port: Int) = Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, "fixture-ap"), Iap2Csm.string(1, "fixture-pass"),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, host)) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, port.toLong()))
        val channel = Channel()
        val endpoints = mutableListOf<CarPlayEndpoint>()
        val session = Iap2SenderSession(channel, {}, onEndpoint = endpoints::add,
            receiverAddress = "00:11:22:33:44:55", profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time })
        session.run(10_000)
        channel.queue += startSession("192.168.5.1", 7000) +
            wifiCredentials("fixture-ap", "new-password") + startSession("192.168.5.2", 7100)
        channel.endWhenEmpty = true
        session.pumpIncomingForever()
        assertEquals("new-password", endpoints.last().passphrase)
        assertEquals(listOf("192.168.5.2"), endpoints.last().ipAddresses)
        assertEquals(7100, endpoints.last().airPlayPort)
    }

    @Test fun replayOfAnEarlier4301KeepsThe5703PasswordAndUpdatesTheEndpoint() {
        fun startSession(password: String, host: String, port: Int, sourceVersion: String) = Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, "fixture-ap"), Iap2Csm.string(1, password),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, host)) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, port.toLong()) +
                Iap2Csm.string(Iap2Messages.START_SESSION_SOURCE_VERSION, sourceVersion))
        val channel = Channel()
        val endpoints = mutableListOf<CarPlayEndpoint>()
        val session = Iap2SenderSession(channel, {}, onEndpoint = endpoints::add,
            receiverAddress = "00:11:22:33:44:55", profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time })
        session.run(10_000)
        channel.queue += startSession("older-passphrase", "192.168.5.1", 7000, "900.0") +
            startSession("interim-passphrase", "192.168.5.1", 7100, "901.0") +
            wifiCredentials("fixture-ap", "current-passphrase") +
            startSession("older-passphrase", "192.168.5.2", 7200, "900.0")
        channel.endWhenEmpty = true
        session.pumpIncomingForever()

        assertEquals(listOf("older-passphrase", "interim-passphrase", "current-passphrase", "current-passphrase"),
            endpoints.takeLast(4).map { it.passphrase })
        assertEquals(listOf("192.168.5.2"), endpoints.last().ipAddresses)
        assertEquals(7200, endpoints.last().airPlayPort)
        assertEquals("901.0", endpoints.last().sourceVersion)
    }

    @Test fun rfcommReconnectUsesTheSameReceiverAndSsidCredentialHistory() {
        val history = Iap2WifiCredentialHistory()
        val receiver = "00:11:22:33:44:55"
        fun start(password: String, host: String, port: Int, version: String) = Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, "fixture-ap"), Iap2Csm.string(1, password),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, host)) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, port.toLong()) +
                Iap2Csm.string(Iap2Messages.START_SESSION_SOURCE_VERSION, version))
        fun publish(channel: Channel, peer: String, frames: ByteArray, scope: Iap2WifiCredentialHistory = history): CarPlayEndpoint {
            val endpoints = mutableListOf<CarPlayEndpoint>()
            val sender = Iap2SenderSession(channel, {}, onEndpoint = endpoints::add, receiverAddress = peer,
                profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time }, wifiHistory = scope.forReceiver(peer))
            sender.run(10_000)
            channel.queue += frames
            channel.endWhenEmpty = true
            sender.pumpIncomingForever()
            return endpoints.last()
        }
        val earlier = start("fixture-pass", "192.168.5.1", 7000, "900.0")
        val current = start("interim-passphrase", "192.168.5.1", 7100, "901.0")
        publish(Channel(), receiver, earlier + current + wifiCredentials("fixture-ap", "current-passphrase"))

        val replay = start("fixture-pass", "192.168.5.2", 7200, "900.0")
        val resumed = publish(Channel(wifiReply = byteArrayOf()), receiver, replay)
        assertEquals("current-passphrase", resumed.passphrase)
        assertEquals(listOf("192.168.5.2"), resumed.ipAddresses)
        assertEquals(7200, resumed.airPlayPort)
        assertEquals("901.0", resumed.sourceVersion)
        assertEquals("fixture-pass", publish(Channel(), "00:11:22:33:44:66", replay).passphrase)
        assertEquals("fixture-pass", publish(Channel(), receiver, replay, Iap2WifiCredentialHistory()).passphrase)
    }

    @Test fun verifiedCachedPasswordCannotOverwriteTheFirst5703PasswordAfterRfcommReconnect() {
        val receiver = "00:11:22:33:44:aa"
        val cached = Iap2Messages.WifiConfiguration("fixture-ap", "cached-passphrase", 6, 2)
        val fresh = cached.copy(passphrase = "new-passphrase")
        val replay = Iap2Messages.StartSession(7200, null, null, null, listOf("192.168.5.2"), cached)

        for (cacheRecordedFirst in listOf(true, false)) {
            val history = Iap2WifiCredentialHistory()
            val initial = history.forReceiver(receiver)
            if (cacheRecordedFirst) initial.rememberAppliedCache(cached.ssid, cached.passphrase)
            initial.remember(fresh)
            if (!cacheRecordedFirst) initial.rememberAppliedCache(cached.ssid, cached.passphrase)

            val resumed = history.forReceiver(receiver.uppercase()).acceptStart(replay, null)
            assertTrue(resumed.replayedPassword)
            assertEquals(fresh, resumed.credential)
            assertEquals(7200, resumed.start.airPlayPort)
            assertEquals(listOf("192.168.5.2"), resumed.start.ipAddresses)
            assertEquals(cached, history.forReceiver("00:11:22:33:44:66").acceptStart(replay, null).credential)
            val otherSsid = replay.copy(wirelessAp = cached.copy(ssid = "other-car-ap"))
            assertEquals(otherSsid.wirelessAp, initial.acceptStart(otherSsid, null).credential)
        }
    }

    @Test fun unobservedCachedPasswordIsCommittedOnlyWhenBindingSucceeds() {
        val receiver = "00:11:22:33:44:aa"
        val cached = Iap2Messages.WifiConfiguration("fixture-ap", "cached-passphrase", 6, 2)
        val fresh = cached.copy(passphrase = "new-passphrase")
        val replay = Iap2Messages.StartSession(7200, null, null, null, listOf("192.168.5.2"), cached)
        for (bound in listOf(false, true)) {
            val history = Iap2WifiCredentialHistory()
            val initial = history.forReceiver(receiver)
            val binding = initial.beginCacheBinding(cached.ssid, cached.passphrase)
            val reconnected = history.forReceiver(receiver.uppercase())

            initial.finishCacheBinding(binding, bound)
            initial.remember(fresh)
            val after = reconnected.acceptStart(replay, null)
            assertEquals(bound, after.replayedPassword)
            assertEquals(if (bound) fresh else cached, after.credential)
        }
    }

    @Test fun cancelledCacheBindingStillProtectsThe5703PasswordAcrossRfcommReconnect() {
        val history = Iap2WifiCredentialHistory()
        val peer = "00:11:22:33:44:55"
        val receiver = history.forReceiver(peer)
        val cached = Iap2Messages.WifiConfiguration("fixture-ap", "cached-passphrase", 6, 2)
        val fresh = cached.copy(passphrase = "new-passphrase")
        val first = Iap2Messages.StartSession(7000, null, null, "901.0", listOf("192.168.5.1"), fresh)
        val replay = first.copy(airPlayPort = 7200, sourceVersion = "900.0", ipAddresses = listOf("192.168.5.2"), wirelessAp = cached)
        val binding = receiver.beginCacheBinding(cached.ssid, cached.passphrase)
        receiver.remember(fresh)
        receiver.acceptStart(first, fresh)
        assertTrue(receiver.acceptStart(replay, fresh).replayedPassword)
        receiver.finishCacheBinding(binding, bound = false)

        val after = history.forReceiver(peer.uppercase()).acceptStart(replay, null)
        assertTrue(after.replayedPassword)
        assertEquals(fresh, after.credential)
        assertEquals(7200, after.start.airPlayPort)
        assertEquals(listOf("192.168.5.2"), after.start.ipAddresses)
        assertEquals("901.0", after.start.sourceVersion)

        val failedWithoutUpdate = history.forReceiver("00:11:22:33:44:66")
        val failed = failedWithoutUpdate.beginCacheBinding(cached.ssid, cached.passphrase)
        failedWithoutUpdate.finishCacheBinding(failed, bound = false)
        failedWithoutUpdate.remember(fresh)
        assertFalse(failedWithoutUpdate.acceptStart(replay, null).replayedPassword)
    }

    @Test fun cancelledCacheCannotPublishTheOldSsidOrItsAddressAfterA5703Switch() {
        val peer = "00:11:22:33:44:55"
        val history = Iap2WifiCredentialHistory()
        val receiver = history.forReceiver(peer)
        val cached = Iap2Messages.WifiConfiguration("previous-ap", "fixture-pass", 6, 2)
        val binding = receiver.beginCacheBinding(cached.ssid, cached.passphrase)
        val channel = Channel(wifiReply = wifiCredentials(cached.ssid))
        val endpoints = mutableListOf<CarPlayEndpoint>()
        val sender = Iap2SenderSession(channel, {}, onEndpoint = { endpoint ->
            endpoints.add(endpoint)
            if (endpoint.ssid == "replacement-ap") receiver.finishCacheBinding(binding, bound = false)
        }, receiverAddress = peer, profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time }, wifiHistory = receiver)
        fun start(ssid: String, password: String, host: String, port: Long) = Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, ssid), Iap2Csm.string(1, password),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, host)) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, port))
        sender.run(10_000)
        val swap = endpoints.size
        channel.queue += start(cached.ssid, cached.passphrase, "192.168.5.1", 7000) +
            wifiCredentials("replacement-ap", "replacement-password") +
            start(cached.ssid, cached.passphrase, "192.168.5.2", 7100) +
            start("replacement-ap", "replacement-password", "192.168.6.2", 7200)
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()

        val after = endpoints.drop(swap)
        assertEquals(listOf(cached.ssid, "replacement-ap", "replacement-ap"), after.map { it.ssid })
        assertTrue(after[1].ipAddresses.isEmpty())
        assertEquals(0, after[1].airPlayPort)
        assertEquals(listOf("192.168.6.2"), after.last().ipAddresses)
        assertEquals(7200, after.last().airPlayPort)
    }

    @Test fun selectingRecoveryCredentialsRepairsHistoryAfterAQueuedOld5703() = runTest {
        val peer = "00:11:22:33:44:55"
        val ssid = "fixture-ap"
        val old = "fixture-pass"
        val fresh = "new-passphrase"
        val history = Iap2WifiCredentialHistory().forReceiver(peer)
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { 100L },
            hasFreshCredentials = { it.bluetoothHotspot?.peer == peer && it.passphrase != null })
        val identification = Iap2Csm.encode(0x1d01, Iap2Csm.param(6, byteArrayOf(0x57, 3, 0x43, 1, 0x68, 0)) +
            Iap2Csm.param(7, byteArrayOf(0x57, 2)))
        val lost = CarWifiRequestIdentity(ssid, old)
        val channel = Channel(identificationExchange = identification,
            wifiReply = wifiCredentials(ssid, old) + wifiCredentials(ssid, fresh) + wifiCredentials(ssid, old))
        val sender = Iap2SenderSession(channel, {}, receiverAddress = peer, profile = HeadUnitProfiles.WINPLAY,
            nanoTime = { channel.time }, wifiHistory = history,
            publishEndpoint = { propose -> propose()?.let { inbox.offer(it.endpoint, onAccept = it.commit) } == true })
        assertEquals(old, sender.run(10_000).passphrase)
        assertEquals(fresh, inbox.awaitReplacementCredentials(lost, 12_000, onSelected = {
            history.restoreSelected(checkNotNull(it.ssid), checkNotNull(it.passphrase))
        })?.passphrase)
        assertEquals(fresh, inbox.poll()?.passphrase)
        assertNull(inbox.poll())

        channel.queue += Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, ssid), Iap2Csm.string(1, fresh),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, "192.168.5.2")) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, 7200))
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()
        val endpoint = checkNotNull(inbox.poll())
        assertEquals(fresh, endpoint.passphrase)
        assertEquals(listOf("192.168.5.2"), endpoint.ipAddresses)
        assertEquals(7200, endpoint.airPlayPort)
    }

    @Test fun addressOnly4301UsesOnlyAddressesOnTheCurrentlyBoundHotspot() {
        val channel = Channel(wifiReply = wifiCredentials("old-ap"))
        val endpoints = mutableListOf<CarPlayEndpoint>()
        val sender = Iap2SenderSession(channel, {}, onEndpoint = endpoints::add,
            receiverAddress = "00:11:22:33:44:55", profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time })
        sender.run(10_000)
        fun addressOnly(address: String, port: Long) = Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(3, address)) + Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, port))
        channel.queue += wifiCredentials("new-ap", "replacement-password") +
            addressOnly("192.168.5.2", 7200) + addressOnly("192.168.6.2", 7300)
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()
        val (oldAddress, newAddress) = endpoints.takeLast(2)
        assertNull(oldAddress.verifiedAirplayEndpoint("new-ap", 2L) { it.startsWith("192.168.6.") })
        assertNull(oldAddress.verifiedAirplayEndpoint("new-ap", 2L, setOf("192.168.5.2")) { true })
        assertNull(newAddress.verifiedAirplayEndpoint("old-ap", 1L) { true })
        assertNull(newAddress.verifiedAirplayEndpoint("new-ap", 2L) { false })
        val verified = checkNotNull(newAddress.verifiedAirplayEndpoint("new-ap", 2L) { it.startsWith("192.168.6.") })
        assertEquals(listOf("192.168.6.2"), verified.ipAddresses)
        assertEquals(7300, verified.airPlayPort)
    }

    @Test fun addressOnly4301WithoutAnSsidKeepsItsFirstVerifiedNetwork() {
        val route = CarPlayEndpoint(null, null, 7200, null, null, null, listOf("192.168.5.2"), null,
            requiresBoundAirplayRoute = true)
        val verified = checkNotNull(route.verifiedAirplayEndpoint("car-old", 11L) { true })
        assertEquals(11L, verified.verifiedAirplayNetworkHandle)
        assertNotNull(verified.verifiedAirplayEndpoint("car-old", 11L) { true })
        assertNull(verified.verifiedAirplayEndpoint("car-new", 12L) { true })
    }

    @Test fun repeated5703CannotVerifyAnEarlier4301OnAReplacementNetwork() {
        val ssid = "fixture-ap"
        val channel = Channel()
        val reports = mutableListOf<CarPlayEndpoint>()
        var network = 11L
        val sender = Iap2SenderSession(channel, {}, receiverAddress = "00:11:22:33:44:55",
            profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time }, publishEndpoint = { propose ->
                val update = checkNotNull(propose())
                update.endpoint.verifiedAirplayEndpoint(ssid, network) { true }
                update.commit()
                reports.add(update.endpoint)
                if (update.endpoint.addressOnlyAirplayReport) network = 12L
                true
            })
        sender.run(10_000)
        channel.queue += Iap2Csm.encode(0x4301, Iap2Csm.group(1, Iap2Csm.string(3, "192.168.5.2")) +
            Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, 7200)) + wifiCredentials(ssid)
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()
        val (first, repeated) = reports.takeLast(2)
        assertEquals(11L, first.verifiedAirplayNetworkHandle)
        assertEquals(11L, repeated.verifiedAirplayNetworkHandle)
        assertNull(repeated.verifiedAirplayEndpoint(ssid, 12L) { true })
    }

    @Test fun recoveryKeepsTheInitiallyLostPasswordRetiredAfterReleasingNewCredentials() {
        val history = Iap2WifiCredentialHistory().forReceiver("00:11:22:33:44:55")
        val lost = Iap2Messages.WifiConfiguration("fixture-ap", "lost-passphrase", 6, 2)
        val selected = lost.copy(passphrase = "selected-passphrase")
        val replacement = selected.copy(passphrase = "replacement-passphrase")
        history.remember(lost)
        history.remember(selected)
        history.restoreSelected(selected.ssid, selected.passphrase, CarWifiRequestIdentity(lost.ssid, lost.passphrase))
        history.retireRecoveryCredential(selected.ssid, selected.passphrase)
        history.remember(replacement)
        val replay = Iap2Messages.StartSession(7200, null, null, null, listOf("192.168.5.2"), lost)
        assertNull(history.resolve(replay, replacement))
        assertNull(history.resolve(replay.copy(wirelessAp = selected), replacement))
    }

    @Test fun retiringWpa2StillAllowsFreshSaeFrom5703And4301() {
        val history = Iap2WifiCredentialHistory().forReceiver("00:11:22:33:44:55")
        val wpa2 = Iap2Messages.WifiConfiguration("fixture-ap", "fixture-pass", 6, 2)
        val sae = wpa2.copy(securityType = 4)
        history.remember(wpa2)
        history.retireRecoveryCredential(wpa2.ssid, wpa2.passphrase, wpa2.securityType)
        assertFalse(history.isRetiredRecoveryCredential(sae.ssid, sae.passphrase, sae.securityType))
        history.remember(sae)
        history.remember(wpa2)
        val route = Iap2Messages.StartSession(7200, null, null, null, listOf("192.168.5.2"), null)
        assertEquals(sae, history.resolve(route, null)?.credential)
        assertEquals(sae, history.resolve(route.copy(wirelessAp = sae), null)?.credential)
        assertNull(history.resolve(route.copy(wirelessAp = wpa2), null))
    }

    @Test fun retired5703AndItsFollowing4301CannotUndoTheRecoveryHandoff() {
        val ssid = "fixture-ap"
        val peer = "00:11:22:33:44:55"
        val history = Iap2WifiCredentialHistory().forReceiver(peer)
        val oldPassword = "selected-passphrase"
        val newPassword = "replacement-passphrase"
        val channel = Channel(wifiReply = wifiCredentials(ssid, newPassword))
        val reports = mutableListOf<CarPlayEndpoint>()
        val sender = Iap2SenderSession(channel, {}, onEndpoint = reports::add, receiverAddress = peer,
            profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time }, wifiHistory = history)
        sender.run(10_000)
        history.retireRecoveryCredential(ssid, oldPassword)
        reports.clear()
        channel.queue += wifiCredentials(ssid, oldPassword) + Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, ssid), Iap2Csm.string(1, oldPassword),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, "192.168.5.2")) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, 7200))
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()
        assertTrue(reports.isEmpty())
        val routeOnly = Iap2Messages.StartSession(7300, null, null, null, listOf("192.168.5.3"), null)
        assertEquals(newPassword, history.resolve(routeOnly, null)?.credential?.passphrase)
    }

    @Test fun releasingAnotherPasswordRetiresTheSelectedPasswords4301Endpoints() {
        val history = Iap2WifiCredentialHistory().forReceiver("00:11:22:33:44:55")
        val selected = Iap2Messages.WifiConfiguration("fixture-ap", "selected-passphrase", 6, 2)
        val replacement = selected.copy(passphrase = "replacement-passphrase")
        val replay = Iap2Messages.StartSession(7200, null, null, null, listOf("192.168.5.2"), selected)
        history.remember(selected)
        history.acceptStart(replay, selected)
        history.retireRecoveryCredential(selected.ssid, selected.passphrase)
        history.remember(replacement)
        assertNull(history.resolve(replay, replacement))

        val current = replay.copy(wirelessAp = replacement, airPlayPort = 7300, ipAddresses = listOf("192.168.5.3"))
        val resolved = history.acceptStart(current, replacement)
        assertEquals(replacement, resolved.credential)
        assertEquals(7300, resolved.start.airPlayPort)
        assertEquals(listOf("192.168.5.3"), resolved.start.ipAddresses)
    }

    @Test fun late5703KeepsTheSelected4301IntactUntilTheRecoveryJoinEnds() = runTest {
        val peer = "00:11:22:33:44:55"
        val ssid = "fixture-ap"
        val selectedPassword = "selected-passphrase"
        val latePassword = "later-passphrase"
        val lost = CarWifiRequestIdentity(ssid, "fixture-pass")
        val history = Iap2WifiCredentialHistory().forReceiver(peer)
        var time = 11_999L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { time },
            hasFreshCredentials = { it.bluetoothHotspot?.peer == peer && it.passphrase != null })
        val identification = Iap2Csm.encode(0x1d01, Iap2Csm.param(6, byteArrayOf(0x57, 3, 0x43, 1, 0x68, 0)) +
            Iap2Csm.param(7, byteArrayOf(0x57, 2)))
        val channel = Channel(identificationExchange = identification,
            wifiReply = wifiCredentials(ssid, selectedPassword) + wifiCredentials(ssid, latePassword))
        inbox.beginWifiRecovery(lost, 12_000)
        val sender = Iap2SenderSession(channel, {}, receiverAddress = peer, profile = HeadUnitProfiles.WINPLAY,
            nanoTime = { channel.time }, wifiHistory = history, publishEndpoint = { propose ->
                propose()?.let { update ->
                    inbox.offer(update.endpoint, onDeferredAccept = update.commitDeferred, onAccept = update.commit).also {
                        if (it && update.endpoint.passphrase == selectedPassword) time = 12_001
                    }
                } == true
            })
        assertEquals(selectedPassword, sender.run(10_000).passphrase)
        val selected = checkNotNull(inbox.awaitReplacementCredentials(lost, 12_000, onSelected = {
            history.restoreSelected(ssid, checkNotNull(it.passphrase))
        }))
        assertEquals(selectedPassword, selected.passphrase)
        channel.queue += Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, ssid), Iap2Csm.string(1, selectedPassword),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, "192.168.6.2")) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, 7200))
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()
        val refreshed = inbox.newestCredentials(selected)
        assertEquals(selectedPassword, refreshed.passphrase)
        assertEquals(listOf("192.168.6.2"), refreshed.ipAddresses)
        assertEquals(7200, refreshed.airPlayPort)

        inbox.finishRecoveryJoin()
        val late = inbox.newestCredentials(selected)
        assertEquals(latePassword, late.passphrase)
        assertTrue(late.ipAddresses.isEmpty())
        assertEquals(0, late.airPlayPort)
    }

    @Test fun detectDropsDeferredCredentialsFromTheOldBluetoothControlGeneration() = runTest {
        val peer = "00:11:22:33:44:55"
        val ssid = "fixture-ap"
        val selectedPassword = "selected-passphrase"
        val stalePassword = "stale-passphrase"
        val lost = CarWifiRequestIdentity(ssid, "fixture-pass")
        val history = Iap2WifiCredentialHistory().forReceiver(peer)
        var time = 11_999L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { time },
            hasFreshCredentials = { it.bluetoothHotspot?.peer == peer && it.passphrase != null })
        val identification = Iap2Csm.encode(0x1d01, Iap2Csm.param(6, byteArrayOf(0x57, 3, 0x43, 1, 0x68, 0)) +
            Iap2Csm.param(7, byteArrayOf(0x57, 2)))
        val channel = Channel(identificationExchange = identification,
            wifiReply = wifiCredentials(ssid, selectedPassword) + wifiCredentials(ssid, stalePassword))
        inbox.beginWifiRecovery(lost, 12_000)
        val sender = Iap2SenderSession(channel, {}, receiverAddress = peer, profile = HeadUnitProfiles.WINPLAY,
            nanoTime = { channel.time }, wifiHistory = history, publishEndpoint = { propose ->
                propose()?.let { update ->
                    inbox.offer(update.endpoint, onDeferredAccept = update.commitDeferred, onAccept = update.commit).also {
                        if (it && update.endpoint.passphrase == selectedPassword) time = 12_001
                    }
                } == true
            })
        assertEquals(selectedPassword, sender.run(10_000).passphrase)
        val selected = checkNotNull(inbox.awaitReplacementCredentials(lost, 12_000))

        // The reader has processed DETECT while the old business worker is still unwinding.
        channel.linkGeneration++
        inbox.finishRecoveryJoin()

        assertNull(inbox.poll())
        assertEquals(selectedPassword, inbox.newestCredentials(selected).passphrase)
        val addressOnly = Iap2Messages.StartSession(7300, null, null, null, listOf("192.168.5.3"), null)
        assertEquals(selectedPassword, history.resolve(addressOnly, null)?.credential?.passphrase)
    }

    @Test fun rejected5703DoesNotOverwriteHistoryOrHideTheNext4301Address() = runTest {
        val receiver = "00:11:22:33:44:55"
        val ssid = "fixture-ap"
        val initial = "fixture-pass"
        val fresh = "new-passphrase"
        val history = Iap2WifiCredentialHistory().forReceiver(receiver)
        var elapsed = 0L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { elapsed },
            hasFreshCredentials = { it.bluetoothHotspot?.peer == receiver && it.passphrase != null })
        fun startWithNewPassword(port: Int, host: String) = Iap2Csm.encode(0x4301,
            Iap2Csm.group(1, Iap2Csm.string(0, ssid), Iap2Csm.string(1, fresh),
                Iap2Csm.u8(2, 1), Iap2Csm.u8(4, 2), Iap2Csm.string(3, host)) +
                Iap2Csm.u32(Iap2Messages.START_SESSION_AIRPLAY_PORT, port.toLong()))
        val identification = Iap2Csm.encode(0x1d01, Iap2Csm.param(6, byteArrayOf(0x57, 3, 0x43, 1, 0x68, 0)) +
            Iap2Csm.param(7, byteArrayOf(0x57, 2)))
        val channel = Channel(identificationExchange = identification, wifiReply = wifiCredentials(ssid, initial) +
            wifiCredentials(ssid, fresh) + startWithNewPassword(7000, "192.168.5.1"))
        val sender = Iap2SenderSession(channel, {}, onEndpoint = {}, receiverAddress = receiver,
            profile = HeadUnitProfiles.WINPLAY, nanoTime = { channel.time }, wifiHistory = history,
            publishEndpoint = { propose -> propose()?.let { inbox.offer(it.endpoint, onAccept = it.commit) } == true })
        assertEquals(fresh, sender.run(10_000).passphrase)
        assertEquals(fresh, inbox.awaitReplacementCredentials(CarWifiRequestIdentity(ssid, initial), 12_000)?.passphrase)
        while (inbox.poll() != null) { }
        elapsed = 12_001
        channel.queue += wifiCredentials(ssid, initial) + startWithNewPassword(7200, "192.168.5.2")
        channel.endWhenEmpty = true
        sender.pumpIncomingForever()
        val endpoint = checkNotNull(inbox.poll())
        assertEquals(fresh, endpoint.passphrase)
        assertEquals(7200, endpoint.airPlayPort)
        assertEquals(listOf("192.168.5.2"), endpoint.ipAddresses)
        assertNull(inbox.poll())
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
