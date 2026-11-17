package io.github.hypercar.net

import java.util.concurrent.atomic.AtomicReference

/** Equal peer addresses are distinct invitations; only consume the exact observed object. */
internal class PendingInvitation<T : Any> {
    private val pending = AtomicReference<T?>(null)
    fun get(): T? = pending.get()
    fun set(value: T?) = pending.set(value)
    fun discard(expected: T): Boolean = pending.compareAndSet(expected, null)
}
