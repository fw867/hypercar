package io.github.hypercar.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProactiveActivationTest {
    @Test fun repeatedClosureCannotEscapeDuringCleanupOrEraseFirstReason() = runBlocking {
        val activation = ProactiveActivation()
        assertTrue(activation.interceptTerminal("control EOF"))
        assertTrue(activation.interceptTerminal("event closed"))
        activation.videoReady() // Late callback from the rejected engine.
        assertTrue(activation.rejected)
        assertEquals("control EOF", activation.result.await())
    }

    @Test fun establishedVideoUsesNormalDisconnectionHandling() = runBlocking {
        val activation = ProactiveActivation()
        activation.videoReady()
        assertNull(activation.result.await())
        assertFalse(activation.interceptTerminal("later EOF"))
        assertFalse(activation.rejected)
    }
}
