package io.github.hypercar.session

import io.github.hypercar.net.CarNetworkReuse

/** Entry lifecycle uses the attempt snapshot; wire protocol is negotiated separately in Rust. */
internal object CarLifeSourcePolicy {
    fun isGateway(source: String) = source == "carwith" || source == "system_carlife"
    fun allowsCallAudio(source: String, systemCallAudio: Boolean) =
        source == "carwith" || source == "system_carlife" && systemCallAudio

    fun shouldReopen(source: String, startup: Boolean) =
        source == "carwith" || source == "system_carlife" && startup

    data class HuIdentity(val address: String, val name: String)
    fun huIdentity(receiver: String?, target: String?, targetName: String?, paired: Set<String>): HuIdentity? {
        fun mac(value: String?) = CarNetworkReuse.mac(value)?.uppercase(java.util.Locale.ROOT)?.takeIf { PairingEvidence.receiverKey(it) != null &&
            it !in setOf("00:00:00:00:00:00","FF:FF:FF:FF:FF:FF","02:00:00:00:00:00") }
        val selected=mac(target)
        val address=mac(receiver) ?: selected ?: return null
        val remembered=paired.firstOrNull { mac(it.substringBefore('|')) == address }
            ?.substringAfter('|')?.substringBeforeLast('|')?.takeIf { it.isNotBlank() }
        val name=targetName?.takeIf { selected == address && it.isNotBlank() } ?: remembered ?: address
        return HuIdentity(address,name.trim().take(128))
    }
}
