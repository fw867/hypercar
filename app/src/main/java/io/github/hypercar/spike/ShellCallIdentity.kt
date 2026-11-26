package io.github.hypercar.spike

import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class PreciseCallRoles(val ringing: Int, val foreground: Int, val background: Int) {
    val idle: Boolean get() = ringing == IDLE && foreground == IDLE && background == IDLE
    val singleActive: Boolean get() = ringing == IDLE && foreground == ACTIVE && background == IDLE

    private companion object {
        const val IDLE = 0
        const val ACTIVE = 1
    }
}

internal sealed interface TelecomCallSnapshot {
    data class Observed(val activeCallId: String?, val endedCallIds: Set<String> = emptySet(),
                        val noCalls: Boolean = false) : TelecomCallSnapshot
    data object Unavailable : TelecomCallSnapshot
}

/** Reads Telecom's active PSTN leg using the existing authorized shell service. */
internal class ShellCallIdentity(
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val readCall: (timeoutMs: Long) -> TelecomCallSnapshot = ::readTelecom,
    private val onRevoked: (String) -> Unit = {},
) {
    private data class Sample(val verifiedAt: Long, val callId: String, val identity: String)

    private data class Revalidation(val notBefore: Long, var firstActiveCallId: String? = null)

    private val stateLock = Any()
    private val track = mutableMapOf<Int, PreciseCallRoles?>()
    private var registration = 0L
    private var boundary = 0L
    private var ready = false
    private var sample: Sample? = null
    private var lastVerifiedCallId: String? = null
    private val endedCallIds = linkedSetOf<String>()
    private var revalidation: Revalidation? = null
    private var nextReadAt = Long.MIN_VALUE
    private var status = "waiting_for_precise_state"

    fun beginTracking(): Long {
        val (generation, retired) = synchronized(stateLock) {
            track.clear()
            ready = false
            ++registration to invalidateLocked("waiting_for_precise_state")
        }
        retired?.let(onRevoked)
        return generation
    }

    fun trackSubscriptions(generation: Long, subscriptions: Set<Int>): Boolean = synchronized(stateLock) {
        if (registration != generation) return@synchronized false
        subscriptions.forEach { track[it] = null }
        true
    }

    fun trackingReady(generation: Long) = synchronized(stateLock) {
        if (registration == generation) ready = true
    }

    fun onPreciseCallState(generation: Long, subscription: Int, roles: PreciseCallRoles): Boolean {
        val (retired, initialStatesReceived) = synchronized(stateLock) {
            if (registration != generation || subscription !in track) return false
            val previous = track[subscription]
            if (roles == previous) return false
            track[subscription] = roles
            invalidateLocked("precise_state_changed") to (previous == null && track.values.none { it == null })
        }
        retired?.let(onRevoked)
        return initialStatesReceived
    }

    fun onDisconnected(generation: Long, subscription: Int): Boolean {
        val retired = synchronized(stateLock) {
            if (registration != generation || subscription !in track) return false
            revalidation = Revalidation(elapsed() + DISCONNECT_SETTLE_MS)
            invalidateLocked("precise_disconnect")
        }
        retired?.let(onRevoked)
        return true
    }

    private fun recordEndedCallsLocked(result: TelecomCallSnapshot.Observed) {
        endedCallIds.addAll(result.endedCallIds)
        if (result.noCalls) {
            lastVerifiedCallId?.let {
                endedCallIds.remove(it)
                endedCallIds.add(it)
            }
            lastVerifiedCallId = null
            revalidation = null
        }
        while (endedCallIds.size > TERMINAL_HISTORY_SIZE) endedCallIds.remove(endedCallIds.first())
    }

    private fun invalidateLocked(reason: String): String? {
        boundary++
        nextReadAt = Long.MIN_VALUE
        status = reason
        revalidation?.firstActiveCallId = null
        return sample?.identity.also { sample = null }
    }

    private fun activeSubscriptionLocked(): Int? {
        if (!ready || track.values.any { it == null || !(it.singleActive || it.idle) }) return null
        return track.entries.singleOrNull { it.value?.singleActive == true }?.key
    }

    @Synchronized
    fun current(): String? {
        val startedAt = elapsed()
        val (expectedBoundary, subscription, timeoutMs) = synchronized(stateLock) {
            val activeSubscription = activeSubscriptionLocked()
            if (activeSubscription == null && revalidation == null) return null
            if (startedAt < (revalidation?.notBefore ?: Long.MIN_VALUE)) return null
            val fresh = freshSampleLocked(startedAt)
            if (startedAt < nextReadAt) return fresh?.identity
            val remainingAge = fresh?.let { MAX_AGE_MS - (startedAt - it.verifiedAt) } ?: MAX_AGE_MS
            Triple(boundary, activeSubscription, minOf(TELECOM_TIMEOUT_MS, remainingAge))
        }
        // The event callback must be able to retire an identity while dumpsys is blocked.
        val observed = readCall(timeoutMs)
        val finishedAt = elapsed()
        val (identity, retired) = synchronized(stateLock) {
            if (expectedBoundary != boundary || activeSubscriptionLocked() != subscription) return null
            nextReadAt = startedAt + REFRESH_MS
            val fresh = freshSampleLocked(finishedAt)
            val result = if (finishedAt - startedAt < MAX_AGE_MS) observed else TelecomCallSnapshot.Unavailable
            when (result) {
                is TelecomCallSnapshot.Observed -> {
                    recordEndedCallsLocked(result)
                    val previous = sample
                    val callId = result.activeCallId
                    val ended = callId != null && (callId in result.endedCallIds || callId in endedCallIds)
                    if (subscription == null || callId == null || ended) {
                        revalidation?.firstActiveCallId = null
                        status = if (ended) "telecom_leg_disconnected" else "telecom_no_eligible_foreground"
                        sample = null
                        null to previous?.identity
                    } else if (!revalidatedLocked(callId)) {
                        status = "telecom_disconnect_unconfirmed"
                        null to null
                    } else {
                        val sameCall = fresh?.takeIf { it.callId == callId }
                        val key = sameCall?.identity ?: UUID.randomUUID().toString()
                        sample = Sample(startedAt, callId, key)
                        lastVerifiedCallId = callId
                        status = TELECOM_CONFIRMED
                        key to previous?.identity?.takeUnless { it == key }
                    }
                }
                TelecomCallSnapshot.Unavailable -> {
                    status = "telecom_query_unavailable"
                    revalidation?.firstActiveCallId = null
                    if (fresh == null) {
                        null to sample?.identity.also { sample = null }
                    } else {
                        // A transport failure cannot extend the age of the last verified call.
                        nextReadAt = startedAt + FAILED_REFRESH_RETRY_MS
                        fresh.identity to null
                    }
                }
            }
        }
        retired?.let(onRevoked)
        return identity
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

    private fun freshSampleLocked(now: Long) = sample?.takeIf { now - it.verifiedAt < MAX_AGE_MS }

    fun matchesCached(identity: String): Boolean = synchronized(stateLock) {
        freshSampleLocked(elapsed())?.identity == identity
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
        if (current() != identity) return false
        return isConfirmed(identity)
    }

    fun isConfirmed(identity: String): Boolean = synchronized(stateLock) {
        status == TELECOM_CONFIRMED && freshSampleLocked(elapsed())?.identity == identity
    }

    fun diagnosticStatus(): String = synchronized(stateLock) {
        if (sample != null && freshSampleLocked(elapsed()) == null) "telecom_identity_expired" else status
    }

    companion object {
        private const val REFRESH_MS = 200L
        private const val FAILED_REFRESH_RETRY_MS = 80L
        private const val MAX_AGE_MS = 500L
        private const val TELECOM_TIMEOUT_MS = 400L
        private const val TERMINAL_HISTORY_SIZE = 16
        private const val DISCONNECT_SETTLE_MS = 250L
        private const val TELECOM_CONFIRMED = "telecom_confirmed"
        private const val CALL_PREFIX = "[Call id="
        private const val ACTIVE_PSTN_FIELDS = ", state=ACTIVE, " +
            "tpac=ComponentInfo{com.android.phone/com.android.services.telephony.TelephonyConnectionService},"

        private fun readTelecom(timeoutMs: Long): TelecomCallSnapshot = runCatching {
            val timeout = "0.${timeoutMs.toString().padStart(3, '0')}"
            val process = ProcessBuilder("/system/bin/timeout", "-k", "0.05", timeout,
                "/system/bin/dumpsys", "-t", "1", "telecom")
                .redirectError(File("/dev/null"))
                .start()
            try {
                val snapshot = process.inputStream.bufferedReader().use { reader ->
                    parseSnapshot(reader.lineSequence())
                }
                if (!process.waitFor(50, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                    TelecomCallSnapshot.Unavailable
                } else {
                    snapshot
                }
            } finally {
                process.destroyForcibly()
            }
        }.getOrDefault(TelecomCallSnapshot.Unavailable)

        private val historyCall = Regex("Call\\s*(TC@[0-9]{1,19})(?=[:\\s]|$).*")
        private val terminalEvent = Regex("[0-9:.]+ - SET_DISCONNECT(?:ED|ING)(?=[:\\s]|$).*")
        private val samsungNonVoip = Regex(
            """falsecaps=\[Capabilities:[^\[\]]*\], props=\[Properties:[^\[\]]*\], conf\(false\), had_child\(false\)""",
        )

        /** Extract only opaque IDs and terminal events. Never retain call details from the history. */
        fun parseSnapshot(lines: Sequence<String>): TelecomCallSnapshot.Observed {
            val head = ArrayList<String>(96)
            val ended = linkedSetOf<String>()
            var historyIndent: Int? = null
            var eventCall: String? = null
            for (line in lines) {
                if (head.size < 96) head.add(line)
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
                    ended.add(eventCall)
                    if (ended.size > TERMINAL_HISTORY_SIZE) ended.remove(ended.first())
                }
            }
            val callsManager = head.indexOfFirst { it.trim() == "CallsManager:" }
            val first = head.getOrNull(callsManager + 1)
            val after = head.drop(callsManager + 2).firstOrNull { it.isNotBlank() }
            val noCalls = callsManager >= 0 && first?.trim() == "mCalls:" &&
                after?.trim() == "mCallAudioManager:" && first.indexOfFirst { !it.isWhitespace() } ==
                after.indexOfFirst { !it.isWhitespace() }
            return TelecomCallSnapshot.Observed(parse(head.asSequence()), ended, noCalls)
        }

        // The main list must identify a single leg, and the audio manager must own that same leg.
        fun parse(lines: Sequence<String>): String? {
            var callsManager = false
            var callsIndent = 0
            var onlyCall: String? = null
            var callAudioManagerIndent = 0
            val iterator = lines.take(96).iterator()
            while (iterator.hasNext()) {
                val line = iterator.next()
                val field = line.trim()
                val indent = line.indexOfFirst { !it.isWhitespace() }
                if (field.isEmpty()) continue
                if (callsIndent > 0 && indent > callsIndent) {
                    if (onlyCall != null) return null
                    onlyCall = parseActiveCall(field) ?: return null
                    continue
                }
                callsIndent = 0
                if (field == "CallsManager:") {
                    callsManager = true
                } else if (callsManager && indent == 0) {
                    return null
                } else if (callsManager && field == "mCalls:") {
                    callsIndent = indent
                } else if (callsManager && field == "mCallAudioManager:") {
                    callAudioManagerIndent = indent
                } else if (callAudioManagerIndent > 0 && indent <= callAudioManagerIndent) {
                    return null
                } else if (callAudioManagerIndent > 0 && field == "Foreground call:" && iterator.hasNext()) {
                    return onlyCall?.takeIf { it == parseActiveCall(iterator.next().trim()) }
                }
            }
            return null
        }

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
