package io.github.hypercar.net

/** Credentials identify the live local-only request; never persist or log its password. */
internal class CarWifiRequestIdentity(val ssid: String, val passphrase: String) {
    fun requiresDifferentHotspot(requestedSsid: String?): Boolean =
        !requestedSsid.isNullOrBlank() && requestedSsid != ssid
    fun matches(requestedSsid: String?, requestedPassphrase: String?): Boolean =
        !requestedSsid.isNullOrBlank() && requestedSsid == ssid && requestedPassphrase == passphrase

    fun requiresCredentialUpdate(requestedSsid: String?, requestedPassphrase: String?): Boolean =
        !requestedSsid.isNullOrBlank() && requestedPassphrase != null && !matches(requestedSsid, requestedPassphrase)
}
