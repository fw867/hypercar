package io.github.hypercar.session

/**
 * BMW secondary boarding drops classic ACL before process bind finishes.
 * Keep the invite wait when the phone is already on the known car Wi-Fi.
 * An already-bound session stays up; an unbound one is kept only while still negotiating.
 */
internal object AclHandoffKeep {
    fun keepInviteWait(
        sessionActive: Boolean,
        streaming: Boolean,
        processBound: Boolean,
        knownCarWifiPresent: Boolean,
    ): Boolean {
        if (!sessionActive) return false
        if (processBound) return true
        return knownCarWifiPresent && !streaming
    }
}
