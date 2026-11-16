package io.github.hypercar.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore

/** One source round. Called serially by the native video pump, never from the UI thread. */
class CarWithHevcTranscoder private constructor(private val inputHevc: Boolean) : AutoCloseable {
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var bridge: CodecSurfaceBridge? = null
    private val pending = ArrayDeque<Pair<ByteArray, Long>>()
    private var pendingBytes = 0
    private var configuration = byteArrayOf()
    @Volatile private var closed = false
    private var decodeThread: Thread? = null
    private var encodeThread: Thread? = null
    private val failure = AtomicReference<Throwable?>(null)
    private val output = ArrayBlockingQueue<ByteArray>(8)
    private val outputBytes = AtomicInteger(0)
    private val info = MediaCodec.BufferInfo()
    private var initThread: Thread? = null
    private val lease = AtomicBoolean(false)
    @Volatile private var workersStarted = false
    private var framePeriodUs = 33_333L
    private var queued = 0L
    private var skipped = 0L
    @Volatile private var decoded = 0L
    @Volatile private var rendered = 0L
    private var encoded = 0L
    private var lastMetrics = System.nanoTime()
    private var awaitingIdr = false
    private val keyframeNeeded = AtomicBoolean(false)
    private var inputIndex = -1 // decoder worker owns a dequeued slot across a concurrent queue retirement

    companion object {
        private val lifecycle = Semaphore(1, true)
        @JvmStatic fun open(inputHevc: Boolean, width: Int, height: Int, targetWidth: Int, targetHeight: Int, fps: Int, csd: ByteArray): CarWithHevcTranscoder {
            require(width in 16..8192 && height in 16..8192 && targetWidth in 16..8192 && targetHeight in 16..8192 && fps in 1..120)
            val pair = CarWithHevcTranscoder(inputHevc)
            val sourceMime = if (inputHevc) "video/hevc" else "video/avc"
            pair.framePeriodUs = 1_000_000L / fps
            pair.initThread = Thread({
              try {
                lifecycle.acquire()
                pair.lease.set(true)
                if (pair.closed) return@Thread
                val format = MediaFormat.createVideoFormat("video/hevc", targetWidth, targetHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_BIT_RATE, (targetWidth.toLong() * targetHeight * fps / 12).coerceIn(4_000_000, 24_000_000).toInt())
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }
                val decoderFormat = MediaFormat.createVideoFormat(sourceMime, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                fun hardware(mime: String, encode: Boolean, f: MediaFormat): MediaCodecInfo =
                    infos.firstOrNull { item ->
                        item.isEncoder == encode && item.isHardwareAccelerated && !item.isSoftwareOnly &&
                            item.supportedTypes.any { it.equals(mime, true) } && runCatching {
                                val caps = item.getCapabilitiesForType(mime)
                                caps.videoCapabilities.areSizeAndRateSupported(if (encode) targetWidth else width, if (encode) targetHeight else height, fps.toDouble()) &&
                                    (!encode || caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) &&
                                    caps.isFormatSupported(f)
                            }.getOrDefault(false)
                    } ?: error("No hardware $mime ${if (encode) "encoder" else "decoder"} for ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}@$fps")
                val enc = hardware("video/hevc", true, format)
                val dec = hardware(sourceMime, false, decoderFormat)
                pair.encoder = MediaCodec.createByCodecName(enc.name)
                pair.encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                pair.surface = pair.encoder!!.createInputSurface()
                pair.bridge = CodecSurfaceBridge(checkNotNull(pair.surface), width, height, targetWidth, targetHeight)
                pair.encoder!!.start()
                pair.decoder = MediaCodec.createByCodecName(dec.name)
                pair.decoder!!.configure(decoderFormat, checkNotNull(pair.bridge).input, null, 0)
                pair.decoder!!.start()
                android.util.Log.i("HyperCar-Transcode", "hardware ${dec.name} ${width}x$height -> GPU full-screen stretch/sharpen -> ${enc.name} ${targetWidth}x$targetHeight@$fps")
                if (!pair.closed) pair.startWorkers()
              } catch (error: Throwable) {
                if (!pair.closed) pair.failure.compareAndSet(null, error)
                pair.closed = true
              } finally {
                if (!pair.workersStarted) { pair.cleanupUnstarted(); pair.releaseLease() }
              }
            }, "CarWithCodecInit").apply { isDaemon = true; start() }
            return pair
        }
    }

