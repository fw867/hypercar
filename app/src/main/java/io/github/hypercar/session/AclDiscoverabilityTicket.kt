package io.github.hypercar.session

import java.util.concurrent.atomic.AtomicLong

/** A single classic ACL edge may authorize one window in one connection attempt. */
internal class AclDiscoverabilityTicket(
    val peer: String,
    private val receivedAt: Long,
    private val target: String?,
    private val profile: String,
    private val autoModeRevision: Long,
) {
    // 0: not assigned; positive: owning attempt; negative: consumed by that attempt.
    private val owner = AtomicLong(0)

    fun isFresh(now: Long, target: String?, profile: String, autoModeRevision: Long): Boolean =
        now - receivedAt in 0 until 10_000L && this.target.equals(target, ignoreCase = true) &&
            this.profile == profile && this.autoModeRevision == autoModeRevision

    fun assign(attempt: Long) {
        if (attempt > 0) owner.compareAndSet(0, attempt)
    }

    fun pendingFor(attempt: Long): Boolean = attempt > 0 && owner.get() == attempt

    fun take(attempt: Long): Boolean = attempt > 0 && owner.compareAndSet(attempt, -attempt)

    fun takenBy(attempt: Long): Boolean = attempt > 0 && owner.get() == -attempt
}
