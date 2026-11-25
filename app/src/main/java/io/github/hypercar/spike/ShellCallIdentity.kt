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

/** Reads Telecom's active PSTN leg using the existing authorized shell service. */
internal class ShellCallIdentity(
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val readCallId: () -> String? = ::readTelecom,
    private val onRevoked: (String) -> Unit = {},
) {
    private data class Sample(val startedAt: Long, val callId: String?, val identity: String?)

    private val stateLock = Any()
    private val track = mutableMapOf<Int, PreciseCallRoles?>()
    private var registration = 0L
    private var boundary = 0L
    private var ready = false
    private var sample: Sample? = null

    fun beginTracking(): Long {
        val (generation, retired) = synchronized(stateLock) {
            track.clear()
            ready = false
            ++registration to invalidateLocked()
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

    fun onPreciseCallState(generation: Long, subscription: Int, roles: PreciseCallRoles?): Boolean {
        val (retired, initialStatesReceived) = synchronized(stateLock) {
            if (registration != generation || subscription !in track) return false
            val first = track[subscription] == null
            track[subscription] = roles
            // A callback can carry a disconnect even when the three role fields match.
            invalidateLocked() to (first && track.values.none { it == null })
        }
        retired?.let(onRevoked)
        return initialStatesReceived
    }

    private fun invalidateLocked(): String? {
        boundary++
        return sample?.identity.also { sample = null }
    }

    private fun singleActiveLocked(): Boolean = ready &&
        track.values.count { it?.singleActive == true } == 1 &&
        track.values.all { it != null && (it.singleActive || it.idle) }

    @Synchronized
    fun current(): String? {
        val startedAt = elapsed()
        val expectedBoundary = synchronized(stateLock) {
            if (!singleActiveLocked()) return null
            sample?.let { if (startedAt - it.startedAt < REFRESH_MS) return it.identity }
            boundary
        }
        // The event callback must be able to retire an identity while dumpsys is blocked.
        val observed = readCallId()
        val finishedAt = elapsed()
        return synchronized(stateLock) {
            if (expectedBoundary != boundary || !singleActiveLocked()) return@synchronized null
            val callId = observed.takeIf { finishedAt - startedAt < MAX_AGE_MS }
            // Keep only an opaque key for this continuous, single-leg interval.
            val identity = callId?.let {
                sample?.takeIf { it.callId == callId && finishedAt - it.startedAt < MAX_AGE_MS }?.identity
                    ?: UUID.randomUUID().toString()
            }
            sample = Sample(startedAt, callId, identity)
            identity
        }
    }

    fun matchesCached(identity: String): Boolean = synchronized(stateLock) {
        sample?.let { it.identity == identity && elapsed() - it.startedAt < MAX_AGE_MS } ?: false
    }

    companion object {
        private const val REFRESH_MS = 200L
        private const val MAX_AGE_MS = 500L
        private const val CALL_PREFIX = "[Call id="
        private const val ACTIVE_PSTN_FIELDS = ", state=ACTIVE, " +
            "tpac=ComponentInfo{com.android.phone/com.android.services.telephony.TelephonyConnectionService},"

        private fun readTelecom(): String? = runCatching {
            val process = ProcessBuilder("/system/bin/timeout", "-k", "0.05", "0.4",
                "/system/bin/dumpsys", "-t", "1", "telecom")
                .redirectError(File("/dev/null"))
                .start()
            try {
                val identity = process.inputStream.bufferedReader().use { reader ->
                    val current = parse(reader.lineSequence())
                    // Drain the pipe so closing it early does not interrupt Telecom's dump writer.
                    val buffer = CharArray(2048)
                    while (reader.read(buffer) != -1) { /* Discard diagnostics and call history. */ }
                    current
                }
                if (process.waitFor(50, TimeUnit.MILLISECONDS) && process.exitValue() == 0) identity else null
            } finally {
                process.destroyForcibly()
            }
        }.getOrNull()

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
            if (!record.endsWith("]") && !record.endsWith("], voip=false")) return null
            if (!record.startsWith(CALL_PREFIX) || ", childs(0), has_parent(false)," !in record) return null
            val id = record.substring(CALL_PREFIX.length).substringBefore(',')
            val digits = id.removePrefix("TC@")
            if (digits == id || digits.length !in 1..19 || digits.any { it !in '0'..'9' }) return null
            return id.takeIf { record.startsWith(CALL_PREFIX + id + ACTIVE_PSTN_FIELDS) }
        }
    }
}
