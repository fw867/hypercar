package io.github.hypercar.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AirplayTarget(val host: String, val port: Int) {
    init { require(host.isNotBlank() && port in 1..65535) }
    override fun toString() = "$host:$port"
}

/** Invitations identify a host, not a port. Preserve a recent explicit SRV/iAP2 port. */
internal class AirplayEndpointHints(private val defaultPort: Int = 7000) {
    private var advertised: AirplayTarget? = null
    private var lastInvitation: Any? = null
    /** The first existing invitation must supersede a cached/scanned endpoint too. */
    fun invitation(identity: Any, host: String): AirplayTarget? {
        if (identity === lastInvitation) return null
        lastInvitation = identity
        return invited(host)
    }
    fun advertised(target: AirplayTarget): AirplayTarget { advertised = target; return target }
    fun invited(host: String): AirplayTarget = advertised?.takeIf { it.host == host } ?: AirplayTarget(host, defaultPort)
}

/** Transport-only recovery. Does not pair, stop iAP2, or treat discovery as activation. */
internal class AirplayEndpointConnector<T>(
    private val current: () -> Boolean,
    private val now: () -> Long,
    private val open: suspend (AirplayTarget) -> T?,
    private val close: (T) -> Unit,
    private val invalidate: (AirplayTarget) -> Unit,
    private val updates: () -> List<AirplayTarget>,
    private val discover: suspend () -> List<AirplayTarget>,
    private val pause: suspend () -> Unit,
) {
    data class Connected<T>(val target: AirplayTarget, val socket: T)

    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("AirPlay endpoint session expired")
    }

    suspend fun connect(initial: List<AirplayTarget>, windowMillis: Long): Connected<T>? {
        val deadline = now() + windowMillis
        var candidates = initial.distinct()
        var discovered = false
        var attempted = false
        val invalidated = mutableSetOf<AirplayTarget>()
        retry@ while (candidates.isNotEmpty()) {
            checkCurrent()
            if (attempted && now() >= deadline) return null
            val fresh = updates().distinct()
            if (fresh.isNotEmpty()) candidates = fresh
            for (target in candidates) {
                checkCurrent()
                if (attempted && now() >= deadline) return null
                attempted = true
                val socket = open(target)
                if (socket != null) {
                    try { checkCurrent() } catch (cancelled: CancellationException) {
                        close(socket)
                        throw cancelled
                    }
                    val arrived = updates().distinct()
                    if (arrived.isNotEmpty() && target !in arrived) {
                        close(socket)
                        candidates = arrived
                        continue@retry
                    }
                    return Connected(target, socket)
                }
                checkCurrent()
                if (invalidated.add(target)) invalidate(target)
                // Fresh invitation/iAP2 parameters take precedence over old fallback ports.
                val arrived = updates().distinct()
                if (arrived.isNotEmpty() && arrived != candidates) { candidates = arrived; continue@retry }
            }
            checkCurrent()
            val arrived = updates().distinct()
            if (arrived.isNotEmpty() && arrived != candidates) { candidates = arrived; continue }
            if (!discovered) {
                discovered = true
                val resolved = discover().distinct()
                checkCurrent()
                val newest = updates().distinct()
                if (newest.isNotEmpty()) { candidates = newest; continue }
                if (resolved.isNotEmpty()) { candidates = resolved; continue }
            }
            if (now() >= deadline) return null
            pause()
        }
        return null
    }
}
