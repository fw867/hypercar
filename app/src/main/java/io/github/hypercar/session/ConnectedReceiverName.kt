package io.github.hypercar.session

/** A target alias or cached peer name may belong to another receiver. */
internal fun connectedReceiverName(
    profileLabel: String,
    receiverMac: String?,
    targetMac: String?, targetName: String?,
    peerMac: String?, peerName: String?,
    bondedName: String?,
): String {
    fun matches(other: String?) = !receiverMac.isNullOrBlank() &&
        !other.isNullOrBlank() && receiverMac.equals(other, ignoreCase = true)
    return (if (matches(targetMac)) targetName?.takeIf { it.isNotBlank() } else null)
        ?: (if (matches(peerMac)) peerName?.takeIf { it.isNotBlank() } else null)
        ?: bondedName?.takeIf { !receiverMac.isNullOrBlank() && it.isNotBlank() }
        ?: profileLabel.takeIf { it.isNotBlank() }
        ?: "车机"
}
