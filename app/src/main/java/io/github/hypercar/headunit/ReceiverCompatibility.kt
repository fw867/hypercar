package io.github.hypercar.headunit

/** Actual receiver identity overrides the manually selected vehicle profile for desktop testing. */
internal object ReceiverCompatibility {
    fun useBmwBootstrap(profileId: String, receiverIdentity: String?): Boolean {
        val identity = receiverIdentity.orEmpty().lowercase()
        return profileId.startsWith("bmw") && !identity.contains("winplay") && !identity.contains("diplay")
    }
}
