package io.github.hypercar.iap2

import io.github.hypercar.navigation.NavigationCue
import io.github.hypercar.navigation.NavigationLane

/** Owned by ONE authenticated iAP2 worker, not by the Android callback thread. */
internal class RouteGuidanceSession(private val log: (String) -> Unit) {
    private class Subscription(val source: Boolean, val support: Boolean) {
        var active = true
    }
    private val subscribers = linkedMapOf<Int, Subscription>()
    private var previous: NavigationCue? = null
    private var published = false
    private val publishedComponents = mutableSetOf<Int>()
    private val publishedCurrentRoad = mutableSetOf<Int>()
    private val publishedLaneGuidance = mutableSetOf<Int>()
    private var previousEnabled = false
    private var nextUpdate = 0L
    private var nextHeartbeat = 0L
    private var dirty = false
    private var diagnostic: String? = null
    private var receiveSupport: Boolean? = null
    private var currentRoadLimits = emptyMap<Int, Int>()
    private var laneGuidanceSupported = false
    private var maneuverSupported = true
    private var declarationVersion = 0L
    private var laneSource: LaneSource? = null

    fun declareReceiveSupport(peer: Iap2Messages.Identification) {
        val ids = peer.navigationReceiveIds
        declareReceiveSupport(peer.acceptsRouteGuidance, peer.currentRoadLimits,
            laneGuidanceSupported = ids?.containsAll(listOf(0x5201, 0x5202, 0x5204)) == true,
            maneuverSupported = ids?.contains(0x5202) != false)
    }

    fun declareReceiveSupport(
        supported: Boolean?,
        roadLimits: Map<Int, Int> = emptyMap(),
        laneGuidanceSupported: Boolean = false,
        maneuverSupported: Boolean = true,
    ) {
        val lanesChanged = this.laneGuidanceSupported != laneGuidanceSupported
        val maneuversChanged = this.maneuverSupported != maneuverSupported
        if (receiveSupport == supported && currentRoadLimits == roadLimits && !lanesChanged && !maneuversChanged) return
        declarationVersion++
        // Freshly permitted consumers need the maneuver again before any reference.
        dirty = true
        nextUpdate = 0
        receiveSupport = supported
        currentRoadLimits = roadLimits
        this.laneGuidanceSupported = laneGuidanceSupported
        this.maneuverSupported = maneuverSupported
        log("HUD: 0x1d01 receive_52xx=${supported?.toString() ?: "unknown"} receive_5204=$laneGuidanceSupported")
    }

    private fun concat(vararg parts: ByteArray): ByteArray = parts.fold(byteArrayOf()) { a, b -> a + b }

    fun handle(frame: Iap2Csm.Frame, onRevoked: (() -> Unit)? = null): Boolean {
        if (frame.messageId != 0x5200 && frame.messageId != 0x5203) return false
        val params = Iap2Csm.parseParams(frame.body)
        val componentFields = params.filter { it.id == 0 }
        if (params.sumOf { it.value.size + Iap2Csm.PARAM_HEADER_BYTES } != frame.body.size ||
            componentFields.isEmpty() || componentFields.any { it.value.size != 2 }) {
            log("HUD: invalid subscription id=0x${frame.messageId.toString(16)} bytes=${frame.body.size}")
            return true
        }
        val components = componentFields.map { it.asU16List().single() }.distinct()
        if (frame.messageId == 0x5203) {
            for (component in components) {
                subscribers.remove(component)?.active = false
                publishedComponents.remove(component)
                publishedCurrentRoad.remove(component)
                publishedLaneGuidance.remove(component)
            }
            onRevoked?.invoke()
            components.forEach { log("HUD: unsubscribe component=$it") }
        } else {
            val source = params.any { it.id == 1 && it.value.isEmpty() }
            val support = params.any { it.id == 2 && it.value.isEmpty() }
            val subscribed = mutableListOf<Int>()
            for (component in components) {
                if (component !in subscribers && subscribers.size >= 4) continue
                subscribers.put(component, Subscription(source, support))?.active = false
                dirty = true
                nextUpdate = 0
                subscribed += component
            }
            onRevoked?.invoke()
            subscribed.forEach { log("HUD: subscribe component=$it source=$source support=$support") }
        }
        return true
    }

