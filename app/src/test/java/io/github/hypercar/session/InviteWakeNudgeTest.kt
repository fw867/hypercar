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
}
