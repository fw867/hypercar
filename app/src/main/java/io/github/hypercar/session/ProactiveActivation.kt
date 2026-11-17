package io.github.hypercar.session

import kotlinx.coroutines.CompletableDeferred

/** One native activation, ending at a real video TCP connection (not merely RECORD/connected).
 * Keep rejected callbacks contained until nativeStop has joined the old engine. */
internal class ProactiveActivation {
    val result = CompletableDeferred<String?>()
    private var ready = false
    @Volatile var requiresFreshInvitation = false
        private set
    @Volatile var rejected = false
        private set

    @Synchronized fun videoReady() {
        if (rejected) return
        ready = true
        result.complete(null)
    }

    @Synchronized fun interceptTerminal(detail: String): Boolean {
        if (ready) return false
        // A reachable/verified receiver can still have no active CarPlay window.
        // Replaying RECORD on that same network is not an activation mechanism.
        requiresFreshInvitation = true
        rejected = true
        result.complete(detail)
        return true
    }
}
