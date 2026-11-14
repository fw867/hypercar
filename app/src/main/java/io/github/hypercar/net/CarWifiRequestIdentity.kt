package io.github.hypercar.net

/** Credentials identify the live local-only request; never persist or log its password. */
internal class CarWifiRequestIdentity(private val ssid: String, private val passphrase: String) {
    fun matches(requestedSsid: String?, requestedPassphrase: String?): Boolean =
        !requestedSsid.isNullOrBlank() && requestedSsid == ssid && requestedPassphrase == passphrase
}
