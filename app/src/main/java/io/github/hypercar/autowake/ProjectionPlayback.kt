package io.github.hypercar.autowake

import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE as DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK as PLAYBACK
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE as MIC
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal object ProjectionForegroundPolicy {
    fun types(microphone: Boolean, playback: Boolean): List<Int> = buildList {
        if (microphone && playback) add(DEVICE or MIC or PLAYBACK)
        if (playback) add(DEVICE or PLAYBACK)
        if (microphone) add(DEVICE or MIC)
        add(DEVICE)
    }
}

internal object ProjectionHeartbeat {
    data class Clock(val elapsedMs: Long, val uptimeMs: Long)
    data class Gap(val elapsedMs: Long, val uptimeMs: Long) {
        val suspendMs get() = (elapsedMs - uptimeMs).coerceAtLeast(0)
    }

    fun gap(before: Clock, now: Clock): Gap? =
        Gap(now.elapsedMs - before.elapsedMs, now.uptimeMs - before.uptimeMs).takeIf { it.elapsedMs >= 1_200L }

    suspend fun monitor(onGap: (Gap) -> Unit) {
        fun sample() = Clock(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
        var previous = sample()
        while (true) {
            delay(500)
            val now = sample()
            gap(previous, now)?.let(onGap)
            previous = now
        }
    }
}

/** A static loop maintains one active track without a writer thread or a media session. */
internal object ProjectionPlayback {
    private const val SAMPLE_RATE = 48_000
    private const val LOOP_FRAMES = SAMPLE_RATE / 10
    // A destroyed Service may still be in native track release while its replacement starts.
    private val trackSlots = Semaphore(2)

    suspend fun withTrackSlot(block: suspend () -> Unit) = trackSlots.withPermit { block() }

    suspend fun releaseAfterForeground(restoreForeground: suspend () -> Unit, releaseTrack: () -> Unit) {
        try {
            restoreForeground()
        } finally {
            releaseTrack()
        }
    }

    fun start(current: () -> Boolean): AudioTrack? {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(LOOP_FRAMES * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            check(track.setVolume(0f) == AudioTrack.SUCCESS) { "setVolume" }
            check(track.write(ShortArray(LOOP_FRAMES), 0, LOOP_FRAMES) == LOOP_FRAMES) { "write" }
            check(track.state == AudioTrack.STATE_INITIALIZED && track.setLoopPoints(0, LOOP_FRAMES, -1) == AudioTrack.SUCCESS) {
                "setLoopPoints"
            }
            if (!current()) {
                track.release()
                return null
            }
            track.play()
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "play" }
            return track
        } catch (error: Exception) {
            runCatching { track.release() }
            throw error
        }
    }
}
