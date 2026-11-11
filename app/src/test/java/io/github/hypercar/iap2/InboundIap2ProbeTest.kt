package io.github.hypercar.iap2

import org.junit.Assert.assertEquals
import org.junit.Test

class InboundIap2ProbeTest {
    @Test fun cafeInboundStaysSilentUntilCarProbeThenReachesLinkReady() {
        val engine = Iap2LinkEngine()
        engine.start(1, passive = true)
        assertEquals(0, engine.takeOutput().size)
        engine.advanceTime(2_000)
        assertEquals(0, engine.takeOutput().size)

        engine.feed(Iap2LinkEngine.MARKER, 3)
        val probeReply = engine.takeOutput()
        assertEquals(Iap2LinkEngine.MARKER.asList(), probeReply.asList())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, engine.state)

        val sync = Iap2LinkEngine.SynchronizationPayload(
            maxOutgoing = 8,
            maxLength = 65535,
            retransmissionTimeoutMillis = 1000,
            acknowledgementTimeoutMillis = 1000,
            maxRetransmissions = 4,
            maxAcknowledgements = 3,
            sessions = listOf(Iap2LinkEngine.SessionDescriptor(10, 0, 2)),
        ).encode()
        engine.feed(packet(0x80, sequence = 5, acknowledgement = 0, body = sync), 4)
        val synAck = frames(engine.takeOutput()).single()
        assertEquals(0xC0, synAck.control)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, engine.state)

        engine.feed(packet(0x40, sequence = 5, acknowledgement = synAck.sequence), 5)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
    }

    private data class Frame(val control: Int, val sequence: Int)

    private fun packet(
        control: Int,
        sequence: Int,
        acknowledgement: Int,
        body: ByteArray = byteArrayOf(),
    ): ByteArray {
        val length = 9 + if (body.isEmpty()) 0 else body.size + 1
        val header = byteArrayOf(
            0xff.toByte(), 0x5a,
            (length shr 8).toByte(), length.toByte(),
            control.toByte(), sequence.toByte(), acknowledgement.toByte(), 0, 0,
        )
        header[8] = (-header.sumOf { it.toInt() and 255 }).toByte()
        return if (body.isEmpty()) header else header + body + byteArrayOf((-body.sumOf { it.toInt() and 255 }).toByte())
    }

    private fun frames(bytes: ByteArray): List<Frame> {
        val frames = mutableListOf<Frame>()
        var offset = 0
        while (offset + 9 <= bytes.size) {
            val length = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            require(length >= 9 && offset + length <= bytes.size)
            frames += Frame(bytes[offset + 4].toInt() and 255, bytes[offset + 5].toInt() and 255)
            offset += length
        }
        return frames
    }
}
