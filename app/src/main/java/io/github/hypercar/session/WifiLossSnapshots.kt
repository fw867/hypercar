package io.github.hypercar.session

import io.github.hypercar.iap2.RfcommClose

/** Android loss and native termination share the first RFCOMM cutoff for this network and attempt. */
internal class WifiLossSnapshots<N>(val attempt: Long) {
    private val snapshots = mutableMapOf<N, RfcommClose?>()
    private val rfcommBindings = mutableMapOf<N, Long>()
    private var retired = false

    @Synchronized fun bindRfcomm(network: N, registration: Long) {
        if (retired || snapshots.containsKey(network)) return
        rfcommBindings.entries.removeAll { it.value == registration }
        rfcommBindings[network] = registration
    }

    @Synchronized fun boundRfcomm(network: N): Long? = rfcommBindings[network]

    @Synchronized fun first(network: N, capture: (Long?) -> RfcommClose?): RfcommClose? {
        if (retired) return null
        if (!snapshots.containsKey(network)) snapshots[network] = capture(rfcommBindings.remove(network))
        return snapshots[network]
    }

    @Synchronized fun retire() {
        retired = true
        snapshots.clear()
        rfcommBindings.clear()
    }
}
