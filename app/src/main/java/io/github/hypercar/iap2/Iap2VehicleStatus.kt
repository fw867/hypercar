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

/** The BMW box requests empty flags 3, 4, 6 and 9..16, in that order (xiap2 0x5bfcc..0x5c2bc). */
internal class Iap2VehicleStatusSession(
    private val onUpdate: (Iap2VehicleStatus) -> Unit,
    private val log: (String) -> Unit,
) {
    private var requested = emptyList<Int>()
    private var status = Iap2VehicleStatus()

    fun subscribe(peer: Iap2Messages.Identification): ByteArray? {
        val fields = if (peer.declaresReceive(Iap2MessageIds.START_VEHICLE_STATUS_UPDATES) == true &&
            peer.declaresSend(Iap2MessageIds.VEHICLE_STATUS_UPDATE) == true) {
            peer.vehicleStatusFields.filter { it == 3 || it == 4 || it == 6 || it in 9..16 }.sorted()
        } else emptyList()
        if (fields == requested) return null
        reset()
        requested = fields
        if (fields.isNotEmpty()) log("VEHICLE A100 requested fields=$fields")
        return fields.takeIf { it.isNotEmpty() }?.fold(byteArrayOf()) { body, field ->
            body + Iap2Csm.param(field, byteArrayOf())
        }
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
        if (status != Iap2VehicleStatus()) publish(Iap2VehicleStatus())
    }

    private fun publish(next: Iap2VehicleStatus) {
        status = next
        onUpdate(next)
    }
}
