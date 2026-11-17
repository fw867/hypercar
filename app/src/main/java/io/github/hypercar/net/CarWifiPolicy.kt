package io.github.hypercar.net

import android.net.NetworkCapabilities

/** A Wi-Fi capability alone is not proof that an unvalidated AP belongs to this car. */
internal object CarWifiPolicy {
    fun canReuse(caps: NetworkCapabilities?, verifiedRequest: Boolean = false): Boolean = caps != null &&
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && safe(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), verifiedRequest)

    fun safe(internet: Boolean, validated: Boolean, verifiedRequest: Boolean = false): Boolean =
        !internet || validated || verifiedRequest

    fun samePairedHotspot(pairedPeer: Boolean, freshInformation: Boolean,
                          announcedSsid: String?, currentSsid: String?): Boolean =
        pairedPeer && freshInformation && !announcedSsid.isNullOrBlank() && announcedSsid == currentSsid

    /** A current invitation on the already identified car network can arrive before 0x5703. */
    fun canReuseInvitedCar(knownCar: Boolean, sameNetwork: Boolean, peerMatches: Boolean,
                          announcedSsid: String?, currentSsid: String?,
                          hasAddress: Boolean, routeToPeer: Boolean, boundSsid: String? = null): Boolean {
        val ssid = currentSsid ?: boundSsid
        return knownCar && sameNetwork && peerMatches && hasAddress && routeToPeer &&
            !ssid.isNullOrBlank() && (boundSsid == null || boundSsid == ssid) &&
            (announcedSsid.isNullOrBlank() || announcedSsid == ssid)
    }

    /** Desktop receivers share an internet LAN; their device MAC is not the router's BSSID. */
    fun canReuseDesktop(peerIdentifiedAsDesktop: Boolean, advertisedSsid: String?, currentSsid: String?,
                        hasAddress: Boolean, validated: Boolean): Boolean =
        peerIdentifiedAsDesktop && validated && hasAddress &&
            !advertisedSsid.isNullOrBlank() && advertisedSsid == currentSsid
}
