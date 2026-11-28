package io.github.hypercar.yadea

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class YadeaAudio(context: Context, onRoute: (String) -> Unit) : Closeable {
    private val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val routeStatus = MutableStateFlow<String?>(null)
    private val routeRequest = AtomicReference(RouteRequest(null, 0))

    private val media = Lane(AudioAttributes.CONTENT_TYPE_MUSIC)
    private val speech = Lane(AudioAttributes.CONTENT_TYPE_SPEECH)
    private val routingListener = AudioRouting.OnRoutingChangedListener { wake.trySend(Unit) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
    }

    init {
        scope.launch(Dispatchers.Main) {
            routeStatus.filterNotNull().collect { if (!closed.get()) onRoute(it) }
        }
        scope.launch { play() }
    }

    fun configure(tts: Boolean, rate: Int, channels: Int) {
        lane(tts).configure(Format(rate, channels).takeIf { it.valid })
        wake.trySend(Unit)
    }

    /** Rust hands off a fresh PCM16 little-endian array on each JNI callback. */
    fun offer(tts: Boolean, rate: Int, channels: Int, pcm: ByteArray) {
        if (lane(tts).offer(rate, channels, pcm)) wake.trySend(Unit)
    }

    fun stop(tts: Boolean) {
        lane(tts).finish()
        wake.trySend(Unit)
    }

    fun setPreferredDevice(deviceId: Int?) {
        if (closed.get()) return
        routeRequest.updateAndGet { RouteRequest(deviceId, it.revision + 1) }
        wake.trySend(Unit)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        media.configure(null)
        speech.configure(null)
        wake.close()
        scope.cancel()
    }

    private fun lane(tts: Boolean) = if (tts) speech else media

    private fun refreshDevices() {
        routeRequest.updateAndGet { it.copy(revision = it.revision + 1) }
        wake.trySend(Unit)
    }

    private suspend fun play() {
        var devices = emptyArray<AudioDeviceInfo>()
        var lastRevision: Int? = null
        val registered = runCatching { manager.registerAudioDeviceCallback(deviceCallback, mainHandler) }
            .onFailure { Log.w(TAG, "Unable to monitor audio outputs", it) }.isSuccess
        try {
            while (scope.isActive && !closed.get()) {
                val (requested, revision) = routeRequest.get()
                if (lastRevision != revision) {
                    devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                        .getOrDefault(emptyArray())
                    lastRevision = revision
                }
                val target = devices.firstOrNull { it.id == requested }
                val now = SystemClock.elapsedRealtime()
                val mediaDelay = media.pump(requested, target, revision, now)
                val speechDelay = speech.pump(requested, target, revision, now)
                val mediaRoute = media.describeRoute(requested, target)
                val speechRoute = speech.describeRoute(requested, target)
                routeStatus.value = when {
                    mediaRoute != null && speechRoute != null && mediaRoute != speechRoute ->
                        "媒体：$mediaRoute · 提示音：$speechRoute"
                    else -> mediaRoute ?: speechRoute ?: when {
                        requested == null -> "系统默认（等待播放）"
                        target == null -> "首选设备不可用（#$requested）"
                        else -> "${target.label()}（等待播放）"
                    }
                }

                val delay = minOf(mediaDelay, speechDelay)
                if (delay == Long.MAX_VALUE) {
                    if (wake.receiveCatching().isClosed) break
                } else {
                    withTimeoutOrNull(delay) { wake.receiveCatching() }
                }
            }
        } finally {
            media.releaseTrack()
            speech.releaseTrack()
            if (registered) runCatching { manager.unregisterAudioDeviceCallback(deviceCallback) }
        }
    }

    private data class RouteRequest(val deviceId: Int?, val revision: Int)

    private data class Format(val rate: Int, val channels: Int) {
        val valid get() = rate in 4_000..192_000 && channels in 1..2
        val frameBytes get() = channels * 2
        val queueBytes get() = (rate * QUEUE_MS / 1_000) * frameBytes
    }

    private class Input(val format: Format) {
        val packets = ArrayDeque<ByteArray>()
        var bytes = 0
        @Volatile var finishAt = Long.MAX_VALUE

        fun clearQueue() {
            packets.clear()
            bytes = 0
        }
    }

    private inner class Lane(private val contentType: Int) {
        // The lock covers only ingress state. Android audio calls run on the playback coroutine.
        private val lock = ReentrantLock()
        @Volatile private var input: Input? = null

        private var playing: Input? = null
        private var track: AudioTrack? = null
        private var pending: ByteBuffer? = null
        private var appliedRevision: Int? = null
        private var routeAccepted = false
        private var writtenFrames = 0
        private var lastWriteAt = 0L
        private var retryAt = 0L

        fun configure(format: Format?) = lock.withLock {
            input?.clearQueue()
            input = if (!closed.get() && format != null) Input(format) else null
        }

        fun offer(rate: Int, channels: Int, pcm: ByteArray): Boolean {
            if (closed.get() || !lock.tryLock()) return false
            try {
                if (closed.get()) return false
                val current = input
                val stream = if (current != null && current.finishAt == Long.MAX_VALUE &&
                    current.format.rate == rate && current.format.channels == channels
                ) {
                    current
                } else {
                    val format = Format(rate, channels)
                    if (!format.valid) return false
                    Input(format)
                }
                val format = stream.format
                if (pcm.isEmpty() || pcm.size > format.queueBytes || pcm.size % format.frameBytes != 0) return false

                while (stream.bytes + pcm.size > format.queueBytes || stream.packets.size >= MAX_PACKETS) {
                    stream.bytes -= stream.packets.removeFirst().size
                }
                stream.packets.addLast(pcm)
                stream.bytes += pcm.size
                if (stream !== current) {
                    current?.clearQueue()
                    input = stream
                }
                return true
            } finally {
                lock.unlock()
            }
        }

        fun finish() = lock.withLock {
            val stream = input
            if (stream != null && stream.finishAt == Long.MAX_VALUE) {
                stream.finishAt = SystemClock.elapsedRealtime() + DRAIN_MS
            }
        }

        fun pump(requested: Int?, target: AudioDeviceInfo?, revision: Int, now: Long): Long {
            val stream = input
            if (playing !== stream) {
                releaseTrack()
                playing = stream
                retryAt = 0L
            }
            if (stream == null) return Long.MAX_VALUE
            if (now >= stream.finishAt) {
                lock.withLock { stream.clearQueue() }
                releaseTrack()
                return Long.MAX_VALUE
            }
            if (now < retryAt) return minOf(retryAt, stream.finishAt) - now

            try {
                repeat(MAX_WRITES_PER_PASS) {
                    if (closed.get() || input !== stream) return Long.MAX_VALUE
                    val chunk = pending ?: lock.withLock {
                        stream.packets.removeFirstOrNull()?.also { stream.bytes -= it.size }
                    }?.let { ByteBuffer.wrap(it).also { buffer -> pending = buffer } }
                    if (chunk == null) {
                        val output = track ?: return Long.MAX_VALUE
                        applyRoute(output, requested, target, revision)
                        val drained = stream.finishAt != Long.MAX_VALUE &&
                            output.playbackHeadPosition - writtenFrames >= 0
                        if (drained || now - lastWriteAt >= IDLE_RELEASE_MS) {
                            releaseTrack()
                            return Long.MAX_VALUE
                        }
                        return minOf(ROUTE_POLL_MS, stream.finishAt - now)
                    }

                    val output = track ?: createTrack(stream.format, contentType).also {
                        track = it
                        applyRoute(it, requested, target, revision)
                        it.play()
                        lastWriteAt = now
                    }
                    applyRoute(output, requested, target, revision)
                    if (closed.get() || input !== stream) return Long.MAX_VALUE
                    val count = output.write(chunk, chunk.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { "AudioTrack.write=$count" }
                    if (count == 0) {
                        check(now - lastWriteAt < IDLE_RELEASE_MS) { "AudioTrack stopped accepting PCM" }
                        return WRITE_RETRY_MS
                    }
                    writtenFrames += count / stream.format.frameBytes
                    lastWriteAt = now
                    if (!chunk.hasRemaining()) pending = null
                }
                return WRITE_RETRY_MS
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to play Yadea PCM", error)
                releaseTrack()
                retryAt = now + ERROR_RETRY_MS
                return minOf(retryAt, stream.finishAt) - now
            }
        }

        private fun applyRoute(output: AudioTrack, requested: Int?, target: AudioDeviceInfo?, revision: Int) {
            if (appliedRevision == revision) return
            appliedRevision = revision
            routeAccepted = runCatching { output.setPreferredDevice(target) }.getOrDefault(false)
            if (requested != null && target != null && !routeAccepted) {
                runCatching { output.setPreferredDevice(null) }
            }
        }

        fun describeRoute(requested: Int?, target: AudioDeviceInfo?): String? {
            val output = track ?: return null
            val actual = runCatching { output.routedDevice }.getOrNull()
            val failure = when {
                requested != null && target == null -> "首选设备不可用（#$requested）"
                !routeAccepted -> "${target?.label() ?: "系统默认"}路由请求失败"
                actual == null -> return "${target?.label() ?: "系统默认"}（等待路由确认）"
                target != null && actual.id != target.id -> "未路由到${target.label()}"
                else -> return actual.label()
            }
            return if (actual == null) failure else "$failure，实际输出：${actual.label()}"
        }

        fun releaseTrack() {
            val output = track
            track = null
            pending = null
            appliedRevision = null
            writtenFrames = 0
            if (output != null) {
                runCatching { output.removeOnRoutingChangedListener(routingListener) }
                runCatching { output.pause() }
                runCatching { output.flush() }
                runCatching { output.release() }
            }
        }
    }

    private fun createTrack(format: Format, contentType: Int): AudioTrack {
        val mask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioTrack.getMinBufferSize(format.rate, mask, encoding)
        check(minimum > 0) { "Unsupported PCM format $format" }
        val bufferFrames = maxOf(
            (minimum + format.frameBytes - 1) / format.frameBytes,
            format.rate * TRACK_BUFFER_MS / 1_000,
        )
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(contentType).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(format.rate).setChannelMask(mask)
                .setEncoding(encoding).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferFrames * format.frameBytes)
            .build()
        try {
            check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
            output.setStartThresholdInFrames(1)
            output.addOnRoutingChangedListener(routingListener, mainHandler)
            return output
        } catch (error: RuntimeException) {
            runCatching { output.release() }
            throw error
        }
    }

    private fun AudioDeviceInfo.label(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "手机听筒"
        else -> productName.toString().trim().ifEmpty { "音频输出 #$id" }
    }

    private companion object {
        const val TAG = "YadeaAudio"
        const val TRACK_BUFFER_MS = 60
        const val QUEUE_MS = 250
        const val MAX_PACKETS = 64
        const val MAX_WRITES_PER_PASS = 8
        const val WRITE_RETRY_MS = 10L
        const val ROUTE_POLL_MS = 80L
        const val DRAIN_MS = 600L
        const val IDLE_RELEASE_MS = 1_000L
        const val ERROR_RETRY_MS = 500L
    }
}
