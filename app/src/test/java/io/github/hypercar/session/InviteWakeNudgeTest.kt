package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class InviteWakeNudgeTest {
    @Test fun ignoresStreamingOrKeepableWifiOrMissingCtrl() {
        assertFalse(InviteWakeNudge.shouldNudge(true, true, false, true, 20_000, 0))
        assertFalse(InviteWakeNudge.shouldNudge(true, false, true, true, 20_000, 0))
        assertFalse(InviteWakeNudge.shouldNudge(true, false, false, false, 20_000, 0))
        assertFalse(InviteWakeNudge.shouldNudge(false, false, false, true, 20_000, 0))
    }

    @Test fun allowsWaitingSessionAndRespectsCooldown() {
        assertTrue(InviteWakeNudge.shouldNudge(true, false, false, true, 20_000, 0))
        assertFalse(InviteWakeNudge.shouldNudge(true, false, false, true, 20_000, 10_000))
        assertTrue(InviteWakeNudge.shouldNudge(true, false, false, true,
            10_000 + InviteWakeNudge.COOLDOWN_MILLIS, 10_000))
    }

    @Test fun wifiHandoffUsesShorterCooldownAndSkipsDiscoverable() {
        assertTrue(InviteWakeNudge.shouldNudge(true, false, false, true, 13_000, 10_000,
            handoffToWifi = true))
        assertFalse(InviteWakeNudge.shouldNudge(true, false, false, true, 12_000, 10_000,
            handoffToWifi = true))
        assertTrue(InviteWakeNudge.armDiscoverable(false))
        assertFalse(InviteWakeNudge.armDiscoverable(true))
    }

    @Test fun connectClockDoesNotBlockFollowingHandoff() {
        val anchor = InviteWakeNudge.cooldownAnchor(
            handoffToWifi = true, lastConnectNudgeAt = 10_000, lastHandoffNudgeAt = 0)
        assertEquals(0L, anchor)
        assertTrue(InviteWakeNudge.shouldNudge(true, false, false, true, 11_000, anchor,
            handoffToWifi = true))
    }

    @Test fun failedRefreshDoesNotConsumeHandoffCooldown() {
        val afterFail = InviteWakeNudge.rememberNudge(false, 11_000, 0)
        assertEquals(0L, afterFail)
        assertTrue(InviteWakeNudge.shouldNudge(true, false, false, true, 11_500, afterFail,
            handoffToWifi = true))
        val afterOk = InviteWakeNudge.rememberNudge(true, 11_500, afterFail)
        assertFalse(InviteWakeNudge.shouldNudge(true, false, false, true, 12_000, afterOk,
            handoffToWifi = true))
    }
}
