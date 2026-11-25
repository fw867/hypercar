package io.github.hypercar.net

import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.wifi.WifiManager
import java.net.Inet6Address
import java.net.InetAddress

/** A Wi-Fi capability alone is not proof that an unvalidated AP belongs to this car. */
internal object CarWifiPolicy {
    fun visibleSsid(value: String?): String? =
        value?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"")?.takeUnless { it.isBlank() }

    fun hasUsableAddress(link: LinkProperties?): Boolean =
        link?.linkAddresses?.any { isUsableAddress(it.address) } == true

    fun isUsableAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress &&
            (address is Inet6Address || !address.isLinkLocalAddress)

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
