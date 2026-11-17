package io.github.hypercar.session

/** An ACL disconnect may refresh discovery during the invitation wait. */
internal object AclHandoffKeep {
    fun shouldRefreshInvitation(sessionActive: Boolean, mediaStarted: Boolean, carWifiPresent: Boolean): Boolean =
        sessionActive && !mediaStarted && carWifiPresent
}
