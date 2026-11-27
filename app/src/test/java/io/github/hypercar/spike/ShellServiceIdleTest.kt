package io.github.hypercar.spike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellServiceIdleTest {
    @Test fun eachCompletedTransactionRestartsTheIdleWindow() {
        val idle = ShellServiceIdle(startedAt = 100, timeoutMs = 180_000)
        assertFalse(idle.eligibleAt(180_099))
        assertTrue(idle.begin())
        assertFalse(idle.eligibleAt(400_000))
        idle.end(400_000)
        assertFalse(idle.tryRetire(579_999))
        assertTrue(idle.tryRetire(580_000))
        assertFalse(idle.begin())
    }

    @Test fun workThatStartsDuringTheResourceCheckPreventsRetirement() {
        val idle = ShellServiceIdle(startedAt = 0, timeoutMs = 180_000)
        val checkedAt = 180_000L
        assertTrue(idle.eligibleAt(checkedAt))
        assertTrue(idle.begin())
        idle.end(checkedAt)
        assertFalse(idle.tryRetire(checkedAt))
        assertTrue(idle.begin())
        idle.stop()
        assertFalse(idle.begin())
        idle.end(checkedAt)
        assertFalse(idle.tryRetire(checkedAt + 180_000))
    }

    @Test fun aResourceCanExtendIdleWithoutAnyClientTransactions() {
        val idle = ShellServiceIdle(startedAt = 0, timeoutMs = 180_000)
        idle.noteBusy(179_999)
        assertFalse(idle.tryRetire(180_000))
        assertTrue(idle.tryRetire(359_999))
    }
}
