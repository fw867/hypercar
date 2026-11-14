package io.github.hypercar.iap2

/** Bounded, transport-local subscription replies; Start/Stop ordering survives early auth. */
internal class Iap2StatusSubscriptions(
    private val power: () -> List<ByteArray> = Iap2Messages::powerUpdates,
) {
    private var authenticated = false
    private val pending = linkedMapOf<Int, List<Iap2Csm.Frame>>()
    val isAuthenticated: Boolean get() = authenticated
    fun authenticated() { authenticated = true }
    fun restartLink() { pending.clear() }
    fun reset() { authenticated = false; pending.clear() }

    fun handle(frame: Iap2Csm.Frame): Boolean {
        val stop = when (frame.messageId) {
            0x4156 -> 0x4154
            0x4159 -> 0x4157
            0x4172 -> 0x4170
            0xae02 -> 0xae00
            0xad02 -> 0xad00
            else -> null
        }
        if (stop != null) { pending.remove(stop); return true }
        val replies = when (frame.messageId) {
            0x4154 -> listOf(Iap2Csm.Frame(0x4155, Iap2Csm.u8(2, 0)))
            // No telephony bridge: do not invent SIM state or advertise working call controls.
            0x4157 -> listOf(Iap2Csm.Frame(0x4158, intArrayOf(5,6,7,8,12,13,14,15,16,17)
                .fold(byteArrayOf()) { body, id -> body + Iap2Csm.u8(id, 0) }))
            0x4170 -> listOf(Iap2Csm.Frame(0x4171, Iap2Csm.u8(0,0) + Iap2Csm.u16(2,0) +
                Iap2Csm.u8(5,0) + Iap2Csm.u16(7,0)))
            0xae00 -> power().map { Iap2Csm.Frame(0xae01, it) }
            // Existing box-compatible AppDiscoveryUpdate response, not an app-launch command.
            0xad00 -> listOf(Iap2Csm.Frame(0xad01, Iap2Csm.u8(0,1)))
            else -> return false
        }
        // Known IDs only, at most five entries; repeated requests coalesce while blocked.
        pending[frame.messageId] = replies
        return true
    }
    fun drain(): List<Iap2Csm.Frame> {
        if (!authenticated) return emptyList()
        return pending.values.flatten().also { pending.clear() }
    }
}
