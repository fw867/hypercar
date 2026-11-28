package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class ReconnectBudgetTest {
    @Test fun actualBluetoothOrEngineReceiverSupersedesThePassiveWifiHint() {
        val budget = ReconnectBudget()
        val hint = "bmw_id85:bt_021122334466"
        val actual = "bmw_id85:bt_021122334477"
        repeat(ReconnectBudget.MAX_RETRIES) { budget.failed(hint, true, it.toLong()) }
        budget.grantWifiWakeAfterProjectionSwitch()
        val pending = budget.reserveWifiWake(hint, 41L) as ReconnectBudget.WifiWake.Extra
        assertEquals(hint, ReconnectBudget.receiverForRetry("bmw_id85", null, null, hint, null))
        val confirmed = ReconnectBudget.receiverForRetry("bmw_id85", null, "02:11:22:33:44:77", hint, null)
        assertEquals(actual, confirmed)
        assertEquals(actual, ReconnectBudget.receiverForRetry("bmw_id85", "02:11:22:33:44:77", null, hint, null))
        budget.videoReady(confirmed, 100)
        assertFalse(budget.commitWifiWake(pending, 41L))
        assertEquals(1, budget.failed(confirmed, true, 200)!!.number)
    }

    @Test fun switchingBackGrantsOnlyOneWifiWakeWithoutReplenishingReconnectRetries() {
        val budget = ReconnectBudget()
        repeat(ReconnectBudget.MAX_RETRIES) { budget.failed("bmw_id85:a", true, it.toLong()) }
        budget.grantWifiWakeAfterProjectionSwitch()
        assertSame(ReconnectBudget.WifiWake.Regular, budget.reserveWifiWake("bmw_id85:b", verifiedNetwork = 10L))
        assertTrue(budget.mayWake("bmw_id85:a"))
        val reserved = budget.reserveWifiWake("bmw_id85:a", verifiedNetwork = 10L) as ReconnectBudget.WifiWake.Extra
        assertNull(budget.reserveWifiWake("bmw_id85:a", verifiedNetwork = 10L))
        assertTrue(budget.commitWifiWake(reserved, boundNetwork = 10L))
        assertNull(budget.reserveWifiWake("bmw_id85:a", verifiedNetwork = 10L))
        assertNull(budget.failed("bmw_id85:a", true, 100L))
    }

    @Test fun staleWifiWakeDoesNotSpendTheProjectionSwitchOpportunity() {
        val budget = ReconnectBudget()
        val receiver = "bmw_id85:a"
        repeat(ReconnectBudget.MAX_RETRIES) { budget.failed(receiver, true, it.toLong()) }
        budget.grantWifiWakeAfterProjectionSwitch()

        assertNull(budget.reserveWifiWake(receiver, verifiedNetwork = null))
        assertTrue(budget.mayWake(receiver))
        val unbound = budget.reserveWifiWake(receiver, verifiedNetwork = 10L) as ReconnectBudget.WifiWake.Extra
        assertFalse(budget.commitWifiWake(unbound, boundNetwork = 11L))
        assertTrue(budget.releaseWifiWake(unbound))
        assertTrue(budget.mayWake(receiver))
        val bound = budget.reserveWifiWake(receiver, verifiedNetwork = 11L) as ReconnectBudget.WifiWake.Extra
        assertFalse(budget.commitWifiWake(unbound, boundNetwork = 10L))
        assertFalse(budget.releaseWifiWake(unbound))
        assertTrue(budget.commitWifiWake(bound, boundNetwork = 11L))
        assertFalse(budget.commitWifiWake(bound, boundNetwork = 11L))
        assertNull(budget.reserveWifiWake(receiver, verifiedNetwork = 11L))
    }

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
