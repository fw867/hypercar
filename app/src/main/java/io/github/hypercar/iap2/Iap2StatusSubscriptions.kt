package io.github.hypercar.iap2

/** Bounded, transport-local subscription replies; Start/Stop ordering survives early auth. */
internal class Iap2StatusSubscriptions(
    private val power: () -> Iap2Power = Iap2PowerSource::snapshot,
    private val log: (String) -> Unit = {},
    private val deviceName: String = "HyperCar",
) {
    private var diagnostics = 0
    private var authenticated = false
    private val pending = linkedMapOf<Int, List<Iap2Csm.Frame>>()
    val isAuthenticated: Boolean get() = authenticated
    fun authenticated() { authenticated = true }
    fun restartLink() { pending.clear() }
    private var separatePowerUpdates = false
    private var communicationsFields = defaultCommunicationsFields

    fun identify(model: String?, manufacturer: String?) {
        val bmw = manufacturer.equals("BMW Group", ignoreCase = true)
        separatePowerUpdates = bmw && model.equals("MGU-55-BMW", ignoreCase = true)
        val mgu = model.equals("MGU", ignoreCase = true) || model?.startsWith("MGU-", ignoreCase = true) == true
        communicationsFields = if (bmw && mgu) mguCommunicationsFields else defaultCommunicationsFields
    }

    fun reset() { authenticated = false; identify(null, null); pending.clear() }

    fun handle(frame: Iap2Csm.Frame): Boolean {
        val stop = when (frame.messageId) {
            0x4c02 -> if (frame.body.isEmpty()) 0x4c00 else return true
            0x4156 -> 0x4154
            0x4159 -> 0x4157
            0x4172 -> 0x4170
            0xae02 -> 0xae00
            0xad02 -> 0xad00
            else -> null
        }
        if (stop != null) { pending.remove(stop); return true }
        val replies = when (frame.messageId) {
            0x4c00 -> if (frame.body.isEmpty()) listOf(Iap2MediaLibrary.information(deviceName)) else return true
            0x4c03, 0x4c05 -> {
                val fields = Iap2Csm.parseParams(frame.body)
                if (fields.sumOf { it.value.size + 4 } != frame.body.size || !Iap2MediaLibrary.matches(fields)) return true
                if (frame.messageId == 0x4c05) {
                    pending.remove(0x4c03)
                    return true
                }
                Iap2MediaLibrary.updates(fields)
            }
            0x4154 -> listOf(Iap2Csm.Frame(0x4155, Iap2Csm.u8(2, 0)))
            0x4157 -> {
                val fields = Iap2Csm.parseParams(frame.body)
                if (fields.sumOf { it.value.size + 4 } != frame.body.size) {
                    if (diagnostics++ < 64) log("IAP2-COMMS: malformed 0x4157 ignored")
                    return true
                }
                val requested = fields.map { it.id }.toSet()
                val replied = communicationsFields.filterKeys { it in requested }
                val body = replied.values.fold(byteArrayOf()) { bytes, field -> bytes + field }
                if (diagnostics++ < 64) log("IAP2-COMMS: 0x4157 fields=${requested.take(32)} -> " +
                    "0x4158 fields=${replied.keys} body_bytes=${body.size}")
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
            0xae00 -> {
                val fields = Iap2Csm.parseParams(frame.body)
                if (fields.sumOf { it.value.size + 4 } != frame.body.size || fields.any { it.value.isNotEmpty() }) return true
                val values = power().fields(fields.map { it.id }.toSet())
                // MGU-55 on both transports: one snapshot, one CSM per available field.
                if (separatePowerUpdates) values.map { Iap2Csm.Frame(0xae01, it) }
                else listOf(Iap2Csm.Frame(0xae01, values.fold(byteArrayOf()) { body, value -> body + value }))
            }
            // Existing box-compatible AppDiscoveryUpdate response, not an app-launch command.
            0xad00 -> listOf(Iap2Csm.Frame(0xad01, Iap2Csm.u8(0,1)))
            else -> return false
        }
        // A single pending response set per request family until the transport can send.
        pending[frame.messageId] = replies
        return true
    }
    fun drain(): List<Iap2Csm.Frame> {
        if (!authenticated) return emptyList()
        return pending.values.flatten().also { pending.clear() }
    }

    private companion object {
        val defaultCommunicationsFields = listOf(5, 6, 7, 8, 12, 13, 14, 15, 16, 17)
            .associateWith { Iap2Csm.u8(it, 0) }

        // xiap2 0x621c0..0x62428. The box skips field 3 and emits field 4 as a C string.
        val mguCommunicationsFields = ((0..2) + (4..17)).associateWith { id ->
            when (id) {
                0 -> Iap2Csm.u8(id, 5)
                1 -> Iap2Csm.u8(id, 4)
                4 -> Iap2Csm.string(id, "Mobile")
                5, 6 -> Iap2Csm.u8(id, 1)
                else -> Iap2Csm.u8(id, 0)
            }
        }
    }
}
