package io.github.hypercar.net

/** 注册请求不等于已广播；过期成功/失败回调均不得改动当前注册。 */
internal class ServiceRegistrationState {
    private var generation = 0L
    @Volatile var pending = false
        private set
    @Volatile var ready = false
        private set

    val currentGeneration: Long @Synchronized get() = generation

    /** Success has no application TTL. Pending requests alone have a bounded probe budget. */
    @Synchronized fun canReuse(sameIdentity: Boolean, ageMillis: Long, pendingTimeoutMillis: Long): Boolean =
        sameIdentity && (ready || (pending && ageMillis >= 0 && ageMillis < pendingTimeoutMillis))

    @Synchronized fun begin(): Long {
        generation++
        pending = true
        ready = false
        return generation
    }

    @Synchronized fun registered(token: Long): Boolean {
        if (token != generation || !pending) return false
        pending = false
        ready = true
        return true
    }

    @Synchronized fun failed(token: Long): Boolean {
        if (token != generation || (!pending && !ready)) return false
        invalidate()
        return true
    }

    @Synchronized fun invalidate() {
        generation++
        pending = false
        ready = false
    }
}
