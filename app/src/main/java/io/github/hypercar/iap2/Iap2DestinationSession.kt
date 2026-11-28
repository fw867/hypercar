package io.github.hypercar.iap2

import io.github.hypercar.navigation.NavigationDestinationShare
import io.github.hypercar.navigation.NavigationDestinationTarget
import java.util.UUID
import kotlin.math.roundToInt

/** Destination handoff subscription and result for a single Wi-Fi iAP2 link. */
internal class Iap2DestinationSession(
    private val log: (String) -> Unit,
    private val transportCurrent: () -> Boolean = { true },
) {
    private class Exchange(
        val wireId: String, val share: NavigationDestinationShare, val scope: NavigationDestinationTarget,
        val subscriptionVersion: Long,
    ) {
        var earlyResult: Boolean? = null
        var started = false
        var written = false
    }

    @Volatile var target = NavigationDestinationTarget()
        private set
    private var retired = false
    private var subscribed = false
    private var sourceNameRequested = false
    private var subscriptionVersion = 0L
    private var authorizedSource: NavigationDestinationShare? = null
    private var queued: Exchange? = null
    private var awaitingResult: Exchange? = null
    val hasWrittenOffer get() = queued?.written == true
    val isSubscribed get() = subscribed

    @Synchronized fun retireTransport() {
        retired = true
        target.close()
    }

    @Synchronized fun reset() {
        target.close()
        if (!retired) target = NavigationDestinationTarget()
        subscribed = false
        sourceNameRequested = false
        subscriptionVersion++
        authorizedSource = null
        queued = null
        awaitingResult = null
    }

    fun refreshSource(share: NavigationDestinationShare?) {
        authorizedSource = share?.takeIf(target::isCurrent)
        if (queued?.share !== authorizedSource) queued = null
        if (awaitingResult?.share !== authorizedSource) awaitingResult = null
    }

    fun handle(frame: Iap2Csm.Frame, onRevoked: (() -> Unit)? = null): Boolean {
        if (frame.messageId !in 0x10da..0x10dd || frame.messageId == 0x10db) return false
        when (frame.messageId) {
            0x10da -> {
                val field = Iap2Csm.parseParams(frame.body).singleOrNull()
                if (frame.body.isEmpty() ||
                    (frame.body.size == Iap2Csm.PARAM_HEADER_BYTES && field?.id == 0 && field.value.isEmpty())) {
                    if (!subscribed) log("DESTINATION: subscribed")
                    subscribed = true
                    val requestsSourceName = field != null
                    if (sourceNameRequested != requestsSourceName) {
                        sourceNameRequested = requestsSourceName
                        onRevoked?.invoke()
                    }
                }
            }
            0x10dc -> if (frame.body.isEmpty()) unsubscribe(onRevoked)
            0x10dd -> receiveResult(frame.body)
        }
        return true
    }

    fun unsubscribe(onRevoked: (() -> Unit)?) {
        if (!subscribed) return
        subscribed = false
        sourceNameRequested = false
        subscriptionVersion++
        queued = null
        awaitingResult = null
        onRevoked?.invoke()
        log("DESTINATION: unsubscribed")
    }

    fun offer(allowStart: () -> Boolean = { true }): Iap2QueuedControl? {
        val share = authorizedSource ?: return null
        if (!allowStart() || !subscribed || queued?.share === share || !target.canClaim(share)) return null
        val offer = Exchange(UUID.randomUUID().toString(), share, target, subscriptionVersion)
        queued = offer
        val destination = share.destination
        val coordinates = Iap2Csm.group(2, coordinate(0, destination.latitude), coordinate(1, destination.longitude))
        val fields = mutableListOf(Iap2Csm.string(0, offer.wireId))
        if (destination.name.isNotBlank()) fields += Iap2Csm.string(1, destination.name)
        fields += coordinates
        if (destination.address.isNotBlank()) fields += Iap2Csm.string(3, destination.address)
        fields += Iap2Csm.string(6, destination.locale)
        val sourceName = requestedSourceName(share)
        if (sourceName != null) fields += Iap2Csm.string(7, sourceName)
        fun canStart() = allowStart() && isAuthorized(offer) && offer.scope.canClaim(share) &&
            sourceName == requestedSourceName(share)
        return Iap2QueuedControl(
            Iap2Csm.Frame(0x10db, fields.reduce(ByteArray::plus)), ::canStart,
            commitStart = {
                // STOP may retire the exchange before the notification of an actual write arrives.
                val canConfirm = offer.scope.consumeWrittenShare(share)
                offer.started = canConfirm && isAuthorized(offer)
                if (offer.started) {
                    log("DESTINATION: offer started")
                } else if (queued === offer) queued = null
                offer.started
            },
            onWritten = { offer.written = offer.started },
            onDiscarded = {
                offer.scope.forgetPendingWrite(share)
                if (queued === offer) queued = null
            },
            authorizeFirstWrite = { cancel -> offer.scope.reserveWrite(share, cancel) },
        )
    }

    private fun requestedSourceName(share: NavigationDestinationShare): String? =
        share.destination.sourceName?.takeIf { sourceNameRequested && it.isNotBlank() }

    private fun isAuthorized(offer: Exchange) = queued === offer && subscribed &&
        subscriptionVersion == offer.subscriptionVersion && target === offer.scope && authorizedSource === offer.share

    fun confirmWrittenOffer() {
        val offer = queued?.takeIf { it.written } ?: return
        if (isAuthorized(offer) && offer.scope.commitIfCurrent(offer.share, transportCurrent) {
                queued = null
                awaitingResult = offer
            }) {
            offer.earlyResult?.let { commitResult(offer, it) }
        } else if (queued === offer) queued = null
    }

    private fun receiveResult(body: ByteArray) {
        val params = Iap2Csm.parseParams(body)
        if (params.sumOf { it.value.size + Iap2Csm.PARAM_HEADER_BYTES } != body.size) return
        val fields = params.associateBy { it.id }
        if (fields.size != params.size || fields.keys.any { it !in 0..2 }) return
        val id = fields[0]?.value ?: return
        val expected = listOfNotNull(awaitingResult, queued).firstOrNull {
            id.contentEquals(it.wireId.encodeToByteArray() + byteArrayOf(0))
        } ?: return
        val failure = fields[1]
        if (failure != null && failure.value.isNotEmpty()) return
        fields[2]?.let { group ->
            val usedFields = Iap2Csm.parseParams(group.value)
            if (usedFields.any { it.id !in 2..4 || it.value.isNotEmpty() } ||
                usedFields.size * Iap2Csm.PARAM_HEADER_BYTES != group.value.size) return
        }
        if (expected === queued) {
            target.commitIfCurrent(expected.share, transportCurrent) {
                if (expected.earlyResult == null) expected.earlyResult = failure == null
            }
        } else {
            commitResult(expected, failure == null)
        }
    }

    private fun commitResult(expected: Exchange, accepted: Boolean) {
        if (expected !== awaitingResult ||
            !expected.scope.commitIfCurrent(expected.share, transportCurrent) { awaitingResult = null }) return
        log(if (accepted) "DESTINATION: result accepted" else "DESTINATION: result rejected")
    }

    // BMW's destination converter decodes a signed 32-bit coordinate scaled by 2^-22.
    private fun coordinate(id: Int, degrees: Double): ByteArray =
        Iap2Csm.u32(id, (degrees * (1 shl 22)).roundToInt().toLong())
}
