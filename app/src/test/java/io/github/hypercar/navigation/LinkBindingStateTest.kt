package io.github.hypercar.navigation

import org.junit.Assert.*
import org.junit.Test

class LinkBindingStateTest {
    @Test fun failedStartupCanRetryButCannotLoopForever() {
        val state = LinkBindingState()
        repeat(8) { index ->
            val token = state.begin()!!
            assertEquals((2000L * (index + 1)).coerceAtMost(15_000), state.retryDelayMillis)
            state.invalidate(token)
            assertFalse(state.accepts(token))
        }
        assertNull(state.begin())
    }
    @Test fun oldServiceCannotRegisterOrPublishIntoTheNewConnection() {
        val state = LinkBindingState()
        val old = state.begin()!!
        state.invalidate(old)
        val current = state.begin()!!
        state.registered(old)
        assertEquals(2, state.attempts)
        assertFalse(state.accepts(old))
        assertTrue(state.accepts(current))
        state.registered(current)
        assertEquals(0, state.attempts)
    }
    @Test fun mapProcessRestartHasAFreshBudgetAndStopRejectsLateCallbacks() {
        val state = LinkBindingState()
        val token = state.begin()!!
        state.registered(token)
        state.invalidate(token)
        val reconnect = state.begin()!!
        assertEquals(1, state.attempts)
        state.close()
        assertFalse(state.accepts(token))
        assertFalse(state.accepts(reconnect))
        state.registered(reconnect)
        assertEquals(1, state.attempts)
        assertNull(state.begin())
    }
    @Test fun mapOpenedAfterStartupRetriesExpireCanStillBind() {
        val state = LinkBindingState()
        repeat(8) { state.invalidate(state.begin()!!) }
        assertNull(state.begin())
        state.mapStarted()
        assertNotNull(state.begin())
        state.close()
        state.mapStarted()
        assertNull(state.begin())
    }
}
