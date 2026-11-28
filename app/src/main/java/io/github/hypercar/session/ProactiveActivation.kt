package io.github.hypercar.session

import io.github.hypercar.net.CarWifiRequestIdentity
import io.github.hypercar.iap2.RfcommClose
import kotlinx.coroutines.CompletableDeferred

/** One native activation, ending at a real video TCP connection (not merely RECORD/connected).
 * Keep rejected callbacks contained until nativeStop has joined the old engine. */
internal class ProactiveActivation {
    data class WifiLoss(val credentials: CarWifiRequestIdentity?, val observedAt: Long, val rfcommClose: RfcommClose?)

    val result = CompletableDeferred<String?>()
    private var ready = false
    @Volatile var wifiLoss: WifiLoss? = null
        private set
    @Volatile var requiresFreshInvitation = false
        private set
    @Volatile var rejected = false
        private set

    @Synchronized fun videoReady() {
        if (rejected) return
        ready = true
        result.complete(null)
    }

    @Synchronized fun interceptWifiLoss(credentials: CarWifiRequestIdentity?, observedAt: Long,
                                        rfcommClose: RfcommClose? = null): Boolean {
        if (ready) return false
        if (wifiLoss == null) wifiLoss = WifiLoss(credentials, observedAt, rfcommClose)
        return interceptTerminal("视频建立前车机 Wi-Fi 已断开")
    }

    @Synchronized fun interceptTerminal(detail: String): Boolean {
        if (ready) return false
        // A reachable/verified receiver can still have no active CarPlay window.
        // Replaying RECORD on that same network is not an activation mechanism.
        requiresFreshInvitation = true
        rejected = true
        result.complete(detail)
        return true
    }
}
