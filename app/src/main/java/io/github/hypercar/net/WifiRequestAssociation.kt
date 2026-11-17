package io.github.hypercar.net

/** Atomic request ownership and association delivery, independent of mutable network capabilities. */
internal class WifiRequestAssociation<O : Any, N : Any> {
    private var callback: O? = null
    private var association: N? = null
    private var finished = false

    @Synchronized fun register(owner: O): Boolean {
        if (finished || callback != null) return false
        callback = owner
        return true
    }
    @Synchronized fun owner(): O? = callback
    @Synchronized fun network(): N? = association
    @Synchronized fun owns(network: N): Boolean = callback != null && association == network
    @Synchronized fun available(owner: O, network: N): Boolean {
        if (callback !== owner) return false
        association = network
        return true
    }
    @Synchronized fun unavailable(owner: O): Boolean {
        return callback === owner && association == null
    }
    @Synchronized fun lost(owner: O, network: N, boundFallback: Boolean = false): Boolean {
        if (callback !== owner || (association != network && (association != null || !boundFallback))) return false
        association = null
        return true
    }
    @Synchronized fun cancelPending(owner: O): O? {
        if (callback !== owner || association != null) return null
        callback = null
        return owner
    }
    @Synchronized fun release(): O? {
        val previous = callback
        callback = null
        association = null
        return previous
    }

    /** Decide under the same lock as onAvailable. Run Android cleanup after releasing the lock. */
    fun finish(keepConnected: Boolean, cleanup: (O?) -> Unit): N? {
        val detached = synchronized(this) {
            if (keepConnected) association?.let { return it }
            finished = true
            release()
        }
        cleanup(detached)
        return null
    }
}
