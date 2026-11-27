package io.github.hypercar.iap2

import java.io.Closeable

/** Called under the transport's admission lock. A retained socket has a single owner
 * until the replacement worker claims it or the connection attempt is cancelled. */
internal class BmwCaffHandoff<S : Closeable>(
    val old: S,
    val incoming: S,
    private val onReady: (BmwCaffHandoff<S>) -> Unit,
) {
    private var released = false
    var isHeld = true
        private set

    fun exited(socket: S) {
        if (socket !== old || !isHeld || released) return
        released = true
        onReady(this)
    }

    fun claim(socket: S): Boolean {
        if (socket !== incoming || !isHeld || !released) return false
        isHeld = false
        return true
    }

    fun cancel() {
        if (!isHeld) return
        isHeld = false
        runCatching { incoming.close() }
    }
}

/** All operations, including the worker's claim, run under the Bluetooth admission lock. */
internal class BmwCafeReservations<S : Closeable> {
    var pending: BmwCaffHandoff<S>? = null
        private set
    private var pendingAttempt = 0L
    private var retiredThrough = 0L
    private var invitedAttempt = 0L
    private var invitedNetworkCurrent: (() -> Boolean)? = null

    fun reserve(attempt: Long, old: S, incoming: S, onReady: (BmwCaffHandoff<S>) -> Unit): Boolean {
        if (attempt <= retiredThrough || pending != null) return false
        if (attempt == invitedAttempt && invitedNetworkCurrent?.invoke() == true) return false
        pendingAttempt = attempt
        pending = BmwCaffHandoff(old, incoming, onReady)
        return true
    }

    fun retire(attempt: Long, networkCurrent: (() -> Boolean)? = null): Boolean {
        if (networkCurrent == null) {
            retiredThrough = maxOf(retiredThrough, attempt)
        } else {
            if (attempt < invitedAttempt || !networkCurrent()) return false
            invitedAttempt = attempt
            invitedNetworkCurrent = networkCurrent
        }
        val held = pending ?: return false
        if (pendingAttempt != attempt || !held.isHeld) return false
        held.cancel()
        return true
    }

    fun exited(old: S) {
        val held = pending ?: return
        if (held.old !== old) return
        held.exited(old)
        if (!held.isHeld) finish(held)
    }

    fun finish(held: BmwCaffHandoff<S>) {
        if (pending === held) pending = null
    }

    fun clear() {
        pending?.cancel()
        pending = null
    }
}
