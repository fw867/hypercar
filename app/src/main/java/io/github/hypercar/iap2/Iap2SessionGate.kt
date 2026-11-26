package io.github.hypercar.iap2

/** SDP ownership is independent of permission to start an iAP2 session. Old tickets never
 * become valid again when the user manually resumes or automatic cooldown expires. */
internal class Iap2SessionGate {
    private var enabled = true
    private var preparingInbound = false
    private var revision = 0L
    @Synchronized fun setEnabled(value: Boolean) {
        if (preparingInbound) {
            preparingInbound = false
            enabled = value
            if (!value) revision++
        } else if (enabled != value) {
            enabled = value
            revision++
        }
    }
    @Synchronized fun prepareInbound() {
        if (!enabled && !preparingInbound) {
            preparingInbound = true
            revision++
        }
    }
    @Synchronized fun diagnostics(): String = "enabled=$enabled preparingInbound=$preparingInbound revision=$revision"
    @Volatile var outboundPausedForWifi = false
    @Synchronized fun allowsOutbound(ticket: Long): Boolean = allows(ticket) && !outboundPausedForWifi
    @Synchronized fun ticket(): Long? = revision.takeIf { enabled }
    @Synchronized fun inboundTicket(): Long? = revision.takeIf { enabled || preparingInbound }
    @Synchronized fun canHoldInbound(accepted: Long?): Boolean = preparingInbound && accepted == revision
    @Synchronized fun admitInbound(accepted: Long?): Long? = accepted?.takeIf { enabled && revision == it }
    @Synchronized fun allows(ticket: Long): Boolean = enabled && revision == ticket
}
