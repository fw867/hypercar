package io.github.hypercar.iap2

import java.util.concurrent.atomic.AtomicReference

internal object PeerDisconnectCleanup {
    fun <T : Any> capture(reference: AtomicReference<T?>, matches: (T) -> Boolean, disconnected: (T) -> Boolean, close: (T) -> Unit): (() -> Unit)? {
        val expected = reference.get() ?: return null
        if (!matches(expected) || !disconnected(expected)) return null
        return { if (reference.compareAndSet(expected, null)) close(expected) }
    }
}
