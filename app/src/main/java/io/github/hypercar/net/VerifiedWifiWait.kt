package io.github.hypercar.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Network callbacks and address/capability publication need not arrive together. */
internal object VerifiedWifiWait {
    suspend fun <T> await(
        waitMs: Long,
        now: () -> Long,
        current: () -> Boolean,
        probe: suspend () -> T?,
    ): T? {
        val started = now()
        suspend fun checkedProbe(): T? {
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("Wi-Fi request replaced")
            val value = probe()
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("Wi-Fi request replaced")
            return value
        }
        if (waitMs <= 0) return checkedProbe() // final non-waiting check at the system deadline
        return withTimeoutOrNull(waitMs) {
            var value = checkedProbe()
            while (value == null && now() - started < waitMs) {
                delay(minOf(200L, waitMs - (now() - started)))
                value = checkedProbe()
            }
            value
        }
    }
}
