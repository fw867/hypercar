package io.github.hypercar.spike

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class PreciseCallRoles(val ringing: Int, val foreground: Int, val background: Int) {
    val idle: Boolean get() = ringing == IDLE && foreground == IDLE && background == IDLE
    val singleActive: Boolean get() = ringing == IDLE && foreground == ACTIVE && background == IDLE
    val activeForeground: Boolean get() = foreground == ACTIVE
    val incomplete: Boolean get() = ringing == UNKNOWN || foreground == UNKNOWN || background == UNKNOWN
    val compatibleWithSingleCall: Boolean get() =
        (ringing == IDLE || ringing == UNKNOWN) &&
            (foreground == IDLE || foreground == ACTIVE || foreground == UNKNOWN) &&
            (background == IDLE || background == UNKNOWN)

    fun withKnownRoles(previous: PreciseCallRoles?): PreciseCallRoles = PreciseCallRoles(
        ringing.takeUnless { it == UNKNOWN } ?: previous?.ringing ?: UNKNOWN,
        foreground.takeUnless { it == UNKNOWN } ?: previous?.foreground ?: UNKNOWN,
        background.takeUnless { it == UNKNOWN } ?: previous?.background ?: UNKNOWN,
    )

    private companion object {
        const val UNKNOWN = -1
        const val IDLE = 0
        const val ACTIVE = 1
    }
}

internal sealed interface TelecomCallSnapshot {
    val endedCallIds: Set<String>
    val exitCode: Int?

    data class Observed(val activeCallId: String?, override val endedCallIds: Set<String> = emptySet(),
                        val noLiveCallsInList: Boolean = false, override val exitCode: Int? = 0,
                        val observedAt: Long? = null, val foregroundIsNull: Boolean = false) : TelecomCallSnapshot {
        val noCalls: Boolean get() = noLiveCallsInList && foregroundIsNull
    }
    data class Unavailable(override val exitCode: Int? = null, val reason: Reason = Reason.PROCESS_ERROR,
                           val callEvidence: Observed? = null,
                           override val endedCallIds: Set<String> = callEvidence?.endedCallIds.orEmpty(),
                           val heldCallIds: Set<String> = emptySet()) : TelecomCallSnapshot {
        enum class Reason { TIMEOUT, SIGKILL, NONZERO_EXIT, PROCESS_ERROR, INCOMPLETE_OUTPUT, MISSING_EXIT_STATUS, STALE_RESULT }
    }
}

internal data class CallAudioBridgeIdentity(val lease: String, val retryKey: String)

