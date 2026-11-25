package io.github.hypercar.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** Allow Android's network metadata to settle and retain changes arriving during a check. */
internal class WifiReconnectSignals {
    private val pending = Channel<Unit>(Channel.CONFLATED)

    fun signal() { pending.trySend(Unit) }

    suspend fun collect(check: suspend () -> Unit) {
        for (signal in pending) {
            delay(750)
            pending.tryReceive() // Updates before the check are covered by that check.
            check()
        }
    }
}
