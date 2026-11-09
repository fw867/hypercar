package io.github.hypercar.session

/** Actual ACL edges, not duplicate receiver/service callbacks or repeated connected snapshots. */
internal class CarActivationWakeEvidence {
    data class Snapshot(val receiver: String, val connectedRevision: Long, val lastChangedAt: Long)
    private data class State(val connected: Boolean, val snapshot: Snapshot)
    private val peers = linkedMapOf<String, State>()
    private var revision = 0L

    @Synchronized fun observe(mac: String?, connected: Boolean, now: Long) {
        val receiver = PairingEvidence.receiverKey(mac) ?: return
        val previous = peers[receiver]
        if (previous?.connected == connected) return
        val connectedRevision = if (connected) ++revision else previous?.snapshot?.connectedRevision ?: 0L
        peers[receiver] = State(connected, Snapshot(receiver, connectedRevision, now))
        // AutoStart filters car peers already. Bound retained metadata across target changes.
        if (peers.size > 8) peers.remove(peers.keys.first())
    }

    @Synchronized fun snapshot(mac: String?): Snapshot? =
        PairingEvidence.receiverKey(mac)?.let { peers[it]?.snapshot }
}
