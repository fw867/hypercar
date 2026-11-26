package io.github.hypercar.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Pick a channel for each request and share one deadline across the initial join and fallback. */
internal object WifiApRetry {
    private const val HISTORICAL_CHECK_MS = 4_000L
    private const val PROTOCOL_CHECK_MS = 12_000L

    data class Budget(val associationWaitMs: Long, val deadlineMillis: Long, val hintCheckMillis: Long? = null)

    data class Target(val ap: WifiApHint?, val protocolFrequency: Int? = null) {
        val frequency: Int? get() = protocolFrequency ?: ap?.takeIf {
            it.ageSeconds <= WifiApHint.MAX_SCAN_AGE_SECONDS && it.frequency in 2400..7125
        }?.frequency
        val source: String get() = when {
            protocolFrequency != null -> "iap2"
            frequency != null -> "scan"
            else -> "full_scan"
        }
    }

    suspend fun <T> join(hint: WifiApHint?, normalWaitMs: Long, current: () -> Boolean,
        associated: () -> Boolean, protocolFrequency: Int? = null, retryAllowed: () -> Boolean = { true },
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        mayStartRequest: suspend () -> Boolean = { true },
        attempt: suspend (Target, Budget) -> T?): T? {
        val started = now()
        val deadline = started + normalWaitMs
        // Only pin the AP on the first request if its scan agrees with the supplied channel.
        // An exact BSSID can otherwise go straight through Android's cached approval path.
        val first = Target(hint?.takeIf { protocolFrequency == null || it.frequency == protocolFrequency }, protocolFrequency)
        val fallback = when {
            protocolFrequency != null -> Target(hint?.takeUnless { it.frequency == protocolFrequency })
            hint?.fromMemory == true -> Target(null)
            else -> null
        }
        val checkAfter = when {
            protocolFrequency != null && fallback?.ap != null -> PROTOCOL_CHECK_MS
            protocolFrequency == null && hint?.fromMemory == true -> HISTORICAL_CHECK_MS
            else -> null
        }
        val hintCheck = checkAfter?.let { started + it }?.takeIf { it < deadline }
        if (!mayStartRequest()) return null
        // Android applies the requestNetwork timeout before the app can inspect STA progress.
        val result = attempt(first, Budget(normalWaitMs, deadline, hintCheck))
        if (result != null || fallback == null) return result
        currentCoroutineContext().ensureActive()
        // A delivered request, including one still waiting for DHCP/capabilities, belongs
        // to the Wi-Fi lifetime. Do not withdraw or replace it to retry an AP hint.
        if (!current() || associated() || !retryAllowed() || !mayStartRequest()) return null
        val fallbackWait = (deadline - now()).coerceIn(0, normalWaitMs)
        if (fallbackWait == 0L) return null
        return attempt(fallback, Budget(fallbackWait, deadline))
    }

    /** Both network requests belong to one admitted join, not two automatic ACL attempts. */
    class Admission(private val current: () -> Boolean, private val claim: (() -> Unit) -> Boolean) {
        private var admitted = false
        fun isCurrent(): Boolean = admitted && current()
        fun submit(request: () -> Unit): Boolean {
            if (!current()) return false
            if (!admitted) return claim(request).also { if (it) admitted = true }
            request()
            return true
        }
    }
}
