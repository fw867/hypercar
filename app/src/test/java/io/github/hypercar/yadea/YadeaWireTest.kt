package io.github.hypercar.yadea

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime

class YadeaWireTest {
    @Test
    fun encodesStartupAndEndFrames() {
        val heartbeat = hex("59 44 3C 00 00 00 10 1F 00 00 00 08 1A 0B 1B 04 05 06 FF FF C0 4B 4A")
        val start = hex("59 44 3C 00 00 00 10 10 00 00 00 08 05 9A F0 FF 00 00 05 9A 91 4B 4A")
        val end = hex("59 44 3C 00 00 00 10 13 00 00 00 08 02 FF FF FF FF FF FF FF 62 4B 4A")
        assertArrayEquals(heartbeat + start, YadeaWire.startup(LocalDateTime.of(2026, 11, 27, 4, 5, 6)))
        assertArrayEquals(end, YadeaWire.end())
    }

    @Test
    fun acceptsSplitAndCoalescedRepliesAndToleratesPeerChecksumMismatch() {
        val reader = YadeaWire.ReplyReader()
        reader.readFrom(hex("00 59 00 59").inputStream())
        assertNull(reader.poll())

        // The real SUM8 for the 0x20 reply is F1. This fixture uses 00 to check compatibility.
        val response = hex("44 4C 00 00 00 10 20 00 00 00 08 04 00 02 58 00 00 F6 19 00 4B 4A")
        val end = hex("59 44 4C 00 00 00 08 23 00 00 00 00 77 4B 4A")
        reader.readFrom(ByteArrayInputStream(response, 0, 13))
        assertNull(reader.poll())
        reader.readFrom((response.copyOfRange(13, response.size) + end).inputStream())

        val startReply = requireNotNull(reader.poll())
        assertEquals(YadeaWire.START_RESPONSE, startReply.command)
        assertEquals(YadeaDisplay(1024, 600, 25, true, imageFormat = 0, capabilities = 0xF6), YadeaWire.display(startReply.payload))
        assertEquals(YadeaWire.END_RESPONSE, reader.poll()?.command)
        assertNull(reader.poll())
    }

    @Test
    fun distinguishesMissingDisplayReplyFromPerFieldFallbacks() {
        val fallback = YadeaDisplay(800, 480, 10, false)
        assertNull(YadeaWire.display(ByteArray(7)))
        assertEquals(fallback, YadeaWire.fallbackDisplay)
        assertEquals(fallback.copy(imageFormat = 0, capabilities = 0), YadeaWire.display(ByteArray(8)))
    }

    @Test
    fun encodesImageCrcAndTimestampInTheirWireByteOrders() {
        val datagrams = mutableListOf<ByteArray>()
        YadeaWire.forEachImageDatagram("123456789".encodeToByteArray(), 1, 0x0123456789L) {
            datagrams.add(it)
        }
        assertArrayEquals(
            hex("59 44 3C 00 00 00 25 12 00 00 00 1D 37 4B 00 09 00 00 00 01 00 00 00 09 " +
                "00 00 00 00 23 45 67 89 31 32 33 34 35 36 37 38 39 5A 4B 4A"),
            datagrams.single(),
        )
    }

    @Test
    fun splitsAt1434BytesAndUsesTheSameImageMetadataInBothPackets() {
        val jpeg = ByteArray(1435) { it.toByte() }
        val datagrams = mutableListOf<ByteArray>()
        YadeaWire.forEachImageDatagram(jpeg, 42, 100L) { datagrams.add(it) }
        assertEquals(listOf(1469, 36), datagrams.map { it.size })

        val first = ByteBuffer.wrap(datagrams[0])
        val last = ByteBuffer.wrap(datagrams[1])
        assertEquals(first.getShort(12), last.getShort(12))
        assertEquals(1434, first.getShort(14).toInt())
        assertEquals(1, last.getShort(14).toInt())
        assertEquals(42, first.getInt(16))
        assertEquals(42, last.getInt(16))
        assertEquals(1435, first.getInt(20))
        assertEquals(1435, last.getInt(20))
        assertEquals(0, first.getInt(24))
        assertEquals(1434, last.getInt(24))
        assertEquals(100, first.getInt(28))
        assertEquals(100, last.getInt(28))
        assertArrayEquals(jpeg, datagrams[0].copyOfRange(32, 1466) + datagrams[1][32])
    }

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
