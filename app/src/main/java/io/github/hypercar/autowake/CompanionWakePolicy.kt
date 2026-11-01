package io.github.hypercar.autowake

/** 伴生设备仅提供唤醒，不冒充经典 ACL，也不接管已有会话。 */
object CompanionWakePolicy {
    fun accepts(autoConnect: Boolean, target: String?, address: String?): Boolean =
        autoConnect && !target.isNullOrBlank() && !address.isNullOrBlank() &&
            target.equals(address, ignoreCase = true)

    fun shouldTrigger(
        autoConnect: Boolean, target: String?, address: String?, hasSession: Boolean,
        now: Long, lastTrigger: Long?,
    ): Boolean = accepts(autoConnect, target, address) && !hasSession &&
        (lastTrigger == null || now - lastTrigger >= 15_000)
}
