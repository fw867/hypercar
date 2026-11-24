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
internal class CtrlInvitationInbox<N : Any, A : Any, V : Any>(private val publish: (Epoch<N>, V) -> Unit) {
    class Epoch<N>(val network: N) {
        @Volatile var isCurrent = true
            internal set
    }
    class Receipt<N>(val epoch: Epoch<N>, val sequence: Long)
    private class Candidate<V>(val sequence: Long, val value: V)

    val latest = PendingInvitation<V>()
    var current: Epoch<N>? = null
        private set
    private var advertised = false
    private var localAddresses = emptySet<A>()
    private var sequence = 0L
    private var published = 0L
    private val waiting = mutableMapOf<A, Candidate<V>>()
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

    fun offer(receipt: Receipt<N>, local: A, value: V) {
        if (current !== receipt.epoch || receipt.sequence <= published) return
        val previous = waiting[local]
        if (previous != null && previous.sequence >= receipt.sequence) return
        awaitingConfirmation = true
        waiting[local] = Candidate(receipt.sequence, value)
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
        if (!advertised) return
        val next = localAddresses.mapNotNull(waiting::get).maxByOrNull { it.sequence } ?: return
        published = next.sequence
        waiting.entries.removeAll { it.value.sequence <= published }
        latest.set(next.value)
        awaitingConfirmation = waiting.isNotEmpty()
        publish(epoch, next.value)
    }
}
