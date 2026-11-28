package io.github.hypercar.net

/** Atomic request ownership and association delivery, independent of mutable network capabilities. */
internal class WifiRequestAssociation<O : Any, N : Any>(private var attempt: Long = 0) {
    private var callback: O? = null
    private var association: N? = null
    private var finished = false
    // Keep the observer's attempt until the specifier delivers its matching onLost.
    private val monitoredLosses = mutableMapOf<N, ArrayDeque<Long>>()

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
    @Synchronized fun adopt(network: N, nextAttempt: Long, claimLease: () -> Boolean): Boolean {
        if (!owns(network) || !claimLease()) return false
        attempt = nextAttempt
        return true
    }

    @Synchronized fun monitorLost(monitorAttempt: Long, network: N) {
        if (attempt == monitorAttempt && association == network) {
            monitoredLosses.getOrPut(network) { ArrayDeque() }.addLast(attempt)
            association = null
        }
    }

    @Synchronized fun lost(owner: O, network: N, boundFallback: Boolean = false): Long? {
        if (callback !== owner) return null
        val monitored = monitoredLosses[network]?.let { pending ->
            val first = pending.removeFirst()
            if (pending.isEmpty()) monitoredLosses.remove(network)
            first
        }
        if (monitored != null && monitored != attempt) return null
        if (association != network && (association != null || (!boundFallback && monitored == null))) return null
        association = null
        return attempt
    }
    @Synchronized fun cancelPending(owner: O): O? {
        if (callback !== owner || association != null) return null
        return release()
    }
    @Synchronized fun release(): O? {
        val previous = callback
        callback = null
        association = null
        monitoredLosses.clear()
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
