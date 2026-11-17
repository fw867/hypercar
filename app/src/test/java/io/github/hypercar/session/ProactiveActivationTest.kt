package io.github.hypercar.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProactiveActivationTest {
    @Test fun failureRequiresAnInvitationAndRepeatedClosureCannotUndoIt() = runBlocking {
        val activation = ProactiveActivation()
        activation.interceptTerminal("control EOF")
        activation.interceptTerminal("event closed")
        assertTrue(activation.requiresFreshInvitation)
        assertEquals("control EOF", activation.result.await())
    }

    @Test fun transportFailureBeforeRecordAndMediaFailureAfterResourcesRequireNewInvitation() {
        val before = ProactiveActivation()
        before.interceptTerminal("connect failed")
        assertTrue(before.requiresFreshInvitation)
        val after = ProactiveActivation()
        after.interceptTerminal("screen SETUP failed")
        assertTrue(after.requiresFreshInvitation)
    }

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
