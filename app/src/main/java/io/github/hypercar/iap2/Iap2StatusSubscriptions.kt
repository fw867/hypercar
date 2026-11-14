package io.github.hypercar.iap2

/** Bounded, transport-local subscription replies; Start/Stop ordering survives early auth. */
internal class Iap2StatusSubscriptions(
    private val power: () -> List<ByteArray> = Iap2Messages::powerUpdates,
    private val log: (String) -> Unit = {},
) {
    private var diagnostics = 0
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
            0x4157 -> {
                val fields = Iap2Csm.parseParams(frame.body)
                if (fields.sumOf { it.value.size + 4 } != frame.body.size) {
                    if (diagnostics++ < 64) log("IAP2-COMMS: malformed 0x4157 ignored")
                    return true
                }
                // Box firmware 0x621c0..0x62428 checks getPara(id) before each addPara.
                // Report only requested, known u8 capability flags. Without a telephony
                // bridge they remain false; SIM/network/operator values are unavailable.
                val requested = fields.map { it.id }.toSet()
                val replied = listOf(5,6,7,8,12,13,14,15,16,17).filter { it in requested }
                val body = replied.fold(byteArrayOf()) { bytes, id -> bytes + Iap2Csm.u8(id, 0) }
                if (diagnostics++ < 64) log("IAP2-COMMS: 0x4157 fields=${requested.take(32)} -> " +
                    "0x4158 fields=$replied body_bytes=${body.size}; unavailable capabilities=false")
                listOf(Iap2Csm.Frame(0x4158, body))
            }
            0x4170 -> {
                val fields = Iap2Csm.parseParams(frame.body)
                if (fields.sumOf { it.value.size + 4 } != frame.body.size) {
                    if (diagnostics++ < 64) log("IAP2-LIST: malformed 0x4170 ignored")
                    return true
                }
                // Successful box firmware 0x61824..0x61868 uses presence of request
                // fields 1/6 to emit u8 fields 0/5. It never fabricates counters 2/7.
                val requested = fields.map { it.id }.toSet()
                val body = (if (1 in requested) Iap2Csm.u8(0,0) else byteArrayOf()) +
                    (if (6 in requested) Iap2Csm.u8(5,0) else byteArrayOf())
                if (diagnostics++ < 64) log("IAP2-LIST: 0x4170 fields=${requested.take(32)} -> 0x4171 fields=${Iap2Csm.parseParams(body).map { it.id }} body_bytes=${body.size}; box-compatible, no list records")
                listOf(Iap2Csm.Frame(0x4171, body)) // Box also returns an empty frame if neither field was requested.
            }
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
