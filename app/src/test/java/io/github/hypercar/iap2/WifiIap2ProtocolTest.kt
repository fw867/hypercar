package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class WifiIap2ProtocolTest {
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
    private fun messages(frames: List<Wire>) = frames.filter { it.session != 0 }.flatMap { Iap2Csm.Parser().offer(it.body) }

    private inner class Car(val window: Int = 32) {
        val logs = mutableListOf<String>()
        val protocol = WifiIap2Protocol("test-device", { logs += it }, { ByteArray(32) { 7 } })
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
            val sync = Iap2LinkEngine.SynchronizationPayload(window, 65535, 0, 0, 0, 0,
                listOf(Iap2LinkEngine.SessionDescriptor(1, 0, 2), Iap2LinkEngine.SessionDescriptor(2, 1, 2))).encode()
            val syn = packet(0x80, sequence, 0, body = sync)
            assertTrue(feed(syn.copyOfRange(0, 6)).isEmpty())
            val answer = feed(syn.copyOfRange(6, syn.size)).single()
            assertEquals(0xc0, answer.control)
            assertEquals(32, answer.body[1].toInt())
            return messages(feed(packet(0x40, sequence, ack)))
        }
        fun message(id: Int, body: ByteArray = byteArrayOf()): List<Iap2Csm.Frame> =
            messages(feed(packet(0x40, ++sequence, ack, 1, Iap2Csm.encode(id, body))))
        fun ready() {
            assertEquals(listOf(0x1d00), start().map { it.messageId })
            val identification = message(0x1d01)
            assertEquals(listOf(0x1d02, 0xaa00), identification.map { it.messageId })
            assertTrue(identification.last().body.isEmpty()) // CatPlay default: request certificate, not serial.
            assertEquals(listOf(0xaa00), message(0xaa06).map { it.messageId })
            val challenge = message(0xaa01, Iap2Csm.param(0, byteArrayOf(1, 2, 3))).single()
            assertEquals(0xaa02, challenge.messageId)
            assertEquals(32, Iap2Csm.parseParams(challenge.body).single().value.size)
            assertEquals(listOf(0xaa05, 0x4e0c, 0xa100, 0x4e0a, 0x4e09, 0x4e0b),
                message(0xaa03, Iap2Csm.param(0, ByteArray(64) { 9 })).map { it.messageId })
            assertFalse(protocol.failed)
        }
    }

    @Test fun readyWaitsForPostAuthenticationGreetingsToLeaveTheLinkBacklog() {
        val car=Car(window=1);car.start();car.message(0x1d01)
        assertEquals(listOf(0xaa00),messages(car.feed(packet(0x40,car.sequence,car.ack))).map { it.messageId })
        assertEquals(0xaa02,car.message(0xaa01,Iap2Csm.param(0,byteArrayOf(1))).single().messageId)
        assertEquals(listOf(0xaa05),car.message(0xaa03,Iap2Csm.param(0,ByteArray(64))).map { it.messageId })
        assertFalse(car.protocol.ready)
        val greetings=listOf(0x4e0c,0xa100,0x4e0a,0x4e09,0x4e0b)
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
        assertFalse(car.protocol.ready) // Five-packet peer window leaves the last greeting queued.
        assertEquals(listOf(0x4e0b), messages(car.feed(packet(0x40, car.sequence, car.ack))).map { it.messageId })
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

    @Test fun splitAndRepeatedDetectionMarkersAreAnsweredWithoutCompetingSyn() {
        val p = WifiIap2Protocol("test", {})
        assertTrue(p.feed(Iap2LinkEngine.MARKER.copyOfRange(0, 2), 1).isEmpty())
        assertArrayEquals(Iap2LinkEngine.MARKER, p.feed(Iap2LinkEngine.MARKER.copyOfRange(2, 6), 2))
        assertArrayEquals(Iap2LinkEngine.MARKER, p.feed(Iap2LinkEngine.MARKER, 1000))
        assertTrue(p.advance(2000).isEmpty())
    }
}
