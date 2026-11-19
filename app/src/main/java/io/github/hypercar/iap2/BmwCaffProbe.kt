package io.github.hypercar.iap2

/** One opportunity per connection attempt, even when the ordinary profiles reconnect. */
internal class BmwCaffProbe(val attempt: Long) {
    companion object {
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

    @Synchronized fun due(connectedPeer: String?, now: Long): Boolean {
        if (finished || attempted) return false
        if (peer != connectedPeer) {
            peer = connectedPeer
            connectedSince = now
        }
        if (peer == null || now - connectedSince < 3_000) return false
        attempted = true
        return true
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