    private data class LaneSource(val lanes: List<NavigationLane>, val maneuver: Int, val road: String, val nextRoad: String)

    fun refreshLaneSource(enabled: Boolean, cue: NavigationCue?) {
        val current = cue?.takeIf { enabled && it.state == 1 && it.lanes.isNotEmpty() }
        val newSource = current?.maneuver?.let { LaneSource(current.lanes, it, current.currentRoad, current.nextRoad) }
        if (newSource != laneSource) {
            if (laneSource != null && publishedLaneGuidance.isNotEmpty()) nextUpdate = 0
            laneSource = newSource
        }
    }

    fun updates(now: Long, enabled: Boolean, cue: NavigationCue?): List<Iap2Csm.Frame> {
        refreshLaneSource(enabled, cue)
        return queuedUpdates(now, enabled, cue).map { message ->
            message.commitStart?.invoke()
            message.frame
        }
    }

    fun queuedUpdates(now: Long, enabled: Boolean, cue: NavigationCue?): List<Iap2QueuedControl> {
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
        val hasManeuver = current?.state == 1 && current.maneuver != null && maneuverSupported
        val laneGroups = if (laneGuidanceSupported && hasManeuver) {
            laneGroups(current.lanes, current.maneuver)
        } else null
        val replaceLaneGuidance = dirty || current?.lanes != previous?.lanes || current?.maneuver != previous?.maneuver ||
            current?.currentRoad != previous?.currentRoad || current?.nextRoad != previous?.nextRoad
        val frames = mutableListOf<Iap2QueuedControl>()
        val version = declarationVersion
        val source = laneSource
        for ((component, subscription) in subscribers) {
            if (current == null && component !in publishedComponents) continue
            fun add(id: Int, body: ByteArray, referencesLanes: Boolean, commitStart: (() -> Boolean)? = null) {
                frames += Iap2QueuedControl(Iap2Csm.Frame(id, body), {
                    subscription.active && version == declarationVersion && (!referencesLanes || source == laneSource)
                }, commitStart)
            }
            if (laneGroups != null && (replaceLaneGuidance || component !in publishedLaneGuidance)) {
                add(0x5204, concat(Iap2Csm.u16(0, component), Iap2Csm.u16(1, 0), *laneGroups), referencesLanes = true)
            }
            if (hasManeuver && !heartbeat) {
                add(0x5202, concat(
                    Iap2Csm.u16(0, component), Iap2Csm.u16(1, 0),
                    Iap2Csm.string(2, current!!.instruction), Iap2Csm.u8(3, current.maneuver!!),
                    *listOfNotNull(
                        current.nextRoad.takeIf(String::isNotEmpty)?.let { Iap2Csm.string(4, it) },
                        if (laneGroups != null) Iap2Csm.u16(0x0a, 0) else null,
                    ).toTypedArray()), referencesLanes = laneGroups != null)
            }
            val fields = mutableListOf(Iap2Csm.u16(0, component), Iap2Csm.u8(1, current?.state ?: 0))
            var carriesRoad = false
            if (current?.state == 1) {
                val road = currentRoadLimits[component]?.let { currentRoadField(current.currentRoad, it) }
                if (road != null) {
                    fields += road
                    carriesRoad = true
                } else if (component in publishedCurrentRoad) {
                    // Clear the earlier value once even if a new identification withdrew the limit.
                    fields += Iap2Csm.string(0x03, "")
                }
                current.remainingTimeSeconds?.takeIf { it >= 0 }?.let { fields += Iap2Csm.u64(0x06, it) }
                current.remainingDistanceMeters?.takeIf { it in 0..0xffff_ffffL }?.let { fields += Iap2Csm.u32(0x07, it) }
            }
            // Index zero is the single CURRENT maneuver, overwritten before publishing its reference.
            fields += Iap2Csm.u16(0x0e, if (hasManeuver) 1 else 0)
            fields += Iap2Csm.param(0x0d, if (hasManeuver) byteArrayOf(0, 0) else byteArrayOf())
            if (hasManeuver) current?.distanceMeters?.let { fields += Iap2Csm.u32(0x0a, it) }
            if (laneGroups != null || component in publishedLaneGuidance) {
                if (laneGroups != null) fields += Iap2Csm.u16(0x10, 0)
                fields += Iap2Csm.u16(0x11, if (laneGroups != null) 1 else 0)
                fields += Iap2Csm.u8(0x12, if (laneGroups != null) 1 else 0)
            }
            if (subscription.source) fields += Iap2Csm.string(0x13, current?.sourceName ?: "HyperCar")
            if (subscription.support) fields += Iap2Csm.u8(0x14, if (enabled) 1 else 0)
            add(0x5201, concat(*fields.toTypedArray()), referencesLanes = laneGroups != null) {
                if (component !in subscribers) return@add true
                if (current == null) publishedComponents.remove(component) else publishedComponents.add(component)
                if (laneGroups == null) publishedLaneGuidance.remove(component) else publishedLaneGuidance.add(component)
                // Replanning retains the last road until an active update can replace or clear it.
                if (carriesRoad) publishedCurrentRoad.add(component) else if (current?.state != 5) publishedCurrentRoad.remove(component)
                true
            }
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

    private fun laneGroups(lanes: List<NavigationLane>, maneuver: Int?): Array<ByteArray>? {
        // Preserve the source order and the complete cross-section. An unknown lane cannot be dropped.
        if (lanes.size !in 1..16) return null
        val selected = lanes.count { it.recommended }
        val maneuverAngle = when (maneuver) {
            1 -> -90
            2 -> 90
            3 -> 0
            else -> null
        }
        return lanes.mapIndexed { index, lane ->
            val angles = laneAngles(lane.direction) ?: return null
            val status = when {
                !lane.recommended -> 0
                selected == 1 -> 2
                else -> 1
            }
            val foreground = lane.foregroundDirection?.let(::laneAngles).orEmpty().intersect(angles.toSet())
            val highlighted = foreground.singleOrNull() ?: maneuverAngle?.takeIf { it in foreground }
            val fields = mutableListOf(Iap2Csm.u16(0, index), Iap2Csm.u8(1, status))
            angles.forEach { fields += Iap2Csm.s16(2, it) }
            if (lane.recommended && highlighted != null) fields += Iap2Csm.s16(3, highlighted)
            Iap2Csm.group(2, *fields.toTypedArray())
        }.toTypedArray()
    }

    // AMap LaneAction to signed iAP2 degrees. Codes 5 and 8 leave the U-turn side unspecified.
    private fun laneAngles(direction: Int): List<Int>? = when (direction) {
        0 -> listOf(0)
        1 -> listOf(-90)
        2 -> listOf(0, -90)
        3 -> listOf(90)
        4 -> listOf(0, 90)
        6 -> listOf(-90, 90)
        7 -> listOf(0, -90, 90)
        9 -> listOf(0, -180)
        10 -> listOf(0, 180)
        11 -> listOf(-90, -180)
        12 -> listOf(90, 180)
        else -> null
    }

    private fun currentRoadField(road: String, limit: Int): ByteArray? {
        if (limit <= 1) return null
        val utf8 = road.replace('\u0000', ' ').trim().encodeToByteArray()
        // Bound the whole C string, including its NUL, without splitting a UTF-8 character.
        var end = minOf(utf8.size, minOf(limit, 512) - 1)
        while (end in 1 until utf8.size && utf8[end].toInt() and 0xc0 == 0x80) end--
        return if (end == 0) null else Iap2Csm.param(0x03, utf8.copyOf(end + 1).also { it[end] = 0 })
    }
}
