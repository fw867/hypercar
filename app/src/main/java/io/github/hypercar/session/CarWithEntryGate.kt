package io.github.hypercar.session

/** Local CarWith launches belong to the full connection attempt, across AirPlay engine restarts. */
internal class CarWithEntryGate {
    private var owner = 0L
    private var reopenClaimed = false

    @Synchronized fun begin(attempt: Long) {
        owner = attempt
        reopenClaimed = false
    }

    @Synchronized fun retire(attempt: Long) {
        if (owner == attempt) owner = 0L
    }

    @Synchronized fun current(attempt: Long) = attempt > 0 && owner == attempt

    @Synchronized fun claimReopen(attempt: Long): Boolean {
        if (!current(attempt) || reopenClaimed) return false
        reopenClaimed = true
        return true
    }

    suspend fun launch(attempt: Long, shell: suspend () -> Boolean, fallback: () -> Unit): Boolean {
        if (!current(attempt)) return false
        if (shell()) return true
        // Serialize only the intent submission with retirement, never the wait for Shizuku.
        synchronized(this) {
            if (!current(attempt)) return false
            fallback()
            return true
        }
    }
}
