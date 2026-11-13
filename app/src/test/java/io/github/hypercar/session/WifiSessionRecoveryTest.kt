package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class WifiSessionRecoveryTest {
    private val car = "bmw:bt_112233445566"
    private val ssid = "DIRECT-BMW"

    @Test fun first_repair_is_immediate_and_only_actual_reservation_consumes_it() {
        val r = WifiSessionRecovery()
        val delay = ProactiveWifiSessionDelay()
        assertFalse(r.firstAttempt(car, ssid, 0))
        assertEquals(2_000L, r.reconnectDelay(car, 0, 2_000))
        r.mediaWorking(car, ssid, true); r.interrupted(car, ssid, 1_000)
        assertEquals(0L, r.reconnectDelay(car, 1_000, 2_000))
        repeat(3) {
            assertTrue(r.firstAttempt(car, ssid, 1_000))
            assertTrue(delay.ready(true, "130", 1_000, immediate = r.firstAttempt(car, ssid, 1_000)))
        }
        assertFalse(delay.ready(false, "130", 1_000, immediate = true))
        assertFalse(delay.ready(true, null, 1_000, immediate = true))
        assertTrue(delay.ready(true, "130", 1_000, immediate = true))
        assertTrue(r.reserve(car, ssid, 1_000))
        assertFalse(r.firstAttempt(car, ssid, 1_000))
        r.interrupted(car, ssid, 1_001)
        assertEquals(5_000L, r.reconnectDelay(car, 1_001, 5_000))
        assertFalse(delay.ready(true, "130", 1_001))
        assertFalse(delay.ready(true, "130", 10_999))
        assertTrue(delay.ready(true, "130", 11_000))
        assertFalse(r.firstAttempt(car, ssid, 121_000))
        assertEquals(30_000L, r.reconnectDelay(car, 121_000, 30_000))
    }

    @Test fun pairing_control_and_proactive_video_never_authorize_initial_connection() {
        val r = WifiSessionRecovery()
        assertFalse(r.allows(car, ssid, 0))
        r.mediaWorking(car, ssid, receiverInitiated = false)
        r.interrupted(car, ssid, 100)
        assertFalse(r.allows(car, ssid, 101))
        assertFalse(r.reserve(car, ssid, 101))
        // Merely waiting overnight with an old pairing never creates permission.
        assertFalse(r.allows(car, ssid, 86_400_000))
    }

    @Test fun only_interruption_of_receiver_initiated_media_opens_two_minute_window() {
        val r = WifiSessionRecovery()
        r.mediaWorking(car, ssid, receiverInitiated = true)
        assertFalse(r.allows(car, ssid, 100))
        r.interrupted(car, ssid, 1_000)
        assertTrue(r.allows(car.uppercase(), ssid, 1_001))
        assertTrue(r.allows(car, ssid, 120_999))
        assertFalse(r.allows(car, ssid, 121_000))
    }

    @Test fun failed_recovery_cannot_extend_deadline_or_replenish_five_attempts() {
        val r = WifiSessionRecovery()
        r.mediaWorking(car, ssid, true); r.interrupted(car, ssid, 0)
        repeat(5) { i ->
            repeat(30) { assertTrue(r.allows(car, ssid, i.toLong())) }
            assertTrue(r.reserve(car, ssid, i.toLong()))
            r.mediaWorking(car, ssid, false)
            r.interrupted(car, ssid, i + 1L)
        }
        assertFalse(r.reserve(car, ssid, 10))
        assertFalse(r.allows(car, ssid, 10))
        val deadline = WifiSessionRecovery()
        deadline.mediaWorking(car, ssid, true); deadline.interrupted(car, ssid, 0)
        deadline.reserve(car, ssid, 90_000)
        deadline.mediaWorking(car, ssid, false); deadline.interrupted(car, ssid, 119_000)
        assertFalse(deadline.allows(car, ssid, 120_000))
    }

    @Test fun stop_restart_different_receiver_and_different_network_invalidate_permission() {
        for ((receiver, network) in listOf(car to "home", "audi:bt_112233445566" to ssid, "bmw:bt_aabbccddeeff" to ssid)) {
            val r = WifiSessionRecovery()
            r.mediaWorking(car, ssid, true); r.interrupted(car, ssid, 0)
            assertFalse(r.allows(receiver, network, 1))
            assertFalse(r.allows(car, ssid, 2))
        }
        val r = WifiSessionRecovery()
        r.mediaWorking(car, ssid, true); r.interrupted(car, ssid, 0); r.clear()
        assertFalse(r.allows(car, ssid, 1))
        assertFalse(WifiSessionRecovery().allows(car, ssid, 1))
    }

    @Test fun transient_network_loss_can_rebind_same_receiver_but_clock_reversal_expires() {
        val r = WifiSessionRecovery()
        r.mediaWorking(car, ssid, true); r.interrupted(car, ssid, 1_000)
        assertFalse(r.allows(null, null, 1_001))
        assertTrue(r.allows(car, ssid, 1_002))
        assertFalse(r.allows(car, ssid, 999))
    }
}
