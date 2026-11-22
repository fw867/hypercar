package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class MdnsSourceLogGateTest {
    @Test fun duplicateSourcesDoNotConsumeTheWindowAndSuppressedSourcesCanRetryLater() {
        var now = 0L
        val gate = MdnsSourceLogGate { now }
        assertTrue(gate.admit("source-0"))
        repeat(100) { assertFalse(gate.admit("source-0")) }
        for (i in 1..23) assertTrue(gate.admit("source-$i"))
        assertFalse(gate.admit("later"))
        now = 15_000
        assertTrue(gate.admit("later"))
        assertFalse(gate.admit("source-0"))
    }

    @Test fun observerLifetimeCapsHistoryEvenAcrossUnlimitedWindows() {
        var now = 0L
        val gate = MdnsSourceLogGate { now }
        repeat(64) { i -> now = i * 15_000L; assertTrue(gate.admit("source-$i")) }
        repeat(100) { i -> now += 15_000; assertFalse(gate.admit("overflow-$i")) }
        assertFalse(gate.admit("source-0"))
    }
}
