package io.github.hypercar.net

/** A Wi-Fi association outlives CarPlay pause/failure; caller serializes operations. */
internal class WifiRecoveryLease<T : Any>(private val release: (T) -> Unit) {
    private var owner: T? = null
    private var receiver = ""

    fun isRetained(request: T): Boolean = owner === request

    fun retain(request: T, peer: String) {
        if (owner === request) return
        close()
        owner = request
        receiver = peer
    }

    fun adopt(request: T, peer: String, confirmedBluetoothHotspot: Boolean = false): Boolean {
        val confirmedUnknown = receiver.isEmpty() && peer.isNotEmpty() && confirmedBluetoothHotspot
        if (owner !== request || (receiver != peer && !confirmedUnknown)) return false
        owner = null
        return true
    }

    /** Only real network loss or replacing this association releases its request. */
    fun close() {
        val previous = owner
        owner = null
        if (previous != null) release(previous)
    }
}
