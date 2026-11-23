package io.github.hypercar.carui

internal class CarUiFrameWatchdog {
    private var lastSource = -1L
    private var sourceBaseline = 0L
    private var writtenBaseline = 0L
    private var lastProgressAt = 0L
    private var hasWritten = false
    private var retryAt = 0L
    private var failedRestarts = 0

    fun observe(now: Long, eligible: Boolean, source: Long, written: Long): Boolean {
        if (!eligible || lastSource < 0 || source < lastSource || written < writtenBaseline) {
            lastSource = if (eligible) source else -1
            sourceBaseline = source
            writtenBaseline = written
            hasWritten = false
            lastProgressAt = now
            return false
        }
        if (source > sourceBaseline && written > writtenBaseline && !hasWritten) {
            hasWritten = true
            lastProgressAt = now
        }
        if (source > lastSource) {
            lastSource = source
            lastProgressAt = now
        }
        return hasWritten && now >= retryAt && now - lastProgressAt >= STALL_MS
    }

    fun restarted(now: Long) {
        restartFinished(now, rebuilt = true)
    }

    fun restartFinished(now: Long, rebuilt: Boolean) {
        lastSource = -1L
        retryAt = now + COOLDOWN_MS
        failedRestarts = if (rebuilt) 0 else failedRestarts + 1
    }

    fun shouldRetry(now: Long): Boolean =
        failedRestarts in 1 until MAX_RESTARTS && now >= retryAt

    private companion object {
        const val STALL_MS = 8_000L
        const val COOLDOWN_MS = 30_000L
        const val MAX_RESTARTS = 3
    }
}
