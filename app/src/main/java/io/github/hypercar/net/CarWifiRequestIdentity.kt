package io.github.hypercar.net

/** Credentials identify the live local-only request; never persist or log its password. */
internal class CarWifiRequestIdentity(val ssid: String, val passphrase: String, val securityType: Int? = null) {
    fun requiresDifferentHotspot(requestedSsid: String?): Boolean =
        !requestedSsid.isNullOrBlank() && requestedSsid != ssid
    fun matches(requestedSsid: String?, requestedPassphrase: String?): Boolean =
        !requestedSsid.isNullOrBlank() && requestedSsid == ssid && requestedPassphrase == passphrase

    fun matchesRequest(requestedSsid: String?, requestedPassphrase: String?, requestedSecurity: Int?, associated: Boolean = false): Boolean =
        matches(requestedSsid, requestedPassphrase) && (associated || securityMatches(requestedSecurity))

    private fun securityMatches(requestedSecurity: Int?): Boolean {
        if (requestedSecurity == null || requestedSecurity == securityType) return true
        val applied = CarWifiJoinPolicy.security(securityType, passphrase) ?: return false
        return applied == CarWifiJoinPolicy.security(requestedSecurity, passphrase)
    }

    fun requiresCredentialUpdate(requestedSsid: String?, requestedPassphrase: String?, requestedSecurity: Int? = null,
        associated: Boolean = false): Boolean =
        !requestedSsid.isNullOrBlank() && requestedPassphrase != null &&
            !matchesRequest(requestedSsid, requestedPassphrase, requestedSecurity, associated)
}
