package io.github.hypercar.session

import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2EndpointGeneration
import io.github.hypercar.iap2.Iap2EndpointProposal
import io.github.hypercar.iap2.Iap2EndpointUpdate
import io.github.hypercar.iap2.RfcommClose

/** Called under the endpoint inbox lock, including receiver changes. */
internal class Iap2EndpointHandoff(private val merge: (CarPlayEndpoint?, CarPlayEndpoint) -> CarPlayEndpoint) {
    private var cutoff = 0L
    private val retiredRegistrations = mutableSetOf<Long>()
    private var waiting = false
    private var latest: CarPlayEndpoint? = null
    private data class RetainedAddress(val source: Iap2EndpointGeneration, val endpoint: CarPlayEndpoint)
    private var retainedAddress: RetainedAddress? = null
    // One credential-bearing report and one route-only report can complement each other.
    private val uncommitted = linkedMapOf<Boolean, Iap2EndpointUpdate>()

    fun prepare(old: RfcommClose?) {
        if (old != null) {
            cutoff = maxOf(cutoff, old.registrationCutoff)
            old.exactRegistration?.takeIf { it > cutoff }?.let(retiredRegistrations::add)
            retiredRegistrations.removeAll { it <= cutoff }
        }
        latest = latest?.takeIf(::eligible)
        retainedAddress = retainedAddress?.takeIf { eligible(it.endpoint) }
        for (update in uncommitted.values) {
            val endpoint = update.endpoint
            if (!publishable(endpoint)) continue
            endpoint.whileSourceCurrent {
                if (!publishable(endpoint)) return@whileSourceCurrent
                update.commitDeferred(endpoint)
                remember(endpoint)
            }
        }
        uncommitted.clear()
        waiting = true
    }

    fun take(): CarPlayEndpoint? {
        if (!waiting) return null
        waiting = false
        return latest.also { latest = null }?.takeIf(::eligible)
    }

    fun recoveryAddress(): CarPlayEndpoint? =
        retainedAddress?.endpoint?.takeIf { publishable(it) && isNewest(it) }

    fun clear() {
        latest = null
        retainedAddress = null
        waiting = false
        uncommitted.clear()
    }

    /** null means the retiring inbox no longer has a consumer. false is an active policy rejection. */
    fun route(propose: Iap2EndpointProposal, receiver: ((Iap2EndpointUpdate) -> Boolean?)?): Boolean {
        val update = propose() ?: return false
        val endpoint = update.endpoint
        if (!publishable(endpoint) || !isNewest(endpoint)) return false
        val accepted = if (waiting) {
            var committed = false
            endpoint.whileSourceCurrent {
                if (!publishable(endpoint)) return@whileSourceCurrent
                update.commit()
                committed = true
            }
            committed
        } else receiver?.invoke(update)
        if (accepted == true && eligible(endpoint)) {
            uncommitted.clear()
            if (endpoint.addressOnlyAirplayReport && endpoint.ipAddresses.isNotEmpty() &&
                retainedAddress?.source === endpoint.sourceGeneration) retainedAddress = null
            remember(endpoint)
        } else if (accepted == null && publishable(endpoint)) {
            if (uncommitted.values.firstOrNull()?.endpoint?.sourceGeneration !== endpoint.sourceGeneration)
                uncommitted.clear()
            uncommitted.remove(endpoint.addressOnlyAirplayReport)
            uncommitted[endpoint.addressOnlyAirplayReport] = update
        }
        return accepted == true
    }

    /** A deferred 0x4301 can follow its socket into the next attempt. Its credentials are only route proof. */
    fun retainAddress(endpoint: CarPlayEndpoint) {
        val source = endpoint.sourceGeneration ?: return
        if (!endpoint.addressOnlyAirplayReport || endpoint.ipAddresses.isEmpty() ||
            !endpoint.requiresBoundAirplayRoute || !endpoint.requiresMatchingAirplayCredentials ||
            !publishable(endpoint) || !isNewest(endpoint)) return
        if (retainedAddress?.source === source) return
        retainedAddress = RetainedAddress(source,
            endpoint.copy(bluetoothHotspot = null, sourceGeneration = source.unpublishedRoute()))
    }

    fun acceptDeferredAddress(endpoint: CarPlayEndpoint) {
        if (!eligible(endpoint) || !isNewest(endpoint)) return
        if (retainedAddress?.source === endpoint.sourceGeneration) retainedAddress = null
        remember(endpoint)
    }

    private fun remember(endpoint: CarPlayEndpoint) {
        if (registration(latest) > registration(endpoint)) return
        latest = merge(latest?.takeIf { it.sourceGeneration === endpoint.sourceGeneration }, endpoint)
    }

    private fun registration(endpoint: CarPlayEndpoint?): Long =
        endpoint?.sourceGeneration?.rfcommRegistration ?: 0L

    private fun isNewest(endpoint: CarPlayEndpoint): Boolean =
        registration(endpoint) >= maxOf(registration(latest), registration(retainedAddress?.endpoint),
            registration(uncommitted.values.firstOrNull()?.endpoint))

    private fun publishable(endpoint: CarPlayEndpoint): Boolean =
        eligible(endpoint) && endpoint.sourceGeneration?.canPublish == true

    private fun eligible(endpoint: CarPlayEndpoint): Boolean =
        endpoint.sourceGeneration?.let { eligibleRegistration(it.rfcommRegistration) && it.isCurrent } == true

    private fun eligibleRegistration(registration: Long): Boolean =
        registration > cutoff && registration !in retiredRegistrations
}
