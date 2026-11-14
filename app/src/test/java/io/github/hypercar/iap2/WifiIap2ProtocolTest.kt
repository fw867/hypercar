package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class WifiIap2ProtocolTest {
    @Test fun noPeerBytesStayPassiveAndDoNotFailMediaSession() {
        val logs = mutableListOf<String>()
        val p = WifiIap2Protocol("test-device", { logs += it })
        assertTrue(p.advance(1).isEmpty())
        assertEquals("WAIT_PEER", p.diagnosticPhase)
        assertTrue(p.advance(15_001).isEmpty())
        assertEquals("WAIT_PEER_LONG", p.diagnosticPhase)
        assertTrue(p.advance(60_001).isEmpty())
        assertFalse(p.failed)
        assertArrayEquals(Iap2LinkEngine.MARKER, p.feed(Iap2LinkEngine.MARKER,60_002))
        assertEquals("LINK", p.diagnosticPhase)
        assertTrue(logs.any { it.startsWith("WAIT_PEER") })
    }
    @Test fun existingBluetoothStartStillInitiatesSyn() {
        val engine = Iap2LinkEngine()
        engine.start(1)
        val output = engine.takeOutput()
        assertArrayEquals(Iap2LinkEngine.MARKER, output.copyOfRange(0, 6))
        assertEquals(0x80, split(output.copyOfRange(6, output.size)).single().control)
    }
    private data class Wire(val control: Int, val seq: Int, val session: Int, val body: ByteArray)
    private fun packet(control: Int, seq: Int, ack: Int, session: Int = 0, body: ByteArray = byteArrayOf()): ByteArray {
        val len = 9 + if (body.isEmpty()) 0 else body.size + 1
        val header = byteArrayOf(0xff.toByte(), 0x5a, (len shr 8).toByte(), len.toByte(), control.toByte(), seq.toByte(), ack.toByte(), session.toByte(), 0)
        header[8] = (-header.sumOf { it.toInt() and 255 }).toByte()
        return if (body.isEmpty()) header else header + body + (-body.sumOf { it.toInt() and 255 }).toByte()
    }
    private fun split(bytes: ByteArray): List<Wire> {
        val frames = mutableListOf<Wire>()
        var offset = 0
        while (offset + 9 <= bytes.size) {
            val n = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            require(n >= 9 && offset + n <= bytes.size)
            frames += Wire(bytes[offset + 4].toInt() and 255, bytes[offset + 5].toInt() and 255,
                bytes[offset + 7].toInt() and 255, if (n == 9) byteArrayOf() else bytes.copyOfRange(offset + 9, offset + n - 1))
            offset += n
        }
        return frames
    }
    private fun messages(frames: List<Wire>): List<Iap2Csm.Frame> {
        val parsers=mutableMapOf<Int,Iap2Csm.Parser>()
        return frames.filter { it.session != 0 }.flatMap { parsers.getOrPut(it.session){Iap2Csm.Parser()}.offer(it.body) }
    }

    private inner class Car(val window: Int = 32, val controlSession: Int = 1, val deviceName: String = "HyperCar", val packetLength:Int = 65535) {
        val logs = mutableListOf<String>()
        val mediaKeys = mutableListOf<Int>()
        var authenticationOutput = emptyList<Iap2Csm.Frame>()
        var nowPlaying = Iap2NowPlaying()
        var navigationAllowed = false
        var navigationCue: io.github.hypercar.navigation.NavigationCue? = null
        val protocol = WifiIap2Protocol("test-device", { logs += it }, { ByteArray(32) { 7 } },
            navigationEnabled = { navigationAllowed }, navigationCue = { navigationCue }, deviceName = deviceName, onMediaKey = mediaKeys::add, nowPlayingProvider = { nowPlaying })
        var sequence = 86
        var ack = 0
        var time = 1L
        val commandSizes = mutableListOf<List<Int>>()
        fun feed(bytes: ByteArray): List<Wire> {
            val commands = WifiIap2Framing.split(protocol.feed(bytes,time++))
            commandSizes += commands.map { it.size }
            return commands.flatMap { split(it) }.also { out -> out.lastOrNull()?.let { ack=it.seq } }
        }
        fun start(): List<Iap2Csm.Frame> {
            assertArrayEquals(Iap2LinkEngine.MARKER, protocol.feed(Iap2LinkEngine.MARKER, time++))
            val sync = Iap2LinkEngine.SynchronizationPayload(window, packetLength, 0, 0, 0, 0,
                listOf(Iap2LinkEngine.SessionDescriptor(controlSession, 0, 2), Iap2LinkEngine.SessionDescriptor(2, 1, 2))).encode()
            val syn = packet(0x80, sequence, 0, body = sync)
            assertTrue(feed(syn.copyOfRange(0, 6)).isEmpty())
            val answer = feed(syn.copyOfRange(6, syn.size)).single()
            assertEquals(0xc0, answer.control)
            assertEquals(32, answer.body[1].toInt())
            return messages(feed(packet(0x40, sequence, ack)))
        }
        fun message(id: Int, body: ByteArray = byteArrayOf()): List<Iap2Csm.Frame> =
            messages(feed(packet(0x40, ++sequence, ack, controlSession, Iap2Csm.encode(id, body))))
        fun ready(identificationBody: ByteArray = byteArrayOf(), beforeIdentification: () -> Unit = {}) {
            assertEquals(listOf(0x1d00), start().map { it.messageId })
            beforeIdentification()
            val identification = message(0x1d01, identificationBody)
            assertEquals(listOf(0x1d02, 0xaa00), identification.map { it.messageId })
            assertTrue(identification.last().body.isEmpty()) // CatPlay default: request certificate, not serial.
            assertEquals(listOf(0xaa00), message(0xaa06).map { it.messageId })
            val challenge = message(0xaa01, Iap2Csm.param(0, byteArrayOf(1, 2, 3))).single()
            assertEquals(0xaa02, challenge.messageId)
            assertEquals(32, Iap2Csm.parseParams(challenge.body).single().value.size)
            val greetings = message(0xaa03, Iap2Csm.param(0, ByteArray(64) { 9 }))
            authenticationOutput = greetings
            assertTrue(greetings.none { it.messageId in listOf(0xa100,0xfffa,0xfff0) })
            assertEquals(listOf(0xaa05, 0x4e0c, 0x4e0a, 0x4e09, 0x4e0b), greetings.filterNot { it.messageId == 0x5201 }.map { it.messageId })
            val name = Iap2Csm.parseParams(greetings.single { it.messageId == 0x4e09 }.body).single()
            assertEquals(deviceName, name.asStringOrNull())
            assertFalse(protocol.failed)
        }
    }

    @Test fun everyWifiCsmUsesNegotiatedFragmentSizeIncludingGreetingsAndStatus() {
        val car=Car(packetLength=32)
        car.ready()
        assertEquals(listOf(0x4158),car.message(0x4157).map{it.messageId})
        assertTrue(car.commandSizes.flatten().all{it<=32})
        assertFalse(car.protocol.failed)
    }
    @Test fun cancellingEarlyCallListAndDiscoveryRequestsLeavesNoStaleReply() {
        val car=Car();car.start();car.message(0x1d01)
        for((start,stop) in listOf(0x4154 to 0x4156,0x4157 to 0x4159,0x4170 to 0x4172,0xad00 to 0xad02)) {
            assertTrue(car.message(start).isEmpty());assertTrue(car.message(stop).isEmpty())
        }
        car.message(0xaa01,Iap2Csm.param(0,byteArrayOf(1)))
        val result=car.message(0xaa03,Iap2Csm.param(0,byteArrayOf(1)))
        assertTrue(result.none{it.messageId in listOf(0x4155,0x4158,0x4171,0xad01)})
        assertTrue(car.protocol.ready)
    }
    @Test fun malformedAuthTlvIsRejectedEvenWhenItContainsAValidPrefix() {
        val car=Car();car.start();car.message(0x1d01)
        assertTrue(car.message(0xaa01,Iap2Csm.param(0,byteArrayOf(1))+byteArrayOf(0)).isEmpty())
        assertTrue(car.protocol.failed)
    }

    @Test fun wifiNowPlayingUsesRealMetadataAndNegotiatedFileTransferSession() {
        val car=Car()
        car.nowPlaying=Iap2NowPlaying(title="fixture-title",album="fixture-album",status=1,artwork=byteArrayOf(9,8,7))
        car.ready()
        val output=car.feed(packet(0x40,++car.sequence,car.ack,car.controlSession,Iap2Csm.encode(0x5000)))
        val update=messages(output.filter { it.session==car.controlSession }).single { it.messageId==0x5001 }
        val item=Iap2Csm.parseParams(Iap2Csm.parseParams(update.body).first { it.id==0 }.value)
        assertEquals("fixture-title",item.first { it.id==1 }.asStringOrNull())
        assertEquals("fixture-album",item.first { it.id==6 }.asStringOrNull())
        val id=item.first { it.id==26 }.value.single()
        val setup=output.single { it.session==2 }.body
        assertEquals(4,setup[1].toInt())
        val data=car.feed(packet(0x40,++car.sequence,car.ack,2,byteArrayOf(id,1)))
            .single { it.session==2 }.body
        assertArrayEquals(byteArrayOf(id,0xc0.toByte(),9,8,7),data)
        car.feed(packet(0x40,++car.sequence,car.ack,2,byteArrayOf(id,5)))
        car.message(0x5002)
        car.nowPlaying=car.nowPlaying.copy(title="new-title")
        car.time+=6000
        assertTrue(messages(split(car.protocol.advance(car.time))).none { it.messageId==0x5001 })
        assertFalse(car.protocol.failed)
    }

    @Test fun wifiLinkRestartDropsMediaSubscriptionUntilCarSubscribesAgain() {
        val car=Car();car.ready();car.message(0x5000)
        car.feed(Iap2LinkEngine.MARKER)
        assertFalse(car.protocol.ready)
        assertTrue(car.protocol.advance(car.time++).isEmpty())
        assertFalse(car.protocol.failed)
    }

    @Test fun detectionRestartReauthenticatesOnSameAirplayTransportAndDropsOldHudSubscription() {
        val car = Car()
        car.ready()
        car.message(0x5200, Iap2Csm.u16(0, 77))
        // A partial old CSM must not swallow the new identification response.
        val partial = Iap2Csm.encode(0x5000, ByteArray(100)).copyOfRange(0, 8)
        car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession, partial))
        car.ready() // Same WifiIap2Protocol, DETECT/SYN and complete auth again.
        car.feed(packet(0x40, car.sequence, car.ack)) // ACK last greetings.
        assertTrue(car.protocol.ready)
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100)
        assertEquals(listOf(0x4155), car.message(0x4154).map { it.messageId })
        assertTrue(car.logs.any { it.contains("DETECT restart") })
    }

    @Test fun authenticatedIap2MediaIsSeparateFromAirplayHidAndStopsOnResync() {
        val car=Car()
        val identity=Iap2Csm.group(0x12,Iap2Csm.u16(0,1),Iap2Csm.u8(2,1))
        val descriptor=byteArrayOf(5,12,9,1,0xa1.toByte(),1,0x15,0,0x25,2,9,0,9,0xb5.toByte(),9,0xb6.toByte(),0x75,8,0x95.toByte(),1,0x81.toByte(),0,0xc0.toByte())
        car.ready(identity)
        assertTrue(car.message(0x6800,Iap2Csm.u16(0,1)+Iap2Csm.param(4,descriptor)).isEmpty())
        fun press(value:Int) { car.message(0x6802,Iap2Csm.u16(0,1)+Iap2Csm.param(1,byteArrayOf(value.toByte()))) }
        press(1);press(1);press(0);press(2)
        assertEquals(listOf(0xb5,0xb6),car.mediaKeys)
        car.message(0x6803,Iap2Csm.u16(0,1));press(1);assertEquals(2,car.mediaKeys.size)
        car.ready(identity);press(1);assertEquals(2,car.mediaKeys.size)
        assertTrue(car.logs.any { it.contains("IAP2-MEDIA: StartHID component=1") })
    }

    @Test fun deviceNameUsesSessionSnapshotAndSupportsUnicode() {
        Car(deviceName = "我的手机 \"BMW\"").ready()
        Car(deviceName = "重命名后的手机").ready()
    }

    @Test fun earlyHudSubscriptionWaitsForAuthenticationAndRealSource() {
        val car = Car()
        car.ready { assertTrue(car.message(0x5200, Iap2Csm.u16(0, 77)).isEmpty()) }
        val idle = car.authenticationOutput.single { it.messageId == 0x5201 }
        assertArrayEquals(byteArrayOf(0), Iap2Csm.parseParams(idle.body).first { it.id == 1 }.value)
        car.time += 1000
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100)
        val replies = car.message(0x4154)
        assertEquals(listOf(0x4155, 0x5202, 0x5201), replies.map { it.messageId })
        val component = Iap2Csm.parseParams(replies.last().body).first { it.id == 0 }.value
        assertArrayEquals(byteArrayOf(0, 77), component)
        car.message(0x5203, Iap2Csm.u16(0, 77))
        car.navigationCue = car.navigationCue!!.copy(distanceMeters = 50)
        assertTrue(car.protocol.advance(car.time + 2000).isEmpty())
    }

    @Test fun communicationsReplyDoesNotClaimUnimplementedPhoneControlsOrInventSimState() {
        val car = Car()
        car.ready()
        val reply = car.message(0x4157).single()
        assertEquals(0x4158, reply.messageId)
        val params = Iap2Csm.parseParams(reply.body)
        // CatPlay iap2.rs CommunicationsUpdate: carrier requires cellular support;
        // 0/1/2 are optional live modem state, 18 is not a defined parameter.
        assertEquals(setOf(5, 6, 7, 8, 12, 13, 14, 15, 16, 17), params.map { it.id }.toSet())
        params.forEach { assertArrayEquals(byteArrayOf(0), it.value) }
    }

    @Test fun readyWaitsForPostAuthenticationGreetingsToLeaveTheLinkBacklog() {
        val car=Car(window=1);car.start();car.message(0x1d01)
        assertEquals(listOf(0xaa00),messages(car.feed(packet(0x40,car.sequence,car.ack))).map { it.messageId })
        assertEquals(0xaa02,car.message(0xaa01,Iap2Csm.param(0,byteArrayOf(1))).single().messageId)
        assertEquals(listOf(0xaa05),car.message(0xaa03,Iap2Csm.param(0,ByteArray(64))).map { it.messageId })
        assertFalse(car.protocol.ready)
        val greetings=listOf(0x4e0c,0x4e0a,0x4e09,0x4e0b)
        greetings.forEachIndexed { index,id ->
            assertEquals(listOf(id),messages(car.feed(packet(0x40,car.sequence,car.ack))).map { it.messageId })
            assertEquals(index==greetings.lastIndex,car.protocol.ready)
        }
    }

    @Test fun wifiWaitsForRealAckEvenWhenPeerDisablesRetransmission() {
        val car=Car(window=1);car.start()
        val accepted=car.message(0x1d01)
        assertEquals(listOf(0x1d02),accepted.map { it.messageId })
        assertEquals(listOf(16),car.commandSizes.last())
        // Time passing must not fabricate acceptance or retransmit a zero-retry link.
        assertTrue(car.protocol.advance(car.time+500).isEmpty())
        val cert=messages(car.feed(packet(0x40,car.sequence,car.ack)))
        assertEquals(listOf(0xaa00),cert.map { it.messageId })
        assertFalse(car.protocol.ready)
    }

    @Test fun identificationAckCannotHideFollowingAuthenticationMessagesInOneCommand() {
        val car=Car();car.start()
        assertEquals(listOf(0x1d02,0xaa00),car.message(0x1d01).map { it.messageId })
        assertEquals(listOf(16,16),car.commandSizes.last())
        assertFalse(car.protocol.ready)
    }

    @Test fun bmwAdvertisedSerialUsesBoxFramesWithPiggybackAckThenDirectCertificate() {
        val car = Car(window=5)
        car.start()
        // IdentificationInformation parameter 6: accessory's sent-message list contains AA06.
        val identification = Iap2Csm.param(6, byteArrayOf(0xaa.toByte(), 6))
        val commands = WifiIap2Framing.split(car.protocol.feed(
            packet(0x40, ++car.sequence, car.ack, 1, Iap2Csm.encode(0x1d01, identification)), car.time++))
        // Independently encoded wire fixtures: local seq 102/103, peer seq 87, control session 1.
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(2, commands.size)
        assertArrayEquals(hex("ff5a00104066570199404000061d025b"), commands[0])
        assertArrayEquals(hex("ff5a001440675701944040000aaa0000040000c8"), commands[1])
        car.ack = 103
        val certificate = car.message(0xaa06).single()
        assertEquals(0xaa00, certificate.messageId)
        assertTrue(certificate.body.isEmpty())
        assertEquals(listOf(16), car.commandSizes.last())
        assertEquals(0xaa02, car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single().messageId)
        car.message(0xaa03, Iap2Csm.param(0, ByteArray(64)))
        assertTrue(car.protocol.ready) // AA05 + four device greetings fit the five-packet window.
        assertTrue(messages(car.feed(packet(0x40, car.sequence, car.ack))).isEmpty())
        assertTrue(car.protocol.ready)
    }

    @Test fun wifiStillAcknowledgesRequestsWithoutBusinessReplyAndDuplicates() {
        val car = Car(); car.start()
        car.message(0x1d01)
        val request = packet(0x40, ++car.sequence, car.ack, 1, Iap2Csm.encode(0xae00))
        val ack = car.feed(request).single()
        assertEquals(0, ack.session)
        assertTrue(ack.body.isEmpty())
        assertEquals(car.sequence, car.protocol.feed(request, car.time++)[6].toInt() and 255)
        assertFalse(car.protocol.ready)
    }

    @Test fun fullWifiSendWindowStillFlushesReceiveAckWithoutSendingQueuedCertificate() {
        val car = Car(window=1); car.start()
        car.message(0x1d01) // 1D02 fills the window; AA00 remains queued.
        val frames = car.feed(packet(0x40, ++car.sequence, car.ack - 1, 1, Iap2Csm.encode(0x4154)))
        assertEquals(1, frames.size)
        assertEquals(0, frames.single().session)
        assertTrue(frames.single().body.isEmpty())
        val certificate = messages(car.feed(packet(0x40, car.sequence, car.ack)))
        assertEquals(listOf(0xaa00), certificate.map { it.messageId })
        assertFalse(car.protocol.ready)
    }

    @Test fun advertisedSerialPeerMayReturnCertificateDirectlyWithoutRestartOnLateSerial() {
        val car = Car(); car.start()
        val request = car.message(0x1d01, Iap2Csm.param(6, byteArrayOf(0xaa.toByte(), 6))).last()
        assertEquals(listOf(0), Iap2Csm.parseParams(request.body).map { it.id })
        assertEquals(0xaa02, car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single().messageId)
        assertTrue(car.message(0xaa06).isEmpty())
        assertFalse(car.protocol.ready)
        car.message(0xaa03, Iap2Csm.param(0, ByteArray(64)))
        assertTrue(car.protocol.ready)
    }

    @Test fun boxWifiSequenceUsesNegotiatedControlSessionAndRespondsToSubscriptions() {
        val car = Car()
        car.ready()
        val power = car.message(0xae00)
        assertEquals(listOf(0xae01, 0xae01, 0xae01), power.map { it.messageId })
        power.forEach { frame ->
            val parsed = Iap2Csm.parseParams(frame.body)
            assertEquals(frame.body.size, parsed.sumOf { it.value.size + 4 })
        }
        assertEquals(0x4155, car.message(0x4154).single().messageId)
        assertEquals(0x4158, car.message(0x4157).single().messageId)
        assertEquals(0x4171, car.message(0x4170).single().messageId)
        assertEquals(0x5001, car.message(0x5000).single().messageId)
        assertEquals(0xad01, car.message(0xad00).single().messageId)
        assertTrue(car.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).isEmpty())
        assertTrue(car.protocol.advance(1000).isEmpty()) // No Bluetooth-style bare ACK timer on reliable WLAN.
        assertFalse(car.logs.any { it.contains("070707") || it.contains("090909") })
    }

    @Test fun fragmentedCsmAndTimeoutDoNotEmitPrematureAuthenticationSuccess() {
        val car = Car()
        car.start()
        val csm = Iap2Csm.encode(0x1d01)
        assertTrue(messages(car.feed(packet(0x40, ++car.sequence, car.ack, 1, csm.copyOfRange(0, 3)))).isEmpty())
        assertEquals(listOf(0x1d02, 0xaa00), messages(car.feed(packet(0x40, ++car.sequence, car.ack, 1, csm.copyOfRange(3, csm.size)))).map { it.messageId })
        assertTrue(car.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).isEmpty())
        car.protocol.advance(20_000)
        assertTrue(car.protocol.failed)
        assertTrue(car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).isEmpty())
    }

    @Test fun earlySubscriptionsAreAnsweredAfterAuthenticationAndCanBeCancelled() {
        for (cancel in listOf(false, true)) {
            val car = Car()
            car.start()
            car.message(0x1d01)
            assertTrue(car.message(0xae00).isEmpty())
            assertTrue(car.message(0x4154).isEmpty())
            if (cancel) assertTrue(car.message(0xae02).isEmpty())
            car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1)))
            val result = car.message(0xaa03, Iap2Csm.param(0, ByteArray(64) { 1 }))
            assertTrue(car.protocol.ready)
            assertEquals(if (cancel) 0 else 3, result.count { it.messageId == 0xae01 })
            assertEquals(1, result.count { it.messageId == 0x4155 })
            assertTrue(car.logs.any { it.startsWith("DEFER") })
            car.message(0x1234)
            assertTrue(car.logs.any { it.startsWith("ACK-only id=0x1234") })
        }
    }

    @Test fun certificateCanArriveDirectlyWithoutSerialRoundTrip() {
        val car = Car()
        car.start()
        val request = car.message(0x1d01).last()
        assertEquals(0xaa00, request.messageId)
        assertTrue(request.body.isEmpty())
        assertEquals(0xaa02, car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single().messageId)
        assertTrue(car.message(0xaa06).isEmpty()) // Late serial must not restart challenge.
        car.message(0xaa03, Iap2Csm.param(0, ByteArray(64) { 1 }))
        assertTrue(car.protocol.ready)
    }

    @Test fun newGenerationNegotiatesItsOwnSessionAndCannotReuseOldAuthentication() {
        val old = Car(controlSession = 1)
        old.ready()
        assertTrue(old.protocol.ready)
        val fresh = Car(controlSession = 7)
        assertEquals(listOf(0x1d00), fresh.start().map { it.messageId })
        // A late signature alone cannot authenticate a fresh Wi-Fi link.
        assertTrue(fresh.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).isEmpty())
        assertFalse(fresh.protocol.ready)
        assertEquals(listOf(0x1d02, 0xaa00), fresh.message(0x1d01).map { it.messageId })
        assertEquals(0xaa02, fresh.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single().messageId)
        assertTrue(fresh.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).any { it.messageId == 0xaa05 })
        val replies = fresh.feed(packet(0x40, ++fresh.sequence, fresh.ack, 7, Iap2Csm.encode(0x4157)))
        assertTrue(replies.filter { it.body.isNotEmpty() }.all { it.session == 7 })
        assertEquals(listOf(0x4158), messages(replies).map { it.messageId })
    }

    @Test fun malformedAuthenticationPayloadCannotReachReadyOrBeReused() {
        val emptyCertificate = Car()
        emptyCertificate.start(); emptyCertificate.message(0x1d01)
        assertTrue(emptyCertificate.message(0xaa01, Iap2Csm.param(0, byteArrayOf())).isEmpty())
        assertTrue(emptyCertificate.protocol.failed)
        assertFalse(emptyCertificate.protocol.ready)
        assertTrue(emptyCertificate.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).isEmpty())

        val emptySignature = Car()
        emptySignature.start(); emptySignature.message(0x1d01)
        emptySignature.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1)))
        assertTrue(emptySignature.message(0xaa03, Iap2Csm.param(0, byteArrayOf())).isEmpty())
        assertTrue(emptySignature.protocol.failed)
        assertFalse(emptySignature.protocol.ready)
    }

    @Test fun splitAndRepeatedDetectionMarkersAreAnsweredWithoutCompetingSyn() {
        val p = WifiIap2Protocol("test", {})
        assertTrue(p.feed(Iap2LinkEngine.MARKER.copyOfRange(0, 2), 1).isEmpty())
        assertArrayEquals(Iap2LinkEngine.MARKER, p.feed(Iap2LinkEngine.MARKER.copyOfRange(2, 6), 2))
        assertArrayEquals(Iap2LinkEngine.MARKER, p.feed(Iap2LinkEngine.MARKER, 1000))
        assertTrue(p.advance(2000).isEmpty())
    }
}
