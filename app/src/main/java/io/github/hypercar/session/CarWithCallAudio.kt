package io.github.hypercar.session

import android.content.Context
import android.media.AudioManager
import android.os.Binder
import android.os.SystemClock
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object CarWithCallAudio {
    private const val REBIND_INTERVAL_MS = 2_000L
    private val monitorLock = Mutex()

    private class ForegroundCall(val identity: String, rate: Int) {
        val rates = CallAudioRates(rate)
        var offer: Long? = null
        var canOffer = true

        fun closeOffer(token: Long) {
            if (offer == null) return
            offer = null
            runCatching { SpikeShell.closeCallAudio(token) }
        }
    }

    suspend fun monitor(context: Context, token: Long, rate: Int, current: () -> Boolean, log: (String) -> Unit) {
        fun armed() = current() && RustCoreBridge.nativeCallAudioArmed(token)
        withArmedMonitor(::armed) { monitorCall(context, token, rate, ::armed, log) }
    }

    internal suspend fun withArmedMonitor(armed: () -> Boolean, monitor: suspend () -> Unit) {
        // The shell has one endpoint slot. Keep it until the previous monitor's cleanup finishes.
        monitorLock.withLock {
            if (armed()) monitor()
        }
    }

    private suspend fun monitorCall(context: Context, token: Long, rate: Int, armed: () -> Boolean, log: (String) -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val owner = Binder()
        var foreground: ForegroundCall? = null
        var offerId = 0L
        var nextBindAt = 0L
        var reportedUnavailable: String? = null
        fun diagnosticStatus() = runCatching { SpikeShell.callAudioStatus() }.getOrNull() ?: "shell_status_unavailable"

        try {
            while (armed()) {
                val inCall = audio.mode == AudioManager.MODE_IN_CALL
                val shellReady = inCall && ShizukuTools.isReady
                if (!shellReady) nextBindAt = 0L
                if (!inCall) reportedUnavailable = null
                if (shellReady && !SpikeShell.isConnected() && SystemClock.elapsedRealtime() >= nextBindAt) {
                    foreground?.closeOffer(token)
                    foreground = null
                    try {
                        SpikeShell.connect(context)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log("CALL-AUDIO: call audio service bind failed ${error.javaClass.simpleName}")
                    } finally {
                        nextBindAt = SystemClock.elapsedRealtime() + REBIND_INTERVAL_MS
                    }
                }
                if (!armed()) return
                val identity = if (shellReady) runCatching { SpikeShell.activeCallAudioIdentity() }.getOrNull() else null
                if (foreground?.identity != identity) {
                    if (foreground?.offer != null) log("CALL-AUDIO: phone call identity changed ${diagnosticStatus()}")
                    foreground?.closeOffer(token)
                    foreground = identity?.let { ForegroundCall(it, rate) }
                }
                val call = foreground
                if (call == null) {
                    if (inCall) {
                        val reason = if (shellReady) diagnosticStatus() else "shizuku_unavailable"
                        if (reportedUnavailable != reason) {
                            reportedUnavailable = reason
                            log("CALL-AUDIO: active cellular call identity unavailable $reason; keep phone call route")
                        }
                    }
                } else {
                    reportedUnavailable = null
                    call.offer?.let { offer ->
                        val retry = RustCoreBridge.nativeTakeCallAudioRetry(token, offer)
                        if (retry != 0) {
                            log("CALL-AUDIO: native requested retry=$retry ${diagnosticStatus()}")
                            val identityBlockedStart = SpikeShell.callAudioOpenNeedsIdentity()
                            call.closeOffer(token)
                            call.canOffer = if (identityBlockedStart) call.rates.retryIdentity() else call.rates.retry(retry)
                        }
                    }
                    if (call.canOffer && call.offer == null) {
                        while (armed() && audio.mode == AudioManager.MODE_IN_CALL) {
                            val captureRate = call.rates.currentRate
                            if (captureRate == null) {
                                call.canOffer = false
                                log("CALL-AUDIO: phone audio endpoint unavailable ${diagnosticStatus()}")
                                break
                            }
                            val socket = try {
                                SpikeShell.openCallAudio(owner, token, call.identity, captureRate)
                            } catch (error: Exception) {
                                call.canOffer = false
                                log("CALL-AUDIO: call audio service failed ${error.javaClass.simpleName}")
                                break
                            }
                            if (socket == null) {
                                if (SpikeShell.callAudioOpenNeedsIdentity()) break
                                call.rates.advance()
                                continue
                            }
                            try {
                                socket.use {
                                    if (armed() && RustCoreBridge.nativeOfferCallAudio(token, ++offerId, captureRate, it.detachFd())) {
                                        call.offer = offerId
                                        log("CALL-AUDIO: phone audio offered rate=$captureRate")
                                    }
                                }
                            } finally {
                                if (call.offer == null) runCatching { SpikeShell.closeCallAudio(token) }
                            }
                            // A rejected queue offer can be retried after the previous leg's cleanup.
                            break
                        }
                    }
                }
                delay(250)
            }
        } finally {
            foreground?.closeOffer(token)
        }
    }
}

internal class CallAudioRates(preferred: Int) {
    private val rates = listOf(preferred, 16_000, 24_000, 48_000, 44_100).distinct()
    private var index = 0
    private var retriedStream = false

    val currentRate: Int? get() = rates.getOrNull(index)

    fun advance() { index++ }

    fun retryIdentity(): Boolean = retry(RETRY_STREAM)

    fun retry(reason: Int): Boolean = when (reason) {
        RETRY_STREAM -> if (retriedStream) false else {
            retriedStream = true
            currentRate != null
        }
        RETRY_NEXT_RATE -> {
            advance()
            currentRate != null
        }
        else -> false
    }

    private companion object {
        const val RETRY_STREAM = 1
        const val RETRY_NEXT_RATE = 2
    }
}
