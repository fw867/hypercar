package io.github.hypercar.headunit

import io.github.hypercar.session.PairingEvidence

internal object HeadUnitActivationPolicy {
    // VW4211 observations in mcarplay; a compatibility threshold, not a protocol constant.
    const val FRESH_INVITE_MILLIS = 20_000L

    fun permitsProactive(profile: HeadUnitProfile): Boolean =
        profile.allowProactiveWifiSession && !profile.requireFreshCtrlInvite

    fun needsFallbackGate(profile: HeadUnitProfile, proactive: Boolean): Boolean =
        proactive || profile.requireFreshCtrlInvite

    fun acceptsInvitation(profile: HeadUnitProfile, receivedAt: Long, now: Long, sameNetwork: Boolean = true): Boolean =
        !profile.requireFreshCtrlInvite ||
            (sameNetwork && receivedAt > 0 && now >= receivedAt && now - receivedAt <= FRESH_INVITE_MILLIS)

    fun canAdvertiseCurrentWifi(profile: HeadUnitProfile, target: String?, activePeer: String?): Boolean =
        profile.advertiseOnCurrentWifi && PairingEvidence.receiverKey(target) != null &&
            (activePeer == null || target.equals(activePeer, ignoreCase = true))
}
