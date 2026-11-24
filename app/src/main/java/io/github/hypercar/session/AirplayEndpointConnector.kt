package io.github.hypercar.session

import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2EndpointGeneration
import io.github.hypercar.net.EndpointScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android owns the connected socket until the native start commits under the session lock. */
internal suspend fun <P, T> handOffControlSocket(
    lock: Mutex,
    credentialCommitLock: Any,
    closeUnclaimed: () -> Unit,
    checkCurrent: () -> Unit,
    prepare: () -> P,
    sourceGeneration: () -> Iap2EndpointGeneration? = { null },
    start: (P, transfer: (() -> T) -> T) -> T,
): T {
    val context = currentCoroutineContext()
    var transferred = false
    try {
        return lock.withLock {
            context.ensureActive()
            checkCurrent()
            val prepared = prepare()
            start(prepared) { adopt ->
                synchronized(credentialCommitLock) {
                    context.ensureActive()
                    checkCurrent()
                    // Claim ownership atomically with DETECT. Native startup can
                    // wait for the previous engine, so run it outside the epoch lock.
                    val generation = sourceGeneration()
                    if (generation == null) transferred = true
                    else if (!generation.runIfCurrent { transferred = true }) throw AirplayEndpointChanged()
                    adopt()
                }
            }
        }
    } finally {
        if (!transferred) closeUnclaimed()
    }
}

internal class AirplayEndpointChanged : CancellationException()

internal data class AirplayTarget(val host: String, val port: Int, val verifiedNetworkHandle: Long? = null,
    val sourceGeneration: Iap2EndpointGeneration? = null) {
    init { require(host.isNotBlank() && port in 1..65535) }
    val sourceIsCurrent: Boolean get() = sourceGeneration?.isCurrent != false
    fun allowsNetwork(networkHandle: Long): Boolean =
        verifiedNetworkHandle == null || verifiedNetworkHandle == networkHandle

    // A connector owns one bound Network. A report can add verification to that
    // route, but must not change the peer, explicit scope, port or verified Network.
    fun sameRoute(other: AirplayTarget): Boolean =
        port == other.port &&
            (verifiedNetworkHandle == null || other.verifiedNetworkHandle == null ||
                verifiedNetworkHandle == other.verifiedNetworkHandle) &&
            (host == other.host || host.substringAfter('%', "") == other.host.substringAfter('%', "") &&
                EndpointScope.sameAddressOnNetwork(host, other.host))

    fun withRouteEvidence(report: AirplayTarget): AirplayTarget = copy(
        verifiedNetworkHandle = report.verifiedNetworkHandle ?: verifiedNetworkHandle,
        sourceGeneration = report.sourceGeneration ?: sourceGeneration,
    )

    override fun toString() = "$host:$port"
}

/** Keep an address-only report's port paired with an address verified on the bound WLAN. */
internal fun CarPlayEndpoint.verifiedAirplayEndpoint(
    boundSsid: String?, networkHandle: Long, retiredAddresses: Set<String> = emptySet(),
    hasMatchingCredentials: Boolean = false, isOnLink: (String) -> Boolean,
): CarPlayEndpoint? {
    if (!sourceIsCurrent) return null
    if (!requiresBoundAirplayRoute) return this
    val binding = airplayRouteBinding ?: return null
    if (binding.networkHandle?.let { it != networkHandle } == true) return null
    if (ssid != null && ssid != boundSsid) return null
    if (requiresMatchingAirplayCredentials && !hasMatchingCredentials) return null
    val addresses = ipAddresses.filter { it.substringBefore('%') !in retiredAddresses && isOnLink(it) }
    if (addresses.isEmpty() || !binding.bind(networkHandle)) return null
    return copy(ipAddresses = addresses, gateway = null)
}

