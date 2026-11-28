package io.github.hypercar.session

import io.github.hypercar.settings.ProjectionTarget
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Requests queued before a target change cannot enter a later CarPlay round. */
internal class ProjectionTargetEpoch {
    private val generation = AtomicLong()
    private val serviceOrigin = UUID.randomUUID().toString()
    private var carSelection = 0L
    private var aclSequence = 0L
    private var currentAcl: Wake.Acl? = null
    private var companionSequence = 0L
    private var currentCompanion: Wake.Companion? = null

    sealed interface Wake {
        data class Acl(val address: String, val observedAt: Long, val sequence: Long) : Wake
        data class Companion(val address: String, val associationId: Int, val presenceGeneration: Long) : Wake {
            fun matches(id: Int, peer: String): Boolean = associationId == id && address.equals(peer, ignoreCase = true)
        }
    }

    data class ServiceStart(
        val origin: String,
        val projection: Long,
        val automaticRequest: Long,
        val carSelection: Long,
        val source: Wake?,
    )

    fun capture(): Long = generation.get()
    @Synchronized fun advance() {
        generation.incrementAndGet()
        currentAcl = null
        currentCompanion = null
    }
    fun allowsCarPlay(ticket: Long, target: ProjectionTarget): Boolean =
        target == ProjectionTarget.CarPlay && generation.get() == ticket

    @Synchronized fun advanceCarSelection() {
        carSelection++
        currentAcl = null
        currentCompanion = null
    }

    @Synchronized fun observeAcl(address: String, observedAt: Long): Wake.Acl =
        Wake.Acl(address, observedAt, ++aclSequence).also { currentAcl = it }

    @Synchronized fun retireAcl(address: String) {
        if (currentAcl?.address.equals(address, ignoreCase = true)) currentAcl = null
    }

    @Synchronized fun observeCompanion(associationId: Int, address: String): Wake.Companion =
        currentCompanion?.takeIf { it.matches(associationId, address) }
            ?: Wake.Companion(address, associationId, ++companionSequence).also { currentCompanion = it }

    @Synchronized fun companionPresence(): Wake.Companion? = currentCompanion

    @Synchronized fun retireCompanion(associationId: Int, address: String?) {
        val current = currentCompanion ?: return
        if (current.associationId == associationId && (address == null || current.address.equals(address, ignoreCase = true)))
            currentCompanion = null
    }

    @Synchronized fun retireCompanion(presence: Wake.Companion) {
        if (currentCompanion == presence) currentCompanion = null
    }

    @Synchronized fun replaceCompanionAssociation(associationId: Int, address: String) {
        val current = currentCompanion ?: return
        if (current.associationId != associationId && current.address.equals(address, ignoreCase = true)) {
            currentCompanion = null
        }
    }

    @Synchronized fun captureServiceStart(
        target: ProjectionTarget, automaticRequest: Long, projection: Long = capture(), source: Wake? = null,
    ): ServiceStart? = ServiceStart(serviceOrigin, projection, automaticRequest, carSelection, source)
        .takeIf { allowsCarPlay(projection, target) }

    @Synchronized fun allowsServiceStart(
        start: ServiceStart?, target: ProjectionTarget, automaticRequest: Long? = null, currentPeer: String? = null,
    ): Boolean {
        if (start == null || start.origin != serviceOrigin || !allowsCarPlay(start.projection, target) ||
            start.carSelection != carSelection || automaticRequest != null && start.automaticRequest != automaticRequest) return false
        return when (val source = start.source) {
            is Wake.Acl -> currentAcl == source && (currentPeer == null || currentPeer.equals(source.address, ignoreCase = true))
            is Wake.Companion -> source == currentCompanion && currentPeer != null && currentPeer.equals(source.address, ignoreCase = true)
            null -> true
        }
    }
}
