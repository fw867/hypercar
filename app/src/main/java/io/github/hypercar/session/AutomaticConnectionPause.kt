package io.github.hypercar.session

/** Persist both clocks: elapsed time survives process restarts and ignores wall-clock adjustments. */
data class AutomaticConnectionPause(val boot: Int, val elapsedDeadline: Long, val wallDeadline: Long) {
    fun remaining(bootNow: Int, elapsedNow: Long, wallNow: Long): Long =
        ((if (boot >= 0 && boot == bootNow) elapsedDeadline - elapsedNow else wallDeadline - wallNow)
            .coerceIn(0L, DURATION_MILLIS))

    fun encode() = "$boot|$elapsedDeadline|$wallDeadline"

    companion object {
        const val DURATION_MILLIS = 10 * 60 * 1000L
        fun start(boot: Int, elapsed: Long, wall: Long) =
            AutomaticConnectionPause(boot, elapsed + DURATION_MILLIS, wall + DURATION_MILLIS)
        fun decode(value: String): AutomaticConnectionPause? {
            val parts = value.split('|')
            if (parts.size != 3) return null
            val boot = parts[0].toIntOrNull() ?: return null
            val elapsed = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val wall = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return AutomaticConnectionPause(boot, elapsed, wall)
        }
    }
}