/** Reads Telecom's active PSTN leg using the existing authorized shell service. */
internal class ShellCallIdentity(
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val readCall: ((timeoutMs: Long) -> TelecomCallSnapshot)? = null,
    private val onRevoked: (String) -> Unit = {},
    private val trace: (String) -> Unit = {},
    private val sleepForStartRetry: (Long) -> Unit = SystemClock::sleep,
    private val readWithEvidence: (Long, (TelecomCallSnapshot) -> Unit) -> TelecomCallSnapshot = ::readTelecom,
) {
    private enum class Refresh { IDLE, FIRST_ATTEMPT, RETRY_PENDING }
    private data class Sample(val verifiedAt: Long, val observedAt: Long, val callId: String, val identity: String,
                               val refresh: Refresh = Refresh.IDLE, val retryRequiresAudio: Boolean = false) {
        fun remainingAt(now: Long): Long {
            val maxAge = if (refresh == Refresh.IDLE) MAX_AGE_MS else REFRESH_MAX_AGE_MS
            return maxAge - (now - verifiedAt)
        }
    }
    private data class StartProof(val recheckAt: Long, val retried: Boolean)
    private data class FallbackCandidate(val callId: String, val observedAt: Long)

    private data class Revalidation(val notBefore: Long, var firstActiveCallId: String? = null)
    private enum class Gate { NOT_READY, NO_SUBSCRIPTIONS, CONTRADICTORY, MULTIPLE_ACTIVE, IDLE, PRECISE, UNKNOWN_ROLES }
    private enum class Query { PRECISE, FALLBACK, TERMINAL_CHECK, BLOCKED }
    private data class ReadRequest(val boundary: Long, val query: Query, val timeoutMs: Long, val retrying: Boolean,
                                   val runningEndpoint: Any?)

    private val stateLock = Any()
    private val track = mutableMapOf<Int, PreciseCallRoles?>()
    private var registration = 0L
    private var boundary = 0L
    private var ready = false
    private var fallbackNotBefore = Long.MAX_VALUE
    private var fallbackCandidate: FallbackCandidate? = null
    private val knownRoles = mutableMapOf<Int, PreciseCallRoles>()
    @Volatile private var sample: Sample? = null
    private var running: Pair<String, Any>? = null
    private var lastVerifiedCallId: String? = null
    private var lastUnconfirmedCallId: String? = null
    private var retryKey: String? = null
    private val endedCallIds = linkedSetOf<String>()
    private var revalidation: Revalidation? = null
    private var nextReadAt = Long.MIN_VALUE
    private var status = "waiting_for_precise_state"
    private var lastGate: String? = null
    private var lastReadSource: Query? = null
    private var lastReadResult: String? = null
    private var lastReadLoggedAt = 0L

    fun beginTracking(): Long {
        val (generation, retired) = synchronized(stateLock) {
            track.clear()
            ready = false
            fallbackNotBefore = Long.MAX_VALUE
            ++registration
            trace("precise generation=$registration ready=false identity=waiting_for_precise_state")
            registration to invalidateLocked("waiting_for_precise_state")
        }
        retired?.let(onRevoked)
        return generation
    }

    fun trackSubscriptions(generation: Long, subscriptions: Set<Int>): Boolean = synchronized(stateLock) {
        if (registration != generation) return@synchronized false
        knownRoles.keys.retainAll(subscriptions)
        track.keys.retainAll(subscriptions)
        subscriptions.forEach { track[it] = null }
        true
    }

    fun queryTrackedSubscriptions(generation: Long, query: () -> Set<Int>): Set<Int>? {
        if (synchronized(stateLock) { registration != generation }) return null
        val subscriptions = query()
        return subscriptions.takeIf { trackSubscriptions(generation, it) }
    }

    fun trackingReady(generation: Long): Boolean = synchronized(stateLock) {
        if (registration != generation) return@synchronized false
        ready = true
        fallbackNotBefore = elapsed() + FALLBACK_SETTLE_MS
        trace("precise generation=$generation ready=true subscriptions=${track.keys}")
        true
    }

    fun onPreciseCallState(generation: Long, subscription: Int, roles: PreciseCallRoles): Boolean {
        val (retired, initialStatesReceived) = synchronized(stateLock) {
            if (registration != generation || subscription !in track) return false
            val previous = track[subscription]
            if (roles == previous) return false
            track[subscription] = roles
            // Missing fields cannot erase conflicting roles or evidence of two active subscriptions.
            knownRoles[subscription] = roles.withKnownRoles(knownRoles[subscription])
            if (ready) fallbackNotBefore = elapsed() + FALLBACK_SETTLE_MS
            trace("precise generation=$generation sub=$subscription ringing=${roles.ringing} " +
                "foreground=${roles.foreground} background=${roles.background} single_active=${roles.singleActive} " +
                "idle=${roles.idle} incomplete=${roles.incomplete}")
            invalidateLocked("precise_state_changed") to (previous == null && track.values.none { it == null })
        }
        retired?.let(onRevoked)
        return initialStatesReceived
    }

    fun onDisconnected(generation: Long, subscription: Int): Boolean {
        val retired = synchronized(stateLock) {
            if (registration != generation || subscription !in track) return false
            revalidation = Revalidation(elapsed() + DISCONNECT_SETTLE_MS)
            trace("precise generation=$generation sub=$subscription disconnect=true")
            invalidateLocked("precise_disconnect")
        }
        retired?.let(onRevoked)
        return true
    }

    private fun recordEndedCallsLocked(ended: Set<String>, noLiveCallsInList: Boolean) {
        endedCallIds.addAll(ended)
        if (noLiveCallsInList) {
            for (id in listOfNotNull(lastVerifiedCallId, lastUnconfirmedCallId)) {
                endedCallIds.remove(id)
                endedCallIds.add(id)
            }
            lastVerifiedCallId = null
            lastUnconfirmedCallId = null
            retryKey = null
            revalidation = null
        }
        while (endedCallIds.size > TERMINAL_HISTORY_SIZE) endedCallIds.remove(endedCallIds.first())
    }

    private fun invalidateLocked(reason: String): String? {
        boundary++
        nextReadAt = Long.MIN_VALUE
        fallbackCandidate = null
        lastReadSource = null
        lastReadResult = null
        updateStatusLocked(reason)
        revalidation?.firstActiveCallId = null
        return sample?.identity.also { sample = null }
    }

    private fun updateStatusLocked(value: String) {
        if (status == value) return
        status = value
        trace("identity=$value generation=$registration")
    }

    private fun preciseGateLocked(): Gate {
        if (!ready) return Gate.NOT_READY
        if (track.isEmpty()) return Gate.NO_SUBSCRIPTIONS
        if (knownRoles.values.any { !it.compatibleWithSingleCall }) return Gate.CONTRADICTORY
        val active = knownRoles.values.count { it.activeForeground }
        if (active > 1) return Gate.MULTIPLE_ACTIVE
        if (track.values.any { it == null || it.incomplete }) return Gate.UNKNOWN_ROLES
        return if (active == 1) Gate.PRECISE else Gate.IDLE
    }

    private fun traceGateLocked(gate: Gate, query: Query, settling: Boolean) {
        val subscription = if (gate == Gate.PRECISE) track.entries.first { it.value?.singleActive == true }.key else null
        val missing = track.filterValues { it == null || it.incomplete }.keys
        val active = knownRoles.filterValues { it.activeForeground }.keys
        val conflicts = knownRoles.filterValues { !it.compatibleWithSingleCall }.keys
        val line = "precise generation=$registration ready=$ready gate=${gate.name.lowercase()} " +
            "active_sub=${subscription ?: "none"} known_active_subs=$active unknown_subs=$missing conflict_subs=$conflicts " +
            "query=${query.name.lowercase()} settling=$settling"
        if (line != lastGate) {
            lastGate = line
            trace(line)
        }
    }

    private fun traceTelecomResultLocked(query: Query, result: TelecomCallSnapshot, startedAt: Long,
                                          finishedAt: Long, timeoutMs: Long, retrying: Boolean, discarded: Boolean,
                                          reason: TelecomCallSnapshot.Unavailable.Reason?) {
        val summary = if (discarded) "discarded" else when (result) {
            is TelecomCallSnapshot.Observed ->
                "active_pstn=${result.activeCallId ?: "none"} no_calls=${result.noCalls} terminal_count=${result.endedCallIds.size}"
            is TelecomCallSnapshot.Unavailable -> "unavailable"
        }
        val exitCode = result.exitCode
        val duration = finishedAt - startedAt
        val timedOut = when {
            reason == TelecomCallSnapshot.Unavailable.Reason.TIMEOUT -> "true"
            exitCode == null || exitCode == 137 -> "unknown"
            else -> "false"
        }
        val line = "telecom query=${query.name.lowercase()} result=$summary " +
            "exit_code=${exitCode ?: "none"} timed_out=$timedOut failure=${reason?.name?.lowercase() ?: "none"} " +
            "refresh_retry=$retrying"
        if (discarded || line != lastReadResult || finishedAt - lastReadLoggedAt >= TELECOM_TRACE_INTERVAL_MS) {
            if (!discarded) {
                lastReadResult = line
                lastReadLoggedAt = finishedAt
            }
            trace("$line duration_ms=$duration timeout_ms=$timeoutMs")
        }
    }

    @Synchronized
    fun current(allowFallback: Boolean = false): String? = refresh(allowFallback, force = false)

    private fun refresh(allowFallback: Boolean, force: Boolean): String? {
        val startedAt = elapsed()
        val (expectedBoundary, query, timeoutMs, retrying, runningEndpoint) = synchronized(stateLock) {
            val gate = preciseGateLocked()
            val fallback = allowFallback && gate == Gate.UNKNOWN_ROLES && startedAt >= fallbackNotBefore
            val query = when {
                gate == Gate.PRECISE -> Query.PRECISE
                fallback -> Query.FALLBACK
                revalidation != null -> Query.TERMINAL_CHECK
                else -> Query.BLOCKED
            }
            val pendingDisconnect = startedAt < (revalidation?.notBefore ?: Long.MIN_VALUE)
            traceGateLocked(gate, query, pendingDisconnect ||
                (allowFallback && gate == Gate.UNKNOWN_ROLES && startedAt < fallbackNotBefore))
            if (query == Query.BLOCKED || pendingDisconnect) return null
            val fresh = freshSampleLocked(startedAt)
            if (startedAt < nextReadAt && (!force || fresh?.refresh != Refresh.IDLE)) return fresh?.identity
            val remainingAge = fresh?.remainingAt(startedAt) ?: MAX_AGE_MS
            val timeout = minOf(TELECOM_TIMEOUT_MS, remainingAge)
            if ((query == Query.PRECISE || query == Query.FALLBACK) && fresh?.refresh == Refresh.IDLE) {
                // A refresh begun with a verified identity can finish and retry once
                // while the same call boundary still holds.
                sample = fresh.copy(refresh = Refresh.FIRST_ATTEMPT)
            }
            if (lastReadSource != query) {
                lastReadSource = query
                trace("telecom query=${query.name.lowercase()} started timeout_ms=$timeout")
            }
            val active = running?.takeIf { it.first == fresh?.identity }?.second
            ReadRequest(boundary, query, timeout, fresh?.refresh == Refresh.RETRY_PENDING, active)
        }
        // The event callback must be able to retire an identity while dumpsys is blocked.
        var evidenceAt: Long? = null
        val observed = readCall?.invoke(timeoutMs) ?: readWithEvidence(timeoutMs) {
            evidenceAt = (it as? TelecomCallSnapshot.Observed)?.observedAt ?: elapsed()
            revokeFromTelecomEvidence(expectedBoundary, it)
        }
        val finishedAt = elapsed()
        val (identity, retired) = synchronized(stateLock) {
            val discarded = expectedBoundary != boundary
            val result = when {
                observed is TelecomCallSnapshot.Observed && observed.exitCode == null && observed.activeCallId != null ->
                    TelecomCallSnapshot.Unavailable(reason = TelecomCallSnapshot.Unavailable.Reason.MISSING_EXIT_STATUS,
                        callEvidence = observed)
                observed is TelecomCallSnapshot.Observed && finishedAt - startedAt >= MAX_AGE_MS ->
                    TelecomCallSnapshot.Unavailable(observed.exitCode, TelecomCallSnapshot.Unavailable.Reason.STALE_RESULT,
                        callEvidence = observed)
                else -> observed
            }
            traceTelecomResultLocked(query, observed, startedAt, finishedAt, timeoutMs, retrying, discarded,
                (result as? TelecomCallSnapshot.Unavailable)?.reason)
            if (discarded) return null
            nextReadAt = startedAt + REFRESH_MS
            val fresh = freshSampleLocked(finishedAt)
            when (result) {
                is TelecomCallSnapshot.Observed -> {
                    recordEndedCallsLocked(result.endedCallIds, result.noLiveCallsInList)
                    val previous = sample
                    val callId = result.activeCallId
                    val ended = callId != null && (callId in result.endedCallIds || callId in endedCallIds)
                    val sameCall = fresh?.takeIf { it.callId == callId }
                    if (query == Query.TERMINAL_CHECK || callId == null || ended) {
                        revalidation?.firstActiveCallId = null
                        fallbackCandidate = null
                        updateStatusLocked(if (ended) "telecom_leg_disconnected" else "telecom_no_eligible_foreground")
                        sample = null
                        null to previous?.identity
                    } else {
                        val confirmedFallback = query != Query.FALLBACK || sameCall != null ||
                            confirmFallbackLocked(callId, finishedAt)
                        val confirmedDisconnect = revalidatedLocked(callId)
                        if (!confirmedFallback || !confirmedDisconnect) {
                            // Resetting a proof does not erase the last call that an empty Telecom list can retire.
                            lastUnconfirmedCallId = callId
                            updateStatusLocked(if (!confirmedDisconnect) "telecom_disconnect_unconfirmed"
                                else "telecom_fallback_unconfirmed")
                            sample = null
                            null to previous?.identity
                        } else {
                            val key = sameCall?.identity ?: UUID.randomUUID().toString()
                            sample = Sample(startedAt, evidenceAt ?: finishedAt, callId, key)
                            if (lastVerifiedCallId != callId) retryKey = UUID.randomUUID().toString()
                            lastVerifiedCallId = callId
                            lastUnconfirmedCallId = null
                            updateStatusLocked(TELECOM_CONFIRMED)
                            key to previous?.identity?.takeUnless { it == key }
                        }
                    }
                }
                is TelecomCallSnapshot.Unavailable -> {
                    updateStatusLocked("telecom_query_unavailable")
                    revalidation?.firstActiveCallId = null
                    fallbackCandidate = null
                    val oldCallId = sample?.callId
                    val evidence = result.callEvidence
                    val oldLegExcluded = oldCallId != null &&
                        (oldCallId in result.endedCallIds || oldCallId in result.heldCallIds ||
                            (evidence != null && evidence.activeCallId != oldCallId))
                    recordEndedCallsLocked(result.endedCallIds, evidence?.noLiveCallsInList == true)
                    val requiresAudio = result.reason == TelecomCallSnapshot.Unavailable.Reason.MISSING_EXIT_STATUS ||
                        result.reason == TelecomCallSnapshot.Unavailable.Reason.TIMEOUT
                    val mayRetry = if (requiresAudio) {
                        (query == Query.PRECISE || query == Query.FALLBACK) && fresh != null &&
                            runningEndpoint != null && running?.takeIf { it.first == fresh.identity }?.second === runningEndpoint
                    } else query == Query.PRECISE
                    if (mayRetry && fresh?.refresh == Refresh.FIRST_ATTEMPT &&
                        !oldLegExcluded && result.reason != TelecomCallSnapshot.Unavailable.Reason.STALE_RESULT
                    ) {
                        sample = fresh.copy(refresh = Refresh.RETRY_PENDING, retryRequiresAudio = requiresAudio)
                        nextReadAt = startedAt + FAILED_REFRESH_RETRY_MS
                        fresh.identity to null
                    } else {
                        null to sample?.identity.also { sample = null }
                    }
                }
            }
        }
        retired?.let(onRevoked)
        return identity
    }

    private fun revokeFromTelecomEvidence(expectedBoundary: Long, snapshot: TelecomCallSnapshot) {
        val retired = synchronized(stateLock) {
            if (expectedBoundary != boundary) return
            val evidence = when (snapshot) {
                is TelecomCallSnapshot.Observed -> snapshot
                is TelecomCallSnapshot.Unavailable -> snapshot.callEvidence
            }
            recordEndedCallsLocked(snapshot.endedCallIds, evidence?.noLiveCallsInList == true)
            val previous = sample ?: return
            val ended = previous.callId in snapshot.endedCallIds
            val held = (snapshot as? TelecomCallSnapshot.Unavailable)?.heldCallIds?.contains(previous.callId) == true
            if (!ended && !held && (evidence == null || evidence.activeCallId == previous.callId)) return
            updateStatusLocked(if (ended) "telecom_leg_disconnected" else "telecom_foreground_changed")
            sample = null
            previous.identity
        }
        onRevoked(retired)
    }

    fun markRunning(identity: String, endpoint: Any): Boolean =
        withCurrentLease(identity, null) {
            running = identity to endpoint
            true
        } == true

    fun markStopped(endpoint: Any) = synchronized(stateLock) {
        if (running?.second === endpoint) {
            val stopped = running?.first
            running = null
            if (sample?.identity == stopped && sample?.retryRequiresAudio == true) sample = null
        }
    }

    fun currentBridgeIdentity(): CallAudioBridgeIdentity? {
        val lease = current(allowFallback = true) ?: return null
        return synchronized(stateLock) {
            if (freshSampleLocked(elapsed())?.identity != lease) return@synchronized null
            retryKey?.let { CallAudioBridgeIdentity(lease, it) }
        }
    }

    private fun confirmFallbackLocked(callId: String, observedAt: Long): Boolean {
        val candidate = fallbackCandidate
        if (candidate?.callId == callId && observedAt - candidate.observedAt < FALLBACK_CONFIRM_MS) {
            fallbackCandidate = null
            return true
        }
        fallbackCandidate = FallbackCandidate(callId, observedAt)
        return false
    }

    // The callback carries no call ID. Two post-callback snapshots must agree before reopening.
    private fun revalidatedLocked(callId: String): Boolean {
        val pending = revalidation ?: return true
        if (pending.firstActiveCallId != callId) {
            pending.firstActiveCallId = callId
            return false
        }
        revalidation = null
        return true
    }

    private fun freshSampleLocked(now: Long) = sample?.takeIf { it.remainingAt(now) > 0 }

    /** Last check after fsync: read one immutable snapshot without waiting on a Telecom reader. */
    fun muteDispatchCurrent(identity: String): Boolean = sample?.let {
        it.identity == identity && it.refresh == Refresh.IDLE && it.remainingAt(elapsed()) > 0
    } == true

    fun matchesCached(identity: String): Boolean = synchronized(stateLock) {
        freshSampleLocked(elapsed())?.identity == identity
    }

    // Only short endpoint operations belong here. Telecom reads and route checks run outside stateLock.
    fun <T : Any> withCurrentLease(identity: String, startBefore: Long?, action: () -> T): T? = synchronized(stateLock) {
        val now = elapsed()
        val lease = freshSampleLocked(now)
        if (lease?.identity == identity &&
            (startBefore == null || (now < startBefore && lease.refresh == Refresh.IDLE))
        ) action() else null
    }

    fun millisUntilRefresh(): Long = synchronized(stateLock) {
        val now = elapsed()
        val deadline = maxOf(nextReadAt, revalidation?.notBefore ?: Long.MIN_VALUE)
        if (deadline <= now) 0L else minOf(deadline - now, REFRESH_MS)
    }

    fun millisUntilRevalidation(): Long? = synchronized(stateLock) {
        val pending = revalidation ?: return@synchronized null
        (maxOf(pending.notBefore, nextReadAt) - elapsed()).coerceAtLeast(0)
    }

    fun confirmsOpen(identity: String): Boolean {
        if (current(allowFallback = true) != identity) return false
        return withCurrentLease(identity, Long.MAX_VALUE) { true } == true
    }

    @Synchronized
    fun confirmsNewOffer(identity: String): Boolean = refresh(allowFallback = true, force = true) == identity &&
        withCurrentLease(identity, Long.MAX_VALUE) { true } == true

    fun confirmsAfterRouteCheck(identity: String, preflight: () -> Boolean, authorize: (Long) -> Boolean): Boolean =
        preflight() && confirmsStart(identity, forceRefresh = true, authorize = authorize)

    // Share the watchdog's query monitor through startup, including its check for a due retry.
    @Synchronized
    fun confirmsStart(identity: String, forceRefresh: Boolean = false, authorize: (Long) -> Boolean = { true },
                      preflight: () -> Boolean = { true }): Boolean {
        val first = confirmStartSample(identity, allowRetry = true, forceRefresh = forceRefresh) ?: return false
        if (!preflight() || !matchesCached(identity)) return false
        val proof = if (elapsed() >= first.recheckAt) {
            val renewed = confirmStartSample(identity, allowRetry = !first.retried, forceRefresh = true) ?: return false
            if (!preflight()) return false
            renewed
        } else first
        return elapsed() < proof.recheckAt && matchesCached(identity) && authorize(proof.recheckAt)
    }

    private fun confirmStartSample(identity: String, allowRetry: Boolean, forceRefresh: Boolean): StartProof? {
        val previous = synchronized(stateLock) { sample }
        if (refresh(allowFallback = true, force = forceRefresh) != identity) return null
        val retryDelay = synchronized(stateLock) {
            val now = elapsed()
            val lease = freshSampleLocked(now)?.takeIf { it.identity == identity } ?: return null
            if (lease.refresh == Refresh.RETRY_PENDING) (nextReadAt - now).coerceAtLeast(0) else null
        }
        if (retryDelay != null) {
            if (!allowRetry) return null
            // Keep the existing endpoint alive during the first miss, but wait for proof before starting it.
            if (retryDelay > 0) sleepForStartRetry(retryDelay)
            if (!matchesCached(identity) || !confirmsOpen(identity)) return null
        }
        return synchronized(stateLock) {
            val lease = freshSampleLocked(elapsed())
                ?.takeIf { it.identity == identity && it.refresh == Refresh.IDLE } ?: return null
            // Waiting for dumpsys to exit must not renew the time of the foreground observation.
            val cadence = if (lease !== previous) maxOf(nextReadAt, lease.observedAt + START_PREFLIGHT_MS) else nextReadAt
            val recheckAt = minOf(cadence, lease.observedAt + REFRESH_MS)
            StartProof(recheckAt, retryDelay != null)
        }
    }

    fun diagnosticStatus(): String = synchronized(stateLock) {
        if (sample != null && freshSampleLocked(elapsed()) == null) "telecom_identity_expired" else status
    }

    fun verifiedCallNumber(identity: String): Long? = synchronized(stateLock) {
        freshSampleLocked(elapsed())?.takeIf { it.identity == identity }?.callId?.substringAfter("TC@")?.toLongOrNull()
    }

    companion object {
        private const val REFRESH_MS = 200L
        private const val FAILED_REFRESH_RETRY_MS = 80L
        private const val START_PREFLIGHT_MS = 50L
        private const val MAX_AGE_MS = 500L
        private const val TELECOM_TIMEOUT_MS = 400L
        private const val REFRESH_MAX_AGE_MS = MAX_AGE_MS + TELECOM_TIMEOUT_MS + FAILED_REFRESH_RETRY_MS
        private const val TELECOM_TRACE_INTERVAL_MS = 1_000L
        private const val TERMINAL_HISTORY_SIZE = 16
        private const val DISCONNECT_SETTLE_MS = 250L
        private const val FALLBACK_SETTLE_MS = 500L
        private const val FALLBACK_CONFIRM_MS = 1_000L
        private const val TELECOM_CONFIRMED = "telecom_confirmed"
        private const val CALL_PREFIX = "[Call id="
        private const val ACTIVE_PSTN_FIELDS = ", state=ACTIVE, " +
            "tpac=ComponentInfo{com.android.phone/com.android.services.telephony.TelephonyConnectionService},"

        fun recoveryCallNumber(): Long? = recoveryCallNumber(readTelecom(350) {})

        fun recoveryCallNumber(snapshot: TelecomCallSnapshot): Long? {
            if (snapshot !is TelecomCallSnapshot.Observed || snapshot.exitCode != 0) return null
            if (snapshot.noCalls) return NO_RECOVERY_CALL
            return snapshot.activeCallId?.takeUnless { it in snapshot.endedCallIds }?.substringAfter("TC@")?.toLongOrNull()
        }

        const val NO_RECOVERY_CALL = -1L

        fun recoveryTelecomPid(): Int? = runCatching {
            val process = ProcessBuilder("/system/bin/timeout", "-k", "0.05", "0.2",
                "/system/bin/dumpsys", "--pid", "telecom")
                .redirectErrorStream(true).start()
            try {
                val raw = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitFor(50, TimeUnit.MILLISECONDS) && process.exitValue() == 0)
                    raw.trim().toIntOrNull()?.takeIf { it > 1 } else null
            } finally {
                process.destroyForcibly()
            }
        }.getOrNull()

        private fun readTelecom(timeoutMs: Long, onEvidence: (TelecomCallSnapshot) -> Unit): TelecomCallSnapshot = runCatching {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val timeout = "0.${timeoutMs.toString().padStart(3, '0')}"
            val process = ProcessBuilder("/system/bin/timeout", "-v", "-k", "0.05", timeout,
                "/system/bin/dumpsys", "-t", "1", "telecom")
                .redirectErrorStream(true)
                .start()
            try {
                var dumpStartedAt: Long? = null
                val parsed = process.inputStream.bufferedReader().use { reader ->
                    parseSnapshot(reader.lineSequence().onEach {
                        if (dumpStartedAt == null) dumpStartedAt = SystemClock.elapsedRealtime()
                    }, onEvidence)
                }
                val snapshot = (parsed as? TelecomCallSnapshot.Observed)?.copy(observedAt = dumpStartedAt) ?: parsed
                onEvidence(snapshot)
                // EOF can arrive before Android publishes the process exit status.
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs > 0) process.waitFor(remainingMs, TimeUnit.MILLISECONDS)
                val exitCode = try {
                    process.exitValue()
                } catch (_: IllegalThreadStateException) {
                    null
                }
                withProcessExit(snapshot, exitCode)
            } finally {
                process.destroyForcibly()
            }
        }.getOrDefault(TelecomCallSnapshot.Unavailable())

        fun withProcessExit(snapshot: TelecomCallSnapshot, exitCode: Int?): TelecomCallSnapshot {
            val reason = when {
                exitCode == 124 ||
                    (snapshot as? TelecomCallSnapshot.Unavailable)?.reason ==
                    TelecomCallSnapshot.Unavailable.Reason.TIMEOUT -> TelecomCallSnapshot.Unavailable.Reason.TIMEOUT
                exitCode == null && snapshot is TelecomCallSnapshot.Unavailable ->
                    TelecomCallSnapshot.Unavailable.Reason.MISSING_EXIT_STATUS
                exitCode == null || exitCode == 0 -> return when (snapshot) {
                    is TelecomCallSnapshot.Observed -> snapshot.copy(exitCode = exitCode)
                    is TelecomCallSnapshot.Unavailable -> snapshot.copy(exitCode = exitCode)
                }
                exitCode == 137 -> TelecomCallSnapshot.Unavailable.Reason.SIGKILL
                else -> TelecomCallSnapshot.Unavailable.Reason.NONZERO_EXIT
            }
            val evidence = when (snapshot) {
                is TelecomCallSnapshot.Observed -> snapshot
                is TelecomCallSnapshot.Unavailable -> snapshot.callEvidence
            }
            return TelecomCallSnapshot.Unavailable(exitCode, reason, evidence, snapshot.endedCallIds,
                (snapshot as? TelecomCallSnapshot.Unavailable)?.heldCallIds.orEmpty())
        }

        private val historyCall = Regex("Call\\s*(TC@[0-9]{1,19})(?=[:\\s]|$).*")
        private val terminalEvent = Regex("[0-9:.]+ - SET_DISCONNECT(?:ED|ING)(?=[:\\s]|$).*")
        private val samsungNonVoip = Regex(
            """falsecaps=\[Capabilities:[^\[\]]*\], props=\[Properties:[^\[\]]*\], conf\(false\), had_child\(false\)""",
        )
        private val finishedCallRecord = Regex(
            """\], voip=(?:true|false)(?:caps=.*\), had_child\((?:true|false)\))?$""",
        )
        private val inactiveCallRecord = Regex("""^\[Call id=(TC@[0-9]{1,19}), state=(DISCONNECTING|DISCONNECTED|ON_HOLD),""")
        private val callSectionHeading = Regex("""[A-Za-z][A-Za-z0-9_]*:.*""")
        private val timeoutSignal = Regex(
            """timeout: sending signal (?:TERM|KILL) to command /system/bin/dumpsys(?:[: ].*)?""",
        )

        /** Extract only opaque IDs and terminal events. Never retain call details from the history. */
        fun parseSnapshot(lines: Sequence<String>, onEvidence: ((TelecomCallSnapshot) -> Unit)? = null): TelecomCallSnapshot {
            val parser = CallHeaderParser()
            val ended = linkedSetOf<String>()
            var historyIndent: Int? = null
            var eventCall: String? = null
            var dumpFailure: TelecomCallSnapshot.Unavailable.Reason? = null
            var header = CallHeader()
            var lastEvidence: TelecomCallSnapshot.Unavailable? = null

            fun evidenceOnly(reason: TelecomCallSnapshot.Unavailable.Reason) = TelecomCallSnapshot.Unavailable(
                0, reason, header.foreground, ended + header.ended, header.held,
            )

            fun publishEvidence() {
                val publish = onEvidence ?: return
                val evidence = evidenceOnly(TelecomCallSnapshot.Unavailable.Reason.INCOMPLETE_OUTPUT)
                if (evidence != lastEvidence) {
                    lastEvidence = evidence
                    if (evidence.callEvidence != null || evidence.endedCallIds.isNotEmpty() || evidence.heldCallIds.isNotEmpty()) {
                        publish(evidence)
                    }
                }
            }

            for (line in lines) {
                if (line.startsWith("*** SERVICE 'telecom' DUMP TIMEOUT (") || timeoutSignal.containsMatchIn(line)) {
                    dumpFailure = TelecomCallSnapshot.Unavailable.Reason.TIMEOUT
                    continue
                }
                if (line.startsWith("Error in poll while dumping service telecom ") ||
                    line.startsWith("Failed to read while dumping service telecom:") ||
                    line.startsWith("Failed to write while dumping service telecom:") ||
                    line.startsWith("Error with service 'telecom' while dumping:")
                ) {
                    dumpFailure = dumpFailure ?: TelecomCallSnapshot.Unavailable.Reason.INCOMPLETE_OUTPUT
                    continue
                }
                if (parser.read(line) && onEvidence != null) {
                    header = parser.evidence()
                    publishEvidence()
                }
                if (line.trim() == "Historical Events:") {
                    historyIndent = line.indexOfFirst { !it.isWhitespace() }
                    eventCall = null
                    continue
                }
                val sectionIndent = historyIndent ?: continue
                if (line.isBlank()) continue
                val indent = line.indexOfFirst { !it.isWhitespace() }
                if (indent <= sectionIndent) {
                    historyIndent = null
                    eventCall = null
                } else if (indent == sectionIndent + 2) {
                    eventCall = historyCall.matchEntire(line.substring(indent))?.groupValues?.get(1)
                } else if (indent == sectionIndent + 4 && eventCall != null &&
                    terminalEvent.matches(line.substring(indent))) {
                    ended.rememberCall(eventCall)
                    publishEvidence()
                }
            }
            header = parser.evidence()
            return if (dumpFailure == null && header.complete) {
                checkNotNull(header.foreground).copy(endedCallIds = ended + header.ended)
            } else evidenceOnly(dumpFailure ?: TelecomCallSnapshot.Unavailable.Reason.INCOMPLETE_OUTPUT)
        }

        private data class CallHeader(
            val foreground: TelecomCallSnapshot.Observed? = null,
            val ended: Set<String> = emptySet(),
            val held: Set<String> = emptySet(),
            val complete: Boolean = false,
        )

        private fun MutableSet<String>.rememberCall(id: String) {
            add(id)
            if (size > TERMINAL_HISTORY_SIZE) remove(first())
        }

        private class CallHeaderParser {
            private var managerIndent: Int? = null
            private var managerClosed = false
            private var callsIndent: Int? = null
            private var rowIndent: Int? = null
            private var readingCalls = false
            private var mainClosed = false
            private var allRowsComplete = true
            private var liveCalls = 0
            private var activeCallId: String? = null
            private var sawAudio = false
            private var readingAudio = false
            private var expectingForeground = false
            private var foreground: TelecomCallSnapshot.Observed? = null
            private val ended = linkedSetOf<String>()
            private val held = linkedSetOf<String>()

            fun read(line: String): Boolean {
                val indent = line.indexOfFirst { !it.isWhitespace() }
                if (indent < 0 || managerClosed) return false
                val text = line.substring(indent).trimEnd()
                val manager = managerIndent
                if (manager == null) {
                    if (text == "CallsManager:") managerIndent = indent
                    return false
                }
                if (indent <= manager) {
                    managerClosed = true
                    return false
                }
                val calls = callsIndent
                if (calls == null) {
                    if (text == "mCalls:") {
                        callsIndent = indent
                        readingCalls = true
                    }
                    return false
                }
                var updated = false
                if (readingCalls) {
                    if (indent > calls) return readCallRow(text, indent)
                    readingCalls = false
                    mainClosed = indent == calls && callSectionHeading.matches(text)
                    updated = mainClosed
                }
                if (indent == calls && text == "mCallAudioManager:" && !sawAudio) {
                    sawAudio = true
                    readingAudio = true
                    return true
                }
                if (!readingAudio) return updated
                if (indent <= calls) {
                    readingAudio = false
                    return updated
                }
                if (expectingForeground) {
                    expectingForeground = false
                    if (text == "null" || isCompleteCallRecord(text)) {
                        foreground = TelecomCallSnapshot.Observed(parseActiveCall(text), foregroundIsNull = text == "null")
                        return true
                    }
                }
                if (foreground == null && text == "Foreground call:") expectingForeground = true
                return updated
            }

            private fun readCallRow(record: String, indent: Int): Boolean {
                if (rowIndent == null) rowIndent = indent
                if (indent != rowIndent || !isCompleteCallRecord(record)) {
                    allRowsComplete = false
                    return false
                }
                val inactive = inactiveCallRecord.find(record)
                if (inactive != null && inactive.groupValues[2] != "ON_HOLD") {
                    ended.rememberCall(inactive.groupValues[1])
                    return true
                }
                liveCalls = (liveCalls + 1).coerceAtMost(2)
                activeCallId = if (liveCalls == 1) parseActiveCall(record) else null
                if (inactive == null) return false
                held.rememberCall(inactive.groupValues[1])
                return true
            }

            fun evidence(): CallHeader {
                val main = if (mainClosed && allRowsComplete) {
                    TelecomCallSnapshot.Observed(activeCallId, noLiveCallsInList = liveCalls == 0)
                } else null
                val audio = foreground
                val combined = when {
                    main == null -> audio
                    audio == null -> main
                    else -> main.copy(activeCallId = main.activeCallId?.takeIf { it == audio.activeCallId },
                        foregroundIsNull = audio.foregroundIsNull)
                }
                // Partial sections can revoke, but must never validate a new or replacement endpoint.
                val complete = sawAudio && main != null && (main.activeCallId == null || audio != null)
                return CallHeader(combined, ended.toSet(), held.toSet(), complete)
            }
        }

        private fun isCompleteCallRecord(record: String): Boolean = record.startsWith(CALL_PREFIX) &&
            (finishedCallRecord.containsMatchIn(record) || (", prop=" in record && record.endsWith("]]")))

        fun parse(lines: Sequence<String>): String? = (parseSnapshot(lines) as? TelecomCallSnapshot.Observed)?.activeCallId

        private fun parseActiveCall(record: String): String? {
            if (!record.startsWith(CALL_PREFIX) || ", childs(0), has_parent(false)," !in record) return null
            val voip = record.substringAfter("], voip=", missingDelimiterValue = "")
            val nonVoip = when {
                voip.isEmpty() -> record.endsWith(']')
                voip == "false" -> true
                else -> samsungNonVoip.matches(voip)
            }
            if (!nonVoip) return null
            val id = record.substring(CALL_PREFIX.length).substringBefore(',')
            val digits = id.removePrefix("TC@")
            if (digits == id || digits.length !in 1..19 || digits.any { it !in '0'..'9' }) return null
            return id.takeIf { record.startsWith(CALL_PREFIX + id + ACTIVE_PSTN_FIELDS) }
        }
    }
}
