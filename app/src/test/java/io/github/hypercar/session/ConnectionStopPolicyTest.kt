package io.github.hypercar.session

import io.github.hypercar.core.LinkState
import io.github.hypercar.session.ConnectionStopPolicy.Decision.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionStopPolicyTest {
    @Test fun delayedUserStopCannotPauseOrDestroyANewerAttempt() {
        assertEquals(IGNORE_STALE, ConnectionStopPolicy.decide(true, 7, 8, true,
            LinkState.Negotiating("等待邀请"), true))
        assertEquals(IGNORE_STALE, ConnectionStopPolicy.decide(true, 7, 8, true,
            LinkState.Connected("car", 1), true))
    }

    @Test fun lateStopAfterFailureCannotCreateACooldown() {
        for (active in listOf(false, true)) {
            assertEquals(IGNORE_INACTIVE, ConnectionStopPolicy.decide(true, 7, 7, active,
                LinkState.Failed("通道失败"), true))
        }
    }

    @Test fun duplicateStopAfterCleanupDoesNotExtendTenMinutePause() {
        for (link in listOf(LinkState.Idle, LinkState.Stopping)) {
            assertEquals(IGNORE_INACTIVE, ConnectionStopPolicy.decide(true, 7, 7, false, link, true))
        }
    }

    @Test fun startupSnapshotCannotStopAnAutomaticAttemptThatHasJustStarted() {
        assertEquals(IGNORE_STALE, ConnectionStopPolicy.decide(true, 0, 1, true,
            LinkState.Negotiating("检查权限"), true))
        assertEquals(IGNORE_INACTIVE, ConnectionStopPolicy.decide(true, 0, 0, false, LinkState.Idle, true))
    }

    @Test fun explicitStopOfCurrentConnectingOrConnectedSessionStillPauses() {
        for (link in listOf(LinkState.Negotiating("等待邀请"), LinkState.Connected("car", 1))) {
            assertEquals(STOP_AND_PAUSE, ConnectionStopPolicy.decide(true, 7, 7, true, link, true))
            assertEquals(STOP, ConnectionStopPolicy.decide(true, 7, 7, true, link, false))
        }
    }

    @Test fun transportFailureAndAclDisconnectNeverCreatePause() {
        for (link in listOf(LinkState.Negotiating("iAP2"), LinkState.Failed("通道失败"), LinkState.Idle)) {
            for (auto in listOf(false, true)) {
                assertEquals(STOP, ConnectionStopPolicy.decide(false, 7, 7, true, link, auto))
            }
        }
        assertEquals(IGNORE_STALE, ConnectionStopPolicy.decide(false, 7, 8, true,
            LinkState.Connected("car", 1), true))
    }
}
