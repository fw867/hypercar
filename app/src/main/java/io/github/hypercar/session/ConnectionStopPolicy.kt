package io.github.hypercar.session

import io.github.hypercar.core.LinkState

/** Evaluate after acquiring the session-control mutex, before changing persisted cooldown. */
internal object ConnectionStopPolicy {
    enum class Decision { IGNORE_STALE, IGNORE_INACTIVE, STOP, STOP_AND_PAUSE }

    fun decide(
        userInitiated: Boolean,
        expectedAttempt: Long,
        currentAttempt: Long,
        sessionActive: Boolean,
        link: LinkState,
        autoConnect: Boolean,
    ): Decision = when {
        expectedAttempt != currentAttempt -> Decision.IGNORE_STALE
        userInitiated && (expectedAttempt <= 0 || !sessionActive ||
            (link !is LinkState.Negotiating && link !is LinkState.Connected)) -> Decision.IGNORE_INACTIVE
        userInitiated && autoConnect -> Decision.STOP_AND_PAUSE
        else -> Decision.STOP
    }
}
