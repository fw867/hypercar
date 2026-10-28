package io.github.hypercar.carui

import android.os.Binder
import android.os.ParcelFileDescriptor
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.spike.SpikeShell
import java.util.concurrent.atomic.AtomicBoolean

/** Playback PCM from shell -> existing Rust mixer -> AirPlay. Never records the microphone. */
object CarAudioCapture {
    private class Pump(val input: ParcelFileDescriptor, val owner: Binder) {
        val live = AtomicBoolean(true)
    }
    private var pump: Pump? = null

    @Synchronized
    fun start() {
        if (pump != null) {
            MusicBridge.onCarAudioReady()
            return
        }
        val owner = Binder()
        val descriptor = runCatching { SpikeShell.startAudioCapture(owner) }.getOrElse {
            HyperCarRepo.log("CARUI-AUDIO: 启动失败 ${it.message}")
            return
        }
        if (descriptor == null) {
            HyperCarRepo.log("CARUI-AUDIO: ${SpikeShell.audioCaptureStatus()}")
            return
        }
        val current = Pump(descriptor, owner)
        pump = current
        Thread({
            var samples = 0L
            var peak = 0
            var squareSum = 0.0
            var windowSamples = 0L
            var lastLog = System.currentTimeMillis()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val bytes = ByteArray(3840)
                    while (current.live.get()) {
                        var count = 0
                        // InputStream reads may be short or split a PCM16 sample.
                        while (count < bytes.size && current.live.get()) {
                            val n = input.read(bytes, count, bytes.size - count)
                            check(n > 0) { "capture pipe closed" }
                            count += n
                        }
                        if (!current.live.get()) break
                        val pcm = ShortArray(count / 2) { i ->
                            ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                        }
                        for (sample in pcm) {
                            peak = maxOf(peak, kotlin.math.abs(sample.toInt()))
                            squareSum += sample.toDouble() * sample
                        }
                        windowSamples += pcm.size
                        samples += pcm.size
                        RustCoreBridge.nativePushAudioPcm(pcm, 48000, 2)
                        val now = System.currentTimeMillis()
                        if (now - lastLog >= 5000) {
                            val rms = kotlin.math.sqrt(squareSum / windowSamples.coerceAtLeast(1)).toInt()
                            HyperCarRepo.log("CARUI-AUDIO: samples=$samples peak=$peak rms=$rms → AirPlay PCM48k stereo")
                            peak = 0
                            squareSum = 0.0
                            windowSamples = 0
                            lastLog = now
                        }
                    }
                }
            } catch (e: Exception) {
                if (current.live.get()) HyperCarRepo.log("CARUI-AUDIO: 采集结束 ${e.message}")
            } finally {
                synchronized(this) {
                    if (pump === current) {
                        pump = null
                        runCatching { SpikeShell.stopAudioCapture() }
                    }
                }
            }
        }, "hypercar-audio-pump").apply { isDaemon = true; start() }
        HyperCarRepo.log("CARUI-AUDIO: 已启动系统播放采集（48000Hz 双声道，不采集麦克风）")
        MusicBridge.onCarAudioReady()
    }

    @Synchronized
    fun stop() {
        val current = pump ?: return
        pump = null
        current.live.set(false)
        runCatching { current.input.close() }
        runCatching { SpikeShell.stopAudioCapture() }
        HyperCarRepo.log("CARUI-AUDIO: 已停止，手机音频输出恢复")
    }
}
