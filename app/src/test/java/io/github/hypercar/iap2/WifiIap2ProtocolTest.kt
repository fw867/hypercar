package io.github.hypercar.iap2

import io.github.hypercar.navigation.HudSourceWrites
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WifiIap2ProtocolTest {
    @Test fun wifiReauthenticationAndSameIdentityReinviteUseCurrentPositionAndResumeCadence() {
        for (reuseProtocol in listOf(false,true)) {
            val old = Car(); old.ready()
            old.nowPlaying = Iap2NowPlaying(status=2,elapsedMs=90000)
            old.message(0x5000,initialMediaRequest())
            val current = if (reuseProtocol) old else Car()
            current.nowPlaying = Iap2NowPlaying(status=2,elapsedMs=1000)
            current.ready() // Actual DETECT/SYN/auth, or a fresh instance with the same identity.
            if (!reuseProtocol) old.message(0x5002)
            fun at(now: Long): List<Iap2Csm.Frame> {
                current.time = now
                return messages(current.feed(packet(0x40,current.sequence,current.ack)))
            }
            assertTrue(at(current.time+100).none { it.messageId==0x5001 })
            val initial = mediaFields(current.message(0x5000,initialMediaRequest()))
            assertArrayEquals(byteArrayOf(0,0,3,0xe8.toByte()),initial.getValue(1 to 1).value)
            assertTrue(initial.containsKey(1 to 5))
            current.nowPlaying = current.nowPlaying.copy(elapsedMs=2000)
            val seek = mediaFields(at(current.time+100))
            assertArrayEquals(byteArrayOf(0,0,7,0xd0.toByte()),seek.getValue(1 to 1).value)
            assertFalse(seek.containsKey(1 to 5))
            current.nowPlaying = current.nowPlaying.copy(status=1)
            val resumedAt = current.time+100
            assertEquals(1,mediaFields(at(resumedAt)).getValue(1 to 0).value.single().toInt())
            current.nowPlaying = current.nowPlaying.copy(elapsedMs=3000)
            assertTrue(at(resumedAt+100).none { it.messageId==0x5001 })
            assertTrue(at(resumedAt+4900).none { it.messageId==0x5001 })
            current.nowPlaying = current.nowPlaying.copy(elapsedMs=7000)
            assertArrayEquals(byteArrayOf(0,0,0x1b,0x58),
                mediaFields(at(resumedAt+5000)).getValue(1 to 1).value)
            assertFalse(current.protocol.failed)
        }
    }

    private fun initialMediaRequest() = Iap2Csm.group(0,
        *intArrayOf(0,1,7,23).map { Iap2Csm.param(it, byteArrayOf()) }.toTypedArray()) +
        Iap2Csm.group(1, *intArrayOf(0,1,2,5).map { Iap2Csm.param(it, byteArrayOf()) }.toTypedArray())

    private fun mediaFields(messages: List<Iap2Csm.Frame>) =
        Iap2Csm.parseParams(messages.single { it.messageId == 0x5001 }.body).flatMap { group ->
            Iap2Csm.parseParams(group.value).map { (group.id to it.id) to it }
        }.toMap()

    @Test fun firstMediaDefaultsFollowRealWifiReauthenticationNotRepeatedAuthOrSubscription() {
        val car = Car(window=5)
        repeat(3) { round ->
            car.ready() // Includes DETECT/SYN and authentication, not just session.reset().
            car.time += 100
            assertTrue(messages(split(car.protocol.advance(car.time))).none { it.messageId == 0x5001 })
            val first = mediaFields(car.message(0x5000, initialMediaRequest()))
            assertEquals(Iap2Csm.Param(5,byteArrayOf(0)), first[1 to 5])
            repeat(2) {
                assertTrue(car.message(0xaa03,Iap2Csm.param(0,ByteArray(64))).isEmpty())
                assertEquals(listOf(0x1d02),car.message(0x1d01).map { it.messageId })
                assertFalse(mediaFields(car.message(0x5000,initialMediaRequest())).containsKey(1 to 5))
            }
            car.message(0x5002)
            car.nowPlaying = Iap2NowPlaying(title="stopped-update-$round",status=1)
            car.time += 100
            assertTrue(messages(split(car.protocol.advance(car.time))).none { it.messageId == 0x5001 })
            val resumed = mediaFields(car.message(0x5000,initialMediaRequest()))
            assertFalse(resumed.containsKey(1 to 5))
            assertEquals(car.nowPlaying.title,resumed.getValue(0 to 1).asStringOrNull())
            assertFalse(car.protocol.failed)
        }
    }

    @Test fun sameIdentityNewWifiProtocolInitializesIndependentlyOfOldProtocolCleanup() {
        val old = Car(); old.ready()
        assertTrue(mediaFields(old.message(0x5000,initialMediaRequest())).containsKey(1 to 5))
        val current = Car(); current.ready()
        current.nowPlaying = Iap2NowPlaying(title="current",status=1)
        val first = mediaFields(current.message(0x5000,initialMediaRequest()))
        assertTrue(first.containsKey(1 to 5))
        assertEquals("current",first.getValue(0 to 1).asStringOrNull())
        old.message(0x5002); old.ready(); old.message(0x5000,initialMediaRequest())
        current.nowPlaying = current.nowPlaying.copy(title="new song")
        current.time += 100
        val update = mediaFields(messages(current.feed(packet(0x40,current.sequence,current.ack))))
        assertEquals("new song",update.getValue(0 to 1).asStringOrNull())
        assertFalse(update.containsKey(1 to 5))
        assertFalse(current.protocol.failed)
    }

    @Test fun lateConfigurationCannotRestartReauthenticationOrResendRequest() {
        val car = Car(window = 5)
        car.ready()
        val credentials = Iap2Csm.string(0, "DIRECT-BMW old") + Iap2Csm.string(1, "old-password")
        assertEquals(listOf(0x1d00), car.start().map { it.messageId })
        assertTrue(car.message(0x5703, credentials).isEmpty())
        assertEquals("IDENTIFICATION", car.protocol.diagnosticPhase)
        assertEquals(listOf(0x1d02, 0xaa00), car.message(0x1d01).map { it.messageId })
        assertTrue(car.message(0x5703, credentials).isEmpty())
        assertEquals("CERTIFICATE", car.protocol.diagnosticPhase)
        assertEquals(0xaa02, car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single().messageId)
        assertTrue(car.message(0x5703, credentials).isEmpty())
        assertEquals("CHALLENGE", car.protocol.diagnosticPhase)
        val greetings = car.message(0xaa03, Iap2Csm.param(0, ByteArray(64)))
        assertEquals(listOf(0xaa05, 0x4e0c, 0x5702, 0x4e0a, 0x4e09, 0x4e0b), greetings.map { it.messageId })
        repeat(2) {
            assertTrue(car.message(0x5703, credentials).isEmpty())
            assertTrue(car.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).isEmpty())
            assertEquals(listOf(0x1d02), car.message(0x1d01).map { it.messageId })
        }
        assertTrue(car.protocol.ready)
        assertTrue(car.message(0x5000).any { it.messageId == 0x5001 })
        assertEquals(2, car.logs.count { it.startsWith("QUEUED phase=READY id=0x5702 ") })
        assertFalse(car.protocol.failed)
    }

    @Test fun newLinkWithSameDeviceIdentityHasIndependentConfigurationRequest() {
        val old = Car()
        old.ready()
        val current = Car() // Same device ID and name, distinct protocol instance.
        current.ready()
        old.message(0x5703, Iap2Csm.string(0, "DIRECT-BMW old"))
        assertTrue(current.protocol.ready)
        assertTrue(current.message(0x5000).any { it.messageId == 0x5001 })
        for (car in listOf(old, current)) {
            assertEquals(1, car.logs.count { it.startsWith("QUEUED phase=READY id=0x5702 ") })
            assertFalse(car.protocol.failed)
        }
    }

    @Test fun wifiConfigurationRequestIsOncePerAuthenticationWithoutWaitingForReply() {
        val car = Car(window = 5)
        car.ready(vehicleMessages + vehicleComponent(3))
        assertTrue(car.authenticationOutput.single { it.messageId == 0x5702 }.body.isEmpty())
        assertTrue(car.protocol.ready)
        assertTrue(car.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).isEmpty())
        // No 5703 yet: subscriptions must already be usable.
        assertTrue(car.message(0x5000).any { it.messageId == 0x5001 })
        val credentials = Iap2Csm.string(0, "DIRECT-BMW test") + Iap2Csm.string(1, "fixture-password")
        repeat(2) {
            assertTrue(car.message(0x5703, credentials).isEmpty())
            assertTrue(car.protocol.ready)
        }
        assertEquals(listOf(0x4155), car.message(0x4154).map { it.messageId })
        car.message(0x5002)
        car.time += 60_000
        assertTrue(car.protocol.advance(car.time).isEmpty())
        assertFalse(car.protocol.failed)
        // A new authenticated iAP2 link gets its own single request.
        car.ready()
        assertEquals(1, car.authenticationOutput.count { it.messageId == 0x5702 })
    }

    @Test fun explicitNowPlayingSubscriptionIsHonoredThroughWifiLink() {
        val car=Car();car.nowPlaying=Iap2NowPlaying(title="track",artwork=byteArrayOf(1));car.ready()
        val output=car.feed(packet(0x40,++car.sequence,car.ack,car.controlSession,
            Iap2Csm.encode(0x5000,byteArrayOf(0,8,0,1,0,4,0,0))))
        assertTrue(output.none { it.session==2 })
        val update=messages(output).single { it.messageId==0x5001 }
        assertArrayEquals(byteArrayOf(0,9,0,1,0,5,0,0,0),update.body)
        assertTrue(car.protocol.diagnosticSummary.contains("rx_5000=1 queued_5001=1"))
    }

    @Test fun linkDiagnosticsRetainLatestFrameAfterTraceBudgetIsExhausted() {
        val car=Car();car.ready()
        repeat(140) { car.message(0x1234) }
        // Unknown messages can be silent on zero-ACK Wi-Fi. A later business reply carries the watermark.
        car.message(0x5000)
        val summary=car.protocol.diagnosticSummary
        assertTrue(summary.contains("rx_seq=${car.sequence and 255}"))
        assertTrue(summary.contains("tx_ack=${car.sequence and 255}"))
        assertTrue(summary.contains("pending=0"))
        assertTrue(car.logs.any { it.startsWith("LINK trace limit") })
    }
    @Test fun noPeerBytesStayPassiveAndDoNotFailMediaSession() {
        val logs = mutableListOf<String>()
        val p = WifiIap2Protocol("test-device", { logs += it })
        assertTrue(p.advance(1).isEmpty())
        assertEquals("WAIT_PEER", p.diagnosticPhase)
        assertTrue(p.advance(15_001).isEmpty())
        assertEquals("WAIT_PEER_LONG", p.diagnosticPhase)
        assertTrue(p.diagnosticSummary.contains("rx_5000=0 queued_5001=0"))
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
    private data class Wire(val control: Int, val seq: Int, val ack: Int, val session: Int, val body: ByteArray)
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
                bytes[offset + 6].toInt() and 255,
                bytes[offset + 7].toInt() and 255, if (n == 9) byteArrayOf() else bytes.copyOfRange(offset + 9, offset + n - 1))
            offset += n
        }
        return frames
    }
    private fun messages(frames: List<Wire>): List<Iap2Csm.Frame> {
        val parsers=mutableMapOf<Int,Iap2Csm.Parser>()
        return frames.filter { it.session != 0 }.flatMap { parsers.getOrPut(it.session){Iap2Csm.Parser()}.offer(it.body) }
    }

    private inner class Car(val window: Int = 32, val controlSession: Int = 1, val deviceName: String = "HyperCar",
        val packetLength: Int = 65535, profileId: String = "", val managedWrites: Boolean = false) {
        val logs = mutableListOf<String>()
        val mediaKeys = mutableListOf<Int>()
        var authenticationOutput = emptyList<Iap2Csm.Frame>()
        var nowPlaying = Iap2NowPlaying()
        val vehicleUpdates = mutableListOf<Iap2VehicleStatus>()
        val locationUpdates = mutableListOf<Iap2VehicleLocation?>()
        val wheelSpeedUpdates = mutableListOf<Iap2WheelSpeed?>()
        val transportReadiness = mutableListOf<Boolean>()
        var acceptReadiness = true
        var autoRegisterReady = true
        var nativeCapacity = 64
        var allowBulkStart = true
        val cancelledWrites = mutableListOf<Pair<Long, Long>>()
        var navigationAllowed = false
        var navigationCue: io.github.hypercar.navigation.NavigationCue? = null
        val protocol = WifiIap2Protocol("test-device", { logs += it }, { size -> ByteArray(size) { 7 } },
            navigationEnabled = { navigationAllowed }, navigationCue = { navigationCue }, deviceName = deviceName, onMediaKey = mediaKeys::add,
            nowPlayingProvider = { nowPlaying }, onVehicleStatus = vehicleUpdates::add, profileId = profileId,
            onVehicleLocation = { locationUpdates.add(it) }, onWheelSpeed = { wheelSpeedUpdates.add(it) },
            onTransportReady = { ready, _ ->
                transportReadiness.add(ready)
                !ready || acceptReadiness
            }, transportCapacity = { nativeCapacity }, allowBulkStart = { allowBulkStart },
            cancelPendingWrite = { generation, ticket -> cancelledWrites += generation to ticket },
            socketWriteFeedback = managedWrites)
        var sequence = 86
        var ack = 0
        var time = 1L
        private var writtenSynAck = 0L
        val commandSizes = mutableListOf<List<Int>>()
        fun feed(bytes: ByteArray): List<Wire> {
            val commands = WifiIap2Framing.split(protocol.feed(bytes,time++,writtenSynAck))
            commandSizes += commands.map { it.size }
            if (autoRegisterReady) protocol.outputEnqueued()
            return commands.flatMap { split(it) }.also { out -> out.lastOrNull()?.let { ack=it.seq } }
        }
        fun start(configure: (Iap2LinkEngine.SynchronizationPayload) -> Iap2LinkEngine.SynchronizationPayload = { it }): List<Iap2Csm.Frame> {
            val echo = protocol.feed(Iap2LinkEngine.MARKER, time++)
            assertArrayEquals(if (audi) byteArrayOf() else Iap2LinkEngine.MARKER, echo)
            val sync = Iap2LinkEngine.SynchronizationPayload(window, packetLength, 0, 0, 0, 0,
                listOf(Iap2LinkEngine.SessionDescriptor(controlSession, 0, 2), Iap2LinkEngine.SessionDescriptor(2, 1, 2)))
            val syn = packet(0x80, sequence, 0, body = configure(sync).encode())
            assertTrue(feed(syn.copyOfRange(0, 6)).isEmpty())
            val answer = feed(syn.copyOfRange(6, syn.size)).single()
            assertEquals(0xc0, answer.control)
            assertEquals(32, answer.body[1].toInt())
            if (managedWrites) writtenSynAck = protocol.takeSocketWrites().single().ticket
            return messages(feed(packet(0x40, sequence, ack)))
        }
        private val audi = profileId == "audi_mib3"
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
            assertEquals(listOf(0xaa05, 0x4e0c, 0x5702, 0x4e0a, 0x4e09, 0x4e0b),
                greetings.filterNot { it.messageId in listOf(0x5201, 0xa100, 0xfffa) }.map { it.messageId })
            val name = Iap2Csm.parseParams(greetings.single { it.messageId == 0x4e09 }.body).single()
            assertEquals(deviceName, name.asStringOrNull())
            assertFalse(protocol.failed)
        }
    }

    @Test fun controlV1AuthenticatesBeforeIdentificationAndThenHonorsEarlySubscriptions() {
        val car = Car()
        val request = car.start { it.copy(sessions = it.sessions.map { session ->
            if (session.kind == 0) session.copy(version = 1) else session
        }) }.single()
        assertEquals(0xaa00, request.messageId)
        assertTrue(request.body.isEmpty())
        assertTrue(car.message(0x1d01).isEmpty())
        assertTrue(car.message(0x4154).isEmpty())

        assertEquals(0xaa02, car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single().messageId)
        assertEquals(listOf(0xaa05, 0x1d00), car.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).map { it.messageId })
        assertFalse(car.protocol.ready)
        assertEquals("IDENTIFICATION", car.protocol.diagnosticPhase)

        val identified = car.message(0x1d01, vehicleMessages + vehicleComponent(3)).map { it.messageId }
        assertEquals(listOf(0x1d02, 0x4e0c, 0x5702, 0xa100, 0x4e0a, 0x4e09, 0x4e0b, 0x4155), identified)
        assertTrue(car.protocol.ready)
        assertEquals(listOf(0x1d02), car.message(0x1d01, vehicleMessages + vehicleComponent(3)).map { it.messageId })
        assertTrue(car.protocol.ready)

        assertEquals(listOf(0x1d00), car.start().map { it.messageId })
        assertFalse(car.protocol.ready)
        assertEquals(listOf(0x1d02, 0xaa00), car.message(0x1d01).map { it.messageId })
    }

    @Test fun controlV1RejectsMalformedIdentificationAfterAuthentication() {
        val car = Car()
        car.start { it.copy(sessions = listOf(Iap2LinkEngine.SessionDescriptor(1, 0, 1))) }
        car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1)))
        car.message(0xaa03, Iap2Csm.param(0, ByteArray(64)))

        val truncated = Iap2Csm.string(0, "Accessory").dropLast(1).toByteArray()
        assertTrue(car.message(0x1d01, truncated).isEmpty())
        assertTrue(car.protocol.failed)
        assertFalse(car.protocol.ready)
    }

    @Test fun nonControlVersionOneDoesNotChangeIdentificationOrder() {
        for (controlVersion in listOf(null, 0, 2, 3)) {
            val car = Car(controlSession = 10)
            val initial = car.start { sync ->
                sync.copy(sessions = listOfNotNull(
                    controlVersion?.let { Iap2LinkEngine.SessionDescriptor(10, 0, it) },
                    Iap2LinkEngine.SessionDescriptor(2, 1, 1),
                ))
            }
            assertEquals(listOf(0x1d00), initial.map { it.messageId })
        }
    }

    @Test fun audiMib3TracksAckAndResponseSeparatelyForItsSelectedChallenge() {
        val car = Car(profileId = "audi_mib3")
        car.start()
        car.message(0x1d01)
        val previousAck = car.ack
        val challenge = car.message(0xaa01, Iap2Csm.param(0, ByteArray(908) { 1 })).single()
        assertEquals(0xaa02, challenge.messageId)
        assertArrayEquals(ByteArray(20) { 7 }, Iap2Csm.parseParams(challenge.body).single().value)
        val challengeSequence = car.ack
        car.feed(packet(0x40, car.sequence, previousAck))
        car.feed(packet(0x40, car.sequence, (challengeSequence + 1) and 255))
        assertTrue(car.protocol.diagnosticSummary.contains("aa02_ack=false aa03_rx=false"))
        repeat(2) { car.feed(packet(0x40, car.sequence, challengeSequence)) }
        assertTrue(car.protocol.diagnosticSummary.contains("aa02_ack=true aa03_rx=false"))
        assertEquals(1, car.logs.count { "AUTH AA02 cumulative ACK" in it })
        val output = car.message(0xaa03, Iap2Csm.param(0, ByteArray(128)))
        assertEquals(0xaa05, output.first().messageId)
        assertTrue(car.protocol.diagnosticSummary.contains("aa02_ack=true aa03_rx=true"))
    }

    @Test fun bmwMgu103CertificateSelects20BytesAndWaitsForAa03BeforeAa05() {
        val car = Car(profileId = "bmw_id85")
        assertEquals(listOf(0x1d00), car.start().map { it.messageId })
        assertEquals(listOf(0x1d02, 0xaa00), car.message(0x1d01).map { it.messageId })

        val challenge = car.message(0xaa01, Iap2Csm.param(0, ByteArray(908) { 1 })).single()
        assertEquals(0xaa02, challenge.messageId)
        assertArrayEquals(ByteArray(20) { 7 }, Iap2Csm.parseParams(challenge.body).single().value)
        assertTrue(car.logs.any { "certificateParamBytes=912 challengeBytes=20" in it })
        assertTrue(car.feed(packet(0x40, car.sequence, car.ack)).isEmpty())
        assertEquals("CHALLENGE", car.protocol.diagnosticPhase)

        val output = car.message(0xaa03, Iap2Csm.param(0, ByteArray(128) { 9 }))
        assertEquals(listOf(0xaa05), output.filter { it.messageId == 0xaa05 }.map { it.messageId })
        assertEquals(0xaa05, output.first().messageId)
        assertTrue(car.protocol.ready)
    }

    @Test fun wifiChallengeUsesTheSameCertificateBoundaryAcrossProfiles() {
        for (profile in listOf("", "generic", "bmw_id85", "bmw_id6", "bmw_id9", "audi_mib3",
            "mercedes_mbux", "volkswagen_mib", "winplay")) {
            for ((certificateBytes, challengeBytes) in listOf(640 to 32, 641 to 20)) {
                val car = Car(profileId = profile)
                car.start()
                car.message(0x1d01)
                val body = Iap2Csm.param(0, ByteArray(certificateBytes) { 1 })
                val challenge = car.message(0xaa01, body).single()
                assertEquals(0xaa02, challenge.messageId)
                assertEquals("$profile certificate=$certificateBytes", challengeBytes,
                    Iap2Csm.parseParams(challenge.body).single().value.size)
            }
        }
    }

    @Test fun wifiChallengeCountsOnlyTheCertificateParameter() {
        val car = Car(profileId = "bmw_id85")
        car.start()
        car.message(0x1d01)
        val body = Iap2Csm.param(1, ByteArray(400) { 1 }) + Iap2Csm.param(0, ByteArray(608) { 1 })
        val challenge = car.message(0xaa01, body).single()
        assertEquals(0xaa02, challenge.messageId)
        assertArrayEquals(ByteArray(32) { 7 }, Iap2Csm.parseParams(challenge.body).single().value)
        assertTrue(car.logs.any { "certificateParamBytes=612 challengeBytes=32" in it })
    }

    @Test fun audiMib3DetectWaitsForFirstScreenSetupAndDoesNotRepeatOnRebuild() {
        val car = Car(profileId = "audi_mib3")
        assertTrue(car.protocol.feed(Iap2LinkEngine.MARKER, car.time++).isEmpty())
        assertFalse(car.protocol.takeStartupDetect())
        car.protocol.screenSetupAccepted()
        assertTrue(car.protocol.takeStartupDetect())
        assertTrue(car.protocol.feed(Iap2LinkEngine.MARKER, car.time++).isEmpty())
        car.protocol.screenSetupAccepted()
        assertFalse(car.protocol.takeStartupDetect())
        val reinvited = Car(profileId = "audi_mib3")
        assertFalse(reinvited.protocol.takeStartupDetect())
        reinvited.protocol.screenSetupAccepted()
        assertTrue(reinvited.protocol.takeStartupDetect())
    }

    @Test fun audiMib3KeepsAnEarlySynSplitAcrossScreenSetupWithoutSendingDetect() {
        val car = Car(profileId = "audi_mib3")
        val sync = Iap2LinkEngine.SynchronizationPayload(5, 2048, 3000, 30, 700, 3,
            listOf(Iap2LinkEngine.SessionDescriptor(car.controlSession, 0, 2)))
        val syn = packet(0x80, car.sequence, 0, body = sync.encode())
        assertTrue(car.feed(syn.copyOfRange(0, 6)).isEmpty())
        car.protocol.screenSetupAccepted()
        assertFalse(car.protocol.takeStartupDetect())
        val response = car.feed(syn.copyOfRange(6, syn.size)).single()
        assertEquals(0xc0, response.control)
        assertFalse(car.protocol.takeStartupDetect())
        assertEquals(listOf(0x1d00), messages(car.feed(packet(0x40, car.sequence, response.seq))).map { it.messageId })
        car.protocol.screenSetupAccepted()
        assertFalse(car.protocol.takeStartupDetect())
    }

    @Test fun audiMib3ProcessesDetectAndSynFromTheSameReadBeforeScreenSetup() {
        val car = Car(profileId = "audi_mib3")
        val sync = Iap2LinkEngine.SynchronizationPayload(5, 2048, 3000, 30, 700, 3,
            listOf(Iap2LinkEngine.SessionDescriptor(car.controlSession, 0, 2)))
        val syn = packet(0x80, car.sequence, 0, body = sync.encode())
        val response = car.feed(Iap2LinkEngine.MARKER + syn).single()
        assertEquals(0xc0, response.control)
        car.protocol.screenSetupAccepted()
        assertFalse(car.protocol.takeStartupDetect())
    }

    @Test fun audiMib3RecordsResponseEvenWhenItDoesNotCoverChallengeSequence() {
        val car = Car(profileId = "audi_mib3")
        car.start()
        car.message(0x1d01)
        val previousAck = car.ack
        car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1)))
        val signed = Iap2Csm.encode(0xaa03, Iap2Csm.param(0, ByteArray(128)))
        val response = car.feed(packet(0x40, ++car.sequence, previousAck, car.controlSession, signed))
        assertEquals(0xaa05, messages(response).first().messageId)
        assertTrue(car.protocol.diagnosticSummary.contains("aa02_ack=false aa03_rx=true"))
    }

    private val vehicleMessages = Iap2Csm.u16(6, 0xa101) + Iap2Csm.u16(7, 0xa100)

    private val locationMessages = Iap2Csm.u16(6, 0xfffb) +
        Iap2Csm.param(7, byteArrayOf(0xff.toByte(), 0xfa.toByte(), 0xff.toByte(), 0xfc.toByte()))

    private fun locationComponent(vararg flags: Int) = Iap2Csm.group(22,
        Iap2Csm.u16(0, 3), Iap2Csm.string(1, "CinemoGPSComponent"),
        *flags.map { Iap2Csm.param(it, byteArrayOf()) }.toTypedArray())

    @Test fun locationRequestsOnlyDeclaredNmeaAfterAuthenticationAndStopsOnWithdrawal() {
        val car = Car()
        val declaration = locationMessages + locationComponent(17, 18, 20)
        car.ready(declaration)
        val start = car.authenticationOutput.single { it.messageId == 0xfffa }
        assertArrayEquals(byteArrayOf(0, 4, 0, 1, 0, 4, 0, 2, 0, 4, 0, 4), start.body)
        assertTrue(car.authenticationOutput.indexOf(start) > car.authenticationOutput.indexOfFirst { it.messageId == 0xaa05 })
        assertEquals(listOf(0x1d02), car.message(0x1d01, declaration).map { it.messageId })
        val stop = car.message(0x1d01, locationMessages).single { it.messageId == 0xfffc }
        assertTrue(stop.body.isEmpty())
        assertEquals(listOf(0x1d02), car.message(0x1d01, locationMessages).map { it.messageId })
    }

    @Test fun locationNeedsCompleteDeclarationsInBothDirections() {
        val component = locationComponent(17, 18)
        val incomplete = listOf(locationMessages, component, Iap2Csm.u16(6, 0xfffb) + component,
            Iap2Csm.u16(7, 0xfffa) + Iap2Csm.u16(6, 0xfffb) + component,
            locationMessages + locationComponent(19), locationMessages + component + component,
            locationMessages + component + byteArrayOf(0))
        for (identity in incomplete) {
            val car = Car()
            car.ready(identity)
            assertTrue(car.authenticationOutput.none { it.messageId == 0xfffa })
        }
    }

    @Test fun declaredWheelSpeedWorksWithoutGnssAndClearsOnExpiryOrWithdrawal() {
        val car = Car()
        val speed = Iap2Csm.string(0, "\$PASCD,1.000,C,R,0,2,0.00,1.500,0.25,1.250*50\r\n")
        val declaration = locationMessages + locationComponent(20)
        car.ready(declaration) { car.message(0xfffb, speed) }
        assertTrue(car.wheelSpeedUpdates.isEmpty())
        assertArrayEquals(Iap2Csm.param(4, byteArrayOf()), car.authenticationOutput.single { it.messageId == 0xfffa }.body)

        for (sentence in listOf("\$PASCD,1.000,C,R,0,2,0.00,1.500,0.25,1.250*00",
            "\$PASCD,1.000,C,R,0,3,0.00,1.500,0.25,1.250*51", "\$PASCD,1.000,C,R,0,2,0.25,1.500,0.00,1.250*50",
            "\$PASCD,1.000,C,R,0,1,0.00,-1.250*4D", "\$PASCD,1.000,C,R,0,99,0.00,1.250*51")) {
            car.message(0xfffb, Iap2Csm.string(0, sentence))
        }
        assertTrue(car.wheelSpeedUpdates.isEmpty())
        car.message(0xfffb, speed)
        assertEquals(1.250, checkNotNull(car.wheelSpeedUpdates.single()).metersPerSecond, 0.0)
        assertTrue(car.locationUpdates.isEmpty())
        assertTrue(car.logs.any { "fixValid=false wheelSpeedValid=true wheelSpeedMps=1.25" in it })
        assertFalse(car.logs.any { "PASCD," in it })
        car.time += 5_001
        car.protocol.advance(car.time)
        assertNull(car.wheelSpeedUpdates.last())

        car.message(0xfffb, speed)
        car.message(0x1d01, locationMessages + locationComponent(17))
        assertNull(car.wheelSpeedUpdates.last())
        val count = car.wheelSpeedUpdates.size
        car.message(0xfffb, speed)
        assertEquals(count, car.wheelSpeedUpdates.size)
    }

    @Test fun gnssInvalidationAndWheelSpeedExpiryAreIndependent() {
        val car = Car()
        val speed = Iap2Csm.string(0, "\$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54\r\n")
        val fix = Iap2Csm.string(0, "\$GPGGA,225833.00,3719.951324,N,12201.808796,W,1,09,0.6,69.8,M,-27.0,M,,*6F")
        car.ready(locationMessages + locationComponent(17, 18, 20))
        car.message(0xfffb, fix + speed)
        assertNotNull(car.locationUpdates.last())
        assertEquals(0.0, checkNotNull(car.wheelSpeedUpdates.last()).metersPerSecond, 0.0)
        car.message(0xfffb, Iap2Csm.string(0, "\$GPRMC,225834.00,V,,,,,,,060813,,,N*7B"))
        assertNull(car.locationUpdates.last())
        assertNotNull(car.wheelSpeedUpdates.last())
        car.message(0xfffb, fix)
        car.time += 5_001
        car.protocol.advance(car.time)
        assertNull(car.wheelSpeedUpdates.last())
        assertNotNull(car.locationUpdates.last())

        car.message(0xfffb, speed)
        car.ready(locationMessages + locationComponent(17))
        assertNull(car.wheelSpeedUpdates.last())
        assertNull(car.locationUpdates.last())
    }

    @Test fun locationUpdatesUseRepeatedNmeaFieldsAndExpireOnInvalidFixOrRestart() {
        val car = Car()
        val gga = "\$GPGGA,225833.00,3719.951324,N,12201.808796,W,1,09,0.6,69.8,M,-27.0,M,,*6F"
        val rmc = "\$GPRMC,225833.00,A,3719.951324,N,12201.808796,W,0.0,26.1,060813,0.0,E,A*1F"
        val validFix = Iap2Csm.string(0, "$gga\r\n") + Iap2Csm.string(0, "$rmc\r\n")
        val declaration = locationMessages + locationComponent(17, 18)
        car.ready(declaration) { car.message(0xfffb, validFix) }
        assertTrue(car.locationUpdates.isEmpty())
        car.message(0xfffb, Iap2Csm.string(1, gga) + Iap2Csm.string(0, gga.dropLast(2) + "00"))
        car.message(0xfffb, validFix + byteArrayOf(0))
        car.message(0xfffb, Iap2Csm.param(0, gga.encodeToByteArray()))
        car.message(0xfffb, Iap2Csm.string(0, "\$GNGGA,225833.00,3719.951324,N,12261.808796,W,1,09,0.6,69.8,M,-27.0,M,,*77"))
        car.message(0xfffb, Iap2Csm.string(0, gga.repeat(64)))
        assertTrue(car.locationUpdates.isEmpty())
        car.message(0xfffb, validFix)
        val fix = checkNotNull(car.locationUpdates.last())
        assertEquals(37.332522, fix.latitudeDegrees, 0.0000001)
        assertEquals(-122.0301466, fix.longitudeDegrees, 0.0000001)
        assertEquals(69.8, checkNotNull(fix.altitudeMeters), 0.001)
        assertEquals(26.1, checkNotNull(fix.bearingDegrees), 0.001)
        car.message(0xfffb, Iap2Csm.string(0, "\$GPRMC,225834.00,V,,,,,,,060813,,,N*7B"))
        assertNull(car.locationUpdates.last())
        car.message(0xfffb, validFix)
        car.time += 10_001
        car.protocol.advance(car.time)
        assertNull(car.locationUpdates.last())
        car.message(0xfffb, validFix)
        car.ready()
        assertNull(car.locationUpdates.last())
        val updates = car.locationUpdates.size
        car.message(0xfffb, validFix)
        assertEquals(updates, car.locationUpdates.size)
        assertFalse(car.logs.any { "3719.951324" in it || "37.332522" in it })
    }

    @Test fun hudDiagnosticsMatchOutputAndOnlyLogNewCumulativeCoverage() {
        val car = Car()
        car.ready()
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100)
        val previousAck = car.ack
        assertEquals(listOf(0x5202, 0x5201), car.message(0x5200, Iap2Csm.u16(0, 1)).map { it.messageId })
        val frames = car.protocol.takeHudOutput()
        assertEquals(listOf(0x5202, 0x5201), frames.map { it.messageId })
        assertEquals(0, frames[0].offset)
        assertTrue(frames[1].offset > 0)
        assertTrue(frames[0].serial < frames[1].serial)
        assertTrue(car.protocol.takeHudOutput().isEmpty())

        car.feed(packet(0x40, car.sequence, previousAck))
        assertFalse(car.logs.any { "covered=true" in it })
        val cumulativeAck = frames.last().sequence
        repeat(2) { car.feed(packet(0x40, car.sequence, cumulativeAck)) }
        assertEquals(frames.map { "HUD LINK ${it.label} peer_ack=$cumulativeAck covered=true" },
            car.logs.filter { "covered=true" in it })
    }
    private fun vehicleComponent(vararg flags: Int) = Iap2Csm.group(21,
        Iap2Csm.u16(0, 1), Iap2Csm.string(1, "VehicleStatus"),
        *flags.map { Iap2Csm.param(it, byteArrayOf()) }.toTypedArray())

    @Test fun vehicleSubscriptionFollowsUuidAndRequestsDeclaredBoxFieldsOnce() {
        val car = Car()
        car.ready(vehicleMessages + vehicleComponent(15, 18, 6, 4, 9, 3))
        val frames = car.authenticationOutput
        assertEquals(listOf(0xaa05, 0x4e0c, 0x5702, 0xa100, 0x4e0a, 0x4e09, 0x4e0b), frames.map { it.messageId })
        assertArrayEquals(byteArrayOf(0,4,0,3, 0,4,0,4, 0,4,0,6, 0,4,0,9, 0,4,0,15), frames.single { it.messageId == 0xa100 }.body)
        assertEquals(listOf(0x1d02), car.message(0x1d01, vehicleMessages + vehicleComponent(3,4,6,9,15)).map { it.messageId })
    }

    @Test fun vehicleWithdrawalSendsOneStopOnTheLiveLink() {
        val car = Car()
        val capabilities = Iap2Csm.u16(6, 0xa101) + Iap2Csm.param(7, byteArrayOf(0xa1.toByte(), 0, 0xa1.toByte(), 2))
        car.ready(capabilities + vehicleComponent(3))
        car.message(0xa101, Iap2Csm.u16(3, 520))
        assertEquals(520, car.vehicleUpdates.last().rangeKm)

        val replacement = car.message(0x1d01, capabilities + vehicleComponent(4))
        assertEquals(listOf(0x1d02, 0xa100), replacement.map { it.messageId })
        assertArrayEquals(Iap2Csm.param(4, byteArrayOf()), replacement.last().body)
        car.message(0xa101, Iap2Csm.u16(4, 19))
        val withdrawal = car.message(0x1d01, capabilities)
        assertEquals(listOf(0x1d02, 0xa102), withdrawal.map { it.messageId })
        assertTrue(withdrawal.last().body.isEmpty())
        assertEquals(Iap2VehicleStatus(), car.vehicleUpdates.last())
        assertEquals(listOf(0x1d02), car.message(0x1d01, capabilities).map { it.messageId })
        car.message(0xa101, Iap2Csm.u16(4, 20))
        assertEquals(Iap2VehicleStatus(), car.vehicleUpdates.last())
        car.ready(capabilities + vehicleComponent(3))
        assertTrue(car.authenticationOutput.none { it.messageId == 0xa102 })
    }

    @Test fun malformedVehicleIdentificationCannotStartOrReplaceASubscription() {
        val car = Car()
        val capabilities = Iap2Csm.u16(6, 0xa101) + Iap2Csm.param(7, byteArrayOf(0xa1.toByte(), 0, 0xa1.toByte(), 2))
        val truncated = capabilities + vehicleComponent(4) + byteArrayOf(0)
        car.ready(truncated)
        assertTrue(car.authenticationOutput.none { it.messageId == 0xa100 })

        assertEquals(listOf(0x1d02, 0xa100), car.message(0x1d01, capabilities + vehicleComponent(3)).map { it.messageId })
        car.message(0xa101, Iap2Csm.u16(3, 520))
        assertTrue(car.message(0x1d01, truncated).isEmpty())
        val malformed = listOf(
            capabilities + Iap2Csm.u16(7, 0xa100) + vehicleComponent(4),
            Iap2Csm.u16(6, 0xa101) + Iap2Csm.param(7, byteArrayOf(0xa1.toByte(), 0, 1)) + vehicleComponent(4),
            capabilities + vehicleComponent(4) + vehicleComponent(3),
            capabilities + Iap2Csm.param(21, Iap2Csm.param(4, byteArrayOf()) + byteArrayOf(0)),
            capabilities + Iap2Csm.group(21, Iap2Csm.u8(4, 1)),
        )
        for (body in malformed) {
            assertEquals(listOf(0x1d02), car.message(0x1d01, body).map { it.messageId })
            assertFalse(car.protocol.failed)
            assertEquals(Iap2VehicleStatus(rangeKm = 520), car.vehicleUpdates.last())
        }
        car.message(0xa101, Iap2Csm.u16(3, 521) + Iap2Csm.u16(4, 19))
        assertEquals(Iap2VehicleStatus(rangeKm = 521), car.vehicleUpdates.last())
    }

    @Test fun vehicleStopUsesTheLastKnownSupportOnThisLink() {
        val canStop = Iap2Csm.u16(6, 0xa101) + Iap2Csm.param(7, byteArrayOf(0xa1.toByte(), 0, 0xa1.toByte(), 2))
        val car = Car()
        car.ready(canStop + vehicleComponent(3))
        assertEquals(listOf(0x1d02, 0xa102), car.message(0x1d01).map { it.messageId })
        car.message(0x1d01, canStop + vehicleComponent(3))
        assertEquals(listOf(0x1d02), car.message(0x1d01, Iap2Csm.param(7, byteArrayOf())).map { it.messageId })
        car.message(0x1d01, vehicleMessages + vehicleComponent(3))
        assertEquals(listOf(0x1d02), car.message(0x1d01).map { it.messageId })
    }

    @Test fun vehicleStopWaitsForSupportAndIsSentOnlyOnce() {
        val car = Car()
        val withdrawn = Iap2Csm.param(7, byteArrayOf())
        val canStop = Iap2Csm.u16(7, 0xa102)
        car.ready(vehicleMessages + vehicleComponent(3))
        car.message(0xa101, Iap2Csm.u16(3, 520))

        assertEquals(listOf(0x1d02), car.message(0x1d01, withdrawn).map { it.messageId })
        assertEquals(Iap2VehicleStatus(), car.vehicleUpdates.last())
        car.message(0xa101, Iap2Csm.u16(3, 521))
        assertEquals(Iap2VehicleStatus(), car.vehicleUpdates.last())
        assertEquals(listOf(0x1d02), car.message(0x1d01, canStop + withdrawn).map { it.messageId })
        assertEquals(listOf(0x1d02, 0xa102), car.message(0x1d01, canStop).map { it.messageId })
        assertEquals(listOf(0x1d02), car.message(0x1d01, canStop).map { it.messageId })
    }

    @Test fun pendingVehicleStopDoesNotSurviveANewSubscriptionOrLink() {
        val car = Car()
        val identity = vehicleMessages + vehicleComponent(3)
        val withdrawn = Iap2Csm.param(7, byteArrayOf())
        val canStop = Iap2Csm.u16(7, 0xa102)
        car.ready(identity)
        car.message(0x1d01, withdrawn)
        assertEquals(listOf(0x1d02, 0xa100), car.message(0x1d01, identity).map { it.messageId })
        assertEquals(listOf(0x1d02), car.message(0x1d01, Iap2Csm.u16(6, 0xa101) +
            Iap2Csm.param(7, byteArrayOf(0xa1.toByte(), 0, 0xa1.toByte(), 2)) +
            vehicleComponent(3)).map { it.messageId })
        car.message(0x1d01, withdrawn)
        car.ready(canStop)
        assertTrue(car.authenticationOutput.none { it.messageId == 0xa102 })
    }

    @Test fun vehicleStopUsesTheLastDeclarationInAnIngressBatch() {
        val car = Car()
        val withdrawn = Iap2Csm.param(7, byteArrayOf())
        val canStop = Iap2Csm.u16(7, 0xa102)
        car.ready(vehicleMessages + vehicleComponent(3))
        car.message(0x1d01, withdrawn)
        val output = car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession,
            Iap2Csm.encode(0x1d01, canStop) + Iap2Csm.encode(0x1d01, withdrawn)))
        assertEquals(listOf(0x1d02, 0x1d02), messages(output).map { it.messageId })
        assertEquals(listOf(0x1d02, 0xa102), car.message(0x1d01, canStop).map { it.messageId })
    }

    @Test fun aMalformedVehicleComponentCannotMaskAValidStopCapabilityWithdrawal() {
        val car = Car()
        val withdrawn = Iap2Csm.param(7, byteArrayOf())
        val canStop = Iap2Csm.u16(7, 0xa102)
        val invalidComponent = Iap2Csm.group(21, Iap2Csm.u8(3, 1))
        car.ready(vehicleMessages + vehicleComponent(3))
        car.message(0xa101, Iap2Csm.u16(3, 520))
        assertEquals(listOf(0x1d02), car.message(0x1d01, withdrawn + invalidComponent).map { it.messageId })
        assertEquals(520, car.vehicleUpdates.last().rangeKm)
        car.message(0x1d01, withdrawn)
        val output = car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession,
            Iap2Csm.encode(0x1d01, canStop) + Iap2Csm.encode(0x1d01, withdrawn + invalidComponent)))
        assertEquals(listOf(0x1d02, 0x1d02), messages(output).map { it.messageId })
        assertEquals(listOf(0x1d02, 0xa102), car.message(0x1d01, canStop).map { it.messageId })
        assertEquals(listOf(0x1d02), car.message(0x1d01, canStop).map { it.messageId })
    }

    @Test fun vehicleStopWaitsForTheRevokedWriteBeforeOfferingAgain() {
        for (wroteBeforeRevocation in listOf(false, true)) {
            val car = Car(managedWrites = true)
            val withdrawn = Iap2Csm.param(7, byteArrayOf())
            val canStop = Iap2Csm.u16(7, 0xa102)
            car.ready(vehicleMessages + vehicleComponent(3))
            car.message(0x1d01, withdrawn)
            assertEquals(listOf(0x1d02, 0xa102), car.message(0x1d01, canStop).map { it.messageId })
            val first = car.protocol.takeSocketWrites().single { it.cancellable }
            assertTrue(car.message(0x1d01, withdrawn).none { it.messageId == 0xa102 })
            assertEquals(listOf(car.protocol.outputGeneration to first.ticket), car.cancelledWrites)
            assertTrue(car.message(0x1d01, canStop).none { it.messageId == 0xa102 })

            car.protocol.socketWriteFinished(first.ticket, car.time.takeIf { wroteBeforeRevocation })
            val output = messages(car.feed(packet(0x40, car.sequence, car.ack)))
            assertEquals(if (wroteBeforeRevocation) 0 else 1, output.count { it.messageId == 0xa102 })
            if (!wroteBeforeRevocation) {
                val retry = car.protocol.takeSocketWrites().single { it.cancellable }
                assertTrue(retry.ticket > first.ticket)
                car.protocol.socketWriteFinished(retry.ticket, car.time)
            }
            assertTrue(car.message(0x1d01, canStop).none { it.messageId == 0xa102 })
        }
    }

    @Test fun aRetiredVehicleStopCannotWriteOrCompleteOnTheNewLink() {
        val car = Car(managedWrites = true)
        val canStop = Iap2Csm.u16(7, 0xa102)
        val withdrawn = Iap2Csm.param(7, byteArrayOf())
        val identity = vehicleMessages + vehicleComponent(3)
        car.ready(identity)
        car.message(0x1d01, withdrawn)
        assertEquals(listOf(0x1d02, 0xa102), car.message(0x1d01, canStop).map { it.messageId })
        val old = car.protocol.takeSocketWrites().single { it.cancellable }

        car.ready(identity)
        assertTrue(car.authenticationOutput.none { it.messageId == 0xa102 })
        car.message(0x1d01, withdrawn)
        car.protocol.socketWriteFinished(old.ticket, car.time)
        assertEquals(listOf(0x1d02, 0xa102), car.message(0x1d01, canStop).map { it.messageId })
        val current = car.protocol.takeSocketWrites().single { it.cancellable }
        assertTrue(current.ticket > old.ticket)
    }

    @Test fun vehicleSubscriptionNeedsBothMessageDirectionsAndSupportedComponentFlags() {
        for (identity in listOf(byteArrayOf(), vehicleMessages, vehicleComponent(3),
            Iap2Csm.u16(6, 0xa101) + vehicleComponent(3), Iap2Csm.u16(7, 0xa100) + vehicleComponent(3),
            vehicleMessages + vehicleComponent(5, 21), vehicleMessages + Iap2Csm.group(21, Iap2Csm.u8(3, 1)))) {
            val car = Car()
            car.ready(identity)
            assertTrue(car.authenticationOutput.none { it.messageId == 0xa100 })
        }
    }

    @Test fun vehicleUpdatesMergeValidSubscribedValuesAndResetOnLinkRestart() {
        val car = Car()
        val identity = vehicleMessages + vehicleComponent(3, 4, 6, 11, 15)
        car.ready(identity) { car.message(0xa101, Iap2Csm.u16(3, 123)) }
        assertTrue(car.vehicleUpdates.isEmpty())
        car.message(0xa101) // First A101 of the BMW capture is empty.
        car.message(0xa101, byteArrayOf(0,5,0,6,0))
        car.message(0xa101, byteArrayOf(0,6,0,4,0xff.toByte(),0xf9.toByte()))
        car.message(0xa101, byteArrayOf(0,6,0,3,2,0x49))
        car.message(0xa101, byteArrayOf(0,6,0,11,0,42, 0,5,0,15,1))
        assertEquals(Iap2VehicleStatus(585, -7, false, mapOf(VehicleFuel.ELECTRIC to 42),
            mapOf(VehicleFuel.ELECTRIC to true)), car.vehicleUpdates.last())
        car.message(0xa101, byteArrayOf(0,5,0,6,2, 0,5,0,4,22, 0,6,0,9,0,64))
        car.message(0xa101, byteArrayOf(0,6,0,3,0,1, 0,6,0))
        assertEquals(4, car.vehicleUpdates.size)
        car.message(0x1d01, identity)
        assertEquals(4, car.vehicleUpdates.size)
        car.ready()
        assertEquals(Iap2VehicleStatus(), car.vehicleUpdates.last())
        car.message(0xa101, Iap2Csm.u16(3, 0))
        assertEquals(5, car.vehicleUpdates.size)
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
        assertEquals(0x80, id.toInt() and 255)
        assertEquals(listOf(car.controlSession,2),output.map { it.session }) // No standalone ACK.
        val setup=output.single { it.session==2 }.body
        assertEquals(id, setup[0])
        assertArrayEquals(byteArrayOf(id,4,0,0,0,0,0,0,0,0,0,2),setup)
        car.time += 100
        assertTrue(car.protocol.advance(car.time).isEmpty()) // Real JPEG must not overtake initialization.
        val data=car.feed(packet(0x40,++car.sequence,car.ack,2,byteArrayOf(id,1)))
            .single { it.session==2 }.body
        assertArrayEquals(byteArrayOf(id,0xc0.toByte()),data)
        assertTrue(car.feed(packet(0x40,++car.sequence,car.ack,2,byteArrayOf(id,1))).isEmpty())
        car.time += 100
        val realSetup=car.feed(packet(0x40,++car.sequence,car.ack,2,byteArrayOf(id,5)))
        val realId=realSetup.single { it.session==2 }.body[0]
        assertEquals(0x81, realId.toInt() and 255)
        assertEquals(listOf(car.controlSession,2),realSetup.map { it.session })
        assertArrayEquals(byteArrayOf(realId,4,0,0,0,0,0,0,0,3,0,2),realSetup.single { it.session==2 }.body)
        assertArrayEquals(byteArrayOf(realId,0xc0.toByte(),9,8,7),
            car.feed(packet(0x40,++car.sequence,car.ack,2,byteArrayOf(realId,1))).single { it.session==2 }.body)
        car.feed(packet(0x40,++car.sequence,car.ack,2,byteArrayOf(realId,5)))
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

    @Test fun detectRevokesTransportReadinessUntilNewAuthenticationOutputIsEnqueued() {
        val car = Car().apply { autoRegisterReady = false }
        car.ready()
        assertTrue(car.transportReadiness.isEmpty())
        car.protocol.outputEnqueued()
        assertEquals(listOf(true), car.transportReadiness)

        car.ready(beforeIdentification = {
            assertFalse(car.protocol.ready)
            assertEquals(listOf(true, false), car.transportReadiness)
            car.protocol.outputEnqueued()
            assertEquals(listOf(true, false), car.transportReadiness)
        })
        assertEquals(listOf(true, false), car.transportReadiness)
        car.protocol.outputEnqueued()
        car.protocol.outputEnqueued()
        assertEquals(listOf(true, false, true), car.transportReadiness)
    }

    @Test fun transportReadinessRetriesAfterQueueFullButNeverBeforeNewAuthentication() {
        val car = Car().apply { autoRegisterReady = false }
        car.ready()
        car.acceptReadiness = false
        car.protocol.outputEnqueued()
        car.protocol.outputEnqueued()
        assertEquals(listOf(true, true), car.transportReadiness)

        car.ready(beforeIdentification = {
            car.acceptReadiness = true
            car.protocol.outputEnqueued()
            assertEquals(listOf(true, true, false), car.transportReadiness)
        })
        car.protocol.outputEnqueued()
        car.protocol.outputEnqueued()
        assertEquals(listOf(true, true, false, true), car.transportReadiness)
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
        assertTrue(car.protocol.diagnosticSummary.contains("rx_6800=1 rx_6802=4"))
        assertEquals(listOf(0xb5,0xb6),car.mediaKeys)
        car.message(0x6803,Iap2Csm.u16(0,1));press(1);assertEquals(2,car.mediaKeys.size)
        car.ready(identity);press(1);assertEquals(2,car.mediaKeys.size)
        assertTrue(car.logs.any { it.contains("IAP2-MEDIA: StartHID component=1") })
    }

    @Test fun deviceNameUsesSessionSnapshotAndSupportsUnicode() {
        Car(deviceName = "我的手机 \"BMW\"").ready()
        Car(deviceName = "重命名后的手机").ready()
    }

    @Test fun partialHudReceiveDeclarationDoesNotSendUnsupportedReferences() {
        val car = Car(profileId = "bmw_id85")
        car.ready(Iap2Csm.u16(Iap2Messages.IDENT_RECEIVE_IDS, 0x5204))
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("左转", 1, 100,
            lanes = listOf(io.github.hypercar.navigation.NavigationLane(1, true, 1)))
        assertTrue(car.message(0x5200, Iap2Csm.u16(0, 42)).isEmpty())
    }

    @Test fun earlyHudWaitsForTheNativeReadyBarrier() {
        val car = Car(profileId = "bmw_id85").apply { autoRegisterReady = false }
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("左转", 1, 100)
        car.ready { assertTrue(car.message(0x5200, Iap2Csm.u16(0, 42)).isEmpty()) }
        assertTrue(car.authenticationOutput.none { it.messageId in 0x5201..0x5204 })
        car.protocol.outputEnqueued()
        assertEquals(listOf(0x5202, 0x5201), car.message(0x1234).map { it.messageId })
    }

    @Test fun largeHudBatchObservesCapacityAndOnlyFinishesTheStartedCsmAfterStop() {
        val car = Car(packetLength = 32, profileId = "bmw_id85")
        val receive = Iap2Csm.param(Iap2Messages.IDENT_RECEIVE_IDS, byteArrayOf(0x52, 1, 0x52, 2, 0x52, 4))
        car.ready(receive)
        car.nativeCapacity = 5
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("左转", 1, 100,
            lanes = List(16) { io.github.hypercar.navigation.NavigationLane(7, true, 1) })
        val parser = Iap2Csm.Parser()
        val fromCar = mutableListOf<Iap2Csm.Frame>()
        fun consume(wire: List<Wire>) {
            assertTrue(wire.size <= car.nativeCapacity)
            fromCar += wire.filter { it.session == car.controlSession }.flatMap { parser.offer(it.body) }
        }
        fun command(id: Int, body: ByteArray) =
            car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession, Iap2Csm.encode(id, body)))
        consume(command(0x5200, Iap2Csm.u16(0, 42) + Iap2Csm.u16(0, 43)))
        assertTrue(fromCar.isEmpty()) // Large 0x5204 for component 42 has started but is incomplete.
        consume(command(0x5203, Iap2Csm.u16(0, 42)))
        repeat(50) { consume(car.feed(packet(0x40, car.sequence, car.ack))) }
        assertEquals(listOf(0x5204, 0x5204, 0x5202, 0x5201), fromCar.map { it.messageId })
        assertEquals(listOf(42, 43, 43, 43), fromCar.map { Iap2Csm.parseParams(it.body).first().asU16List().single() })
        assertTrue(car.protocol.ready)
        assertFalse(car.protocol.failed)
    }

    @Test fun stoppingBeforeTheFirstSocketWriteReusesItsSequenceForTheNextResponse() {
        val car = Car(profileId = "bmw_id85", managedWrites = true)
        car.ready()
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100)
        val lastWritten = car.ack
        val staged = car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession,
            Iap2Csm.encode(0x5200, Iap2Csm.u16(0, 42)))).single()
        val ticket = car.protocol.takeSocketWrites().single()
        assertTrue(ticket.cancellable)
        assertTrue(car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession,
            Iap2Csm.encode(0x5203, Iap2Csm.u16(0, 42)))).isEmpty())
        assertEquals(listOf(0L to ticket.ticket), car.cancelledWrites)
        assertTrue(car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession, Iap2Csm.encode(0x4154))).isEmpty())
        car.protocol.socketWriteFinished(ticket.ticket, null)
        val response = car.feed(packet(0x40, car.sequence, lastWritten)).single()
        assertEquals(staged.seq, response.seq)
        assertEquals(0x4155, messages(listOf(response)).single().messageId)
    }

    @Test fun localRouteStopBlocksPendingHudBeforeTheWorkerObservesIt() {
        val car = Car(profileId = "bmw_id85", managedWrites = true)
        car.ready()
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100)
        val lastWritten = car.ack
        val staged = car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession,
            Iap2Csm.encode(0x5200, Iap2Csm.u16(0, 42)))).single()
        val ticket = car.protocol.takeSocketWrites().single().ticket
        assertTrue(car.protocol.canStartHudWrite(0, ticket))
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null, state = 0)
        assertFalse(car.protocol.canStartHudWrite(0, ticket))
        car.protocol.socketWriteFinished(ticket, null)
        val clearing = car.feed(packet(0x40, car.sequence, lastWritten)).single()
        assertEquals(staged.seq, clearing.seq)
        val update = messages(listOf(clearing)).single()
        assertEquals(0x5201, update.messageId)
        assertArrayEquals(byteArrayOf(0), Iap2Csm.parseParams(update.body).single { it.id == 1 }.value)
    }

    @Test fun localRouteStopFinishesTheStartedHudMessageWithoutAnOldArrowReference() {
        val car = Car(packetLength = 32, profileId = "bmw_id85", managedWrites = true)
        val receive = Iap2Csm.param(Iap2Messages.IDENT_RECEIVE_IDS, byteArrayOf(0x52, 1, 0x52, 2, 0x52, 4))
        car.ready(receive)
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("左转", 1, 100,
            lanes = List(16) { io.github.hypercar.navigation.NavigationLane(7, true, 1) })
        val started = car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession,
            Iap2Csm.encode(0x5200, Iap2Csm.u16(0, 42)))).single()
        val ticket = car.protocol.takeSocketWrites().single().ticket
        assertTrue(car.protocol.canStartHudWrite(0, ticket))
        car.protocol.socketWriteFinished(ticket, car.time)
        car.navigationAllowed = false
        val output = mutableListOf(started)
        repeat(45) {
            output += car.feed(packet(0x40, car.sequence, car.ack))
            for (write in car.protocol.takeSocketWrites()) {
                assertFalse(write.cancellable)
                car.protocol.socketWriteFinished(write.ticket, car.time)
            }
        }
        assertEquals(listOf(0x5204), messages(output).map { it.messageId })
        assertTrue(car.cancelledWrites.isEmpty())
    }

    @Test fun hudRetransmissionKeepsItsSequenceWithoutRequiringAFirstFragmentPermit() {
        val engine = Iap2LinkEngine(reliableTransport = true, socketWriteFeedback = true)
        openHudTestLink(engine, managed = true)
        var sourceActive = true
        assertTrue(engine.queueBulkControl(byteArrayOf(1, 2, 3), true, 0x5201, { sourceActive }))
        engine.flushBulkControl(4, 8)
        val first = split(engine.takeOutput()).single()
        val firstWrite = engine.takeSocketWrites().single()
        assertTrue(firstWrite.hudFirst)
        engine.socketWriteFinished(firstWrite.ticket, 4)
        sourceActive = false
        engine.advanceTime(4_004)
        val retransmitted = split(engine.takeOutput()).single()
        val retryWrite = engine.takeSocketWrites().single()
        assertTrue(retryWrite.cancellable)
        assertFalse(retryWrite.hudFirst)
        assertEquals(first.seq, retransmitted.seq)
        assertArrayEquals(first.body, retransmitted.body)
    }

    @Test fun nativeSourceRejectionDiscardsItsCsmEvenIfTheSourceHasAlreadyReturned() {
        val car = Car(profileId = "bmw_id85", managedWrites = true)
        car.ready()
        car.navigationAllowed = true
        val cue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100)
        car.navigationCue = cue
        val lastWritten = car.ack
        val staged = car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession,
            Iap2Csm.encode(0x5200, Iap2Csm.u16(0, 42)))).single()
        val ticket = car.protocol.takeSocketWrites().single().ticket
        car.navigationCue = null
        assertFalse(car.protocol.canStartHudWrite(0, ticket))
        car.navigationCue = cue.copy(instruction = "新指引")
        car.protocol.socketWriteFinished(ticket, null, sourceRevoked = true)
        val next = car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession, Iap2Csm.encode(0x4154)))
        assertEquals(staged.seq, next.first().seq)
        val replies = messages(next)
        assertEquals(0x4155, replies.first().messageId)
        val fresh = replies.single { it.messageId == 0x5202 }
        assertEquals("新指引", Iap2Csm.parseParams(fresh.body).single { it.id == 2 }.asStringOrNull())
    }

    @Test fun rfcommRechecksTheHudStartAfterEarlierSocketBytesHaveBeenWritten() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val transport = RfcommTransport(java.io.Closeable {})
        var sourceActive = true
        var discarded = false
        val earlier = byteArrayOf(1, 2, 3)
        assertTrue(engine.sendControl(earlier, 4, awaitAck = false))
        assertTrue(engine.queueBulkControl(byteArrayOf(4, 5, 6), false, 0x5201, { sourceActive },
            onDiscarded = { discarded = true }))
        engine.flushBulkControl(4, 8)

        val writes = mutableListOf<Wire>()
        transport.writeOutput(engine) { bytes ->
            writes += split(bytes)
            HudSourceWrites.publish { sourceActive = false }
        }
        assertArrayEquals(earlier, writes.single().body)
        assertTrue(discarded)
        assertTrue(engine.sendControl(byteArrayOf(7), 5, awaitAck = false))
        val next = split(engine.takeOutput()).single { it.session != 0 }
        assertEquals((writes.single().seq + 1) and 0xff, next.seq)
    }

    @Test fun rfcommFinishesAStartedHudMessageAfterTheSourceStops() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false, maxLength = 32)
        val transport = RfcommTransport(java.io.Closeable {})
        val cue = ByteArray(44) { it.toByte() }
        var sourceActive = true
        var discarded = false
        assertTrue(engine.queueBulkControl(cue, false, 0x5202, { sourceActive },
            onDiscarded = { discarded = true }))

        val output = ByteArrayOutputStream()
        engine.flushBulkControl(4, 8)
        transport.writeOutput(engine, output::write)
        assertEquals(1, split(output.toByteArray()).size)
        HudSourceWrites.publish { sourceActive = false }
        engine.discardUnstartedBulkControl()
        engine.flushBulkControl(5, 8)
        transport.writeOutput(engine, output::write)

        val writes = split(output.toByteArray())
        assertEquals(2, writes.size)
        assertArrayEquals(cue, writes[0].body + writes[1].body)
        assertEquals((writes[0].seq + 1) and 0xff, writes[1].seq)
        assertFalse(discarded)
    }

    @Test fun rfcommRejectsHudWhenLocalStopWinsDuringSourceValidation() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val transport = RfcommTransport(Closeable { fail("stop aborted an unadmitted write") })
        var checkingTheFirstWrite = false
        var discarded = false
        assertTrue(engine.queueBulkControl(byteArrayOf(4, 5, 6), false, 0x5201, canStart = {
            if (checkingTheFirstWrite) transport.noteLocalStop()
            true
        }, onDiscarded = { discarded = true }))
        engine.flushBulkControl(4, 8)
        checkingTheFirstWrite = true

        assertEquals(0, transport.writeOutput(engine) { fail("HUD reached a stopped RFCOMM socket") })
        assertTrue(transport.localStopRequested)
        assertTrue(discarded)
    }

    @Test fun rfcommDefersFirstHudByteWhenACompletedReadIsWaitingForTheChannelLock() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val transport = RfcommTransport(Closeable { fail("a pending read should not close RFCOMM") })
        val required = byteArrayOf(1, 2, 3)
        val hud = byteArrayOf(4, 5, 6)
        var discarded = false
        assertTrue(engine.sendControl(required, 4, awaitAck = false))
        assertTrue(engine.queueBulkControl(hud, false, 0x5201, { true }, onDiscarded = { discarded = true }))
        engine.flushBulkControl(4, 8)

        val output = ByteArrayOutputStream()
        transport.writeOutput(engine) { bytes ->
            output.write(bytes)
            transport.setSocketReadPending(true)
        }
        val earlier = split(output.toByteArray())
        assertEquals(listOf(required.toList()), earlier.map { it.body.toList() })
        assertTrue(engine.hasBulkControl)
        assertFalse(discarded)

        transport.setSocketReadPending(false)
        output.reset()
        engine.flushBulkControl(5, 8)
        transport.writeOutput(engine, output::write)
        val resumed = split(output.toByteArray()).single()
        assertArrayEquals(hud, resumed.body)
        assertEquals((earlier.single().seq + 1) and 0xff, resumed.seq)
    }

    @Test fun rfcommLocalStopImmediatelyAbortsAnAdmittedFirstHudByte() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val firstWrite = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        val connections = RfcommConnections<Closeable>()
        val original = connections.register(Closeable { socketClosed.countDown() })
        connections.admit(original)
        val stop = connections.captureClose()
        val replacement = connections.register(Closeable { fail("replacement socket was closed") })
        connections.admit(replacement)
        assertTrue(engine.queueBulkControl(byteArrayOf(4, 5, 6), false, 0x5201, { true }))
        engine.flushBulkControl(4, 8)

        try {
            val writer = workers.submit<IOException> {
                assertThrows(IOException::class.java) {
                    original.writeOutput(engine) { bytes ->
                        assertEquals(1, bytes.size)
                        firstWrite.countDown()
                        assertTrue(finishWrite.await(3, TimeUnit.SECONDS))
                        throw IOException("socket closed")
                    }
                }
            }
            assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
            stop.noteLocalStop()
            assertEquals("local stop must close the admitted socket before returning", 0L, socketClosed.count)
            assertTrue(original.isCancelled)
            assertSame(replacement, connections.session.get())
            assertFalse(replacement.localStopRequested)
            finishWrite.countDown()
            assertEquals("socket closed", writer.get(2, TimeUnit.SECONDS).message)
        } finally {
            finishWrite.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun rfcommSourcePublicationWaitsForTheFirstByteAndCanCompleteDuringTheRemainder() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val socketClosed = CountDownLatch(1)
        val transport = RfcommTransport(Closeable { socketClosed.countDown() })
        val cue = byteArrayOf(4, 5, 6)
        var sourceActive = true
        assertTrue(engine.queueBulkControl(cue, false, 0x5201, { sourceActive }))
        engine.flushBulkControl(4, 8)

        val firstWrite = CountDownLatch(1)
        val releaseFirstByte = CountDownLatch(1)
        val publicationStarted = CountDownLatch(1)
        val publicationFinished = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val writer = workers.submit<Int> {
                transport.writeOutput(engine) { bytes ->
                    if (output.size() == 0) {
                        assertEquals(1, bytes.size)
                        firstWrite.countDown()
                        assertTrue(releaseFirstByte.await(2, TimeUnit.SECONDS))
                    } else {
                        assertTrue(publicationFinished.await(2, TimeUnit.SECONDS))
                        assertFalse(socketClosed.await(1_250, TimeUnit.MILLISECONDS))
                    }
                    output.write(bytes)
                }
            }
            assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
            val stop = workers.submit {
                publicationStarted.countDown()
                HudSourceWrites.publish { sourceActive = false }
                publicationFinished.countDown()
            }
            assertTrue(publicationStarted.await(2, TimeUnit.SECONDS))
            assertFalse(publicationFinished.await(100, TimeUnit.MILLISECONDS))
            releaseFirstByte.countDown()
            stop.get(2, TimeUnit.SECONDS)
            assertEquals(13, writer.get(3, TimeUnit.SECONDS))
            assertArrayEquals(cue, split(output.toByteArray()).single().body)
        } finally {
            releaseFirstByte.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun rfcommClosesAStalledFirstHudByteAndUnblocksSourcePublication() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val socketClosed = CountDownLatch(1)
        val transport = RfcommTransport(Closeable { socketClosed.countDown() })
        var sourceActive = true
        assertTrue(engine.queueBulkControl(byteArrayOf(4, 5, 6), false, 0x5201, { sourceActive }))
        engine.flushBulkControl(4, 8)

        val firstWrite = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val writer = workers.submit<IOException> {
                try {
                    transport.writeOutput(engine) {
                        firstWrite.countDown()
                        assertTrue(socketClosed.await(3, TimeUnit.SECONDS))
                        throw IOException("socket closed")
                    }
                    throw AssertionError("stalled write returned")
                } catch (error: IOException) {
                    error
                }
            }
            assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
            val stop = workers.submit { HudSourceWrites.publish { sourceActive = false } }
            assertEquals("socket closed", writer.get(4, TimeUnit.SECONDS).cause?.message)
            stop.get(2, TimeUnit.SECONDS)
            assertFalse(sourceActive)
            assertFalse(transport.localStopRequested)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun rfcommDiscardedHudStartDoesNotPostponeKeepalive() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val transport = RfcommTransport(Closeable {})
        val trace = mutableListOf<Int>()
        engine.onFrameSent = { _, _, _, session, _ -> trace += session }
        val deadline = engine.nextDeadlineMillis()!!
        var sourceActive = true
        assertTrue(engine.queueBulkControl(byteArrayOf(4, 5, 6), false, 0x5201, { sourceActive }))
        engine.flushBulkControl(deadline - 1, 8)

        HudSourceWrites.publish { sourceActive = false }
        assertEquals(0, transport.writeOutput(engine) { fail("revoked HUD reached RFCOMM") })
        assertTrue(trace.isEmpty())
        engine.advanceTime(deadline)
        val keepalive = split(engine.takeOutput()).single()
        assertEquals(0x40, keepalive.control)
        assertEquals(0, keepalive.session)
        assertEquals(listOf(0), trace)
        assertTrue(keepalive.body.isEmpty())
    }

    @Test fun rfcommPartialInboundControlOnlyDefersNewHudAndKeepsLinkOutputMoving() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false, maxLength = 32)
        val transport = RfcommTransport(Closeable {})
        val output = ByteArrayOutputStream()
        val route = ByteArray(44) { it.toByte() }
        assertTrue(engine.queueBulkControl(route, false, 0x5202, { true }))
        engine.flushBulkControl(4, 8)
        transport.writeOutput(engine, output::write)
        val first = split(output.toByteArray()).single()
        output.reset()

        val stop = packet(0x40, 87, first.seq, 1, Iap2Csm.encode(0x5203, Iap2Csm.u16(0, 42)))
        engine.feed(stop.copyOf(11), 5)
        assertTrue(engine.queueBulkControl(byteArrayOf(99), false, 0x5202, { true }))
        val required = byteArrayOf(11, 22, 33)
        assertTrue(engine.sendControl(required, 5, awaitAck = false))
        engine.flushBulkControl(5, 8)
        transport.writeOutput(engine, output::write)
        assertEquals(listOf(route.copyOfRange(22, 44).toList(), required.toList()),
            split(output.toByteArray()).map { it.body.toList() })
        assertTrue(engine.hasBulkControl)
        output.reset()

        val keepaliveDeadline = engine.nextDeadlineMillis()!!
        engine.advanceTime(keepaliveDeadline)
        transport.writeOutput(engine, output::write)
        assertEquals(0, split(output.toByteArray()).single().session)

        // Unknown stream prefix stops deferring optional work after its fixed grace period.
        output.reset()
        engine.flushBulkControl(6_000, 8)
        transport.writeOutput(engine, output::write)
        assertArrayEquals(byteArrayOf(99), split(output.toByteArray()).single().body)
    }

    @Test fun rfcommRetransmitsAnUnacknowledgedHudFrameAfterTheSourceStops() {
        val engine = Iap2LinkEngine(rfcommHudWriteFeedback = true)
        openHudTestLink(engine, managed = false)
        val transport = RfcommTransport(Closeable {})
        val cue = byteArrayOf(4, 5, 6)
        var sourceActive = true
        assertTrue(engine.queueBulkControl(cue, true, 0x5201, { sourceActive }))
        engine.flushBulkControl(4, 8)
        val output = ByteArrayOutputStream()
        transport.writeOutput(engine, output::write)
        val first = split(output.toByteArray()).single()

        HudSourceWrites.publish { sourceActive = false }
        engine.discardUnstartedBulkControl()
        engine.advanceTime(System.nanoTime() / 1_000_000 + 5_000)
        output.reset()
        transport.writeOutput(engine, output::write)
        val retry = split(output.toByteArray()).single { it.session != 0 }
        assertEquals(first.seq, retry.seq)
        assertArrayEquals(cue, retry.body)
    }

    private fun openHudTestLink(engine: Iap2LinkEngine, managed: Boolean, maxLength: Int = 65535) {
        engine.start(0, passive = true)
        engine.feed(Iap2LinkEngine.MARKER, 1)
        engine.takeOutput()
        val sync = Iap2LinkEngine.SynchronizationPayload(32, maxLength, 4000, 1000, 4, 3,
            listOf(Iap2LinkEngine.SessionDescriptor(1, 0, 2)))
        engine.feed(packet(0x80, 86, 0, body = sync.encode()), 2)
        val answer = split(engine.takeOutput()).single()
        val ticket = if (managed) engine.takeSocketWrites().single().ticket else 0L
        engine.feed(packet(0x40, 86, answer.seq), 3, writtenSynAck = ticket)
        engine.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
    }

    @Test fun aStartedCsmAndControlResponseDrainWhileNewBulkStartsArePaused() {
        val car = Car(packetLength = 32, profileId = "bmw_id85", managedWrites = true)
        val receive = Iap2Csm.param(Iap2Messages.IDENT_RECEIVE_IDS, byteArrayOf(0x52, 1, 0x52, 2, 0x52, 4))
        car.ready(receive)
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("左转", 1, 100,
            lanes = List(16) { io.github.hypercar.navigation.NavigationLane(7, true, 1) })
        fun incoming(id: Int, body: ByteArray = byteArrayOf()) =
            packet(0x40, ++car.sequence, car.ack, car.controlSession, Iap2Csm.encode(id, body))
        val staged = car.feed(incoming(0x5200, Iap2Csm.u16(0, 42) + Iap2Csm.u16(0, 43))).single()
        val write = car.protocol.takeSocketWrites().single()
        car.protocol.socketWriteFinished(write.ticket, car.time)
        car.allowBulkStart = false
        car.nativeCapacity = 2
        val output = mutableListOf(staged)
        output += car.feed(incoming(0x5203, Iap2Csm.u16(0, 42)) + incoming(0x4154))
        repeat(45) { output += car.feed(packet(0x40, car.sequence, car.ack)) }
        val frames = messages(output)
        assertEquals(listOf(0x5204, 0x4155), frames.map { it.messageId })
        assertEquals(42, Iap2Csm.parseParams(frames.first().body).first().asU16List().single())
        assertTrue(car.cancelledWrites.isEmpty())
        car.nativeCapacity = 64
        car.allowBulkStart = true
        car.feed(packet(0x40, car.sequence, car.ack))
        assertTrue(car.protocol.takeSocketWrites().single().cancellable)
    }

    @Test fun restartOnlyAcceptsTheAckForTheCurrentSynAck() {
        val car = Car(window = 5, profileId = "bmw_id85")
        car.ready()
        val previousAck = car.ack
        car.feed(Iap2LinkEngine.MARKER)
        val peer = Iap2LinkEngine.SynchronizationPayload(5, 65535, 37, 73, 1, 0,
            listOf(Iap2LinkEngine.SessionDescriptor(car.controlSession, 0, 2)))
        val synAck = car.feed(packet(0x80, car.sequence, 0, body = peer.encode())).single()
        assertNotEquals(previousAck, synAck.seq)
        assertNotEquals(100, synAck.seq) // The previous handshake's SYN|ACK.
        assertTrue(car.feed(packet(0x40, car.sequence, previousAck)).isEmpty())
        assertTrue(car.feed(packet(0x40, car.sequence, (synAck.seq + 70) and 255)).isEmpty())
        assertEquals("LINK", car.protocol.diagnosticPhase)
        val stale = Iap2Csm.encode(0x5200, Iap2Csm.u16(0, 42))
        assertTrue(car.feed(packet(0x40, car.sequence + 1, (synAck.seq + 70) and 255, car.controlSession, stale)).isEmpty())
        assertEquals(listOf(0x1d00), messages(car.feed(packet(0x40, car.sequence, synAck.seq))).map { it.messageId })
    }

    @Test fun passiveWifiDoesNotAcceptAnOldSynAckOrReuseAnEchoAcrossDetect() {
        val car = Car(window = 5, profileId = "bmw_id85")
        car.ready()
        val peer = Iap2LinkEngine.SynchronizationPayload(5, 65535, 37, 73, 1, 0,
            listOf(Iap2LinkEngine.SessionDescriptor(car.controlSession, 0, 2)))
        car.feed(Iap2LinkEngine.MARKER)
        assertTrue(car.feed(packet(0xc0, car.sequence, 99, body = peer.encode())).isEmpty())
        assertEquals("LINK", car.protocol.diagnosticPhase)
        val syn = packet(0x80, car.sequence, 0, body = peer.encode())
        val before = car.feed(syn).single()
        car.feed(Iap2LinkEngine.MARKER)
        assertTrue(car.feed(packet(0x40, car.sequence, before.seq)).isEmpty())
        assertEquals("LINK", car.protocol.diagnosticPhase)
        val after = car.feed(syn).single()
        assertNotEquals(before.seq, after.seq)
        assertEquals(0x1d00, messages(car.feed(packet(0x40, car.sequence, after.seq))).single().messageId)
    }

    @Test fun ackBeforeTheSynAckFinishesWritingCannotOpenTheLink() {
        val car = Car(profileId = "bmw_id85", managedWrites = true)
        car.ready()
        car.feed(Iap2LinkEngine.MARKER)
        val peer = Iap2LinkEngine.SynchronizationPayload(5, 65535, 0, 0, 0, 0,
            listOf(Iap2LinkEngine.SessionDescriptor(car.controlSession, 0, 2)))
        val echo = car.feed(packet(0x80, car.sequence, 0, body = peer.encode())).single()
        val ticket = car.protocol.takeSocketWrites().single().ticket
        val ack = packet(0x40, car.sequence, echo.seq)
        assertTrue(car.protocol.feed(ack, car.time++, ticket - 1).isEmpty())
        assertEquals("LINK", car.protocol.diagnosticPhase)
        assertEquals(0x1d00, messages(split(car.protocol.feed(ack, car.time++, ticket))).single().messageId)
    }

    @Test fun aNecessaryReplyCanWithdrawAnUnwrittenOptionalStartAndUseItsSequence() {
        val car = Car(profileId = "bmw_id85", managedWrites = true)
        car.ready()
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("左转", 1, 100)
        val first = car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession,
            Iap2Csm.encode(0x5200, Iap2Csm.u16(0, 42)))).single()
        val write = car.protocol.takeSocketWrites().single()
        car.nativeCapacity = 2
        assertTrue(car.feed(packet(0x40, ++car.sequence, (first.seq - 1) and 255,
            car.controlSession, Iap2Csm.encode(0x4154))).isEmpty())
        assertEquals(listOf(0L to write.ticket), car.cancelledWrites)
        car.protocol.socketWriteFinished(write.ticket, null)
        val response = car.feed(packet(0x40, car.sequence, (first.seq - 1) and 255)).single()
        assertEquals(first.seq, response.seq)
        assertEquals(0x4155, messages(listOf(response)).single().messageId)
    }

    @Test fun destinationHandoffOnlyEmitsARealOfferOnTheCurrentSubscription() {
        val car = Car()
        car.navigationAllowed = true
        car.ready { assertTrue(car.message(0x10da, Iap2Csm.param(0, byteArrayOf())).isEmpty()) }
        assertTrue(car.authenticationOutput.none { it.messageId == 0x10db })

        val offer = io.github.hypercar.navigation.NavigationDestination("test-trip", 1.0, -2.0, "en_GB",
            name = "Station", address = "Main Road", sourceName = "高德地图")
        val target = car.protocol.destinationTarget!!
        val cue = io.github.hypercar.navigation.NavigationCue("", null, null, destinationToShare = target.authorize(offer))
        car.navigationCue = cue
        val reply = car.message(0x1234).single()
        assertEquals(0x10db, reply.messageId)
        val params = Iap2Csm.parseParams(reply.body).associateBy { it.id }
        val firstWireId = params.getValue(0).asStringOrNull()!!
        assertNotEquals(offer.id, firstWireId)
        assertEquals("en_GB", params.getValue(6).asStringOrNull())
        assertEquals("Main Road", params.getValue(3).asStringOrNull())
        assertArrayEquals("高德地图\u0000".encodeToByteArray(), params.getValue(7).value)
        val coordinates = Iap2Csm.parseParams(params.getValue(2).value)
        assertArrayEquals(byteArrayOf(0, 64, 0, 0), coordinates[0].value)
        assertArrayEquals(byteArrayOf(-1, -128, 0, 0), coordinates[1].value)
        car.message(0x10dd, Iap2Csm.string(0, "another-trip"))
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        car.message(0x10dd, Iap2Csm.string(0, firstWireId) + Iap2Csm.group(2, Iap2Csm.param(2, byteArrayOf())))
        assertTrue(car.logs.any { "DESTINATION: result accepted" in it })
        assertTrue(car.message(0x10da).isEmpty())
        car.message(0x10dc)
        car.navigationCue = cue.copy(destinationToShare = target.authorize(offer))
        assertTrue(car.message(0x1234).isEmpty())
        assertTrue(car.message(0x10da, Iap2Csm.u8(0, 1)).isEmpty())
        car.ready()
        assertTrue(car.authenticationOutput.none { it.messageId == 0x10db })
        assertNull(target.authorize(offer))
        assertTrue(car.message(0x10da).isEmpty())
        car.navigationCue = cue.copy(destinationToShare = car.protocol.destinationTarget!!.authorize(offer))
        val second = car.message(0x1234).single()
        assertEquals(0x10db, second.messageId)
        val secondParams = Iap2Csm.parseParams(second.body)
        assertTrue(secondParams.none { it.id == 7 })
        val secondWireId = secondParams.first().asStringOrNull()!!
        assertNotEquals(firstWireId, secondWireId)
        car.message(0x10dd, Iap2Csm.string(0, firstWireId))
        assertEquals(1, car.logs.count { "DESTINATION: result accepted" in it })
        car.message(0x10dd, Iap2Csm.string(0, secondWireId))
        assertEquals(2, car.logs.count { "DESTINATION: result accepted" in it })
    }

    @Test fun destinationSourceNameIsOmittedWhenTheAuthorizedSourceHasNoName() {
        val car = Car().apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
        val target = car.protocol.destinationTarget!!
        for (sourceName in listOf(null, "   ")) {
            val destination = io.github.hypercar.navigation.NavigationDestination(
                "A", 1.0, 2.0, "en_GB", name = "Display name", sourceName = sourceName,
            )
            car.navigationCue = io.github.hypercar.navigation.NavigationCue(
                "", null, null, sourceName = "Cue label", destinationToShare = target.authorize(destination),
            )
            val reply = car.message(0x1234).single { it.messageId == 0x10db }
            assertTrue(Iap2Csm.parseParams(reply.body).none { it.id == 7 })
        }
    }

    @Test fun destinationRequestsDuringExplicitDenialDoNotSurviveCapabilityRecovery() {
        val car = Car(profileId = "bmw_id85").apply { navigationAllowed = true }
        val receive = Iap2Messages.IDENT_RECEIVE_IDS
        val supported = Iap2Csm.u16(receive, 0x10db)
        car.ready(supported)
        car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
        car.message(0x1d01, Iap2Csm.u16(receive, 0x5001))
        assertNull(car.protocol.destinationTarget)
        car.message(0x1d01)
        assertNull(car.protocol.destinationTarget)
        car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
        car.message(0x1d01, supported)

        val target = car.protocol.destinationTarget!!
        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB", sourceName = "Map app")
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
            destinationToShare = target.authorize(destination))
        assertTrue(car.message(0x1234).none { it.messageId == 0x10db })
        val offer = car.message(0x10da, Iap2Csm.param(0, byteArrayOf())).single { it.messageId == 0x10db }
        assertEquals("Map app", Iap2Csm.parseParams(offer.body).single { it.id == 7 }.asStringOrNull())
    }

    @Test fun mfiDestinationDenialGatesRequestsWithoutChangingAuthenticationOrder() {
        for (version in listOf(1, 2)) {
            val car = Car(profileId = "bmw_id85").apply { navigationAllowed = true }
            val supported = Iap2Csm.u16(Iap2Messages.IDENT_RECEIVE_IDS, 0x10db)
            val denied = Iap2Csm.u16(Iap2Messages.IDENT_RECEIVE_IDS, 0x5001)
            val start = car.start { it.copy(sessions = it.sessions.map { session ->
                if (session.kind == 0) session.copy(version = version) else session
            }) }
            assertEquals(listOf(if (version == 1) 0xaa00 else 0x1d00), start.map { it.messageId })
            if (version == 2) {
                assertEquals(listOf(0x1d02, 0xaa00), car.message(0x1d01, supported).map { it.messageId })
                assertTrue(car.message(0x10da, Iap2Csm.param(0, byteArrayOf())).isEmpty())
            }
            if (version == 1) assertTrue(car.message(0x1d01, denied).isEmpty())
            assertEquals(0xaa02, car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single().messageId)
            if (version == 2) assertTrue(car.message(0x1d01, denied).isEmpty())
            assertTrue(car.message(0x10da, Iap2Csm.param(0, byteArrayOf())).isEmpty())
            val greetings = listOf(0x4e0c, 0x5702, 0x4e0a, 0x4e09, 0x4e0b)
            val authentication = car.message(0xaa03, Iap2Csm.param(0, ByteArray(64))).map { it.messageId }
            assertEquals(listOf(0xaa05) + if (version == 1) listOf(0x1d00) else greetings, authentication)
            if (version == 2) assertNull(car.protocol.destinationTarget)
            val identification = car.message(0x1d01, supported).map { it.messageId }
            assertEquals(listOf(0x1d02) + if (version == 1) greetings else emptyList(), identification)

            val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB", sourceName = "Map app")
            car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
                destinationToShare = car.protocol.destinationTarget!!.authorize(destination))
            assertTrue(car.message(0x1234).none { it.messageId == 0x10db })
            val offer = car.message(0x10da, Iap2Csm.param(0, byteArrayOf())).single { it.messageId == 0x10db }
            assertEquals("Map app", Iap2Csm.parseParams(offer.body).single { it.id == 7 }.asStringOrNull())
        }
    }

    @Test fun aPartialDestinationWithdrawalHoldsOrCancelsTheUnwrittenFirstFragment() {
        for (staged in listOf(false, true)) {
            val car = Car(managedWrites = true).apply { navigationAllowed = true }
            car.ready()
            car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
            val target = car.protocol.destinationTarget!!
            val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB", sourceName = "Map app")
            car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
                destinationToShare = target.authorize(destination))
            val lastWritten = car.ack
            val stagedTicket = if (staged) {
                assertEquals(0x10db, car.message(0x1234).single().messageId)
                car.protocol.takeSocketWrites().single().ticket
            } else null
            val bareRequest = Iap2Csm.encode(0x10da)
            assertTrue(car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession,
                bareRequest.copyOfRange(0, 4))).isEmpty())
            if (stagedTicket != null) {
                assertEquals(listOf(0L to stagedTicket), car.cancelledWrites)
                car.protocol.socketWriteFinished(stagedTicket, null)
            }
            val output = car.feed(packet(0x40, ++car.sequence, lastWritten, car.controlSession,
                bareRequest.copyOfRange(4, bareRequest.size))).single()
            assertEquals((lastWritten + 1) and 0xff, output.seq)
            val offer = messages(listOf(output)).single()
            assertEquals(0x10db, offer.messageId)
            assertTrue(Iap2Csm.parseParams(offer.body).none { it.id == 7 })
        }
    }

    @Test fun stalledControlFragmentExpiresTheDestinationSubscriptionWithoutBlockingReplies() {
        val car = Car().apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB", sourceName = "Map app")
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
            destinationToShare = car.protocol.destinationTarget!!.authorize(destination))
        val query = Iap2Csm.encode(0x4154)
        assertTrue(car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession, query.copyOfRange(0, 4))).isEmpty())
        car.time += 1_100
        assertTrue(car.protocol.advance(car.time).isEmpty())
        val complete = car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession, query.copyOfRange(4, query.size)))
        assertEquals(listOf(0x4155), messages(complete).map { it.messageId })
        val offer = car.message(0x10da).single()
        assertEquals(0x10db, offer.messageId)
        assertTrue(Iap2Csm.parseParams(offer.body).none { it.id == 7 })
    }

    @Test fun aNewControlPrefixInTheSamePacketGetsItsOwnDeadline() {
        val car = Car(profileId = "bmw_id85").apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
        val query = Iap2Csm.encode(0x4154)
        val prefix = query.copyOfRange(0, 4)
        val suffix = query.copyOfRange(4, query.size)
        fun incoming(time: Long, body: ByteArray): List<Int> {
            car.time = time
            return messages(car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession, body))).map { it.messageId }
        }
        assertTrue(incoming(1_000, prefix).isEmpty())
        assertEquals(listOf(0x4155), incoming(1_950, suffix + prefix))
        assertTrue(car.protocol.advance(2_001).isEmpty())
        assertEquals(listOf(0x4155), incoming(2_050, suffix))

        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB", sourceName = "Map app")
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
            destinationToShare = car.protocol.destinationTarget!!.authorize(destination))
        val offer = car.message(0x1234).single { it.messageId == 0x10db }
        assertEquals("Map app", Iap2Csm.parseParams(offer.body).single { it.id == 7 }.asStringOrNull())
    }

    @Test fun aSplitBareLinkAckDoesNotExpireTheDestinationSubscription() {
        val car = Car().apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da)
        val ack = packet(0x40, car.sequence, car.ack)
        assertTrue(car.feed(ack.copyOfRange(0, 4)).isEmpty())
        car.time += 1_100
        assertTrue(car.protocol.advance(car.time).isEmpty())
        assertTrue(car.feed(ack.copyOfRange(4, ack.size)).isEmpty())

        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB")
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
            destinationToShare = car.protocol.destinationTarget!!.authorize(destination))
        assertEquals(0x10db, car.message(0x1234).single().messageId)
    }

    @Test fun changingTheDestinationSourceNameRequestPreservesOnlyAlreadyWrittenOffers() {
        for (wroteBeforeChange in listOf(false, true)) {
            val car = Car(managedWrites = true).apply { navigationAllowed = true }
            car.ready()
            car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
            val target = car.protocol.destinationTarget!!
            val destination = io.github.hypercar.navigation.NavigationDestination(
                "A", 1.0, 2.0, "en_GB", sourceName = "Map app",
            )
            car.navigationCue = io.github.hypercar.navigation.NavigationCue(
                "", null, null, destinationToShare = target.authorize(destination),
            )
            val offer = car.message(0x1234).single { it.messageId == 0x10db }
            val fields = Iap2Csm.parseParams(offer.body).associateBy { it.id }
            assertEquals("Map app", fields.getValue(7).asStringOrNull())
            val oldId = fields.getValue(0)
            val ticket = car.protocol.takeSocketWrites().single { it.cancellable }
            assertTrue(car.message(0x10da).none { it.messageId == 0x10db })
            assertEquals(listOf(car.protocol.outputGeneration to ticket.ticket), car.cancelledWrites)
            car.protocol.socketWriteFinished(ticket.ticket, car.time.takeIf { wroteBeforeChange })

            val output = car.message(0x10dd, Iap2Csm.param(0, oldId.value))
            if (wroteBeforeChange) {
                assertTrue(output.none { it.messageId == 0x10db })
            } else {
                assertTrue(car.logs.none { "DESTINATION: result" in it })
                val replacement = output.single { it.messageId == 0x10db }
                val newFields = Iap2Csm.parseParams(replacement.body)
                assertTrue(newFields.none { it.id == 7 })
                val newId = newFields.first()
                assertFalse(oldId.value.contentEquals(newId.value))
                val write = car.protocol.takeSocketWrites().single { it.cancellable }
                car.protocol.socketWriteFinished(write.ticket, car.time)
                car.message(0x10dd, Iap2Csm.param(0, newId.value))
            }
            assertEquals(1, car.logs.count { "DESTINATION: result accepted" in it })
        }
    }

    @Test fun earlyHudSubscriptionWaitsForAuthenticationAndRealSource() {
        val car = Car()
        car.ready { assertTrue(car.message(0x5200, Iap2Csm.u16(0, 77)).isEmpty()) }
        assertTrue(car.authenticationOutput.none { it.messageId in listOf(0x5201, 0x5202) })
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

    @Test fun replacingAnUnsentDestinationAndReauthorizingItCannotUseAnOldResult() {
        val car = Car().apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da)
        val target = car.protocol.destinationTarget!!
        val first = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB", name = "First")
        val other = first.copy(id = "B", name = "Other")
        fun display(share: io.github.hypercar.navigation.NavigationDestinationShare?) {
            car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null, destinationToShare = share)
        }
        fun sentDestination() = Iap2Csm.parseParams(car.message(0x1234).single { it.messageId == 0x10db }.body)
            .associateBy { it.id }
        val old = target.authorize(first)
        car.nativeCapacity = 2
        display(old)
        assertTrue(car.message(0x1234).isEmpty())
        display(target.authorize(other))
        car.nativeCapacity = 64
        val sentOther = sentDestination()
        assertEquals("Other", sentOther.getValue(1).asStringOrNull())
        display(old)
        assertTrue(car.message(0x1234).isEmpty())
        display(target.authorize(first))
        val sentFirst = sentDestination()
        assertEquals("First", sentFirst.getValue(1).asStringOrNull())
        assertNotEquals(sentOther.getValue(0), sentFirst.getValue(0))
        car.message(0x10dd, Iap2Csm.param(0, sentOther.getValue(0).value))
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        car.message(0x10dd, Iap2Csm.param(0, sentFirst.getValue(0).value))
        assertEquals(1, car.logs.count { "DESTINATION: result accepted" in it })
    }

    @Test fun hudReceiveDeclarationGatesSubscriptionAndHeartbeatOnTheActualLink() {
        val car = Car(profileId = "generic")
        val parameter = Iap2Messages.IDENT_RECEIVE_IDS
        car.ready(Iap2Csm.u16(parameter, 0x5001))
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100)
        assertTrue(car.message(0x5200, Iap2Csm.u16(0, 77)).isEmpty())
        assertTrue(car.message(0x1d01, Iap2Csm.param(parameter, byteArrayOf())).none { it.messageId in 0x5201..0x5202 })
        val supported = car.message(0x1d01, Iap2Csm.u16(parameter, 0x5201))
        assertEquals(listOf(0x1d02, 0x5201), supported.map { it.messageId })
        assertArrayEquals(byteArrayOf(0, 77), Iap2Csm.parseParams(supported.last().body).first().value)
        car.message(0x1d01, Iap2Csm.u16(parameter, 0x5001))
        car.time += 10_000
        assertTrue(car.message(0x4154).none { it.messageId in 0x5201..0x5202 })

        // A malformed declaration cannot enable optional navigation; an absent one retains legacy behavior.
        assertEquals(listOf(0x1d02), car.message(0x1d01, Iap2Csm.param(parameter, byteArrayOf(0x50))).map { it.messageId })
        assertEquals(listOf(0x1d02, 0x5202, 0x5201),
            car.message(0x1d01).map { it.messageId })
    }

    @Test fun genericWifiLinkSendsUnchangedHudHeartbeatThenOneClear() {
        val car = Car(profileId = "generic")
        car.ready(Iap2Csm.group(30, Iap2Csm.u16(0, 77), Iap2Csm.u16(2, 64)))
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100,
            currentRoad = "测试路", remainingDistanceMeters = 1200, remainingTimeSeconds = 60)
        val first = car.message(0x5200, Iap2Csm.u16(0, 77))
        assertEquals(listOf(0x5202, 0x5201), first.map { it.messageId })
        val fields = Iap2Csm.parseParams(first.last().body).associate { it.id to it.value }
        assertArrayEquals("测试路\u0000".encodeToByteArray(), fields[0x03])
        assertArrayEquals(Iap2Csm.u64Bytes(60), fields[0x06])
        assertArrayEquals(Iap2Csm.u32Bytes(1200), fields[0x07])
        car.time += 10_000
        val heartbeat = car.message(0x4154).filter { it.messageId in 0x5201..0x5202 }.single()
        assertEquals(0x5201, heartbeat.messageId)
        assertArrayEquals(first.last().body, heartbeat.body)
        car.navigationCue = null
        car.time += 1_000
        val clear = car.message(0x4154).filter { it.messageId in 0x5201..0x5202 }.single()
        assertEquals(0x5201, clear.messageId)
        assertArrayEquals(byteArrayOf(0, 77), Iap2Csm.parseParams(clear.body).first().value)
        assertArrayEquals(byteArrayOf(0), Iap2Csm.parseParams(clear.body).single { it.id == 1 }.value)
        assertTrue(Iap2Csm.parseParams(clear.body).none { it.id in setOf(0x03, 0x06, 0x07) })
        car.time += 10_000
        assertTrue(car.message(0x4154).none { it.messageId in 0x5201..0x5202 })
    }

    @Test fun lateIdentificationClearsRoadOnceForTheComponentThatLostItsLimit() {
        val car = Car(profileId = "generic")
        val receive = Iap2Csm.u16(Iap2Messages.IDENT_RECEIVE_IDS, 0x5201)
        fun roadLimit(id: Int) = Iap2Csm.group(30, Iap2Csm.u16(0, id), Iap2Csm.u16(2, 64))
        fun updates(frames: List<Iap2Csm.Frame>) = frames.filter { it.messageId == 0x5201 }.associate { frame ->
            val fields = Iap2Csm.parseParams(frame.body).associateBy { it.id }
            fields.getValue(0).asU16List().single() to fields
        }
        car.ready(receive + roadLimit(77) + roadLimit(78))
        car.navigationAllowed = true
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("右转", 2, 100, currentRoad = "测试路")
        val road = "测试路\u0000".encodeToByteArray()
        for (id in 77..79) {
            val first = updates(car.message(0x5200, Iap2Csm.u16(0, id))).getValue(id)
            if (id < 79) assertArrayEquals(road, first.getValue(0x03).value)
            else assertFalse(first.containsKey(0x03))
        }

        val changed = updates(car.message(0x1d01, receive + roadLimit(78)))
        assertArrayEquals(byteArrayOf(1), changed.getValue(77).getValue(1).value)
        assertArrayEquals(byteArrayOf(0), changed.getValue(77)[0x03]?.value)
        assertArrayEquals(road, changed.getValue(78).getValue(0x03).value)
        assertFalse(changed.getValue(79).containsKey(0x03))

        car.time += 10_000
        val heartbeat = updates(car.message(0x4154))
        assertFalse(heartbeat.getValue(77).containsKey(0x03))
        assertArrayEquals(road, heartbeat.getValue(78).getValue(0x03).value)
        assertArrayEquals(road, updates(car.message(0x1d01, receive + roadLimit(77) + roadLimit(78)))
            .getValue(77).getValue(0x03).value)
    }

    @Test fun replanningKeepsThePendingRoadClearUntilTheRouteResumes() {
        val car = Car(profileId = "generic")
        val receive = Iap2Csm.u16(Iap2Messages.IDENT_RECEIVE_IDS, 0x5201)
        val roadLimit = Iap2Csm.group(30, Iap2Csm.u16(0, 77), Iap2Csm.u16(2, 64))
        fun fields(frames: List<Iap2Csm.Frame>) = frames.filter { it.messageId == 0x5201 }.associate { frame ->
            val fields = Iap2Csm.parseParams(frame.body).associateBy { it.id }
            fields.getValue(0).asU16List().single() to fields.mapValues { it.value.value }
        }
        car.ready(receive + roadLimit)
        car.navigationAllowed = true
        val active = io.github.hypercar.navigation.NavigationCue("右转", 2, 100, currentRoad = "测试路")
        car.navigationCue = active
        val start = fields(car.message(0x5200, Iap2Csm.u16(0, 77))).getValue(77)
        assertArrayEquals("测试路\u0000".encodeToByteArray(), start[0x03])
        val other = fields(car.message(0x5200, Iap2Csm.u16(0, 78))).getValue(78)
        assertFalse(other.containsKey(0x03))

        car.navigationCue = io.github.hypercar.navigation.AmapNotificationParser.parse(listOf("正在重新规划路线"), true)
        car.time += 1000
        val replanning = fields(car.message(0x4154))
        assertArrayEquals(byteArrayOf(5), replanning.getValue(77)[1])
        assertFalse(replanning.getValue(77).containsKey(0x03))

        car.navigationCue = active.copy(currentRoad = "")
        car.time += 1000
        val resumed = fields(car.message(0x4154))
        assertArrayEquals(byteArrayOf(1), resumed.getValue(77)[1])
        assertArrayEquals(byteArrayOf(0), resumed.getValue(77)[0x03])
        assertFalse(resumed.getValue(78).containsKey(0x03))

        car.time += 10_000
        val heartbeat = fields(car.message(0x4154))
        assertEquals(setOf(77, 78), heartbeat.keys)
        assertTrue(heartbeat.values.none { it.containsKey(0x03) })
    }

    @Test fun amapLinkReplanningWinsUntilTheNextFullGuidanceSample() {
        val car = Car(profileId = "generic")
        car.ready(Iap2Csm.param(Iap2Messages.IDENT_RECEIVE_IDS, byteArrayOf(0x52, 1, 0x52, 2)) +
            Iap2Csm.group(30, Iap2Csm.u16(0, 77), Iap2Csm.u16(2, 64)))
        car.navigationAllowed = true
        val link = io.github.hypercar.navigation.AmapLinkNavigationState()
        val notifications = io.github.hypercar.navigation.NavigationCueStore()
        val linkToken = link.begin()
        val notificationGeneration = notifications.generation()
        val replan = requireNotNull(io.github.hypercar.navigation.AmapNotificationParser.parse(listOf("正在重新规划路线"), true))
        fun type4(road: String) = org.json.JSONObject()
            .put("curManeuverID", 3).put("segmentRemainDist", 100).put("curRouteName", road)
        fun send(id: Int, body: ByteArray = byteArrayOf()): List<Iap2Csm.Frame> {
            car.navigationCue = link.hudSnapshot(car.time, notifications.clusterRoute(car.time))
            return car.message(id, body).filter { it.messageId in 0x5201..0x5202 }
        }
        fun field(frames: List<Iap2Csm.Frame>, id: Int) = Iap2Csm.parseParams(frames.single { it.messageId == 0x5201 }.body)
            .singleOrNull { it.id == id }?.value

        car.time += 1000
        assertTrue(link.accept(linkToken, 4, type4("旧路"), car.time))
        notifications.put(notificationGeneration, "navigation", replan, car.time - 500)
        val first = send(0x5200, Iap2Csm.u16(0, 77))
        assertArrayEquals("旧路\u0000".encodeToByteArray(), field(first, 0x03))

        car.time += 1000
        val replannedAt = car.time
        notifications.put(notificationGeneration, "navigation", replan, replannedAt)
        val replanning = send(0x4154)
        assertEquals(listOf(0x5201), replanning.map { it.messageId })
        assertArrayEquals(byteArrayOf(5), field(replanning, 1))

        car.time += 1000
        notifications.remove("navigation")
        assertTrue(link.accept(linkToken, 5, org.json.JSONObject().put("maneuverID", 2), car.time))
        assertTrue(send(0x4154).isEmpty()) // A partial turn hint cannot republish the old route.

        car.time += 1000
        assertTrue(link.accept(linkToken, 4, type4(""), car.time))
        notifications.put(notificationGeneration, "navigation", replan, replannedAt)
        val resumed = send(0x4154)
        assertEquals(listOf(0x5202, 0x5201), resumed.map { it.messageId })
        assertArrayEquals(byteArrayOf(1), field(resumed, 1))
        assertArrayEquals(byteArrayOf(0), field(resumed, 0x03))

        car.time += 1000
        assertTrue(link.accept(linkToken, 200, org.json.JSONObject().put("isArrived", true), car.time))
        notifications.put(notificationGeneration, "navigation", replan, car.time + 1)
        car.time += 1
        assertArrayEquals(byteArrayOf(2), field(send(0x4154), 1))
    }

    @Test fun communicationsReplyDoesNotClaimUnimplementedPhoneControlsOrInventSimState() {
        val car = Car()
        car.ready()
        val request = (0..18).fold(byteArrayOf()) { body, id -> body + Iap2Csm.param(id, byteArrayOf()) }
        val reply = car.message(0x4157, request).single()
        assertEquals(0x4158, reply.messageId)
        val params = Iap2Csm.parseParams(reply.body)
        // CatPlay iap2.rs CommunicationsUpdate: carrier requires cellular support;
        // 0/1/2 are optional live modem state, 18 is not a defined parameter.
        assertEquals(setOf(5, 6, 7, 8, 12, 13, 14, 15, 16, 17), params.map { it.id }.toSet())
        params.forEach { assertArrayEquals(byteArrayOf(0), it.value) }
    }

    @Test fun allZeroWifiParametersSendSevenGreetingsBeforeNextPeerAck() {
        val car = Car(window = 5)
        car.ready(vehicleMessages + vehicleComponent(3))
        assertEquals(listOf(0xaa05, 0x4e0c, 0x5702, 0xa100, 0x4e0a, 0x4e09, 0x4e0b),
            car.authenticationOutput.map { it.messageId })
        assertTrue(car.protocol.diagnosticSummary.contains("pending=0 outstanding=7 "))
        assertTrue(car.feed(packet(0x40, car.sequence, car.ack)).isEmpty())
        assertTrue(car.protocol.diagnosticSummary.contains("pending=0 outstanding=0 "))
    }

    @Test fun anyNonzeroWifiAckOrRetryParameterKeepsExcessGreetingsQueuedUntilAck() {
        val offers = listOf<(Iap2LinkEngine.SynchronizationPayload) -> Iap2LinkEngine.SynchronizationPayload>(
            { it.copy(retransmissionTimeoutMillis = 1500) },
            { it.copy(acknowledgementTimeoutMillis = 73) },
            { it.copy(maxRetransmissions = 5) },
            { it.copy(maxAcknowledgements = 3) },
        )
        for (offer in offers) {
            val car = Car(window = 5)
            car.start(offer)
            car.message(0x1d01, vehicleMessages + vehicleComponent(3))
            car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1)))
            val greetings = car.message(0xaa03, Iap2Csm.param(0, ByteArray(64)))
            assertEquals(listOf(0xaa05, 0x4e0c, 0x5702, 0xa100, 0x4e0a), greetings.map { it.messageId })
            val released = messages(car.feed(packet(0x40, car.sequence, car.ack)))
            assertEquals(listOf(0x4e09, 0x4e0b), released.map { it.messageId })
        }
    }

    @Test fun readyWaitsForPostAuthenticationGreetingsToLeaveTheLinkBacklog() {
        val car=Car(window=1);car.start { it.copy(acknowledgementTimeoutMillis = 73) };car.message(0x1d01)
        assertEquals(listOf(0xaa00),messages(car.feed(packet(0x40,car.sequence,car.ack))).map { it.messageId })
        assertEquals(0xaa02,car.message(0xaa01,Iap2Csm.param(0,byteArrayOf(1))).single().messageId)
        assertEquals(listOf(0xaa05),car.message(0xaa03,Iap2Csm.param(0,ByteArray(64))).map { it.messageId })
        assertFalse(car.protocol.ready)
        val greetings=listOf(0x4e0c,0x5702,0x4e0a,0x4e09,0x4e0b)
        greetings.forEachIndexed { index,id ->
            assertEquals(listOf(id),messages(car.feed(packet(0x40,car.sequence,car.ack))).map { it.messageId })
            assertEquals(index==greetings.lastIndex,car.protocol.ready)
        }
    }

    @Test fun wifiWaitsForRealAckEvenWhenPeerDisablesRetransmission() {
        val car=Car(window=1);car.start { it.copy(acknowledgementTimeoutMillis = 73) }
        val accepted=car.message(0x1d01)
        assertEquals(listOf(0x1d02),accepted.map { it.messageId })
        assertEquals(listOf(16),car.commandSizes.last())
        // Time passing must not fabricate acceptance or retransmit a zero-retry link.
        car.time += 5_000
        assertTrue(car.protocol.advance(car.time).isEmpty())
        val cert=messages(car.feed(packet(0x40,car.sequence,car.ack)))
        assertEquals(listOf(0xaa00),cert.map { it.messageId })
        assertFalse(car.protocol.ready)
    }

    @Test fun wifiRetransmitsAtAgreedDeadlineAndStopsAtAgreedCount() {
        val car = Car()
        car.start { it.copy(retransmissionTimeoutMillis = 37, maxRetransmissions = 1) }
        val sentAt = car.time - 1
        val sequence = car.ack

        assertTrue(car.protocol.advance(sentAt + 36).isEmpty())
        val retries = split(car.protocol.advance(sentAt + 37))
        assertEquals(1, retries.size)
        val retried = retries.single()
        assertEquals(sequence, retried.seq)
        assertEquals(0x1d00, messages(listOf(retried)).single().messageId)
        assertTrue(car.protocol.advance(sentAt + 73).isEmpty())
        assertFalse(car.protocol.failed)
        assertTrue(car.protocol.advance(sentAt + 74).isEmpty())
        assertTrue(car.protocol.failed)
    }

    @Test fun wifiRetryBudgetRunsOnlyAfterEachSocketWriteCompletes() {
        val car = Car(managedWrites = true)
        assertEquals(0x1d00, car.start { it.copy(retransmissionTimeoutMillis = 37, maxRetransmissions = 1) }.single().messageId)
        val first = car.protocol.takeSocketWrites().single()
        assertFalse(first.cancellable)
        assertTrue(car.protocol.advance(500).isEmpty())
        car.protocol.socketWriteFinished(first.ticket, 500)
        assertTrue(car.protocol.advance(536).isEmpty())
        assertEquals(0x1d00, messages(split(car.protocol.advance(537))).single().messageId)
        val retry = car.protocol.takeSocketWrites().single()
        assertTrue(car.protocol.advance(900).isEmpty())
        assertFalse(car.protocol.failed)
        car.protocol.socketWriteFinished(retry.ticket, 900)
        assertTrue(car.protocol.advance(936).isEmpty())
        car.protocol.advance(937)
        assertTrue(car.protocol.failed)
    }

    @Test fun cumulativeAckWithdrawsAnUnwrittenRetryAndWithdrawalDoesNotUseTheBudget() {
        val car = Car(managedWrites = true)
        car.start { it.copy(retransmissionTimeoutMillis = 37, maxRetransmissions = 1) }
        val transmitted = car.protocol.takeSocketWrites().single()
        car.protocol.socketWriteFinished(transmitted.ticket, 500)
        car.protocol.advance(537)
        val withdrawn = car.protocol.takeSocketWrites().single()
        assertTrue(withdrawn.cancellable)
        car.protocol.socketWriteFinished(withdrawn.ticket, null)
        car.protocol.advance(538)
        val queuedRetry = car.protocol.takeSocketWrites().single()
        assertFalse(car.protocol.failed)
        car.protocol.ingest(packet(0x40, car.sequence, car.ack), 539)
        assertEquals(listOf(0L to queuedRetry.ticket), car.cancelledWrites)
    }

    @Test fun queuedAckIsAppliedBeforeTheFinalRetryDeadline() {
        val car = Car()
        car.start { it.copy(retransmissionTimeoutMillis = 37, maxRetransmissions = 1) }
        val sentAt = car.time - 1
        assertEquals(0x1d00, messages(split(car.protocol.advance(sentAt + 37))).single().messageId)

        val deadline = sentAt + 74
        val duplicate = packet(0x40, car.sequence, car.ack - 1)
        val confirmation = packet(0x40, car.sequence, car.ack)
        car.protocol.ingest(duplicate, deadline)
        assertTrue(car.protocol.retryDeadlineReached(deadline))
        car.protocol.advance(deadline, allowRetryExpiry = false)
        assertFalse(car.protocol.failed)
        car.protocol.ingest(confirmation, deadline)
        assertFalse(car.protocol.retryDeadlineReached(deadline))
        car.protocol.advance(deadline)
        assertFalse(car.protocol.failed)
    }

    @Test fun fragmentedDestinationOnlyAcceptsItsResultAfterTheLastFragmentIsWritten() {
        val car = Car(packetLength = 64, managedWrites = true).apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da)
        val target = car.protocol.destinationTarget!!
        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB", name = "A".repeat(500))
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
            destinationToShare = target.authorize(destination))
        val first = car.feed(packet(0x40, car.sequence, car.ack)).single()
        val id = Iap2Csm.parseParams(first.body.copyOfRange(6, first.body.size)).first()
        val firstWrite = car.protocol.takeSocketWrites().single()
        car.protocol.socketWriteFinished(firstWrite.ticket, car.time)
        val fragments = mutableListOf(first)
        fragments += car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession,
            Iap2Csm.encode(0x10dd, Iap2Csm.param(0, id.value))))
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        fragments += car.feed(packet(0x40, car.sequence, car.ack))
        assertEquals(0x10db, messages(fragments).single().messageId)
        val finalWrite = car.protocol.takeSocketWrites().single()
        assertFalse(finalWrite.cancellable)
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        car.protocol.socketWriteFinished(finalWrite.ticket, car.time)
        assertTrue(car.protocol.hasWrittenDestinationOffer)
        car.protocol.advance(car.time, allowDestinationConfirmation = false)
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        car.protocol.advance(car.time)
        assertEquals(1, car.logs.count { "DESTINATION: result accepted" in it })

    }

    @Test fun aStartedDestinationAndRequiredReplyDrainAcrossAFragmentedStop() {
        val car = Car(packetLength = 64, profileId = "bmw_id85", managedWrites = true).apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da, Iap2Csm.param(0, byteArrayOf()))
        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB",
            name = "A".repeat(500), sourceName = "Map app")
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
            destinationToShare = car.protocol.destinationTarget!!.authorize(destination))
        val first = car.feed(packet(0x40, car.sequence, car.ack)).single()
        val id = Iap2Csm.parseParams(first.body.copyOfRange(6, first.body.size)).first()
        car.protocol.socketWriteFinished(car.protocol.takeSocketWrites().single().ticket, car.time)

        car.nativeCapacity = 2
        car.allowBulkStart = false
        val stop = Iap2Csm.encode(0x10dc)
        val duringStop = car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession, stop.copyOfRange(0, 4)))
        assertTrue(duringStop.isNotEmpty())
        val output = (listOf(first) + duringStop).toMutableList()
        output += car.feed(packet(0x40, ++car.sequence, car.ack, car.controlSession,
            stop.copyOfRange(4, stop.size) + Iap2Csm.encode(0x4154)))
        repeat(12) { output += car.feed(packet(0x40, car.sequence, car.ack)) }
        val messages = messages(output)
        assertEquals(listOf(0x10db, 0x4155), messages.map { it.messageId })
        assertEquals("Map app", Iap2Csm.parseParams(messages.first().body).single { it.id == 7 }.asStringOrNull())
        assertTrue(car.cancelledWrites.isEmpty())
        car.protocol.takeSocketWrites().forEach { car.protocol.socketWriteFinished(it.ticket, car.time) }
        car.message(0x10dd, Iap2Csm.param(0, id.value))
        assertTrue(car.logs.none { "DESTINATION: result" in it })
    }

    @Test fun destinationResultWaitsForWriteConfirmationAndStillChecksTheCurrentAuthorization() {
        val car = Car(managedWrites = true).apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da)
        val target = car.protocol.destinationTarget!!
        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB")
        fun sendOffer(): Pair<Iap2Csm.Param, Long> {
            car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
                destinationToShare = target.authorize(destination))
            val frame = car.message(0x1234).single()
            return Iap2Csm.parseParams(frame.body).first() to car.protocol.takeSocketWrites().single().ticket
        }
        val (oldId, oldTicket) = sendOffer()
        car.message(0x10dd, Iap2Csm.param(0, oldId.value))
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        target.authorize(destination)
        assertEquals(listOf(0L to oldTicket), car.cancelledWrites)
        car.protocol.socketWriteFinished(oldTicket, car.time)
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        val (newId, newTicket) = sendOffer()
        car.message(0x10dd, Iap2Csm.param(0, oldId.value))
        car.message(0x10dd, Iap2Csm.param(0, newId.value))
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        car.protocol.socketWriteFinished(newTicket, car.time)
        car.protocol.advance(car.time)
        assertEquals(1, car.logs.count { "DESTINATION: result accepted" in it })

        val (revokedId, revokedTicket) = sendOffer()
        car.message(0x10dd, Iap2Csm.param(0, revokedId.value))
        car.protocol.socketWriteFinished(revokedTicket, car.time)
        assertTrue(car.protocol.hasWrittenDestinationOffer)
        car.protocol.ingest(packet(0x40, ++car.sequence, car.ack, car.controlSession, Iap2Csm.encode(0x10dc)), car.time)
        car.protocol.advance(car.time)
        assertEquals(1, car.logs.count { "DESTINATION: result accepted" in it })
    }

    @Test fun stopBeforeWriteFeedbackCannotReuseAnAuthorizationWhoseFirstBytesWereWritten() {
        val car = Car(profileId = "bmw_id85", managedWrites = true).apply { navigationAllowed = true }
        car.ready()
        car.message(0x10da)
        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB")
        val target = car.protocol.destinationTarget!!
        car.navigationCue = io.github.hypercar.navigation.NavigationCue("", null, null,
            destinationToShare = target.authorize(destination))
        val oldOffer = car.message(0x1234).single { it.messageId == 0x10db }
        val oldId = Iap2Csm.parseParams(oldOffer.body).first()
        val oldWrite = car.protocol.takeSocketWrites().single()
        val writtenAt = car.time
        assertTrue(car.message(0x10dc).isEmpty())
        assertEquals(listOf(0L to oldWrite.ticket), car.cancelledWrites)
        car.protocol.socketWriteFinished(oldWrite.ticket, writtenAt)
        assertTrue(car.message(0x10da).none { it.messageId == 0x10db })

        car.navigationCue = car.navigationCue!!.copy(destinationToShare = target.authorize(destination))
        val newOffer = car.message(0x1234).single { it.messageId == 0x10db }
        val newId = Iap2Csm.parseParams(newOffer.body).first()
        assertNotEquals(oldId, newId)
        val newWrite = car.protocol.takeSocketWrites().single()
        car.protocol.socketWriteFinished(newWrite.ticket, car.time)
        car.message(0x10dd, Iap2Csm.param(0, oldId.value))
        assertTrue(car.logs.none { "DESTINATION: result" in it })
        car.message(0x10dd, Iap2Csm.param(0, newId.value))
        assertEquals(1, car.logs.count { "DESTINATION: result accepted" in it })
    }

    @Test fun retiringTheTransportRevokesTheTargetAndItsAlreadyWrittenOffer() {
        val logs = mutableListOf<String>()
        val session = Iap2DestinationSession(logs::add)
        val target = session.target
        val destination = io.github.hypercar.navigation.NavigationDestination("A", 1.0, 2.0, "en_GB")
        session.refreshSource(target.authorize(destination))
        session.handle(Iap2Csm.Frame(0x10da, byteArrayOf()))
        val offer = session.offer()!!
        assertTrue(offer.commitStart!!.invoke())
        offer.onWritten!!.invoke()
        val id = Iap2Csm.parseParams(offer.frame.body).first().value
        session.handle(Iap2Csm.Frame(0x10dd, Iap2Csm.param(0, id)))
        session.retireTransport()
        session.confirmWrittenOffer()
        assertTrue(logs.none { "DESTINATION: result" in it })
        assertNull(target.authorize(destination))
        session.reset()
        assertNull(session.target.authorize(destination))
    }

    @Test fun wifiTimerKeepsEarliestDeadlineWhenAnotherPacketIsSent() {
        val car = Car()
        car.start { it.copy(retransmissionTimeoutMillis = 50, maxRetransmissions = 2) }
        val firstSentAt = car.time - 1
        car.time += 20
        car.feed(packet(0x40, ++car.sequence, car.ack - 1, car.controlSession, Iap2Csm.encode(0x1d01)))

        assertTrue(car.protocol.advance(firstSentAt + 49).isEmpty())
        val retries = split(car.protocol.advance(firstSentAt + 50))
        assertEquals(listOf(0x1d00), messages(retries).map { it.messageId })
    }

    @Test fun activeLinkDoesNotRetryBeyondItsAdvertisedProposal() {
        val engine = Iap2LinkEngine(retransmissionTimeoutMillis = 40, maxRetransmissions = 1, reliableTransport = true)
        engine.start(0)
        val ownSyn = split(engine.takeOutput().drop(6).toByteArray()).single()
        val peer = Iap2LinkEngine.SynchronizationPayload(4, 2048, 80, 30, 2, 3,
            listOf(Iap2LinkEngine.SessionDescriptor(1, 0, 2)))
        engine.feed(packet(0xc0, 41, ownSyn.seq, body = peer.encode()), 1)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        assertTrue(engine.sendControl(Iap2Csm.encode(0x1d00), 2))
        val first = engine.takeOutput()

        engine.advanceTime(42)
        assertArrayEquals(first, engine.takeOutput())
        engine.advanceTime(82)
        assertTrue(engine.isDead)
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
        assertTrue(car.protocol.ready) // All-zero Wi-Fi parameters allow the extra request past the window.
        assertTrue(messages(car.feed(packet(0x40, car.sequence, car.ack))).isEmpty())
        assertTrue(car.protocol.ready)
    }

    @Test fun zeroRetryAndAckWifiDefersConfirmationToNextBusinessReply() {
        val car = Car(); car.ready()
        for (id in listOf(0x5200, 0x10da, 0x1234)) {
            val request = packet(0x40, ++car.sequence, car.ack, 1, Iap2Csm.encode(id))
            assertTrue(car.feed(request).isEmpty())
            assertTrue(car.feed(request).isEmpty())
        }
        assertTrue(car.protocol.advance(car.time + 1000).isEmpty())
        val output = car.feed(packet(0x40, ++car.sequence, car.ack, 1, Iap2Csm.encode(0x5000)))
        assertEquals(listOf(0x5001), messages(output).map { it.messageId })
        assertEquals(listOf(1, 2), output.map { it.session }) // 5001 then empty-file SETUP, no standalone ACK.
        assertTrue(output.all { it.ack == car.sequence })
    }

    @Test fun subscribedUpdatesContinuePastEightUnacknowledgedFramesOnZeroAckWifi() {
        val car = Car(window = 5); car.ready()
        car.nowPlaying = Iap2NowPlaying(title = "initial")
        assertEquals(listOf(0x5001), car.message(0x5000).map { it.messageId })
        // No incoming link ACK or other peer packet while the source keeps changing.
        repeat(260) { index ->
            car.nowPlaying = Iap2NowPlaying(title = "track-$index")
            val output = split(car.protocol.advance(car.time + (index + 1) * 1000L))
            assertEquals("update $index", listOf(0x5001), messages(output).map { it.messageId })
            assertTrue(output.all { it.ack == car.sequence })
        }
        assertFalse(car.protocol.failed)
    }

    @Test fun anyNonzeroWifiAckOrRetryParameterStillAcknowledgesSilentRequestsAndDuplicates() {
        val configurations: List<(Iap2LinkEngine.SynchronizationPayload) -> Iap2LinkEngine.SynchronizationPayload> = listOf(
            { it.copy(retransmissionTimeoutMillis = 4000) },
            { it.copy(acknowledgementTimeoutMillis = 73) },
            { it.copy(maxRetransmissions = 1) },
            { it.copy(maxAcknowledgements = 1) },
        )
        for (configure in configurations) {
            val car = Car(); car.start(configure)
            car.message(0x1d01)
            val request = packet(0x40, ++car.sequence, car.ack, 1, Iap2Csm.encode(0xae00))
            repeat(2) {
                val ack = car.feed(request).single()
                assertEquals(0, ack.session)
                assertTrue(ack.body.isEmpty())
                assertEquals(car.sequence, ack.ack)
            }
            assertFalse(car.protocol.ready)
        }
    }

    @Test fun fullWifiSendWindowStillFlushesReceiveAckWithoutSendingQueuedCertificate() {
        val car = Car(window=1); car.start { it.copy(acknowledgementTimeoutMillis = 73) }
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

    @Test fun wifiDeviceUuidIsRandomAndSharedAcrossRetryInstances() {
        val a=Car();a.ready();val b=Car();b.ready()
        fun device(car: Car) = Iap2Csm.parseParams(car.authenticationOutput.single { it.messageId==0x4e0c }.body).single().asStringOrNull()!!
        val id=device(a)
        assertEquals(id,device(b))
        assertEquals(Iap2DeviceUuid.value,id)
        assertEquals(4,java.util.UUID.fromString(id).version())
        assertNotEquals(java.util.UUID.nameUUIDFromBytes("test-device".toByteArray()).toString().uppercase(),id)
    }

    @Test fun wifiListReplyUsesBoxFieldsThroughAuthenticatedLink() {
        val car = Car(); car.ready()
        val request = byteArrayOf(0,4,0,1) // request field 1 only, independent wire fixture
        val reply = car.message(0x4170, request).single()
        assertEquals(0x4171, reply.messageId)
        assertArrayEquals(byteArrayOf(0,5,0,0,0), reply.body)
        val other = car.message(0x4170, byteArrayOf(0,4,0,6)).single()
        assertArrayEquals(byteArrayOf(0,5,0,5,0), other.body)
        assertTrue(car.protocol.ready)
    }

    @Test fun wifiCommunicationsReplyFiltersRequestedFieldsThroughAuthenticatedLink() {
        val car = Car(); car.ready()
        val reply = car.message(0x4157, byteArrayOf(0,4,0,8,0,4,0,5,0,4,0,4)).single()
        assertEquals(0x4158, reply.messageId)
        assertArrayEquals(byteArrayOf(0,5,0,5,0,0,5,0,8,0), reply.body)
        assertTrue(car.message(0x4159).isEmpty())
        assertTrue(car.protocol.ready)
    }

    @Test fun boxWifiSequenceUsesNegotiatedControlSessionAndRespondsToSubscriptions() {
        val car = Car()
        car.ready()
        val power = car.message(0xae00)
        assertEquals(listOf(0xae01), power.map { it.messageId })
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
            assertEquals(if (cancel) 0 else 1, result.count { it.messageId == 0xae01 })
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

        val emptySignature = Iap2Csm.param(0, byteArrayOf())
        val truncatedSignature = Iap2Csm.param(0, byteArrayOf(1)).copyOf(4)
        for (signature in listOf(emptySignature, truncatedSignature)) {
            val car = Car()
            car.start()
            car.message(0x1d01)
            car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1)))
            assertTrue(car.message(0xaa03, signature).isEmpty())
            assertTrue(car.protocol.failed)
            assertFalse(car.protocol.ready)
            assertTrue(car.protocol.diagnosticSummary.contains("aa03_rx=true"))
            assertTrue(car.logs.contains("FAILED invalid challenge response parameter"))
        }
    }

    @Test fun splitAndRepeatedDetectionMarkersAreAnsweredWithoutCompetingSyn() {
        val p = WifiIap2Protocol("test", {})
        assertTrue(p.feed(Iap2LinkEngine.MARKER.copyOfRange(0, 2), 1).isEmpty())
        assertArrayEquals(Iap2LinkEngine.MARKER, p.feed(Iap2LinkEngine.MARKER.copyOfRange(2, 6), 2))
        assertArrayEquals(Iap2LinkEngine.MARKER, p.feed(Iap2LinkEngine.MARKER, 1000))
        assertTrue(p.advance(2000).isEmpty())
    }
}
