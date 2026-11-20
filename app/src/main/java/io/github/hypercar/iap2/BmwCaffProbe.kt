package io.github.hypercar.iap2

/** One opportunity per connection attempt, even when the ordinary profiles reconnect. */
internal class BmwCaffProbe(val attempt: Long) {
    companion object {
        const val NORMAL_GRACE_MS = 3_000L
        const val CTRL_GRACE_MS = 15_000L

        fun target(profile: String, explicit: String?, hint: String?, bonded: Set<String>, knownBmw: Set<String>): String? {
            if (profile != "bmw_id85") return null
            if (!explicit.isNullOrBlank()) return bonded.firstOrNull { it.equals(explicit, true) }
            val eligible = bonded.intersect(knownBmw)
            return eligible.firstOrNull { it.equals(hint, true) } ?: eligible.singleOrNull()
        }
    }
    private var peer: String? = null
    private var connectedSince = 0L
    @Volatile var attempted = false
        private set
    @Volatile var finished = false
        private set
    private var closePending: (() -> Unit)? = null
    private var advertised = false
    private var ctrlReadyLast = false
    private var ordinarySince = 0L

    enum class Decision { WAIT, ENTER_CTRL_GRACE, DIAL }

    /** A healthy local listener is not an invite: prefer inbound on brief profile windows,
     * but never starve the one UUID-based dial when the same peer stays online. */
    @Synchronized fun poll(connectedPeer: String?, now: Long, ctrlReady: Boolean): Decision {
        if (finished || attempted) return Decision.WAIT
        if (peer != connectedPeer) {
            peer = connectedPeer
            connectedSince = now
            ordinarySince = now
            ctrlReadyLast = false
        }
        if (connectedPeer == null) return Decision.WAIT
        val enteringCtrl = ctrlReady && !ctrlReadyLast
        if (!ctrlReady && ctrlReadyLast) ordinarySince = now
        ctrlReadyLast = ctrlReady
        if (now - connectedSince < CTRL_GRACE_MS && (ctrlReady || now - ordinarySince < NORMAL_GRACE_MS)) {
            return if (enteringCtrl) Decision.ENTER_CTRL_GRACE else Decision.WAIT
        }
        attempted = true
        return Decision.DIAL
    }

    @Synchronized fun recordSdp(present: Boolean): Boolean {
        if (!attempted || finished || advertised) return false
        advertised = present
        finished = !present
        return present
    }

    @Synchronized fun attach(close: () -> Unit): Boolean {
        if (!advertised || finished || closePending != null) return false
        closePending = close
        return true
    }

    @Synchronized fun promote(): Boolean {
        if (finished || closePending == null) return false
        closePending = null
        finished = true
        return true
    }

    @Synchronized fun cancel() {
        finished = true
        closePending?.invoke()
        closePending = null
    }
}
