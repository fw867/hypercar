package io.github.hypercar.headunit

/** Actual receiver identity overrides the manually selected vehicle profile for desktop testing. */
internal object ReceiverCompatibility {
    fun isDesktopReceiver(profileId: String, receiverIdentity: String?): Boolean {
        val identity = receiverIdentity.orEmpty().lowercase()
        return profileId == "winplay" || identity.contains("winplay") || identity.contains("diplay")
    }
    fun useBmwBootstrap(profileId: String, receiverIdentity: String?): Boolean =
        profileId.startsWith("bmw") && !isDesktopReceiver(profileId, receiverIdentity)
}
