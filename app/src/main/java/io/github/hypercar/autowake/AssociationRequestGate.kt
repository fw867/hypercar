package io.github.hypercar.autowake

/** 单次系统申请：确认入口与完成结果都只交付一次。 */
internal class AssociationRequestGate {
    data class Ticket(val id: Long, val address: String)
    private var sequence = 0L
    private var active: Ticket? = null
    private var pendingDelivered = false

    @Synchronized fun begin(address: String): Ticket? {
        if (active != null) return null
        return Ticket(++sequence, address).also { active = it; pendingDelivered = false }
    }

    @Synchronized fun isActive(ticket: Ticket) = active == ticket

    @Synchronized fun deliverPending(ticket: Ticket): Boolean {
        if (active != ticket || pendingDelivered) return false
        pendingDelivered = true
        return true
    }

    @Synchronized fun finish(ticket: Ticket): Boolean {
        if (active != ticket) return false
        active = null
        return true
    }
}
