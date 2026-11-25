package io.github.hypercar.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Network callbacks and address/capability publication need not arrive together. */
internal object VerifiedWifiWait {
    const val ASSOCIATED_ADDRESS_WAIT_MS = 12_000L
    enum class CallbackWait { SIGNAL, TIMEOUT, HINT_EXPIRED, SUPERSEDED }

    fun addressBudget(remainingMs: Long, associated: Boolean): Long =
        remainingMs.coerceIn(0L, if (associated) ASSOCIATED_ADDRESS_WAIT_MS else 3_000L)

    suspend fun awaitCallback(
        waitMs: Long,
        now: () -> Long,
        credentialsCurrent: () -> Boolean,
        poll: suspend (Long) -> Boolean,
        hintCheckMillis: Long? = null,
        keepHint: () -> Boolean = { true },
        onWaiting: () -> Unit = {},
    ): CallbackWait {
        val deadline = now() + waitMs
        var checkpoint = hintCheckMillis?.takeIf { it < deadline }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!credentialsCurrent()) return CallbackWait.SUPERSEDED
            val time = now()
            if (time >= deadline) return CallbackWait.TIMEOUT
            val check = checkpoint
            if (check != null && time >= check) {
                checkpoint = null
                if (!keepHint()) return CallbackWait.HINT_EXPIRED
                continue
            }
            if (poll(minOf(200L, (check ?: deadline) - time))) {
                return if (credentialsCurrent()) CallbackWait.SIGNAL else CallbackWait.SUPERSEDED
            }
            onWaiting()
        }
    }

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
