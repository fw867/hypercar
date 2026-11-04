package io.github.hypercar.spike

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/** Shell playback-output capture. REMOTE_SUBMIX routes sound away from the phone speaker. */
class ShellPlaybackAudio {
    private class Session(val record: AudioRecord, val output: ParcelFileDescriptor,
        val owner: IBinder, val death: IBinder.DeathRecipient, val volume: CaptureVolume) {
        val live = AtomicBoolean(true)
    }
    private var session: Session? = null
    @Volatile var status = "stopped"
        private set

    /** Remote-submix has its own volume index. Change it only while it is the sole media route. */
    private class CaptureVolume(context: Context) {
        private val audio = AudioManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(PlaybackAudioContext(context))
        private val devices = AudioManager::class.java.getMethod("getDevicesForStream", Int::class.javaPrimitiveType)
        private var original = -1
        private var maximum = -1
        private var routeLease: PlaybackRouteLease? = null
        private var routeAttempted = false
        private var released = false
        private fun isCaptureRoute() = (devices.invoke(audio, AudioManager.STREAM_MUSIC) as Int) == 0x8000

        @Synchronized fun inspect(silent: Boolean): String {
            if (released) return "capture released"
            val mask = devices.invoke(audio, AudioManager.STREAM_MUSIC) as Int
            val active = audio.isMusicActive
            val recovery = if (silent && active && mask != 0x8000 && !routeAttempted) {
                routeAttempted = true
                runCatching {
                    val lease = PlaybackRouteLease(audio)
                    routeLease = lease
                    lease.acquire()
                }.getOrElse { "media route recovery failed: ${it.cause?.message ?: it.message}" }
            } else ""
            // Playback can change the route after the one-second startup check has finished.
            if (silent && active && original < 0 && isCaptureRoute()) acquire()
            return "route=0x${mask.toString(16)} active=$active muted=${audio.isStreamMute(AudioManager.STREAM_MUSIC)} volume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} silent=$silent $recovery"
        }

        @Synchronized fun acquire(): String {
            if (released) return "capture released"
            repeat(50) {
                if (isCaptureRoute()) {
                    original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, maximum, 0)
                    return "remote_submix volume $original → ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/$maximum"
                }
                android.os.SystemClock.sleep(20)
            }
            return "remote_submix route not exclusive; phone volume unchanged"
        }

        @Synchronized fun release() {
            if (released) return
            released = true
            try {
                if (original >= 0 && isCaptureRoute() &&
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC) == maximum) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
                }
            } finally {
                routeLease?.let { android.util.Log.i("HyperCar", "CARUI-AUDIO: ${it.release()}") }
            }
        }
    }

    @Synchronized
    // REMOTE_SUBMIX (8) is hidden from the SDK; this service records as shell UID.
    @android.annotation.SuppressLint("MissingPermission", "WrongConstant")
    fun start(context: Context, owner: IBinder): ParcelFileDescriptor? {
        stop()
        var recorder: AudioRecord? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var volume: CaptureVolume? = null
        return try {
            val format = AudioFormat.Builder().setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val builder = AudioRecord.Builder().setAudioSource(8).setAudioFormat(format)
                .setBufferSizeInBytes(38400)
            AudioRecord.Builder::class.java.getMethod("setContext", Context::class.java)
                .invoke(builder, PlaybackAudioContext(context))
            val record = builder.build()
            recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "not recording" }
            val captureVolume = CaptureVolume(context)
            volume = captureVolume
            val volumeStatus = captureVolume.acquire()
            android.util.Log.i("HyperCar", "CARUI-AUDIO: $volumeStatus")
            val descriptors = ParcelFileDescriptor.createPipe()
            pipe = descriptors
            val death = IBinder.DeathRecipient { stop() }
            val current = Session(record, descriptors[1], owner, death, captureVolume)
            owner.linkToDeath(death, 0)
            session = current
            status = "capturing 48000Hz stereo PCM16; $volumeStatus"
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(current.output).use { out ->
                        val bytes = ByteArray(3840) // 20ms, bounded pipe provides backpressure.
                        var lastInspect = android.os.SystemClock.elapsedRealtime()
                        var nonzero = false
                        while (current.live.get()) {
                            val count = record.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                            check(count > 0) { "AudioRecord.read=$count" }
                            if (!nonzero) nonzero = (0 until count).any { bytes[it].toInt() != 0 }
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastInspect >= 5_000) {
                                val detail = runCatching { current.volume.inspect(!nonzero) }
                                    .getOrElse { "route inspection failed: ${it.cause?.message ?: it.message}" }
                                status = "capturing 48000Hz stereo PCM16; $detail"
                                android.util.Log.i("HyperCar", "CARUI-AUDIO: $detail")
                                lastInspect = now
                                nonzero = false
                            }
                            out.write(bytes, 0, count)
                        }
                    }
                } catch (e: Exception) {
                    if (current.live.get()) status = "capture failed: ${e.message}"
                } finally {
                    synchronized(this) { if (session === current) stop() }
                }
            }, "hypercar-playback-capture").apply { isDaemon = true; start() }
            descriptors[0]
        } catch (e: Exception) {
            runCatching { volume?.release() }
            recorder?.release()
            pipe?.forEach { runCatching { it.close() } }
            status = "capture failed: ${e.cause?.message ?: e.message}"
            null
        }
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        session = null
        current.live.set(false)
        runCatching { current.volume.release() }
        runCatching { current.record.stop() }
        runCatching { current.output.close() }
        runCatching { current.record.release() }
        runCatching { current.owner.unlinkToDeath(current.death, 0) }
        status = "stopped"
    }
}
