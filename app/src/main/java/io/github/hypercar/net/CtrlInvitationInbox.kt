package io.github.hypercar.net

internal class CtrlInvitation<N : Any>(
    val host: String,
    val receivedAtMillis: Long,
    private val epoch: CtrlInvitationInbox.Epoch<N>,
    val receiverId: String?,
) {
    val network: N get() = epoch.network
    fun isOn(network: N?): Boolean = epoch.isCurrent && epoch.network == network
}

/** The advertiser serializes network changes and publication on the same monitor. */
internal class CtrlInvitationInbox<N : Any, A : Any, V : Any>(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val publish: (Epoch<N>, V) -> Unit,
) {
    companion object { const val CONFIRMATION_WAIT_MILLIS = 2_000L }
    class Epoch<N>(val network: N) {
        @Volatile var isCurrent = true
            internal set
    }
    class Receipt<N>(val epoch: Epoch<N>, val sequence: Long)
    private class Candidate<V>(val sequence: Long, val value: V, val expiresAt: Long)

    val latest = PendingInvitation<V>()
    var current: Epoch<N>? = null
        private set
    private var advertised = false
    private var localAddresses = emptySet<A>()
    private var sequence = 0L
    private var published = 0L
    private val waiting = mutableMapOf<A, Candidate<V>>()
    private val acknowledging = mutableSetOf<Receipt<N>>()
    private var connectRequestsRetired = false
    private var retryConfirmationDeadline = Long.MAX_VALUE
    @Volatile var awaitingConfirmation: Boolean = false
        private set

    fun select(network: N?): Epoch<N>? {
        if (current?.network == network) return current
        current?.isCurrent = false
        current = network?.let { Epoch(it) }
        advertised = false
        localAddresses = emptySet()
        waiting.clear()
        awaitingConfirmation = false
        latest.set(null)
        return current
    }

    fun accept(listener: Epoch<N>): Receipt<N>? =
        if (current === listener) Receipt(listener, ++sequence) else null

    fun beginAcknowledgement(receipt: Receipt<N>): Boolean =
        !connectRequestsRetired && clockMillis() < retryConfirmationDeadline &&
            current === receipt.epoch && acknowledging.add(receipt)

    fun finishAcknowledgement(receipt: Receipt<N>) { acknowledging.remove(receipt) }

    fun acknowledge(receipt: Receipt<N>, local: A, value: V) {
        if (!acknowledging.remove(receipt)) return
        offer(receipt, local, value)
    }

    fun canAcknowledge(receipt: Receipt<N>): Boolean =
        !connectRequestsRetired && current === receipt.epoch && receipt in acknowledging

    fun retireConnectRequests(confirmationDeadline: Long = Long.MAX_VALUE): Boolean {
        retryConfirmationDeadline = minOf(retryConfirmationDeadline, confirmationDeadline)
        if (acknowledging.isNotEmpty()) return false
        expireConfirmation()
        if (awaitingConfirmation) return false
        connectRequestsRetired = true
        return true
    }

    fun keepConnectRequests() {
        if (retryConfirmationDeadline != Long.MAX_VALUE) expireConfirmation()
        retryConfirmationDeadline = Long.MAX_VALUE
    }

    fun offer(receipt: Receipt<N>, local: A, value: V) {
        if (connectRequestsRetired || current !== receipt.epoch || receipt.sequence <= published) return
        val previous = waiting[local]
        if (previous != null && previous.sequence >= receipt.sequence) return
        awaitingConfirmation = true
        waiting[local] = Candidate(receipt.sequence, value, clockMillis() + CONFIRMATION_WAIT_MILLIS)
        flush(receipt.epoch)
    }

    fun advertise(epoch: Epoch<N>) {
        if (current !== epoch) return
        advertised = true
        flush(epoch)
    }

    fun addresses(epoch: Epoch<N>, addresses: Set<A>) {
        if (current !== epoch) return
        localAddresses = addresses
        flush(epoch)
    }

    private fun flush(epoch: Epoch<N>) {
        if (retryConfirmationDeadline != Long.MAX_VALUE) expireConfirmation()
        if (!advertised) return
        val next = localAddresses.mapNotNull(waiting::get).maxByOrNull { it.sequence } ?: return
        published = next.sequence
        waiting.entries.removeAll { it.value.sequence <= published }
        latest.set(next.value)
        awaitingConfirmation = waiting.isNotEmpty()
        publish(epoch, next.value)
    }

    private fun expireConfirmation() {
        val now = clockMillis()
        waiting.entries.removeAll { now >= minOf(it.value.expiresAt, retryConfirmationDeadline) }
        awaitingConfirmation = waiting.isNotEmpty()
    }
}
