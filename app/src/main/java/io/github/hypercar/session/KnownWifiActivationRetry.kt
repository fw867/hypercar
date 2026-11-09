package io.github.hypercar.session

/** Transport failures back off; activation closure waits for an actual invitation.
 * Both are scoped to one connection attempt, bound network and receiver. */
internal class KnownWifiActivationRetry {
    private var network: String? = null
    private var receiver: String? = null
    private var failures = 0
    private var retryAt = 0L
    private var invitationRequired = false
    private var exhausted = false

    fun matches(network: String?, mac: String?): Boolean = network != null &&
        this.network == network && receiver != null && receiver == PairingEvidence.receiverKey(mac)

    fun failed(network: String?, mac: String?, now: Long, waitForInvitation: Boolean = false): Long? {
        val key = PairingEvidence.receiverKey(mac)
        if (network == null || key == null) { clear(); return null }
        if (!matches(network, mac)) { failures = 0; invitationRequired = false; exhausted = false }
        this.network = network
        receiver = key
        invitationRequired = invitationRequired || waitForInvitation
        if (invitationRequired) return null
        if (failures >= MAX_RETRIES) { exhausted = true; return null }
        val delay = DELAYS[failures.coerceAtMost(DELAYS.lastIndex)]
        failures = (failures + 1).coerceAtMost(MAX_RETRIES)
        retryAt = now + delay
        return delay
    }

    fun ready(network: String?, mac: String?, now: Long): Boolean =
        matches(network, mac) && !invitationRequired && !exhausted && now >= retryAt

    fun allowsRetry(eligible: Boolean, paused: Boolean, network: String?, mac: String?, now: Long): Boolean =
        eligible && !paused && network != null && PairingEvidence.receiverKey(mac) != null &&
            (!matches(network, mac) || ready(network, mac, now))

    fun clear() { network = null; receiver = null; failures = 0; retryAt = 0; invitationRequired = false; exhausted = false }

    private companion object { const val MAX_RETRIES = 5; val DELAYS = longArrayOf(10_000, 20_000, 40_000, 60_000) }
}
