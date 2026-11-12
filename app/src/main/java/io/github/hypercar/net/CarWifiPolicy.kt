package io.github.hypercar.net

import android.net.NetworkCapabilities

/** An unvalidated system internet AP must be reacquired as a local-only request. */
internal object CarWifiPolicy {
    fun canReuse(caps: NetworkCapabilities?): Boolean = caps != null &&
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && safe(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

    fun safe(internet: Boolean, validated: Boolean): Boolean = !internet || validated

    /** Desktop receivers share an internet LAN; their device MAC is not the router's BSSID. */
    fun canReuseDesktop(peerIdentifiedAsDesktop: Boolean, advertisedSsid: String?, currentSsid: String?,
                        hasAddress: Boolean, validated: Boolean): Boolean =
        peerIdentifiedAsDesktop && validated && hasAddress &&
            !advertisedSsid.isNullOrBlank() && advertisedSsid == currentSsid
}
