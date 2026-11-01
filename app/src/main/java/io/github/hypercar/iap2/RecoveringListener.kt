package io.github.hypercar.iap2

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** 每轮启动单独持有资源；旧轮次的关闭不会碰到新轮次的监听 socket。 */
internal class RecoveringListener<S : Closeable, C : Closeable>(
    private val ownerAlive: () -> Boolean,
    private val open: () -> S,
    private val accept: (S) -> C,
    private val onFailure: (IOException) -> Unit,
    private val awaitRetry: () -> Unit,
) : Closeable {
    @Volatile private var stopped = false
    private val socket = AtomicReference<S?>(null)
    val isRegistered: Boolean get() = active() && socket.get() != null
    private fun active() = !stopped && ownerAlive()

    fun acceptNext(): C? {
        while (active()) {
            try {
                val listener = socket.get() ?: open().let { created ->
                    if (!active()) { runCatching { created.close() }; return null }
                    if (!socket.compareAndSet(null, created)) runCatching { created.close() }
                    socket.get()
                } ?: continue
                if (!active()) { close(); return null }
                val connection = accept(listener)
                if (!active()) { runCatching { connection.close() }; close(); return null }
                return connection
            } catch (error: IOException) {
                socket.getAndSet(null)?.let { runCatching { it.close() } }
                if (!active()) return null
                onFailure(error)
                awaitRetry()
            }
        }
        close()
        return null
    }

    override fun close() {
        stopped = true
        socket.getAndSet(null)?.let { runCatching { it.close() } }
    }
}
