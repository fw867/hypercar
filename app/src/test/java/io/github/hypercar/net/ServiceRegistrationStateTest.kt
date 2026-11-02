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
}
