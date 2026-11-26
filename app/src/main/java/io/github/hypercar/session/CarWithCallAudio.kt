package io.github.hypercar.session

import android.content.Context
import android.media.AudioManager
import android.os.Binder
import android.os.SystemClock
import io.github.hypercar.bluetooth.CarHfpAudio
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.CallAudioOfferEnd
import io.github.hypercar.spike.ICallAudioRoute
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

internal object CarWithCallAudio {
    private const val REBIND_INTERVAL_MS = 2_000L
    private val monitorLock = Mutex()

    private class Offer(val id: Long, val lease: String, val routeGeneration: Long?) {
        var withdrawn = false
            private set

        fun withdraw(token: Long) {
            if (withdrawn) return
            withdrawn = true
            runCatching { SpikeShell.revokeCallAudio(token, id) }
        }
    }

    private class ForegroundCall(val retryKey: String, rate: Int, var routeGeneration: Long?) {
        val rates = CallAudioRates(rate)
        var offer: Offer? = null
        var returnedFromHfpPending = false
    }

    suspend fun monitor(context: Context, token: Long, rate: Int, current: () -> Boolean,
                        receiver: String?, log: (String) -> Unit) {
        fun armed() = current() && RustCoreBridge.nativeCallAudioArmed(token)
        withArmedMonitor(::armed) {
            coroutineScope {
                val shellOffer = AtomicReference<Offer?>()
                val hfp = receiver?.takeIf { it.isNotBlank() }?.let { address ->
                    CarHfpAudio(context, address) { generation ->
                        val offer = shellOffer.get()
                        if (offer != null && offer.routeGeneration?.let { it < generation } == true) {
                            // Telecom reads on the monitor thread can still be in flight.
                            launch(Dispatchers.IO) { runCatching { SpikeShell.revokeCallAudio(token, offer.id) } }
                        }
                    }
                }
                try {
                    monitorCall(context, token, rate, ::armed, receiver, hfp, shellOffer, log)
                } finally {
                    hfp?.close()
                }
            }
        }
    }

    internal fun usesCarHfp(inCall: Boolean, receiver: String?, hasSco: (String) -> Boolean): Boolean =
        inCall && !receiver.isNullOrBlank() && hasSco(receiver)

    internal suspend fun withArmedMonitor(armed: () -> Boolean, monitor: suspend () -> Unit) {
        // The shell has one endpoint slot. Keep it until the previous monitor's cleanup finishes.
        monitorLock.withLock {
            if (armed()) monitor()
        }
    }

