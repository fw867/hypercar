package io.github.hypercar.iap2

import java.util.concurrent.atomic.AtomicReference

internal object PeerDisconnectCleanup {
    fun <T : Any> capture(reference: AtomicReference<T?>, matches: (T) -> Boolean, close: (T) -> Unit): (() -> Unit)? {
        val expected = reference.get()?.takeIf(matches) ?: return null
        return { if (reference.compareAndSet(expected, null)) close(expected) }
    }
}
