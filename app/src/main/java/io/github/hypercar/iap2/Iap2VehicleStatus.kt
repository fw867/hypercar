package io.github.hypercar.iap2

enum class VehicleFuel(internal val rangeField: Int, internal val warningField: Int) {
    GASOLINE(9, 13), DIESEL(10, 14), ELECTRIC(11, 15), CNG(12, 16),
}

data class Iap2VehicleStatus(
    val rangeKm: Int? = null,
    val outsideTemperatureC: Int? = null,
    val rangeWarning: Boolean? = null,
    val fuelRangesKm: Map<VehicleFuel, Int> = emptyMap(),
    val fuelRangeWarnings: Map<VehicleFuel, Boolean> = emptyMap(),
)

internal val VEHICLE_STATUS_SELECTORS = setOf(3, 4, 6, 9, 10, 11, 12, 13, 14, 15, 16)

/** The BMW box requests empty flags 3, 4, 6 and 9..16, in that order (xiap2 0x5bfcc..0x5c2bc). */
internal class Iap2VehicleStatusSession(
    private val onUpdate: (Iap2VehicleStatus) -> Unit,
    private val log: (String) -> Unit,
) {
    private class PendingStop {
        var offered = false
        var revoked = false
    }

    private var requested = emptyList<Int>()
    private var status = Iap2VehicleStatus()
    private var stopSupported = false
    private var pendingStop: PendingStop? = null

    fun subscribe(peer: Iap2Messages.Identification): Iap2Csm.Frame? {
        stopSupported = peer.vehicleStatusStopSupported ?: stopSupported
        if (!stopSupported) pendingStop?.revoked = true
        val declaredFields = peer.vehicleStatusFields ?: return null
        val fields = if (peer.declaresReceive(Iap2MessageIds.START_VEHICLE_STATUS_UPDATES) == true &&
            peer.declaresSend(Iap2MessageIds.VEHICLE_STATUS_UPDATE) == true) {
            declaredFields.sorted()
        } else emptyList()
        if (fields == requested) return null
        requested = fields
        pendingStop = if (fields.isEmpty()) PendingStop() else null
        if (status != Iap2VehicleStatus()) publish(Iap2VehicleStatus())
        if (fields.isEmpty()) return null
        log("VEHICLE A100 requested fields=$fields")
        val body = fields.fold(byteArrayOf()) { body, field ->
            body + Iap2Csm.param(field, byteArrayOf())
        }
        return Iap2Csm.Frame(Iap2MessageIds.START_VEHICLE_STATUS_UPDATES, body)
    }

    fun offerStop(): Iap2QueuedControl? {
        val stop = pendingStop?.takeIf { stopSupported && !it.offered } ?: return null
        stop.offered = true
        stop.revoked = false
        return Iap2QueuedControl(
            Iap2Csm.Frame(Iap2MessageIds.STOP_VEHICLE_STATUS_UPDATES, byteArrayOf()),
            canStart = { pendingStop === stop && stopSupported && !stop.revoked },
            onWritten = {
                // A successful write may have preceded the revocation on the socket.
                if (pendingStop === stop) pendingStop = null
                log("VEHICLE A102 written after capability withdrawal")
            },
            onDiscarded = { stop.offered = false },
        )
    }

    fun receive(body: ByteArray) {
        if (requested.isEmpty() || body.isEmpty()) return
        val params = Iap2Csm.parseParams(body)
        if (params.sumOf { it.value.size + 4 } != body.size) return
        val fields = params.filter { it.id in requested }.groupBy { it.id }
        fun word(id: Int) = fields[id]?.singleOrNull()?.value?.takeIf { it.size == 2 }?.let {
            ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255)
        }
        fun flag(id: Int) = when (fields[id]?.singleOrNull()?.value?.singleOrNull()?.toInt()) {
            0 -> false
            1 -> true
            else -> null
        }
        val ranges = status.fuelRangesKm.toMutableMap()
        val warnings = status.fuelRangeWarnings.toMutableMap()
        for (fuel in VehicleFuel.entries) {
            word(fuel.rangeField)?.let { ranges[fuel] = it }
            flag(fuel.warningField)?.let { warnings[fuel] = it }
        }
        val next = Iap2VehicleStatus(
            rangeKm = word(3) ?: status.rangeKm,
            outsideTemperatureC = word(4)?.toShort()?.toInt() ?: status.outsideTemperatureC,
            rangeWarning = flag(6) ?: status.rangeWarning,
            fuelRangesKm = ranges,
            fuelRangeWarnings = warnings,
        )
        if (next != status) {
            log("VEHICLE A101 update $next")
            publish(next)
        }
    }

    fun reset() {
        requested = emptyList()
        stopSupported = false
        pendingStop = null
        if (status != Iap2VehicleStatus()) publish(Iap2VehicleStatus())
    }

    private fun publish(next: Iap2VehicleStatus) {
        status = next
        onUpdate(next)
    }
}
