package io.github.hypercar.iap2

/** SDP ownership is independent of permission to start an iAP2 session. Old tickets never
 * become valid again when the user manually resumes or automatic cooldown expires. */
internal class Iap2SessionGate {
    private var enabled = true
    private var revision = 0L
    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled != value) { enabled = value; revision++ }
    }
    @Synchronized fun ticket(): Long? = revision.takeIf { enabled }
    @Synchronized fun allows(ticket: Long): Boolean = enabled && revision == ticket
}
