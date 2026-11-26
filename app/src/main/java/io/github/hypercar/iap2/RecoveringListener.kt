package io.github.hypercar.iap2

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** 每轮启动单独持有资源；旧轮次的关闭不会碰到新轮次的监听 socket。 */
internal class RecoveringListener<S : Closeable, C : Closeable>(
    private val ownerAlive: () -> Boolean,
    private val open: () -> S,
    private val accept: (S) -> C,
    private val onFailure: (FailureStage, IOException) -> Unit,
    private val awaitRetry: () -> Unit,
    private val onRegistered: () -> Unit = {},
) : Closeable {
    enum class FailureStage { REGISTER, ACCEPT }

    data class Diagnostics(
        val registrations: Int = 0,
        val registerFailures: Int = 0,
        val acceptFailures: Int = 0,
        val lastFailure: FailureStage? = null,
    )

    // Only the accept worker updates these counters. Readers get one immutable snapshot.
    @Volatile var diagnostics = Diagnostics()
        private set
    @Volatile private var stopped = false
    private val socket = AtomicReference<S?>(null)
    val isRegistered: Boolean get() = active() && socket.get() != null
    private fun active() = !stopped && ownerAlive()

    fun acceptNext(): C? {
        while (active()) {
            var stage = FailureStage.REGISTER
            try {
                val listener = socket.get() ?: socketOperation(open).let { created ->
                    if (!active()) { runCatching { created.close() }; return null }
                    if (socket.compareAndSet(null, created)) {
                        if (!active()) { close(); return null }
                        diagnostics = diagnostics.copy(registrations = diagnostics.registrations + 1)
                        onRegistered()
                    } else runCatching { created.close() }
                    socket.get()
                } ?: continue
                if (!active()) { close(); return null }
                stage = FailureStage.ACCEPT
                val connection = socketOperation { accept(listener) }
                if (!active()) { runCatching { connection.close() }; close(); return null }
                return connection
            } catch (error: IOException) {
                socket.getAndSet(null)?.let { runCatching { it.close() } }
                if (!active()) return null
                diagnostics = when (stage) {
                    FailureStage.REGISTER -> diagnostics.copy(registerFailures = diagnostics.registerFailures + 1, lastFailure = stage)
                    FailureStage.ACCEPT -> diagnostics.copy(acceptFailures = diagnostics.acceptFailures + 1, lastFailure = stage)
                }
                onFailure(stage, error)
                awaitRetry()
            }
        }
        close()
        return null
    }

    private inline fun <T> socketOperation(operation: () -> T): T = try {
        operation()
    } catch (error: RuntimeException) {
        throw IOException("蓝牙监听 socket 操作失败：${error.javaClass.simpleName}", error)
    }

    override fun close() {
        stopped = true
        socket.getAndSet(null)?.let { runCatching { it.close() } }
    }
}
