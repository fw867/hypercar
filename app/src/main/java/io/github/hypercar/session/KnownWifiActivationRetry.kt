package io.github.hypercar.session

/** Transport failures back off; activation closure waits for an invitation or a bounded fresh wake.
 * Both are scoped to one connection attempt, bound network and receiver. */
internal class KnownWifiActivationRetry {
    private var network: String? = null
    private var receiver: String? = null
    private var failures = 0
    private var retryAt = 0L
    private var invitationRequired = false
    private var exhausted = false
    private var activationFailedAt = 0L
    private var blockedWakeRevision = 0L
    private var stateRecoveries = 0
    /** Uninvited self-dialed :7000 closed after RECORD. Do not replay that port. */
    private var replayBlocked = false

    data class StateRetry(val number: Int, val totalRetries: Int)

    fun matches(network: String?, mac: String?): Boolean = network != null &&
        this.network == network && receiver != null && receiver == PairingEvidence.receiverKey(mac)

    fun failed(network: String?, mac: String?, now: Long, waitForInvitation: Boolean = false,
               wakeRevision: Long = 0L): Long? {
        val key = PairingEvidence.receiverKey(mac)
        if (network == null || key == null) { clear(); return null }
        if (!matches(network, mac)) clear()
        this.network = network
        receiver = key
        if (replayBlocked) {
            invitationRequired = true
            return null
        }
        if (waitForInvitation && !invitationRequired) {
            activationFailedAt = now
            blockedWakeRevision = wakeRevision
        }
        invitationRequired = invitationRequired || waitForInvitation
        if (invitationRequired) return null
        if (failures >= MAX_RETRIES) { exhausted = true; return null }
        val delay = DELAYS[failures.coerceAtMost(DELAYS.lastIndex)]
        failures = (failures + 1).coerceAtMost(MAX_RETRIES)
        retryAt = now + delay
        return delay
    }

    /** One reservation per new post-failure connection edge, after the entire ACL burst settles.
     * A short ACL may retire normally onto Wi-Fi; it need not remain connected, but flapping
     * keeps restarting the quiet interval. Neither ACL events nor time alone replenish budgets. */
    fun recoverAfterStateChange(
        eligible: Boolean, paused: Boolean, network: String?, mac: String?, now: Long,
        wake: CarActivationWakeEvidence.Snapshot?,
    ): StateRetry? {
        if (replayBlocked || !eligible || paused || !matches(network, mac) || !invitationRequired || exhausted ||
            stateRecoveries >= MAX_STATE_RECOVERIES || failures >= MAX_RETRIES || wake == null ||
            wake.receiver != receiver || wake.connectedRevision <= blockedWakeRevision ||
            wake.lastChangedAt < activationFailedAt || now < activationFailedAt || now < wake.lastChangedAt ||
            now - activationFailedAt < ACTIVATION_COOLDOWN_MILLIS ||
            now - wake.lastChangedAt < WAKE_SETTLE_MILLIS) return null
        failures++
        stateRecoveries++
        blockedWakeRevision = wake.connectedRevision
        invitationRequired = false
        retryAt = now
        return StateRetry(stateRecoveries, failures)
    }

    /** No ctrl invite and no iAP2: the head unit closed the phone-dialed :7000 after RECORD.
     * Stay on the invite wait. A later ACL edge must not replay this port. */
    fun suppressUninvitedReplay(network: String?, mac: String?) {
        val key = PairingEvidence.receiverKey(mac)
        if (network == null || key == null) return
        if (!matches(network, mac)) clear()
        this.network = network
        receiver = key
        invitationRequired = true
        replayBlocked = true
        retryAt = 0
    }

    fun ready(network: String?, mac: String?, now: Long): Boolean =
        matches(network, mac) && !replayBlocked && !invitationRequired && !exhausted && now >= retryAt

    fun allowsRetry(eligible: Boolean, paused: Boolean, network: String?, mac: String?, now: Long): Boolean =
        eligible && !paused && network != null && PairingEvidence.receiverKey(mac) != null &&
            (!matches(network, mac) || ready(network, mac, now))

    fun clear() {
        network = null; receiver = null; failures = 0; retryAt = 0
        invitationRequired = false; exhausted = false; replayBlocked = false
        activationFailedAt = 0; blockedWakeRevision = 0; stateRecoveries = 0
    }

    companion object {
        const val MAX_RETRIES = 5
        const val MAX_STATE_RECOVERIES = 2
        const val ACTIVATION_COOLDOWN_MILLIS = 30_000L
        const val WAKE_SETTLE_MILLIS = 10_000L
        private val DELAYS = longArrayOf(10_000, 20_000, 40_000, 60_000)
        const val UNINVITED_PORT = 7000
    }
}

/** Phone-dialed :7000 on known car Wi-Fi, with no ctrl invite and no iAP2, closed after RECORD. */
internal fun suppressUninvitedPortReplay(
    proactive: Boolean,
    fromInvite: Boolean,
    ctrlInvited: Boolean,
    iap2Started: Boolean,
    knownWifi: Boolean,
    port: Int,
    closedAfterRecord: Boolean,
): Boolean = proactive && !fromInvite && !ctrlInvited && !iap2Started && knownWifi &&
    port == KnownWifiActivationRetry.UNINVITED_PORT && closedAfterRecord