    private suspend fun monitorCall(context: Context, token: Long, rate: Int, armed: () -> Boolean,
                                    receiver: String?, bluetooth: CarHfpAudio?, shellOffer: AtomicReference<Offer?>,
                                    log: (String) -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val owner = Binder()
        var foreground: ForegroundCall? = null
        var offerId = 0L
        var nextBindAt = 0L
        var reportedUnavailable: String? = null
        var usingCarHfp = false
        var awaitingHfp = bluetooth != null
        var identityPolled = false
        fun diagnosticStatus() = runCatching { SpikeShell.callAudioStatus() }.getOrNull() ?: "shell_status_unavailable"
        fun flushIdentityDiagnostics() {
            if (!identityPolled) return
            identityPolled = false
            runCatching { SpikeShell.pollCallAudioIdentity(verify = false, log) }
        }
        suspend fun awaitRouteChange() {
            if (bluetooth == null) delay(250) else bluetooth.awaitChange(250)
        }

        try {
            while (armed()) {
                val inCall = audio.mode == AudioManager.MODE_IN_CALL
                if (inCall && awaitingHfp) {
                    awaitingHfp = false
                    bluetooth?.awaitInitialState()
                    continue
                }
                val hfp = usesCarHfp(inCall, receiver) { bluetooth?.isCallAudioConnected() == true }
                if (hfp != usingCarHfp) {
                    usingCarHfp = hfp
                    log(if (hfp) "CALL-AUDIO: target car SCO active; Android handles the call"
                        else "CALL-AUDIO: target car SCO no longer active")
                }
                foreground?.let { call ->
                    val offer = call.offer
                    val generation = bluetooth?.routeGeneration
                    if (hfp || call.routeGeneration != generation) call.returnedFromHfpPending = true
                    call.routeGeneration = generation
                    if (offer != null) {
                        if (hfp || offer.routeGeneration != generation) offer.withdraw(token)
                        val result = RustCoreBridge.nativeTakeCallAudioResult(token, offer.id)
                        if (result != 0) {
                            val status = SpikeShell.awaitCallAudioOfferStatus(token, offer.id)
                            log("CALL-AUDIO: native offer=${offer.id} result=$result ${status ?: "shell_status_unavailable"}")
                            call.rates.completeOffer(
                                result,
                                withdrawn = offer.withdrawn || offer.routeGeneration != bluetooth?.routeGeneration,
                                endpointEnd = CallAudioOfferEnd.fromStatus(status),
                            )
                            shellOffer.compareAndSet(offer, null)
                            call.offer = null
                            runCatching { SpikeShell.closeCallAudio(token) }
                        }
                    }
                }
                if (hfp) {
                    flushIdentityDiagnostics()
                    reportedUnavailable = null
                    awaitRouteChange()
                    continue
                }
                val shellReady = inCall && ShizukuTools.isReady
                if (!shellReady) nextBindAt = 0L
                if (!inCall) reportedUnavailable = null
                if (shellReady && !SpikeShell.isConnected() && SystemClock.elapsedRealtime() >= nextBindAt) {
                    foreground?.offer?.withdraw(token)
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
                val identity = if (shellReady) {
                    identityPolled = true
                    runCatching { SpikeShell.pollCallAudioIdentity(verify = true, log) }.getOrNull()
                } else {
                    flushIdentityDiagnostics()
                    null
                }
                val activeOffer = foreground?.offer
                if (activeOffer != null && activeOffer.lease != identity?.lease) {
                    if (!activeOffer.withdrawn) log("CALL-AUDIO: phone call identity changed ${diagnosticStatus()}")
                    activeOffer.withdraw(token)
                    awaitRouteChange()
                    continue
                }
                if (identity != null && foreground?.retryKey != identity.retryKey) {
                    foreground = ForegroundCall(identity.retryKey, rate, bluetooth?.routeGeneration)
                }
                val call = foreground
                if (identity == null || call == null) {
                    if (inCall) {
                        val reason = if (shellReady) diagnosticStatus() else "shizuku_unavailable"
                        if (reportedUnavailable != reason) {
                            reportedUnavailable = reason
                            log("CALL-AUDIO: active cellular call identity unavailable $reason; keep phone call route")
                        }
                    }
                } else {
                    reportedUnavailable = null
                    if (call.offer == null && call.returnedFromHfpPending) {
                        call.returnedFromHfpPending = false
                        call.rates.resumeAfterHfp()
                    }
                    if (call.rates.canOffer && call.offer == null) {
                        while (armed() && audio.mode == AudioManager.MODE_IN_CALL) {
                            val routeGeneration = bluetooth?.routeGeneration
                            fun routeAllowsStart() = armed() && audio.mode == AudioManager.MODE_IN_CALL &&
                                (bluetooth == null || bluetooth.mayStart(checkNotNull(routeGeneration)))
                            if (!routeAllowsStart()) break
                            val captureRate = call.rates.currentRate
                            if (captureRate == null) {
                                call.rates.reject()
                                log("CALL-AUDIO: phone audio endpoint unavailable ${diagnosticStatus()}")
                                break
                            }
                            val offer = Offer(++offerId, identity.lease, routeGeneration)
                            val route = object : ICallAudioRoute.Stub() {
                                override fun mayStart(): Boolean {
                                    val caller = Binder.clearCallingIdentity()
                                    return try { routeAllowsStart() }
                                    finally { Binder.restoreCallingIdentity(caller) }
                                }
                            }
                            shellOffer.set(offer)
                            try {
                                val socket = try {
                                    SpikeShell.openCallAudio(owner, token, offer.id, identity.lease, captureRate, route)
                                } catch (error: Exception) {
                                    call.rates.reject()
                                    log("CALL-AUDIO: call audio service failed ${error.javaClass.simpleName}")
                                    break
                                }
                                if (socket == null) {
                                    if (!routeAllowsStart() || SpikeShell.callAudioOpenNeedsIdentity()) break
                                    call.rates.advance()
                                    continue
                                }
                                socket.use {
                                    if (routeAllowsStart() &&
                                        RustCoreBridge.nativeOfferCallAudio(token, offer.id, captureRate, it.detachFd())) {
                                        call.offer = offer
                                        log("CALL-AUDIO: phone audio offered token=$token offer=${offer.id} rate=$captureRate")
                                    }
                                }
                                break
                            } finally {
                                if (call.offer == null) {
                                    shellOffer.compareAndSet(offer, null)
                                    runCatching { SpikeShell.closeCallAudio(token) }
                                }
                            }
                        }
                    }
                }
                awaitRouteChange()
            }
        } finally {
            shellOffer.set(null)
            if (foreground?.offer != null) runCatching { SpikeShell.closeCallAudio(token) }
            flushIdentityDiagnostics()
        }
    }
}

internal class CallAudioRates(preferred: Int) {
    private val rates = listOf(preferred, 16_000, 24_000, 48_000, 44_100).distinct()
    private var index = 0
    private var retriedStream = false
    private var retriedAfterHfp = false
    var canOffer = true
        private set

    val currentRate: Int? get() = rates.getOrNull(index)

    fun advance() { index++ }

    fun reject() { canOffer = false }

    fun completeOffer(result: Int, withdrawn: Boolean, endpointEnd: CallAudioOfferEnd = CallAudioOfferEnd.OTHER) {
        when {
            result == OFFER_REJECTED -> reject()
            withdrawn || endpointEnd == CallAudioOfferEnd.WITHDRAWN -> return
            result == OFFER_WITHDRAWN || endpointEnd == CallAudioOfferEnd.IDENTITY_UNCONFIRMED ->
                canOffer = retry(RETRY_STREAM)
            else -> canOffer = retry(result)
        }
    }

    fun resumeAfterHfp() {
        if ((!canOffer || currentRate == null) && retryAfterHfp()) canOffer = true
    }

    fun retryAfterHfp(): Boolean {
        if (retriedAfterHfp) return false
        retriedAfterHfp = true
        index = 0
        retriedStream = false
        return true
    }

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
        // Keep these in sync with OfferResult in call_audio.rs.
        const val OFFER_REJECTED = 3
        const val OFFER_WITHDRAWN = 4
    }
}
