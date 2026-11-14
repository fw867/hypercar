package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class ServiceRegistrationStateTest {
    @Test fun requestSubmissionIsNotBroadcastReadiness() {
        val state = ServiceRegistrationState()
        val token = state.begin()
        assertTrue(state.pending)
        assertFalse(state.ready)
        assertTrue(state.registered(token))
        assertTrue(state.ready)
        assertFalse(state.pending)
    }

    @Test fun lateCallbacksCannotChangeNewNetworkRegistration() {
        val state = ServiceRegistrationState()
        val old = state.begin()
        state.invalidate()
        val current = state.begin()
        assertFalse(state.registered(old))
        assertFalse(state.failed(old))
        assertTrue(state.pending)
        assertTrue(state.registered(current))
        assertFalse(state.failed(old))
        assertTrue(state.ready)
    }

    @Test fun successAfterStopCannotResurrectAdvertisement() {
        val state = ServiceRegistrationState()
        val token = state.begin()
        state.invalidate()
        assertFalse(state.registered(token))
        assertFalse(state.ready)
    }

    @Test fun failureCannotBeFollowedBySuccessForTheSameRequest() {
        val state = ServiceRegistrationState()
        val token = state.begin()
        assertTrue(state.failed(token))
        assertFalse(state.registered(token))
        val retry = state.begin()
        assertTrue(state.registered(retry))
    }
    @Test fun healthyRegistrationSurvivesAclEdgesAndLongInviteWait() {
        val state = ServiceRegistrationState()
        val token = state.begin()
        assertTrue(state.registered(token))
        // Replay short ACL connect/disconnect windows and periodic 30s polls from Nov 14.
        for (age in listOf(834L, 6_952L, 11_264L, 30_000L, 60_000L, 240_000L, 600_000L)) {
            assertTrue(state.canReuse(sameIdentity = true, ageMillis = age, pendingTimeoutMillis = 10_000))
            assertEquals(token, state.currentGeneration)
            assertTrue(state.ready)
        }
    }

    @Test fun pendingProbeSurvivesAclButTimeoutFailureAndIdentityChangeAllowRepair() {
        val state = ServiceRegistrationState()
        val old = state.begin()
        assertTrue(state.canReuse(true, 0, 10_000))
        assertTrue(state.canReuse(true, 9_999, 10_000))
        assertFalse(state.canReuse(true, 10_000, 10_000))
        assertFalse(state.canReuse(false, 1, 10_000))
        assertTrue(state.registered(old))
        assertFalse(state.canReuse(false, 1, 10_000))
        assertTrue(state.failed(old))
        assertFalse(state.canReuse(true, 1, 10_000))
        val replacement = state.begin()
        assertFalse(state.registered(old))
        assertTrue(state.registered(replacement))
        state.invalidate()
        assertFalse(state.canReuse(true, 1, 10_000))
    }

}
