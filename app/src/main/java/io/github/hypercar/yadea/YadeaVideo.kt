package io.github.hypercar.yadea

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Converts one ordered Annex B video stream to instrument JPEGs and phone previews.
 * Callbacks run on background workers. The preview recipient owns the delivered bitmap.
 * After a source reset, supply the new encoder size before or alongside its first video units.
 */
class YadeaVideo(
    private val width: Int,
    private val height: Int,
    fps: Int,
    private val onPreview: (Bitmap) -> Unit,
    private val onJpeg: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val requestKeyFrame: () -> Unit = {},
) : Closeable {
    init {
        require(width > 0 && height > 0 && fps > 0)
    }

    private val frameIntervalNs = (1_000_000_000L / fps).coerceAtLeast(1L)
    private val jpegQualities = (if (width.toLong() * height > 320 * 240) 72 else 80).let { quality ->
        intArrayOf(quality, quality - 8, 20)
    }
    private val lock = Object()
    private val input = ArrayDeque<AccessUnit>()
    private var queuedBytes = 0
    private var generation = 0L
    private var closed = false
    private var streamCodec: Codec? = null
    private var parameterSets = emptyList<ByteArray?>()
    private var awaitingSync = true
    private var dropRasl = false
    private var keyFramePending = false
    private var lastKeyFrameRequestNs: Long? = null
    private var pendingFrame: DecodedFrame? = null

    @Volatile private var sourceSize: Size? = null
    val sourceWidth: Int get() = sourceSize?.width ?: 0
    val sourceHeight: Int get() = sourceSize?.height ?: 0

    init {
        Thread(::decodeLoop, "Yadea video decoder").apply { isDaemon = true }.start()
        Thread(::outputLoop, "Yadea video output").apply { isDaemon = true }.start()
    }

    /** The dimensions come from the native EncoderResolution event. */
    fun setSourceSize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        val size = Size(width, height)
        synchronized(lock) {
            if (closed || sourceSize == size) return
            if (sourceSize != null) {
                discardChain(clearParameters = true)
                scheduleKeyFrame()
            }
            sourceSize = size
            lock.notifyAll()
        }
    }

    /** Offers one complete access unit. The caller may reuse the array after this returns. */
    fun offerVideo(accessUnit: ByteArray, ptsUs: Long, codec: Int) {
        val format = when (codec) {
            0 -> Codec.AVC
            1 -> Codec.HEVC
            else -> throw IllegalArgumentException("Unsupported Yadea video codec $codec")
        }
        synchronized(lock) {
            if (closed || accessUnit.isEmpty()) return
            if (streamCodec != format) {
                discardChain(clearParameters = true)
                streamCodec = format
            }
            if (accessUnit.size > MAX_ACCESS_UNIT_BYTES) {
                discardChain()
                scheduleKeyFrame()
                return
            }

            val unit = parseAccessUnit(accessUnit.copyOf(), format)
            if (unit == null) {
                discardChain()
                scheduleKeyFrame()
                return
            }
            val updated = unit.parameters.mapIndexed { index, bytes ->
                bytes ?: parameterSets.getOrNull(index)
            }
            val configurationChanged = !awaitingSync && updated.indices.any { index ->
                updated[index]?.contentEquals(parameterSets[index]) == false
            }
            if (configurationChanged) {
                discardChain()
                scheduleKeyFrame()
            }
            parameterSets = updated
            if (unit.pictureType < 0) return

            // HEVC RASL pictures can reference pictures from before the starting IRAP.
            if (dropRasl && unit.pictureType in 8..9) return
            if (input.size >= MAX_QUEUED_UNITS || queuedBytes + unit.bytes.size > MAX_QUEUED_BYTES) {
                // After losing any compressed picture, discard its entire dependency chain.
                discardChain()
            }
            val sync = if (format == Codec.AVC) unit.pictureType == 5 else unit.pictureType in 16..21
            var configuration: List<ByteArray>? = null
            if (awaitingSync) {
                if (!sync || parameterSets.any { it == null }) {
                    scheduleKeyFrame()
                    return
                }
                configuration = parameterSets.filterNotNull()
                awaitingSync = false
                keyFramePending = false
                dropRasl = format == Codec.HEVC
            } else if (dropRasl && unit.pictureType !in 6..7) {
                dropRasl = false
            }

            input.addLast(AccessUnit(format, unit.bytes, unit.pictureSize, ptsUs, sync, configuration))
            queuedBytes += unit.bytes.size
            lock.notifyAll()
        }
    }

    fun reset() {
        synchronized(lock) {
            if (closed) return
            sourceSize = null
            streamCodec = null
            lastKeyFrameRequestNs = null
            discardChain(clearParameters = true)
        }
    }

    /** Signals both workers to release their resources. An in-flight callback may finish. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            discardChain(clearParameters = true)
        }
    }

    // Called under lock. The decoder worker recreates MediaCodec after a discontinuity.
    private fun discardChain(clearParameters: Boolean = false) {
        generation++
        input.clear()
        queuedBytes = 0
        awaitingSync = true
        dropRasl = false
        keyFramePending = false
        if (clearParameters) parameterSets = emptyList()
        pendingFrame?.bitmap?.recycle()
        pendingFrame = null
        lock.notifyAll()
    }

    private fun scheduleKeyFrame() {
        if (!keyFramePending) {
            keyFramePending = true
            lock.notifyAll()
        }
    }

    private fun keyFrameDelayNs(): Long {
        val last = lastKeyFrameRequestNs ?: return 0L
        return (KEY_FRAME_INTERVAL_NS - (System.nanoTime() - last)).coerceAtLeast(0L)
    }

    private fun decodeLoop() {
        var decoder: MediaCodec? = null
        var activeGeneration = -1L
        var nextFrameNs: Long? = null
        var inputBlockedSince: Long? = null
        val converter = YuvConverter()
        val outputInfo = MediaCodec.BufferInfo()
        try {
            while (true) {
                val work = synchronized(lock) {
                    while (!closed && generation == activeGeneration &&
                        (sourceSize == null || input.isEmpty())
                    ) {
                        val delay = if (keyFramePending) keyFrameDelayNs() else null
                        if (delay == 0L) break
                        val waitMs = delay?.let { (it + 999_999L) / 1_000_000L }
                        if (decoder == null) {
                            lock.wait(waitMs ?: 0L)
                        } else {
                            lock.wait(minOf(waitMs ?: DECODER_POLL_MS, DECODER_POLL_MS))
                            break
                        }
                    }
                    if (closed) return
                    val requestSync = keyFramePending && keyFrameDelayNs() == 0L
                    if (requestSync) {
                        keyFramePending = false
                        lastKeyFrameRequestNs = System.nanoTime()
                    }
                    val size = sourceSize
                    DecodeWork(generation, size, if (size == null) null else input.peekFirst(), requestSync)
                }

                if (activeGeneration != work.generation) {
                    releaseDecoder(decoder)
                    decoder = null
                    activeGeneration = work.generation
                    nextFrameNs = null
                    inputBlockedSince = null
                }
                if (work.requestSync && isCurrent(work.generation)) {
                    runCatching(requestKeyFrame).onFailure { reportError(work.generation, it) }
                }
                if (!isCurrent(work.generation)) continue

                try {
                    val unit = work.unit
                    if (decoder == null && unit != null) {
                        decoder = startDecoder(checkNotNull(work.size), unit)
                    }
                    val codec = decoder ?: continue
                    val now = System.nanoTime()
                    val frameDeadline = nextFrameNs ?: now
                    val lag = now - frameDeadline
                    val bitmap = drainDecoder(codec, outputInfo, converter, lag >= 0)
                    if (bitmap != null) {
                        // Skip elapsed slots without shifting the cadence when a frame arrives late.
                        nextFrameNs = now + frameIntervalNs - lag % frameIntervalNs
                        enqueueDecoded(bitmap, work.generation)
                    }
                    if (unit == null || !isCurrent(work.generation)) continue

                    val inputIndex = codec.dequeueInputBuffer(INPUT_WAIT_US)
                    if (inputIndex < 0) {
                        val blockedAt = inputBlockedSince ?: now
                        inputBlockedSince = blockedAt
                        check(now - blockedAt < INPUT_STALL_NS) { "Yadea decoder input stalled" }
                        continue
                    }
                    inputBlockedSince = null
                    val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                    buffer.clear()
                    check(unit.size <= buffer.remaining()) { "Yadea video unit exceeds decoder input capacity" }
                    buffer.put(unit.bytes, 0, unit.size)
                    val flags = if (unit.sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(inputIndex, 0, unit.size, unit.ptsUs, flags)
                    synchronized(lock) {
                        if (generation == work.generation) {
                            queuedBytes -= input.removeFirst().bytes.size
                        }
                    }
                } catch (error: Exception) {
                    releaseDecoder(decoder)
                    decoder = null
                    val recovery = synchronized(lock) {
                        if (closed || generation != work.generation) null else {
                            discardChain()
                            scheduleKeyFrame()
                            generation
                        }
                    }
                    if (recovery != null) reportError(recovery, error)
                }
            }
        } finally {
            releaseDecoder(decoder)
        }
    }

    private fun enqueueDecoded(bitmap: Bitmap, frameGeneration: Long) {
        synchronized(lock) {
            if (closed || generation != frameGeneration) {
                bitmap.recycle()
                return
            }
            pendingFrame?.bitmap?.recycle()
            pendingFrame = DecodedFrame(frameGeneration, bitmap)
            lock.notifyAll()
        }
    }

    private fun outputLoop() {
        var instrument: Bitmap? = null
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val destination = Rect(0, 0, width, height)
        val jpeg = ByteArrayOutputStream(IMAGE_HARD_LIMIT)
        try {
            while (true) {
                val frame = synchronized(lock) {
                    while (!closed && pendingFrame == null) lock.wait()
                    if (closed) return
                    checkNotNull(pendingFrame).also { pendingFrame = null }
                }
                val source = frame.bitmap
                var preview: Bitmap? = null
                var delivered = false
                try {
                    if (!isCurrent(frame.generation)) continue
                    val target = instrument ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        .also { instrument = it }
                    Canvas(target).drawBitmap(source, centerCrop(source), destination, paint)

                    val scale = minOf(1.0, PREVIEW_MAX_EDGE.toDouble() / maxOf(source.width, source.height))
                    val phoneFrame = Bitmap.createScaledBitmap(
                        source,
                        (source.width * scale).roundToInt().coerceAtLeast(1),
                        (source.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                    preview = phoneFrame
                    if (isCurrent(frame.generation)) {
                        delivered = true
                        runCatching { onPreview(phoneFrame) }.onFailure { reportError(frame.generation, it) }
                    }
                    if (isCurrent(frame.generation)) {
                        val bytes = encodeJpeg(target, jpeg)
                        if (bytes != null && isCurrent(frame.generation)) onJpeg(bytes)
                    }
                } catch (error: Exception) {
                    reportError(frame.generation, error)
                } finally {
                    if (!delivered && preview !== source) preview?.recycle()
                    if (!delivered || preview !== source) source.recycle()
                }
            }
        } finally {
            instrument?.recycle()
        }
    }

    private fun centerCrop(source: Bitmap): Rect {
        val cropWidth = minOf(source.width.toLong(), source.height.toLong() * width / height)
            .toInt().coerceAtLeast(1)
        val cropHeight = minOf(source.height.toLong(), source.width.toLong() * height / width)
            .toInt().coerceAtLeast(1)
        val x = (source.width - cropWidth) / 2
        val y = (source.height - cropHeight) / 2
        return Rect(x, y, x + cropWidth, y + cropHeight)
    }

    private fun encodeJpeg(bitmap: Bitmap, output: ByteArrayOutputStream): ByteArray? {
        for (quality in jpegQualities) {
            output.reset()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Yadea JPEG encoding failed" }
            if (output.size() in 1..IMAGE_HARD_LIMIT) return output.toByteArray()
        }
        return null
    }

    private fun isCurrent(frameGeneration: Long): Boolean = synchronized(lock) {
        !closed && generation == frameGeneration
    }

    private fun reportError(frameGeneration: Long, error: Throwable) {
        if (isCurrent(frameGeneration)) runCatching { onError(error) }
    }

    private fun startDecoder(size: Size, unit: AccessUnit): MediaCodec {
        val parameters = checkNotNull(unit.configuration)
        val mime = unit.codec.mime
        val yuv420 = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        var lastError: Exception? = null
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || mime !in info.supportedTypes) continue
            var decoder: MediaCodec? = null
            try {
                val capabilities = info.getCapabilitiesForType(mime)
                if (yuv420 !in capabilities.colorFormats) continue
                val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, yuv420)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT_BYTES)
                    if (capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    if (unit.codec == Codec.AVC) {
                        setByteBuffer("csd-0", ByteBuffer.wrap(parameters[0]))
                        setByteBuffer("csd-1", ByteBuffer.wrap(parameters[1]))
                    } else {
                        setByteBuffer("csd-0", ByteBuffer.wrap(parameters[0] + parameters[1] + parameters[2]))
                    }
                }
                val candidate = MediaCodec.createByCodecName(info.name)
                decoder = candidate
                candidate.configure(format, null, null, 0)
                candidate.start()
                return candidate
            } catch (error: Exception) {
                lastError = error
                releaseDecoder(decoder)
            }
        }
        throw IllegalStateException("No Yadea decoder for $mime at ${size.width}x${size.height}", lastError)
    }

    private fun drainDecoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        converter: YuvConverter,
        publish: Boolean,
    ): Bitmap? {
        var latest = -1
        var bitmap: Bitmap? = null
        try {
            // Only decoded output can be skipped without breaking reference dependencies.
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                if (index < 0) break
                val picture = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                if (!publish || !picture) {
                    codec.releaseOutputBuffer(index, false)
                    continue
                }
                val older = latest
                latest = index
                if (older >= 0) codec.releaseOutputBuffer(older, false)
            }
            if (latest >= 0) {
                val format = codec.getOutputFormat(latest)
                val image = checkNotNull(codec.getOutputImage(latest)) { "Yadea decoder did not provide a YUV image" }
                image.use { bitmap = converter.toBitmap(it, format) }
            }
        } catch (error: Throwable) {
            bitmap?.recycle()
            bitmap = null
            throw error
        } finally {
            if (latest >= 0) {
                try {
                    codec.releaseOutputBuffer(latest, false)
                } catch (error: Exception) {
                    bitmap?.recycle()
                    throw error
                }
            }
        }
        return bitmap
    }

    private fun releaseDecoder(codec: MediaCodec?) {
        runCatching { codec?.release() }
    }

    private class YuvConverter {
        private var pixels = IntArray(0)

        fun toBitmap(image: Image, format: MediaFormat): Bitmap {
            check(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) {
                "Yadea decoder output must be 8-bit YUV 4:2:0"
            }
            val crop = image.cropRect
            check(!crop.isEmpty && Rect(0, 0, image.width, image.height).contains(crop))
            val yPlane = Plane(image.planes[0], crop.right - 1, crop.bottom - 1)
            val uPlane = Plane(image.planes[1], (crop.right - 1) / 2, (crop.bottom - 1) / 2)
            val vPlane = Plane(image.planes[2], (crop.right - 1) / 2, (crop.bottom - 1) / 2)
            val color = YuvMatrix.from(format)
            val count = Math.multiplyExact(crop.width(), crop.height())
            if (pixels.size < count) pixels = IntArray(count)

            var pixel = 0
            for (y in crop.top until crop.bottom) {
                val yRow = yPlane.row(y)
                val uRow = uPlane.row(y / 2)
                val vRow = vPlane.row(y / 2)
                for (x in crop.left until crop.right) {
                    val luma = (yPlane.sample(yRow, x) - color.yOffset).coerceAtLeast(0) * color.yScale
                    val u = uPlane.sample(uRow, x / 2) - 128
                    val v = vPlane.sample(vRow, x / 2) - 128
                    val r = ((luma + color.redV * v + 128) shr 8).coerceIn(0, 255)
                    val g = ((luma - color.greenU * u - color.greenV * v + 128) shr 8).coerceIn(0, 255)
                    val b = ((luma + color.blueU * u + 128) shr 8).coerceIn(0, 255)
                    pixels[pixel++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(pixels, 0, crop.width(), crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        }
    }

    private class Plane(plane: Image.Plane, lastX: Int, lastY: Int) {
        private val buffer = plane.buffer
        private val base = buffer.position()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride

        init {
            val last = base + lastY.toLong() * rowStride + lastX.toLong() * pixelStride
            check(rowStride > 0 && pixelStride > 0 && last < buffer.limit()) { "Invalid Yadea YUV plane layout" }
        }

        fun row(y: Int): Int = base + y * rowStride
        fun sample(row: Int, x: Int): Int = buffer.get(row + x * pixelStride).toInt() and 0xFF
    }

    private data class YuvMatrix(
        val yOffset: Int,
        val yScale: Int,
        val redV: Int,
        val greenU: Int,
        val greenV: Int,
        val blueU: Int,
    ) {
        companion object {
            fun from(format: MediaFormat): YuvMatrix {
                val full = format.getInteger(MediaFormat.KEY_COLOR_RANGE, 0) == MediaFormat.COLOR_RANGE_FULL
                return when (format.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0)) {
                    MediaFormat.COLOR_STANDARD_BT709 -> if (full) YuvMatrix(0, 256, 403, 48, 120, 475)
                        else YuvMatrix(16, 298, 459, 55, 136, 541)
                    MediaFormat.COLOR_STANDARD_BT2020 -> if (full) YuvMatrix(0, 256, 377, 42, 146, 482)
                        else YuvMatrix(16, 298, 430, 48, 167, 548)
                    else -> if (full) YuvMatrix(0, 256, 359, 88, 183, 454)
                        else YuvMatrix(16, 298, 409, 100, 208, 516)
                }
            }
        }
    }

    private enum class Codec(val mime: String, val parameterTypes: IntRange) {
        AVC(MediaFormat.MIMETYPE_VIDEO_AVC, 7..8),
        HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, 32..34),
    }

    private data class Size(val width: Int, val height: Int)
    private data class AccessUnit(
        val codec: Codec,
        val bytes: ByteArray,
        val size: Int,
        val ptsUs: Long,
        val sync: Boolean,
        val configuration: List<ByteArray>?,
    )
    private data class DecodeWork(val generation: Long, val size: Size?, val unit: AccessUnit?, val requestSync: Boolean)
    private data class DecodedFrame(val generation: Long, val bitmap: Bitmap)
    private data class ParsedUnit(
        val bytes: ByteArray,
        val pictureSize: Int,
        val pictureType: Int,
        val parameters: List<ByteArray?>,
    )

    private companion object {
        const val IMAGE_HARD_LIMIT = 81_920
        const val PREVIEW_MAX_EDGE = 1280
        const val MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024
        const val MAX_QUEUED_BYTES = 8 * 1024 * 1024
        const val MAX_QUEUED_UNITS = 12
        const val MAX_PARAMETER_SET_BYTES = 64 * 1024
        const val DECODER_POLL_MS = 8L
        const val INPUT_WAIT_US = 5_000L
        const val INPUT_STALL_NS = 500_000_000L
        const val KEY_FRAME_INTERVAL_NS = 500_000_000L
        val ANNEX_B_PREFIX = byteArrayOf(0, 0, 0, 1)

        fun parseAccessUnit(bytes: ByteArray, codec: Codec): ParsedUnit? {
            val parameters = arrayOfNulls<ByteArrayOutputStream>(codec.parameterTypes.count())
            var start = findStartCode(bytes, 0)
            if (start < 0) return null
            var pictureSize = 0
            var pictureType = -1
            while (start >= 0) {
                val header = start + if (bytes[start + 2].toInt() == 1) 3 else 4
                val next = findStartCode(bytes, header)
                val end = if (next < 0) bytes.size else next
                val headerSize = if (codec == Codec.AVC) 1 else 2
                if (end - header < headerSize) return null
                val firstByte = bytes[header].toInt()
                val type = if (codec == Codec.AVC) firstByte and 31 else (firstByte shr 1) and 63
                if (type in codec.parameterTypes) {
                    val index = type - codec.parameterTypes.first
                    val output = parameters[index] ?: ByteArrayOutputStream().also { parameters[index] = it }
                    var nalEnd = end
                    while (nalEnd > header + headerSize && bytes[nalEnd - 1].toInt() == 0) nalEnd--
                    if (output.size() + ANNEX_B_PREFIX.size + nalEnd - header > MAX_PARAMETER_SET_BYTES) return null
                    output.write(ANNEX_B_PREFIX)
                    output.write(bytes, header, nalEnd - header)
                } else {
                    val vcl = if (codec == Codec.AVC) type in 1..5 else type in 0..31
                    if (pictureType < 0 && vcl) pictureType = type
                    // Strip CSD in place. The decoder is configured with it via MediaFormat.
                    if (pictureSize != start) bytes.copyInto(bytes, pictureSize, start, end)
                    pictureSize += end - start
                }
                start = next
            }
            return ParsedUnit(bytes, pictureSize, pictureType, parameters.map { it?.toByteArray() })
        }

        fun findStartCode(bytes: ByteArray, from: Int): Int {
            for (index in from until bytes.size - 2) {
                if (bytes[index].toInt() != 0 || bytes[index + 1].toInt() != 0) continue
                if (bytes[index + 2].toInt() == 1 ||
                    (bytes[index + 2].toInt() == 0 && index + 3 < bytes.size && bytes[index + 3].toInt() == 1)
                ) return index
            }
            return -1
        }
    }
}
