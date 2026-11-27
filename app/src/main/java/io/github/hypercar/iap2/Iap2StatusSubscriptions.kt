package io.github.hypercar.iap2

/** Bounded, transport-local subscription replies; Start/Stop ordering survives early auth. */
internal class Iap2StatusSubscriptions(
    private val power: () -> Iap2Power = Iap2PowerSource::snapshot,
    private val log: (String) -> Unit = {},
    private val deviceName: String = "HyperCar",
    private val callState: () -> Iap2CallSample = { Iap2CallSample.Unavailable },
    private val observeCalls: (Boolean) -> Unit = {},
    private val communications: (Set<Int>) -> Iap2Communications = Iap2CommunicationsSource::snapshot,
) {
    private var diagnostics = 0
    private var authenticated = false
    private val pending = linkedMapOf<Int, List<Iap2Csm.Frame>>()
    private var callStatusRequested = false
    private var lastCallState: Iap2CallState? = null
    private var firstCallSampleDeadline: Long? = null
    private var powerFields = emptySet<Int>()
    private var lastPower: Iap2Power? = null
    private var nextPowerSampleAt = 0L
    private var communicationsRequested: Set<Int>? = null
    private val lastCommunicationsFields = mutableMapOf<Int, ByteArray>()
    private var nextCommunicationsSampleAt = 0L
    val isAuthenticated: Boolean get() = authenticated
    fun authenticated() {
        authenticated = true
        observeCalls(callStatusRequested)
    }
    fun restartLink() {
        pending.clear()
        clearCallSubscription()
        clearPowerSubscription()
        clearCommunicationsSubscription()
    }
    private var separatePowerUpdates = false
    private var mgu = false

    fun identify(model: String?, manufacturer: String?) {
        val bmw = manufacturer.equals("BMW Group", ignoreCase = true)
        separatePowerUpdates = bmw && model.equals("MGU-55-BMW", ignoreCase = true)
        mgu = bmw && (model.equals("MGU", ignoreCase = true) || model?.startsWith("MGU-", ignoreCase = true) == true)
    }

    fun reset() { authenticated = false; identify(null, null); restartLink() }

    fun handle(frame: Iap2Csm.Frame): Boolean {
        val stop = when (frame.messageId) {
            0x4c02 -> if (frame.body.isEmpty()) 0x4c00 else return true
            Iap2MessageIds.STOP_CALL_STATE_UPDATES -> {
                if (frame.body.isNotEmpty()) return true
                clearCallSubscription()
                Iap2MessageIds.START_CALL_STATE_UPDATES
            }
            0x4159 -> {
                if (frame.body.isNotEmpty()) return true
                clearCommunicationsSubscription()
                0x4157
            }
            0x4172 -> 0x4170
            0xae02 -> {
                if (frame.body.isNotEmpty()) return true
                clearPowerSubscription()
                0xae00
            }
            0xad02 -> if (frame.body.isEmpty()) 0xad00 else return true
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
            Iap2MessageIds.START_CALL_STATE_UPDATES -> {
                val fields = Iap2Csm.parseParams(frame.body)
                if (fields.sumOf { it.value.size + Iap2Csm.PARAM_HEADER_BYTES } != frame.body.size ||
                    fields.any { it.value.isNotEmpty() }) return true
                callStatusRequested = fields.isEmpty() || fields.any { it.id == 2 }
                lastCallState = null
                firstCallSampleDeadline = null
                observeCalls(authenticated && callStatusRequested)
                emptyList() // Keep initial-response order and sample the current state at drain time.
            }
            0x4157 -> {
                val fields = Iap2Csm.parseParams(frame.body)
                if (fields.sumOf { it.value.size + 4 } != frame.body.size) {
                    if (diagnostics++ < 64) log("IAP2-COMMS: malformed 0x4157 ignored")
                    return true
                }
                clearCommunicationsSubscription()
                communicationsRequested = fields.map { it.id }.toSet()
                emptyList()
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
                powerFields = fields.map { it.id }.filter { it in 4..6 }.toSet()
                lastPower = null
                emptyList()
            }
            0xad00 -> {
                if (!validAppDiscoveryRequest(frame.body)) return true
                emptyList() // Choose the reply after the peer identifies itself.
            }
            else -> return false
        }
        // A single pending response set per request family until the transport can send.
        pending[frame.messageId] = replies
        return true
    }
    fun drain(nowMillis: Long = System.nanoTime() / 1_000_000L): List<Iap2Csm.Frame> {
        if (!authenticated) return emptyList()
        val callStart = Iap2MessageIds.START_CALL_STATE_UPDATES
        val sample = if (callStatusRequested) callState() else Iap2CallSample.Unavailable
        val currentCall = (sample as? Iap2CallSample.Known)?.state
        val initialCallReply = callStart in pending
        val awaitingFirstSample = initialCallReply && currentCall == null && if (mgu) {
            callStatusRequested && waitForFirstCallSample(nowMillis)
        } else sample == Iap2CallSample.Pending
        val reportedCall = when {
            currentCall != null && (initialCallReply || currentCall != lastCallState) -> currentCall
            !mgu && initialCallReply && !awaitingFirstSample -> Iap2CallState.DISCONNECTED
            else -> null
        }
        val callReply = reportedCall?.let {
            lastCallState = it
            Iap2Csm.Frame(Iap2MessageIds.CALL_STATE_UPDATE, Iap2Csm.u8(2, it.wireValue))
        }
        val initialCommunicationsReply = 0x4157 in pending
        val initialPowerReply = 0xae00 in pending
        return buildList {
            for ((id, frames) in pending) {
                when (id) {
                    callStart -> callReply?.let(::add)
                    0x4157 -> communicationsRequested?.let {
                        communicationsUpdate(it, nowMillis, initial = true)?.let(::add)
                    }
                    // BMW carplay maps status 0 to unsupported and 1 to explicit user refusal.
                    0xad00 -> add(Iap2Csm.Frame(0xad01, Iap2Csm.u8(0, if (mgu) 0 else 1)))
                    0xae00 -> addAll(powerUpdates(nowMillis, initial = true))
                    else -> addAll(frames)
                }
            }
            if (!initialCallReply) callReply?.let(::add)
            if (!initialPowerReply && mgu && powerFields.isNotEmpty() && nowMillis >= nextPowerSampleAt) {
                addAll(powerUpdates(nowMillis, initial = false))
            }
            val requested = communicationsRequested
            if (!initialCommunicationsReply && mgu && requested != null &&
                requested.any { it in liveCommunicationsFields } && nowMillis >= nextCommunicationsSampleAt) {
                communicationsUpdate(requested, nowMillis, initial = false)?.let(::add)
            }
            pending.clear()
            if (!mgu) communicationsRequested = null
            if (awaitingFirstSample) pending[callStart] = emptyList() else firstCallSampleDeadline = null
        }
    }

    private fun waitForFirstCallSample(nowMillis: Long): Boolean {
        val deadline = firstCallSampleDeadline ?: (nowMillis + CALL_FIRST_SAMPLE_MILLIS)
        firstCallSampleDeadline = deadline
        return nowMillis < deadline
    }

    private fun validAppDiscoveryRequest(body: ByteArray): Boolean {
        val fields = Iap2Csm.parseParams(body)
        if (fields.sumOf { it.value.size + Iap2Csm.PARAM_HEADER_BYTES } != body.size) return false
        return fields.all { field ->
            when (field.id) {
                0, 3 -> {
                    val selectors = Iap2Csm.parseParams(field.value)
                    selectors.all { it.value.isEmpty() } &&
                        field.value.size == selectors.size * Iap2Csm.PARAM_HEADER_BYTES
                }
                1, 2, 4 -> field.value.size == 2
                else -> true
            }
        }
    }

    private fun powerUpdates(nowMillis: Long, initial: Boolean): List<Iap2Csm.Frame> {
        val snapshot = if (powerFields.isEmpty()) Iap2Power() else power()
        val previous = lastPower
        val values = snapshot.fields(powerFields, previous)
        lastPower = Iap2Power(
            snapshot.externalCharger ?: previous?.externalCharger,
            snapshot.chargingState ?: previous?.chargingState,
            snapshot.level ?: previous?.level,
        )
        nextPowerSampleAt = nowMillis + POWER_SAMPLE_INTERVAL_MILLIS
        // MGU-55 on both transports expects one message per available field.
        if (separatePowerUpdates) return values.map { Iap2Csm.Frame(0xae01, it) }
        if (values.isEmpty() && !initial) return emptyList()
        return listOf(Iap2Csm.Frame(0xae01, values.fold(byteArrayOf()) { body, value -> body + value }))
    }

    private fun clearPowerSubscription() {
        powerFields = emptySet()
        lastPower = null
        nextPowerSampleAt = 0L
    }

    private fun communicationsUpdate(requested: Set<Int>, nowMillis: Long, initial: Boolean): Iap2Csm.Frame? {
        val samples = if (mgu) communications(requested) else Iap2Communications()
        val available = buildMap {
            if (mgu) {
                samples.signal?.takeIf { it in 0..4 }?.let { put(0, Iap2Csm.u8(0, it)) }
                samples.registration?.let { put(1, Iap2Csm.u8(1, it)) }
                samples.airplane?.let { put(2, Iap2Csm.u8(2, if (it) 1 else 0)) }
                samples.carrier?.substringBefore('\u0000')?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
                    ?.let { put(4, Iap2Csm.string(4, it)) }
                // These services and call-control actions are unavailable in the app.
                putAll(mguUnavailableCapabilities)
            } else putAll(defaultCommunicationsFields)
        }.filterKeys { it in requested }
        val fields = available.filter { (id, value) ->
            initial || lastCommunicationsFields[id]?.contentEquals(value) != true
        }
        if (mgu) {
            lastCommunicationsFields.putAll(available)
            nextCommunicationsSampleAt = nowMillis + COMMUNICATIONS_SAMPLE_INTERVAL_MILLIS
        }
        if (!initial && fields.isEmpty()) return null
        val body = fields.values.fold(byteArrayOf()) { bytes, field -> bytes + field }
        if (diagnostics++ < 64) log("IAP2-COMMS: 0x4157 fields=${requested.take(32)} -> " +
            "0x4158 fields=${fields.keys} body_bytes=${body.size}")
        return Iap2Csm.Frame(0x4158, body)
    }

    private fun clearCommunicationsSubscription() {
        communicationsRequested = null
        lastCommunicationsFields.clear()
        nextCommunicationsSampleAt = 0L
    }

    private fun clearCallSubscription() {
        callStatusRequested = false
        lastCallState = null
        firstCallSampleDeadline = null
        observeCalls(false)
    }

    private companion object {
        const val POWER_SAMPLE_INTERVAL_MILLIS = 1_000L
        const val COMMUNICATIONS_SAMPLE_INTERVAL_MILLIS = 2_000L
        const val CALL_FIRST_SAMPLE_MILLIS = 2_500L
        val liveCommunicationsFields = setOf(0, 1, 2, 4)
        val defaultCommunicationsFields = listOf(5, 6, 7, 8, 12, 13, 14, 15, 16, 17)
            .associateWith { Iap2Csm.u8(it, 0) }
        val mguUnavailableCapabilities = listOf(7, 8, 12, 13, 14, 15, 16, 17)
            .associateWith { Iap2Csm.u8(it, 0) }
    }
}
