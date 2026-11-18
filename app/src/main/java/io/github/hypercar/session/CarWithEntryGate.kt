package io.github.hypercar.session

/** Local CarWith launches belong to the full connection attempt, across AirPlay engine restarts. */
internal class CarWithEntryGate(private val stopDialer: (Long) -> Unit) {
    private var owner = 0L
    private var reopenClaimed = false

    @Synchronized fun begin(attempt: Long) {
        owner = attempt
        reopenClaimed = false
    }

    fun retire(attempt: Long) {
        synchronized(this) {
            if (owner == 0L || owner != attempt) return
            owner = 0L
        }
        stopDialer(attempt)
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
