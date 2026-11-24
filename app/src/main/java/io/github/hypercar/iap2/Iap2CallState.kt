package io.github.hypercar.iap2

import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** AIS R32 table 95-48. No call identifiers or contact data are collected. */
internal enum class Iap2CallState(val wireValue: Int) {
    DISCONNECTED(0), SENDING(1), RINGING(2), ACTIVE(4), HELD(5), DISCONNECTING(6);

    companion object {
        private val preciseState = Regex("(?:Ringing|Foreground|Background) call state: (-?\\d+)")

        fun fromTelephonyRegistry(text: String): Iap2CallState? {
            val coarse = mutableListOf<Int>()
            val precise = mutableSetOf<Int>()
            for (line in text.lineSequence().take(16).map(String::trim)) {
                if (line.startsWith("mCallState=")) line.substringAfter('=').toIntOrNull()?.let(coarse::add)
                if (line.startsWith("mPreciseCallState=")) {
                    for (match in preciseState.findAll(line)) match.groupValues[1].toIntOrNull()?.let(precise::add)
                }
            }
            // AOSP PreciseCallState. Telephony's coarse OFFHOOK alone can mean dialing,
            // active or on hold. Report the most prominent state across cellular subscriptions.
            return when {
                5 in precise || 6 in precise || 1 in coarse -> RINGING
                3 in precise || 4 in precise -> SENDING
                1 in precise -> ACTIVE
                2 in precise -> HELD
                8 in precise -> DISCONNECTING
                2 in coarse -> null
                coarse.isNotEmpty() && coarse.all { it == 0 } -> DISCONNECTED
                precise.isNotEmpty() && precise.all { it == 0 || it == 7 } -> DISCONNECTED
                else -> null
            }
        }
    }
}

internal sealed interface Iap2CallSample {
    data object Pending : Iap2CallSample
    data object Unavailable : Iap2CallSample
    data class Known(val state: Iap2CallState) : Iap2CallSample
}

/** Keep shell I/O off the control worker and active only while this link has an authenticated subscriber. */
internal class Iap2CallStateSource {
    private class Observation {
        @Volatile var sample: Iap2CallSample = Iap2CallSample.Pending
        var job: Job? = null
    }

    private var observation: Observation? = null

    fun setSubscribed(subscribed: Boolean) {
        if (subscribed == (observation != null)) return
        if (!subscribed) {
            observation?.job?.cancel()
            observation = null
            return
        }
        val active = Observation()
        observation = active
        active.job = worker.launch {
            while (isActive) {
                val state = if (ShizukuTools.isReady) {
                    // Only whitelist state lines in the shell. Numbers, call history and logs stay on device.
                    val result = ShizukuTools.exec(
                        "timeout 1 dumpsys telephony.registry | grep -E '^[[:space:]]*m(CallState|PreciseCallState)=' | head -n 16",
                        1_500,
                    )
                    result.out.takeIf { result.code == 0 }?.let(Iap2CallState::fromTelephonyRegistry)
                } else null
                active.sample = state?.let { Iap2CallSample.Known(it) } ?: Iap2CallSample.Unavailable
                delay(1_000)
            }
        }
    }

    fun snapshot(): Iap2CallSample = observation?.sample ?: Iap2CallSample.Unavailable

    private companion object {
        val worker = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