    fun queue(data: ByteArray, pts: Long) {
        checkFailure()
        require(data.size <= 4 * 1024 * 1024)
        synchronized(pending) {
            // CarWith can deliver a read burst much faster than its average frame rate.
            val maxPackets = 32
            val maxBytes = if (workersStarted) 8 * 1024 * 1024 else 16 * 1024 * 1024
            if (awaitingIdr) {
                if (!hasIdr(data)) { skipped++; return }
                awaitingIdr = false
            }
            if (pending.size >= maxPackets || pendingBytes + data.size > maxBytes) {
                // Only a fresh IDR can retire unsubmitted reference pictures without corrupting the decoder chain.
                skipped += pending.size; pending.clear(); pendingBytes = 0
                if (!hasIdr(data)) {
                    awaitingIdr = true; keyframeNeeded.set(true); skipped++
                    android.util.Log.w("HyperCar-Transcode", "input burst exceeded bound; retire pending references and request fresh source IDR")
                    return
                }
            }
            pending.addLast(data to pts); pendingBytes += data.size
            queued++
        }
    }
    fun needsKeyFrame(): Boolean = keyframeNeeded.getAndSet(false)
    private fun hasIdr(data: ByteArray): Boolean {
        for (i in 0 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte()) {
                val n = if (data[i+2] == 1.toByte()) i+3 else if(data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) i+4 else continue
                if (n < data.size) {
                    val header = data[n].toInt() and 255
                    if (if (inputHevc) (header ushr 1 and 63) in 16..21 else (header and 31) == 5) return true
                }
            }
        }
        return false
    }
    private fun checkFailure() {
        failure.get()?.let { throw IllegalStateException("Hardware transcode worker failed", it) }
        check(!closed) { "Hardware transcode closed" }
    }
    /** Native calls never wait on hardware, EGL, or codec teardown. */
    fun drain(): ByteArray? {
        checkFailure()
        return output.poll()?.also { outputBytes.addAndGet(-it.size) }
    }
    private fun startWorkers() {
        decodeThread = Thread({
            try {
                val dec = checkNotNull(decoder)
                val decoded = MediaCodec.BufferInfo()
                var nextRender = 0L
                while (!closed) {
                    feed()
                    val index = dec.dequeueOutputBuffer(decoded, 1_000)
                    if (index >= 0) {
                        check(decoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Unexpected decoder EOS" }
                        this.decoded++
                        val pts = decoded.presentationTimeUs
                        val render = pts >= nextRender
                        dec.releaseOutputBuffer(index, render)
                        if (render) {
                            nextRender = maxOf(nextRender + framePeriodUs, pts + framePeriodUs / 4)
                            checkNotNull(bridge).render(pts)
                            rendered++
                        }
                    }
                }
            } catch (error: Throwable) { if (!closed) failure.compareAndSet(null,error); closed = true }
            finally {
                decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }; decoder = null
                runCatching { bridge?.close() }; bridge = null
            }
        }, "CarWithDecode").apply { isDaemon = true }
        encodeThread = Thread({
            try {
                // Keep draining during close until the decoder releases the Surface producer.
                var closingAt = 0L
                while (!closed || decodeThread?.isAlive == true) {
                    if (closed) {
                        if (closingAt == 0L) closingAt = System.nanoTime()
                        if (System.nanoTime() - closingAt > 2_000_000_000L) break
                    }
                    val packet = readEncoded()
                    if (packet != null && !closed) {
                        encoded++
                        check(outputBytes.addAndGet(packet.size) <= 16 * 1024 * 1024 && output.offer(packet)) { "HEVC output backlog" }
                    } else if (packet == null) Thread.sleep(1)
                    if (System.nanoTime() - lastMetrics > 2_000_000_000L) {
                        lastMetrics = System.nanoTime()
                        synchronized(pending) {
                            android.util.Log.i("HyperCar-Transcode", "units in=$queued decoded=$decoded rendered=$rendered encoded=$encoded retiredBeforeIdr=$skipped pending=${pending.size}/$pendingBytes")
                        }
                    }
                }
            } catch (error: Throwable) { if (!closed) failure.compareAndSet(null,error); closed = true }
            finally {
                // Stop the encoder to unblock a Surface producer on an encoder failure.
                encoder?.let { runCatching { it.stop() } }
                // A stuck driver keeps the lifecycle permit; later sources time out to AVC,
                // rather than racing another hardware allocation against live old GL resources.
                runCatching { decodeThread?.join() }
                encoder?.let { runCatching { it.release() } }; encoder = null
                surface?.release(); surface = null
                releaseLease()
            }
        }, "CarWithEncode").apply { isDaemon = true }
        workersStarted = true
        decodeThread!!.start(); encodeThread!!.start()
    }

    private fun feed() {
        val codec = checkNotNull(decoder)
        while (!closed) {
            synchronized(pending) { if (pending.isEmpty()) return }
            if (inputIndex < 0) inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex < 0) break
            val packet = synchronized(pending) { pending.pollFirst()?.also { pendingBytes -= it.first.size } } ?: return
            val (data, pts) = packet
            val index = inputIndex; inputIndex = -1
            val input = checkNotNull(codec.getInputBuffer(index))
            check(input.capacity() >= data.size) { "Source frame exceeds codec buffer" }
            input.clear(); input.put(data)
            codec.queueInputBuffer(index, 0, data.size, pts, 0)
        }
    }

    private fun readEncoded(): ByteArray? {
        val enc = checkNotNull(encoder)
        repeat(16) {
            val index = enc.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = enc.outputFormat
                    val headers = java.io.ByteArrayOutputStream()
                    for (key in listOf("csd-0", "csd-1")) {
                        val buffer = format.getByteBuffer(key)?.duplicate() ?: continue
                        headers.write(ByteArray(buffer.remaining()).also { buffer.get(it) })
                    }
                    if (headers.size() > 0) configuration = headers.toByteArray()
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                index >= 0 -> {
                    try {
                        check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Unexpected encoder EOS" }
                        if (info.size > 0) {
                            val buffer = checkNotNull(enc.getOutputBuffer(index)).duplicate()
                            require(info.offset >= 0 && info.size <= 8 * 1024 * 1024 && info.offset.toLong() + info.size <= buffer.capacity())
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val data = ByteArray(info.size).also { buffer.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) configuration = data
                            else {
                                val config = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) configuration else byteArrayOf()
                                return ByteBuffer.allocate(8 + config.size + data.size).order(ByteOrder.LITTLE_ENDIAN)
                                    .putLong(info.presentationTimeUs).put(config).put(data).array()
                            }
                        }
                    } finally { enc.releaseOutputBuffer(index, false) }
                }
            }
        }
        return null
    }

    private fun releaseLease() { if (lease.compareAndSet(true,false)) lifecycle.release() }
    private fun cleanupUnstarted() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }; decoder = null
        runCatching { bridge?.close() }; bridge = null
        encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }; encoder = null
        surface?.release(); surface = null
    }
    override fun close() {
        closed = true
        synchronized(pending) { pending.clear(); pendingBytes = 0 }
        output.clear(); outputBytes.set(0)
        // Interrupt semaphore waits. Hardware initialization and cleanup never run on the video pump.
        initThread?.interrupt()
        // Init/worker finally blocks own resources and the process-wide hardware lifecycle permit.
    }
}
