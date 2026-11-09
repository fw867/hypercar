package io.github.hypercar.session

/** One recovery chain per receiver; brief control connections do not replenish it. */
internal class ReconnectBudget {
    data class Retry(val number: Int, val delayMs: Long)
    private var peer: String? = null
    private var failures = 0
    private var recovering = false
    private var healthySince: Long? = null

    @Synchronized fun start(receiver: String?, manual: Boolean = false) {
        if (manual || (receiver != null && peer != null && peer != receiver)) clear()
        if (receiver != null) peer = receiver
    }

    /** Called only after the current native pipeline has actually written video. */
    @Synchronized fun videoReady(receiver: String?, now: Long) {
        start(receiver)
        if (healthySince == null) healthySince = now
    }

    @Synchronized fun failed(receiver: String?, hadLink: Boolean, now: Long): Retry? {
        start(receiver)
        val stable = healthySince?.let { now >= it && now - it >= STABLE_MS } == true
        if (stable) failures = 0
        healthySince = null
        if (!hadLink && !recovering) return null
        recovering = true
        if (failures >= DELAYS.size) return null
        return Retry(failures + 1, DELAYS[failures++])
    }

    @Synchronized fun mayWake(receiver: String?): Boolean =
        (receiver != null && peer != null && receiver != peer) || failures < DELAYS.size

    @Synchronized fun clear() {
        peer = null; failures = 0; recovering = false; healthySince = null
    }

    companion object {
        const val STABLE_MS = 120_000L
        const val MAX_RETRIES = 5
        private val DELAYS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
    }
}
