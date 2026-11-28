package io.github.hypercar.session

import io.github.hypercar.iap2.RfcommClose

/** Android loss and native termination share the first RFCOMM cutoff for this network and attempt. */
internal class WifiLossSnapshots<N>(val attempt: Long) {
    private val snapshots = mutableMapOf<N, RfcommClose?>()
    private var retired = false

    @Synchronized fun first(network: N, capture: () -> RfcommClose?): RfcommClose? {
        if (retired) return null
        if (!snapshots.containsKey(network)) snapshots[network] = capture()
        return snapshots[network]
    }

    @Synchronized fun retire() {
        retired = true
        snapshots.clear()
    }
}
