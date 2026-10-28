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
        private fun isCaptureRoute() = (devices.invoke(audio, AudioManager.STREAM_MUSIC) as Int) == 0x8000

        fun acquire(): String {
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

        fun release() {
            if (original >= 0 && isCaptureRoute() &&
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) == maximum) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            }
        }
    }

    @Synchronized
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
                        while (current.live.get()) {
                            val count = record.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                            check(count > 0) { "AudioRecord.read=$count" }
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
