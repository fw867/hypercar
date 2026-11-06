package io.github.hypercar.session

/** Failed proactive activation never permanently disables a trusted Wi-Fi restore path.
 * Delays are scoped to the exact bound network and receiver; old endpoint hints cannot cross them. */
internal class KnownWifiActivationRetry {
    private var network: String? = null
    private var receiver: String? = null
    private var failures = 0
    private var retryAt = 0L

    fun matches(network: String?, mac: String?): Boolean = network != null &&
        this.network == network && receiver != null && receiver == PairingEvidence.receiverKey(mac)

    fun failed(network: String?, mac: String?, now: Long): Long? {
        val key = PairingEvidence.receiverKey(mac)
        if (network == null || key == null) { clear(); return null }
        if (!matches(network, mac)) failures = 0
        this.network = network
        receiver = key
        val delay = DELAYS[failures.coerceAtMost(DELAYS.lastIndex)]
        failures = (failures + 1).coerceAtMost(DELAYS.size)
        retryAt = now + delay
        return delay
    }

    fun ready(network: String?, mac: String?, now: Long): Boolean = matches(network, mac) && now >= retryAt

    fun allowsRetry(eligible: Boolean, paused: Boolean, network: String?, mac: String?, now: Long): Boolean =
        eligible && !paused && network != null && PairingEvidence.receiverKey(mac) != null &&
            (!matches(network, mac) || ready(network, mac, now))

    fun clear() { network = null; receiver = null; failures = 0; retryAt = 0 }

    private companion object { val DELAYS = longArrayOf(10_000, 20_000, 40_000, 60_000) }
}
