package io.github.hypercar.headunit

/** Actual receiver identity overrides the manually selected vehicle profile for desktop testing. */
internal object ReceiverCompatibility {
    fun isDesktopReceiver(profileId: String, receiverIdentity: String?): Boolean {
        val identity = receiverIdentity.orEmpty().lowercase()
        return profileId == "winplay" || identity.contains("winplay") || identity.contains("diplay")
    }
    fun matchesAirplayService(profileId: String, receiverIdentity: String?, instance: String, model: String?): Boolean {
        val actual = receiverIdentity.orEmpty().lowercase()
        val service = "$instance ${model.orEmpty()}".lowercase()
        return when {
            actual.contains("diplay") || actual.contains("xcertplay") -> service.contains("diplay") || service.contains("xcertplay")
            profileId == "winplay" || actual.contains("winplay") -> service.contains("winplay")
            else -> true
        }
    }
    fun useBmwBootstrap(profileId: String, receiverIdentity: String?): Boolean =
        profileId.startsWith("bmw") && !isDesktopReceiver(profileId, receiverIdentity)
}
