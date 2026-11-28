package io.github.hypercar.net

import java.io.Closeable
import java.io.IOException

/** Captures one session's network; cancelling it never changes another session's lease. */
internal class SessionSocketLease<N>(val network: N, rfcommRegistration: Long? = null) {
    private val lock = Any()
    @Volatile private var active = true
    val isActive: Boolean get() = active
    @Volatile private var transportEnded = false
    val blocksBluetoothInbound: Boolean get() = active && !transportEnded
    @Volatile var rfcommRegistration: Long? = rfcommRegistration
        private set

    fun noteTransportEnded() = synchronized(lock) { transportEnded = true }

    fun endedWithRfcomm(registration: Long): Boolean = transportEnded && rfcommRegistration == registration

    fun bindRfcomm(registration: Long) = synchronized(lock) {
        if (active && !transportEnded) rfcommRegistration = registration
    }

    fun invalidate() = synchronized(lock) { active = false }

    private fun checkActive() {
        if (!active) throw IOException("session socket lease closed")
    }

    /** handoff 必须导出独立 FD；原 Java socket 成功失败都关闭（本项目 minSdk=31）。 */
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
        } finally {
            runCatching { socket.close() }
        }
    }
}
