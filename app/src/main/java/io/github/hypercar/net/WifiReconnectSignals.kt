package io.github.hypercar.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Wake on verified readiness, otherwise coalesce metadata changes until the original deadline. */
internal class WifiReconnectSignals {
    private val pending = Channel<Unit>(Channel.CONFLATED)

    fun signal() { pending.trySend(Unit) }

    suspend fun collect(tryReady: () -> Boolean = { false }, check: suspend () -> Unit) {
        for (signal in pending) {
            withTimeoutOrNull(750) {
                var submitted = false
                while (true) {
                    if (!submitted) submitted = tryReady()
                    pending.receive()
                }
            }
            // A queued early wake can lose its network before admission. Keep the settled check.
            check()
        }
    }
}
