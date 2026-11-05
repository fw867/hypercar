package io.github.hypercar.session

import kotlinx.coroutines.CompletableDeferred

/** One native activation, ending at a real video TCP connection (not merely RECORD/connected).
 * Keep rejected callbacks contained until nativeStop has joined the old engine. */
internal class ProactiveActivation {
    val result = CompletableDeferred<String?>()
    private var ready = false
    @Volatile var rejected = false
        private set

    @Synchronized fun videoReady() {
        if (rejected) return
        ready = true
        result.complete(null)
    }

    @Synchronized fun interceptTerminal(detail: String): Boolean {
        if (ready) return false
        rejected = true
        result.complete(detail)
        return true
    }
}
