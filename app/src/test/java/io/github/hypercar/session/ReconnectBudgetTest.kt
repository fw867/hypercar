package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class ReconnectBudgetTest {
    @Test fun briefSuccessesAndFailedRetriesShareOneBoundedBudget() {
        val b = ReconnectBudget()
        assertNull(b.failed("bmw:a", false, 0))
        val delays = listOf(2000L, 5000L, 10000L, 30000L, 60000L)
        delays.forEachIndexed { i, delay ->
            // Some retries fail before connected; others briefly write video.
            if (i % 2 == 0) b.videoReady("bmw:a", i * 100L)
            val next = b.failed("bmw:a", i == 0, i * 100L + 99)!!
            assertEquals(i + 1, next.number)
            assertEquals(delay, next.delayMs)
        }
        assertNull(b.failed("bmw:a", true, 900))
        assertFalse(b.mayWake("bmw:a"))
        assertTrue(b.mayWake("diplay:b"))
    }
    @Test fun onlyStableVideoReplenishesTheBudgetAndRepeatedProgressDoesNotRestartTheClock() {
        val b = ReconnectBudget()
        assertEquals(1, b.failed("a", true, 0)!!.number)
        b.videoReady("a", 100)
        b.videoReady("a", 60_000)
        assertEquals(2, b.failed("a", true, 120_099)!!.number)
        b.videoReady("a", 200_000)
        assertEquals(1, b.failed("a", true, 320_000)!!.number)
    }
    @Test fun controlOnlySuccessDoesNotReplenishAndManualStartRemainsAvailable() {
        val b = ReconnectBudget()
        repeat(5) { assertNotNull(b.failed("a", true, it.toLong())) }
        b.start("a")
        assertNull(b.failed("a", true, 900_000))
        b.start("a", manual = true)
        assertTrue(b.mayWake("a"))
        assertNull(b.failed("a", false, 900_001))
        assertEquals(1, b.failed("a", true, 900_002)!!.number)
    }
    @Test fun changingCarsAndDisablingAutomaticModeClearTheOldChain() {
        val b = ReconnectBudget()
        b.failed("a", true, 0)
        b.start("b")
        assertNull(b.failed("b", false, 1))
        assertEquals(1, b.failed("b", true, 2)!!.number)
        b.clear()
        assertNull(b.failed("b", false, 3))
    }
}
