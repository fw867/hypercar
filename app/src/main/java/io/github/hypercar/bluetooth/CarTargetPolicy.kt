package io.github.hypercar.bluetooth

object CarTargetPolicy {
    /** 自动模式不以 ACL 缓存限制入站；ACL 地址只用于出站候选排序。 */
    fun acceptsIncoming(explicitTarget: String?, peer: String?): Boolean =
        explicitTarget.isNullOrBlank() || explicitTarget.equals(peer, ignoreCase = true)

    fun outgoingTarget(explicitTarget: String?, aclHint: String?): String? = explicitTarget ?: aclHint
}
