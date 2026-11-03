package io.github.hypercar.bluetooth

object CarTargetPolicy {
    /** 自动模式不以 ACL 缓存限制入站；ACL 地址只用于出站候选排序。 */
    fun acceptsIncoming(explicitTarget: String?, peer: String?): Boolean =
        explicitTarget.isNullOrBlank() || explicitTarget.equals(peer, ignoreCase = true)

    /** An ACL hint orders candidates; only a user-selected target excludes other cars. */
    fun outgoingCandidates(
        explicitTarget: String?, aclHint: String?, bonded: List<String>, hasIap2: (String) -> Boolean,
    ): List<String> {
        if (!explicitTarget.isNullOrBlank()) return bonded.filter { it.equals(explicitTarget, true) }
        return bonded.sortedBy { if (it.equals(aclHint, true)) 0 else 1 }
            .filter { it.equals(aclHint, true) || hasIap2(it) }
    }

    /** Automatic ACL wake must also work for previously used or service-identified receivers. */
    fun acceptsAcl(explicitTarget: String?, peer: String, name: String, knownCar: Boolean, hasIap2: Boolean): Boolean {
        if (!explicitTarget.isNullOrBlank()) return explicitTarget.equals(peer, true)
        return knownCar || hasIap2 || listOf("BMW", "MINI", "宝马").any { name.contains(it, true) }
    }
}
