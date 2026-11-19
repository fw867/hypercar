package io.github.hypercar.iap2

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

    private inner class Car(val window: Int = 32, val controlSession: Int = 1, val deviceName: String = "HyperCar", val packetLength:Int = 65535, profileId: String = "") {
        val logs = mutableListOf<String>()
        val mediaKeys = mutableListOf<Int>()
        var authenticationOutput = emptyList<Iap2Csm.Frame>()
        var nowPlaying = Iap2NowPlaying()
        val vehicleUpdates = mutableListOf<Iap2VehicleStatus>()
        var navigationAllowed = false
        var navigationCue: io.github.hypercar.navigation.NavigationCue? = null
        val protocol = WifiIap2Protocol("test-device", { logs += it }, { size -> ByteArray(size) { 7 } },
            navigationEnabled = { navigationAllowed }, navigationCue = { navigationCue }, deviceName = deviceName, onMediaKey = mediaKeys::add,
            nowPlayingProvider = { nowPlaying }, onVehicleStatus = vehicleUpdates::add, profileId = profileId)
        var sequence = 86
        var ack = 0
        var time = 1L
        val commandSizes = mutableListOf<List<Int>>()
        fun feed(bytes: ByteArray): List<Wire> {
            val commands = WifiIap2Framing.split(protocol.feed(bytes,time++))
            commandSizes += commands.map { it.size }
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
                greetings.filterNot { it.messageId == 0x5201 || it.messageId == 0xa100 }.map { it.messageId })
            val name = Iap2Csm.parseParams(greetings.single { it.messageId == 0x4e09 }.body).single()
            assertEquals(deviceName, name.asStringOrNull())
            assertFalse(protocol.failed)
        }
    }

    @Test fun audiMib3ChallengeUses20BytesAndSeparatelyTracksAckAndResponse() {
        val car = Car(profileId = "audi_mib3")
        car.start()
        car.message(0x1d01)
        val previousAck = car.ack
        val challenge = car.message(0xaa01, Iap2Csm.param(0, byteArrayOf(1))).single()
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

    @Test fun otherWifiProfilesRetain32ByteChallenge() {
        for (profile in listOf("", "bmw_id85", "bmw_id9", "mercedes_mbux", "volkswagen_mib")) {
            Car(profileId = profile).ready()
        }
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
