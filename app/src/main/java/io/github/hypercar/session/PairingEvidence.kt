package io.github.hypercar.session

import java.io.File
import java.util.Locale

/** 本机证据，不等价于车机当前仍授权；全局手机身份不能代表已与某台车配对。 */
data class PairingEvidence(val identityPresent: Boolean, val receiverCredentialPresent: Boolean) {
    val mayDropProfiles: Boolean get() = identityPresent && receiverCredentialPresent

    companion object {
        fun receiverKey(mac: String?): String? {
            val normalized = mac?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (!Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}").matches(normalized)) return null
            return "bt_" + normalized.replace(":", "")
        }

        fun read(filesDir: File, mac: String?): PairingEvidence {
            val identity = File(filesDir, "controller_identity.txt")
            val key = receiverKey(mac)
            return PairingEvidence(identity.isFile && identity.length() > 0,
                key != null && File(filesDir, "controller_identity.txt.paired-$key").isFile)
        }
    }
}
