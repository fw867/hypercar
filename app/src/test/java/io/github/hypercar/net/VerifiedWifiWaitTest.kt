package io.github.hypercar.net

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedWifiWaitTest {
    @Test fun associatedWifiCanWaitForLateAddressesWithinTheOriginalRequestDeadline() = runTest {
        val budget = VerifiedWifiWait.addressBudget(30_000, associated = true)
        val result = VerifiedWifiWait.await(budget, { testScheduler.currentTime }, { true }) {
            "car-network".takeIf { testScheduler.currentTime >= 4_000 }
        }
        assertEquals("car-network", result)
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(12_000L, budget)
        assertEquals(2_500L, VerifiedWifiWait.addressBudget(2_500, associated = true))
        assertEquals(3_000L, VerifiedWifiWait.addressBudget(30_000, associated = false))
        assertEquals(0L, VerifiedWifiWait.addressBudget(-1, associated = true))
    }

    @Test fun sameSsidPasswordChangeStopsAnOutstandingRequestBeforeItsSystemTimeout() = runTest {
        val request = CarWifiRequestIdentity("car", "old-passphrase")
        val association = WifiRequestAssociation<Any, String>()
        val old = Any()
        assertTrue(association.register(old))
        var newestPassword = "old-passphrase"
        launch {
            delay(250)
            newestPassword = "new-passphrase"
        }
        val current = VerifiedWifiWait.awaitCallback(60_000, { testScheduler.currentTime },
            credentialsCurrent = { !request.requiresCredentialUpdate("car", newestPassword) },
            poll = {
                delay(it)
                testScheduler.currentTime == 400L && association.available(old, "old-network")
            })
        if (!current) association.release()
        assertFalse(current)
        assertEquals(400L, testScheduler.currentTime)
        assertFalse(association.available(old, "late-old-network"))
        assertTrue(association.register(Any()))
    }

    @Test fun unavailable_at_twenty_seconds_can_recover_a_later_verified_network() = runTest {
        testScheduler.advanceTimeBy(20_106)
        val result = VerifiedWifiWait.await(3_000, { testScheduler.currentTime }, { true }) {
            "car-network".takeIf { testScheduler.currentTime >= 20_706 }
        }
        assertEquals("car-network", result)
        assertEquals(20_706L, testScheduler.currentTime)
    }

    @Test fun available_callback_waits_for_address_and_capability_publication() = runTest {
        val result = VerifiedWifiWait.await(3_000, { testScheduler.currentTime }, { true }) {
            val link = CarNetworkReuse.Evidence("car", "aa:bb:cc:dd:ee:ff",
                hasAddress = testScheduler.currentTime >= 400, routeToPeer = true, pinned = false)
            "car-network".takeIf { CarNetworkReuse.accepts("car", link.bssid, false, link) }
        }
        assertEquals("car-network", result)
        assertEquals(400L, testScheduler.currentTime)
    }

    @Test fun association_does_not_bypass_internet_safety_or_wrong_ap_checks() = runTest {
        for ((safe, bssid) in listOf(false to "aa:bb:cc:dd:ee:ff", true to "aa:bb:cc:dd:ee:00")) {
            val result = VerifiedWifiWait.await(600, { testScheduler.currentTime }, { true }) {
                val link = CarNetworkReuse.Evidence("car", bssid, true, true, false)
                "wrong-network".takeIf { CarWifiPolicy.safe(internet = !safe, validated = false) &&
                    CarNetworkReuse.accepts("car", "aa:bb:cc:dd:ee:ff", false, link) }
            }
            assertNull(result)
        }
        assertEquals(1_200L, testScheduler.currentTime)
    }

    @Test fun a_replaced_request_cannot_adopt_a_late_network() = runTest {
        var current = true
        try {
            VerifiedWifiWait.await(3_000, { testScheduler.currentTime }, { current }) {
                current = false
                "late-network"
            }
            fail("stale request returned a network")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun slow_verification_cannot_extend_the_grace_budget() = runTest {
        val result = VerifiedWifiWait.await(3_000, { testScheduler.currentTime }, { true }) {
            delay(10_000)
            "late-network"
        }
        assertNull(result)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun deadline_still_performs_one_final_check_without_sleeping() = runTest {
        assertEquals("ready", VerifiedWifiWait.await(0, { testScheduler.currentTime }, { true }) { "ready" })
        assertNull(VerifiedWifiWait.await<String>(0, { testScheduler.currentTime }, { true }) { null })
        assertEquals(0L, testScheduler.currentTime)
    }
}
