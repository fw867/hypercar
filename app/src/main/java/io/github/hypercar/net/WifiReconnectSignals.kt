package io.github.hypercar.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Wake on verified readiness, otherwise coalesce metadata changes until the original deadline. */
internal class WifiReconnectSignals {
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private val associations = mutableMapOf<Long, Association>()
    private var retired = false
    private var deferredWhileActive = false

    private class Association {
        val identity = Any()
        val attempts = mutableMapOf<String, Attempts>()
    }

    private class Attempts {
        val addresses = mutableSetOf<Set<String>>()
        var count = 0
        var latest: ExtraWakeClaim? = null
        var latestAddresses: Set<String>? = null
    }

    class ExtraWakeClaim internal constructor(val networkHandle: Long, internal val association: Any)

    @Synchronized fun signal() {
        if (!retired) pending.trySend(Unit)
    }

    @Synchronized fun available(networkHandle: Long) {
        if (retired) return
        associations.getOrPut(networkHandle, ::Association)
        signal()
    }

    @Synchronized fun lost(networkHandle: Long) {
        associations.remove(networkHandle)
        signal()
    }

    @Synchronized fun deferWhile(blocked: () -> Boolean): Boolean {
        if (retired) return true
        if (!blocked()) return false
        deferredWhileActive = true
        return true
    }

    @Synchronized fun replayForIdle() {
        if (!deferredWhileActive) return
        deferredWhileActive = false
        signal()
    }

    @Synchronized fun retire() {
        retired = true
        deferredWhileActive = false
        associations.clear()
    }

    @Synchronized fun claimExtraWake(receiver: String, networkHandle: Long, addresses: Set<String>,
        releasedLeaseRetry: ExtraWakeClaim? = null): ExtraWakeClaim? {
        if (addresses.isEmpty()) return null
        val association = associations[networkHandle] ?: return null
        val attempts = association.attempts.getOrPut(receiver, ::Attempts)
        if (attempts.count >= MAX_EXTRA_ATTEMPTS) return null
        val sameEvidenceRetry = releasedLeaseRetry != null && attempts.latest === releasedLeaseRetry &&
            attempts.latestAddresses == addresses
        if (!attempts.addresses.add(addresses) && !sameEvidenceRetry) return null
        return ExtraWakeClaim(networkHandle, association.identity).also {
            attempts.count++
            attempts.latest = it
            attempts.latestAddresses = addresses
        }
    }

    @Synchronized fun isCurrent(claim: ExtraWakeClaim): Boolean =
        associations[claim.networkHandle]?.identity === claim.association

    @Synchronized fun commitIfCurrent(claim: ExtraWakeClaim, commit: () -> Boolean): Boolean =
        isCurrent(claim) && commit()

    private companion object {
        const val MAX_EXTRA_ATTEMPTS = 2
    }

    suspend fun collect(tryReady: () -> Boolean = { false }, check: suspend () -> Unit) {
        for (signal in pending) {
            withTimeoutOrNull(750) {
                var submitted = false
                while (true) {
                    if (!submitted) submitted = tryReady()
                    pending.receive()
                }
            }
            // A queued early wake can lose its network before admission. Keep the settled check.
            check()
        }
    }
}
