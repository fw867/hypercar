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

    private inner class Car {
        val logs = mutableListOf<String>()
        val protocol = WifiIap2Protocol("test-device", { logs += it }, { ByteArray(32) { 7 } })
        var sequence = 86
        var ack = 0
        var time = 1L
        fun feed(bytes: ByteArray): List<Wire> = split(protocol.feed(bytes, time++)).also { out -> out.lastOrNull()?.let { ack = it.seq } }
        fun start(): List<Iap2Csm.Frame> {
            assertArrayEquals(Iap2LinkEngine.MARKER, protocol.feed(Iap2LinkEngine.MARKER, time++))
            val sync = Iap2LinkEngine.SynchronizationPayload(5, 65535, 0, 0, 0, 0,
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
