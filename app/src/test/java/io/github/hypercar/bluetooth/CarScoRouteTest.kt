package io.github.hypercar.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarScoRouteTest {
    @Test fun connectedBroadcastBlocksNewOffersEvenWithoutAProfileReading() {
        val route = CarScoRoute("AA:BB:CC:DD:EE:FF")
        val queued = route.generation
        assertFalse(route.connected("11:22:33:44:55:66"))
        assertTrue(route.mayStart(queued) { false })
        assertTrue(route.connected("aa:bb:cc:dd:ee:ff"))

        assertFalse(route.mayStart(queued) { false })
        assertFalse(route.mayStart(route.generation) { false })
        route.disconnected("11:22:33:44:55:66")
        assertFalse(route.mayStart(route.generation) { false })
        route.disconnected("aa:bb:cc:dd:ee:ff")
        assertFalse(route.mayStart(queued) { false })
        assertTrue(route.mayStart(route.generation) { false })
    }

    @Test fun connectedBroadcastNotifiesRevocationBeforeTheMonitorQueriesSco() {
        val receiver = "AA:BB:CC:DD:EE:FF"
        val revoked = mutableListOf<Long>()
        val route = CarScoRoute(receiver, revoked::add)
        val queued = route.generation
        assertFalse(route.connected("11:22:33:44:55:66"))
        assertTrue(revoked.isEmpty())

        route.connected(receiver)
        route.connected(receiver)
        assertTrue(revoked == listOf(queued + 1))
    }

    @Test fun profileReconnectCanClearStaleScoWithoutOverridingANewBroadcast() {
        val receiver = "AA:BB:CC:DD:EE:FF"
        val route = CarScoRoute(receiver)
        route.connected(receiver)

        assertNull(route.reconcileAfterProfileReconnect(route.snapshot(), profileSco = false, selectedSco = null))
        assertTrue(route.reportedConnected)
        assertEquals(true, route.reconcileAfterProfileReconnect(route.snapshot(), profileSco = false, selectedSco = true))

        val before = route.snapshot()
        route.connected(receiver)
        assertNull(route.reconcileAfterProfileReconnect(before, profileSco = false, selectedSco = false))
        assertTrue(route.reportedConnected)
        assertEquals(false, route.reconcileAfterProfileReconnect(route.snapshot(), profileSco = false, selectedSco = false))
        assertFalse(route.reportedConnected)
        assertTrue(route.mayStart(route.generation) { false })
    }

    @Test fun activationRechecksLiveScoAndRejectsEventsDuringTheQuery() {
        val receiver = "AA:BB:CC:DD:EE:FF"
        val route = CarScoRoute(receiver)
        val queued = route.generation
        assertFalse(route.mayStart(queued) { true })
        assertFalse(route.mayStart(queued) { false })
        assertFalse(route.mayStart(route.generation) {
            route.connected(receiver)
            false
        })
    }
}
