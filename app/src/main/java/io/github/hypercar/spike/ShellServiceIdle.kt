package io.github.hypercar.spike

/** The resource check runs outside this lock. A concurrent transaction invalidates its result. */
internal class ShellServiceIdle(startedAt: Long, private val timeoutMs: Long) {
    private var lastUse = startedAt
    private var inFlight = 0
    private var stopping = false

    @Synchronized fun begin(): Boolean {
        if (stopping) return false
        inFlight++
        return true
    }

    @Synchronized fun end(now: Long) {
        inFlight--
        lastUse = maxOf(lastUse, now)
    }

    @Synchronized fun noteBusy(now: Long) {
        lastUse = maxOf(lastUse, now)
    }

    @Synchronized fun eligibleAt(now: Long): Boolean = !stopping && inFlight == 0 && now - lastUse >= timeoutMs

    @Synchronized fun tryRetire(checkedAt: Long): Boolean {
        if (!eligibleAt(checkedAt)) return false
        stopping = true
        return true
    }

    @Synchronized fun stop() {
        stopping = true
    }
}
