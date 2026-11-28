package io.github.hypercar.iap2

import java.io.Closeable
import java.util.concurrent.Future

/** Called under the transport's admission lock. A retained socket has a single owner
 * until the replacement worker claims it or the connection attempt is cancelled. */
internal class BmwCaffHandoff<S : Closeable>(
    val old: S,
    val incoming: S,
    private val onReady: (BmwCaffHandoff<S>) -> Unit,
) {
    private var released = false
    private var deadline: Future<*>? = null
    var isHeld = true
        private set
    val waitingForExit: Boolean get() = isHeld && !released

    fun setDeadline(timer: Future<*>) {
        if (waitingForExit) deadline = timer else timer.cancel(false)
    }

    fun exited(socket: S) {
        if (socket !== old || !waitingForExit) return
        released = true
        cancelDeadline()
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
        cancelDeadline()
        runCatching { incoming.close() }
    }

    private fun cancelDeadline() {
        deadline?.cancel(false)
        deadline = null
    }
}

/** All operations, including the worker's claim, run under the Bluetooth admission lock. */
internal class BmwCafeReservations<S : Closeable> {
    var pending: BmwCaffHandoff<S>? = null
        private set
    private var pendingAttempt = 0L
    private var retiredThrough = 0L
    private var invitedAttempt = 0L
    private var invitationCurrent: (() -> Boolean)? = null
    private var inviteMatches: ((S) -> Boolean)? = null

    fun reserve(attempt: Long, old: S, incoming: S, onReady: (BmwCaffHandoff<S>) -> Unit): Boolean {
        if (attempt != 0L && attempt <= retiredThrough || pending != null) return false
        if (invitationOwns(attempt, incoming)) return false
        pendingAttempt = attempt
        pending = BmwCaffHandoff(old, incoming, onReady)
        return true
    }

    fun retire(attempt: Long, matchesPeer: (S) -> Boolean = { true }, currentInvite: (() -> Boolean)? = null): Boolean {
        if (currentInvite == null) {
            retiredThrough = maxOf(retiredThrough, attempt)
        } else {
            if (attempt < invitedAttempt || !currentInvite()) return false
            invitedAttempt = attempt
            invitationCurrent = currentInvite
            inviteMatches = matchesPeer
        }
        val held = pending ?: return false
        val owns = if (currentInvite == null) pendingAttempt == attempt else invitationOwns(pendingAttempt, held.incoming)
        if (!owns || !held.isHeld) return false
        clear()
        return true
    }

    fun canClaim(held: BmwCaffHandoff<S>): Boolean =
        pending === held && !invitationOwns(pendingAttempt, held.incoming)

    private fun invitationOwns(attempt: Long, socket: S): Boolean =
        attempt <= invitedAttempt &&
            invitationCurrent?.invoke() == true && inviteMatches?.invoke(socket) == true

    fun expire(held: BmwCaffHandoff<S>, releaseOwner: (S) -> Boolean): Boolean {
        if (pending !== held || !held.waitingForExit) return false
        if (releaseOwner(held.old)) exited(held.old) else clear()
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
        val held = pending
        pending = null
        held?.cancel()
    }
}

/** Null leaves normal admission in charge. False means a matching handoff was already retired or held. */
internal fun <S : Closeable> BmwCafeReservations<RfcommTransport<S>>.reserveAfterPeerClose(
    attempt: Long,
    old: RfcommTransport<S>?,
    incoming: RfcommTransport<S>,
    sameBondedPeer: (S, S) -> Boolean,
    onReady: (BmwCaffHandoff<RfcommTransport<S>>) -> Unit,
): Boolean? {
    if (old == null || !old.endedByIo || !sameBondedPeer(old.socket, incoming.socket)) return null
    return reserve(attempt, old, incoming, onReady)
}
