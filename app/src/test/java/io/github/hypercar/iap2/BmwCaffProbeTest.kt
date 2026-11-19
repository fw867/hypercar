package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class BmwCaffProbeTest {
    @Test fun onlyAnAlreadyBondedId85TargetCanBeSelected() {
        val bonded = setOf("CAR", "BMW2", "HEADSET")
        val cars = setOf("CAR", "BMW2", "OLD")
        assertEquals("CAR", BmwCaffProbe.target("bmw_id85", "CAR", null, bonded, cars))
        assertEquals("BMW2", BmwCaffProbe.target("bmw_id85", null, "BMW2", bonded, cars))
        assertNull(BmwCaffProbe.target("bmw_id85", null, "HEADSET", bonded, cars))
        assertNull(BmwCaffProbe.target("bmw_id85", "OLD", null, bonded, cars))
        assertNull(BmwCaffProbe.target("bmw_id9", "CAR", null, bonded, cars))
    }
    @Test fun requiresContinuousClassicProfileAndOnlyQueriesOncePerAttempt() {
        val probe = BmwCaffProbe(4)
        assertFalse(probe.due(null, 0))
        assertFalse(probe.due("BMW", 1))
        assertFalse(probe.due(null, 2_000))
        assertFalse(probe.due("BMW", 3_000))
        assertFalse(probe.due("BMW", 5_999))
        assertTrue(probe.due("BMW", 6_000))
        assertFalse(probe.due("BMW", 20_000))
    }

    @Test fun inboundWinsDuringSdpAndDuringConnectOrHandover() {
        fun started() = BmwCaffProbe(1).also { it.due("BMW", 0); assertTrue(it.due("BMW", 3_000)) }
        val duringSdp = started()
        assertFalse(duringSdp.attach { fail() })
        duringSdp.cancel()
        assertFalse(duringSdp.recordSdp(true))
        assertFalse(duringSdp.attach { fail() })
        val absent = started()
        assertFalse(absent.recordSdp(false))
        assertFalse(absent.recordSdp(true))
        assertFalse(absent.attach { fail() })
        val connecting = started()
        var closes = 0
        assertTrue(connecting.recordSdp(true))
        assertTrue(connecting.attach { closes++ })
        assertFalse(connecting.attach { fail() })
        connecting.cancel()
        connecting.cancel()
        assertEquals(1, closes)
        assertFalse(connecting.promote())
        val connected = started()
        assertTrue(connected.recordSdp(true))
        assertTrue(connected.attach { fail("established session belongs to the existing handshake") })
        assertTrue(connected.promote())
        connected.cancel()
        assertFalse(connected.promote())
    }
}
