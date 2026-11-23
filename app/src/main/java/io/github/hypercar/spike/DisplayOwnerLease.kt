package io.github.hypercar.spike

/** Serializes display cleanup with creation and ignores callbacks from superseded clients. */
internal class DisplayOwnerLease<T>(
    private val lock: Any,
    private val releaseDisplays: () -> Unit,
) {
    private class Owner<T>(val token: T, val identity: Any, val unlink: () -> Unit)
    private var current: Owner<T>? = null

    fun bind(token: T, link: (onDeath: () -> Unit) -> (() -> Unit)?): Boolean = synchronized(lock) {
        if (current?.token == token) return@synchronized true
        val identity = Any()
        val unlink = link { died(identity) } ?: return@synchronized false
        clear()
        current = Owner(token, identity, unlink)
        true
    }

    private fun died(identity: Any) = synchronized(lock) {
        if (current?.identity === identity) clear()
    }

    private fun clear() {
        val old = current ?: return
        current = null
        old.unlink()
        releaseDisplays()
    }
}
