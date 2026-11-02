package io.github.hypercar.net

import java.io.Closeable
import java.io.IOException

/** Captures one session's network; cancelling it never changes another session's lease. */
internal class SessionSocketLease<N>(val network: N) {
    private val lock = Any()
    @Volatile private var active = true

    fun invalidate() = synchronized(lock) { active = false }

    private fun checkActive() {
        if (!active) throw IOException("session socket lease closed")
    }

    /** Bind before connect, and close every socket that is not handed to the native owner. */
    fun <S : Closeable, R> open(
        create: () -> S,
        bind: (N, S) -> Unit,
        connect: (S) -> Unit,
        handoff: (S) -> R,
    ): R {
        checkActive()
        val socket = create()
        try {
            bind(network, socket)
            checkActive()
            connect(socket)
            // Invalidation may happen while connect blocks. Never hand that socket to Rust.
            return synchronized(lock) {
                checkActive()
                handoff(socket)
            }
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }
}
