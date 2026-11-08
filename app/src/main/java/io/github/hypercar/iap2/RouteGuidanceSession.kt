package io.github.hypercar.iap2

import io.github.hypercar.navigation.NavigationCue

/** Owned by ONE authenticated iAP2 worker, not by the Android callback thread. */
internal class RouteGuidanceSession(private val log: (String) -> Unit) {
    private data class Subscription(val source: Boolean, val support: Boolean)
    private val subscribers = linkedMapOf<Int, Subscription>()
    private var previous: NavigationCue? = null
    private var published = false
    private var previousEnabled = false
    private var nextUpdate = 0L
    private var dirty = false

    private fun concat(vararg parts: ByteArray): ByteArray = parts.fold(byteArrayOf()) { a, b -> a + b }

    fun handle(frame: Iap2Csm.Frame): Boolean {
        if (frame.messageId !in listOf(0x5200, 0x5203)) return false
        val params = Iap2Csm.parseParams(frame.body)
        if (params.sumOf { it.value.size + 4 } != frame.body.size ||
            params.count { it.id == 0 } != 1 || params.first { it.id == 0 }.value.size != 2) {
            log("HUD: invalid subscription id=0x${frame.messageId.toString(16)} bytes=${frame.body.size}")
            return true
        }
        val component = params.first { it.id == 0 }.value.let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
        if (frame.messageId == 0x5203) {
            subscribers.remove(component)
            log("HUD: unsubscribe component=$component")
        } else {
            if (component !in subscribers && subscribers.size >= 4) return true
            subscribers[component] = Subscription(params.any { it.id == 1 }, params.any { it.id == 2 })
            dirty = true
            nextUpdate = 0
            log("HUD: subscribe component=$component source=${subscribers[component]?.source} support=${subscribers[component]?.support}")
        }
        return true
    }

    fun updates(now: Long, enabled: Boolean, cue: NavigationCue?): List<Iap2Csm.Frame> {
        if (subscribers.isEmpty()) { published = false; previous = null; return emptyList() }
        val current = cue.takeIf { enabled }
        // When scope/source disappears send one no-route update to clear the old arrow.
        if (!enabled && !published) return emptyList()
        if (now < nextUpdate || (!dirty && published && current == previous && enabled == previousEnabled)) return emptyList()
        val frames = mutableListOf<Iap2Csm.Frame>()
        for ((component, subscription) in subscribers) {
            val hasManeuver = current?.state == 1 && current.maneuver != null
            if (hasManeuver) {
                frames += Iap2Csm.Frame(0x5202, concat(
                    Iap2Csm.u16(0, component), Iap2Csm.u16(1, 0),
                    Iap2Csm.string(2, current!!.instruction), Iap2Csm.u8(3, current.maneuver!!),
                    *listOfNotNull(current.nextRoad.takeIf(String::isNotEmpty)?.let { Iap2Csm.string(4, it) }).toTypedArray()))
            }
            val fields = mutableListOf(Iap2Csm.u16(0, component), Iap2Csm.u8(1, current?.state ?: 0))
            // Index zero is the single CURRENT maneuver, overwritten before publishing its reference.
            fields += Iap2Csm.u16(0x0e, if (hasManeuver) 1 else 0)
            fields += Iap2Csm.param(0x0d, if (hasManeuver) byteArrayOf(0, 0) else byteArrayOf())
            if (hasManeuver) current?.distanceMeters?.let { fields += Iap2Csm.u32(0x0a, it) }
            if (subscription.source) fields += Iap2Csm.string(0x13, current?.sourceName ?: "HyperCar")
            if (subscription.support) fields += Iap2Csm.u8(0x14, if (enabled) 1 else 0)
            frames += Iap2Csm.Frame(0x5201, concat(*fields.toTypedArray()))
        }
        log("HUD: QUEUED components=${subscribers.keys} state=${current?.state ?: 0} maneuver=${current?.maneuver} frames=${frames.size}; awaiting vehicle display validation")
        previous = current
        previousEnabled = enabled
        published = true
        dirty = false
        nextUpdate = now + 1000
        return frames
    }
}
