package io.github.hypercar.navigation

/** Native engine token and cue lifetime are updated under one lock. */
internal class CarWithNavigationState {
    // Structured guidance is event driven: unchanged turns stay valid until cancel/reset/close.
    private val store = NavigationCueStore(maxAgeMillis = Long.MAX_VALUE)
    private var counter = 0L
    private var token = 0L
    @Synchronized fun begin(): Long { token = ++counter; store.clear(); return token }
    @Synchronized fun accepts(sourceToken: Long): Boolean = sourceToken != 0L && sourceToken == token
    @Synchronized fun update(sourceToken: Long, cue: NavigationCue?, now: Long): Boolean {
        if (!accepts(sourceToken)) return false
        if (cue == null || cue.state == 0) store.clear()
        else store.put(store.generation(), "carwith", cue, now)
        return true
    }
    @Synchronized fun clear() { token = 0; store.clear() }
    fun snapshot(now: Long): NavigationCue? = store.snapshot(now)
}
