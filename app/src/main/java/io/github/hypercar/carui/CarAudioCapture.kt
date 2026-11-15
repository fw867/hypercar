package io.github.hypercar.carui

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.spike.SpikeShell
import java.util.concurrent.atomic.AtomicBoolean

/** Mutually exclusive MEDIA / map-app playback capture; never records a microphone. */
object CarAudioCapture {
    private class Pump(
        val inputs: Array<ParcelFileDescriptor>,
        val owner: Binder,
        val context: Context,
        val split: Boolean,
    ) {
        val live = AtomicBoolean(true)
        var navigation: NavigationPcmGate? = null
    }
    private var pump: Pump? = null
    private var wanted = false

    @Synchronized
    fun start(context: Context) {
        wanted = true
        pump?.let {
            // A new native engine has no previous TTS INIT, even if the shell
            // capture survived a control reconnection. Begin a fresh audio segment.
            it.navigation?.reset()
            MusicBridge.onCarAudioReady()
            return
        }
        startLocked(context.applicationContext, preferSplit = true)
    }

    private fun startLocked(context: Context, preferSplit: Boolean) {
        val owner = Binder()
        val uid = if (preferSplit) runCatching {
            context.packageManager.getApplicationInfo("com.autonavi.minimap", 0).uid
        }.getOrNull()?.takeIf { it >= 10000 } else null
        var split = false
        var inputs = if (uid != null) runCatching {
            SpikeShell.startSplitAudioCapture(owner, uid)
        }.onFailure { HyperCarRepo.log("CARUI-AUDIO: UID split unavailable: ${it.message}") }.getOrNull() else null
        if (inputs != null && inputs.size == 2) split = true
        else {
            inputs?.forEach { runCatching { it.close() } }
            // No partial UID policy may coexist with the legacy all-playback sink.
            runCatching { SpikeShell.stopAudioCapture() }
            if (preferSplit) HyperCarRepo.log("CARUI-AUDIO: split unavailable; fallback to one system mix, no separate navigation capture")
            val legacy = runCatching { SpikeShell.startAudioCapture(owner) }.getOrNull()
            inputs = legacy?.let { arrayOf(it) }
        }
        if (inputs == null) {
            HyperCarRepo.log("CARUI-AUDIO: capture unavailable: ${runCatching { SpikeShell.audioCaptureStatus() }.getOrDefault("shell disconnected")}")
            return
        }
        val current = Pump(inputs, owner, context, split)
        current.navigation = if (split) NavigationPcmGate(object : NavigationPcmGate.Sink {
            override fun start() = synchronized(this@CarAudioCapture) {
                if (pump === current && current.live.get()) {
                    RustCoreBridge.nativeStartNavigationAudio(48000, 2)
                    HyperCarRepo.log("CARUI-NAV-AUDIO: segment start source=map-uid; requesting independent navigation output")
                }
            }
            override fun pcm(samples: ShortArray) = synchronized(this@CarAudioCapture) {
                if (pump === current && current.live.get()) RustCoreBridge.nativePushNavigationAudioPcm(samples, 48000, 2)
            }
            override fun stop() = synchronized(this@CarAudioCapture) {
                if (pump === current && current.live.get()) {
                    RustCoreBridge.nativeStopNavigationAudio()
                    HyperCarRepo.log("CARUI-NAV-AUDIO: segment stop; navigation tail/unduck handled by output lifecycle")
                }
            }
        }) else null
        pump = current
        inputs.forEachIndexed { lane, descriptor ->
            Thread({ read(current, lane, descriptor) }, if (lane == 1) "hypercar-navigation-pcm" else "hypercar-media-pcm")
                .apply { isDaemon = true; start() }
        }
        HyperCarRepo.log(if (split) "CARUI-AUDIO: split active media=exclude-map-uid navigation=map-uid; PCM48k stereo, disjoint policy sinks"
            else "CARUI-AUDIO: legacy system mix active PCM48k stereo; navigation included once in MEDIA")
        MusicBridge.onCarAudioReady()
    }

    private fun read(current: Pump, lane: Int, descriptor: ParcelFileDescriptor) {
        var samples = 0L
        var peak = 0
        var squareSum = 0.0
        var windowSamples = 0L
        var nonzeroSamples = 0L
        var lastLog = SystemClock.elapsedRealtime()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val bytes = ByteArray(3840)
                while (current.live.get()) {
                    var count = 0
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
                        if (sample.toInt() != 0) nonzeroSamples++
                    }
                    samples += pcm.size
                    windowSamples += pcm.size
                    synchronized(this) {
                        if (pump !== current || !current.live.get()) return
                        if (lane == 1) current.navigation?.accept(pcm, SystemClock.elapsedRealtime())
                        else RustCoreBridge.nativePushAudioPcm(pcm, 48000, 2)
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLog >= 5000) {
                        val rms = kotlin.math.sqrt(squareSum / windowSamples.coerceAtLeast(1)).toInt()
                        HyperCarRepo.log("CARUI-${if (lane == 1) "NAV-AUDIO" else "AUDIO"}: samples=$samples nonzeroSamples=$nonzeroSamples peak=$peak rms=$rms split=${current.split} → ${if (lane == 1) "navigation PCM" else "MEDIA PCM48k stereo"}")
                        if (lane == 0) HyperCarRepo.log("CARUI-AUDIO: ${runCatching { SpikeShell.audioCaptureStatus() }.getOrDefault("shell status unavailable")}")
                        peak = 0; squareSum = 0.0; windowSamples = 0; lastLog = now
                    }
                }
            }
        } catch (error: Exception) {
            if (current.live.get()) HyperCarRepo.log("CARUI-AUDIO: lane=$lane ended: ${error.message}")
        } finally {
            synchronized(this) {
                if (pump === current) {
                    retireLocked(current)
                    if (wanted && current.split) {
                        HyperCarRepo.log("CARUI-AUDIO: split lane lost; retire both sinks and fall back to one system mix")
                        startLocked(current.context, preferSplit = false)
                    }
                }
            }
        }
    }

    private fun retireLocked(current: Pump) {
        // Close the gate while its generation is still current, so STOP reaches
        // exactly this native source before any replacement may start.
        current.navigation?.close()
        current.live.set(false)
        if (pump === current) pump = null
        current.inputs.forEach { runCatching { it.close() } }
        runCatching { SpikeShell.stopAudioCapture() }
    }

    @Synchronized
    fun stop() {
        wanted = false
        pump?.let(::retireLocked)
        HyperCarRepo.log("CARUI-AUDIO: stopped; playback routing restored")
    }
}