/** Invitations identify a host, not a port. Preserve a recent explicit SRV/iAP2 port. */
internal class AirplayEndpointHints(private val defaultPort: Int = 7000) {
    private val advertised = mutableMapOf<String, AirplayTarget>()
    private var lastInvitation: Any? = null
    /** The first existing invitation must supersede a cached/scanned endpoint too. */
    fun invitation(identity: Any, host: String): AirplayTarget? {
        if (identity === lastInvitation) return null
        lastInvitation = identity
        return invited(host)
    }
    fun advertised(target: AirplayTarget): AirplayTarget {
        advertised[EndpointScope.addressKey(target.host) ?: target.host] = target
        return target
    }
    fun invited(host: String): AirplayTarget = advertised[EndpointScope.addressKey(host) ?: host]
        ?.takeIf { it.sourceIsCurrent } ?: AirplayTarget(host, defaultPort)
}

/** Transport-only recovery. Does not pair, stop iAP2, or treat discovery as activation. */
internal class AirplayEndpointConnector<T>(
    private val current: () -> Boolean,
    private val now: () -> Long,
    private val open: suspend (AirplayTarget) -> T?,
    private val close: (T) -> Unit,
    private val invalidate: (AirplayTarget) -> Unit,
    private val updates: () -> List<AirplayTarget>,
    private val discover: suspend (remainingMillis: Long) -> List<AirplayTarget>,
    private val pause: suspend () -> Unit,
    private val networkHandle: Long? = null,
) {
    data class Connected<T>(val target: AirplayTarget, val socket: T)

    private fun availableTargets(targets: List<AirplayTarget>): List<AirplayTarget> =
        targets.filter { it.sourceIsCurrent && (networkHandle == null || it.allowsNetwork(networkHandle)) }.distinct()

    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("AirPlay endpoint session expired")
    }

    suspend fun connect(initial: List<AirplayTarget>, windowMillis: Long): Connected<T>? {
        val deadline = now() + windowMillis
        var candidates = availableTargets(initial)
        var nextDiscoveryAt = now()
        var attempted = false
        val invalidated = mutableSetOf<AirplayTarget>()
        retry@ while (candidates.isNotEmpty()) {
            checkCurrent()
            if (attempted && now() >= deadline) return null
            val fresh = availableTargets(updates())
            if (fresh.isNotEmpty()) candidates = fresh
            for (target in candidates) {
                checkCurrent()
                if (!target.sourceIsCurrent) continue
                if (attempted && now() >= deadline) return null
                attempted = true
                val socket = open(target)
                if (socket != null) {
                    var accepted = false
                    try {
                        checkCurrent()
                        val arrived = availableTargets(updates())
                        checkCurrent()
                        if (now() >= deadline) return null
                        val report = arrived.firstOrNull(target::sameRoute)
                        if (arrived.isNotEmpty() && (report == null || !target.sourceIsCurrent)) {
                            candidates = arrived
                            continue@retry
                        }
                        val selected = report?.let(target::withRouteEvidence) ?: target
                        if (!target.sourceIsCurrent || !selected.sourceIsCurrent) continue
                        accepted = true
                        return Connected(selected, socket)
                    } finally {
                        if (!accepted) close(socket)
                    }
                }
                checkCurrent()
                if (target.sourceIsCurrent && invalidated.add(target)) invalidate(target)
                // Fresh invitation/iAP2 parameters take precedence over old fallback ports.
                val arrived = availableTargets(updates())
                if (arrived.isNotEmpty() && arrived != candidates) { candidates = arrived; continue@retry }
            }
            checkCurrent()
            val arrived = availableTargets(updates())
            if (arrived.isNotEmpty() && arrived != candidates) { candidates = arrived; continue }
            val beforeDiscovery = now()
            if (beforeDiscovery >= deadline) return null
            if (beforeDiscovery >= nextDiscoveryAt) {
                val resolved = availableTargets(discover(deadline - beforeDiscovery))
                checkCurrent()
                nextDiscoveryAt = now() + DISCOVERY_RETRY_INTERVAL_MILLIS
                val newest = availableTargets(updates())
                if (newest.isNotEmpty() && newest != candidates) { candidates = newest; continue }
                if (newest.isEmpty() && resolved.isNotEmpty() && resolved != candidates) { candidates = resolved; continue }
            }
            if (now() >= deadline) return null
            pause()
        }
        return null
    }

    private companion object {
        const val DISCOVERY_RETRY_INTERVAL_MILLIS = 400L
    }
}
