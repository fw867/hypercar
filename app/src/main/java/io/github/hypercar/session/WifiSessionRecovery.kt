package io.github.hypercar.session

/** Memory-only permission to repair a recently interrupted, receiver-initiated media session. */
internal class WifiSessionRecovery {
    private data class Scope(val receiver: String, val ssid: String)
    private data class Window(val scope: Scope, val started: Long, var attempts: Int = 0)
    private var working: Scope? = null
    private var window: Window? = null

    private fun scope(receiver: String?, ssid: String?): Scope? =
        if (receiver.isNullOrBlank() || ssid.isNullOrBlank()) null else Scope(receiver.lowercase(), ssid)

    /** Caller requires fresh receiver invitation/iAP2 origin and observed video writes, not control ACK. */
    @Synchronized fun mediaWorking(receiver: String?, ssid: String?, receiverInitiated: Boolean): Boolean {
        if (!receiverInitiated) return false // proactive media must never grant or replenish its own permission
        val next = scope(receiver, ssid) ?: return false
        val changed = working != next
        working = next
        window = null
        return changed
    }

    @Synchronized fun interrupted(receiver: String?, ssid: String?, now: Long): Boolean {
        val current = scope(receiver, ssid)
        val opened = current != null && working == current
        if (opened) window = Window(current!!, now)
        working = null // repeated failures cannot extend the original recovery deadline
        return opened
    }

    @Synchronized fun allows(receiver: String?, ssid: String?, now: Long): Boolean {
        val w = window ?: return false
        val current = scope(receiver, ssid) ?: return false
        if (current != w.scope || now < w.started || now - w.started >= WINDOW_MS) {
            window = null
            return false
        }
        return w.attempts < MAX_ATTEMPTS
    }

    /** Spend once per proactive connection attempt, never while polling or waiting for invites. */
    @Synchronized fun reserve(receiver: String?, ssid: String?, now: Long): Boolean {
        if (!allows(receiver, ssid, now)) return false
        window!!.attempts++
        return true
    }

    @Synchronized fun firstAttempt(receiver: String?, ssid: String?, now: Long): Boolean =
        allows(receiver, ssid, now) && window!!.attempts == 0

    /** Scheduling only; live network/identity checks still run before the actual connection. */
    @Synchronized fun reconnectDelay(receiver: String?, now: Long, fallback: Long): Long {
        val w = window ?: return fallback
        return if (firstAttempt(receiver, w.scope.ssid, now)) 0L else fallback
    }

    @Synchronized fun clear() { working = null; window = null }

    companion object {
        const val WINDOW_MS = 120_000L
        const val MAX_ATTEMPTS = ReconnectBudget.MAX_RETRIES
    }
}
