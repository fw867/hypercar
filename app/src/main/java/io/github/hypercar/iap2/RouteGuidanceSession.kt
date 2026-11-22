package io.github.hypercar.iap2

import io.github.hypercar.navigation.NavigationCue

/** Owned by ONE authenticated iAP2 worker, not by the Android callback thread. */
internal class RouteGuidanceSession(private val log: (String) -> Unit) {
    private data class Subscription(val source: Boolean, val support: Boolean)
    private val subscribers = linkedMapOf<Int, Subscription>()
    private var previous: NavigationCue? = null
    private var published = false
    private val publishedComponents = mutableSetOf<Int>()
    private var previousEnabled = false
    private var nextUpdate = 0L
    private var nextHeartbeat = 0L
    private var dirty = false
    private var diagnostic: String? = null
    private var receiveSupport: Boolean? = null

    fun declareReceiveSupport(supported: Boolean?) {
        if (receiveSupport == supported) return
        if (receiveSupport == false || supported == false) {
            // Freshly permitted consumers need the maneuver again before any reference.
            dirty = true
            nextUpdate = 0
        }
        receiveSupport = supported
        log("HUD: 0x1d01 receive_52xx=${supported?.toString() ?: "unknown"}")
    }

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
            publishedComponents.remove(component)
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
        val status = when {
            receiveSupport == false -> "blocked_by_receive_declaration"
            !enabled -> "disabled"
            subscribers.isEmpty() && cue?.state == 1 -> "source_ready_wait_subscription"
            subscribers.isEmpty() -> "wait_source_and_subscription"
            cue == null -> "subscribed_wait_source"
            cue.state != 1 -> "subscribed_no_active_route"
            cue.maneuver == null -> "subscribed_unknown_maneuver"
            else -> "source_and_subscription_ready"
        }
        if (diagnostic != status) {
            diagnostic = status
            log("HUD: status=$status subscribers=${subscribers.size}; source readiness is separate from vehicle subscription and display")
        }
        if (receiveSupport == false) return emptyList()
        if (subscribers.isEmpty()) { published = false; previous = null; publishedComponents.clear(); return emptyList() }
        val current = cue.takeIf { enabled }
        // Box 0x6317c..0x6323c stores the subscription without inventing an initial
        // navigation state. Still clear a route once when a previously published source ends.
        if (current == null && publishedComponents.isEmpty()) return emptyList()
        // When scope/source disappears send one no-route update to clear the old arrow.
        val unchanged = !dirty && published && current == previous && enabled == previousEnabled
        val heartbeat = unchanged && current?.state == 1 && now >= nextHeartbeat
        if (now < nextUpdate || (unchanged && !heartbeat)) return emptyList()
        val frames = mutableListOf<Iap2Csm.Frame>()
        for ((component, subscription) in subscribers) {
            if (current == null && component !in publishedComponents) continue
            val hasManeuver = current?.state == 1 && current.maneuver != null
            if (hasManeuver && !heartbeat) {
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
            if (current == null) publishedComponents.remove(component) else publishedComponents.add(component)
        }
        log("HUD: QUEUED components=${subscribers.keys} state=${current?.state ?: 0} maneuver=${current?.maneuver} frames=${frames.size} heartbeat=$heartbeat; awaiting vehicle display validation")
        previous = current
        previousEnabled = enabled
        published = current != null
        dirty = false
        nextUpdate = now + 1000
        nextHeartbeat = now + 10_000
        return frames
    }
}
