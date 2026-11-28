package io.github.hypercar.session

import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2EndpointProposal
import io.github.hypercar.iap2.Iap2EndpointUpdate
import io.github.hypercar.iap2.RfcommClose

/** Called under the endpoint inbox lock, including receiver changes. */
internal class Iap2EndpointHandoff(private val merge: (CarPlayEndpoint?, CarPlayEndpoint) -> CarPlayEndpoint) {
    private var cutoff = 0L
    private val preservedRegistrations = mutableSetOf<Long>()
    private var waiting = false
    private var latest: CarPlayEndpoint? = null
    // One credential-bearing report and one route-only report can complement each other.
    private val uncommitted = linkedMapOf<Boolean, Iap2EndpointUpdate>()

    fun prepare(old: RfcommClose?) {
        if (old != null) {
            val retained = old.preservedRegistrations.filter(::eligibleRegistration)
            preservedRegistrations.removeAll(old::retires)
            preservedRegistrations.addAll(retained)
            cutoff = maxOf(cutoff, old.registrationCutoff)
        }
        latest = latest?.takeIf(::eligible)
        for (update in uncommitted.values) {
            if (eligible(update.endpoint)) update.endpoint.whileSourceCurrent { update.commitDeferred(update.endpoint) }
        }
        uncommitted.clear()
        waiting = true
    }

    fun take(): CarPlayEndpoint? {
        if (!waiting) return null
        waiting = false
        return latest.also { latest = null }?.takeIf(::eligible)
    }

    fun clear() {
        latest = null
        waiting = false
        preservedRegistrations.clear()
        uncommitted.clear()
    }

    /** null means the retiring inbox no longer has a consumer. false is an active policy rejection. */
    fun route(propose: Iap2EndpointProposal, receiver: ((Iap2EndpointUpdate) -> Boolean?)?): Boolean {
        val update = propose() ?: return false
        val endpoint = update.endpoint
        if (!eligible(endpoint)) return false
        if ((latest?.sourceGeneration?.rfcommRegistration ?: 0L) > endpoint.sourceGeneration!!.rfcommRegistration) return false
        val accepted = if (waiting) endpoint.whileSourceCurrent(update.commit) else receiver?.invoke(update)
        if (accepted != false && eligible(endpoint)) {
            val previous = latest?.takeIf { it.sourceGeneration === endpoint.sourceGeneration }
            if (previous == null) uncommitted.clear()
            if (accepted == null) {
                uncommitted.remove(endpoint.addressOnlyAirplayReport)
                uncommitted[endpoint.addressOnlyAirplayReport] = update
            }
            // The worker may close its inbox before Wi-Fi loss cleanup commits the original
            // socket snapshot. Keep the report locally until that snapshot can classify it.
            latest = merge(previous, endpoint)
        }
        return accepted == true
    }

    private fun eligible(endpoint: CarPlayEndpoint): Boolean =
        endpoint.sourceGeneration?.let { eligibleRegistration(it.rfcommRegistration) && it.isCurrent } == true

    private fun eligibleRegistration(registration: Long): Boolean =
        registration > cutoff || registration in preservedRegistrations
}
