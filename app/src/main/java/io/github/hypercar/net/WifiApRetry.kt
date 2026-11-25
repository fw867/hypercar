package io.github.hypercar.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A historical AP can disappear after scanning. Never let it consume the whole restore budget. */
internal object WifiApRetry {
    private const val HISTORICAL_WAIT_MS = 4_000L

    suspend fun <T> join(hint: WifiApHint?, normalWaitMs: Long, current: () -> Boolean,
        associated: () -> Boolean, channelHint: Boolean = false, retryAllowed: () -> Boolean = { true },
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        attempt: suspend (WifiApHint?, Long) -> T?): T? {
        val started = now()
        val boundedHint = hint?.fromMemory == true || channelHint
        // A channel-only request still needs the normal consent/association window.
        val result = attempt(hint, if (hint?.fromMemory == true) minOf(HISTORICAL_WAIT_MS, normalWaitMs) else normalWaitMs)
        if (result != null || !boundedHint) return result
        currentCoroutineContext().ensureActive()
        // A delivered request, including one still waiting for DHCP/capabilities, belongs
        // to the Wi-Fi lifetime. Do not withdraw or replace it to retry an AP hint.
        if (!current() || associated() || !retryAllowed()) return null
        val fallbackWait = if (channelHint) (normalWaitMs - (now() - started).coerceAtLeast(0)).coerceAtLeast(0)
            else normalWaitMs
        if (fallbackWait == 0L) return null
        return attempt(null, fallbackWait)
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
