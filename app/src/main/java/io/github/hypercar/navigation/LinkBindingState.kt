package io.github.hypercar.navigation

/** One reader owns its retries and invalidates callbacks from an earlier binding. */
internal class LinkBindingState {
    private var generation = 0L
    private var closed = false
    var attempts = 0
        private set
    val retryDelayMillis: Long get() = (2000L * attempts.coerceAtLeast(1)).coerceAtMost(15_000)

    @Synchronized fun begin(): Long? {
        if (closed || attempts >= 8) return null
        attempts++
        return ++generation
    }
    @Synchronized fun accepts(token: Long): Boolean = !closed && generation == token
    @Synchronized fun registered(token: Long) { if (accepts(token)) attempts = 0 }
    @Synchronized fun mapStarted() { if (!closed) attempts = 0 }
    @Synchronized fun invalidate(token: Long) { if (accepts(token)) generation++ }
    @Synchronized fun close() { closed = true; generation++ }
}
