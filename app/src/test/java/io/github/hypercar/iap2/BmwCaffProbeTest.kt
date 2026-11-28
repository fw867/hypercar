package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class BmwCaffProbeTest {
    private fun BmwCaffProbe.due(peer: String?, now: Long) = poll(peer, now, false) == BmwCaffProbe.Decision.DIAL
    private val wait = BmwCaffProbe.Decision.WAIT
    private val extended = BmwCaffProbe.Decision.ENTER_CTRL_GRACE
    private val dial = BmwCaffProbe.Decision.DIAL

    @Test fun capturedCloseStaysWithDialAfterPromotion() {
        val probe = BmwCaffProbe(1)
        assertEquals(dial, probe.poll("BMW", 0, false, manual = true))
        assertTrue(probe.recordSdp(true))
        var closed = false
        val owners = RfcommConnections<java.io.Closeable>()
        val dialSocket = owners.register(java.io.Closeable { closed = true })
        assertTrue(probe.attach(dialSocket::cancelSocket))
        val stopOld = owners.captureClose()
        assertTrue(probe.promote())
        owners.admit(dialSocket)
        probe.cancel()
        stopOld.noteLocalStop()
        assertTrue(dialSocket.localStopRequested)
        assertFalse(closed)
        stopOld.close()
        assertTrue(closed)
    }

    @Test fun healthyCtrlLetsRepeatedShortClassicWindowsPassWithoutConsumingDial() {
        val p = BmwCaffProbe(1)
        assertEquals(extended, p.poll("BMW", 0, true))
        assertEquals(wait, p.poll("BMW", 10_000, true))
        assertEquals(wait, p.poll(null, 11_000, true))
        assertEquals(extended, p.poll("BMW", 18_000, true))
        assertEquals(wait, p.poll("BMW", 32_999, true))
        assertFalse(p.attempted)
        assertEquals(dial, p.poll("BMW", 33_000, true))
        assertEquals(wait, p.poll("BMW", 60_000, false))
    }

    @Test fun lostCtrlRestoresOrdinaryGraceAndNetworkChurnCannotStarvePersistentPeer() {
        val p = BmwCaffProbe(1)
        p.poll("BMW", 0, true)
        assertEquals(wait, p.poll("BMW", 5_000, false))
        assertEquals(wait, p.poll("BMW", 7_999, false))
        assertEquals(dial, p.poll("BMW", 8_000, false))
        val q = BmwCaffProbe(2)
        q.poll("BMW", 0, false)
        assertEquals(extended, q.poll("BMW", 2_999, true))
        assertEquals(wait, q.poll("BMW", 14_999, false))
        assertEquals(dial, q.poll("BMW", 15_000, true))
    }

    @Test fun manualDialSkipsGraceButStillRequiresOnlinePeerAndConsumesOneOpportunity() {
        val p = BmwCaffProbe(1)
        assertEquals(wait, p.poll(null, 100, ctrlReady = true, manual = true))
        assertFalse(p.attempted)
        assertEquals(dial, p.poll("BMW", 101, ctrlReady = true, manual = true))
        assertTrue(p.attempted)
        assertEquals(wait, p.poll("BMW", 102, ctrlReady = true, manual = true))
        assertEquals(wait, p.poll("BMW", 20_000, ctrlReady = false))
    }

    @Test fun cancelledProbeCannotBeStartedManually() {
        val p = BmwCaffProbe(1)
        p.cancel()
        assertEquals(wait, p.poll("BMW", 101, ctrlReady = false, manual = true))
        assertFalse(p.attempted)
    }

    @Test fun realInviteOrInboundCancelsEvenBeforeExtendedGraceExpires() {
        val p = BmwCaffProbe(1)
        assertEquals(extended, p.poll("BMW", 0, true))
        p.cancel()
        assertEquals(wait, p.poll("BMW", 20_000, false))
        assertFalse(p.attempted)
    }
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
