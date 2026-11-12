package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class WifiRequestRegistrationTest {
    @Test fun abortBeforeFactoryActivationDoesNotEndTheJoin() {
        val gate=WifiRequestRegistration()
        assertTrue(gate.beginAttempt())
        assertTrue(gate.retryAfterEarlyAbort())
        assertTrue(gate.beginAttempt())
        gate.acknowledge()
        assertFalse(gate.retryAfterEarlyAbort())
        assertFalse(gate.beginAttempt())
    }
    @Test fun registrationRetryRemainsBounded() {
        val gate=WifiRequestRegistration(3)
        repeat(3) { assertTrue(gate.beginAttempt()) }
        assertFalse(gate.retryAfterEarlyAbort())
        assertFalse(gate.beginAttempt())
    }
}
