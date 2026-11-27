package io.github.hypercar.yadea

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime

internal data class YadeaDisplay(
    val width: Int,
    val height: Int,
    val fps: Int,
    val negotiated: Boolean,
    val imageFormat: Int? = null,
    val capabilities: Int? = null,
)

/** Wire layout follows toupin's YadeaProtocol and the base.apk-compatible FrameSender. */
internal object YadeaWire {
    const val START_RESPONSE = 0x20
    const val END_RESPONSE = 0x23
    const val NEGATIVE_RESPONSE = 0xF1

    private const val CHUNK_BYTES = 1434
    private const val IMAGE_HEADER_BYTES = 20
    private const val FRAME_HEADER_BYTES = 12
    private const val FRAME_OVERHEAD_BYTES = 15
    private const val LENGTH_OVERHEAD_BYTES = 8
    private const val MAX_FRAME_BYTES = 1_048_576 + 7
    private const val MAGIC_Y = 0x59
    private const val MAGIC_D = 0x44
    private const val REQUEST_MARKER = 0x3C
    private const val RESPONSE_MARKER = 0x4C
    private const val TAIL_K = 0x4B
    private const val TAIL_J = 0x4A

    val fallbackDisplay = YadeaDisplay(800, 480, 10, negotiated = false)

    fun startup(now: LocalDateTime): ByteArray = heartbeat(now) + encode(0x10, 8) {
        putShort(CHUNK_BYTES.toShort())
        put(0xF0.toByte())
        put(0xFF.toByte())
        putInt(CHUNK_BYTES)
    }

    fun heartbeat(now: LocalDateTime): ByteArray = encode(0x1F, 8) {
        put((now.year % 100).toByte())
        put(now.monthValue.toByte())
        put(now.dayOfMonth.toByte())
        put(now.hour.toByte())
        put(now.minute.toByte())
        put(now.second.toByte())
        putShort((-1).toShort())
    }

    fun end(): ByteArray = encode(0x13, 8) {
        put(2.toByte())
        repeat(7) { put(0xFF.toByte()) }
    }

    fun display(payload: ByteArray): YadeaDisplay? {
        if (payload.size < 8) return null
        val dimensions = ByteBuffer.wrap(payload)
        val width = dimensions.short.toInt() and 0xFFFF
        val height = dimensions.short.toInt() and 0xFFFF
        val fps = payload[7].unsigned()
        return YadeaDisplay(
            width.takeIf { it in 320..4096 } ?: fallbackDisplay.width,
            height.takeIf { it in 160..2160 } ?: fallbackDisplay.height,
            fps.takeIf { it in 1..30 } ?: fallbackDisplay.fps,
            negotiated = width in 320..4096 && height in 160..2160 && fps in 1..30,
            imageFormat = payload[4].unsigned(),
            capabilities = payload[6].unsigned(),
        )
    }

    fun forEachImageDatagram(jpeg: ByteArray, sequence: Int, capturedAtMs: Long, send: (ByteArray) -> Unit) {
        val crc = imageCrc(jpeg)
        var offset = 0
        while (offset < jpeg.size) {
            val count = minOf(CHUNK_BYTES, jpeg.size - offset)
            val datagram = encode(0x12, IMAGE_HEADER_BYTES + count) {
                put(crc.toByte())
                put((crc ushr 8).toByte())
                putShort(count.toShort())
                putInt(sequence)
                putInt(jpeg.size)
                putInt(offset)
                putInt(capturedAtMs.toInt())
                put(jpeg, offset, count)
            }
            send(datagram)
            offset += count
        }
    }

    private fun imageCrc(jpeg: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in jpeg) {
            crc = crc xor byte.unsigned()
            repeat(8) {
                crc = (crc ushr 1) xor (if (crc and 1 == 1) 0xA001 else 0)
            }
        }
        return crc
    }

    private inline fun encode(command: Int, payloadBytes: Int, writePayload: ByteBuffer.() -> Unit): ByteArray {
        val frame = ByteBuffer.allocate(FRAME_OVERHEAD_BYTES + payloadBytes)
        frame.put(MAGIC_Y.toByte()).put(MAGIC_D.toByte()).put(REQUEST_MARKER.toByte())
        frame.putInt(payloadBytes + LENGTH_OVERHEAD_BYTES)
        frame.put(command.toByte()).putInt(payloadBytes)
        frame.writePayload()
        val bytes = frame.array()
        var sum = 0
        for (index in 2 until frame.position()) sum += bytes[index].unsigned()
        frame.put(sum.toByte()).put(TAIL_K.toByte()).put(TAIL_J.toByte())
        return bytes
    }

    data class Reply(val command: Int, val payload: ByteArray)

    /** Poll buffered replies before reading again. Partial frames survive socket read timeouts. */
    class ReplyReader {
        private var buffer = ByteArray(4096)
        private var start = 0
        private var end = 0

        fun readFrom(input: InputStream) {
            if (start == end) {
                start = 0
                end = 0
            } else if (end == buffer.size && start > 0) {
                buffer.copyInto(buffer, startIndex = start, endIndex = end)
                end -= start
                start = 0
            }
            if (end == buffer.size) buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_FRAME_BYTES))
            val count = input.read(buffer, end, buffer.size - end)
            if (count < 0) throw EOFException("Yadea instrument closed the TCP connection")
            end += count
        }

        fun poll(): Reply? {
            while (start < end) {
                if (buffer[start].unsigned() != MAGIC_Y) {
                    start++
                    continue
                }
                if (end - start < 3) return null
                val marker = buffer[start + 2].unsigned()
                if (buffer[start + 1].unsigned() != MAGIC_D ||
                    (marker != RESPONSE_MARKER && marker != REQUEST_MARKER)
                ) {
                    start++
                    continue
                }
                if (end - start < FRAME_HEADER_BYTES) return null
                val outerLength = buffer.intAt(start + 3)
                val payloadBytes = buffer.intAt(start + 8)
                if (outerLength !in LENGTH_OVERHEAD_BYTES..(MAX_FRAME_BYTES - 7) ||
                    payloadBytes != outerLength - LENGTH_OVERHEAD_BYTES
                ) {
                    start++
                    continue
                }
                val frameBytes = FRAME_OVERHEAD_BYTES + payloadBytes
                if (end - start < frameBytes) return null
                val next = start + frameBytes
                if (buffer[next - 2].unsigned() != TAIL_K || buffer[next - 1].unsigned() != TAIL_J) {
                    start++
                    continue
                }
                // The reference receiver accepts replies even if their SUM8 differs.
                val reply = Reply(buffer[start + 7].unsigned(), buffer.copyOfRange(start + FRAME_HEADER_BYTES, next - 3))
                start = next
                return reply
            }
            return null
        }

        private fun ByteArray.intAt(offset: Int): Int =
            (this[offset].unsigned() shl 24) or (this[offset + 1].unsigned() shl 16) or
                (this[offset + 2].unsigned() shl 8) or this[offset + 3].unsigned()
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
